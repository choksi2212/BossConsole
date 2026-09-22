-- Plugin version row lifecycle: a row is only "published" once the JAR has
-- actually been uploaded and finalized.
--
-- Reopens #912. Before this, `createVersion` inserted the row with the
-- schema default of `published_at = NOW()`, which made every consumer that
-- ordered by `published_at` (notably `getLatestVersion`) treat a half-baked
-- row as the latest version of the plugin. Anyone asking for "latest" got a
-- row whose `sha256` was the literal string `pending`, whose `jar_size` was
-- zero, and whose `jar_path` pointed at a storage key that did not yet exist
-- - or worse, one a previous failed finalize had left populated, so the old
-- artifact got served under the new version anchor.
--
-- The fix is two phases:
--
--   1. New rows are inserted in `status = 'pending'`, with `published_at`
--      explicitly NULL. They are invisible to `getLatestVersion` and to the
--      version list the storefront renders.
--
--   2. `finalizeVersion` flips the row to `status = 'published'` and sets
--      `published_at` to the finalize time, in the same update that records
--      the real `sha256`, `jar_size` and store signature. The row becomes
--      eligible for "latest" only then.
--
-- `created_at` is added so a future reaper can drop pending rows that were
-- never finalized (a publish that died before finalize). The reaper is out of
-- scope for this migration - the column is here for it.
--
-- Migration ordering matters: the column is added NULLABLE and with no
-- default, existing rows are backfilled to 'published' first, and only then
-- does the default flip to 'pending' and the NOT NULL constraint land. That
-- way every existing row reads as 'published' from the instant the column
-- exists, and a query that races the migration cannot see them as 'pending'.

alter table plugin_versions
    add column if not exists status text,
    add column if not exists created_at timestamptz;

update plugin_versions
    set status = 'published'
    where status is null;

-- Backfill `created_at` from `published_at` (which was always populated on
-- existing rows because of the old `DEFAULT NOW()`) and fall back to `now()`
-- only for the rare row whose `published_at` was nulled. Without this
-- backfill, the `alter column created_at set not null` below fails on any
-- existing row with a NULL `created_at`. Review feedback for #912.
update plugin_versions
    set created_at = coalesce(published_at, now())
    where created_at is null;

alter table plugin_versions
    alter column status set default 'pending',
    alter column status set not null,
    alter column created_at set default now(),
    alter column created_at set not null;

alter table plugin_versions
    drop constraint if exists plugin_versions_status_check,
    add constraint plugin_versions_status_check check (status in ('pending', 'published'));

-- `published_at` was a `DEFAULT NOW()` column with no NOT NULL. The default
-- is what bit us: every inserted row got a publish time even when no JAR had
-- been uploaded. Drop both, so the column is set only by finalize.
alter table plugin_versions
    alter column published_at drop default,
    alter column published_at drop not null;

-- The "latest version" lookup orders by `published_at DESC NULLS LAST` and
-- filters to `status = 'published'`. A partial index covers the common case
-- (browse, download) without paying for pending rows in the index.
create index if not exists idx_plugin_versions_published_latest
    on plugin_versions (plugin_id, published_at desc nulls last)
    where status = 'published';

comment on column plugin_versions.status is
    'Row lifecycle: pending = row inserted but JAR not yet finalized (NOT eligible for getLatestVersion); published = JAR uploaded and finalized, eligible for getLatestVersion';
comment on column plugin_versions.created_at is
    'Insertion time; used by a future reaper to drop pending rows whose publish was abandoned before finalize';

-- ============================================================================
-- SECTION: latest-version resolution paths
-- ============================================================================
-- Review feedback for #912: the partial index is not enough on its own.
-- Every consumer that resolves "the latest version" still has to read the
-- `status = 'published'` filter, otherwise a half-finalized row whose
-- `published_at` was set first (or a fresh `pending` row) wins ordering by
-- `published_at DESC NULLS LAST`. The functions and view below are the only
-- such resolution surfaces; everything else is reached through these.
--
-- All bodies are reproduced verbatim from their defining migrations with one
-- change: the subquery on `plugin_versions` adds `pv.status = 'published'`.
-- Return types and signatures are unchanged, so CREATE OR REPLACE is safe
-- (the only call that changed return type was `get_plugin_with_stats`, which
-- was already a DROP+CREATE in 20260803000000 - we keep that pattern).
-- ============================================================================

