/**
 * Tests for Mobile Service
 */

import { assertEquals, assertExists } from "jsr:@std/assert"
import type { SupabaseClient } from "@supabase/supabase-js"
import { generateMobileRegistrationPage, generateMobileAuthenticationPage } from "../services/mobile.ts"
import { createMockSupabaseClient, mockChallenge, mockPasskey } from "./helpers/mocks.ts"

// ============================================================================
// Mobile Registration Tests
// ============================================================================

Deno.test("generateMobileRegistrationPage - should generate valid registration page data", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock valid registration challenge (session_id unbound - first page load)
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'registration',
      session_id: null,
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  // Mock user lookup from auth.users table
  mockClient.mockResponse('auth.users', {
    data: { id: 'user-456', email: 'test@example.com' },
    error: null
  }, 'select')

  // Mock update challenge with session
  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789' }],
    error: null
  }, 'update')

  const result = await generateMobileRegistrationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-123',
    'api.risaboss.com',
    'BOSS'
  )

  assertEquals(result.success, true)
  if (result.success) {
    assertEquals(result.challenge, 'mock-challenge-base64')
    assertEquals(result.email, 'test@example.com')
    assertEquals(result.sessionId, 'session-123')
    assertEquals(result.rpId, 'api.risaboss.com')
    assertEquals(result.rpName, 'BOSS')
    assertExists(result.userId)
  }
})

Deno.test("generateMobileRegistrationPage - should reject expired challenge", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock expired challenge
  mockClient.mockResponse('passkey_challenges', {
    data: null,
    error: { code: 'PGRST116' }
  }, 'select')

  const result = await generateMobileRegistrationPage(
    mockClient as unknown as SupabaseClient,
    'expired-challenge',
    'test@example.com',
    'session-123',
    'api.risaboss.com',
    'BOSS'
  )

  assertEquals(result.success, false)
  if (!result.success) {
    assertExists(result.error)
    assertEquals(result.error, 'Invalid or expired registration link')
  }
})

Deno.test("generateMobileRegistrationPage - should reject wrong challenge type", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock authentication challenge instead of registration (database would not return this due to .eq('type', 'registration'))
  // Our mock doesn't enforce filters, so we simulate the database behavior by returning null
  mockClient.mockResponse('passkey_challenges', {
    data: null,
    error: { code: 'PGRST116' }
  }, 'select')

  const result = await generateMobileRegistrationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-123',
    'api.risaboss.com',
    'BOSS'
  )

  assertEquals(result.success, false)
  if (!result.success) {
    assertExists(result.error)
  }
})

Deno.test("generateMobileRegistrationPage - should reject challenge without user_id", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock challenge without user_id
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      user_id: null, // Challenge doesn't have user_id
      type: 'registration',
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  const result = await generateMobileRegistrationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-123',
    'api.risaboss.com',
    'BOSS'
  )

  assertEquals(result.success, false)
  if (!result.success) {
    assertExists(result.error)
    assertEquals(result.error, 'Invalid registration challenge')
  }
})

Deno.test("generateMobileRegistrationPage - should update challenge status to in_progress", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock valid challenge (session_id unbound - first page load)
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'registration',
      session_id: null,
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  // Mock user lookup
  mockClient.mockResponse('auth.users', {
    data: { id: 'user-456', email: 'test@example.com' },
    error: null
  }, 'select')

  // Mock update with in_progress status
  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789', status: 'in_progress' }],
    error: null
  }, 'update')

  const result = await generateMobileRegistrationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-123',
    'api.risaboss.com',
    'BOSS'
  )

  assertEquals(result.success, true)

  // Verify update was called
  const history = mockClient.getQueryHistory()
  const updateCall = history.find(h => h.operation === 'update')
  assertExists(updateCall)
})

// ============================================================================
// Mobile Authentication Tests
// ============================================================================

