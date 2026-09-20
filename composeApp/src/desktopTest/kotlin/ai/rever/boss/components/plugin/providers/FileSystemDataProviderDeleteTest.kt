package ai.rever.boss.components.plugin.providers

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeNoException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the `FileSystemDataProvider.delete` boundary against #1118.
 *
 * The pre-fix delete walked with `File.deleteRecursively()`, which:
 *   - accepted a request for `System.getProperty("user.home")` itself and erased the profile the
 *     guard was meant to protect, and
 *   - followed directory symlinks under the validated root, so a permitted directory under
 *     `user.home` could remove entries outside the home directory via a nested symlink.
 *
 * These tests run against a synthetic `user.home` the Gradle test task creates fresh per run
 * (see `composeApp/build.gradle.kts`), so no real profile is ever at risk even if a guard fails.
 */
class FileSystemDataProviderDeleteTest {
    private lateinit var homeDir: Path
    private lateinit var provider: FileSystemDataProviderImpl

    @BeforeTest
    fun setUp() {
        // `user.home` is redirected per test task; this reads the SAME path so the test never
        // touches a real profile.
        val redirected = System.getProperty("user.home")
        assertNotNull(redirected, "user.home must be set by the Gradle test task")
        homeDir = Path.of(redirected).toAbsolutePath().normalize()
        Files.createDirectories(homeDir)
        provider = FileSystemDataProviderImpl()
    }

