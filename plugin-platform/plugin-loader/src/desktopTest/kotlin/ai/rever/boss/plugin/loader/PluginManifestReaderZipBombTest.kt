package ai.rever.boss.plugin.loader

import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Regression tests for the zip-bomb bound on [PluginManifestReader.readFromJar].
 *
 * Before the bound, `readFromJar` consumed the manifest entry's `InputStream`
 * with `readText()` and no cap, so a JAR whose `META-INF/boss-plugin/plugin.json`
 * entry decompressed to many MB could OOM the host. The same reader is reached
 * by every install path (`DynamicPluginLoader.loadPlugin`, `PluginUpdateBridge`,
 * `PluginJarReconciler.readCandidates`, `StoreMissingDependencyInstaller.vetAndLoad`,
 * `ApiClassLoader.fromPluginDir`, `DefaultPlugin.loadExternalPlugins`).
 */
class PluginManifestReaderZipBombTest {
    private val tempJars = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        tempJars.forEach { it.delete() }
    }

    /** A JAR whose manifest entry is a 4 MiB payload — well above the 64 KiB cap. */
    private fun oversizedManifestJar(): String {
        val jar = File.createTempFile("plugin-manifest-zipbomb", ".jar")
        tempJars.add(jar)
        val oversized = ByteArray(4 * 1024 * 1024) { (it % 95 + 32).toByte() }
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("META-INF/boss-plugin/plugin.json"))
            out.write(oversized)
            out.closeEntry()
        }
        return jar.absolutePath
    }

    @Test
    fun `readFromJar rejects a manifest entry that exceeds the size cap`() {
        val jarPath = oversizedManifestJar()

        assertFails("oversized manifest entry must be rejected, not buffered in full") {
            PluginManifestReader.readFromJar(jarPath)
        }
    }

    @Test
    fun `readFromJar failure on an oversized entry is a PluginManifestException, not OOM`() {
        val jarPath = oversizedManifestJar()

        try {
            PluginManifestReader.readFromJar(jarPath)
            error("expected readFromJar to fail on a 4 MiB manifest entry")
        } catch (e: PluginManifestException) {
            // The cap surfaces as a structured failure, not as a thrown OOM
            // that the caller never recovers from.
            assertTrue(
                e.message?.contains("exceeds", ignoreCase = true) == true ||
                    e.message?.contains("too large", ignoreCase = true) == true ||
                    e.message?.contains("bytes", ignoreCase = true) == true,
                "expected message to mention the cap, got: ${e.message}",
            )
        }
    }
}
