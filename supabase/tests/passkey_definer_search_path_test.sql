-- pgTAP tests for the passkey SECURITY DEFINER search_path hardening
-- (20260916130000).
--
-- Every SECURITY DEFINER function added since 20260802000000 carries
-- `SET search_path TO ''` plus fully-qualified references; the three passkey
-- lifecycle functions predated that convention and resolved their table
-- references through the caller-influenced search_path. These assertions
-- pin the closed form so a future CREATE OR REPLACE cannot silently drop
-- the clause again, and pin the revoked client grants on the cleanup RPC.
--
-- Review follow-ups pinned here as well: the nested-trigger chain
-- (trigger_cleanup_expired_challenges and the bounded cleanup RPC it now
-- delegates to) carries its own empty search_path, so the hardened
-- create_mobile_registration_session can no longer abort registration on
-- the 10% cleanup branch. Membership testing ('search_path=""' = any(...))
-- is used throughout rather than positional proconfig indexing.

begin;
select plan(16);

-- 1-4: the hardened functions carry an empty search_path.
select is(
    (select proconfig from pg_proc
      where oid = 'public.clean_expired_passkey_challenges()'::regprocedure),
    ARRAY['search_path=""'],
    'clean_expired_passkey_challenges pins search_path to empty'
);

select is(
    (select proconfig from pg_proc
      where oid = 'public.create_mobile_registration_session(text, text, text)'::regprocedure),
    ARRAY['search_path=""'],
    'create_mobile_registration_session pins search_path to empty'
);

select is(
    (select proconfig from pg_proc
      where oid = 'public.get_session_status(text)'::regprocedure),
    ARRAY['search_path=""'],
    'get_session_status pins search_path to empty'
);

select is(
    (select proconfig from pg_proc
      where oid = 'public.trigger_cleanup_expired_challenges()'::regprocedure),
    ARRAY['search_path=""'],
    'trigger_cleanup_expired_challenges pins search_path to empty (nested-trigger regression closed)'
);

-- 5: membership form, robust to GUC list position: every function this
-- migration hardened or introduced reports the empty search_path somewhere
-- in proconfig.
select is(
    (select count(*)::int from pg_proc
      where oid in (
        'public.clean_expired_passkey_challenges()'::regprocedure,
        'public.create_mobile_registration_session(text, text, text)'::regprocedure,
        'public.get_session_status(text)'::regprocedure,
        'public.trigger_cleanup_expired_challenges()'::regprocedure
      )
      and 'search_path=""' = any(proconfig)),
    4,
    'all four functions carry the empty search_path (membership test, order-independent)'
);

-- 6: the trigger owns its cleanup so an authenticated INSERT does not need
-- EXECUTE on the separately revoked cleanup RPC; its empty path and qualified
-- table reference keep that elevated body closed.
select ok(
    (select prosecdef from pg_proc
      where oid = 'public.trigger_cleanup_expired_challenges()'::regprocedure) = true,
    'trigger_cleanup_expired_challenges is SECURITY DEFINER with a closed path'
);

-- 7-8: the cleanup RPC executes under the closed path and retains all three
-- lifecycle rules from its original body.
select lives_ok(
    $$ select public.clean_expired_passkey_challenges() $$,
    'the cleanup RPC executes with the closed search_path'
);

select alike(
    pg_get_functiondef('public.clean_expired_passkey_challenges()'::regprocedure),
    '%status IN (%failed%, %expired%)%',
    'the cleanup RPC retains failed and expired session cleanup'
);

-- 9-13: the dead client grants are gone; the operational role keeps access.
select ok(
    not has_function_privilege('anon', 'public.clean_expired_passkey_challenges()', 'EXECUTE'),
    'anon can no longer execute the cleanup RPC'
);

select ok(
    not has_function_privilege('authenticated', 'public.clean_expired_passkey_challenges()', 'EXECUTE'),
    'authenticated can no longer execute the cleanup RPC (nothing invokes it directly; the trigger inlines its own DELETE)'
);

select ok(
    not has_function_privilege('public', 'public.clean_expired_passkey_challenges()', 'EXECUTE'),
    'PUBLIC can no longer execute the cleanup RPC'
);

select ok(
    has_function_privilege('service_role', 'public.clean_expired_passkey_challenges()', 'EXECUTE'),
    'service_role keeps EXECUTE for operational use'
);

-- 14-16: the hardened registration path still executes with the closed
-- search_path. get_session_status and the cleanup RPC are the live-path
-- proofs; the registration session itself needs a confirmed auth user, so
-- its read side stands in.
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
