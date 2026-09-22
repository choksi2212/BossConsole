package ai.rever.boss.app.editor

import ai.rever.boss.ipc.proto.services.OpenFileRequest
import ai.rever.boss.plugin.language.LanguageIds
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * BossConsole#75: this used to be a hand-maintained table independent of
 * `composeApp`'s `EditorLanguages`, and the two disagreed on `.sh`/`.bash`/`.zsh`
 * (`shell` here, `bash` there). Both now read the shared `LanguageIds` table -
 * `detectLanguage("sh")` returning `bash` rather than `shell` is the actual bug this
 * consolidation fixes, not just a refactor with no observable effect.
 */
class EditorServiceImplTest {
    private val service = EditorServiceImpl()

    @Test
    fun `shell extensions now agree with the shared table, not the old local one`() {
        assertEquals("bash", service.detectLanguage("sh"))
        assertEquals("bash", service.detectLanguage("bash"))
        assertEquals("bash", service.detectLanguage("zsh"))
    }

    @Test
    fun `previously working mappings are unchanged`() {
        assertEquals("kotlin", service.detectLanguage("kt"))
        assertEquals("kotlin", service.detectLanguage("kts"))
        assertEquals("java", service.detectLanguage("java"))
        assertEquals("python", service.detectLanguage("py"))
        assertEquals("javascript", service.detectLanguage("js"))
        assertEquals("go", service.detectLanguage("go"))
        assertEquals("rust", service.detectLanguage("rs"))
        assertEquals("yaml", service.detectLanguage("yaml"))
        assertEquals("json", service.detectLanguage("json"))
    }

    @Test
    fun `proto keeps its local mapping - the shared table has no id for it`() {
        // LanguageIds is shared with boss-file-types.json's default-app extension
        // list; adding "proto" there is a separate change, so it stays a local
        // addition on top of the shared table rather than migrated into it.
        assertEquals("protobuf", service.detectLanguage("proto"))
    }

    @Test
    fun `an unrecognised extension is plaintext, not the shared table's text`() {
        // EditorServiceImpl's own default was always "plaintext", distinct from
        // EditorLanguages' "text" - preserved deliberately, since this is this
        // service's own gRPC contract, not a value composeApp reads.
        assertEquals("plaintext", service.detectLanguage("notarealextension"))
    }

    @Test
    fun `newly available ids the old local table never had`() {
        // Gained for free by reading the shared table instead of a copy that only
        // ever knew ~24 extensions.
        assertEquals("fortran", service.detectLanguage("f90"))
        assertEquals("clojure", service.detectLanguage("clj"))
        assertEquals("batch", service.detectLanguage("bat"))
        assertEquals("diff", service.detectLanguage("diff"))
    }

    @Test
    fun `every shared extension agrees with the service`() {
        LanguageIds.extensions().forEach { (extension, language) ->
            assertEquals(language, service.detectLanguage(extension), extension)
        }
    }

    @Test
    fun `opening a shell file returns the shared language through the RPC response`() =
        runBlocking {
            // Place the file under user.home so the path gate accepts it. The system
            // temp directory is OUTSIDE user.home on POSIX (under /var/folders/... on
            // macOS, /tmp on Linux), and the test JVM's redirected user.home is the
            // test runner's build dir, not the system temp.
            val parent = File(System.getProperty("user.home")).toPath()
            val file = Files.createTempFile(parent, "boss-language-", ".sh").toFile()
            try {
                file.writeText("echo hello\n")
                val response = service.openFile(OpenFileRequest.newBuilder().setPath(file.absolutePath).build())
                assertTrue(response.success)
                assertEquals("bash", response.language)
                assertEquals("echo hello\n", response.content)
            } finally {
                file.delete()
            }
        }

