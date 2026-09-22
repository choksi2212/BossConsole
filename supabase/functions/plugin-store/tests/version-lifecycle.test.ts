/**
 * Row-lifecycle tests for plugin versions (#912).
 *
 * Pin the contract that prevents an unfinalized row from being served as
 * "latest":
 *
 *   - createVersion inserts with status='pending' and published_at=null
 *   - getLatestVersion filters status='published' so a half-baked row never
 *     wins ordering by published_at
 *   - getPluginVersions hides pending rows from the storefront version list
 *   - getVersion (specific-version lookup) hides pending rows too
 *   - finalizeVersion flips status='published' AND sets published_at AND
 *     records the real sha256/jar_size/signature in the same update
 *   - versionExists deliberately includes pending rows so a concurrent
 *     publish for the same version string is rejected
 *   - getVersionById sees pending rows - the finalize handler must find
 *     its own row to flip
 *
 * Each test drives the real service functions against a Supabase stub that
 * records every chain call. The point of asserting on the captured chain,
 * rather than on a returned value, is that the bug in #912 was a defect in
 * what the database was asked, not in what the service did after the
 * answer came back. A test that exercises the chain is the only one that
 * can fail against the broken code.
 *
 * Run: deno test --allow-all tests/version-lifecycle.test.ts
 */
import { assert, assertEquals } from "jsr:@std/assert"
import type { SupabaseClient } from "@supabase/supabase-js"
import {
  createVersion,
  finalizeVersion,
  getLatestVersion,
  getPluginVersions,
  getVersion,
  getVersionById,
  versionExists,
} from "../services/versions.ts"

/** A single method called on the Supabase chain, in order. */
interface Call {
  method: string
  args: unknown[]
}

/**
 * A Supabase stub that records every chain call. The terminal of every chain
 * resolves with `payload.data` / `payload.error`; that lets each test set up
 * exactly the response shape the function under test expects.
 *
 * Only the methods the service functions call are typed - if the code
 * grows new ones (e.g. `.in()`, `.or()`) the test will read `undefined`
 * from the proxy and throw, which is the desired loud failure: the stub
 * needs to grow with the production code.
 */
interface StubSupabase {
  client: SupabaseClient
  calls: Call[]
}

function makeStub(payload: { data: unknown; error: unknown } = { data: null, error: null }): StubSupabase {
  const calls: Call[] = []

  const chain: Record<string, unknown> = {}
  // Every chainable method records its call and returns the same chain.
  const methods = [
    "select",
    "insert",
    "update",
    "upsert",
    "delete",
    "eq",
    "neq",
    "gt",
    "gte",
    "lt",
    "lte",
    "in",
    "is",
    "like",
    "ilike",
    "match",
    "or",
    "filter",
    "order",
    "limit",
    "range",
  ]
  for (const m of methods) {
    chain[m] = (...args: unknown[]) => {
      calls.push({ method: m, args })
      return chain
    }
  }
  // Terminals resolve with the staged payload.
  chain.single = () => {
    calls.push({ method: "single", args: [] })
    return Promise.resolve(payload)
  }
  chain.maybeSingle = () => {
    calls.push({ method: "maybeSingle", args: [] })
    return Promise.resolve(payload)
  }
  // The chain itself is thenable, so `await client.from(...)...` works.
  chain.then = (onFulfilled: (v: unknown) => unknown, onRejected?: (e: unknown) => unknown) => {
    return Promise.resolve(payload).then(onFulfilled, onRejected)
  }

  const client: Record<string, unknown> = {
    from: (_table: string) => {
      calls.push({ method: "from", args: [_table] })
      return chain
    },
    rpc: (name: string, args?: Record<string, unknown>) => {
      calls.push({ method: "rpc", args: [name, args] })
      return Promise.resolve(payload)
    },
  }

  return { client: client as unknown as SupabaseClient, calls }
}

