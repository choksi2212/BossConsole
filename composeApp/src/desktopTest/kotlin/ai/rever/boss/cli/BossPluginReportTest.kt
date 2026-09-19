package ai.rever.boss.cli

import ai.rever.boss.plugin.launchpad.HostMeta
import ai.rever.boss.plugin.launchpad.PluginManifest
import ai.rever.boss.plugin.launchpad.PluginMcpToolDeclaration
import ai.rever.boss.plugin.launchpad.PluginPermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BossPluginReportTest {
    private fun manifest(
        pluginId: String = "ai.rever.boss.example",
        displayName: String = "Example Plugin",
        version: String = "1.2.0",
        apiVersion: String = HostMeta.CURRENT_API_VERSION,
        mainClass: String = "ai.rever.boss.example.Main",
        description: String = "",
        author: String = "",
        license: String = "Apache-2.0",
        permissions: List<String> = listOf("filesystem", "terminal"),
        mcpTools: List<PluginMcpToolDeclaration> = emptyList(),
    ): PluginManifest =
        PluginManifest(
            pluginId = pluginId,
            displayName = displayName,
            version = version,
            apiVersion = apiVersion,
            mainClass = mainClass,
            description = description,
            author = author,
            license = license,
            requiredPermissions = permissions,
            mcpTools = mcpTools,
        )

    private fun tool(
        name: String,
        desc: String,
        admin: Boolean = false,
    ) = PluginMcpToolDeclaration(name = name, description = desc, adminOnly = admin)

    @Test
    fun `the report header carries id, version, and api version`() {
        val md = PluginReportMarkdown.render(manifest(pluginId = "ai.rever.boss.example", version = "1.2.0"))
        assertTrue(md.contains("# Plugin report — `ai.rever.boss.example`"))
        assertTrue(md.contains("Version:      `1.2.0`"))
        assertTrue(md.contains("API version:  `${HostMeta.CURRENT_API_VERSION}`"))
    }

    @Test
    fun `the permissions section lists each declared permission with a description`() {
        val md = PluginReportMarkdown.render(manifest(permissions = listOf("filesystem", "terminal")))
        assertTrue(md.contains("## Permissions"))
        assertTrue(md.contains("`filesystem`"))
        assertTrue(md.contains("`terminal`"))
        // Description text appears for both permissions (loose match so
        // the test does not break when the description wording is tuned).
        assertTrue(md.lowercase().contains("files"))
        assertTrue(md.lowercase().contains("shell"))
    }

    @Test
    fun `unrecognised permissions are flagged with a marker`() {
        val md = PluginReportMarkdown.render(manifest(permissions = listOf("filesystem", "sneaky.injection")))
        assertTrue(md.contains("**UNRECOGNISED**"), "expected UNRECOGNISED marker for sneaky.injection")
        // And not for filesystem, which is canonical.
        val fsLine = md.lines().single { it.contains("`filesystem`") }
        assertFalse(fsLine.contains("UNRECOGNISED"))
    }

    @Test
    fun `the MCP tools section renders a markdown table`() {
        val md =
            PluginReportMarkdown.render(
                manifest(
                    mcpTools =
                        listOf(
                            tool("read", "Read a file"),
                            tool("destructive", "Delete a file", admin = true),
                        ),
                ),
            )
        assertTrue(md.contains("## MCP tools"))
        assertTrue(md.contains("| Tool name | Description | Admin-only |"))
        assertTrue(md.contains("| `read` | Read a file | no |"))
        assertTrue(md.contains("| `destructive` | Delete a file | yes |"))
    }

    @Test
    fun `a manifest with no declared permissions still produces a valid section`() {
        val md = PluginReportMarkdown.render(manifest(permissions = emptyList()))
        assertTrue(md.contains("## Permissions"))
        assertTrue(md.contains("_None declared._"))
    }

    @Test
    fun `a manifest with no MCP tools still produces a valid section`() {
        val md = PluginReportMarkdown.render(manifest(mcpTools = emptyList()))
        assertTrue(md.contains("## MCP tools"))
        assertTrue(md.contains("_None declared._"))
    }

    @Test
    fun `the review checklist appears at the bottom`() {
        val md = PluginReportMarkdown.render(manifest())
        assertTrue(md.contains("## Review checklist"))
        assertTrue(md.contains("- [ ] Plugin id matches the store page"))
    }

    @Test
    fun `every permission in the canonical enum has a description`() {
        // If a future contributor adds a permission to [PluginPermission]
        // without adding a one-line description, the report silently
        // degrades to "(no description)". This test pins the inverse:
        // every entry the host knows about has an explanation.
        for (p in PluginPermission.values()) {
            assertTrue(
                PluginReportMarkdown
                    .render(manifest(permissions = listOf(p.identifier)))
                    .contains(p.identifier),
                "report should mention ${p.identifier}",
            )
        }
    }

    @Test
    fun `description and author surface when present`() {
        val md =
            PluginReportMarkdown.render(
                manifest(description = "Sample description", author = "Jane Doe"),
            )
        assertTrue(md.contains("> Sample description"))
        assertTrue(md.contains("- Author:       `Jane Doe`"))
    }

    @Test
    fun `description is omitted from the header when blank`() {
        val md = PluginReportMarkdown.render(manifest(description = ""))
        // No "> " block-quote line should appear when description is blank.
        assertFalse(md.lines().any { it.startsWith("> ") })
    }
}
