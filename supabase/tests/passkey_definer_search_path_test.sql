-- pgTAP tests for the passkey SECURITY DEFINER search_path hardening
-- (20260916130000).
--
-- Every SECURITY DEFINER function added since 20260802000000 carries
-- `SET search_path TO ''`; the three passkey lifecycle functions predated
-- that convention and resolved their table references through the
-- caller-influenced search_path. These assertions pin the closed form so a
-- future CREATE OR REPLACE cannot silently drop the clause again, and pin
-- the revoked client grants on clean_expired_passkey_challenges - the one
-- function on the list that was still anon/authenticated-executable with no
-- production caller.

begin;
select plan(13);

-- 1-4: the hardened functions carry an empty search_path, provable in
-- pg_proc.proconfig (a text[] GUC list; a search_path-less function has
-- NULL proconfig). Any future leak of a non-empty path fails the equality.
select is(
    (select proconfig[1]
       from pg_proc
      where oid = 'public.clean_expired_passkey_challenges()'::regprocedure),
    'search_path=""',
    'clean_expired_passkey_challenges pins search_path to empty'
);

select is(
    (select proconfig[1]
       from pg_proc
      where oid = 'public.create_mobile_registration_session(text, text, text)'::regprocedure),
    'search_path=""',
    'create_mobile_registration_session pins search_path to empty'
);

select is(
    (select proconfig[1]
       from pg_proc
      where oid = 'public.get_session_status(text)'::regprocedure),
    'search_path=""',
    'get_session_status pins search_path to empty'
);

select ok(
    (select prosecdef from pg_proc
      where oid = 'public.clean_expired_passkey_challenges()'::regprocedure),
    'clean_expired_passkey_challenges is still SECURITY DEFINER (owner-privileged, unchanged intent)'
);

-- 5-9: the dead client grants are gone; the operational roles keep access.
select ok(
    not has_function_privilege('anon', 'public.clean_expired_passkey_challenges()', 'EXECUTE'),
    'anon can no longer execute the cleanup RPC'
);

select ok(
    not has_function_privilege('authenticated', 'public.clean_expired_passkey_challenges()', 'EXECUTE'),
    'authenticated can no longer execute the cleanup RPC (nothing invokes it; the trigger inlines its own DELETE)'
);

select ok(
    not has_function_privilege('public', 'public.clean_expired_passkey_challenges()', 'EXECUTE'),
    'PUBLIC can no longer execute the cleanup RPC'
);

select ok(
    has_function_privilege('service_role', 'public.clean_expired_passkey_challenges()', 'EXECUTE'),
    'service_role keeps EXECUTE for operational use'
);

-- 10-13: the hardening must not have gone too far. get_session_status and
-- create_mobile_registration_session were both already revoked from
-- clients (20260910000000 / the 20260908030000-era sweep) - the edge
-- functions drive registration through the service role. What must hold
-- is that the *live* paths still execute with the closed search_path.
select ok(
    not has_function_privilege('anon', 'public.get_session_status(text)', 'EXECUTE'),
    'anon still cannot execute get_session_status (revoked by 20260910000000; unchanged here)'
);

select ok(
    has_function_privilege('service_role', 'public.create_mobile_registration_session(text, text, text)', 'EXECUTE'),
    'service_role keeps the registration-session RPC (the live passkey edge-function path)'
);

select lives_ok(
    $$ select public.get_session_status('definitely-not-a-session') $$,
    'get_session_status still executes with the closed search_path'
);

select is_empty(
    $$ select * from public.get_session_status('definitely-not-a-session') $$,
    'get_session_status returns the same empty result as before for an unknown session'
);

select * from finish();
rollback;
