package ai.rever.boss.config

import ai.rever.boss.utils.sha256Of
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the staged-engine promotion swap: the multi-step move-aside /
 * promote / restore file logic in [ChromiumAutoDownloader.promotePendingInstall].
 */
class ChromiumAutoDownloaderTest {
    @TempDir
    lateinit var root: File

    private val pending get() = File(root, "boss-chromium.pending")
    private val target get() = File(root, "boss-chromium")
    private val backup get() = File(root, "boss-chromium.old")

    /** A staging dir as downloadChromium(staged=true) leaves it on success. */
    private fun makeCompleteStaging(version: String = "9.2.0") {
        pending.mkdirs()
        File(pending, "executable.name").writeText("BOSS")
        File(pending, "version.txt").writeText(version)
        File(pending, "payload.bin").writeText("new-engine")
        File(pending, ".staging-complete").writeText(version)
    }

    private fun makeExistingTarget(version: String = "9.1.2") {
        target.mkdirs()
        File(target, "executable.name").writeText("BOSS")
        File(target, "version.txt").writeText(version)
        File(target, "payload.bin").writeText("old-engine")
    }

    private fun promote() = ChromiumAutoDownloader.promotePendingInstall(pending, target, backup)

    @Test
    fun `complete staged install replaces the existing engine`() {
        makeExistingTarget("9.1.2")
        makeCompleteStaging("9.2.0")

        promote()

        assertEquals("9.2.0", File(target, "version.txt").readText())
        assertEquals("new-engine", File(target, "payload.bin").readText())
        assertFalse(File(target, ".staging-complete").exists(), "commit marker should be removed after promote")
        assertFalse(pending.exists(), "staging dir should be gone")
        assertFalse(backup.exists(), "backup should be deleted after successful promote")
    }

    @Test
    fun `staging without commit marker is discarded and engine preserved`() {
        makeExistingTarget("9.1.2")
        makeCompleteStaging("9.2.0")
        File(pending, ".staging-complete").delete() // simulate interrupted extraction

        promote()

        assertFalse(pending.exists(), "incomplete staging should be discarded")
        assertEquals("9.1.2", File(target, "version.txt").readText(), "existing engine must be untouched")
        assertEquals("old-engine", File(target, "payload.bin").readText())
    }

    @Test
    fun `staging with markers from the archive but no commit marker is not promoted`() {
        // executable.name and version.txt can plausibly come from the archive itself;
        // only the strictly-last commit marker proves extraction finished.
        makeExistingTarget("9.1.2")
        pending.mkdirs()
        File(pending, "executable.name").writeText("BOSS")
        File(pending, "version.txt").writeText("9.2.0")

        promote()

        assertFalse(pending.exists())
        assertEquals("9.1.2", File(target, "version.txt").readText())
    }

    @Test
    fun `promotes onto a missing target (fresh install)`() {
        makeCompleteStaging("9.2.0")

        promote()

        assertTrue(target.exists())
        assertEquals("9.2.0", File(target, "version.txt").readText())
        assertFalse(pending.exists())
        assertFalse(backup.exists())
    }

    @Test
    fun `stale backup from an earlier failed swap is cleaned up`() {
        backup.mkdirs()
        File(backup, "stale.bin").writeText("stale")
        makeExistingTarget("9.1.2")
        makeCompleteStaging("9.2.0")

        promote()

        assertEquals("9.2.0", File(target, "version.txt").readText())
        assertFalse(backup.exists(), "stale backup must not survive a successful swap")
    }

    @Test
    fun `no-op when nothing is staged`() {
        makeExistingTarget("9.1.2")

        promote()

        assertEquals("9.1.2", File(target, "version.txt").readText())
        assertFalse(backup.exists())
    }

    @Test
    fun `read-only cache inspection leaves the startup repair attempt available`() {
        target.mkdirs()
        File(target, "executable.name").writeText("BOSS")
        File(target, "version.txt").writeText("9.2.0")
        val plist = File(target, "BOSS.app/Contents/Info.plist")
        plist.parentFile.mkdirs()
        plist.writeText("<plist><dict><key>CFBundleURLTypes</key><array/></dict></plist>")
        val marker = File(root, "boss-chromium.types-repair")

        fun inspect(recordRepair: () -> Unit) =
            ChromiumAutoDownloader.chromiumInstalledAt(
                dir = target.toPath(),
                requiredVersion = "9.2.0",
                isMac = true,
                repairAttempted = { marker.exists() },
                recordRepair = recordRepair,
            )

        assertFalse(inspect {})
        assertFalse(inspect {})
        assertFalse(marker.exists(), "health inspection must not consume the repair budget")
        assertFalse(inspect { marker.writeText("9.2.0") }, "startup must still schedule the repair")
        assertTrue(marker.exists())
        assertTrue(inspect { error("the one-shot repair must not repeat") })
    }

