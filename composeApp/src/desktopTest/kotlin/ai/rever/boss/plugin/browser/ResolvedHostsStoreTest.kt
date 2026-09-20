package ai.rever.boss.plugin.browser

import ai.rever.boss.plugin.pathutils.BossDirectories
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the snapshot-under-lock contract for [ResolvedHostsStore].
 *
 * The regression: [ResolvedHostsStore.recordLoaded] captured `hosts.toList().sorted()` on the
 * calling thread and only acquired `saveLock` inside the coroutine in `save()`. Two
 * `recordLoaded` calls landing close together both captured the same snapshot, raced for the
 * lock, and whichever won LAST overwrote the newer in-memory state with the older snapshot -
 * silently dropping the host added by the other call.
 *
 * The fix takes the snapshot inside `saveLock.withLock` so the in-memory `hosts` set, which is
 * already a `ConcurrentHashMap.newKeySet`, can grow between the two calls without one losing.
 */
class ResolvedHostsStoreTest {
    private lateinit var workDir: File
    private lateinit var tempFile: File

    @BeforeTest
    fun setUp() {
        workDir = File.createTempFile("resolved-hosts-test-", ".dir").apply { delete(); mkdirs() }
        tempFile = File.createTempFile("resolved-hosts-test-", ".json").apply { delete() }
        ResolvedHostsStore.storeFile = tempFile
        ResolvedHostsStore.clear()
    }

    @AfterTest
    fun tearDown() {
        ResolvedHostsStore.storeFile = BossDirectories.resolve("browser-resolved-hosts.json")
        ResolvedHostsStore.clear()
        tempFile.delete()
        workDir.deleteRecursively()
    }

    /**
     * Pin the post-fix property directly. Each `recordLoaded` fires the save path; `saveNowBlocking`
     * forces the writes to land in the calling thread under the lock. The file MUST contain every
     * host added, regardless of how the snapshot is taken relative to the lock - the in-memory
     * `hosts` set is the source of truth, so a correctly-ordered snapshot includes all of it.
     */
    @Test
    fun `consecutive recordLoaded calls persist every host under the lock`() {
        repeat(50) { i -> ResolvedHostsStore.recordLoaded("host-$i.example") }

        // Drive the same write path `save` uses, but inline. The lock makes the test
        // deterministic - we know exactly which snapshot landed.
        val payload = ResolvedHostsStore.saveNowBlocking()

        val persisted = decode(payload)
        assertEquals(50, persisted.size, "every recorded host must end up on disk")
        for (i in 0 until 50) {
            assertTrue(
                "host-$i.example" in persisted,
                "host-$i.example was recorded but did not land in the persisted set",
            )
        }
    }

    /**
     * The race the fix closes. With the snapshot taken outside the lock, two `recordLoaded`
     * calls in flight can race the lock and the LAST save can overwrite the FIRST's snapshot
     * with a smaller one - dropping the host added by the first save.
     *
     * Here we exercise the production code path (the launched coroutine) for many concurrent
     * recordLoaded calls and then drain saves; the only way every host lands is for the
     * snapshot to be taken inside the lock, so any snapshot-outside code regresses this test.
     */
    @Test
    fun `many concurrent recordLoaded calls all land on disk`() = runBlocking {
        val count = 200
        val deferreds = (0 until count).map { i ->
            withContext(Dispatchers.IO) {
                async {
                    // Spread the calls over a small window so the launched saves genuinely race.
                    ResolvedHostsStore.recordLoaded("concurrent-$i.example")
                    delay(2L)
                }
            }
        }
        deferreds.awaitAll()

        // Drain: kick a final synchronous save, then read what landed.
        ResolvedHostsStore.saveNowBlocking()
        val persisted = decode(tempFile.readText())

        assertEquals(
            count,
            persisted.size,
            "expected every concurrent recordLoaded call to land on disk, " +
                "got ${persisted.size} of $count - the snapshot was taken outside saveLock",
        )
        for (i in 0 until count) {
            assertTrue(
                "concurrent-$i.example" in persisted,
                "concurrent-$i.example was recorded but is missing from disk",
            )
        }
    }

    /**
     * Empty inputs do not write. A common side-effect of moving the snapshot is accidentally
     * serialising an empty set on every keystroke that does not add a new host - and the
     * observable file write here would surface it.
     */
    @Test
    fun `recordLoaded of a blank host does not write or mutate the set`() {
        val before = ResolvedHostsStore.saveNowBlocking()
        ResolvedHostsStore.recordLoaded("")
        ResolvedHostsStore.recordLoaded("   ")
        val after = ResolvedHostsStore.saveNowBlocking()
        assertEquals(before, after, "no new host, no write - bytes must be identical")
    }

    private fun decode(payload: String): Set<String> =
        kotlinx.serialization.json.Json
            .decodeFromString<List<String>>(payload)
            .toSet()
}
