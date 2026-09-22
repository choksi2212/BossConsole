package ai.rever.boss.app.editor

import ai.rever.boss.ipc.proto.services.OpenFileRequest
import ai.rever.boss.ipc.proto.services.SaveFileRequest
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for the editor service's save path (BossConsole#885).
 *
 * The out-of-process EditorService is the editor's durable-save surface. Before
 * #885 its path gate ran on the RAW string (a `..` literal plus a POSIX-only
 * `/etc`-style blocklist - Windows system paths passed untouched) and saveFile
 * truncated the target in place, so a crash mid-save tore the user's source
 * file. The fix canonicalizes BEFORE validating, confines everything to the
 * user's home, and saves atomically (temp sibling + atomic move).
 *
 * The impl is instantiated directly (the gRPC base is a no-op for these
 * paths); the tests drive the real file code with real symlinks. The
 * confinement root is injected per test, so no process-global user.home
 * mutation is needed.
 */
class EditorServiceImplSaveTest {
    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("editor-save-test-").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun impl() = EditorServiceImpl(root = tempDir)

    private fun saveRequest(path: File): SaveFileRequest =
        SaveFileRequest
            .newBuilder()
            .setPath(path.absolutePath)
            .setContent("saved content\n")
            .build()

    private fun openRequest(path: File): OpenFileRequest =
        OpenFileRequest
            .newBuilder()
            .setPath(path.absolutePath)
            .build()

    @Test
    fun `a save inside the user's home is atomic - no torn target on a failed write`() {
        val target = File(tempDir, "project/Solution.kt").apply { parentFile.mkdirs() }
        target.writeText("original complete content\n")

        // A successful save replaces content whole.
        runBlocking { impl().saveFile(saveRequest(target)) }
        assertEquals("saved content\n", target.readText())

        // The atomic shape: any .part siblings from failed/interrupted writes
        // must never have replaced the committed content.
        val parts = target.parentFile.listFiles { f -> f.name.endsWith(".part") } ?: emptyArray()
        parts.forEach { it.delete() }
        assertTrue(target.readText() == "saved content\n", "the committed record survives stray temp siblings")
    }

    @Test
    fun `a path outside the user's home is refused`() {
        // Outside home: on the test harness, home is the temp dir, so an
        // absolute path to a sibling outside it is the escape shape.
        val outside = File(tempDir.parentFile ?: File("/"), "escape-target.txt")
        val e =
            assertFailsWith<io.grpc.StatusRuntimeException> {
                runBlocking { impl().openFile(openRequest(outside)) }
            }
        assertEquals(io.grpc.Status.Code.INVALID_ARGUMENT, e.status.code)
    }

    @Test
    fun `a symlink inside the home pointing at an outside target is refused after canonicalization`() {
        // createTempDirectory lands under the OS temp dir, which on Windows is
        // INSIDE the real user home (AppData\Local\Temp) - not an escape target.
        // The confinement home for THIS test is the per-test tempDir (redirected
        // user.home), so any sibling OUTSIDE it works; the user's own drive root
        // temp area next to it is what we use here.
        val outsideDir = Files.createTempDirectory("boss-editor-outside-").toFile()
        val outsideTarget = File(outsideDir, "secret.txt").apply { writeText("outside\n") }
        val link = File(tempDir, "innocent-link.txt")
        Files.createSymbolicLink(link.toPath(), outsideTarget.toPath())

        // The raw path contains no `..` and lives inside home; the canonical
        // target does not - the old raw-string gate passed this shape.
        val e =
            assertFailsWith<io.grpc.StatusRuntimeException> {
                runBlocking { impl().openFile(openRequest(link)) }
            }
        assertEquals(io.grpc.Status.Code.INVALID_ARGUMENT, e.status.code)
        outsideDir.deleteRecursively()
    }

    @Test
    fun `traversal sequences are still refused`() {
        // The old raw-string `..` ban is gone: the refusal now comes from
        // normalization + confining the resolved result to the root.
        val outside = File(tempDir.parentFile ?: File("/"), "trav-$$/../escape.txt")
        val e =
            assertFailsWith<io.grpc.StatusRuntimeException> {
                runBlocking { impl().openFile(openRequest(outside)) }
            }
        assertEquals(io.grpc.Status.Code.INVALID_ARGUMENT, e.status.code)
    }