    // ---- installFromCandidates: source fallback + checksum verification ----

    private fun candidate(
        source: String,
        url: String,
        sha: String? = null,
    ) = EngineDownloadCandidate(source, url, sha)

    /** Fake extraction: produce the one file the installer verifies. */
    private val fakeExtract: (java.nio.file.Path, java.nio.file.Path) -> Unit = { _, dest ->
        dest.toFile().mkdirs()
        File(dest.toFile(), "executable.name").writeText("BOSS")
    }

    @Test
    fun `falls back to the next source when a fetch fails`() =
        runBlocking {
            val attempted = mutableListOf<String>()

            val result =
                ChromiumAutoDownloader.installFromCandidates(
                    candidates = listOf(candidate("supabase", "https://supabase/a.zip"), candidate("github", "https://github/a.zip")),
                    version = "9.2.0",
                    targetDir = target.toPath(),
                    staged = false,
                    onProgress = {},
                    fetch = { url, dest ->
                        attempted += url
                        if (url.startsWith("https://supabase")) throw IllegalStateException("supabase down")
                        dest.toFile().writeText("zip-bytes")
                    },
                    extract = fakeExtract,
                )

            assertTrue(result.isSuccess)
            assertEquals(listOf("https://supabase/a.zip", "https://github/a.zip"), attempted)
            assertEquals("9.2.0", File(target, "version.txt").readText())
        }

    @Test
    fun `checksum mismatch rejects the candidate and falls through to the next source`() =
        runBlocking {
            val goodSha = sha256Of(File(root, "sha-src").apply { writeText("good-bytes") })
            val attempted = mutableListOf<String>()

            val result =
                ChromiumAutoDownloader.installFromCandidates(
                    candidates =
                        listOf(
                            candidate("supabase", "https://supabase/a.zip", sha = goodSha),
                            candidate("github", "https://github/a.zip"), // no hash available
                        ),
                    version = "9.2.0",
                    targetDir = target.toPath(),
                    staged = false,
                    onProgress = {},
                    fetch = { url, dest ->
                        attempted += url
                        // Supabase serves corrupted bytes that won't match goodSha
                        dest.toFile().writeText(if (url.startsWith("https://supabase")) "corrupted" else "good-bytes")
                    },
                    extract = fakeExtract,
                )

            assertTrue(result.isSuccess)
            assertEquals(listOf("https://supabase/a.zip", "https://github/a.zip"), attempted)
        }

    @Test
    fun `matching checksum is accepted`() =
        runBlocking {
            val goodSha = sha256Of(File(root, "sha-src").apply { writeText("good-bytes") })
            val attempted = mutableListOf<String>()

            val result =
                ChromiumAutoDownloader.installFromCandidates(
                    candidates =
                        listOf(
                            candidate("supabase", "https://supabase/a.zip", sha = goodSha),
                            candidate("github", "https://github/a.zip"),
                        ),
                    version = "9.2.0",
                    targetDir = target.toPath(),
                    staged = false,
                    onProgress = {},
                    fetch = { url, dest ->
                        attempted += url
                        dest.toFile().writeText("good-bytes")
                    },
                    extract = fakeExtract,
                )

            assertTrue(result.isSuccess)
            assertEquals(listOf("https://supabase/a.zip"), attempted, "github must not be attempted after a verified supabase download")
        }

    @Test
    fun `staged install writes the commit marker last`() =
        runBlocking {
            val result =
                ChromiumAutoDownloader.installFromCandidates(
                    candidates = listOf(candidate("github", "https://github/a.zip")),
                    version = "9.2.0",
                    targetDir = pending.toPath(),
                    staged = true,
                    onProgress = {},
                    fetch = { _, dest -> dest.toFile().writeText("zip-bytes") },
                    extract = fakeExtract,
                )

            assertTrue(result.isSuccess)
            assertEquals("9.2.0", File(pending, ".staging-complete").readText())
        }

    @Test
    fun `all candidates failing returns failure and reports the error`() =
        runBlocking {
            var reportedError: String? = null

            val result =
                ChromiumAutoDownloader.installFromCandidates(
                    candidates = listOf(candidate("supabase", "https://supabase/a.zip"), candidate("github", "https://github/a.zip")),
                    version = "9.2.0",
                    targetDir = target.toPath(),
                    staged = false,
                    onProgress = { p -> if (p.error != null) reportedError = p.error },
                    fetch = { _, _ -> throw IllegalStateException("network down") },
                    extract = fakeExtract,
                )

            assertTrue(result.isFailure)
            assertEquals("network down", reportedError)
            assertFalse(File(target, "version.txt").exists())
        }

