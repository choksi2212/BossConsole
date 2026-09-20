import type { SupabaseClient } from "@supabase/supabase-js"
import { withErrorHandler } from "../utils/error-handler.ts"
import { normalizeBase64Url } from "../utils/base64.ts"

/**
 * Normalises a PostgREST result that may be a single row or an array of rows.
 * Mirrors the helper in utils/database.ts; duplicated locally to avoid widening
 * that module's surface for one extra caller.
 */
function rowsOf<T>(data: T | T[] | null | undefined): T[] {
  if (Array.isArray(data)) return data
  return data ? [data] : []
}

/**
 * Mobile Registration Service
 * Handles business logic for mobile registration HTML page generation
 */
export const generateMobileRegistrationPage = withErrorHandler(
  async (
    supabase: SupabaseClient,
    challenge: string,
    email: string,
    sessionId: string,
    rpId: string,
    rpName: string
  ) => {
    console.log('📱 Generating mobile registration page for:', email)

    // Verify challenge exists and is valid
    const { data: challengeData, error: challengeError } = await supabase
      .from('passkey_challenges')
      .select('*')
      .eq('challenge', challenge)
      .eq('type', 'registration')
      .gt('expires_at', new Date().toISOString())
      .single()

    if (challengeError || !challengeData) {
      console.error('❌ Invalid or expired challenge:', challengeError)
      return {
        success: false,
        error: 'Invalid or expired registration link'
      }
    }

    // Get userId from the challenge data - it was stored when the challenge was created
    const userId = challengeData.user_id
    if (!userId) {
      console.error('❌ Challenge does not have user_id')
      return {
        success: false,
        error: 'Invalid registration challenge'
      }
    }

    console.log('✅ Found userId from challenge:', userId)

    // Bind session_id to the challenge row, refusing any rebind attempt.
    //
    // A second page load with a DIFFERENT session_id would otherwise overwrite
    // the bound session and redirect the completed ceremony's token handoff to
    // whichever session id last won the write (issue #924). We:
    //   - reject when the row is already bound to a different session_id
    //   - bind via compare-and-set on session_id IS NULL otherwise, so a
    //     concurrent first load cannot lose its claim to a racing request
    //   - allow a same-session reload to refresh status to in_progress
    const existingSessionId = challengeData.session_id
    if (existingSessionId !== null && existingSessionId !== sessionId) {
      console.error('❌ Mobile registration attempted to rebind challenge session:', challenge)
      return {
        success: false,
        error: 'Challenge session already bound to a different session'
      }
    }

    if (existingSessionId === null) {
      const { data: bound, error: bindError } = await supabase
        .from('passkey_challenges')
        .update({
          session_id: sessionId,
          status: 'in_progress'
        })
        .eq('challenge', challenge)
        // CAS: only bind if the row is still unbound. Two concurrent first-load
        // requests carrying different session_ids will both read session_id as
        // NULL; only one of them will see the row it just updated.
        .not('session_id', 'is', null)
        .select('id')

      if (bindError || rowsOf(bound).length === 0) {
        console.error('❌ Challenge session was concurrently bound by another request:', challenge)
        return {
          success: false,
          error: 'Challenge session was concurrently bound by another request'
        }
      }
    } else {
      // Same session - legitimate reload. Refresh status only; session_id is
      // already correct and writing it again would burn the UPDATE row count.
      await supabase
        .from('passkey_challenges')
        .update({ status: 'in_progress' })
        .eq('challenge', challenge)
    }

    console.log('✅ Mobile registration page ready for user:', userId)

    return {
      success: true,
      userId,
      email,
      challenge,
      sessionId,
      rpId,
      rpName
    }
  },
  'Failed to generate mobile registration page',
  '📱'
)

/**
 * Mobile Authentication Service
 * Handles business logic for mobile authentication HTML page generation
 */
export const generateMobileAuthenticationPage = withErrorHandler(
  async (
    supabase: SupabaseClient,
    challenge: string,
    email: string,
    sessionId: string,
    credentialId: string,
    rpId: string
  ) => {
    console.log('📱 Generating mobile authentication page for:', email)

    // Verify challenge is valid
    const { data: challengeData, error: challengeError } = await supabase
      .from('passkey_challenges')
      .select('*')
      .eq('challenge', challenge)
      .eq('type', 'authentication')
      .gt('expires_at', new Date().toISOString())
      .single()

    if (challengeError || !challengeData) {
      console.error('❌ Invalid or expired challenge:', challengeError)
      return {
        success: false,
        error: 'Invalid or expired authentication challenge'
      }
    }

    // Get userId from the challenge data - it was stored when the challenge was created
    const userId = challengeData.user_id
    if (!userId) {
      console.error('❌ Challenge does not have user_id')
      return {
        success: false,
        error: 'Invalid authentication challenge'
      }
    }

    console.log('✅ Found userId from challenge:', userId)

    // Get user's passkey credential
    const { data: passkey, error: passkeyError } = await supabase
      .from('user_passkeys')
      .select('*')
      // Canonicalised, like findPasskeyByCredentialId: credential ids are stored
      // as unpadded base64url, so an exact match on a padded or standard-base64
      // parameter would miss the row
      .eq('credential_id', normalizeBase64Url(credentialId))
      .eq('user_id', userId)
      .eq('active', true)
      .single()

    if (passkeyError || !passkey) {
      console.error('❌ Credential not found:', passkeyError)
      return {
        success: false,
        error: 'Authentication credential not found'
      }
    }

    // Bind session_id to the challenge row, refusing any rebind attempt.
    // See generateMobileRegistrationPage for the rationale (issue #924).
    const existingSessionId = challengeData.session_id
    if (existingSessionId !== null && existingSessionId !== sessionId) {
      console.error('❌ Mobile authentication attempted to rebind challenge session:', challenge)
      return {
        success: false,
        error: 'Challenge session already bound to a different session'
      }
    }

    if (existingSessionId === null) {
      const { data: bound, error: bindError } = await supabase
        .from('passkey_challenges')
        .update({
          session_id: sessionId,
          status: 'in_progress'
        })
        .eq('challenge', challenge)
        .not('session_id', 'is', null)
        .select('id')

      if (bindError || rowsOf(bound).length === 0) {
        console.error('❌ Challenge session was concurrently bound by another request:', challenge)
        return {
          success: false,
          error: 'Challenge session was concurrently bound by another request'
        }
      }
    } else {
      await supabase
        .from('passkey_challenges')
        .update({ status: 'in_progress' })
        .eq('challenge', challenge)
    }

    console.log('✅ Mobile authentication page ready for user:', userId)

    return {
      success: true,
      email,
      challenge,
      sessionId,
      rpId,
      credentialId,
      credentialDisplayName: passkey.display_name,
      credentialCreatedAt: passkey.created_at
    }
  },
  'Failed to generate mobile authentication page',
  '📱'
)
