package ai.rever.boss.cache

import java.nio.file.Files
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for symlink safety in the favicon caches. Both [FaviconCache] and
 * [HqFaviconDiskCache] enumerate a directory under `~/.boss/cache` and call
 * `File.delete()` on each entry. `File.delete` follows symlinks, and
 * `file.isFile` is also true for a symlink whose target is a regular file - so
 * a symlink planted in the cache directory pointing at an arbitrary file on
 * disk would have its target deleted on the next cleanup sweep.
 *
 * The caches are singletons with no injection seam; the tests redirect
 * `cacheDir` to a per-test temp directory and verify the symlink survives a
 * cleanup sweep that should only have touched cache entries.
 */
class FaviconCacheSymlinkTest {
    private lateinit var tempDir: java.io.File
    private lateinit var realTarget: java.io.File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("favicon-cache-symlink-test").toFile()
        // An arbitrary file the symlink will point at. Anything the test would
        // NOT want deleted lives here.
        realTarget =
            java.io.File(tempDir, "real-target.txt").apply {
                writeText("important user data - do not delete\n")
            }
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /**
     * Pins the bug: a symlink planted in the favicon cache directory must
     * survive `FaviconCache.clearCache()` and `cleanupStaleEntries()`. Before
     * the fix, both called `File.delete()` on every entry and followed the
     * link, deleting the target.
     */
    @Test
    fun `FaviconCache clearCache skips symlinks and leaves the target intact`() {
        // Cache directory needs to exist for the cache to operate on it.
        tempDir.mkdirs()
        val symlinkInCache = java.io.File(tempDir, "link.png")
        Files.createSymbolicLinkPointingTo(symlinkInCache.toPath(), realTarget.toPath())

        FaviconCache.clearCacheInDirectoryForTest(tempDir)

        // The symlink stays put.
        assertTrue(
            symlinkInCache.exists(),
            "symlink itself must not be removed",
        )
        // The target is byte-identical: nothing on disk was rewritten.
        assertEquals(
            "important user data - do not delete\n",
            realTarget.readText(),
            "symlink target must not be overwritten or deleted",
        )
        assertTrue(Files.isSymbolicLink(symlinkInCache.toPath()))
    }

    @Test
    fun `FaviconCache cleanupStaleEntries skips symlinks and leaves the target intact`() {
        tempDir.mkdirs()
        val symlinkInCache = java.io.File(tempDir, "link.png")
        Files.createSymbolicLinkPointingTo(symlinkInCache.toPath(), realTarget.toPath())

        // daysOld = 0 means "older than now" - the cutoff matches nothing real,
        // so a real entry would survive. We want the symlink to also survive.
        FaviconCache.cleanupStaleEntriesInDirectoryForTest(tempDir, daysOld = 0)

        assertTrue(symlinkInCache.exists())
        assertEquals(
            "important user data - do not delete\n",
            realTarget.readText(),
            "symlink target must not be overwritten or deleted",
        )
    }

    @Test
    fun `HqFaviconDiskCache clear skips symlinks and leaves the target intact`() {
        // HQ cache has its own subdirectory; mirror the layout.
        val hqDir = java.io.File(tempDir, "favicon-hq-cache").apply { mkdirs() }
        val symlinkInCache = java.io.File(hqDir, "link.png")
        Files.createSymbolicLinkPointingTo(symlinkInCache.toPath(), realTarget.toPath())

        HqFaviconDiskCache.clearInDirectoryForTest(hqDir)

        assertTrue(symlinkInCache.exists())
        assertEquals(
            "important user data - do not delete\n",
            realTarget.readText(),
            "symlink target must not be overwritten or deleted",
        )
    }

    /**
     * A real cache entry (no symlink) is still removed by clearCache. The
     * symlink guard must not turn cleanup into a no-op for legitimate entries.
     */
    @Test
    fun `FaviconCache clearCache still removes real png entries`() {
        tempDir.mkdirs()
        val realEntry =
            java.io.File(tempDir, "real.png").apply {
                writeText("a real cache entry\n")
            }

        FaviconCache.clearCacheInDirectoryForTest(tempDir)

        assertFalse(realEntry.exists(), "real entries must still be removed")
    }
}
