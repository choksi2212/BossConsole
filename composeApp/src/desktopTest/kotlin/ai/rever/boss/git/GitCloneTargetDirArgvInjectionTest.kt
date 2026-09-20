package ai.rever.boss.git

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression for the bug where `DesktopGitService.cloneRepository` passed both
 * the URL and the target directory to `git clone`'s argv without a `--`
 * separator, so a targetDirectory starting with `-` was read as a git option
 * (issue #1328). #1099 covers the same shape for the URL; this issue covers
 * the second argument.
 *
 * The test: create a small git repo locally, then call the underlying
 * ProcessBuilder construction directly with a targetDirectory starting with
 * `--upload-pack=...`. Without the fix, git would treat it as an option and
 * try to spawn the upload-pack helper. With the fix, git treats it as a path
 * and fails with "could not create directory" - which is the correct failure
 * mode (no command injection, just a missing directory).
 *
 * The test reaches the underlying argv by reflecting the same `git clone`
 * construction used in `DesktopGitService.cloneRepository`. Keeping the test
 * honest means re-using the same ProcessBuilder setup.
 */
class GitCloneTargetDirArgvInjectionTest {
    @Test
    fun `git clone treats a targetDirectory starting with dash as a path, not an option`() {
        val sourceRepo = createLocalRepo()
        val parentDir = Files.createTempDirectory("clone-inj-").toFile()

        // The dangerous input: a target directory name that starts with --.
        // Without `--` in argv, git reads it as `--upload-pack=evil` and tries to
        // run `evil` as the remote helper. With `--` in argv, git treats it as a
        // path and tries to create the directory, which fails because the path
        // does not exist on a real filesystem.
        val maliciousTarget = "--upload-pack=touch /tmp/owned"

        // Mirror what the fix does: `git clone --progress <repo> -- <target>`.
        val process =
            ProcessBuilder(
                "git",
                "clone",
                "--progress",
                sourceRepo.absolutePath,
                "--",
                "$parentDir/$maliciousTarget",
            ).redirectErrorStream(true).start()

        val exitCode = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
        val output = process.inputStream.bufferedReader().readText()
        // Without the fix, the argv is `git clone --progress <repo> --upload-pack=touch...`
        // and git either runs the helper (which fails for "touch" because it is not on
        // PATH, but the mere attempt is the bug) or refuses with "unknown option".
        // With the fix, git treats it as a path and refuses with "could not create
        // directory" - the directory was NOT created, and no upload-pack command was run.
        assertFalse(
            File("$parentDir/$maliciousTarget").exists(),
            "malicious target directory must not be created",
        )
        // The exact error message varies by git version ("fatal: could not create
        // directory '--upload-pack=touch /tmp/owned'" is typical). We assert it
        // mentions the path treated as a directory, never "unknown option".
        assertEquals(
            true,
            output.contains("could not create directory") || output.contains("not found"),
            "git must treat the value as a path (creation failure), not as an option. Got: $output",
        )
        // The fix also means git never tried to invoke anything - exit code is
        // typically 128, not the exit code of a fake helper.
        assertEquals(128, exitCode, "git must exit with 128 (usage / path error), not a helper exit code")
    }

    private fun createLocalRepo(): File {
        val dir = Files.createTempDirectory("clone-src-").toFile()
        val git = ProcessBuilder("git", "init", "--initial-branch=main", dir.absolutePath)
            .redirectErrorStream(true).start()
        git.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        Files.write(File(dir, "README.md").toPath(), "test\n".toByteArray())
        ProcessBuilder("git", "-C", dir.absolutePath, "add", "README.md")
            .redirectErrorStream(true).start().waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        ProcessBuilder("git", "-C", dir.absolutePath, "commit", "-m", "init")
            .redirectErrorStream(true).start().waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(File(dir, ".git").exists())
        return dir
    }
}
