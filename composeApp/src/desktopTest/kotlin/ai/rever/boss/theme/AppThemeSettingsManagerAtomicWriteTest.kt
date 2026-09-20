package ai.rever.boss.theme

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the persistence contract for [AppThemeSettingsManager].
 *
 * The regression: `save()` writes `~/.boss/app-theme-settings.json` via `File.writeText`,
 * which truncates and then streams into place. A crash or power loss mid-write leaves a
 * half-written JSON file that the next load refuses to decode - the user's chosen theme
 * silently reverts to the platform default, and Space theme overrides layered on top of it
 * lose the baseline they were overriding.
 *
 * The fix swaps `writeText` for `atomicWriteText`, which stages the bytes in a sibling temp
 * file and moves it into place - the same shape every other settings manager in the host uses.
 * This test pins the post-fix property: every save lands a fully decodable file.
 */
class AppThemeSettingsManagerAtomicWriteTest {
    private val tempFile: File =
        File(
            System.getProperty("user.home") ?: error("user.home not set"),
            ".boss/app-theme-settings.json",
        ).also { it.parentFile?.mkdirs() }

    @BeforeTest
    fun setUp() {
        tempFile.delete()
    }

    @AfterTest
    fun tearDown() {
        tempFile.delete()
    }

    /**
     * After every `select` call, the persisted file must be fully decodable. A torn file
     * would fail to decode here and reset the user to the platform default at next launch.
     */
    @Test
    fun `select lands a fully decodable file`() {
        // Pick any theme the platform actually knows - this just exercises the save path.
        val themeId = AppThemeSettings.defaultsFor(isWindows = false).appThemeId
        AppThemeSettingsManager.select(themeId)

        // The save runs on Dispatchers.IO - wait a moment for it to drain.
        Thread.sleep(500L)

        assertTrue(tempFile.exists(), "save must produce a file")
        val raw = tempFile.readText()
        // Round-trip through the same decoder the load uses.
        val decoded =
            AppThemeSettings.storageJson.decodeFromString(
                AppThemeSettings.serializer(),
                raw,
            )
        assertNotNull(decoded)
        // The persisted value must be the one we just selected.
        val text =
            AppThemeSettings.storageJson.encodeToString(
                AppThemeSettings.serializer(),
                decoded,
            )
        assertTrue(
            text.contains("\"appThemeId\":\"$themeId\"") || decoded.appThemeId == themeId,
            "saved file must contain the selected theme - got $decoded",
        )
    }

    /**
     * Two selects in succession. A non-atomic write that races two save coroutines would
     * truncate the file with the first writer and stream the second writer's bytes on top,
     * producing an interleaved half-file that fails to decode. The atomic writer stages
     * each save in its own sibling temp file, so the second write moves into place only
     * after the first has finished.
     */
    @Test
    fun `two selects in a row leave the file fully decodable`() {
        val first = AppThemeSettings.defaultsFor(isWindows = false).appThemeId
        val second = AppThemeSettings.defaultsFor(isWindows = true).appThemeId
        val candidates = listOf(first, second).distinct()
        if (candidates.size < 2) return // skip if the platform only has one theme id

        AppThemeSettingsManager.select(first)
        AppThemeSettingsManager.select(candidates.last { it != first })

        Thread.sleep(500L)

        val decoded =
            AppThemeSettings.storageJson.decodeFromString(
                AppThemeSettings.serializer(),
                tempFile.readText(),
            )
        assertNotNull(decoded)
        assertEquals(
            candidates.last { it != first },
            decoded.appThemeId,
            "the second select must be the one on disk",
        )
    }
}
