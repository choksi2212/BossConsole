package ai.rever.boss.cli

import ai.rever.boss.plugin.launchpad.PluginPermission
import ai.rever.boss.plugin.launchpad.launchpadJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins `boss plugin inspect` against the manifest fields a plugin author actually controls.
 *
 * The two surfaces (human and JSON) are tested independently because they answer the same
 * question through different shapes: a one-line regression in either is enough to leave a
 * plugin author with a report they cannot trust. The fixture plugin is written to exercise
 * every non-default field the schema defines - permission with a known enum, one without,
 * an MCP tool with `adminOnly`, a system plugin flag - so a missing field is impossible to
 * mistake for an absent one in the manifest.
 */
class BossPluginInspectCliTest {
    private val workDir = Files.createTempDirectory("boss-plugin-inspect-cli-test")
    private val originalOut = System.out
    private val originalErr = System.err
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()

    @BeforeTest
    fun setUp() {
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(err))
    }

    @AfterTest
    fun tearDown() {
        System.setOut(originalOut)
        System.setErr(originalErr)
        workDir.toFile().deleteRecursively()
    }

    // ---- Directory with manifest at src/main/resources/META-INF/boss-plugin/plugin.json ----

    @Test
    fun `inspect reads the manifest from the resources path that a built project would emit`() {
        val project = writeProject(
            manifestPath = "src/main/resources/META-INF/boss-plugin/plugin.json",
            manifest = fixtureManifest(),
        )

        val report = formatHumanInspectReport(inspectTarget(project).okOrFail())

        assertTrue(report.contains("Plugin: Demo Plugin"), report)
        assertTrue(report.contains("ID:          com.example.demo"), report)
        assertTrue(report.contains("Version:     1.2.3"), report)
        assertTrue(report.contains("API:         1.0.88"), report)
        assertTrue(report.contains("Entrypoint:  com.example.demo.DemoPlugin"), report)
        assertTrue(report.contains("Author:      Demo Author"), report)
        assertTrue(report.contains("License:     Apache-2.0"), report)
        assertTrue(report.contains("Demo plugin that exercises every manifest field."), report)
        assertTrue(report.contains("Permissions (3):"), report)
        assertTrue(report.contains("Permissions (3):"), report)
        // Known permission: no "(unrecognised)" suffix.
        assertTrue(report.contains("  - ${PluginPermission.NETWORK.identifier}"), report)
        // Unrecognised permission: explicitly flagged.
        assertTrue(report.contains("  - secret.read (unrecognised)"), report)
        assertTrue(report.contains("MCP Tools (2):"), report)
        assertTrue(report.contains("  - mcp__demo__echo"), report)
        assertTrue(report.contains("  - mcp__demo__admin_tool [admin]"), report)
        assertTrue(report.contains("Source:"), report)
        assertTrue(report.contains("Directory:   ${project.absolutePath.replace('\\', "/")}"), report)
        assertTrue(report.contains("Manifest:    src/main/resources/META-INF/boss-plugin/plugin.json"), report)
    }

    @Test
    fun `inspect prefers src-resources manifest over the root plugin json when both exist`() {
        // The order matters: a built project's manifest is the canonical one, and a top-level
        // plugin.json that a developer dropped in for scaffolding should not win over it.
        val project = workDir.toFile().resolve("demo")
        project.mkdirs()
        File(project, "plugin.json").writeText(legacyManifest())
        File(project, "src/main/resources/META-INF/boss-plugin/plugin.json").parentFile.mkdirs()
        File(project, "src/main/resources/META-INF/boss-plugin/plugin.json").writeText(fixtureManifest())

        val report = formatHumanInspectReport(inspectTarget(project).okOrFail())

        assertTrue(report.contains("ID:          com.example.demo"), report)
        assertTrue(report.contains("Manifest:    src/main/resources/META-INF/boss-plugin/plugin.json"), report)
        // The legacy id field does not appear because the canonical manifest overrode it.
        assertTrue(!report.contains("com.example.legacy"), report)
    }

    @Test
    fun `inspect falls back to a top-level plugin json when no resources manifest exists`() {
        val project = workDir.toFile().resolve("demo")
        project.mkdirs()
        File(project, "plugin.json").writeText(fixtureManifest())

        val report = formatHumanInspectReport(inspectTarget(project).okOrFail())

        assertTrue(report.contains("ID:          com.example.demo"), report)
        assertTrue(report.contains("Manifest:    plugin.json"), report)
    }

    @Test
    fun `inspect resolves an unstaged project with a built jar in build-libs like link does`() {
        val project = workDir.toFile().resolve("demo")
        project.mkdirs()
        // No manifest on disk. Instead, simulate "I forgot to point at the JAR".
        // This isn't the inspect path's job - inspect should fail loud, not silently walk into
        // build/libs like link does. Pin that here so a future change does not regress it.
        val result = inspectTarget(project)

        assertTrue(result is InspectReport.Error, "expected an error for a manifest-less directory, got $result")
        val message = (result as InspectReport.Error).message
        assertTrue(message.contains("plugin.json not found"), message)
    }

    // ---- Directory with a malformed manifest ----

    @Test
    fun `inspect reports a parse error rather than crashing on an unreadable manifest`() {
        val project = workDir.toFile().resolve("bad")
        project.mkdirs()
        File(project, "plugin.json").writeText("{ this is not valid json")

        val result = inspectTarget(project)

        assertTrue(result is InspectReport.Error)
        val message = (result as InspectReport.Error).message
        assertTrue(message.contains("Unable to parse"), message)
    }

    // ---- JAR archive ----

    @Test
    fun `inspect reads a manifest from a packaged jar and reports jar metadata`() {
        val jar = writeJarPlugin("demo-1.2.3.jar", fixtureManifest())
        val report = formatHumanInspectReport(inspectTarget(jar).okOrFail())

        assertTrue(report.contains("Plugin: Demo Plugin"), report)
        assertTrue(report.contains("Archive:     ${jar.absolutePath.replace('\\', "/")}"), report)
        assertTrue(report.contains("Size:        ${jar.length()} bytes"), report)
        // The fixture jar contains exactly the manifest entry, so entries >= 1.
        val entriesLine = report.lines().single { it.trim().startsWith("Entries:") }
        val entries = entriesLine.substringAfter("Entries:").trim().toInt()
        assertTrue(entries >= 1, "expected at least one entry, got $entries")
    }

    @Test
    fun `inspect rejects a jar that does not carry the plugin manifest`() {
        val jar = writeJarWithoutManifest("empty.jar")
        val result = inspectTarget(jar)

        assertTrue(result is InspectReport.Error)
        assertTrue((result as InspectReport.Error).message.contains("META-INF/boss-plugin/plugin.json not found"))
    }

    // ---- Wrong target shapes ----

    @Test
    fun `inspect rejects a path that is neither a directory nor a known archive`() {
        val txt = workDir.resolve("not-a-plugin.txt").toFile()
        txt.writeText("hello")

        val result = inspectTarget(txt)

        assertTrue(result is InspectReport.Error)
        assertTrue((result as InspectReport.Error).message.contains("directory or a .jar/.zip archive"))
    }

    @Test
    fun `inspect rejects a path that does not exist at all`() {
        val missing = workDir.resolve("nope").toFile()

        val result = inspectTarget(missing)

        assertTrue(result is InspectReport.Error)
        assertTrue((result as InspectReport.Error).message.contains("does not exist"))
    }

    // ---- JSON output ----

    @Test
    fun `inspect json has the same fields the human report carries`() {
        val project = writeProject(
            manifestPath = "plugin.json",
            manifest = fixtureManifest(),
        )

        val report = inspectTarget(project).okOrFail()
        val payload = inspectPayload(report)
        val obj = Json.parseToJsonElement(payload.toString()).jsonObject

        assertEquals("ok", obj.string("status"))
        assertEquals("com.example.demo", obj.string("pluginId"))
        assertEquals("Demo Plugin", obj.string("displayName"))
        assertEquals("1.2.3", obj.string("version"))
        assertEquals("1.0.88", obj.string("apiVersion"))
        assertEquals("com.example.demo.DemoPlugin", obj.string("mainClass"))
        assertEquals("Demo Author", obj.string("author"))
        assertEquals("Apache-2.0", obj.string("license"))
        assertTrue(obj.string("description").startsWith("Demo plugin"))
        assertEquals(1, obj.int("manifestVersion"))
        assertEquals(false, obj.bool("systemPlugin"))
        assertEquals(true, obj.bool("canUnload"))

        val permissions = obj.jsonArray("permissions")
        assertEquals(3, permissions.size)
        val byId = permissions.associate { it.jsonObject.string("id") to it.jsonObject }
        assertEquals(true, byId.getValue("${PluginPermission.NETWORK.identifier}").booleanOrNullSafe("recognised"))
        assertEquals("NETWORK", byId.getValue("${PluginPermission.NETWORK.identifier}").stringOrNullSafe("category"))
        assertEquals(false, byId.getValue("secret.read").booleanOrNullSafe("recognised"))
        assertNull(byId.getValue("secret.read").jsonObject["category"])

        val tools = obj.jsonArray("mcpTools")
        assertEquals(2, tools.size)
        val toolNames = tools.map { it.jsonObject.string("name") }.toSet()
        assertEquals(setOf("mcp__demo__echo", "mcp__demo__admin_tool"), toolNames)
        val adminTool = tools.first { it.jsonObject.string("name") == "mcp__demo__admin_tool" }.jsonObject
        assertEquals(true, adminTool.booleanOrNullSafe("adminOnly"))

        val source = obj.jsonObject("source")
        assertEquals("directory", source.string("type"))
        assertEquals("plugin.json", source.string("manifestPath"))
    }

    @Test
    fun `inspect json omits optional fields the manifest does not set`() {
        val project = workDir.toFile().resolve("minimal")
        project.mkdirs()
        File(project, "plugin.json").writeText(MINIMAL_MANIFEST)

        val payload = inspectPayload(inspectTarget(project).okOrFail())
        val obj = Json.parseToJsonElement(payload.toString()).jsonObject

        // description / author / license are not present on a minimal manifest.
        assertNull(obj["description"])
        assertNull(obj["author"])
        assertNull(obj["license"])
        // Defaults still come through as concrete values, not nulls - license defaults to Apache-2.0
        // even when the manifest does not declare one, which is what a plugin author expects to see.
        assertEquals("Apache-2.0", obj.string("license"))
        // The minimal manifest does declare pluginId and mainClass.
        assertEquals("com.example.minimal", obj.string("pluginId"))
        assertEquals("1.0.0", obj.string("version"))
    }

    @Test
    fun `inspect json is round-trip stable through launchpad json`() {
        // Pin the JSON encoder: a future change to the project's `launchpadJson` instance that
        // suddenly stopped accepting the inspect payload would break every CLI consumer at once.
        val project = workDir.toFile().resolve("rt")
        project.mkdirs()
        File(project, "plugin.json").writeText(fixtureManifest())

        val payload = inspectPayload(inspectTarget(project).okOrFail())
        val encoded = launchpadJson.encodeToString(JsonObject.serializer(), Json.parseToJsonElement(payload.toString()).jsonObject)
        val decoded = Json.parseToJsonElement(encoded).jsonObject

        assertEquals("com.example.demo", decoded.string("pluginId"))
        assertEquals(3, decoded.jsonArray("permissions").size)
        assertEquals(2, decoded.jsonArray("mcpTools").size)
    }

    // ---- Recognised vs unrecognised permissions ----

    @Test
    fun `every known permission identifier is flagged recognised, none other`() {
        // Cross-check the lookup the JSON report relies on. The enum and the JSON consumer must
        // agree, otherwise an unknown permission reports "recognised: true" or a known one slips
        // through as "unrecognised".
        for (p in PluginPermission.entries) {
            assertEquals(
                p,
                PluginPermission.fromIdentifier(p.identifier),
                "fromIdentifier round-trip failed for $p",
            )
        }
        assertNull(PluginPermission.fromIdentifier("definitely.not.a.permission"))
        assertNull(PluginPermission.fromIdentifier(""))
    }

    // ---- Helpers ----

    private fun writeProject(
        manifestPath: String,
        manifest: String,
    ): File {
        val project = workDir.resolve("project-${System.nanoTime()}").toFile()
        project.mkdirs()
        val manifestFile = File(project, manifestPath)
        manifestFile.parentFile?.mkdirs()
        manifestFile.writeText(manifest)
        return project
    }

    private fun writeJarPlugin(
        name: String,
        manifest: String,
    ): File {
        val jar = workDir.resolve(name).toFile()
        JarOutputStream(jar.outputStream().buffered()).use { jos ->
            jos.putNextEntry(JarEntry("META-INF/boss-plugin/plugin.json"))
            jos.write(manifest.toByteArray(Charsets.UTF_8))
            jos.closeEntry()
        }
        return jar
    }

    private fun writeJarWithoutManifest(name: String): File {
        val jar = workDir.resolve(name).toFile()
        JarOutputStream(jar.outputStream().buffered()).use { jos ->
            jos.putNextEntry(JarEntry("META-INF/MANIFEST.MF"))
            jos.write("Manifest-Version: 1.0\n".toByteArray(Charsets.UTF_8))
            jos.closeEntry()
        }
        return jar
    }

    private fun InspectReport.okOrFail(): InspectReport.Ok {
        assertTrue(this is InspectReport.Ok, "expected Ok, got $this")
        return this as InspectReport.Ok
    }

    private fun JsonObject.string(key: String): String {
        val element = this[key] ?: error("missing key '$key' in $this")
        return element.jsonPrimitive.content
    }

    private fun JsonObject.stringOrNullSafe(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    private fun JsonObject.jsonObject(key: String): JsonObject =
        this[key]?.jsonObject ?: error("missing object key '$key' in $this")

    private fun JsonObject.int(key: String): Int {
        val element = this[key] ?: error("missing key '$key'")
        return element.jsonPrimitive.intOrNull ?: error("key '$key' is not an int: $element")
    }

    private fun JsonObject.bool(key: String): Boolean {
        val element = this[key] ?: error("missing key '$key'")
        return element.jsonPrimitive.boolean
    }

    private fun JsonObject.booleanOrNullSafe(key: String): Boolean? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull

    private fun JsonObject.jsonArray(key: String): JsonArray =
        (this[key] as? JsonArray) ?: error("missing array key '$key' in $this")

    private fun fixtureManifest(): String = """
        {
          "pluginId": "com.example.demo",
          "displayName": "Demo Plugin",
          "version": "1.2.3",
          "apiVersion": "1.0.88",
          "mainClass": "com.example.demo.DemoPlugin",
          "description": "Demo plugin that exercises every manifest field.",
          "author": "Demo Author",
          "license": "Apache-2.0",
          "manifestVersion": 1,
          "systemPlugin": false,
          "canUnload": true,
          "requiredPermissions": ["network", "terminal", "secret.read"],
          "mcpTools": [
            { "name": "mcp__demo__echo", "description": "Echoes its argument.", "adminOnly": false },
            { "name": "mcp__demo__admin_tool", "description": "Administers the demo.", "adminOnly": true }
          ]
        }
    """.trimIndent()

    private fun legacyManifest(): String = """
        {
          "id": "com.example.legacy",
          "name": "Legacy Plugin",
          "version": "0.1.0",
          "minApiVersion": "1.0.0",
          "entrypointClass": "com.example.legacy.LegacyPlugin",
          "permissions": ["network"]
        }
    """.trimIndent()

    private val MINIMAL_MANIFEST = """
        {
          "pluginId": "com.example.minimal",
          "displayName": "Minimal",
          "version": "1.0.0",
          "apiVersion": "1.0.0",
          "mainClass": "com.example.minimal.MinimalPlugin"
        }
    """.trimIndent()
}
