package ai.rever.boss.components.plugin

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The host's Update path must vet the downloaded jar's declared pluginId BEFORE the running
 * instance is unloaded, mirroring the gate the store installers apply at
 * `StoreVersionInstaller.activate` and `StoreMissingDependencyInstaller.vetAndLoad`.
 *
 * Reopens #927: a download for plugin X whose jar declares plugin Y would otherwise reach
 * `DynamicPluginManager.installPlugin`, which force-unloads X and then either routes an
 * `ai.rever.boss.plugin.api` jar into `hotSwapApiLayer` (process-wide unload-all / swap / reload-all)
 * or fails with ALREADY_LOADED - leaving the original plugin gone for the session.
 *
 * These tests pin the bridge's gate against a real jar built on disk; end-to-end coverage that
 * the gate runs before `swapPlugin` lives in `PluginUpdateVerifyDownloadGateTest` (where the
 * `updatePlugin` verify step is unit-tested without the manifest read).
 */
class PluginUpdateBridgeUpdateIdentityVetTest {
    private val expectedId = "ai.rever.boss.plugin.dynamic.demo"

    @TempDir
    lateinit var dir: File

    /** A jar carrying just enough manifest for `PluginManifestReader.readFromJar` to parse. */
    private fun jar(
        name: String,
        declaring: String,
    ): File {
        val file = File(dir, name)
        JarOutputStream(file.outputStream()).use { out ->
            out.putNextEntry(JarEntry("META-INF/boss-plugin/plugin.json"))
            out.write(
                """
                {"manifestVersion":1,"pluginId":"$declaring","displayName":"Demo",
                 "version":"2.0.0","apiVersion":"1.0.0","mainClass":"example.Plugin"}
                """.trimIndent().toByteArray(),
            )
            out.closeEntry()
        }
        return file
    }

    @Test
    fun `mismatched pluginId is refused`() {
        val downloaded = jar("downloaded.jar", declaring = "ai.rever.boss.plugin.dynamic.other")
        val result = PluginUpdateBridge.verifyUpdateIdentity(expectedId, downloaded.absolutePath)

        assertTrue(result.isFailure, "a jar declaring a different pluginId must be refused")
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue(
            message.contains(expectedId),
            "the message must name the plugin the user was updating",
        )
        assertTrue(
            message.contains("ai.rever.boss.plugin.dynamic.other"),
            "the message must name what the jar declared, so the user can see the mismatch",
        )
    }

    @Test
    fun `matched pluginId is accepted`() {
        val downloaded = jar("downloaded.jar", declaring = expectedId)
        val result = PluginUpdateBridge.verifyUpdateIdentity(expectedId, downloaded.absolutePath)

        assertTrue(result.isSuccess, "a jar declaring the same pluginId must pass the gate")
    }

    @Test
    fun `api plugin jar is refused even when the expected id happens to match`() {
        // Reaches the NOT_USER_INSTALLABLE check, not the mismatch check: the jar declares
        // exactly the plugin the user asked to update - the case where a single-byte difference
        // in the store row would otherwise route into `hotSwapApiLayer`.
        val downloaded = jar("downloaded.jar", declaring = "ai.rever.boss.plugin.api")
        val result = PluginUpdateBridge.verifyUpdateIdentity("ai.rever.boss.plugin.api", downloaded.absolutePath)

        assertTrue(result.isFailure, "an api-plugin jar must be refused regardless of the mismatch check")
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue(
            message.contains("ai.rever.boss.plugin.api"),
            "the message must name what the jar declared",
        )
    }

    @Test
    fun `microkernel runtime jar is refused`() {
        // The other member of NOT_USER_INSTALLABLE, alongside the api id: the microkernel
        // runtime refuses to be installed via `loadPlugin`, so a download declaring it would
        // also be a silent unload with no path back.
        val downloaded = jar("downloaded.jar", declaring = "ai.rever.boss.microkernel.runtime")
        val result =
            PluginUpdateBridge.verifyUpdateIdentity(
                "ai.rever.boss.microkernel.runtime",
                downloaded.absolutePath,
            )

        assertTrue(result.isFailure, "a microkernel runtime jar must be refused")
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue(message.contains("ai.rever.boss.microkernel.runtime"))
    }

    @Test
    fun `a jar with no readable manifest is refused`() {
        // Garbage in place of a jar: an admin uploading the wrong file produces exactly this.
        // `installPlugin` would only learn the manifest is unreadable AFTER unloading the
        // running instance.
        val garbage = File(dir, "garbage.jar").apply { writeText("not a jar") }
        val result = PluginUpdateBridge.verifyUpdateIdentity(expectedId, garbage.absolutePath)

        assertTrue(result.isFailure, "an unreadable jar must be refused")
    }

    @Test
    fun `a missing jar is refused`() {
        val result = PluginUpdateBridge.verifyUpdateIdentity(expectedId, File(dir, "does-not-exist.jar").absolutePath)

        assertTrue(result.isFailure, "a missing jar must be refused")
        assertTrue(
            result.exceptionOrNull()?.message?.contains("no readable manifest") == true,
            "the message must distinguish unreadable from identity mismatch, so the log is actionable",
        )
    }

    @Test
    fun `every NOT_USER_INSTALLABLE id is refused at the gate`() {
        // Pin the bridge against PluginDependencyResolution.NOT_USER_INSTALLABLE itself, so
        // a future addition to that set is picked up here. Pinned by this test, not by the
        // upstream `NOT_USER_INSTALLABLE` test alone, because the gate's correctness against
        // an empty or stale copy of that set is exactly what a silent identity regression
        // would look like.
        for (forbidden in PluginDependencyResolution.NOT_USER_INSTALLABLE) {
            val jar = jar("forbidden-$forbidden.jar", declaring = forbidden)
            val result = PluginUpdateBridge.verifyUpdateIdentity(forbidden, jar.absolutePath)
            assertTrue(
                result.isFailure,
                "the gate must refuse $forbidden - a future plugin id added to NOT_USER_INSTALLABLE " +
                    "must be refused here too",
            )
        }
        // And the set is not empty by accident: at least the api and runtime ids are present.
        assertTrue(
            PluginDependencyResolution.NOT_USER_INSTALLABLE.size >= 2,
            "expected at least api and microkernel runtime, got ${PluginDependencyResolution.NOT_USER_INSTALLABLE}",
        )
        assertEquals(
            setOf("ai.rever.boss.plugin.api", "ai.rever.boss.microkernel.runtime"),
            PluginDependencyResolution.NOT_USER_INSTALLABLE,
            "the set's contents are part of the public contract for this gate",
        )
    }
}
