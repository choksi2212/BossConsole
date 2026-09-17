package ai.rever.boss.mcp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression tests for provider-scoped session trust (BossConsole#823).
 *
 * Session trust used to be a set of tool NAMES, so one click on "trust for
 * this session" granted auto-run to every provider's same-named tool - a
 * second, unvetted plugin shipping its own `run_command` inherited the trust
 * silently for the rest of the session. Trust is now keyed by
 * (toolName, providerId), matching the identity every other engine surface
 * already uses.
 */
class McpSessionTrustScopingTest {
    private fun engine() = McpPolicyEngine(policyFile = null)

    @Test
    fun `trusting one provider's tool does not pre-approve another provider's same-named tool`() {
        val engine = engine()

        // A mutating-suffixed name, so the copycat's own classification (name-only,
        // per the surviving #804 fix) defaults to ASK rather than the lenient
        // read-only default - this test is about SESSION TRUST inheritance, not
        // the catalog defaults.
        engine.trustForSession("env_sync_apply", "vetted-provider")

        assertEquals(
            McpPolicyAction.ALLOW,
            engine.policyFor("env_sync_apply", "vetted-provider"),
            "the trusted provider's own tool auto-runs (that is what the operator approved)",
        )
        assertEquals(
            McpPolicyAction.ASK,
            engine.policyFor("env_sync_apply", "copycat-provider"),
            "a same-named tool from a different provider must not inherit the session trust",
        )
    }

    @Test
    fun `revoking one provider's trust leaves another provider's separate trust intact`() {
        val engine = engine()

        engine.trustForSession("env_sync_apply", "provider-a")
        engine.trustForSession("env_sync_apply", "provider-b")

        engine.revokeSessionTrust("env_sync_apply", "provider-a")

        assertEquals(
            McpPolicyAction.ASK,
            engine.policyFor("env_sync_apply", "provider-a"),
            "the revoked provider's tool prompts again",
        )
        assertEquals(
            McpPolicyAction.ALLOW,
            engine.policyFor("env_sync_apply", "provider-b"),
            "the other provider's independent trust is untouched by the revoke",
        )
    }

    @Test
    fun `a providerless trust covers only providerless lookups`() {
        val engine = engine()

        engine.trustForSession("env_sync_apply", null)

        assertEquals(
            McpPolicyAction.ALLOW,
            engine.policyFor("env_sync_apply"),
            "a caller with no provider context still sees the old behavior",
        )
        assertEquals(
            McpPolicyAction.ASK,
            engine.policyFor("env_sync_apply", "some-provider"),
            "a provider-scoped lookup does not match a providerless trust",
        )
    }

    @Test
    fun `confirmInvocation grants trust scoped to the approving provider`() {
        val engine = engine()

        val revocation = engine.revocationVersion("env_sync_apply", "vetted-provider")
        val granted =
            engine.confirmInvocation(
                "env_sync_apply",
                revocation,
                grantSessionTrust = true,
                providerId = "vetted-provider",
            )

        assertEquals(true, granted, "the approval flow still grants")
        assertEquals(
            McpPolicyAction.ALLOW,
            engine.policyFor("env_sync_apply", "vetted-provider"),
            "the approving provider's tool is trusted",
        )
        assertEquals(
            McpPolicyAction.ASK,
            engine.policyFor("env_sync_apply", "copycat-provider"),
            "the copycat does not inherit a trust granted through confirmInvocation",
        )
    }

    @Test
    fun `clearing session trusts clears every provider's trust`() {
        val engine = engine()

        engine.trustForSession("env_sync_apply", "provider-a")
        engine.trustForSession("run_command", "provider-b")
        engine.clearSessionTrusts()

        assertEquals(McpPolicyAction.ASK, engine.policyFor("env_sync_apply", "provider-a"))
        assertEquals(McpPolicyAction.ASK, engine.policyFor("run_command", "provider-b"))
    }

    @Test
    fun `the name-only session trust view still reports trusted tool names`() {
        val engine = engine()

        engine.trustForSession("env_sync_apply", "provider-a")

        assertEquals(
            setOf("env_sync_apply" to "provider-a"),
            engine.sessionTrustedTools.value,
            "the pair-keyed session trust exposes exactly the granted pair",
        )
    }
}
