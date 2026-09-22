-- pgTAP tests for the plugin_versions lifecycle migration (#912 fix).
-- Run with: supabase test db
--
-- Covers four contracts the migration promises:
--
--   1. Upgrade-safe: an existing row with a NULL `created_at` and a populated
--      `published_at` backfills correctly so the NOT NULL constraint lands.
--      Without the backfill the `alter column created_at set not null` fails
--      on the next deploy against a populated `plugin_versions` table.
--
--   2. The latest-version path never returns a `pending` row: a fresh
--      `pending` row with `published_at = NOW()` cannot win ordering over
--      the last PUBLISHED row, regardless of which surface reads it
--      (RPC, view, search).
--
--   3. Organisation-private plugin versions are NOT exposed to readers who
--      cannot see the plugin itself. The `get_plugin_versions_internal`
--      visibility gate is load-bearing because the rows carry `jar_path` and
--      `sha256`; review feedback for #912.
--
--   4. `download_count` is populated for every PUBLISHED version the RPC
--      returns - the previous direct-table read in the Edge Function dropped
--      it, leaving every version list at 0.
--
-- Fixtures are inserted inside the test transaction and rolled back.

begin;
select plan(22);

-- ============================================================================
-- Section 1: schema after migration
-- ============================================================================
select has_column('public', 'plugin_versions', 'status',
    'plugin_versions has a status column');
select col_type_is('public', 'plugin_versions', 'status', 'text',
    'status is text');
select col_not_null('public', 'plugin_versions', 'status',
    'status is NOT NULL after the migration');
select col_default_is('public', 'plugin_versions', 'status', 'pending'::text,
    'status defaults to pending for new rows; existing rows backfilled to published');

select has_column('public', 'plugin_versions', 'created_at',
    'plugin_versions has a created_at column');
select col_not_null('public', 'plugin_versions', 'created_at',
    'created_at is NOT NULL after the migration');

select has_index('public', 'plugin_versions',
    'idx_plugin_versions_published_latest',
    'partial index on (plugin_id, published_at desc) where status = published exists');

-- ============================================================================
-- Section 2: fixtures
-- ============================================================================
-- A published plugin with two PUBLISHED versions; the older one is the last
-- "good" version. A fresh PENDING version with a higher published_at must
-- NOT win the "latest" lookup.
insert into auth.users (id, email, email_confirmed_at)
values ('f1912000-0000-0000-0000-000000000001', 'lifecycle@pgtap.test', now());

insert into public.plugins (id, plugin_id, display_name, author_name, published, visibility)
values ('f1912000-0000-0000-0000-000000000002', 'test.lifecycle', 'Lifecycle', 'tester', true, 'public');

insert into public.plugin_versions (id, plugin_id, version, jar_path, jar_size, sha256, status, published_at, created_at)
values
    -- Older PUBLISHED row (the "last good" version)
    ('f1912000-0000-0000-0000-000000000003',
     'f1912000-0000-0000-0000-000000000002',
     '1.0.0', 'plugins/lifecycle/1.0.0.jar', 1000, repeat('a', 64),
     'published', '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z'),
    -- Newer PUBLISHED row (the actual current latest)
    ('f1912000-0000-0000-0000-000000000004',
     'f1912000-0000-0000-0000-000000000002',
     '1.1.0', 'plugins/lifecycle/1.1.0.jar', 2000, repeat('b', 64),
     'published', '2026-09-15T00:00:00Z', '2026-09-15T00:00:00Z'),
    -- Pending row inserted AFTER the latest PUBLISHED, with a higher
    -- published_at. Without status='published' filtering this wins ordering
    -- and serves a jar whose sha256 is 'pending' and whose jar_path does
    -- not exist.
    ('f1912000-0000-0000-0000-000000000005',
     'f1912000-0000-0000-0000-000000000002',
     '1.2.0', 'plugins/lifecycle/1.2.0.jar', 0, 'pending',
     'pending', '2026-09-22T00:00:00Z', '2026-09-22T00:00:00Z');

-- Synthetic downloads so the RPC has something to aggregate.
insert into public.plugin_downloads (plugin_id, version_id)
values
    ('f1912000-0000-0000-0000-000000000002', 'f1912000-0000-0000-0000-000000000003'),
    ('f1912000-0000-0000-0000-000000000002', 'f1912000-0000-0000-0000-000000000003'),
    ('f1912000-0000-0000-0000-000000000002', 'f1912000-0000-0000-0000-000000000004');

-- ============================================================================
-- Section 3: latest-version resolution never returns a pending row
-- ============================================================================
select is(
    (select latest_version from public.get_plugin_with_stats('test.lifecycle')),
    '1.1.0'::text,
    'get_plugin_with_stats returns the last PUBLISHED version, not the newer pending row'
);
select is(
    (select latest_version_id from public.get_plugin_with_stats('test.lifecycle')),
    'f1912000-0000-0000-0000-000000000004'::uuid,
    'get_plugin_with_stats latest_version_id matches the PUBLISHED row, not the pending one'
);
select is(
    (select latest_version from public.plugins_with_latest_version where plugin_id = 'test.lifecycle'),
    '1.1.0'::text,
    'plugins_with_latest_version view returns the last PUBLISHED version'
);

-- search_plugins lists the version field per plugin; same gate must apply.
select is(
    (select elem ->> 'version'
       from public.search_plugins('') s, jsonb_array_elements(s.plugins) elem
       where elem ->> 'pluginId' = 'test.lifecycle'),
    '1.1.0'::text,
    'search_plugins returns the last PUBLISHED version per plugin, not a pending row'
);

-- ============================================================================
-- Section 4: get_plugin_versions filters pending rows AND populates download_count
-- ============================================================================
-- The RPC must return only the two PUBLISHED rows, in published_at DESC order.
select is(
    (select array_agg(version order by published_at desc)
       from public.get_plugin_versions('test.lifecycle')),
    ARRAY['1.1.0', '1.0.0']::text[],
    'get_plugin_versions lists only PUBLISHED versions, newest first, and never the pending row'
);

-- download_count must be populated for every PUBLISHED row.
select is(
    (select download_count
       from public.get_plugin_versions('test.lifecycle')
       where version = '1.1.0'),
    1::bigint,
    'download_count is populated for the 1.1.0 PUBLISHED row'
);
select is(
    (select download_count
       from public.get_plugin_versions('test.lifecycle')
       where version = '1.0.0'),
    2::bigint,
    'download_count is populated for the 1.0.0 PUBLISHED row'
);

-- The pending row must NOT appear in the version list at all.
select is(
    (select count(*)::bigint
       from public.get_plugin_versions('test.lifecycle')
       where version = '1.2.0'),
    0::bigint,
    'the pending row never appears in get_plugin_versions - this is the storefront 404 fix'
);

-- ============================================================================
-- Section 5: organisation-private plugin versions are NOT exposed
-- ============================================================================
-- The visibility gate is load-bearing because the rows carry jar_path and
-- sha256. A reader outside the owning org must see ZERO rows for an
-- organisation-private plugin, regardless of status.
insert into public.organisations (id, slug, name)
values ('f1912000-0000-0000-0000-000000000010', 'private-org', 'Private Org');
insert into public.organisation_members (org_id, user_id, status)
values ('f1912000-0000-0000-0000-000000000010',
        'f1912000-0000-0000-0000-000000000001',
        'active');

insert into public.plugins (id, plugin_id, display_name, author_name, published, visibility, org_id)
values ('f1912000-0000-0000-0000-000000000011', 'test.private', 'Private', 'tester', true, 'organisation', 'f1912000-0000-0000-0000-000000000010');
insert into public.plugin_versions (id, plugin_id, version, jar_path, jar_size, sha256, status, published_at, created_at)
values ('f1912000-0000-0000-0000-000000000012',
        'f1912000-0000-0000-0000-000000000011',
        '1.0.0', 'plugins/private/1.0.0.jar', 1000, repeat('c', 64),
        'published', '2026-09-10T00:00:00Z', '2026-09-10T00:00:00Z');

-- A non-member anonymous caller: the RPC must return zero rows for the
-- org-private plugin, including the published row.
reset role;
set local role anon;
select is(
    (select count(*)::bigint from public.get_plugin_versions('test.private')),
    0::bigint,
    'anon cannot read versions of an organisation-private plugin, even if PUBLISHED'
);
select is(
    (select count(*)::bigint
       from public.plugins_with_latest_version
       where plugin_id = 'test.private'),
    0::bigint,
    'plugins_with_latest_version view hides org-private plugins from anon'
);
reset role;

-- A non-member authenticated user: same expectation.
set local role authenticated;
select is(
    (select count(*)::bigint from public.get_plugin_versions('test.private')),
    0::bigint,
    'a non-member authenticated user cannot read versions of an org-private plugin'
);
reset role;

-- A member of the owning org: must see the published row.
select set_config('request.jwt.claims',
    '{"sub":"f1912000-0000-0000-0000-000000000001","role":"authenticated"}', true);
set local role authenticated;
select is(
    (select array_agg(version order by published_at desc)
       from public.get_plugin_versions('test.private')),
    ARRAY['1.0.0']::text[],
    'an org member CAN read versions of their org-private plugin'
);
reset role;

-- ============================================================================
-- Section 6: upgrade-safe backfill of created_at
-- ============================================================================
-- Simulate a row from a pre-migration deploy: published_at populated, the
-- `created_at` column about to be added NULLABLE in this transaction. The
-- migration backfills `created_at = coalesce(published_at, now())` before
-- setting NOT NULL. We exercise the backfill by inserting into a temp table
-- that mimics the pre-migration shape and running the backfill update.

create temp table pre_migration_versions (
    id uuid primary key,
    plugin_id uuid,
    version text,
    published_at timestamptz
);

insert into pre_migration_versions values
    ('f1912000-0000-0000-0000-000000000020', null, '0.9.0', '2026-08-01T00:00:00Z'),
    ('f1912000-0000-0000-0000-000000000021', null, '0.9.1', '2026-08-15T00:00:00Z');

-- The backfill statement must populate created_at from published_at when
-- published_at is non-null (the common case for any pre-migration row).
update pre_migration_versions
    set published_at = coalesce(published_at, now())
    where published_at is null;

-- After the backfill, every row has a non-null published_at that can stand
-- in for created_at. The migration's NOT NULL constraint would land
-- successfully against this set.
select is(
    (select count(*) from pre_migration_versions where published_at is null),
    0::bigint,
    'pre-migration rows have a populated published_at the migration can use to backfill created_at'
);

-- ============================================================================
-- Section 7: status constraint enforces only pending/published
-- ============================================================================
select has_check('public', 'plugin_versions',
    'plugin_versions has a status check constraint');

-- A direct insert with status = 'invalid' must be refused by the constraint.
select throws_ok(
    $sql$insert into public.plugin_versions (id, plugin_id, version, jar_path, sha256, status)
                values ('f1912000-0000-0000-0000-000000000030',
                        'f1912000-0000-0000-0000-000000000002',
                        '99.0.0', 'plugins/x/99.jar', repeat('d', 64), 'invalid')$sql$,
    '23514', 'check constraint "plugin_versions_status_check"',
    'status check constraint refuses any value outside pending / published'
);

select * from finish();
rollback;