    // ---- atomicInstallSwap: non-staged path rollback contract ----

    private val swap get() = File(root, "boss-chromium.swap")
    private fun makeSwap(version: String = "9.2.0") {
        swap.mkdirs()
        File(swap, "executable.name").writeText("BOSS")
        File(swap, "version.txt").writeText(version)
        File(swap, "payload.bin").writeText("new-engine")
    }

    private fun swapNow(rename: (File, File) -> Boolean = { a, b -> a.renameTo(b) }) =
        ChromiumAutoDownloader.atomicInstallSwap(swap, target, backup, rename)

    @Test
    fun `atomic swap succeeds when rename of swap into target works`() {
        makeExistingTarget("9.1.2")
        makeSwap("9.2.0")

        assertTrue(swapNow())
        assertEquals("9.2.0", File(target, "version.txt").readText())
        assertEquals("new-engine", File(target, "payload.bin").readText())
        assertFalse(swap.exists(), "swap dir must be consumed on success")
        assertFalse(backup.exists(), "backup must be deleted on success")
    }

    @Test
    fun `atomic swap onto a missing target succeeds (fresh install)`() {
        makeSwap("9.2.0")

        assertTrue(swapNow())
        assertTrue(target.exists())
        assertEquals("9.2.0", File(target, "version.txt").readText())
        assertFalse(swap.exists())
        assertFalse(backup.exists())
    }

    @Test
    fun `promotion failure restores the previous engine from backup`() {
        // First rename (target -> backup) succeeds; second rename (swap ->
        // target) fails. The previous engine must end up back in target.
        makeExistingTarget("9.1.2")
        makeSwap("9.2.0")

        var moves = 0
        val renaming: (File, File) -> Boolean = { src, dest ->
            moves++
            if (src == swap && dest == target) false else src.renameTo(dest)
        }

        assertFalse(swapNow(renaming), "swap must report failure when promotion cannot complete")
        assertEquals("9.1.2", File(target, "version.txt").readText(), "previous engine must be restored into target")
        assertEquals("old-engine", File(target, "payload.bin").readText())
        assertFalse(swap.exists(), "swap dir must be cleaned up after a failed promotion")
        assertFalse(backup.exists(), "backup must be cleaned up after restoration")
    }

    @Test
    fun `rollback cannot restore from backup returns failure and preserves the engine in backup`() {
        // First rename (target -> backup) succeeds; second rename (swap ->
        // target) fails; backup rename (backup -> target) ALSO fails.
        // The call must report failure and the only copy of the engine (now
        // sitting in backup) must survive so manual recovery can put it back.
        makeExistingTarget("9.1.2")
        makeSwap("9.2.0")

        val renaming: (File, File) -> Boolean = { src, dest ->
            // swap -> target and backup -> target both fail
            if (dest == target) false else src.renameTo(dest)
        }

        assertFalse(swapNow(renaming), "must report failure when promotion and restore both fail")
        assertFalse(target.exists(), "without restoration target is gone - caller must re-download")
        assertTrue(
            backup.exists(),
            "backup holds the only copy of the engine when restoration fails - it must survive for manual recovery",
        )
        assertEquals("9.1.2", File(backup, "version.txt").readText(), "backup must hold the live engine's data")
        assertFalse(swap.exists(), "swap dir must be cleaned up")
    }

    @Test
    fun `move-aside failure returns failure and preserves the live engine`() {
        // target exists but target -> backup rename fails. Live engine must
        // remain in target; swap must be cleaned up so the next attempt starts
        // clean.
        makeExistingTarget("9.1.2")
        makeSwap("9.2.0")

        val renaming: (File, File) -> Boolean = { src, dest ->
            // only the move-aside fails
            if (src == target && dest == backup) false else src.renameTo(dest)
        }

        assertFalse(swapNow(renaming))
        assertEquals("9.1.2", File(target, "version.txt").readText(), "live engine must be untouched when move-aside fails")
        assertEquals("old-engine", File(target, "payload.bin").readText())
        assertFalse(swap.exists(), "swap dir must be cleaned up so retry isn't blocked by stale files")
        assertFalse(backup.exists())
    }

    @Test
    fun `atomic swap exception during rename is logged and returns failure`() {
        makeExistingTarget("9.1.2")
        makeSwap("9.2.0")

        val renaming: (File, File) -> Boolean = { _, _ ->
            throw RuntimeException("simulated AV scanner killed rename")
        }

        assertFalse(swapNow(renaming))
        // Exception path leaves target/backup/swap as it found them (no half-state).
        // The catch block attempts a best-effort restore but it also throws here.
        assertFalse(swap.exists(), "swap cleanup is best-effort but must not throw past the function")
    }

