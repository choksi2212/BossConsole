package ai.rever.boss.dashboard

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for RecentFilesManager's concurrent mutations
 * (BossConsole#764). `FileEventBus` fires one callback per opened file with no
 * batching, and every mutator rewrote the whole recorded list from an
 * unfenced read on its own coroutine - so interleaved opens each persisted a
 * list that lacked the others' entries. The mutation mutex makes the
 * read-modify-write sections indivisible; these tests hold it to that.
 *
 * Every mutator is fire-and-forget on the manager's own IO scope, so the
 * tests await the *observable* result (the StateFlow settling) rather than a
 * return value, with a bounded wait instead of sleeps.
 *
 * The manager persists to `~/.boss/recent-files.json` via BossDirectories; the
 * composeApp test-home isolation redirects `user.home` per task.
 */
class RecentFilesConcurrencyTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Real temp files so the displayed list's File.exists() filter admits
    // them; MAX_FILES (20) bound kept so the trim path is not mistaken for a
    // lost entry.
    private val openedFiles = (1..20).map { File.createTempFile("race-file-$it-", ".kt").apply { deleteOnExit() } }

    /** Wait until the displayed list stops containing the given path, bounded. */
    private suspend fun awaitAbsence(path: String) {
        withTimeout(10_000) {
            while (RecentFilesManager.recentFiles.value.any { it.path == path }) {
                kotlinx.coroutines.delay(10)
            }
        }
    }

    /** Wait until the displayed list contains the given path, bounded. */
    private suspend fun awaitPresence(path: String) {
        withTimeout(10_000) {
            while (RecentFilesManager.recentFiles.value.none { it.path == path }) {
                kotlinx.coroutines.delay(10)
            }
        }
    }

    @AfterTest
    fun tearDown() {
        runBlocking {
            RecentFilesManager.clearAll()
            // Let the debounced save drain so the next test starts clean.
            withTimeout(10_000) {
                kotlinx.coroutines.delay(6_000)
            }
        }
        scope.coroutineContext.cancelChildren()
    }

    @Test
    fun `interleaved opens all survive`() {
        runBlocking {
            RecentFilesManager.clearAll()
            awaitAbsence(openedFiles.first().path)

            // Fire N opens the way FileEventBus does: one call each, all
            // racing on their own coroutines. Unfenced, each read the list
            // before the others' writes and the debounced save persisted a
            // list missing most of them.
            val opens = openedFiles.map { f -> scope.async { RecentFilesManager.recordFileOpen(f.path) } }
            opens.forEach { it.await() }
            awaitPresence(openedFiles.last().path)

            val recorded = RecentFilesManager.recentFiles.value
            assertEquals(openedFiles.size, recorded.size, "every interleaved open must survive, got ${recorded.size}: ${recorded.map { it.path }}")
            openedFiles.forEach { f ->
                assertTrue(
                    recorded.any { it.path == f.path },
                    "${f.path} was lost to an interleaved open",
                )
            }
        }
    }

    @Test
    fun `a remove racing opens does not resurrect the removed entry`() {
        runBlocking {
            RecentFilesManager.clearAll()
            awaitAbsence(openedFiles.first().path)

            val keptA = File.createTempFile("race-kept-a-", ".kt").apply { deleteOnExit() }
            val keptB = File.createTempFile("race-kept-b-", ".kt").apply { deleteOnExit() }
            val keptC = File.createTempFile("race-kept-c-", ".kt").apply { deleteOnExit() }

            RecentFilesManager.recordFileOpen(keptA.path)
            RecentFilesManager.recordFileOpen(keptB.path)
            awaitPresence(keptB.path)

            val remove = scope.async { RecentFilesManager.removeFile(keptA.path) }
            val open = scope.async { RecentFilesManager.recordFileOpen(keptC.path) }
            remove.await()
            open.await()
            awaitPresence(keptC.path)
            awaitAbsence(keptA.path)

            val paths = RecentFilesManager.recentFiles.value.map { it.path }
            assertTrue(
                keptA.path !in paths,
                "a removed entry must not be resurrected by a racing open (got $paths)",
            )
            assertTrue(
                keptC.path in paths && keptB.path in paths,
                "both the racing open's entry and the untouched entry must survive (got $paths)",
            )
        }
    }

    // The persistence durability property (unique sibling temp file + atomic
    // move, never truncate-in-place) is already pinned by AtomicFileWriteTest,
    // which exists for exactly that; duplicating it here through the
    // singleton's debounced save only re-asserts a scheduler timing, so the
    // class pins the two lost-update regressions and leaves durability to
    // the dedicated suite.
}
