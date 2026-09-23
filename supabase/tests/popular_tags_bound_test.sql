-- pgTAP tests for 20260922160000: get_popular_tags clamps p_limit in the function itself.
--
-- The edge route caps `limit`, but the function is anon-callable through PostgREST, so the
-- bound has to hold for a direct call too. The fixture has 150 distinct public tags, so every
-- cap below is observable rather than satisfied by an empty table.

begin;
select plan(10);

-- ---------------------------------------------------------------------------
-- Fixtures: one published public plugin carrying 150 distinct tags.
-- ---------------------------------------------------------------------------
insert into auth.users (id, email, email_confirmed_at) values
    ('71000000-0000-0000-0000-000000000001', 'tagcap@pgtap.test', now());

select public.create_organisation_internal(
    p_slug=>'pgtagcap', p_name=>'PGTap Tag Cap',
    p_owner_id=>'71000000-0000-0000-0000-000000000001',
    p_visibility=>'private', p_join_policy=>'invite_only');

insert into public.plugins (plugin_id, display_name, author_id, author_name, type, api_version, published, org_id, visibility)
values ('test.tagcap', 'Tag Cap', '71000000-0000-0000-0000-000000000001', 'owner', 'panel', '1.0', true,
        (select id from public.organisations where slug='pgtagcap'), 'public');

insert into public.plugin_tags (plugin_id, tag)
select p.id, 'pgtap-cap-' || lpad(g::text, 3, '0')
from public.plugins p, generate_series(1, 150) g
where p.plugin_id = 'test.tagcap';

-- The guard that keeps the cap assertions from passing vacuously.
select cmp_ok(
    (select count(*)::int from public.get_popular_tags(100)),
    '=', 100,
    'FIXTURE: at least 100 public tags exist, so a cap of 100 is observable');

-- ---------------------------------------------------------------------------
-- The grant survives the replace. The event trigger revokes anon on CREATE FUNCTION, which
-- CREATE OR REPLACE also fires, so the migration re-grants; these fail if that line goes.
-- ---------------------------------------------------------------------------
select ok(has_function_privilege('anon', 'public.get_popular_tags(integer)', 'EXECUTE'),
    'anon can still call get_popular_tags, so the store browses before sign-in');
select ok(has_function_privilege('authenticated', 'public.get_popular_tags(integer)', 'EXECUTE'),
    'authenticated can still call get_popular_tags');

-- ---------------------------------------------------------------------------
-- The clamp, called the way PostgREST calls it for the anon key.
-- ---------------------------------------------------------------------------
set local role anon;

select is((select count(*)::int from public.get_popular_tags(null)), 20,
    'NULL takes the default of 20, where it used to be LIMIT NULL, which is LIMIT ALL');
select is((select count(*)::int from public.get_popular_tags(1000000000)), 100,
    'an oversized limit is clamped to 100');
select is((select count(*)::int from public.get_popular_tags(101)), 100,
    'one past the cap is clamped to 100');
select is((select count(*)::int from public.get_popular_tags(0)), 1,
    'zero is raised to 1');
select lives_ok('select * from public.get_popular_tags(-1)',
    'a negative limit does not raise "LIMIT must not be negative"');
select is((select count(*)::int from public.get_popular_tags(-1)), 1,
    'a negative limit is raised to 1');
select is((select count(*)::int from public.get_popular_tags(7)), 7,
    'a limit inside 1..100 is unchanged');

reset role;

select * from finish();
rollback;