    @Test
    fun `opening named files uses shared filename precedence and keeps service defaults`() =
        runBlocking {
            val parent = File(System.getProperty("user.home")).toPath()
            val directory = Files.createTempDirectory(parent, "boss-language-names-").toFile()
            val cases =
                mapOf(
                    "Dockerfile" to "dockerfile",
                    "Containerfile" to "dockerfile",
                    "Makefile" to "makefile",
                    "GNUmakefile" to "makefile",
                    "Gemfile" to "ruby",
                    "Rakefile" to "ruby",
                    "Dockerfile.dev" to "dockerfile",
                    "Dockerfile.sh" to "dockerfile",
                    ".env.local" to "properties",
                    "service.proto" to "protobuf",
                    "notes.unknown" to "plaintext",
                    "Gemfile.lock" to "plaintext",
                )
            try {
                cases.forEach { (name, expected) ->
                    val file = directory.resolve(name).apply { writeText("content") }
                    val response = service.openFile(OpenFileRequest.newBuilder().setPath(file.absolutePath).build())
                    assertTrue(response.success, name)
                    assertEquals(expected, response.language, name)
                }
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun `extension lookup is case-insensitive`() {
        assertEquals("kotlin", service.detectLanguage("KT"))
    }
}

/**
 * BossConsole#885: the out-of-process `saveFile` tore user files on crash (in-place
 * `writeText` truncates before write) and its path gate missed Windows system paths
 * and symlink escapes (a POSIX-only prefix blocklist on the raw string, never
 * canonicalized).
 *
 * The atomic write is exercised directly so a failure mid-write can be triggered
 * without simulating a kill; the path gate covers all three failure modes the issue
 * calls out (system paths, `..` traversal, symlink escapes).
 */
class EditorServiceSecurityTest {
    private val service = EditorServiceImpl()
    private val tempDirs = mutableListOf<File>()

    private fun tempDir(prefix: String = "boss-editor-sec-"): File {
        // Use the test JVM's redirected user.home as the parent so the path is
        // unambiguously inside the home boundary on every platform. The system temp
        // directory lives under user.home on Windows but at /tmp or /var/folders/... on
        // POSIX, which would let an outside-path test look inside on Windows and an
        // inside-path test look outside on Linux/macOS.
        val parent = File(System.getProperty("user.home"))
        return Files
            .createTempDirectory(parent.toPath(), prefix)
            .toFile()
            .also { tempDirs.add(it) }
    }

    /** Creates a symlink if the platform allows it; null otherwise. */
    private fun symlink(
        link: File,
        target: File,
    ): File? =
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
            link
        } catch (_: Exception) {
            null
        }

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    // -------- atomic write --------

    @Test
    fun `atomic write replaces the target and leaves no temp file behind`() {
        val dir = tempDir()
        val target = File(dir, "notes.txt").apply { writeText("before") }

        service.atomicWriteText(target.absolutePath, "after", Charsets.UTF_8)

        assertEquals("after", target.readText())
        val tmps = dir.listFiles { f -> f.name.endsWith(".tmp") }
        assertTrue(tmps?.isEmpty() != false, "no .tmp files should remain after a successful atomic write")
    }

    @Test
    fun `a failed atomic write leaves the original file intact`() {
        // The cleanest reproducible failure for the atomic write: target the parent
        // directory itself, which `writeString` rejects with an IOException because it
        // is a directory and not a regular file. The previous file at a sibling path
        // must survive.
        val dir = tempDir()
        val survivor = File(dir, "survivor.txt").apply { writeText("original") }

        assertFailsWith<Exception> {
            service.atomicWriteText(dir.absolutePath, "anything", Charsets.UTF_8)
        }

        assertEquals("original", survivor.readText())
        // No .tmp should be left in the sibling either.
        val tmps = dir.listFiles { f -> f.name.endsWith(".tmp") } ?: emptyArray()
        assertTrue(tmps.isEmpty(), "no .tmp files should remain after a failed atomic write")
    }

    // -------- path gate --------

    @Test
    fun `a dot-dot path is refused before any canonical check`() {
        assertFailsWith<IllegalArgumentException> {
            service.validatePath("..\\Windows\\System32\\config\\SAM")
        }
        assertFailsWith<IllegalArgumentException> {
            service.validatePath("/etc/../../../etc/passwd")
        }
    }

    @Test
    fun `posix system paths are refused`() {
        assertFailsWith<IllegalArgumentException> {
            service.validatePath("/etc/passwd")
        }
        assertFailsWith<IllegalArgumentException> {
            service.validatePath("/sys/kernel/something")
        }
        assertFailsWith<IllegalArgumentException> {
            service.validatePath("/proc/cpuinfo")
        }
    }

    @Test
    fun `windows system paths are refused regardless of drive letter case`() {
        if (!System.getProperty("os.name").lowercase().contains("windows")) return
        assertFailsWith<IllegalArgumentException> {
            service.validatePath("C:\\Windows\\System32\\drivers\\etc\\hosts")
        }
        assertFailsWith<IllegalArgumentException> {
            service.validatePath("c:\\program files\\app\\evil.exe")
        }
        assertFailsWith<IllegalArgumentException> {
            service.validatePath("C:\\Program Files (x86)\\vendor\\bin\\tool.exe")
        }
        assertFailsWith<IllegalArgumentException> {
            service.validatePath("C:\\System Volume Information\\something")
        }
    }

    /**
     * Builds a writable file whose canonical path resolves OUTSIDE the user's home
     * directory. Returns `null` when the platform refuses to give us one - the test
     * then skips, with the symmetric "inside" test still pinning the happy path.
     */
    private fun outsideHomeFile(): File? {
        val outside =
            if (System.getProperty("os.name").lowercase().contains("windows")) {
                File("C:\\Users\\Public").resolve("boss-editor-outside-${System.nanoTime()}")
            } else {
                File("/").resolve("boss-editor-outside-${System.nanoTime()}")
            }
        val created =
            try {
                outside.mkdirs()
            } catch (_: Exception) {
                false
            }
        if (!created || !outside.canWrite()) return null
        tempDirs.add(outside)
        val target = File(outside, "secret.txt")
        return try {
            target.writeText("target content")
            target
        } catch (_: Exception) {
            null
        }
    }

    @Test
    fun `a symlink inside user home pointing outside is refused once the resolved path is checked`() {
        val inside = tempDir("boss-editor-inside-")
        val target = outsideHomeFile() ?: return
        val link = symlink(File(inside, "alias.txt"), target) ?: return

        // The raw path lives under the test runner's home, but canonicalPath() resolves
        // the symlink first - so the gate sees the outside target and refuses. This is
        // the regression #885 calls out: a workspace symlink to anywhere outside the
        // user's home must not slip past validation just because the link name is local.
        assertFailsWith<IllegalArgumentException> {
            service.validatePath(link.absolutePath)
        }
    }

    /**
     * The exact regression case from kshivang's review of #1404: a path that *names*
     * a directory under user.home, but whose DIRECTORY is itself a symlink to
     * somewhere outside home. The previous gate checked only the raw string and let
     * this through; canonicalPath must follow the parent symlink so the gate sees the
     * real resolved parent and refuses.
     */
    @Test
    fun `a symlinked parent directory pointing outside is refused by the resolved path`() {
        val inside = tempDir("boss-editor-inside-")
        val target = outsideHomeFile() ?: return
        // Make a directory inside user.home that itself is a symlink to the
        // outside directory. The user's file at "$inside/link/secret.txt"
        // resolves to "$outside/secret.txt".
        val link = symlink(File(inside, "link"), target.parentFile) ?: return
        val pathInsideLink = File(link, "secret.txt")

        assertFailsWith<IllegalArgumentException> {
            service.validatePath(pathInsideLink.absolutePath)
        }
    }

    @Test
    fun `a symlink inside user home pointing inside is accepted`() {
        val dir = tempDir()
        val real = File(dir, "real.txt").apply { writeText("mine") }
        val alias = symlink(File(dir, "alias.txt"), real) ?: return

        // No throw; canonical path lands inside user.home.
        service.validatePath(alias.absolutePath)
    }

    @Test
    fun `a path outside user home is refused by the resolved path`() {
        // user.home for the test JVM is the test runner's home. Pick a path that is
        // almost certainly outside it (filesystem root on POSIX, drive root on Windows).
        val outside =
            if (System.getProperty("os.name").lowercase().contains("windows")) {
                File("C:\\__boss_editor_test_outside__\\file.txt")
            } else {
                File("/__boss_editor_test_outside__/file.txt")
            }
        assertFailsWith<IllegalArgumentException> {
            service.validatePath(outside.absolutePath)
        }
    }

    @Test
    fun `a path inside user home is accepted`() {
        val dir = tempDir()
        val target = File(dir, "in-home.txt")
        // No throw; canonical path lands inside user.home.
        service.validatePath(target.absolutePath)
    }

    /**
     * `saveFile` must refuse the resolved target: a save through a symlink that
     * points outside home is the writing half of #885, and the previous
     * implementation accepted the request because it called `validatePath` for
     * the gate but then wrote through the *raw* path string.
     */
    @Test
    fun `saveFile refuses to write through a symlink that resolves outside home`() =
        runBlocking {
            val inside = tempDir("boss-editor-inside-")
            val target = outsideHomeFile() ?: return@runBlocking
            val link = symlink(File(inside, "alias.txt"), target) ?: return@runBlocking

            service.saveFile(
                ai.rever.boss.ipc.proto.services.SaveFileRequest
                    .newBuilder()
                    .setPath(link.absolutePath)
                    .setContent("tampered")
                    .build(),
            )

            assertEquals("target content", target.readText())
        }
}