    // ---- Hard-kill mid-swap recovery at startup ----

    /**
     * Recovers the same way [promotePendingInstall] does at app startup: a leftover
     * `boss-chromium.old` with no live `boss-chromium` is promoted back into place
     * before any new swap runs. The function under test is private, so the test
     * drives the public entry point after seeding the hard-kill state.
     */
    private fun recoverAfterHardKill(): Boolean {
        // Use the no-arg overload which runs recoverInterruptedSwap first.
        ChromiumAutoDownloader.promotePendingInstall(
            pending = File(root, "boss-chromium.pending"),
            target = target,
            backup = backup,
        )
        return target.exists()
    }

    @Test
    fun `hard-kill recovery promotes backup back into target on next startup`() {
        // Simulate: previous swap moved target -> backup, then process died.
        // No new pending install exists.
        makeExistingTarget("9.1.2")
        File(target, "payload.bin").delete()
        target.deleteRecursively()
        // Now "backup" holds the engine the user actually had:
        File(backup, "version.txt").writeText("9.1.2")
        File(backup, "executable.name").writeText("BOSS")
        File(backup, "payload.bin").writeText("old-engine")

        assertTrue(recoverAfterHardKill())
        assertEquals("9.1.2", File(target, "version.txt").readText())
        assertEquals("old-engine", File(target, "payload.bin").readText())
        assertFalse(backup.exists(), "backup must be consumed by recovery")
    }

    @Test
    fun `hard-kill recovery is a no-op when target still exists`() {
        // If both target and backup exist (a swap somehow landed twice), do not
        // clobber the live engine.
        makeExistingTarget("9.1.2")
        File(backup, "version.txt").writeText("9.0.0")
        File(backup, "payload.bin").writeText("older")

        assertTrue(recoverAfterHardKill())
        assertEquals("9.1.2", File(target, "version.txt").readText(), "live engine must win over a stale backup")
        assertEquals("old-engine", File(target, "payload.bin").readText())
    }

    @Test
    fun `hard-kill recovery is a no-op when neither target nor backup exists`() {
        // Fresh install scenario - nothing to recover.
        assertTrue(recoverAfterHardKill())
        assertFalse(target.exists())
        assertFalse(backup.exists())
    }

    @Test
    fun `failed extraction of non-staged install preserves the live engine`() =
        runBlocking {
            // Simulate a non-staged download whose extraction fails partway: the
            // extract lambda throws after the swap dir was created. The swap
            // dir is cleaned up before rethrow and the live engine is untouched.
            makeExistingTarget("9.1.2")
            val beforeVersion = File(target, "version.txt").readText()

            val result =
                ChromiumAutoDownloader.installFromCandidates(
                    candidates = listOf(candidate("github", "https://github/a.zip")),
                    version = "9.2.0",
                    targetDir = target.toPath(),
                    staged = false,
                    onProgress = {},
                    swapDir = File(root, "boss-chromium.swap").toPath(),
                    backupDir = File(root, "boss-chromium.old").toPath(),
                    fetch = { _, dest -> dest.toFile().writeText("zip-bytes") },
                    extract = { _, _ -> throw IllegalStateException("extraction blew up") },
                )

            assertTrue(result.isFailure)
            assertEquals(beforeVersion, File(target, "version.txt").readText(), "live engine must be untouched")
            assertFalse(
                File(root, "boss-chromium.swap").exists(),
                "stale swap dir must be cleaned up so the next install starts fresh",
            )
            assertFalse(File(root, "boss-chromium.old").exists())
        }

    @Test
    fun `failed extraction of staged install leaves the pending dir alone`() =
        runBlocking {
            // Staged installs do not touch the live engine, so a failed
            // extraction must leave both the live engine and the existing
            // pending dir alone for the next try.
            makeExistingTarget("9.1.2")
            val pendingDir = File(root, "boss-chromium.pending")
            pendingDir.mkdirs()
            File(pendingDir, "old-pending.bin").writeText("leftover")

            val result =
                ChromiumAutoDownloader.installFromCandidates(
                    candidates = listOf(candidate("github", "https://github/a.zip")),
                    version = "9.2.0",
                    targetDir = pendingDir.toPath(),
                    staged = true,
                    onProgress = {},
                    fetch = { _, dest -> dest.toFile().writeText("zip-bytes") },
                    extract = { _, _ -> throw IllegalStateException("extraction blew up") },
                )

            assertTrue(result.isFailure)
            assertEquals("9.1.2", File(target, "version.txt").readText(), "live engine must be untouched")
            assertTrue(
                File(pendingDir, "old-pending.bin").exists(),
                "staged extraction cleans up its own partial files but does not sweep a prior pending dir",
            )
        }
}
