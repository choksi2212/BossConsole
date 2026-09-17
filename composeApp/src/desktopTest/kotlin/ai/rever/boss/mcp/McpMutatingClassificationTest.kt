package ai.rever.boss.mcp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression tests for the fail-closed mutating classification (BossConsole#804).
 *
 * The mutating-vs-read default used to be decided by the tool NAME alone - the
 * provider's own `McpToolDefinition.readOnly` declaration was ignored - so a
 * third-party tool that mutates but is named like a read (`data_fetch`, no
 * catalog suffix) fell through to the lenient read-only default. The
 * classification now combines both fail-closed: provider declaration OR name
 * catalog.
 *
 * Defaults from McpToolPolicyConfig: mutating = ASK, read-only = ALLOW.
 */
class McpMutatingClassificationTest {
    /** A name that dodges every suffix in the catalog. */
    private val dodgyName = "data_fetch"

    @Test
    fun `a provider-declared mutating tool gets the mutating default even with a read-like name`() {
        val engine = McpPolicyEngine(policyFile = null)

        val byNameAlone = engine.policyFor(dodgyName)
        val withDeclaration = engine.policyFor(dodgyName, declaredReadOnly = false)

        assertEquals(
            McpPolicyAction.ALLOW,
            byNameAlone,
            "sanity: the name alone still classifies as read-only (the old, exploitable behavior)",
        )
        assertEquals(
            McpPolicyAction.ASK,
            withDeclaration,
            "a provider-declared mutating tool must get the mutating default (ASK) even when its name dodges the catalog",
        )
    }

    @Test
    fun `a catalog-suffix name stays mutating even when the provider declares it read-only`() {
        val engine = McpPolicyEngine(policyFile = null)

        // The catalog is the backstop for a mislabeling provider.
        assertEquals(
            McpPolicyAction.ASK,
            engine.policyFor("k8s_delete", declaredReadOnly = true),
            "the name catalog must still catch a mutating-suffixed name a provider mislabels read-only",
        )
    }

    @Test
    fun `a declared read-only tool with a read-like name keeps the read-only default`() {
        val engine = McpPolicyEngine(policyFile = null)

        assertEquals(
            McpPolicyAction.ALLOW,
            engine.policyFor(dodgyName, declaredReadOnly = true),
            "an honestly-declared read-only tool is unchanged",
        )
    }

    @Test
    fun `callers without a declaration fall back to the name catalog alone`() {
        val engine = McpPolicyEngine(policyFile = null)

        assertEquals(
            engine.policyFor(dodgyName),
            engine.policyFor(dodgyName, declaredReadOnly = null),
            "null declaration must preserve the old name-only behavior exactly",
        )
    }
}
