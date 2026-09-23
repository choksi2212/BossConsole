package ai.rever.boss.utils

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the atomic file-replacement helpers.
 *
 * These exist because of a platform split that hides itself during development: `File.renameTo`
 * replaces an existing destination on macOS and Linux (POSIX `rename(2)`) but fails on Windows
 * (`MoveFile` returns `ERROR_ALREADY_EXISTS`). The browser's favicon cache open-coded that call, so
 * on Windows it wrote each icon exactly once and every later save failed — and because the cache
 * survives restarts, that meant favicons stopped updating entirely.
 *
 * The overwrite tests below therefore only *fail* on Windows. They are worth keeping anyway: PR CI
 * runs `build-test (windows-latest)`, which is precisely the leg that would have caught it.
 */
class AtomicFileWriteTest {
    private val tempDir: File =
        File.createTempFile("atomic-write-", "").let {
            it.delete()
            it.mkdirs()
            it
        }

    @AfterTest
    fun cleanUp() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `atomicMoveFrom replaces a file that already exists`() {
        // The regression. On Windows this threw before the fix; on POSIX it always passed.
        val target = File(tempDir, "icon.png").apply { writeText("old") }
        val temp = File(tempDir, "icon.tmp").apply { writeText("new") }

        target.atomicMoveFrom(temp)

        assertEquals("new", target.readText())
        assertFalse(temp.exists(), "the source should have been moved, not copied")
    }

    @Test
    fun `atomicMoveFrom creates the file when it does not exist`() {
        val target = File(tempDir, "fresh.png")
        val temp = File(tempDir, "fresh.tmp").apply { writeText("content") }

        target.atomicMoveFrom(temp)

        assertEquals("content", target.readText())
    }

    @Test
    fun `atomicWriteText overwrites existing content`() {
        val target = File(tempDir, "registry.json")

        target.atomicWriteText("first")
        assertEquals("first", target.readText())

        target.atomicWriteText("second")
        assertEquals("second", target.readText())
    }

    @Test
    fun `atomicWriteText creates missing parent directories`() {
        val target = File(tempDir, "nested/deeper/registry.json")

        target.atomicWriteText("value")

        assertEquals("value", target.readText())
    }

    @Test
    fun `atomicWriteText leaves no temp files behind`() {
        // The temp file is a sibling of the target, so a leak would accumulate in the real cache
        // and config directories rather than in the OS temp dir.
        val target = File(tempDir, "clean.json")

        repeat(3) { target.atomicWriteText("write $it") }

        val strays = tempDir.listFiles()?.filter { it.name != target.name }.orEmpty()
        assertTrue(strays.isEmpty(), "unexpected leftovers: ${strays.map { it.name }}")
    }

    @Test
    fun `atomicWriteText restricts file permissions to owner only on posix filesystems`() {
        val target = File(tempDir, "owner-only.json")
        target.atomicWriteText("sensitive-content")

        assertEquals("sensitive-content", target.readText())

        val path = target.toPath()
        if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) == null) {
            return
        }

        val expected = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        val actual = Files.getPosixFilePermissions(path)
        assertEquals(expected, actual, "File permissions must be owner-only (0600), got: $actual")
    }

    @Test
    fun `atomicWriteText preserves owner only permissions across overwrites`() {
        val target = File(tempDir, "owner-only-overwrite.json")
        target.atomicWriteText("initial-content")
        target.atomicWriteText("updated-content")

        assertEquals("updated-content", target.readText())

        val path = target.toPath()
        if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) == null) {
            return
        }

        val expected = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        val actual = Files.getPosixFilePermissions(path)
        assertEquals(expected, actual, "File permissions must remain owner-only (0600) on overwrite, got: $actual")
    }

    @Test
    fun `atomicWriteTextPreserving keeps the executable bit across an editor save`() {
        if (Files.getFileAttributeView(tempDir.toPath(), PosixFileAttributeView::class.java) == null) return
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
        val target = File(tempDir, "script.sh").apply { writeText("#!/bin/sh\necho original\n") }
        Files.setPosixFilePermissions(target.toPath(), executable)
        assertEquals(executable, Files.getPosixFilePermissions(target.toPath()))

        target.atomicWriteTextPreserving("#!/bin/sh\necho updated\n")

        assertEquals("#!/bin/sh\necho updated\n", target.readText())
        assertEquals(
            executable,
            Files.getPosixFilePermissions(target.toPath()),
            "the executable bit (and the rest of the mode) must survive an atomic replace",
        )
    }

    @Test
    fun `atomicWriteTextPreserving refuses a symlink at the target path`() {
        val targetDir = File(tempDir, "real-dir").apply { mkdirs() }
        val targetCanary = File(targetDir, "untouched.txt").apply { writeText("keep") }
        val link = File(tempDir, "link")
        if (runCatching { Files.createSymbolicLink(link.toPath(), targetDir.toPath()) }.isFailure) return

        val ex = runCatching { link.atomicWriteTextPreserving("replacement") }
        assertTrue(
            ex.isFailure,
            "atomicWriteTextPreserving on a symlink must refuse rather than walk the target",
        )
        assertTrue(Files.isSymbolicLink(link.toPath()), "the symlink must still be there after the refusal")
        assertTrue(targetCanary.exists(), "the canary inside the target must NOT be touched")
        assertEquals("keep", targetCanary.readText())
    }

    @Test
    fun `atomicWriteTextPreserving refuses a hard-linked file`() {
        if (Files.getFileAttributeView(tempDir.toPath(), PosixFileAttributeView::class.java) == null) return
        val source = File(tempDir, "source.txt").apply { writeText("original") }
        val link = File(tempDir, "link.txt")
        if (runCatching { Files.createLink(link.toPath(), source.toPath()) }.isFailure) return
        val nlink =
            runCatching { Files.getAttribute(source.toPath(), "unix:nlink") as? Long }.getOrNull() ?: 1L
        if (nlink < 2) return

        val ex = runCatching { source.atomicWriteTextPreserving("replacement") }
        assertTrue(ex.isFailure, "atomicWriteTextPreserving on a hard-linked file must refuse")
        assertEquals("original", source.readText(), "the source's contents must NOT change on a refused write")
        assertEquals("original", link.readText(), "the linked path's contents must NOT change on a refused write")
    }
}
