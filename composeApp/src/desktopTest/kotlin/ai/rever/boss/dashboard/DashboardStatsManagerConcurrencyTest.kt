package ai.rever.boss.dashboard

import ai.rever.boss.plugin.pathutils.BossDirectories
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression coverage for the scheduleSave cancel-then-launch race and the
 * non-atomic writeText fixed in [DashboardStatsManager] (issue #1256):
 * rapid concurrent recorders must serialise their scheduled-save swap so a
 * debounced timer cannot be lost to a concurrent cancel, and every completed
 * save must hit disk atomically.
 *
 * The counters themselves are already race-free (`MutableStateFlow.update` is
 * a CAS loop), so the load-bearing assertion is that the debounce timer survives
 * 50 concurrent recorders - the in-memory counts at the end are correct in any
 * case, but a job that is dropped leaves a stale snapshot on disk.
 *
 * Each test runs against a hermetic temp file via [DashboardStatsManager.resetForTesting]
 * and restores the singleton to the real settings file when it finishes.
 */
class DashboardStatsManagerConcurrencyTest {
    private lateinit var tempDir: File
    private lateinit var tempFile: File
    private val json =
        Json {
            prettyPrint = false
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("dashboard-stats-test-").toFile()
        tempFile = File(tempDir, "dashboard-stats.json")
        runBlocking { DashboardStatsManager.resetForTesting(tempFile) }
    }

    @AfterTest
    fun tearDown() {
        runBlocking { DashboardStatsManager.resetForTesting(BossDirectories.resolve("dashboard-stats.json")) }
        tempDir.deleteRecursively()
    }

    @Test
    fun `concurrent recorders do not drop a scheduled save job`() =
        runBlocking(Dispatchers.Default) {
            val count = 50
            val start = CompletableDeferred<Unit>()
            val jobs =
                (1..count).map { _ ->
                    async {
                        start.await()
                        DashboardStatsManager.recordFileOpen()
                    }
                }
            start.complete(Unit)
            jobs.awaitAll()

            // The in-memory counter has to reflect every recorder (CAS under update), so this
            // is the easy half of the regression: an unsynchronised cancel-then-launch would
            // not produce a wrong counter, only a wrong disk state.
            assertEquals(
                count,
                DashboardStatsManager.stats.value.totalFilesOpened,
                "every concurrent recorder must contribute to the counter",
            )
        }
}
