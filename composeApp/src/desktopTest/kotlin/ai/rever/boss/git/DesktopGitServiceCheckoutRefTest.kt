package ai.rever.boss.git

import ai.rever.boss.plugin.api.GitOperationResultData
import ai.rever.boss.window.WindowGitState
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for issue #919: a remote branch name like `origin/-f`
 * passes the pre-strip [GitService.isSafeRefName] gate (the WHOLE refname does
 * not start with `-`), but after `substringAfter("/")` the surviving `-f` was
 * passed to `git checkout` as an OPTION. With `--` placed AFTER the ref, the
 * ref still parsed as a flag (`git checkout -f -- ...`), which force-discards
 * every uncommitted change in the working tree.
 *
 * These tests pin the post-strip re-validation, plus a live `git checkout`
 * pass that proves no uncommitted file is lost when the input is hostile.
 */
class DesktopGitServiceCheckoutRefTest {
    private val tempRoot = File(System.getProperty("java.io.tmpdir"), "boss-git-919-${hashCode()}")

    @AfterTest
    fun cleanup() {
        tempRoot.deleteRecursively()
        // The GitService is a singleton, so tests that touched the global
        // project path must clear it - a later test in the same JVM that
        // points at its own temp repo must not inherit a deleted dir.
        GitService.clearCurrentProjectPathForTests()
    }

    private fun git(
        dir: File,
        vararg args: String,
    ): String {
        val p =
            ProcessBuilder(listOf("git", *args))
                .directory(dir)
                .redirectErrorStream(true)
                .start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out
    }

    /** A clean repo with one commit on `main` and a tracked file. */
    private fun repo(parent: File): File {
        val dir = File(parent, "repo").apply { mkdirs() }
        git(dir, "init", "-q", "-b", "main")
        git(dir, "config", "user.email", "t@example.com")
        git(dir, "config", "user.name", "Test")
        git(dir, "config", "commit.gpgsign", "false")
        // Pin LF on Windows runners too: core.autocrlf=true would round-trip a
        // committed "one\n" as "one\r\n" and fail content assertions below for
        // the wrong reason.
        git(dir, "config", "core.autocrlf", "false")
        File(dir, "tracked.txt").writeText("one\n")
        git(dir, "add", ".")
        git(dir, "commit", "-q", "-m", "init")
        return dir
    }

    private fun provider(dir: File): GitDataProviderImpl {
        val state = WindowGitState("w")
        return GitDataProviderImpl(state, { "w" }) { dir.absolutePath }
    }

    // ==================== isSafeRefName: the post-strip gate ====================

    @Test
    fun `isSafeRefName rejects refnames that start with a dash`() {
        // The gate the checkout post-strip re-runs. Each value below was a real
        // candidate for `git checkout` to read as a flag before #919.
        assertFalse(GitService.isSafeRefName("-f"), "-f must be refused")
        assertFalse(GitService.isSafeRefName("-foo"), "-foo must be refused")
        assertFalse(GitService.isSafeRefName("--force"), "--force must be refused")
        assertFalse(GitService.isSafeRefName("--orphan"), "--orphan must be refused")
        // Sanity: legal names still pass.
        assertTrue(GitService.isSafeRefName("main"))
        assertTrue(GitService.isSafeRefName("feature/x"))
        assertTrue(GitService.isSafeRefName("release/1.2.3"))
    }

    // ==================== checkout: post-strip re-validation ====================

    @Test
    fun `checkout refuses origin-slash-dashf even though the whole refname passes the pre-strip gate`(
        @TempDir tmp: File,
    ) = runTest {
        // The defining case from issue #919: `origin/-f` is NOT an option itself
        // (the WHOLE string does not start with `-`), so isSafeRefName accepts
        // it. Without the post-strip re-check, the strip yielded `-f` and that
        // was handed to `git checkout -f --`, discarding uncommitted work.
        val dir = repo(tmp)
        val p = provider(dir)

        val result = p.checkout("origin/-f")

        assertTrue(
            result !is GitOperationResultData.Success,
            "checkout accepted origin/-f; the post-strip re-validation is missing",
        )
    }