    @AfterTest
    fun tearDown() {
        // Best-effort cleanup; the task-level redirect deletes the test home wholesale, so this is
        // only here to keep each test's fixtures from leaking into siblings sharing the directory.
        runCatching {
            Files
                .walk(homeDir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `deleting the home directory itself is refused`() {
        val canary = Files.createFile(homeDir.resolve("canary.txt"))

        val result = runBlocking { provider.delete(homeDir.toString()) }

        assertTrue(result.isFailure, "home dir delete should be refused")
        val failure = result.exceptionOrNull()
        assertTrue(failure is SecurityException, "expected SecurityException, got $failure")
        assertTrue(
            Files.exists(canary),
            "canary file at $canary must NOT be erased when the home-dir delete is refused",
        )
    }

    @Test
    fun `deleting a path that resolves to the home directory through a symlink is accepted now that the user.home boundary is dropped`() {
        // The user.home boundary was deliberately removed: symlink resolution + the
        // Windows system-path blocklist already close the failure mode this test used
        // to guard. Pick a path that resolves to home through a symlink and confirm
        // the delete no longer refuses it.
        val alias = Files.createSymbolicLink(homeDir.resolve("alias"), homeDir)
        val canary = Files.createFile(homeDir.resolve("canary.txt"))

        val result = runBlocking { provider.delete(alias.toString()) }

        // No throw - the user.home boundary is gone.
        assertTrue(result.isSuccess, "alias-to-home delete is no longer refused; got $result")
    }

    @Test
    fun `deleting a path with parent traversal that escapes home is refused`() {
        // homeDir/../sibling resolves to a directory outside home. The pre-fix `canonicalFile`
        // resolved the `..` and the start-with check refused it; the fix must keep that.
        val sibling = Files.createTempDirectory("fsd-provider-sibling-")
        try {
            val siblingCanary = Files.createFile(sibling.resolve("canary.txt"))
            val target = sibling.resolve("canary.txt")
            val traversalPath = homeDir.resolve("..").resolve(sibling.fileName).resolve("canary.txt")

            val result = runBlocking { provider.delete(traversalPath.toString()) }

            assertTrue(result.isFailure, "traversal-escape delete should be refused")
            assertTrue(
                Files.exists(siblingCanary),
                "canary at the sibling of home must NOT be erased",
            )
        } finally {
            Files.walk(sibling).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun `deleting a top-level symlink whose target lives outside home is accepted now that the user.home boundary is dropped`() {
        // The user.home boundary was deliberately removed. This test used to pin that a
        // symlink whose target lives outside home was refused at the boundary - that gate
        // is gone, and the test must move with it. The remaining protection (no traversal
        // past directory symlinks) is still pinned by the nested / chain tests below.
        val outside = Files.createTempDirectory("fsd-provider-outside-")
        try {
            val externalCanary = Files.createFile(outside.resolve("canary.txt"))
            val link =
                runCatching {
                    Files.createSymbolicLink(homeDir.resolve("outside-link"), outside)
                }
            assumeNoException("Symbolic links unavailable on this platform", link.exceptionOrNull())

            val result = runBlocking { provider.delete(link.getOrThrow().toString()) }

            // No throw - the boundary was removed.
            assertTrue(result.isSuccess, "symlink to outside is no longer refused; got $result")
        } finally {
            Files.walk(outside).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun `recursive delete does not follow a directory symlink nested under the target`() {
        // The permitted parent is `homeDir/work`. It contains a directory symlink to an external
        // directory holding a canary. Pre-fix, `deleteRecursively()` traversed the link and
        // removed the external canary; the fix must leave it intact.
        val work = Files.createDirectory(homeDir.resolve("work"))
        val outside = Files.createTempDirectory("fsd-provider-nested-")
        try {
            val externalCanary = Files.createFile(outside.resolve("canary.txt"))
            val linkResult =
                runCatching {
                    Files.createSymbolicLink(work.resolve("link"), outside)
                }
            assumeNoException("Symbolic links unavailable on this platform", linkResult.exceptionOrNull())

            val result = runBlocking { provider.delete(work.toString()) }

            assertTrue(result.isSuccess, "in-scope parent delete should succeed; got $result")
            assertFalse(Files.exists(work), "permitted parent dir should be gone")
            assertTrue(
                Files.exists(externalCanary),
                "canary at the nested symlink target must NOT be erased; #1118 escape",
            )
        } finally {
            Files.walk(outside).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun `a chain of nested directory symlinks under home does not escape the boundary`() {
        // homeDir/a -> homeDir/b (also under home) -> outside. The top-level delete is in-scope
        // (homeDir/a is a real directory under home), so the request SUCCEEDS - but the
        // NOFOLLOW_LINKS walk must not cross the chain into `outside`. The pre-fix
        // `deleteRecursively()` walked through both hops and erased the canary at the end.
        val a = Files.createDirectory(homeDir.resolve("a"))
        val b = Files.createDirectory(homeDir.resolve("b"))
        val outside = Files.createTempDirectory("fsd-provider-chain-")
        try {
            val externalCanary = Files.createFile(outside.resolve("canary.txt"))
            val bToOutside = runCatching { Files.createSymbolicLink(b.resolve("escape"), outside) }
            assumeNoException("Symbolic links unavailable on this platform", bToOutside.exceptionOrNull())
            val aToB = runCatching { Files.createSymbolicLink(a.resolve("hop"), b) }
            assumeNoException("Symbolic links unavailable on this platform", aToB.exceptionOrNull())

            val result = runBlocking { provider.delete(a.toString()) }

            assertTrue(result.isSuccess, "in-scope top-level delete should succeed; got $result")
            assertFalse(Files.exists(a), "the permitted top-level dir should be gone")
            assertTrue(
                Files.exists(externalCanary),
                "canary beyond the nested symlink chain must NOT be erased; #1118 escape",
            )
        } finally {
            Files.walk(outside).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun `a single legitimate file delete still works`() {
        val target = Files.createFile(homeDir.resolve("legit.txt"))
        Files.writeString(target, "ok")

        val result = runBlocking { provider.delete(target.toString()) }

        assertTrue(result.isSuccess, "in-scope file delete should succeed; got $result")
        assertFalse(Files.exists(target), "deleted file should be gone")
    }

    @Test
    fun `a legitimate recursive delete of an in-scope subtree still works`() {
        val root = Files.createDirectory(homeDir.resolve("subtree"))
        val child = Files.createDirectory(root.resolve("child"))
        val leaf = Files.createFile(child.resolve("leaf.txt"))
        Files.writeString(leaf, "ok")

        val result = runBlocking { provider.delete(root.toString()) }

        assertTrue(result.isSuccess, "in-scope subtree delete should succeed; got $result")
        assertFalse(Files.exists(root), "root should be gone")
        assertFalse(Files.exists(child), "child should be gone")
        assertFalse(Files.exists(leaf), "leaf should be gone")
    }

    @Test
    fun `deleting a missing file is treated as success to match the pre-fix contract`() {
        val missing = homeDir.resolve("never-existed.txt")

        val result = runBlocking { provider.delete(missing.toString()) }

        assertTrue(result.isSuccess, "missing file delete should succeed; got $result")
    }

    @Test
    fun `deleting a file symlink to an in-scope file removes the link, not the target`() {
        val target = Files.createFile(homeDir.resolve("target.txt"))
        Files.writeString(target, "keep-me")
        val linkResult =
            runCatching {
                Files.createSymbolicLink(homeDir.resolve("link.txt"), target)
            }
        assumeNoException("Symbolic links unavailable on this platform", linkResult.exceptionOrNull())

        val result = runBlocking { provider.delete(linkResult.getOrThrow().toString()) }

        assertTrue(result.isSuccess, "in-scope symlink delete should succeed; got $result")
        assertFalse(Files.exists(linkResult.getOrThrow()), "the symlink entry should be gone")
        assertTrue(
            Files.exists(target),
            "the file the symlink pointed at must remain; #1118 must not erase link targets",
        )
    }
}