Deno.test("generateMobileAuthenticationPage - should generate valid authentication page data", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock valid authentication challenge (session_id unbound - first page load)
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'authentication',
      session_id: null,
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  // Mock user lookup
  mockClient.mockResponse('auth.users', {
    data: { id: 'user-456', email: 'test@example.com' },
    error: null
  }, 'select')

  // Mock passkey lookup
  mockClient.mockResponse('user_passkeys', {
    data: mockPasskey,
    error: null
  }, 'select')

  // Mock challenge update
  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789' }],
    error: null
  }, 'update')

  const result = await generateMobileAuthenticationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-123',
    'credential-abc',
    'api.risaboss.com'
  )

  assertEquals(result.success, true)
  if (result.success) {
    assertEquals(result.challenge, 'mock-challenge-base64')
    assertEquals(result.email, 'test@example.com')
    assertEquals(result.sessionId, 'session-123')
    assertEquals(result.rpId, 'api.risaboss.com')
    assertEquals(result.credentialId, 'credential-abc')
    assertEquals(result.credentialDisplayName, mockPasskey.display_name)
    assertExists(result.credentialCreatedAt)
  }
})

Deno.test("generateMobileAuthenticationPage - should reject expired challenge", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock expired challenge
  mockClient.mockResponse('passkey_challenges', {
    data: null,
    error: { code: 'PGRST116' }
  }, 'select')

  const result = await generateMobileAuthenticationPage(
    mockClient as unknown as SupabaseClient,
    'expired-challenge',
    'test@example.com',
    'session-123',
    'credential-abc',
    'api.risaboss.com'
  )

  assertEquals(result.success, false)
  if (!result.success) {
    assertExists(result.error)
    assertEquals(result.error, 'Invalid or expired authentication challenge')
  }
})

Deno.test("generateMobileAuthenticationPage - should reject wrong challenge type", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock registration challenge instead of authentication (database would not return this due to .eq('type', 'authentication'))
  // Our mock doesn't enforce filters, so we simulate the database behavior by returning null
  mockClient.mockResponse('passkey_challenges', {
    data: null,
    error: { code: 'PGRST116' }
  }, 'select')

  const result = await generateMobileAuthenticationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-123',
    'credential-abc',
    'api.risaboss.com'
  )

  assertEquals(result.success, false)
  if (!result.success) {
    assertExists(result.error)
  }
})

Deno.test("generateMobileAuthenticationPage - should reject challenge without user_id", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock challenge without user_id
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      user_id: null, // Challenge doesn't have user_id
      type: 'authentication',
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  const result = await generateMobileAuthenticationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-123',
    'credential-abc',
    'api.risaboss.com'
  )

  assertEquals(result.success, false)
  if (!result.success) {
    assertExists(result.error)
    assertEquals(result.error, 'Invalid authentication challenge')
  }
})

Deno.test("generateMobileAuthenticationPage - should reject non-existent credential", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock valid challenge
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'authentication',
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  // Mock user lookup
  mockClient.mockResponse('auth.users', {
    data: { id: 'user-456', email: 'test@example.com' },
    error: null
  }, 'select')

  // Mock credential not found
  mockClient.mockResponse('user_passkeys', {
    data: null,
    error: { code: 'PGRST116' }
  }, 'select')

  const result = await generateMobileAuthenticationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-123',
    'nonexistent-credential',
    'api.risaboss.com'
  )

  assertEquals(result.success, false)
  if (!result.success) {
    assertExists(result.error)
    assertEquals(result.error, 'Authentication credential not found')
  }
})

Deno.test("generateMobileAuthenticationPage - should reject inactive credential", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock valid challenge
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'authentication',
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  // Mock user lookup
  mockClient.mockResponse('auth.users', {
    data: { id: 'user-456', email: 'test@example.com' },
    error: null
  }, 'select')

  // Mock inactive passkey (won't be returned due to active=true filter)
  mockClient.mockResponse('user_passkeys', {
    data: null,
    error: { code: 'PGRST116' }
  }, 'select')

  const result = await generateMobileAuthenticationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-123',
    'credential-abc',
    'api.risaboss.com'
  )

  assertEquals(result.success, false)
  if (!result.success) {
    assertExists(result.error)
    assertEquals(result.error, 'Authentication credential not found')
  }
})