/** Was a filter recorded for `column == value` (matches `.eq(col, val)` or `.is(col, val)`)? */
function hasEq(calls: Call[], column: string, value: unknown): boolean {
  return calls.some((c) =>
    (c.method === "eq" || c.method === "is") &&
    c.args[0] === column &&
    c.args[1] === value
  )
}

/** Did any filter touch the given column, irrespective of operator/value? */
function hasFilterOn(calls: Call[], column: string): boolean {
  return calls.some((c) => c.args[0] === column)
}

// ---------------------------------------------------------------------------
// createVersion
// ---------------------------------------------------------------------------

Deno.test("createVersion inserts the row in 'pending' with published_at null", async () => {
  const { client, calls } = makeStub({ data: { id: "ver-1" }, error: null })

  await createVersion(
    client,
    "plugin-uuid",
    "1.2.3",
    "changelog",
    "1.0.0",
    "1.0.0",
    [],
    "plugins/foo/1.2.3/foo-1.2.3.jar",
    "",
  )

  const inserts = calls.filter((c) => c.method === "insert")
  assertEquals(inserts.length, 1, "createVersion must call insert() exactly once")
  const row = inserts[0].args[0] as Record<string, unknown>
  assertEquals(
    row.status,
    "pending",
    "createVersion must insert the row with status='pending'; this is the half of the lifecycle fix that hides the half-baked row from getLatestVersion",
  )
  assertEquals(
    row.published_at,
    null,
    "createVersion must set published_at to null. The schema default of NOW() is exactly what made the broken row 'latest' by ordering in #912.",
  )
})

Deno.test("createVersion calls insert() exactly once on plugin_versions", async () => {
  const { client, calls } = makeStub({ data: { id: "ver-1" }, error: null })

  await createVersion(
    client,
    "plugin-uuid",
    "1.2.3",
    "",
    "1.0.0",
    "1.0.0",
    [],
    "plugins/foo/1.2.3/foo-1.2.3.jar",
    "",
  )

  const froms = calls.filter((c) => c.method === "from")
  assertEquals(
    froms.length,
    1,
    "createVersion must hit exactly one table",
  )
  assertEquals(
    froms[0].args[0],
    "plugin_versions",
    "createVersion writes to plugin_versions",
  )
})

// ---------------------------------------------------------------------------
// finalizeVersion
// ---------------------------------------------------------------------------

Deno.test("finalizeVersion writes the row back to plugin_versions", async () => {
  const { client, calls } = makeStub({ data: null, error: null })

  await finalizeVersion(
    client,
    "ver-1",
    "a".repeat(64),
    12345,
    "ai.rever.boss.plugin.dynamic.example",
    "1.2.3",
  )

  const froms = calls.filter((c) => c.method === "from")
  assertEquals(froms.length, 1, "finalizeVersion must hit exactly one table")
  assertEquals(froms[0].args[0], "plugin_versions")

  const updates = calls.filter((c) => c.method === "update")
  assertEquals(updates.length, 1, "finalizeVersion must call update() exactly once")
  const patch = updates[0].args[0] as Record<string, unknown>
  assertEquals(
    patch.status,
    "published",
    "finalizeVersion must flip status='published'; this is what makes the row visible to getLatestVersion",
  )
  assertEquals(
    patch.sha256,
    "a".repeat(64),
    "finalizeVersion must record the server-computed sha256 (not 'pending') in the same update as the status flip",
  )
  assertEquals(
    patch.jar_size,
    12345,
    "finalizeVersion must record jar_size in the same update",
  )
  assert(
    typeof patch.published_at === "string" && patch.published_at.length > 0,
    "finalizeVersion must set published_at to a real timestamp; the column the 'latest' lookup orders by",
  )
  assert(
    hasEq(calls, "id", "ver-1"),
    "finalizeVersion must target the row by id",
  )
})

// ---------------------------------------------------------------------------
// getLatestVersion - the load-bearing filter
// ---------------------------------------------------------------------------

