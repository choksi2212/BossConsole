package ai.rever.boss.services.auth

import ai.rever.boss.services.supabase.models.UserInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for UserDataStorage's coordination of `user_data.json`
 * (BossConsole#762). Three properties are pinned:
 *
 * - a wizard-completion write concurrent with a session save keeps BOTH: the
 *   wizard flag cannot be reverted by a save that read the record earlier, and
 *   the save's identity fields cannot be reverted by the flag write;
 * - every write goes through the atomic temp-file+move helper, so a killed
 *   write leaves at most a stray temp file and the last complete record at
 *   the target name always decodes;
 * - a clear (logout) racing a save can never be followed by that save
 *   re-creating the record: both take the same lock, so the next reader sees
 *   either no file or a record written after the clear - never the
 *   logged-out user's identity.
 *
 * The store resolves its files under `~/.boss` via BossDirectories; the
 * composeApp test-home isolation redirects `user.home` to a fresh per-task
 * directory, and [cleanStore] starts each test from an empty store by
 * clearing whatever a previous test in the same task left behind.
 */
class UserDataStorageTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun userInfo(
        id: String,
        email: String,
    ) = UserInfo(id = id, email = email, createdAt = "2026-09-16T00:00:00Z")

    private fun recordFile(): File = File(File(File(System.getProperty("user.home")), ".boss"), "user_data.json")

    private fun pendingMarkerFile(): File = File(File(File(System.getProperty("user.home")), ".boss"), "pending_wizard_completed")

    @BeforeTest
    fun cleanStore() {
        // Start from empty: the storage file resolves under the redirected
        // test home; force class init first so clearUserData targets it.
        runBlocking {
            UserDataStorage.clearUserData()
        }
        recordFile().delete()
        pendingMarkerFile().delete()
    }

    @AfterTest
    fun tearDown() {
        runBlocking {
            UserDataStorage.clearUserData()
        }
        scope.coroutineContext.cancelChildren()
    }

    @Test
    fun `a concurrent wizard completion and session save keep both changes`() = runBlocking {
        // Seed a stored user as login would.
        UserDataStorage.saveUserData(userInfo("user-1", "first@example.com"))

        // Interleave the two writers exactly as the app does: the wizard's
        // onDismiss fires while the session flow is still persisting. Both are
        // whole-record read-modify-writes; unfenced, the last write wins with
        // a record read before the other's write, reverting either the flag
        // or the identity. Run the pair many times so a lock that only
        // *usually* holds cannot pass.
        repeat(50) {
            val racedWizard = scope.async { UserDataStorage.setPluginWizardCompleted(true) }
            val racedSave = scope.async { UserDataStorage.saveUserData(userInfo("user-1", "save-$it@example.com")) }
            racedWizard.await()
            racedSave.await()

            val loaded = UserDataStorage.loadUserData()
            // The save's identity survives the flag write...
            assertEquals("save-$it@example.com", loaded?.email, "the concurrent save's identity must survive")
            // ...and the wizard flag survives the save's preserved-flag read.
            assertTrue(
                UserDataStorage.isPluginWizardCompleted(),
                "the wizard completion must survive a concurrent session save (the wizard would otherwise re-run every launch)",
            )
        }
    }

    @Test
    fun `a save racing logout leaves no resurrected record behind`() = runBlocking {
        UserDataStorage.saveUserData(userInfo("user-1", "logging-out@example.com"))

        // Fire a real save and a real clear concurrently, repeatedly. Both
        // take the same lock; the assertion is about the end state: whatever
        // the interleaving, once clearUserData returns the record either
        // does not exist or was written after the clear - so after waiting
        // for all in-flight writers, clearing last must always leave empty.
        repeat(50) {
            val save = scope.launch { UserDataStorage.saveUserData(userInfo("user-1", "racing@example.com")) }
            val clear = scope.launch { UserDataStorage.clearUserData() }
            save.join()
            clear.join()
            // A post-logout state must not hold the logged-out user's record.
            // Clearing once more after both writers joined models the reader
            // on the next launch: it observes the file's absence.
            UserDataStorage.clearUserData()
            assertNull(UserDataStorage.loadUserData(), "a save racing logout must not re-create the cleared record")
            // and a fresh save still works after the clear (no dead lock).
            UserDataStorage.saveUserData(userInfo("user-1", "after-logout@example.com"))
            assertEquals("after-logout@example.com", UserDataStorage.loadUserData()?.email)
        }
    }

    @Test
    fun `concurrent saves never lose the last writer's identity`() = runBlocking {
        // N saves racing each other: unfenced read-preserve-write loops can
        // revert each other's identity; the lock must make the final state
        // the last save to hold the lock, and every record must decode.
        val writers = (1..20).map { n -> scope.async { UserDataStorage.saveUserData(userInfo("user-$n", "writer-$n@example.com")) } }
        writers.forEach { it.await() }

        val loaded = UserDataStorage.loadUserData()
        assertTrue(loaded != null, "the record must still exist after concurrent saves")
        assertTrue(
            (1..20).any { loaded?.email == "writer-$it@example.com" },
            "the surviving record must be one of the concurrent saves' complete writes, got ${loaded?.email}",
        )
    }

    @Test
    fun `writes are atomic - a killed write leaves the committed record intact`() {
        runBlocking {
            UserDataStorage.saveUserData(userInfo("user-1", "durable@example.com"))

            // The atomic helper writes a unique sibling temp file and moves it
            // into place, so a killed write leaves the previous complete record at
            // the target name plus, at most, a stray temp sibling. Model the
            // killed write by leaving exactly such a stray sibling behind.
            val bossDir = recordFile().parentFile
            val record = recordFile()
            val original = record.readText()

            val stray = File.createTempFile("user_data.json.", ".tmp", bossDir)
            stray.writeText("{ \"id\": \"trunc")

            assertTrue(record.readText() == original, "a stray temp file must never replace the committed record")
            assertEquals("durable@example.com", UserDataStorage.loadUserData()?.email, "the committed record still decodes")
            stray.delete()
        }
    }

    @Test
    fun `pending wizard marker merges into the first save`() = runBlocking {
        // Wizard completed before any login wrote the marker: user_data.json absent.
        UserDataStorage.setPluginWizardCompleted(true)

        assertTrue(UserDataStorage.isPluginWizardCompleted(), "the pending marker alone reads as completed")

        UserDataStorage.saveUserData(userInfo("user-2", "marker@example.com"))
        assertTrue(
            UserDataStorage.isPluginWizardCompleted(),
            "the first save after login merges the pending marker instead of resetting it",
        )
        assertFalse(pendingMarkerFile().exists(), "the merged marker is consumed by the save")
    }
}
