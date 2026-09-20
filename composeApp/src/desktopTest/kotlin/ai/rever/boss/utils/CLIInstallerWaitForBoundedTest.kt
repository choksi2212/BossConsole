package ai.rever.boss.utils

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression for the bug where `CLIInstaller.updateWindowsPath` called
 * `process.waitFor()` with no timeout, so a hung `setx PATH` pinned an IO thread
 * for the JVM lifetime. See issue #1254.
 *
 * The test exercises the bounded-wait helper that replaces the bare `waitFor()`.
 * A real `setx PATH` cannot be reproduced portably (it is Windows-only and
 * hangs depend on registry contention), so the helper is driven with a process
 * that intentionally outlives the bound. The assertion is that the helper
 * returns within a small multiple of the bound, regardless of how long the
 * child has to live.
 */
class CLIInstallerWaitForBoundedTest {
    @Test
    fun `bounded wait returns false and does not hang when the child outlives the bound`() {
        val process = longSleepingProcess()
        val bound = 1L
        val started = System.currentTimeMillis()
        val finished = waitForBounded(process, timeoutSeconds = bound, killGraceSeconds = 2L)
        val elapsedMs = System.currentTimeMillis() - started
        assertFalse(finished, "child should not have finished within the bound")
        // A 1 s bound plus a 2 s kill grace gives 3 s of ceiling. Allow generous slack for
        // CI noise, but the value MUST stay in the same order of magnitude as the bound.
        assertTrue(
            elapsedMs < TimeUnit.SECONDS.toMillis(10),
            "bounded wait must not hang for ~30 s on a 1 s bound (elapsed=${elapsedMs}ms)",
        )
        // The helper is supposed to have killed the process by the time it returned.
        assertFalse(process.isAlive, "bounded wait must destroy the child on timeout")
    }

    @Test
    fun `bounded wait returns true for a child that exits well within the bound`() {
        val process = quickExitProcess()
        val finished = waitForBounded(process, timeoutSeconds = 5L, killGraceSeconds = 1L)
        assertTrue(finished, "a child that exits immediately should be reported as finished")
    }

    /**
     * Spawn a child that ignores SIGTERM (where supported) and sleeps far longer than any
     * reasonable bound. `ping -n` on Windows and `sleep` on POSIX both fit.
     */
    private fun longSleepingProcess(): Process {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val command =
            if (isWindows) {
                listOf("cmd", "/c", "ping", "-n", "60", "127.0.0.1")
            } else {
                listOf("sleep", "60")
            }
        return ProcessBuilder(command).start()
    }

    private fun quickExitProcess(): Process {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val command =
            if (isWindows) {
                listOf("cmd", "/c", "exit", "0")
            } else {
                listOf("true")
            }
        return ProcessBuilder(command).start()
    }
}
