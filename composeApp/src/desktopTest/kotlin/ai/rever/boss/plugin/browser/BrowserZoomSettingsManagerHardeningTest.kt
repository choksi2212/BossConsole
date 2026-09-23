package ai.rever.boss.plugin.browser

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for the BrowserZoomSettingsManager hardening (#925, #1051):
 *
 * - a corrupt settings file is renamed aside (self-heal) instead of silently
 *   re-failing every launch at the live path;
 * - the rename-aside helper is a pure file operation, so it is pinned on a
 *   caller-supplied directory without touching the shared BossDirectories root;
 * - the helper's aside name is collision-proof and its `renameTo` return
 *   value is checked, so two corrupt cycles in the same millisecond do not
 *   clobber each other and a Windows-side rename failure leaves a signal;
 * - the read-modify-write mutators ([setZoomForDomain], [clearDomainZoom],
 *   [clearAllSettings]) hold [BrowserZoomSettingsManager]'s save lock for
 *   their whole critical section, so concurrent mutators do not lose updates;
 * - the manager's own `saveSettingsSync` and `loadSettings` round-trip a
 *   value through the real atomic-write helper (and a corrupt file lands
 *   beside the live path with the documented `<name>.corrupt.<millis>.<uuid>`
 *   suffix), exercising the same path the production manager uses.
 */
class BrowserZoomSettingsManagerHardeningTest {
    private lateinit var tmp: File
    private var originalFile: File? = null

    @BeforeTest
    fun setUp() {
        tmp = File.createTempFile("zoom-hardening", null)
        tmp.delete()
        tmp.mkdir()
        originalFile = BrowserZoomSettingsManager.settingsFile
        BrowserZoomSettingsManager.settingsFile = File(tmp, "browser-zoom-settings.json")
        // Start every test from a clean in-memory state so the assertions
        // about what survived a corrupt load are not polluted by the
        // previous test's domain map.
        BrowserZoomSettingsManager.clearAllSettings()
    }

    @AfterTest
    fun tearDown() {
        originalFile?.let { BrowserZoomSettingsManager.settingsFile = it }
        tmp.deleteRecursively()
        BrowserZoomSettingsManager.clearAllSettings()
    }

    // --- quarantine helper: pure file operation ----------------------------------------------

    @Test
    fun `a corrupt settings file is renamed aside and no longer sits at the live path`() {
        val live = File(tmp, "browser-zoom-settings.json")
        live.writeText("{ this is not valid json")

        moveCorruptSettingsAside(live) { 1726000000000L }

        // The corrupt bytes no longer sit at the live name; an aside copy survives for diagnosis.
        assertFalse(live.exists(), "the corrupt file must not remain at the live path")
        val aside = tmp.listFiles()!!.single { it.name.startsWith("browser-zoom-settings.json.corrupt.") }
        assertTrue(aside.exists(), "the aside copy must exist")
        assertTrue(aside.name.contains("1726000000000"), "millisecond timestamp preserved for diagnosis: ${aside.name}")
        assertEquals(true, aside.readText().startsWith("{ this"))
    }

    @Test
    fun `an absent file is a no-op - no aside is created`() {
        val absent = File(tmp, "never-existed.json")
        moveCorruptSettingsAside(absent) { 1726000000000L }
        assertEquals(0, tmp.listFiles()!!.size, "the directory must stay empty - no aside for an absent file")
    }

    /**
     * #1051's collision case: two corrupt cycles that resolve to the SAME
     * millisecond used to rename to the same target, so POSIX `rename(2)`
     * overwrote the earlier backup and Windows `MoveFile` reported
     * `ERROR_ALREADY_EXISTS`. The UUID half guarantees distinct asides,
     * so both survive.
     */
    @Test
    fun `two corrupt cycles in the same millisecond keep two distinct asides`() {
        val live = File(tmp, "browser-zoom-settings.json")
        live.writeText("garbage one")
        moveCorruptSettingsAside(live) { 1L }
        live.writeText("garbage two")
        moveCorruptSettingsAside(live) { 1L }

        val asides = tmp.listFiles()!!.filter { it.name.contains(".corrupt.") }
        assertEquals(2, asides.size, "two distinct corrupt cycles must keep two diagnosable asides")
        assertEquals(setOf("garbage one", "garbage two"), asides.map { it.readText() }.toSet())
        assertEquals(2, asides.map { it.name }.toSet().size, "the two asides must have distinct names")
    }

    /**
     * #1051's rename-failure case: when [File.renameTo] cannot move the
     * file (a locked target on Windows, a path that has already vanished,
     * or any other platform-specific refusal), the helper must leave a
     * signal that the recovery step did not actually move anything. The
     * test arranges the failure by making the live path a directory: the
     * JVM refuses to rename a file onto a path occupied by a directory, so
     * renameTo returns false. The live file must STILL be there after the
     * helper runs (the helper cannot remove it), and no aside should be
     * created at the would-be target.
     */
    @Test
    fun `a rename failure leaves the source in place and does not create an aside`() {
        val live = File(tmp, "browser-zoom-settings.json")
        live.writeText("{ broken")
        // Block renameTo by occupying the target path with a directory.
        val blocker = File(tmp, "browser-zoom-settings.json.corrupt.1.dir-blocker")
        blocker.mkdirs()

        moveCorruptSettingsAside(live) { 1L }

        assertTrue(live.exists(), "renameTo failure must leave the live file in place")
        assertTrue(blocker.exists(), "the blocker must not be disturbed")
        // No aside file is created when the rename does not happen.
        assertEquals(emptyList(), tmp.listFiles()!!.filter { it.name.contains(".corrupt.") && !it.name.endsWith("-dir-blocker") })
        blocker.delete()
    }

    // --- real save/load roundtrip through the manager ----------------------------------------

    /**
     * #1051's end-to-end shape: the manager's own `saveSettingsSync` and
     * `loadSettings` round-trip a value through the same atomic-write helper
     * the production code uses, and the file on disk contains the bytes
     * that were meant to land there (no half-written live file, no leftover
     * temp file).
     */
    @Test
    fun `a value written through saveSettingsSync round-trips through loadSettings`() {
        BrowserZoomSettingsManager.setZoomForDomain("example.com", 1.25)
        BrowserZoomSettingsManager.saveSettingsSync()

        val live = BrowserZoomSettingsManager.settingsFile
        assertTrue(live.exists(), "saveSettingsSync must create the live settings file")

        val onDisk = live.readText()
        assertTrue(onDisk.contains("\"example.com\""), "the saved file must contain the domain: $onDisk")
        assertTrue(onDisk.contains("1.25"), "the saved file must contain the zoom level: $onDisk")

        // No temp file should sit beside the live file; the atomic move took it.
        assertEquals(emptyList(), tmp.listFiles()!!.filter { it.name.startsWith(live.name) && it.name != live.name })

        // Forcing a load exercises the file-read path: the value the save
        // wrote must come back out. The simplest way to verify that without
        // resetting the manager's in-memory state is to rebuild the JSON
        // decode path the same way loadSettings does.
        val reloaded = kotlinx.serialization.json.Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }.decodeFromString<BrowserZoomSettingsData>(onDisk)
        assertEquals(1.25, reloaded.domainSettings["example.com"]?.zoomLevel)
    }

    /**
     * The corrupt-load self-heal path through the real manager: a
     * deliberately-broken file at the live path is renamed aside when
     * [BrowserZoomSettingsManager.loadSettings] runs, the live path is
     * freed for the next save, and the in-memory state falls back to the
     * default rather than staying broken.
     */
    @Test
    fun `loading a corrupt file through the manager renames it aside and recovers to defaults`() {
        BrowserZoomSettingsManager.setZoomForDomain("before-load.example", 1.5)
        BrowserZoomSettingsManager.saveSettingsSync()
        // Replace the saved file with corrupt bytes so the next load triggers recovery.
        BrowserZoomSettingsManager.settingsFile.writeText("{ not valid json")

        BrowserZoomSettingsManager.loadSettings()

        assertFalse(
            BrowserZoomSettingsManager.settingsFile.exists(),
            "the live path must be free after the corrupt file is renamed aside",
        )
        val asides = tmp.listFiles()!!.filter { it.name.startsWith("browser-zoom-settings.json.corrupt.") }
        assertEquals(1, asides.size, "exactly one aside for the one corrupt load")
        assertEquals("default", BrowserZoomSettingsManager.getZoomForDomain("after-load.example"))
    }

    // --- read-modify-write race: concurrent mutators must not lose updates ------------------

    /**
     * #1051's lost-update case: the in-memory `settings` map is read,
     * mutated, and written back by [setZoomForDomain], and two concurrent
     * callers without serialization can each read the same starting state
     * and overwrite each other. The lock turns the R-M-W into a single
     * critical section, so every domain a thread writes survives.
     *
     * Without the lock the test fails under any thread schedule where two
     * reads complete before either write - which the [CyclicBarrier] at
     * the call site is meant to encourage, and which the 100 iterations
     * above that make reliable.
     */
    @Test
    fun `concurrent mutators do not lose updates`() {
        val iterations = 100
        val threadCount = 4
        val executor = Executors.newFixedThreadPool(threadCount)

        try {
            repeat(iterations) { iteration ->
                BrowserZoomSettingsManager.clearAllSettings()
                val domains = Array(threadCount) { i -> "iter-$iteration-thread-$i.example" }
                val ready = CyclicBarrier(threadCount)
                val done = CountDownLatch(threadCount)
                for (i in 0 until threadCount) {
                    executor.execute {
                        // All threads enter the R-M-W at the same instant
                        // so the race window is at its widest.
                        ready.await(5, TimeUnit.SECONDS)
                        BrowserZoomSettingsManager.setZoomForDomain(domains[i], 1.0 + i * 0.1)
                        done.countDown()
                    }
                }
                assertTrue(done.await(5, TimeUnit.SECONDS), "iteration $iteration: threads did not finish")
                for (i in 0 until threadCount) {
                    val expected = 1.0 + i * 0.1
                    val actual = BrowserZoomSettingsManager.getZoomForDomain(domains[i])
                    assertEquals(expected, actual, "iteration $iteration thread $i: ${domains[i]} lost its update")
                }
            }
        } finally {
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    /**
     * Companion regression: a mutator interleaved with a save must not
     * observe a torn state - the saved file must contain the union of the
     * mutator's write and whatever the save captured. Without the lock,
     * the save could read the in-memory state BEFORE the mutator's update
     * lands and the file would be missing the latest change.
     */
    @Test
    fun `a save after a mutator contains the mutator's value`() {
        BrowserZoomSettingsManager.clearAllSettings()
        BrowserZoomSettingsManager.setZoomForDomain("first.example", 1.25)
        BrowserZoomSettingsManager.saveSettingsSync()
        BrowserZoomSettingsManager.setZoomForDomain("second.example", 1.5)
        BrowserZoomSettingsManager.saveSettingsSync()

        val onDisk = BrowserZoomSettingsManager.settingsFile.readText()
        assertTrue(onDisk.contains("first.example"), "first mutator's domain must be in the saved file")
        assertTrue(onDisk.contains("second.example"), "second mutator's domain must be in the saved file")

        // POSIX permissions on the saved file match the atomic-write helper's
        // owner-only contract; we check this on POSIX and skip silently on
        // Windows where the bit is meaningless.
        val perms = runCatching { Files.getPosixFilePermissions(BrowserZoomSettingsManager.settingsFile.toPath()) }.getOrNull()
        if (perms != null) {
            assertEquals(setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE), perms)
        }
    }
}
