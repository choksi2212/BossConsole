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