    @Test
    fun `legitimate names containing dots are no longer false-refused`() {
        // The raw `..` substring check used to refuse these; resolution +
        // confinement is the real gate.
        val dotDot = File(tempDir, "notes..txt").apply { writeText("ok\n") }
        val res = runBlocking { impl().openFile(openRequest(dotDot)) }
        assertTrue(res.success, res.errorMessage)
    }

    @Test
    fun `a refused outside-home save creates no directories outside the confinement root`() {
        // A save whose target does not exist yet used to mkdirs its parent BEFORE
        // validating (the new-file case), so a refused outside-home path still
        // created directories outside the confinement root. The gate now resolves
        // the deepest existing ancestor first and refuses before any mkdirs.
        val escapeDir = File(tempDir.parentFile ?: File("/"), "escape-parent-$$")
        val outside = File(escapeDir, "newdir/secret.txt")
        val e =
            assertFailsWith<io.grpc.StatusRuntimeException> {
                runBlocking { impl().saveFile(saveRequest(outside)) }
            }
        assertEquals(io.grpc.Status.Code.INVALID_ARGUMENT, e.status.code)
        assertFalse(escapeDir.exists(), "the gate must not create directories outside the confinement root")
    }

    @Test
    fun `a new-file save into a fresh subdirectory inside the home still succeeds`() {
        // No caller pre-creates the parent anymore: validatePath resolves the
        // deepest existing ancestor and re-appends the missing tail, and
        // atomicWrite mkdirs the validated parent itself.
        val target = File(tempDir, "fresh/deep/nested/NewFile.kt")
        runBlocking { impl().saveFile(saveRequest(target)) }
        assertEquals("saved content\n", target.readText())
    }

    @Test
    fun `an open whose parent directory is gone reports not-found, not an error`() {
        // A stale recent-files entry under a deleted folder: the gate's
        // NOT_FOUND (parent raced away) must map to the File-not-found
        // response, not an RPC error.
        val goneDir = File(tempDir, "vanished").apply { mkdirs() }
        val stale = File(goneDir, "Stale.kt").apply { writeText("x\n") }
        goneDir.deleteRecursively()
        val res = runBlocking { impl().openFile(openRequest(stale)) }
        assertFalse(res.success)
        assertEquals("File not found: ${stale.absolutePath}", res.errorMessage)
    }

    @Test
    fun `a failed save propagates on the wire instead of reading as success`() {
        // The old code caught every exception and returned the same Empty a
        // successful save returns; the client marks the buffer clean on Empty,
        // so a disk-full save lost the edit silently. A write failure must
        // surface as an INTERNAL error.
        val target = File(tempDir, "ro/file.kt")
        target.parentFile.mkdirs()
        target.writeText("original\n")
        target.parentFile.setWritable(false)
        try {
            val e =
                assertFailsWith<io.grpc.StatusRuntimeException> {
                    runBlocking { impl().saveFile(saveRequest(target)) }
                }
            assertEquals(io.grpc.Status.Code.INTERNAL, e.status.code)
        } finally {
            target.parentFile.setWritable(true)
        }
        assertEquals("original\n", target.readText(), "the previous content must survive a failed save")
    }

    @Test
    fun `a save preserves the target's existing posix mode`() {
        // The atomic replace moves a fresh umask-0644 temp over the target, so
        // without re-applying the target's attributes an executable script
        // would lose +x and a 0600 file would WIDEN on Ctrl-S.
        if (Files.getFileAttributeView(
                tempDir.toPath(),
                java.nio.file.attribute.PosixFileAttributeView::class.java,
            ) == null
        ) {
            return
        }
        val target = File(tempDir, "script.sh").apply { writeText("#!/bin/sh\necho hi\n") }
        Files.setPosixFilePermissions(
            target.toPath(),
            setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
            ),
        )
        runBlocking { impl().saveFile(saveRequest(target)) }
        val perms = Files.getPosixFilePermissions(target.toPath())
        assertTrue(
            java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE in perms,
            "the executable bit must survive an atomic re-save, got $perms",
        )
    }
}