-- ---- plugins_with_latest_version view -------------------------------
-- Originally defined in 20260307000001 (definition) and re-stamped in
-- 20260307000002 (SECURITY INVOKER). Either migration is still applied in
-- any deployed database, so we re-stamp the full definition here; the view
-- definition is identical except for the new `pv.status = 'published'` filter.
CREATE OR REPLACE VIEW public.plugins_with_latest_version AS
SELECT
    p.*,
    lv.version AS latest_version,
    lv.min_boss_version AS latest_min_boss_version,
    lv.published_at AS latest_published_at
FROM public.plugins p
LEFT JOIN LATERAL (
    SELECT pv.version, pv.min_boss_version, pv.published_at
    FROM public.plugin_versions pv
    WHERE pv.plugin_id = p.id
      AND pv.status = 'published'
    ORDER BY pv.published_at DESC NULLS LAST, pv.id DESC
    LIMIT 1
) lv ON true;

GRANT SELECT ON public.plugins_with_latest_version TO authenticated;
GRANT SELECT ON public.plugins_with_latest_version TO anon;

COMMENT ON VIEW public.plugins_with_latest_version IS 'Denormalized view of plugins with their latest PUBLISHED version info (status=published). Eliminates N+1 queries in plugin store listing.';

-- ---- search_plugins -------------------------------------------------
-- Original definition in 20260130000000 (extended in 20260630000000 to
-- include requiredPermissions). CREATE OR REPLACE is safe; return type is
-- the same TABLE(plugins JSONB, total_count BIGINT).
CREATE OR REPLACE FUNCTION search_plugins(
    p_query TEXT DEFAULT '',
    p_type TEXT DEFAULT NULL,
    p_tags TEXT[] DEFAULT NULL,
    p_min_rating NUMERIC DEFAULT 0,
    p_verified_only BOOLEAN DEFAULT FALSE,
    p_page INT DEFAULT 1,
    p_page_size INT DEFAULT 20,
    p_sort_by TEXT DEFAULT 'downloads'
)
RETURNS TABLE (
    plugins JSONB,
    total_count BIGINT
) AS $$
DECLARE
    v_offset INT;
    v_plugins JSONB;
    v_total BIGINT;
BEGIN
    v_offset := (p_page - 1) * p_page_size;

    -- Get total count
    SELECT COUNT(*)::BIGINT INTO v_total
    FROM plugins p
    WHERE p.published = true
    AND (
        p_query = ''
        OR to_tsvector('english', p.display_name || ' ' || COALESCE(p.description, '')) @@ plainto_tsquery('english', p_query)
        OR p.plugin_id ILIKE '%' || p_query || '%'
    )
    AND (p_type IS NULL OR p.type = p_type)
    AND (p_verified_only = false OR p.verified = true)
    AND (
        p_tags IS NULL
        OR EXISTS (
            SELECT 1 FROM plugin_tags pt
            WHERE pt.plugin_id = p.id
            AND pt.tag = ANY(p_tags)
        )
    )
    AND (
        p_min_rating = 0
        OR COALESCE(
            (SELECT AVG(pr.rating) FROM plugin_ratings pr WHERE pr.plugin_id = p.id),
            0
        ) >= p_min_rating
    );

    -- Get paginated results
    SELECT jsonb_agg(plugin_data ORDER BY sort_key DESC)
    INTO v_plugins
    FROM (
        SELECT
            jsonb_build_object(
                'id', p.id,
                'pluginId', p.plugin_id,
                'displayName', p.display_name,
                'description', p.description,
                'author', p.author_name,
                'type', p.type,
                'apiVersion', p.api_version,
                'verified', p.verified,
                'iconUrl', p.icon_url,
                'url', p.homepage_url,
                'version', (
                    SELECT pv.version
                    FROM plugin_versions pv
                    WHERE pv.plugin_id = p.id
                      AND pv.status = 'published'
                    ORDER BY pv.published_at DESC NULLS LAST
                    LIMIT 1
                ),
                'rating', COALESCE(
                    (SELECT AVG(pr.rating)::NUMERIC(3,2) FROM plugin_ratings pr WHERE pr.plugin_id = p.id),
                    0
                ),
                'ratingCount', (SELECT COUNT(*)::INT FROM plugin_ratings pr WHERE pr.plugin_id = p.id),
                'downloadCount', (SELECT COUNT(*)::INT FROM plugin_downloads pd WHERE pd.plugin_id = p.id),
                'tags', COALESCE(
                    (SELECT ARRAY_AGG(pt.tag) FROM plugin_tags pt WHERE pt.plugin_id = p.id),
                    ARRAY[]::TEXT[]
                ),
                'requiredPermissions', COALESCE(p.required_permissions, ARRAY[]::TEXT[]),
                'updatedAt', p.updated_at
            ) AS plugin_data,
            CASE p_sort_by
                WHEN 'name' THEN 0
                WHEN 'downloads' THEN (SELECT COUNT(*) FROM plugin_downloads pd WHERE pd.plugin_id = p.id)
                WHEN 'rating' THEN COALESCE(
                    (SELECT AVG(pr.rating) * 100 FROM plugin_ratings pr WHERE pr.plugin_id = p.id)::BIGINT,
                    0
                )
                WHEN 'newest' THEN EXTRACT(EPOCH FROM p.created_at)::BIGINT
                WHEN 'updated' THEN EXTRACT(EPOCH FROM p.updated_at)::BIGINT
                ELSE (SELECT COUNT(*) FROM plugin_downloads pd WHERE pd.plugin_id = p.id)
            END AS sort_key
        FROM plugins p
        WHERE p.published = true
        AND (
            p_query = ''
            OR to_tsvector('english', p.display_name || ' ' || COALESCE(p.description, '')) @@ plainto_tsquery('english', p_query)
            OR p.plugin_id ILIKE '%' || p_query || '%'
        )
        AND (p_type IS NULL OR p.type = p_type)
        AND (p_verified_only = false OR p.verified = true)
        AND (
            p_tags IS NULL
            OR EXISTS (
                SELECT 1 FROM plugin_tags pt
                WHERE pt.plugin_id = p.id
                AND pt.tag = ANY(p_tags)
            )
        )
        AND (
            p_min_rating = 0
            OR COALESCE(
                (SELECT AVG(pr.rating) FROM plugin_ratings pr WHERE pr.plugin_id = p.id),
                0
            ) >= p_min_rating
        )
        ORDER BY sort_key DESC
        LIMIT p_page_size
        OFFSET v_offset
    ) AS subquery;

    RETURN QUERY SELECT COALESCE(v_plugins, '[]'::JSONB), v_total;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

