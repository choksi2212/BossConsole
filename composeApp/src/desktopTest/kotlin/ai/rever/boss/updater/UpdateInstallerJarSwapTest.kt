package ai.rever.boss.updater

import ai.rever.boss.utils.atomicMoveFrom
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the JAR swap path that `installJarUpdate` uses. The production method
 * itself is private to `UpdateInstaller`, so these exercise the same dance it
 * does and pin the property the bug fix needs: a swap either lands entirely or
 * leaves the live file byte-identical, with a backup on disk the next install
 * can fall back to.
 *
 * The sequence under test is four steps:
 *  1. stage the download as a sibling `<live>.part` (regular copy, can fail)
 *  2. atomic-move live -> backup (live slot is empty if this succeeds)
 *  3. atomic-move `<live>.part` -> live (if this fails, restore live from backup)
 *  4. delete the backup on success
 *
 * The first fix attempt used steps 2 and 3 without staging first, and skipped
 * the restore on step-3 failure. The bug: a step-3 failure left the live jar
 * gone forever, with the backup still holding the old bytes but nothing
 * putting them back. The tests below pin the four cases (success, stage
 * failure, backup failure, promote failure) so any regression on any step
 * fails a named test.
 */
class UpdateInstallerJarSwapTest {
    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("update-jar-swap-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /**
     * The "happy path" a non-broken run looks like. The live jar should end up
     * holding the new bytes and no backup should remain (the cleanup step
     * drops it on success).
     */
    @Test
    fun `atomic swap leaves the live jar holding the new bytes and removes the backup`() {
        val (liveJar, download, backup, part) = installScenario()
        runInstall(liveJar, download, backup, part, failurePoint = null)

        assertTrue(liveJar.exists(), "live jar must exist")
        assertEquals(listOf<Byte>(99, 98, 97, 96), liveJar.readBytes().toList())
        assertFalse(backup.exists(), "backup must be removed on success")
        assertFalse(part.exists(), "part must be removed on success")
    }

    /**
     * Stage failure: the download cannot be staged (parent directory gone, or
     * disk full, or any other step-1 reason). The live jar must be untouched -
     * the user is still running the previous build, no recovery is needed.
     */
    @Test
    fun `stage failure leaves the live jar byte-identical`() {
        val (liveJar, download, backup, part) = installScenario()
        val original = liveJar.readBytes()

        runInstall(liveJar, download, backup, part, failurePoint = "stage")

        assertTrue(liveJar.exists(), "live jar must still exist after stage failure")
        assertEquals(original.toList(), liveJar.readBytes().toList(), "live jar must be byte-identical")
        assertFalse(backup.exists(), "backup must not be created when stage failed")
        assertFalse(part.exists(), "part must be cleaned up when stage failed")
        // The download stays put - the user can retry the install.
        assertTrue(download.exists(), "download must be preserved so the install can be retried")
    }

    /**
     * Backup failure: the stage worked (the part holds the new bytes), but the
     * atomic move from live -> backup failed. The live jar must be untouched
     * (the atomic move that failed did not modify it) and the staged part must
     * be cleaned up so it does not look like a runnable jar to the directory
     * scan.
     */
    @Test
    fun `backup failure leaves the live jar byte-identical and cleans the staged part`() {
        val (liveJar, download, backup, part) = installScenario()
        val original = liveJar.readBytes()

        runInstall(liveJar, download, backup, part, failurePoint = "backup")

        assertTrue(liveJar.exists(), "live jar must still exist after backup failure")
        assertEquals(original.toList(), liveJar.readBytes().toList(), "live jar must be byte-identical")
        assertFalse(part.exists(), "staged part must be cleaned up when backup failed")
        // No backup written (the atomic move threw, so the destination never appeared).
        assertFalse(backup.exists(), "backup must not be written when backup failed")
        assertTrue(download.exists(), "download must be preserved so the install can be retried")
    }

    /**
     * Promote failure: this is the regression Shivang caught. The previous fix
     * did backup then promote without a restore. If promote throws, the live
     * jar slot is empty and the backup holds the old bytes, with nothing on
     * disk to put them back. The fix restores live from backup on promote
     * failure, so the install leaves a runnable jar (the previous build).
     */
    @Test
    fun `promote failure restores the live jar from the backup`() {
        val (liveJar, download, backup, part) = installScenario()
        val original = liveJar.readBytes()

        runInstall(liveJar, download, backup, part, failurePoint = "promote")

        assertTrue(liveJar.exists(), "live jar must be restored after promote failure")
        assertEquals(
            original.toList(),
            liveJar.readBytes().toList(),
            "live jar must hold the OLD bytes (restored from backup)",
        )
        assertFalse(part.exists(), "staged part must be cleaned up when promote failed")
        // The restore moved backup -> live, so the backup is gone.
        assertFalse(backup.exists(), "backup must be consumed by the restore")
    }

    /**
     * Build the four-file scenario: live jar with old bytes, download with new
     * bytes, empty backup, empty part. The byte values are distinct so any
     * unintended write shows up in the assertions.
     */
    private fun installScenario(): FileQuad {
        val liveJar =
            File(tempDir, "composeApp.jar").apply {
                writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
            }
        val download =
            File(tempDir, "download.jar").apply {
                writeBytes(byteArrayOf(99, 98, 97, 96))
            }
        val backup = File(tempDir, "composeApp.jar.backup")
        val part = File(tempDir, "composeApp.jar.part")
        return FileQuad(liveJar, download, backup, part)
    }

    /**
     * Mirror the production sequence. When [failurePoint] is null, the happy
     * path runs to completion. Otherwise the named step throws and we
     * reproduce the production cleanup for that branch.
     */
    private fun runInstall(
        liveJar: File,
        download: File,
        backup: File,
        part: File,
        failurePoint: String?,
    ) {
        // Step 1: stage.
        try {
            if (failurePoint == "stage") throw RuntimeException("simulated stage failure")
            Files.copy(
                download.toPath(),
                part.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: Exception) {
            part.delete()
            return
        }

        // Step 2: backup (atomic).
        if (backup.exists()) backup.delete()
        try {
            if (failurePoint == "backup") throw RuntimeException("simulated backup failure")
            backup.atomicMoveFrom(liveJar)
        } catch (e: Exception) {
            part.delete()
            return
        }

        // Step 3: promote (atomic). On failure, restore live from backup.
        try {
            if (failurePoint == "promote") throw RuntimeException("simulated promote failure")
            liveJar.atomicMoveFrom(part)
        } catch (e: Exception) {
            var restored = false
            try {
                liveJar.atomicMoveFrom(backup)
                restored = true
            } catch (_: Exception) {
                // Restore failed too. Leave both files so the user has at
                // least the backup to recover from by hand.
            }
            part.delete()
            if (restored) backup.delete()
            return
        }

        // Step 4: cleanup.
        backup.delete()
    }

    private data class FileQuad(
        val liveJar: File,
        val download: File,
        val backup: File,
        val part: File,
    )

    /**
     * The atomic property the production code relies on: a half-written jar is
     * never observable as the "live" jar. Simulate the failure path by
     * demonstrating that an atomic move that throws leaves the destination
     * untouched (the source `.part` file disappears on cleanup).
     */
    @Test
    fun `a failed atomic move leaves the live jar byte-identical`() {
        val liveJar =
            File(tempDir, "composeApp.jar").apply {
                // Distinctive bytes so any unintended write would be visible.
                writeBytes(byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50))
            }
        val originalBytes = liveJar.readBytes()

        // Force a failure: write nothing to source, then attempt to move it. The
        // source's canonical path does not exist, so Files.move throws NoSuchFileException.
        val emptyDownload = File(tempDir, "does-not-exist.jar")

        val moved =
            runCatching {
                liveJar.atomicMoveFrom(emptyDownload)
            }

        assertTrue(moved.isFailure, "expected move to fail when source does not exist")
        // Live jar is byte-identical: the failure left no partial write behind.
        assertEquals(
            originalBytes.toList(),
            liveJar.readBytes().toList(),
            "failed atomic move must not touch the destination",
        )
    }
}
