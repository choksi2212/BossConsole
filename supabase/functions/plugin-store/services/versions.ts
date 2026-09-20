import type { SupabaseClient } from "@supabase/supabase-js"
import type { PluginVersion, PluginDependency } from "../types/plugin.ts"
import { signVersionAnchor } from "../utils/signing.ts"

/**
 * Get all published versions of a plugin.
 *
 * Pending rows (inserted by `createVersion` but not yet finalized) are NOT
 * returned: they have `sha256='pending'`, `jar_size=0` and a `jar_path`
 * pointing at a storage key that may not exist yet. Returning them to the
 * storefront would let users click on a version that 404s on download. See
 * #912 for the broken-state history.
 */
export async function getPluginVersions(
  supabase: SupabaseClient,
  pluginId: string
): Promise<PluginVersion[]> {
  const { data, error } = await supabase
    .from('plugin_versions')
    .select('*')
    .eq('plugin_id', pluginId)
    // Filtered in code rather than via an RPC so the new column is consulted
    // immediately, without a migration on `get_plugin_versions`.
    .eq('status', 'published')
    .order('published_at', { ascending: false })

  if (error) {
    console.error('Error getting plugin versions:', error)
    throw new Error(`Failed to get versions: ${error.message}`)
  }

  return (data || []).map((row: Record<string, unknown>) => ({
    id: row.id as string,
    pluginId: pluginId,
    version: row.version as string,
    changelog: row.changelog as string,
    minBossVersion: row.min_boss_version as string,
    minIpcVersion: (row.min_ipc_version as string) ?? '1.0.0',
    minApiVersion: (row.min_api_version as string) ?? '',
    jarPath: row.jar_path as string,
    jarSize: Number(row.jar_size) || 0,
    sha256: row.sha256 as string,
    dependencies: (row.dependencies as PluginDependency[]) || [],
    publishedAt: row.published_at as string,
    downloadCount: Number(row.download_count) || 0
  }))
}

/**
 * Get the latest published version of a plugin.
 *
 * Filters `status = 'published'` so a half-baked row - inserted by
 * `createVersion` before the JAR was uploaded - never wins this lookup.
 * Before this filter, the schema default of `published_at = NOW()` made
 * every freshly inserted row the "latest" version by ordering, and users
 * asking for `GET /:pluginId/download` got a row whose `sha256` was the
 * literal string `pending` and whose `jar_path` pointed at a storage key
 * that did not exist. See #912.
 */
export async function getLatestVersion(
  supabase: SupabaseClient,
  pluginUuid: string
): Promise<PluginVersion | null> {
  const { data, error } = await supabase
    .from('plugin_versions')
    .select('*')
    .eq('plugin_id', pluginUuid)
    .eq('status', 'published')
    .order('published_at', { ascending: false })
    .limit(1)
    .maybeSingle()

  if (error) {
    console.error('Error getting latest version:', error)
    throw new Error(`Failed to get latest version: ${error.message}`)
  }

  if (!data) return null

  return {
    id: data.id,
    pluginId: data.plugin_id,
    version: data.version,
    changelog: data.changelog,
    minBossVersion: data.min_boss_version,
    minIpcVersion: (data.min_ipc_version as string) ?? '1.0.0',
    minApiVersion: (data.min_api_version as string) ?? '',
    jarPath: data.jar_path,
    jarSize: Number(data.jar_size) || 0,
    sha256: data.sha256,
    signature: (data.signature as string | null) ?? null,
    dependencies: data.dependencies || [],
    publishedAt: data.published_at
  }
}

/**
 * Get a specific published version by plugin UUID and version string.
 *
 * Pending rows are excluded: a name lookup for an unfinalized version is
 * indistinguishable, from the caller's point of view, from a version that
 * does not exist at all, and both should 404 rather than serve a broken or
 * poisoned jar.
 */
export async function getVersion(
  supabase: SupabaseClient,
  pluginUuid: string,
  version: string
): Promise<PluginVersion | null> {
  const { data, error } = await supabase
    .from('plugin_versions')
    .select('*')
    .eq('plugin_id', pluginUuid)
    .eq('version', version)
    .eq('status', 'published')
    .maybeSingle()

  if (error) {
    console.error('Error getting version:', error)
    throw new Error(`Failed to get version: ${error.message}`)
  }

  if (!data) return null

  return {
    id: data.id,
    pluginId: data.plugin_id,
    version: data.version,
    changelog: data.changelog,
    minBossVersion: data.min_boss_version,
    minIpcVersion: (data.min_ipc_version as string) ?? '1.0.0',
    minApiVersion: (data.min_api_version as string) ?? '',
    jarPath: data.jar_path,
    jarSize: Number(data.jar_size) || 0,
    sha256: data.sha256,
    signature: (data.signature as string | null) ?? null,
    dependencies: data.dependencies || [],
    publishedAt: data.published_at
  }
}

/**
 * Get a version by its UUID
 */