COMMENT ON FUNCTION search_plugins IS 'Search plugins with filtering, sorting, and pagination. The version listed per plugin is always the latest PUBLISHED row (status=published).';

-- ---- get_plugin_with_stats (org-visibility variant) -----------------
-- The 20260803000000 migration added organisation visibility and org_id /
-- org_slug / visibility to the return type, which means a CREATE OR REPLACE
-- against the original function cannot change the return type. The migration
-- handles that with a DROP+CREATE for `get_plugin_with_stats` itself and a
-- new `_internal` + `_for_viewer` pair. We must DROP+CREATE the public
-- function again because we are widening it with a new filter.
DROP FUNCTION IF EXISTS get_plugin_with_stats(TEXT);

CREATE OR REPLACE FUNCTION get_plugin_with_stats_internal(
    p_plugin_id TEXT,
    p_viewer_id UUID
)
RETURNS TABLE (
    id UUID,
    plugin_id TEXT,
    display_name TEXT,
    description TEXT,
    author_id UUID,
    author_name TEXT,
    homepage_url TEXT,
    icon_url TEXT,
    type TEXT,
    api_version TEXT,
    verified BOOLEAN,
    published BOOLEAN,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    latest_version TEXT,
    latest_version_id UUID,
    avg_rating NUMERIC,
    rating_count BIGINT,
    download_count BIGINT,
    tags TEXT[],
    screenshots JSONB,
    required_permissions TEXT[],
    org_id UUID,
    org_slug TEXT,
    visibility TEXT
)
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path TO ''
AS $$
BEGIN
    RETURN QUERY
    SELECT
        p.id, p.plugin_id, p.display_name, p.description,
        p.author_id, p.author_name, p.homepage_url, p.icon_url,
        p.type, p.api_version, p.verified, p.published,
        p.created_at, p.updated_at,
        (SELECT pv.version FROM plugin_versions pv
          WHERE pv.plugin_id = p.id
            AND pv.status = 'published'
          ORDER BY pv.published_at DESC NULLS LAST LIMIT 1) AS latest_version,
        (SELECT pv.id FROM plugin_versions pv
          WHERE pv.plugin_id = p.id
            AND pv.status = 'published'
          ORDER BY pv.published_at DESC NULLS LAST LIMIT 1) AS latest_version_id,
        COALESCE((SELECT AVG(pr.rating)::NUMERIC(3,2) FROM plugin_ratings pr
                   WHERE pr.plugin_id = p.id), 0) AS avg_rating,
        (SELECT COUNT(*)::BIGINT FROM plugin_ratings pr WHERE pr.plugin_id = p.id) AS rating_count,
        (SELECT COUNT(*)::BIGINT FROM plugin_downloads pd WHERE pd.plugin_id = p.id) AS download_count,
        COALESCE((SELECT ARRAY_AGG(pt.tag) FROM plugin_tags pt
                   WHERE pt.plugin_id = p.id), ARRAY[]::TEXT[]) AS tags,
        COALESCE((
            SELECT jsonb_agg(jsonb_build_object('url', ps.url, 'caption', ps.caption)
                             ORDER BY ps.sort_order)
            FROM plugin_screenshots ps WHERE ps.plugin_id = p.id
        ), '[]'::JSONB) AS screenshots,
        COALESCE(p.required_permissions, ARRAY[]::TEXT[]) AS required_permissions,
        p.org_id,
        (SELECT o.slug FROM organisations o WHERE o.id = p.org_id) AS org_slug,
        p.visibility
    FROM plugins p
    WHERE p.plugin_id = p_plugin_id
      AND user_can_view_plugin_row(p_viewer_id, p.visibility, p.org_id, p.author_id, p.published);
