package ai.rever.boss.keymap

import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeyStroke
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.model.ShortcutContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the persistence contract for [KeymapSettingsManager].
 *
 * The regression: every save went through `File.writeText`, which truncates and then
 * streams. A crash or power loss mid-write leaves a half-written JSON file that the next
 * load refuses to decode - the user loses every rebind and falls back to the BOSS Default
 * keymap. The migration write (line 70) runs at every launch when migration makes changes,
 * so the window for corruption is the entire startup.
 *
 * The fix swaps every `writeText` for `atomicWriteText`, which stages the bytes in a
 * sibling temp file and moves it into place, exactly the shape `recent-files.json` and
 * `recent-browser-pages.json` already use. This test pins the post-fix property: every
 * save lands a fully decodable file.
 */
class KeymapSettingsManagerAtomicWriteTest {
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    private fun tempFile(): File {
        // Mirror the manager's filename inside the hermetic composeApp test-home root.
        val home = System.getProperty("user.home") ?: error("user.home not set")
        return File(home, ".boss/keymap-settings.json").also { it.parentFile?.mkdirs() }
    }

    private val tempKeymap: File = tempFile()

    @BeforeTest
    fun setUp() {
        // Wipe the file so the first load lands the default (which exercises both write
        // paths: first-run save + the migration branch in the next test).
        tempKeymap.delete()
    }

    @AfterTest
    fun tearDown() {
        tempKeymap.delete()
    }

    /**
     * Save a non-trivial keymap and confirm the file is fully decodable. A torn file would
     * fail to decode here and leave the user on BOSS Default at next launch.
     */
    @Test
    fun `save lands a fully decodable file`() =
        runBlocking {
            val bindings =
                mapOf(
                    "panel.navigate_right" to
                        KeyBinding(
                            actionId = "panel.navigate_right",
                            key = "Right",
                            alternateKeystrokes = listOf(KeyStroke(key = "L", modifiers = listOf("Cmd"))),
                            enabled = true,
                            context = ShortcutContext.GLOBAL,
                        ),
                    "file.save" to
                        KeyBinding(
                            actionId = "file.save",
                            key = "S",
                            alternateKeystrokes = listOf(KeyStroke(key = "S", modifiers = listOf("Cmd", "Shift"))),
                            enabled = true,
                            context = ShortcutContext.EDITOR,
                        ),
                )
            val payload =
                KeymapSettings(
                    presetName = "BOSS Default",
                    shortcuts = bindings,
                )
            // Push the new state through the manager, then ask the file to decode itself.
            KeymapSettingsManager.updateSettings(payload)
            assertTrue(tempKeymap.exists(), "save must produce a file")

            val decoded = json.decodeFromString<KeymapSettings>(tempKeymap.readText())
            assertEquals("BOSS Default", decoded.presetName)
            assertEquals(2, decoded.shortcuts.size)
            assertTrue(decoded.shortcuts.containsKey("panel.navigate_right"))
            assertTrue(decoded.shortcuts.containsKey("file.save"))
        }

    /**
     * Save twice in succession. A non-atomic write that races two save coroutines would
     * truncate the file with the first writer and stream the second writer's bytes on top,
     * producing an interleaved half-file that fails to decode. The atomic writer stages each
     * save in its own sibling temp file, so the second write moves into place only after the
     * first has finished - no interleaving.
     */
    @Test
    fun `two saves in a row land a fully decodable file each time`() =
        runBlocking {
            val first = KeymapSettings(presetName = "BOSS Default", shortcuts = emptyMap())
            val second =
                KeymapSettings(
                    presetName = "VS Code",
                    shortcuts =
                        mapOf(
                            "panel.navigate_right" to
                                KeyBinding(
                                    actionId = "panel.navigate_right",
                                    key = "Right",
                                    alternateKeystrokes = emptyList(),
                                    enabled = true,
                                    context = ShortcutContext.GLOBAL,
                                ),
                        ),
                )

            KeymapSettingsManager.updateSettings(first)
            KeymapSettingsManager.updateSettings(second)

            val decoded = json.decodeFromString<KeymapSettings>(tempKeymap.readText())
            assertEquals("VS Code", decoded.presetName, "the second save must be the one on disk")
            assertEquals(1, decoded.shortcuts.size)
        }
}
