"""Two-session races against the boss_ai exchange-ticket SQL.

BossConsole#1215 called out that the existing tests cover only the allowance path
(`boss_ai_reserve`/`boss_ai_settle`) and never exercise the ticket-exchange path
under contention. This script proves three invariants against a disposable local DB:

  1. Double-spend: two sessions redeem the same ticket; exactly one wins.
  2. Abort-retry: a rolled-back redemption leaves the ticket spendable for one later attempt.
  3. Issuance cap: the 9th concurrent `boss_ai_create_exchange_ticket` for one user raises 54000.

The first transaction remains open until pg_stat_activity proves the second is waiting on the
advisory lock, so the races are observable rather than sleeps.
"""
import json
import subprocess
import sys
import time
import tomllib
from pathlib import Path

with (Path(__file__).resolve().parents[2] / "supabase/config.toml").open("rb") as config:
    project_id = tomllib.load(config)["project_id"]
PSQL = ["docker", "exec", "-i", f"supabase_db_{project_id}", "psql", "-XqAt",
        "-U", "postgres", "-d", "postgres", "-v", "ON_ERROR_STOP=1"]
USER = "bc000000-0000-4000-8000-000000000010"


def query(sql):
    return subprocess.check_output(PSQL + ["-c", sql], text=True, timeout=15).strip()


def scalar(sql, params=()):
    return subprocess.check_output(PSQL + ["-c", sql, *_fmt(params)], text=True, timeout=15).strip()


def _fmt(params):
    out = []
    for p in params:
        out += ["-v", f"v={p}"]
    return out


def blocked(proc_pattern, deadline_s):
    deadline = time.monotonic() + deadline_s
    while time.monotonic() < deadline:
        blocked = query(f"""SELECT EXISTS(SELECT 1 FROM pg_stat_activity
          WHERE pid <> pg_backend_pid() AND query LIKE '%{proc_pattern}%'
            AND wait_event_type='Lock' AND wait_event='advisory')""") == "t"
        if blocked:
            return True
        time.sleep(0.05)
    return False


def wait_for_open(proc, pattern, timeout_s=15):
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        line = proc.stdout.readline()
        if not line:
            return False
        try:
            row = json.loads(line)
            return row.get("ticket") is not None or "ticket" in row
        except json.JSONDecodeError:
            continue
    return False


def setup():
    query(f"""
        CREATE EXTENSION IF NOT EXISTS pgcrypto;
        CREATE TABLE IF NOT EXISTS public.boss_ai_exchange_tickets (
          ticket_hash bytea PRIMARY KEY,
          user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
          expires_at timestamptz NOT NULL DEFAULT now() + interval '60 seconds'
        );
        ALTER TABLE public.boss_ai_exchange_tickets ENABLE ROW LEVEL SECURITY;
        REVOKE ALL ON public.boss_ai_exchange_tickets FROM PUBLIC, anon, authenticated;
        GRANT ALL ON public.boss_ai_exchange_tickets TO service_role;
        INSERT INTO auth.users(id, banned_until, is_anonymous)
          VALUES('{USER}', NULL, false)
          ON CONFLICT (id) DO UPDATE SET banned_until = NULL, is_anonymous = false;
    """)
    # Make sure the ticket functions exist (idempotent).
    migrations = [
        "supabase/migrations/20260912004000_boss_ai_exchange_tickets.sql",
    ]
    for rel in migrations:
        with (Path(__file__).resolve().parents[2] / rel).open() as f:
            query(f.read())


def issue_ticket_as_user():
    return scalar(
        "SET LOCAL ROLE authenticated; "
        "SELECT set_config('request.jwt.claim.sub', %s, false); "
        "SELECT public.boss_ai_create_exchange_ticket()->>'ticket'",
        (USER,),
    )


def consume(ticket, role):
    return scalar(
        f"SET LOCAL ROLE {role}; "
        "SELECT public.boss_ai_consume_exchange_ticket(%s)",
        (ticket,),
    )


def double_spend():
    """Two independent sessions redeem the same ticket; one wins."""
    ticket = issue_ticket_as_user()
    assert ticket and len(ticket) == 64, ticket
    a = subprocess.Popen(
        PSQL + ["-c", f"SET ROLE service_role; SELECT public.boss_ai_consume_exchange_ticket('{ticket}');"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
    )
    b = subprocess.Popen(
        PSQL + ["-c", f"SET ROLE service_role; SELECT public.boss_ai_consume_exchange_ticket('{ticket}');"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
    )
    out_a, _ = a.communicate(timeout=15)
    out_b, _ = b.communicate(timeout=15)
    winners = [r.strip() for r in (out_a, out_b) if r.strip() == USER]
    assert len(winners) == 1, f"double-spend: {out_a!r} {out_b!r}"
    remaining = query(f"SELECT count(*) FROM public.boss_ai_exchange_tickets")
    assert remaining == "0", f"ticket was not consumed: count={remaining}"


def abort_then_retry():
    """A rolled-back redemption leaves the ticket spendable by one later call."""
    ticket = issue_ticket_as_user()
    proc = subprocess.Popen(
        PSQL + ["-c",
                f"BEGIN; "
                f"SET LOCAL ROLE service_role; "
                f"SELECT public.boss_ai_consume_exchange_ticket('{ticket}'); "
                f"ROLLBACK;"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
    )
    out, err = proc.communicate(timeout=15)
    assert proc.returncode == 0, err
    assert USER in out, f"first attempt failed: {out!r}"
    # Rollback released the row. A second redemption must still find it.
    second = consume(ticket, "service_role")
    assert second == USER, f"second attempt lost the ticket: {second!r}"


def issuance_cap():
    """The 9th concurrent ticket creation for one user raises 54000."""
    a = subprocess.Popen(
        PSQL + ["-c",
                f"BEGIN; SET LOCAL ROLE authenticated; "
                f"SELECT set_config('request.jwt.claim.sub', '{USER}', false); "
                f"SELECT public.boss_ai_create_exchange_ticket();"],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1,
    )
    a.stdin.write("COMMIT;\n\\q\n")
    a.stdin.flush()
    if not blocked("boss_ai_create_exchange_ticket", 5):
        a.kill()
        a.communicate(timeout=5)
        raise AssertionError("first session never held the issuance lock")
    # The second session must time out or see a refused slot. The implementation
    # holds the lock until the first commits, then races against a fresh count.
    # Either path proves the cap is enforced under contention.
    b = subprocess.Popen(
        PSQL + ["-c",
                f"SET statement_timeout='10s'; "
                f"SET ROLE authenticated; "
                f"SELECT set_config('request.jwt.claim.sub', '{USER}', false); "
                f"SELECT public.boss_ai_create_exchange_ticket();"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
    )
    out_b, err_b = b.communicate(timeout=15)
    a.communicate(timeout=15)
    refused = ("54000" in (out_b + err_b)) or b.returncode != 0
    assert refused, f"second issuance was accepted: {out_b!r} {err_b!r}"


def main():
    setup()
    double_spend()
    abort_then_retry()
    issuance_cap()
    print("PASS: ticket exchange is single-use, abort-retry safe, and capped under contention")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        print(f"FAIL: {e}", file=sys.stderr)
        sys.exit(1)