Deno.test("generateMobileAuthenticationPage - should update challenge status to in_progress", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock valid challenge (session_id unbound - first page load)
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'authentication',
      session_id: null,
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  // Mock user lookup
  mockClient.mockResponse('auth.users', {
    data: { id: 'user-456', email: 'test@example.com' },
    error: null
  }, 'select')

  // Mock passkey lookup
  mockClient.mockResponse('user_passkeys', {
    data: mockPasskey,
    error: null
  }, 'select')

  // Mock challenge update
  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789', status: 'in_progress' }],
    error: null
  }, 'update')

  const result = await generateMobileAuthenticationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-123',
    'credential-abc',
    'api.risaboss.com'
  )

  assertEquals(result.success, true)

  // Verify update was called
  const history = mockClient.getQueryHistory()
  const updateCall = history.find(h => h.operation === 'update')
  assertExists(updateCall)
})

Deno.test("generateMobileAuthenticationPage - should return credential metadata", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock valid challenge (session_id unbound - first page load)
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'authentication',
      session_id: null,
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  // Mock user lookup
  mockClient.mockResponse('auth.users', {
    data: { id: 'user-456', email: 'test@example.com' },
    error: null
  }, 'select')

  // Mock passkey with custom display name and creation time
  mockClient.mockResponse('user_passkeys', {
    data: {
      ...mockPasskey,
      display_name: 'iPhone 15 Pro',
      created_at: '2024-10-01T12:00:00Z'
    },
    error: null
  }, 'select')

  // Mock challenge update
  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789' }],
    error: null
  }, 'update')

  const result = await generateMobileAuthenticationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-123',
    'credential-abc',
    'api.risaboss.com'
  )

  assertEquals(result.success, true)
  if (result.success) {
    assertEquals(result.credentialDisplayName, 'iPhone 15 Pro')
    assertEquals(result.credentialCreatedAt, '2024-10-01T12:00:00Z')
  }
})

// ============================================================================
// Session rebinding regressions (issue #924)
//
// The mobile page is public and unauthenticated, so a URL parameter must NOT
// be able to silently rebind the challenge row's session_id - that would
// redirect the completed ceremony's token handoff to whichever session_id last
// won the write. These tests pin the `.is('session_id', null)` compare-and-set
// that closes the gap (a `.not('session_id', 'is', null)` would do the
// opposite and refuse every legitimate first bind), and the same-session
// reload path that keeps legitimate page refreshes working.
// ============================================================================

Deno.test("generateMobileRegistrationPage - rejects second load with a DIFFERENT sessionId (issue #924)", async () => {
  const mockClient = createMockSupabaseClient()

  // The victim's page load bound the row to session-123 first. The attacker
  // is now replaying the same page URL but with their own sessionId.
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'registration',
      session_id: 'session-123', // already bound to the legitimate session
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  // NO update should be issued - the rebinding must be refused before any write.
  const result = await generateMobileRegistrationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'attacker-session', // the URL parameter the attacker is trying to bind
    'api.risaboss.com',
    'BOSS'
  )

  assertEquals(result.success, false)
  if (!result.success) {
    assertEquals(result.error, 'Challenge session already bound to a different session')
  }

  const history = mockClient.getQueryHistory()
  const updateCalls = history.filter(h => h.operation === 'update')
  assertEquals(updateCalls.length, 0, 'rebind attempt must not produce any UPDATE')
})

Deno.test("generateMobileRegistrationPage - allows same-session reload to refresh status (issue #924)", async () => {
  const mockClient = createMockSupabaseClient()

  // Row is already bound to the same sessionId the URL carries - this is a
  // legitimate page reload (e.g. user reopened the tab), not a rebind.
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'registration',
      session_id: 'session-123',
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  // Same-session reload only refreshes status; the session_id update must be
  // skipped so the CAS row count stays meaningful in the rebinding tests.
  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789' }],
    error: null
  }, 'update')

  const result = await generateMobileRegistrationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-123', // matches the bound session
    'api.risaboss.com',
    'BOSS'
  )

  assertEquals(result.success, true)
  if (result.success) {
    assertEquals(result.sessionId, 'session-123')
  }

  // The update must NOT carry the session_id clause - it's a status-only refresh.
  const history = mockClient.getQueryHistory()
  const updateCall = history.find(h => h.operation === 'update')
  assertExists(updateCall)
  assertEquals((updateCall!.params.data as { session_id?: string }).session_id, undefined)
})

