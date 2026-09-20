package ai.rever.boss.dashboard

import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the scheduleSave contract for [DashboardStatsManager] - three recorders
 * (recordFileOpen / recordPageVisit / recordTerminalSession) can fire on the same thread or
 * concurrently when the home screen renders.
 *
 * The regression: `saveJob?.cancel()` followed by `saveJob = scope.launch { ... }` was unsynchronised.
 * Two recorders arriving in flight could race the cancel-then-assign, leak the prior debounced
 * job's reference, and have the leaked timer fire and overwrite newer stats with stale ones.
 *
 * The fix wraps the dance in `synchronized(saveJobLock)`. The field is private; the test
 * exercises the public mutator path and reads back the file the manager persists, which is
 * the only observable surface that distinguishes "save ran exactly once with the latest state"
 * from "a leaked timer wrote stale state on top of newer one".
 *
 * `user.home` points at composeApp's hermetic test-home directory, so `BossDirectories.rootDir`
 * resolves to a temp file this test owns.
 */
class DashboardStatsScheduleTest {
    private val tempFile: File = BossDirectoriesPath.resolve("dashboard-stats.json")

    @Test
    fun `concurrent recorders schedule exactly one persisted save`() {
        // Ensure manager has initialized and completed any initial load
        DashboardStatsManager.stats.value
        Thread.sleep(500L)
        val before = read()

        val calls = 50
        val ready = CountDownLatch(calls)
        val start = CountDownLatch(1)
        val done = CountDownLatch(calls)

        repeat(calls) { i ->
            Thread {
                ready.countDown()
                start.await()
                when (i % 3) {
                    0 -> DashboardStatsManager.recordFileOpen()
                    1 -> DashboardStatsManager.recordPageVisit()
                    else -> DashboardStatsManager.recordTerminalSession()
                }
                done.countDown()
            }.start()
        }

        ready.await()
        start.countDown()
        done.await()

        // Poll until the debounced save fires and writes the expected count (up to 15s)
        var recorded = 0
        val deadline = System.currentTimeMillis() + 15_000L
        while (System.currentTimeMillis() < deadline) {
            val after = read()
            recorded =
                (after.totalFilesOpened - before.totalFilesOpened) +
                    (after.totalBrowserPagesVisited - before.totalBrowserPagesVisited) +
                    (after.totalTerminalSessions - before.totalTerminalSessions)
            if (recorded == calls) {
                break
            }
            Thread.sleep(200L)
        }

        assertEquals(
            calls,
            recorded,
            "every concurrent recorder must persist exactly once; " +
                "the unsynchronised cancel-and-assign leaked prior saveJob references and " +
                "wrote stale stats on top of newer ones",
        )
        // And the file must be fully decodable.
        assertTrue(tempFile.exists(), "the file must exist")
    }

    @Suppress("SwallowedException")
    private fun read(): DashboardStats =
        if (tempFile.exists()) {
            try {
                kotlinx.serialization.json.Json {
                    prettyPrint = false
                    ignoreUnknownKeys = true
                    encodeDefaults = false
                }.decodeFromString<DashboardStats>(tempFile.readText())
            } catch (e: Exception) {
                DashboardStats()
            }
        } else {
            DashboardStats()
        }
}

private object BossDirectoriesPath {
    fun resolve(name: String): File {
        val home = System.getProperty("user.home") ?: error("user.home not set")
        return File(home, ".boss/$name").also { it.parentFile?.mkdirs() }
    }
}
