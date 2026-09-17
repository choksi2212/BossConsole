package ai.rever.boss.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An `Authorization: <scheme> <credential>` header must be masked whole.
 *
 * Before the auth-scheme alternative, `sensitiveAssignment`'s `[^\s&,;}]+` stopped at the first
 * space, so the value it captured was the scheme word itself and the credential survived as
 * `[REDACTED] <token>`. Sanitized arguments reach the approval dialog AND `McpOperationLedger`,
 * which appends them to disk, so a surviving credential is written out in plaintext.
 */
class McpArgumentSanitizerAuthSchemeTest {
    private fun command(command: String): String = McpArgumentSanitizer.sanitize(mapOf("command" to command))["command"] ?: ""

    @Test
    fun `bearer credential in an Authorization header is masked, not just its label`() {
        val out = command("curl -H 'Authorization: Bearer abc123def456' https://api.internal")
        assertFalse(out.contains("abc123def456"), "credential survived sanitisation: $out")
    }

    @Test
    fun `basic token and negotiate schemes are masked too`() {
        assertFalse(command("curl -H 'Authorization: Basic YWRtaW46aHVudGVyMg=='").contains("YWRtaW46aHVudGVyMg=="))
        assertFalse(command("curl -H 'Authorization: Token ghs_SecretValue123'").contains("ghs_SecretValue123"))
        assertFalse(command("curl -H 'Authorization: Negotiate YIIZk3YGKw'").contains("YIIZk3YGKw"))
    }

    @Test
    fun `a standalone bearer header without a sensitive key still uses the bearer rule`() {
        // "X-Auth" is not in the sensitive-keyword set, so this must still be caught by [bearer].
        val out = command("curl -H 'X-Auth: Bearer standalone987'")
        assertFalse(out.contains("standalone987"), "standalone bearer survived: $out")
        assertTrue(out.contains("Bearer [REDACTED]"), "expected the bearer rule to fire: $out")
    }

    @Test
    fun `existing assignment redaction is unchanged`() {
        assertEquals("psql --[REDACTED]", command("psql --password=hunter2"))
        assertFalse(command("export GH=ghp_AbCdEfGhIjKlMnOpQrStUvWxYz0123").contains("ghp_AbCdEfGhIjKlMnOpQrStUvWxYz0123"))
        assertFalse(command("curl -d token=xoxb-2444-55667788-aBcDeFgHiJkLmNoP https://x").contains("xoxb-2444"))
        assertFalse(command("curl -H 'X: eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abc'").contains("eyJzdWIiOiIxIn0"))
    }

    @Test
    fun `a non-credential value that merely starts with a scheme word is still masked`() {
        // Regression guard: the scheme prefix is optional, so ordinary values keep working.
        assertFalse(command("psql --password=Bearerish").contains("Bearerish"))
    }
}
