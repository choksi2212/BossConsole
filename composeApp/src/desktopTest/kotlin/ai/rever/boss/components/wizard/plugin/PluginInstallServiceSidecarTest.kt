package ai.rever.boss.components.wizard.plugin

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression for the wizard "rename drops the signature sidecar" bug.
 *
 * `PluginInstallService.installPlugins` writes its wizard downloads to
 * `${pluginId}-downloading.jar`, and `downloadPlugin` writes the signature
 * sidecar next to that path - so the sidecar lands at
 * `${pluginId}-downloading.jar.sig`. The code then moves the jar to
 * `${pluginId}-${actualVersion}.jar` via `atomicMoveFrom`, which moves only
 * the jar (see `composeApp/src/commonMain/kotlin/ai/rever/boss/utils/AtomicFileWrite.kt`).
 * The sidecar is left behind at the `-downloading.jar.sig` name and the
 * installed plugin loads unsigned, defeating the rollout.
 *
 * The fix moves the sidecar alongside the jar: read it from the downloaded
 * path, persist it beside the final name, then delete the downloaded
 * sidecar. Mirrors what `StoreMissingDependencyInstaller.InstallerHooks.promoteFiles`
 * does for the same reason.
 */
class PluginInstallServiceSidecarTest {
    private fun source(): String {
        val root =
            assertNotNull(
                generateSequence(File("").absoluteFile) { it.parentFile }
                    .firstOrNull { File(it, "composeApp/build.gradle.kts").isFile },
                "could not locate the repository root",
            )
        val file =
            File(
                root,
                "composeApp/src/desktopMain/kotlin/ai/rever/boss/components/wizard/plugin/PluginInstallService.kt",
            )
        assertTrue(file.isFile, "PluginInstallService.kt not found at ${file.absolutePath}")
        return file.readText()
    }

    @Test
    fun `the wizard rename moves the sidecar alongside the jar`() {
        val text = source()

        // The sidecar is moved to the same final path the jar moved to. Both
        // names have to match - the wrong one leaves the sidecar at the
        // downloaded path, which is what this whole bug is about.
        assertTrue(
            Regex(
                """PluginSignatureSidecar\.persist\(\s*finalFile\.absolutePath\s*,""",
            ).containsMatchIn(text),
            "the wizard must persist the sidecar beside the finalFile, not the downloaded path",
        )
        assertTrue(
            Regex(
                """PluginSignatureSidecar\.delete\(\s*downloadedPath\s*\)""",
            ).containsMatchIn(text),
            "the wizard must delete the sidecar at the downloaded path after promoting it",
        )
    }

    @Test
    fun `the wizard rename reads the sidecar from the downloaded path before promoting`() {
        val text = source()

        // The sidecar at the downloaded path is the source of truth; reading
        // it from the final path would read a sidecar that does not exist
        // yet (or, on a reinstall, a stale one).
        assertTrue(
            Regex(
                """PluginSignatureSidecar\.read\(\s*downloadedPath\s*\)""",
            ).containsMatchIn(text),
            "the wizard must read the sidecar at the downloaded path before promoting",
        )
    }
}
