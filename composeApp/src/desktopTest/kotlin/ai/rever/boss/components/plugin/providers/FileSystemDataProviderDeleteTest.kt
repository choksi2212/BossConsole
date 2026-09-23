package ai.rever.boss.components.plugin.providers

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
    fun `delete refuses a path that traverses a link with parent-dot even when the OS would resolve it outside home`() {
        // Pins the review finding on the #1118 PR: a request like `home/link/../<sibling>` has
        // an OS-resolved target that lives outside home (the link points to `outside`, so
        // `outside/..` is `outside`'s parent, not `home`), but the lexically-normalized form
        // cancels `link/..` back to home and LOOKS in scope. The containment check and the
        // walk have to resolve paths the same way, or the walk escapes even when the check
        // sees a benign-looking path.
        //
        // Shape: a temp root, a `home` directory under it, a symlink `home/link -> outside`
        // (also under root), a canary in a sibling of `outside` (so `home/link/../<canary>`
        // OS-resolves to that canary), and a request to delete it. The canary must remain and
        // the call must be refused.
        val root = createTempDirectory("filesystem-provider-linkdot").toFile()
        try {
            val home = File(root, "home").apply { mkdirs() }
            val outside = File(root, "outside").apply { mkdirs() }
            val canaryName = "fsd-linkdot-canary"
            // The canary lives in `outside`'s parent, which is exactly where `home/link/..` lands.
            val siblingCanary = File(root, canaryName).apply { writeText("keep") }
            val link = File(home, "link")
            if (runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isFailure) return

            // The traversal path: `home/link/../<canary>` - the OS walks `link` (the symlink),
            // then `..` from the symlink's TARGET (NOT from `home`), landing at `outside`'s
            // parent (= `root`), then at `<canary>`. Without the fix the walk used the
            // unnormalized input and would reach this canary file.
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
