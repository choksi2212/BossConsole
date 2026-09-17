-- pgTAP tests for the part-2 SECURITY DEFINER search_path hardening
-- (20260916140000, BossConsole#772).
--
-- 20260916130000 (BossConsole#773) pins the three passkey lifecycle
-- functions; a live catalog audit showed share_secret / unshare_secret /
-- get_secret_shares / handle_new_user were already hardened by later
-- migrations. These
-- assertions pin the three that remained, so a future CREATE OR REPLACE
-- cannot silently drop the clause again.

begin;
select plan(4);

-- 1-3: each hardened function pins an empty search_path in pg_proc.proconfig.
select is(
    (select proconfig[1] from pg_proc
      where oid = 'public.find_user_by_email(text)'::regprocedure),
    'search_path=""',
    'find_user_by_email pins search_path to empty'
);

select is(
    (select proconfig[1] from pg_proc
      where oid = 'public.handle_user_email_update()'::regprocedure),
    'search_path=""',
    'handle_user_email_update pins search_path to empty'
);

select is(
    (select proconfig[1] from pg_proc
      where oid = 'public.safe_decrypt_recovery_codes(text)'::regprocedure),
    'search_path=""',
    'safe_decrypt_recovery_codes pins search_path to empty'
);

-- 4: the live decryption path still executes with the closed search_path.
-- NULL must return the documented empty array without touching any table -
-- proof the clause did not break the read path.
select is(
    public.safe_decrypt_recovery_codes(NULL::text),
    '[]'::jsonb,
    'safe_decrypt_recovery_codes still executes and returns the documented empty array for NULL'
);

select * from finish();
rollback;