Deno.test("getLatestVersion filters status='published' so a half-baked row never wins", async () => {
  const { client, calls } = makeStub({ data: null, error: null })

  await getLatestVersion(client, "plugin-uuid")

  assert(
    hasEq(calls, "status", "published"),
    "getLatestVersion MUST filter status='published'. Without this filter, a row inserted by createVersion (status='pending', published_at=now()) wins ordering by published_at and ships a broken jar. See #912.",
  )
  assert(
    hasEq(calls, "plugin_id", "plugin-uuid"),
    "getLatestVersion must still scope by plugin_id",
  )
})

Deno.test("getLatestVersion returns null when the database returns no rows", async () => {
  const { client } = makeStub({ data: null, error: null })

  const latest = await getLatestVersion(client, "plugin-uuid")
  assertEquals(
    latest,
    null,
    "no published row -> null, not a thrown PGRST116. A pending-only row is exactly the case #912 ships today.",
  )
})

Deno.test("getLatestVersion uses maybeSingle(), not single()", async () => {
  const { client, calls } = makeStub({ data: null, error: null })

  await getLatestVersion(client, "plugin-uuid")

  // single() throws on zero rows (PGRST116). maybeSingle() returns null.
  // A null result is what we want when the only row is a pending one.
  const terminals = calls.filter((c) => c.method === "maybeSingle" || c.method === "single")
  assertEquals(
    terminals.filter((c) => c.method === "maybeSingle").length,
    1,
    "getLatestVersion must use maybeSingle() so an empty result is null, not a thrown error",
  )
  assertEquals(
    terminals.filter((c) => c.method === "single").length,
    0,
    "getLatestVersion must not use single() - it throws when no published row exists, which is the steady state for a plugin whose publish was abandoned mid-finalize",
  )
})

// ---------------------------------------------------------------------------
// getPluginVersions
// ---------------------------------------------------------------------------

Deno.test("getPluginVersions goes through the viewer-scoped + status-scoped RPC, not a direct table read", async () => {
  // Review feedback for #912: the previous implementation bypassed the
  // `get_plugin_versions` RPC with a direct `from('plugin_versions')` read.
  // That lost three properties the RPC provides:
  //
  //   1. the `status = 'published'` filter, so a pending row would surface
  //      in the storefront version list;
  //   2. the `user_can_view_plugin_row(viewer, ...)` visibility gate, which
  //      meant an organisation-private plugin's jar_path / sha256 would be
  //      served to any caller who knew the plugin id;
  //   3. the server-side `download_count` join, leaving every version list
  //      with `downloadCount = 0`.
  //
  // Asserting that the call goes through `rpc('get_plugin_versions', ...)` is
  // the only way to pin all three: a test on the chain cannot tell whether
  // the RPC is what the function calls.
  const { client, calls } = makeStub({ data: [], error: null })

  await getPluginVersions(client, "plugin-uuid")

  const rpcs = calls.filter((c) => c.method === "rpc")
  assertEquals(
    rpcs.length,
    1,
    "getPluginVersions must call exactly one RPC; a direct from('plugin_versions') read would lose the viewer-scope and download_count joins",
  )
  assertEquals(
    rpcs[0].args[0],
    "get_plugin_versions",
    "getPluginVersions must invoke the get_plugin_versions RPC; that is the single source of truth that filters status='published' and joins the viewer-scoped visibility check plus download_count",
  )
  assertEquals(
    (rpcs[0].args[1] as Record<string, unknown> | undefined)?.p_plugin_id,
    "plugin-uuid",
    "getPluginVersions must pass the plugin id as p_plugin_id so the RPC scopes by it",
  )
  // No direct table read should be issued alongside the RPC.
  assertEquals(
    calls.filter((c) => c.method === "from").length,
    0,
    "getPluginVersions must not also call from('plugin_versions'); the direct read would bypass the RPC's status and visibility filters",
  )
})