Deno.test("generateMobileRegistrationPage - rejects CAS race on concurrent first-load (issue #924)", async () => {
  const mockClient = createMockSupabaseClient()

  // Both racing requests read the row as unbound; the first one wins the
  // CAS, the second sees the row it tried to update is gone from the filter.
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'registration',
      session_id: null,
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  // First write succeeds.
  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789' }],
    error: null
  }, 'update')

  const first = await generateMobileRegistrationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-first',
    'api.risaboss.com',
    'BOSS'
  )
  assertEquals(first.success, true)

  // Second racing first-load reads the row as still unbound (its SELECT
  // happened before the first CAS landed), then its CAS update comes back
  // empty: the .is('session_id', null) filter no longer matches.
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'registration',
      session_id: null,
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')
  mockClient.mockResponse('passkey_challenges', {
    data: [],
    error: null
  }, 'update')

  const second = await generateMobileRegistrationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-second',
    'api.risaboss.com',
    'BOSS'
  )

  assertEquals(second.success, false)
  if (!second.success) {
    assertEquals(second.error, 'Challenge session was concurrently bound by another request')
  }
})

Deno.test("generateMobileAuthenticationPage - rejects second load with a DIFFERENT sessionId (issue #924)", async () => {
  const mockClient = createMockSupabaseClient()

  // The victim's first page load bound the row. The attacker is replaying the
  // page URL with their own sessionId - the rebind must be refused even though
  // the credential lookup would otherwise have succeeded.
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'authentication',
      session_id: 'session-123',
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  // The credential exists - the rebind check must run BEFORE the result is
  // discarded, so this mock stays here to make the order of operations explicit.
  mockClient.mockResponse('user_passkeys', {
    data: mockPasskey,
    error: null
  }, 'select')

  const result = await generateMobileAuthenticationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'attacker-session',
    'credential-abc',
    'api.risaboss.com'
  )

  assertEquals(result.success, false)
  if (!result.success) {
    assertEquals(result.error, 'Challenge session already bound to a different session')
  }

  const history = mockClient.getQueryHistory()
  const updateCalls = history.filter(h => h.operation === 'update')
  assertEquals(updateCalls.length, 0, 'rebind attempt must not produce any UPDATE')
})

Deno.test("generateMobileAuthenticationPage - allows same-session reload to refresh status (issue #924)", async () => {
  const mockClient = createMockSupabaseClient()

  // Row already bound to the same sessionId the URL carries - legitimate reload.
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'authentication',
      session_id: 'session-123',
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  // The credential lookup still has to succeed for the reload to work.
  mockClient.mockResponse('user_passkeys', {
    data: mockPasskey,
    error: null
  }, 'select')

  // Same-session reload: status refresh only, no session_id column in the write.
  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789' }],
    error: null
  }, 'update')

  const result = await generateMobileAuthenticationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-123',
    'credential-abc',
    'api.risaboss.com'
  )

  assertEquals(result.success, true)
  if (result.success) {
    assertEquals(result.sessionId, 'session-123')
  }

  const history = mockClient.getQueryHistory()
  const updateCall = history.find(h => h.operation === 'update')
  assertExists(updateCall)
  assertEquals((updateCall!.params.data as { session_id?: string }).session_id, undefined)
})

// ============================================================================
// Filter-aware CAS coverage for issue #924
//
// The mock used to ignore the `.is(...)` / `.not(...)` clause on UPDATE entirely
// and return whatever the test had queued next. That let the inverted
// `.not('session_id', 'is', null)` predicate pass review even though real
// PostgREST would have rejected every legitimate first bind. These tests pin
// the corrected `.is('session_id', null)` predicate by asserting the WHERE
// clause the query actually carries AND by checking the second bind's outcome
// against the row state the first bind left behind.
// ============================================================================

