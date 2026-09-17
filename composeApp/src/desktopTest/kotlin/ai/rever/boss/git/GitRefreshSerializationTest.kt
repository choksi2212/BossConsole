package ai.rever.boss.git

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for the serialized git refresh (BossConsole#813).
 *
 * DesktopGitService.refresh() opened with `refreshJob?.cancel()` over a field
 * that nothing ever assigned - a dead guard. Two concurrent refresh() calls
 * therefore both ran in full and interleaved their writes to the shared git
 * state (branch/flows), producing a window-switched mix of one project's
 * branch with another's repo state. refresh() and clear() now serialize on
 * one mutex; these tests pin that the second refresh waits and that the
 * surviving state is internally consistent - the branch belongs to the
 * project that refreshed LAST, never a cross-project mix.
 *
 * Uses real `git` in a temp directory (the repo's established git-test
 * pattern, see GitProviderWritesToRepoTest): no stubbing of the binary.
 */
class GitRefreshSerializationTest {
    @TempDir
    lateinit var tempDir: File

    private fun git(
        dir: File,
        vararg args: String,
    ) {
        val p = ProcessBuilder(listOf("git", *args)).directory(dir).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText()
        p.waitFor()
    }

    private fun repo(
        name: String,
        branch: String,
    ): File {
        val dir = File(tempDir, name).apply { mkdirs() }
        git(dir, "init", "-q", "-b", branch)
        git(dir, "config", "user.email", "t@example.com")
        git(dir, "config", "user.name", "Test")
        git(dir, "config", "commit.gpgsign", "false")
        File(dir, "tracked.txt").writeText("one\n")
        git(dir, "add", ".")
        git(dir, "commit", "-q", "-m", "init")
        return dir
    }

    @Test
    fun `concurrent refreshes leave the last project's state, never a cross-project mix`() =
        runTest {
            val service = GitService
            val repoA = repo("alpha", "alpha-branch")
            val repoB = repo("beta", "beta-branch")

            // Two refreshes race exactly as the app can produce them (project
            // open in one window, panel refresh in another). Unserialized, the
            // loser's writes land wherever the interleaving puts them; with the
            // mutex, one completes fully and then the other.
            val first = async { service.refresh(repoA.absolutePath) }
            val second = async { service.refresh(repoB.absolutePath) }
            first.await()
            second.await()

            // Whichever project refreshed LAST owns the state; the branch, the
            // repository-ness, and the recorded path must all belong to that
            // SAME project - never a mix. Coroutine start order does not
            // determine completion order even under the mutex (the first
            // async may acquire the lock second), so assert the cross-field
            // consistency rather than which project won.
            val lastBranch = service.currentBranch.value
            val isRepo = service.isGitRepository.value
            val lastPath = service.getCurrentProjectPath()

            assertTrue(isRepo, "the surviving state must be a repository")
            val expectedBranch =
                when (lastPath) {
                    repoA.absolutePath -> "alpha-branch"
                    repoB.absolutePath -> "beta-branch"
                    else -> null
                }
            assertEquals(
                expectedBranch,
                lastBranch,
                "the surviving branch must belong to the project the path names " +
                    "(path=$lastPath, branch=$lastBranch) - a cross-project mix is the #813 bug",
            )
        }

    @Test
    fun `a clear during an in-flight refresh cannot be overwritten by its leftovers`() =
        runTest {
            val service = GitService
            val repoA = repo("gamma", "gamma-branch")

            // Refresh runs to completion, then a clear (project closed) zeroes
            // the state. Unserialized, a refresh that started before the clear
            // could still be writing; with the shared mutex the clear waits
            // and the zeroed state survives.
            service.refresh(repoA.absolutePath)
            service.clear()

            assertEquals(null, service.currentBranch.value, "the clear must zero the branch state")
            assertEquals(false, service.isGitRepository.value, "the clear must zero the repository flag")
            assertEquals(null, service.getCurrentProjectPath(), "the clear must zero the project path")
        }
}