export async function getVersionById(
  supabase: SupabaseClient,
  versionId: string
): Promise<PluginVersion | null> {
  const { data, error } = await supabase
    .from('plugin_versions')
    .select('*')
    .eq('id', versionId)
    .single()

  if (error) {
    if (error.code === 'PGRST116') return null // Not found
    console.error('Error getting version by ID:', error)
    throw new Error(`Failed to get version: ${error.message}`)
  }

  return {
    id: data.id,
    pluginId: data.plugin_id,
    version: data.version,
    changelog: data.changelog,
    minBossVersion: data.min_boss_version,
    minIpcVersion: (data.min_ipc_version as string) ?? '1.0.0',
    minApiVersion: (data.min_api_version as string) ?? '',
    jarPath: data.jar_path,
    jarSize: Number(data.jar_size) || 0,
    sha256: data.sha256,
    signature: (data.signature as string | null) ?? null,
    dependencies: data.dependencies || [],
    publishedAt: data.published_at
  }
}

/**
 * Create a new version (pending JAR upload).
 *
 * The row is inserted in `status = 'pending'`, with `published_at` explicitly
 * null, so it is invisible to `getLatestVersion` and `getPluginVersions`
 * until `finalizeVersion` runs. Before this, the schema default of
 * `published_at = NOW()` made every freshly inserted row the "latest"
 * version by ordering, and consumers downloaded a row whose jar did not yet
 * exist. See #912.
 */
export async function createVersion(
  supabase: SupabaseClient,
  pluginUuid: string,
  version: string,
  changelog: string,
  minBossVersion: string,
  minIpcVersion: string,
  dependencies: PluginDependency[],
  jarPath: string,
  minApiVersion: string = ''
): Promise<{ id: string }> {
  const { data, error } = await supabase
    .from('plugin_versions')
    .insert({
      plugin_id: pluginUuid,
      version,
      changelog,
      min_boss_version: minBossVersion,
      min_ipc_version: minIpcVersion,
      min_api_version: minApiVersion,
      dependencies,
      jar_path: jarPath,
      // The row is in the `pending` lifecycle until `finalizeVersion`
      // observes the uploaded JAR and flips it to `published`. Filtering on
      // status (not on `sha256 <> 'pending'`) is deliberate: a finalized
      // row whose JAR truly did hash to `pending` would be impossible
      // anyway, but a string check is one more implicit invariant this
      // service should not have.
      status: 'pending',
      sha256: 'pending', // Will be updated after upload
      jar_size: 0,
      published_at: null
    })
    .select('id')
    .single()

  if (error) {
    console.error('Error creating version:', error)
    if (error.code === '23505') {
      throw new Error('Version already exists')
    }
    throw new Error(`Failed to create version: ${error.message}`)
  }

  return { id: data.id }
}

/**
 * Finalize a version after JAR upload.
 *
 * Single point at which the row transitions `pending -> published`. The
 * same update records the real `sha256`, `jar_size` and (if configured)
 * the store signature, and stamps `published_at` to now. The row becomes
 * eligible for `getLatestVersion` only after this returns successfully -
 * a half-applied update (e.g. client died mid-update) would leave the row
 * `pending` and invisible, not `published` with a `pending` hash.
 */
export async function finalizeVersion(
  supabase: SupabaseClient,
  versionId: string,
  sha256: string,
  jarSize: number,
  pluginId: string,
  version: string
): Promise<void> {
  // Sign the canonical anchor pluginId|version|sha256 — binding identity and
  // version, not just the digest, so store-signed artifacts aren't mutually
  // substitutable. Null (no signing key configured) leaves the version
  // unsigned, which hosts currently treat as warn-only.
  const signature = await signVersionAnchor(pluginId, version, sha256)
  if (signature === null) {
    // Deliberate never-block-publish behavior, but the degraded state must
    // be observable: this version ships unsigned (warn-only on hosts) until
    // a backfill --re-sign-all pass.
    console.error(`PUBLISHED UNSIGNED: ${pluginId} v${version} (versionId=${versionId}) — signing unavailable`)
  }

  const { error } = await supabase
    .from('plugin_versions')
    .update({
      // Flip the lifecycle. Without this, the row inserted by createVersion
      // stays `pending` and is invisible to every "latest" / "list" lookup.
      // Pairing the status flip with the sha256/jar_size/signature update
      // means a half-applied update leaves the row pending rather than
      // half-correct.
      status: 'published',
      sha256,
      jar_size: jarSize,
      signature,
      // `published_at` is the column `getLatestVersion` orders by; setting
      // it here (rather than at createVersion) is what makes the finalized
      // row the new latest. NULL up to now.
      published_at: new Date().toISOString()
    })
    .eq('id', versionId)

  if (error) {
    console.error('Error finalizing version:', error)
    throw new Error(`Failed to finalize version: ${error.message}`)
  }
}

/**
 * Check if a version exists
 */
export async function versionExists(
  supabase: SupabaseClient,
  pluginUuid: string,
  version: string
): Promise<boolean> {
  const { data, error } = await supabase
    .from('plugin_versions')
    .select('id')
    .eq('plugin_id', pluginUuid)
    .eq('version', version)
    .maybeSingle()

  if (error) {
    console.error('Error checking version existence:', error)
    throw new Error(`Failed to check version: ${error.message}`)
  }

  return data !== null
}
