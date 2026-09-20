package ai.rever.boss.components.plugin.panels.left_top

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.window.Project
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the persistence contract for [ProjectState] that the home screen, the CLI's `boss recent`,
 * and the new-tab dialog all rely on:
 *
 *  - **Saves are atomic.** `recent-projects.json` is the file that decides what projects the
 *    app remembers across restarts; `writeText` truncates and streams into place, so a crash or
 *    power loss mid-write leaves a half-written file that the next load refuses to decode - and
 *    the user loses every entry. `atomicWriteText` stages the bytes in a sibling temp file and
 *    moves it into place, exactly the shape `recent-files.json` and `recent-browser-pages.json`
 *    already use.
 *  - **Loads are forward-compatible.** A future field added to [Project] must not brick every
 *    installed build. The CLI's `BossRecentCommand` already pins this with `ignoreUnknownKeys =
 *    true`; the host had drifted.
 *
 * Isolation: every `composeApp` Test points `user.home` at its own fresh
 * `build/test-home/<task-name>` directory (AGENTS.md: "composeApp test home isolation"), so
 * `BossDirectories.rootDir` resolves to a hermetic path for this class. The
 * `recentProjectsFile` hook is the test affordance for redirecting to a per-test file inside
 * that hermetic root.
 */
class ProjectStatePersistenceTest {
    private lateinit var tempFile: File
    private val json =
        Json {
            prettyPrint = false
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @BeforeTest
    fun setUp() {
        tempFile = File.createTempFile("project-state-persistence-", ".json").apply { delete() }
        ProjectState.recentProjectsFile = tempFile
    }

    @AfterTest
    fun tearDown() {
        ProjectState.recentProjectsFile =
            File(BossDirectories.rootDir, "recent-projects.json")
        tempFile.delete()
    }

    private fun project(path: String) = Project(name = path.substringAfterLast('/'), path = path, lastOpened = 0L)

    private fun awaitSave() =
        runBlocking {
            withTimeout(5_000L) {
                while (!tempFile.exists() || tempFile.length() == 0L) {
                    delay(20L)
                }
            }
        }

    /**
     * The save helper writes via `File.writeText`. The regression: a crash mid-write left a
     * half-written file on disk that the load refused to decode, so the user's recent list was
     * wiped and the next save rewrote the broken file with the in-memory empty list.
     *
     * `atomicWriteText` moves a complete sibling temp file into place - either the old file is
     * intact or the new file is intact, never a torn half. We assert that the file is fully
     * decodable JSON whenever it exists.
     */
    @Test
    fun `save lands a fully decodable file even if interrupted mid-write`() {
        val dir = Files.createTempDirectory("project-state-dirs-").toFile()
        try {
            val projects = listOf(project(dir.resolve("a").apply { mkdirs() }.path))
            runBlocking { ProjectState.updateRecentProjectsSuspending(projects) }
            awaitSave()

            assertTrue(tempFile.exists(), "save must produce a file")
            val decoded = json.decodeFromString<List<Project>>(tempFile.readText())
            assertEquals(1, decoded.size, "saved file must be fully decodable - no half-written JSON")
            assertEquals(projects.single().path, decoded.single().path)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * The loader used the default `kotlinx.serialization.json.Json`, which is strict. Any new
     * field added to `Project` in a future build (a `pinnedTimestamp` or a `color` accent) would
     * then refuse to decode the file written by every previously installed build, silently
     * wiping the user's recent list. The CLI's `BossRecentCommand` already opts in to
     * `ignoreUnknownKeys = true`; the host has to agree.
     */
    @Test
    fun `a file with a future unknown field loads the known fields`() {
        val dir = Files.createTempDirectory("project-state-future-").toFile()
        try {
            val realDir = dir.resolve("real").apply { mkdirs() }
            // An "older" file written by a build that knew a field the current build does not.
            // Strict decoding would throw here; the host silently logs and drops everything.
            val onDisk =
                buildJsonArray {
                    addJsonObject {
                        put("name", realDir.name)
                        put("path", realDir.path)
                        put("lastOpened", 1234L)
                        put("pinnedTimestamp", 9999L)
                    }
                }.toString()
            tempFile.writeText(onDisk)

            runBlocking { ProjectState.reload() }

            val loaded = ProjectState.recentProjects.value
            assertEquals(1, loaded.size, "the known field must survive a future field")
            assertEquals(realDir.path, loaded.single().path)
            assertEquals(1234L, loaded.single().lastOpened)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Sanity: a normal update round-trips through the file the host just wrote, the CLI's
     * `BossRecentCommand`, and back into `recentProjects`. This is the assertion that would
     * fail loudly if the atomic-write path stopped serializing what `_recentProjects.value`
     * actually holds.
     */
    @Test
    fun `a round-trip through the file preserves the recorded projects`() {
        val dir = Files.createTempDirectory("project-state-roundtrip-").toFile()
        try {
            val a = dir.resolve("a").apply { mkdirs() }
            val b = dir.resolve("b").apply { mkdirs() }
            runBlocking {
                ProjectState.updateRecentProjectsSuspending(listOf(project(a.path), project(b.path)))
            }
            awaitSave()

            val onDisk = json.decodeFromString<List<Project>>(tempFile.readText())
            assertEquals(2, onDisk.size)
            assertNotNull(onDisk.firstOrNull { it.path == a.path })
            assertNotNull(onDisk.firstOrNull { it.path == b.path })
        } finally {
            dir.deleteRecursively()
        }
    }
}