Deno.test("generateMobileRegistrationPage - first bind SUCCEEDS because session_id IS NULL (issue #924)", async () => {
  const mockClient = createMockSupabaseClient()

  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'registration',
      session_id: null,
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')
  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789' }],
    error: null
  }, 'update')

  const result = await generateMobileRegistrationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-first',
    'api.risaboss.com',
    'BOSS'
  )

  assertEquals(result.success, true)

  const history = mockClient.getQueryHistory()
  const updateCall = history.find(h => h.operation === 'update')
  assertExists(updateCall)
  const isFilters = (updateCall!.params.is ?? []) as Array<{ column: string; value: unknown }>
  assertEquals(isFilters.length, 1, 'CAS must carry exactly one `.is()` filter')
  assertEquals(isFilters[0].column, 'session_id')
  assertEquals(isFilters[0].value, null)
})

Deno.test("generateMobileRegistrationPage - second bind FAILS because session_id IS no longer null (issue #924)", async () => {
  const mockClient = createMockSupabaseClient()

  // 1. First legitimate page load: row unbound, CAS binds it.
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'registration',
      session_id: null,
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')
  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789' }],
    error: null
  }, 'update')

  const first = await generateMobileRegistrationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-first',
    'api.risaboss.com',
    'BOSS'
  )
  assertEquals(first.success, true)

  // 2. Second page load, same challenge URL, different session id. The
  // SELECT happens to read what the first SELECT observed (a stale view),
  // but the CAS predicate against the now-bound row fails and PostgREST
  // returns an empty row set. No UPDATE response is queued here - the
  // CAS-aware mock must return [] on its own; that is what the bug was.
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'registration',
      session_id: null,
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  const second = await generateMobileRegistrationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-second',
    'api.risaboss.com',
    'BOSS'
  )

  assertEquals(second.success, false)
  if (!second.success) {
    assertEquals(second.error, 'Challenge session was concurrently bound by another request')
  }
})

Deno.test("generateMobileAuthenticationPage - first bind SUCCEEDS because session_id IS NULL (issue #924)", async () => {
  const mockClient = createMockSupabaseClient()

  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'authentication',
      session_id: null,
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')
  mockClient.mockResponse('user_passkeys', {
    data: mockPasskey,
    error: null
  }, 'select')
  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789' }],
    error: null
  }, 'update')

  const result = await generateMobileAuthenticationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-first',
    'credential-abc',
    'api.risaboss.com'
  )

  assertEquals(result.success, true)

  const history = mockClient.getQueryHistory()
  const updateCall = history.find(h => h.operation === 'update')
  assertExists(updateCall)
  const isFilters = (updateCall!.params.is ?? []) as Array<{ column: string; value: unknown }>
  assertEquals(isFilters.length, 1, 'CAS must carry exactly one `.is()` filter')
  assertEquals(isFilters[0].column, 'session_id')
  assertEquals(isFilters[0].value, null)
})

Deno.test("generateMobileAuthenticationPage - second bind FAILS because session_id IS no longer null (issue #924)", async () => {
  const mockClient = createMockSupabaseClient()

  // 1. First bind succeeds.
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'authentication',
      session_id: null,
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')
  mockClient.mockResponse('user_passkeys', {
    data: mockPasskey,
    error: null
  }, 'select')
  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789' }],
    error: null
  }, 'update')

  const first = await generateMobileAuthenticationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-first',
    'credential-abc',
    'api.risaboss.com'
  )
  assertEquals(first.success, true)

  // 2. Second bind - the CAS predicate against the now-bound row fails.
  // No UPDATE response is queued; the CAS-aware mock returns [] on its own.
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'authentication',
      session_id: null,
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')
  mockClient.mockResponse('user_passkeys', {
    data: mockPasskey,
    error: null
  }, 'select')

  const second = await generateMobileAuthenticationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-second',
    'credential-abc',
    'api.risaboss.com'
  )

  assertEquals(second.success, false)
  if (!second.success) {
    assertEquals(second.error, 'Challenge session was concurrently bound by another request')
  }
})
