package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.FileReadResult
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The host's whole surviving file-I/O surface, exercised through the provider
 * the editor-tab plugin actually calls -- so the mapping onto the plugin-api
 * [FileReadResult] is covered too, not just the internal [FileReadOutcome].
 *
 * `editor_write_file` had no test, and stack-overflowed: the override called
 * itself, because Kotlin resolves a member of the implicit receiver before a
 * top-level function of the same name and signature. A round trip is all it
 * took to see it.
 */
class EditorFileIoTest {
    private val provider = EditorContentProviderImpl()

    private fun tempFile(name: String): File =
        File
            .createTempFile("editor-file-io-", "-$name")
            .also { it.deleteOnExit() }

    @Test
    fun `write then read round-trips`() {
        val file = tempFile("round-trip.txt")
        assertTrue(provider.writeFileContent(file.absolutePath, "hello editor"))
        val result = provider.readFileContent(file.absolutePath)
        assertIs<FileReadResult.Success>(result)
        assertEquals("hello editor", result.content)
    }

    @Test
    fun `write creates missing parent directories`() {
        val root = File(tempFile("parents.txt").parentFile, "editor-io-${System.nanoTime()}")
        val nested = File(root, "a/b/c.txt")
        assertTrue(provider.writeFileContent(nested.absolutePath, "nested"))
        assertTrue(nested.exists())
        root.deleteRecursively()
    }

    @Test
    fun `write preserves an existing file when a sibling temp file cannot be created`() {
        val root = Files.createTempDirectory("editor-file-io-readonly-").toFile()
        val target = File(root, "source.kt").also { it.writeText("last complete source") }
        if (Files.getFileAttributeView(root.toPath(), PosixFileAttributeView::class.java) == null) return
        try {
            Files.setPosixFilePermissions(
                target.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
            Files.setPosixFilePermissions(
                root.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE),
            )

            assertFalse(provider.writeFileContent(target.absolutePath, "replacement"))
            assertEquals("last complete source", target.readText())
        } finally {
            Files.setPosixFilePermissions(
                root.toPath(),
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
            root.deleteRecursively()
        }
    }

    @Test
    fun `read of an absent path is FileNotFound`() {
        val missing = File(tempFile("gone.txt").parentFile, "definitely-absent-${System.nanoTime()}")
        assertIs<FileReadResult.FileNotFound>(provider.readFileContent(missing.absolutePath))
    }

    @Test
    fun `read past maxSize is FileTooLarge rather than a load`() {
        val file = tempFile("large.txt")
        file.writeText("more than one byte")
        val result = provider.readFileContent(file.absolutePath, maxSize = 1)
        assertIs<FileReadResult.FileTooLarge>(result)
        assertEquals(file.length(), result.sizeBytes)
        assertEquals(1, result.maxSizeBytes)
    }

    @Test
    fun `write preserves an executable bit on POSIX`() {
        val file = tempFile("script.sh")
        if (Files.getFileAttributeView(file.toPath(), PosixFileAttributeView::class.java) == null) return
        val executable =
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE,
            )
        Files.setPosixFilePermissions(file.toPath(), executable)
        // Sanity: the script is now executable before the save.
        assertEquals(executable, Files.getPosixFilePermissions(file.toPath()))

        assertTrue(provider.writeFileContent(file.absolutePath, "#!/bin/sh\necho hi\n"))

        assertEquals(executable, Files.getPosixFilePermissions(file.toPath()), "executable bit must survive an editor save")
    }

    @Test
    fun `write refuses a symlink at the delete root without following it`() {
        val root = Files.createTempDirectory("editor-file-io-symlink-").toFile()
        try {
            val targetDir = File(root, "real-dir").apply { mkdirs() }
            val targetCanary = File(targetDir, "untouched.txt").apply { writeText("keep") }
            val link = File(root, "link")
            if (runCatching { Files.createSymbolicLink(link.toPath(), targetDir.toPath()) }.isFailure) return

            // The save refuses with IOException - the link is left in place, the target
            // tree is untouched. We use the editor write through the provider so the
            // refusal reaches the caller.
            val outcome = provider.writeFileContent(link.absolutePath, "replacement")
            assertFalse(outcome, "writing through a symlink at the target path must refuse")

            assertTrue(
                Files.isSymbolicLink(link.toPath()),
                "the symlink must still be there after a refused save",
            )
            assertTrue(
                targetCanary.exists(),
                "the canary inside the symlink target must NOT be touched",
            )
            assertEquals("keep", targetCanary.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `write refuses a hard-linked file`() {
        // Hard-linked files (POSIX nlink > 1) would split into two inodes if any
        // write replaced the path's inode: one link keeps the old content, the
        // edited path points at a fresh inode. The helper refuses rather than
        // produce a half-edited file the user did not intend.
        val root = Files.createTempDirectory("editor-file-io-hardlink-").toFile()
        try {
            val source = File(root, "source.txt").apply { writeText("original") }
            if (Files.getFileAttributeView(source.toPath(), PosixFileAttributeView::class.java) == null) return
            val link = File(root, "link.txt")
            if (runCatching { Files.createLink(link.toPath(), source.toPath()) }.isFailure) return

            // Sanity: link count is 2 (source + the new link).
            val nlink = runCatching {
                Files.getAttribute(source.toPath(), "unix:nlink") as? Long
            }.getOrNull() ?: 1L
            if (nlink < 2) return

            val outcome = provider.writeFileContent(source.absolutePath, "replacement")
            assertFalse(outcome, "writing a hard-linked file must refuse")
            assertEquals("original", source.readText(), "the source's contents must NOT change on a refused save")
            assertEquals("original", link.readText(), "the linked path's contents must NOT change on a refused save")
        } finally {
            root.deleteRecursively()
        }
    }
}