END;
$$;

ALTER FUNCTION get_plugin_with_stats_internal(TEXT, UUID) OWNER TO postgres;

REVOKE EXECUTE ON FUNCTION get_plugin_with_stats_internal(TEXT, UUID) FROM PUBLIC, anon, authenticated;
GRANT  EXECUTE ON FUNCTION get_plugin_with_stats_internal(TEXT, UUID) TO service_role;

CREATE OR REPLACE FUNCTION get_plugin_with_stats(p_plugin_id TEXT)
RETURNS TABLE (
    id UUID,
    plugin_id TEXT,
    display_name TEXT,
    description TEXT,
    author_id UUID,
    author_name TEXT,
    homepage_url TEXT,
    icon_url TEXT,
    type TEXT,
    api_version TEXT,
    verified BOOLEAN,
    published BOOLEAN,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    latest_version TEXT,
    latest_version_id UUID,
    avg_rating NUMERIC,
    rating_count BIGINT,
    download_count BIGINT,
    tags TEXT[],
    screenshots JSONB,
    required_permissions TEXT[],
    org_id UUID,
    org_slug TEXT,
    visibility TEXT
)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path TO ''
AS $$
    SELECT * FROM get_plugin_with_stats_internal(p_plugin_id, auth.uid());
$$;

ALTER FUNCTION get_plugin_with_stats(TEXT) OWNER TO postgres;

COMMENT ON FUNCTION get_plugin_with_stats(TEXT) IS 'Plugin detail, scoped to what auth.uid() may see. latest_version / latest_version_id resolve only PUBLISHED rows (status=published). Dropped and recreated by 20260920000000 because the latest-version subquery gained a status filter.';

