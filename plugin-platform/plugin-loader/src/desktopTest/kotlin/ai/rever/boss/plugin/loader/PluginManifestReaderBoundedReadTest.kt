package ai.rever.boss.plugin.loader

import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the bounded manifest read in [PluginManifestReader]: a `plugin.json`
 * JAR entry that inflates past [PluginManifestReader.MAX_MANIFEST_BYTES] must
 * be rejected with the same [PluginManifestException] used for malformed
 * manifests, before callers (e.g. signature verification) trust the JAR.
 * Without the bound, a tiny malicious JAR could declare a manifest that
 * decompresses to gigabytes and exhaust the heap (zip bomb).
 */
class PluginManifestReaderBoundedReadTest {
    private val tempJars = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        tempJars.forEach { it.delete() }
    }

    /** Writes a temp JAR whose `plugin.json` entry holds [manifestText]. */
    private fun manifestJar(manifestText: String): String {
        val jar = File.createTempFile("bounded-manifest", ".jar")
        tempJars.add(jar)
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("META-INF/boss-plugin/plugin.json"))
            out.write(manifestText.toByteArray(Charsets.UTF_8))
            out.closeEntry()
        }
        return jar.absolutePath
    }

    /**
     * A structurally valid manifest whose exact UTF-8 size is [totalBytes].
     * The padding lives inside the `description` string value and is pure
     * ASCII, so bytes == chars and the JSON stays well-formed at any size.
     */
    private fun manifestOfTotalBytes(totalBytes: Int): String {
        val skeleton =
            listOf(
                "{\"pluginId\":\"com.example.bounded.read\"",
                "\"displayName\":\"Bounded Read\"",
                "\"version\":\"1.0.0\"",
                "\"apiVersion\":\"1.0.0\"",
                "\"mainClass\":\"com.example.Missing\"",
                "\"description\":\"\"}",
            ).joinToString(",")
        require(totalBytes >= skeleton.length) { "target below skeleton size" }
        val padding = "x".repeat(totalBytes - skeleton.length)
        return skeleton.replace("\"description\":\"\"", "\"description\":\"$padding\"")
    }

    @Test
    fun `a manifest larger than the cap is rejected with PluginManifestException`() {
        val oversize = manifestOfTotalBytes(PluginManifestReader.MAX_MANIFEST_BYTES + 1024)

        val error =
            assertFailsWith<PluginManifestException> {
                PluginManifestReader.readFromJar(manifestJar(oversize))
            }

        assertTrue(error.message.orEmpty().contains("exceeds"), "message should name the size cap: ${error.message}")
    }

    @Test
    fun `an oversize manifest also fails the hasValidManifest pre-check`() {
        val oversize = manifestOfTotalBytes(PluginManifestReader.MAX_MANIFEST_BYTES + 1024)

        assertFalse(PluginManifestReader.hasValidManifest(manifestJar(oversize)))
    }

    @Test
    fun `a small manifest still parses`() {
        val small =
            """
            {
              "pluginId": "com.example.bounded.read",
              "displayName": "Bounded Read",
              "version": "1.0.0",
              "apiVersion": "1.0.0",
              "mainClass": "com.example.Missing"
            }
            """.trimIndent()

        val manifest = PluginManifestReader.readFromJar(manifestJar(small))

        assertEquals("com.example.bounded.read", manifest.pluginId)
    }

    @Test
    fun `a manifest just under the cap still parses`() {
        val manifest =
            PluginManifestReader.readFromJar(
                manifestJar(manifestOfTotalBytes(PluginManifestReader.MAX_MANIFEST_BYTES - 1)),
            )

        assertEquals("com.example.bounded.read", manifest.pluginId)
    }

    @Test
    fun `a manifest exactly at the cap still parses`() {
        val manifest =
            PluginManifestReader.readFromJar(
                manifestJar(manifestOfTotalBytes(PluginManifestReader.MAX_MANIFEST_BYTES)),
            )

        assertEquals("com.example.bounded.read", manifest.pluginId)
    }
}