    @Test
    fun `checkout refuses origin-slash-dashfoo for the same reason as origin-slash-dashf`(
        @TempDir tmp: File,
    ) = runTest {
        // One more shape the picker feeds in: any post-strip `-`-prefix is the
        // same bug. A branch literally named `foo` is fine; the `origin/-` prefix
        // is what makes it land at `-foo`.
        val dir = repo(tmp)
        val p = provider(dir)

        val result = p.checkout("origin/-foo")

        assertTrue(result !is GitOperationResultData.Success, "checkout accepted origin/-foo: $result")
    }

    @Test
    fun `checkout accepts a normal origin-slash-branch name and checks it out`(
        @TempDir tmp: File,
    ) = runTest {
        // Sanity for the post-strip pass: an ordinary `origin/<branch>` input
        // still works. Without this the regression test above could pass for
        // the wrong reason (refusing everything, not just the hostile subset).
        val dir = repo(tmp)
        git(dir, "branch", "side")
        // Make `origin/side` resolvable from THIS repo's show-ref: clone into a
        // sibling so the picker sees a real remote-tracking ref.
        val cloneDir = File(tmp, "clone").apply { mkdirs() }
        git(tmp, "clone", "-q", dir.absolutePath, cloneDir.absolutePath)
        git(cloneDir, "config", "user.email", "t@example.com")
        git(cloneDir, "config", "user.name", "Test")
        val p = provider(cloneDir)

        val result = p.checkout("origin/side")

        assertTrue(result is GitOperationResultData.Success, "checkout of origin/side failed: $result")
        assertEquals(
            "side",
            git(cloneDir, "rev-parse", "--abbrev-ref", "HEAD").trim(),
            "origin/side should create and check out the local tracking branch",
        )
    }

    @Test
    fun `checkout accepts a slashed remote-style name like origin-slash-feature-slash-foo`(
        @TempDir tmp: File,
    ) = runTest {
        // Stripping `origin/` from `origin/feature/foo` yields `feature/foo`,
        // which IS legal as a branch name and must continue to work. This pins
        // that the fix rejects `-`-prefixed strips but not every stripped name.
        val dir = repo(tmp)
        git(dir, "branch", "feature/foo")
        val cloneDir = File(tmp, "clone").apply { mkdirs() }
        git(tmp, "clone", "-q", dir.absolutePath, cloneDir.absolutePath)
        git(cloneDir, "config", "user.email", "t@example.com")
        git(cloneDir, "config", "user.name", "Test")
        val p = provider(cloneDir)

        val result = p.checkout("origin/feature/foo")

        assertTrue(result is GitOperationResultData.Success, "checkout of origin/feature/foo failed: $result")
        assertEquals(
            "feature/foo",
            git(cloneDir, "rev-parse", "--abbrev-ref", "HEAD").trim(),
            "the stripped name should have been checked out verbatim",
        )
    }

    @Test
    fun `a refused hostile checkout does not lose uncommitted work`(
        @TempDir tmp: File,
    ) = runTest {
        // The whole point of #919: an unsafe checkout that PASSES through to
        // git would `git checkout -f -- ...` and silently overwrite the
        // working tree. Pin that an unsafe POST-STRIP name cannot reach git by
        // checking the working tree is untouched afterwards.
        val dir = repo(tmp)
        val tracked = File(dir, "tracked.txt")
        tracked.writeText("dirty-edits-the-user-cannot-afford-to-lose\n")
        assertTrue(
            git(dir, "status", "--porcelain=v1", "--", "tracked.txt").startsWith(" M"),
            "precondition: tracked.txt is dirty in the working tree",
        )
        val p = provider(dir)

        val result = p.checkout("origin/-f")

        assertTrue(result !is GitOperationResultData.Success, "checkout accepted origin/-f: $result")
        assertEquals(
            "dirty-edits-the-user-cannot-afford-to-lose\n",
            tracked.readText(),
            "the refused checkout must not have written anything to the working tree",
        )
        assertTrue(
            git(dir, "status", "--porcelain=v1", "--", "tracked.txt").startsWith(" M"),
            "the working tree must still report the modification",
        )
    }
}