REVOKE EXECUTE ON FUNCTION get_plugin_with_stats(TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION get_plugin_with_stats(TEXT) TO anon, authenticated, service_role;

CREATE OR REPLACE FUNCTION get_plugin_with_stats_for_viewer(p_plugin_id TEXT, p_viewer_id UUID)
RETURNS TABLE (
    id UUID,
    plugin_id TEXT,
    display_name TEXT,
    description TEXT,
    author_id UUID,
    author_name TEXT,
    homepage_url TEXT,
    icon_url TEXT,
    type TEXT,
    api_version TEXT,
    verified BOOLEAN,
    published BOOLEAN,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    latest_version TEXT,
    latest_version_id UUID,
    avg_rating NUMERIC,
    rating_count BIGINT,
    download_count BIGINT,
    tags TEXT[],
    screenshots JSONB,
    required_permissions TEXT[],
    org_id UUID,
    org_slug TEXT,
    visibility TEXT
)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path TO ''
AS $$
    SELECT * FROM get_plugin_with_stats_internal(p_plugin_id, p_viewer_id);
$$;

ALTER FUNCTION get_plugin_with_stats_for_viewer(TEXT, UUID) OWNER TO postgres;

REVOKE EXECUTE ON FUNCTION get_plugin_with_stats_for_viewer(TEXT, UUID) FROM PUBLIC, anon, authenticated;
GRANT  EXECUTE ON FUNCTION get_plugin_with_stats_for_viewer(TEXT, UUID) TO service_role;

-- ---- get_plugin_versions (org-visibility variant) -------------------
-- 20260803000000 added the org-visibility scope. These rows carry
-- jar_path and sha256, so the visibility gate is load-bearing - leaving it
-- off would expose an organisation-private plugin's artifact location to
-- the world. Add status='published' alongside.
DROP FUNCTION IF EXISTS get_plugin_versions(TEXT);

CREATE OR REPLACE FUNCTION get_plugin_versions_internal(
    p_plugin_id TEXT,
    p_viewer_id UUID
)
RETURNS TABLE (
    id UUID,
    version TEXT,
    changelog TEXT,
    min_boss_version TEXT,
    min_ipc_version TEXT,
    min_api_version TEXT,
    jar_path TEXT,
    jar_size BIGINT,
    sha256 TEXT,
    dependencies JSONB,
    published_at TIMESTAMPTZ,
    download_count BIGINT
)
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path TO ''
AS $$
BEGIN
    RETURN QUERY
    SELECT
        pv.id, pv.version, pv.changelog,
        pv.min_boss_version, pv.min_ipc_version, pv.min_api_version,
        pv.jar_path, pv.jar_size, pv.sha256,
        pv.dependencies, pv.published_at,
        (SELECT COUNT(*)::BIGINT FROM plugin_downloads pd WHERE pd.version_id = pv.id) AS download_count
    FROM plugin_versions pv
    JOIN plugins p ON p.id = pv.plugin_id
    WHERE p.plugin_id = p_plugin_id
      AND user_can_view_plugin_row(p_viewer_id, p.visibility, p.org_id, p.author_id, p.published)
      AND pv.status = 'published'
    ORDER BY pv.published_at DESC NULLS LAST;
END;
$$;

ALTER FUNCTION get_plugin_versions_internal(TEXT, UUID) OWNER TO postgres;

REVOKE EXECUTE ON FUNCTION get_plugin_versions_internal(TEXT, UUID) FROM PUBLIC, anon, authenticated;
GRANT  EXECUTE ON FUNCTION get_plugin_versions_internal(TEXT, UUID) TO service_role;

CREATE OR REPLACE FUNCTION get_plugin_versions(p_plugin_id TEXT)
RETURNS TABLE (
    id UUID,
    version TEXT,
    changelog TEXT,
    min_boss_version TEXT,
    min_ipc_version TEXT,
    min_api_version TEXT,
    jar_path TEXT,
    jar_size BIGINT,
    sha256 TEXT,
    dependencies JSONB,
    published_at TIMESTAMPTZ,
    download_count BIGINT
)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path TO ''
AS $$
    SELECT * FROM get_plugin_versions_internal(p_plugin_id, auth.uid());
$$;

ALTER FUNCTION get_plugin_versions(TEXT) OWNER TO postgres;

COMMENT ON FUNCTION get_plugin_versions(TEXT) IS 'Version list for a plugin, scoped to what auth.uid() may see and to PUBLISHED rows (status=published). These rows carry jar_path and sha256, so leaving this ungated would expose an organisation-private plugin''s artifact even with every other surface closed.';

REVOKE EXECUTE ON FUNCTION get_plugin_versions(TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION get_plugin_versions(TEXT) TO anon, authenticated, service_role;

CREATE OR REPLACE FUNCTION get_plugin_versions_for_viewer(p_plugin_id TEXT, p_viewer_id UUID)
RETURNS TABLE (
    id UUID,
    version TEXT,
    changelog TEXT,
    min_boss_version TEXT,
    min_ipc_version TEXT,
    min_api_version TEXT,
    jar_path TEXT,
    jar_size BIGINT,
    sha256 TEXT,
    dependencies JSONB,
    published_at TIMESTAMPTZ,
    download_count BIGINT
)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path TO ''
AS $$
    SELECT * FROM get_plugin_versions_internal(p_plugin_id, p_viewer_id);
$$;

ALTER FUNCTION get_plugin_versions_for_viewer(TEXT, UUID) OWNER TO postgres;

REVOKE EXECUTE ON FUNCTION get_plugin_versions_for_viewer(TEXT, UUID) FROM PUBLIC, anon, authenticated;
GRANT  EXECUTE ON FUNCTION get_plugin_versions_for_viewer(TEXT, UUID) TO service_role;
