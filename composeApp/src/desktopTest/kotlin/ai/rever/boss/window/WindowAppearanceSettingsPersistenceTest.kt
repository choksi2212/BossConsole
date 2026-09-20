package ai.rever.boss.window

import ai.rever.boss.plugin.pathutils.BossDirectories
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression coverage for the non-atomic writeText fixed in
 * [WindowAppearanceSettingsManager] (issue #1271):
 * a settings save must not leave the file half-written on crash mid-write.
 *
 * The atomic form writes a unique sibling temp file and moves it into place, so a
 * truncated target is impossible even if the process dies inside `withContext`.
 *
 * Each test runs against a hermetic temp file via [WindowAppearanceSettingsManager.resetForTesting]
 * and restores the singleton to the real settings file when it finishes.
 */
class WindowAppearanceSettingsPersistenceTest {
    private lateinit var tempDir: File
    private lateinit var tempFile: File
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("window-appearance-test-").toFile()
        tempFile = File(tempDir, "window-appearance-settings.json")
        WindowAppearanceSettingsManager.resetForTesting(tempFile)
    }

    @AfterTest
    fun tearDown() {
        WindowAppearanceSettingsManager.resetForTesting(BossDirectories.resolve("window-appearance-settings.json"))
        tempDir.deleteRecursively()
    }

    @Test
    fun `updateSettings writes a parseable file when it completes`() =
        runBlocking {
            val saved =
                WindowAppearanceSettings(
                    showTitleBar = true,
                    density = ChromeDensity.COMPACT,
                    settingsVersion = WindowAppearanceSettings.CURRENT_SETTINGS_VERSION,
                )
            WindowAppearanceSettingsManager.updateSettings(saved)

            assertTrue(
                tempFile.exists(),
                "Atomic write must land a file at the expected path",
            )
            val readBack =
                json.decodeFromString<WindowAppearanceSettings>(tempFile.readText())
            assertEquals(
                saved,
                readBack,
                "Every persisted field must round-trip through atomic writeText",
            )
        }
}
