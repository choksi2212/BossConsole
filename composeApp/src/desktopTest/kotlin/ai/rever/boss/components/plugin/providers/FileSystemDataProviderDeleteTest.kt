package ai.rever.boss.components.plugin.providers

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assumeFalse

class FileSystemDataProviderDeleteTest {
    @Test
    fun `delete refuses the user home directory itself`() {
        val home = createTempDirectory("filesystem-provider-home").toFile()
        try {
            val canary = File(home, "keep.txt").apply { writeText("keep") }

            val result = deleteUserPath(home, home)

            assertIs<SecurityException>(result.exceptionOrNull())
            assertEquals("keep", canary.readText())
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `recursive delete removes an ordinary descendant tree`() {
        val home = createTempDirectory("filesystem-provider-home").toFile()
        try {
            val target = File(home, "workspace").apply { mkdirs() }
            File(target, "nested/file.txt").apply {
                parentFile.mkdirs()
                writeText("delete")
            }

            assertTrue(deleteUserPath(target, home).isSuccess)
            assertFalse(target.exists())
            assertTrue(home.isDirectory)
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `recursive delete removes a nested symlink without touching its target`() {
        val root = createTempDirectory("filesystem-provider-symlink").toFile()
        try {
            val home = File(root, "home").apply { mkdirs() }
            val target = File(home, "workspace").apply { mkdirs() }
            val outside = File(root, "outside").apply { mkdirs() }
            val canary = File(outside, "keep.txt").apply { writeText("keep") }
            val link = File(target, "external")
            if (runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isFailure) return

            assertTrue(deleteUserPath(target, home).isSuccess)
            assertFalse(target.exists())
            assertEquals("keep", canary.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `link-then-dotdot traversal preserves the safety invariant on every platform`() {
        // Pins the review finding on the #1118 PR: a request like `home/link/../<sibling>` has
        // an OS-resolved target that lives outside home (the link points to `outside`, so
        // `outside/..` is `outside`'s parent, not `home`), but the lexically-normalized form
        // cancels `link/..` back to home and LOOKS in scope. The containment check and the
        // walk have to resolve paths the same way, or the walk escapes even when the check
        // sees a benign-looking path.
        //
        // Shape: a temp root, a `home` directory under it, a symlink `home/link -> outside`
        // (also under root), and a canary in a sibling of `outside` (so `home/link/../<canary>`
        // OS-resolves to that canary on POSIX).
        //
        // The safety property the test pins is the same on every platform: the canary
        // outside home survives. The exact failure mode differs:
        //   - POSIX: the OS walks `link` first, so `link/..` lands at `outside`'s parent
        //     (`root`), the containment check sees an escape, and the call refuses with
        //     SecurityException.
        //   - Windows: `..` is resolved lexically BEFORE the link is followed, so the
        //     traversal path canonicalizes to `home/canary` (which doesn't exist on disk).
        //     The containment check sees something inside home and admits it; the walk then
        //     fails because the file is absent. Either way the canary is untouched.
        val root = createTempDirectory("filesystem-provider-linkdot").toFile()
        try {
            val home = File(root, "home").apply { mkdirs() }
            val outside = File(root, "outside").apply { mkdirs() }
            val canaryName = "fsd-linkdot-canary"
            // The canary lives in `outside`'s parent, which is exactly where `home/link/..`
            // OS-lands on POSIX.
            val siblingCanary = File(root, canaryName).apply { writeText("keep") }
            val link = File(home, "link")
            if (runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isFailure) return

            // The traversal path: `home/link/../<canary>` - on POSIX the OS walks `link`
            // (the symlink) then `..` from the symlink's TARGET (NOT from `home`), landing
            // at `outside`'s parent (= `root`), then at `<canary>`. On Windows the `..` is
            // resolved lexically first and the path becomes `home/canary` instead.
            val traversalPath = File(home, "link/../$canaryName")

            // Exercise the API; the specific Result outcome is platform-specific.
            deleteUserPath(traversalPath, home)

            // Safety property 1: the canary outside home survives untouched.
            assertTrue(
                siblingCanary.exists(),
                "canary at $siblingCanary must NOT be erased by a link-then-dotdot traversal",
            )
            assertEquals(
                "keep",
                siblingCanary.readText(),
                "canary at $siblingCanary must NOT be erased by a link-then-dotdot traversal",
            )
            // Safety property 2: nothing under `outside` was touched either - that's
            // where a walk that escaped home would land.
            assertTrue(
                outside.isDirectory,
                "the outside directory must remain intact after a link-then-dotdot traversal",
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `link-then-dotdot traversal is refused on POSIX`() {
        // The "the walk must refuse with SecurityException" outcome is POSIX-specific:
        // the OS-resolved `link/..` lands at `outside`'s parent, the containment check
        // sees the escape and throws. Windows lexically normalizes `link/..` to home
        // before following the link, so the call takes a different path there - the
        // safety property test above is the assertion that holds on every platform.
        assumeFalse(
            System.getProperty("os.name").lowercase().contains("windows"),
            "POSIX-only refusal assertion; the safety property test covers Windows",
        )
        val root = createTempDirectory("filesystem-provider-linkdot").toFile()
        try {
            val home = File(root, "home").apply { mkdirs() }
            val outside = File(root, "outside").apply { mkdirs() }
            val canaryName = "fsd-linkdot-canary"
            val siblingCanary = File(root, canaryName).apply { writeText("keep") }
            val link = File(home, "link")
            if (runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isFailure) return

            val traversalPath = File(home, "link/../$canaryName")

            val result = deleteUserPath(traversalPath, home)

            assertTrue(
                result.isFailure,
                "link-then-dotdot traversal that escapes home must be refused; got $result",
            )
            assertIs<SecurityException>(result.exceptionOrNull())
            assertEquals(
                "keep",
                siblingCanary.readText(),
                "canary at $siblingCanary must NOT be erased when the link-then-dotdot traversal is refused",
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