Deno.test("getPluginVersions still returns the downloadCount the RPC joins in", async () => {
  // Review feedback for #912: the direct-table read returned no
  // download_count, leaving every version list at 0. The RPC joins
  // plugin_downloads server-side, and the caller surfaces that as
  // downloadCount on each row.
  const { client } = makeStub({
    data: [
      {
        id: "ver-1",
        version: "1.2.3",
        changelog: "changelog",
        min_boss_version: "1.0.0",
        min_ipc_version: "1.0.0",
        min_api_version: "",
        jar_path: "plugins/foo/1.2.3/foo.jar",
        jar_size: 12345,
        sha256: "a".repeat(64),
        dependencies: [],
        published_at: "2026-09-20T12:00:00Z",
        download_count: 42,
      },
    ],
    error: null,
  })

  const versions = await getPluginVersions(client, "plugin-uuid")
  assertEquals(versions.length, 1)
  assertEquals(
    versions[0].downloadCount,
    42,
    "getPluginVersions must surface the server-side download_count from the RPC; the direct table read dropped it",
  )
})

// ---------------------------------------------------------------------------
// getVersion (specific-version lookup)
// ---------------------------------------------------------------------------

Deno.test("getVersion filters status='published' so a name lookup for an unfinalized version 404s", async () => {
  const { client, calls } = makeStub({ data: null, error: null })

  await getVersion(client, "plugin-uuid", "1.2.3")

  assert(
    hasEq(calls, "status", "published"),
    "getVersion must hide pending rows; a pending version is indistinguishable from a non-existent one from the caller's point of view, and both should 404",
  )
  assert(
    hasEq(calls, "plugin_id", "plugin-uuid"),
    "getVersion must scope by plugin_id",
  )
  assert(
    hasEq(calls, "version", "1.2.3"),
    "getVersion must scope by version string",
  )
})

Deno.test("getVersion returns null when the version is pending", async () => {
  const { client } = makeStub({ data: null, error: null })

  const v = await getVersion(client, "plugin-uuid", "1.2.3")
  assertEquals(v, null, "pending or missing version -> null, not a thrown error")
})

// ---------------------------------------------------------------------------
// getVersionById
// ---------------------------------------------------------------------------

Deno.test("getVersionById does NOT filter by status - the finalize handler must find its own pending row", async () => {
  // This is the one reader that must see pending rows: the finalize handler
  // calls it with the versionId returned by createVersion, and that row is
  // `pending` until finalize runs. If this filtered on status, finalize
  // would never find its own row to flip.
  const { client, calls } = makeStub({ data: { id: "ver-1", status: "pending" }, error: null })

  const v = await getVersionById(client, "ver-1")
  assert(v !== null, "getVersionById must find its own pending row so finalize can flip it")
  assert(
    !hasFilterOn(calls, "status"),
    "getVersionById must NOT add a status filter; finalize calls it on the pending row it is about to publish",
  )
})

// ---------------------------------------------------------------------------
// versionExists
// ---------------------------------------------------------------------------

Deno.test("versionExists intentionally includes pending rows so a duplicate publish is rejected", async () => {
  // Collision check on (plugin_id, version) MUST see pending rows: if a
  // publisher kills their client mid-upload and tries again with the same
  // version, they should be told the version already exists - not silently
  // double-published. A status filter here would let the second publish
  // succeed and leave two rows competing for the same version string.
  const { client, calls } = makeStub({ data: { id: "ver-1" }, error: null })

  await versionExists(client, "plugin-uuid", "1.2.3")

  assert(
    !hasFilterOn(calls, "status"),
    "versionExists must NOT filter by status - pending rows are still 'exists' for collision avoidance",
  )
  assert(
    hasEq(calls, "plugin_id", "plugin-uuid"),
    "versionExists must scope by plugin_id",
  )
  assert(
    hasEq(calls, "version", "1.2.3"),
    "versionExists must scope by version string",
  )
})
