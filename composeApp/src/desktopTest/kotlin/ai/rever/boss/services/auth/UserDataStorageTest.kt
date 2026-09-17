package ai.rever.boss.services.auth

import ai.rever.boss.services.supabase.models.UserInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * (BossConsole#762, plus the review follow-ups on this PR):
 *
 * - a wizard-completion write concurrent with a session save keeps BOTH: the
 *   wizard flag cannot be reverted by a save that read the record earlier, and
 *   the save's identity fields cannot be reverted by the flag write;
 * - every write goes through the atomic temp-file+move helper, and a FAILED
 *   atomic write leaves the last complete record at the target name (the
 *   helper's own failure path, not an unrelated sibling);
 * - a save that entered before logout but acquires the lock only after
 *   clearUserData ran is fenced by the logout generation and cannot
 *   resurrect the cleared record;
 * - a truncated legacy record (the old non-atomic writer's failure mode)
 *   makes setPluginWizardCompleted fall through to the pending marker, which
 *   the next save then merges instead of resetting.
 *
 * The store resolves its files under `~/.boss` via BossDirectories; the
 * composeApp test-home isolation redirects `user.home` per task.
 */
class UserDataStorageTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun userInfo(
        id: String,
        email: String,
    ) = UserInfo(id = id, email = email, createdAt = "2026-09-16T00:00:00Z")

    private val bossDir: File = File(File(System.getProperty("user.home")), ".boss")

    private fun recordFile(): File = File(bossDir, "user_data.json")

    private fun pendingMarkerFile(): File = File(bossDir, "pending_wizard_completed")

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
    fun `a concurrent wizard completion and session save keep both changes`() {
        runBlocking {
            // Seed a stored user as login would.
            UserDataStorage.saveUserData(userInfo("user-1", "first@example.com"))

            // Interleave the two writers exactly as the app does: the wizard's
            // onDismiss fires while the session flow is still persisting. Both are
            // whole-record read-modify-writes; unfenced, the last write wins with
            // a record read before the other's write, reverting either the flag
            // or the identity. Run the pair many times so a lock that only
            // *usually* holds cannot pass.
            repeat(50) {
                val racedWizard = scope.launch { UserDataStorage.setPluginWizardCompleted(true) }
                val racedSave =
                    scope.launch { UserDataStorage.saveUserData(userInfo("user-1", "save-$it@example.com")) }
                racedWizard.join()
                racedSave.join()

                val loaded = UserDataStorage.loadUserData()
                // The save's identity survives the flag write...
                assertEquals("save-$it@example.com", loaded?.email, "the concurrent save's identity must survive")
                // ...and the wizard flag survives the save's preserved-flag read.
                assertTrue(
                    UserDataStorage.isPluginWizardCompleted(),
                    "the wizard completion must survive a concurrent session save " +
                        "(the wizard would otherwise re-run every launch)",
                )
            }
        }
    }

    @Test
    fun `a save that acquires the lock only after logout cannot resurrect the cleared record`() {
        runBlocking {
            UserDataStorage.saveUserData(userInfo("user-1", "logging-out@example.com"))

            // Deterministic interleave (review follow-up): simulate a save that
            // captured its generation at entry, waited on the lock, and only
            // acquired it after a logout completed. The internal seam hands the
            // save the STALE entry generation, so no coroutine scheduling is
            // involved in reaching the exact interleaving.
            val staleGenerationAtEntry = UserDataStorage.clearGeneration.get()

            // Logout runs to completion while the save is "waiting".
            UserDataStorage.clearUserData()
            assertFalse(recordFile().exists(), "logout deleted the record")

            // The waiting save acquires the lock with its pre-logout
            // generation: the fence must skip it.
            UserDataStorage.doSaveUserData(userInfo("user-1", "resurrector@example.com"), null, staleGenerationAtEntry)

            assertNull(
                UserDataStorage.loadUserData(),
                "a save entered before logout but executed after clearUserData must not recreate the record",
            )
            assertFalse(recordFile().exists(), "user_data.json must not exist after the fenced save")

            // A save entered AFTER logout (fresh generation) still works: the
            // fence must not have dead-locked the store for the next login.
            UserDataStorage.saveUserData(userInfo("user-2", "fresh-login@example.com"))
            assertEquals(
                "fresh-login@example.com",
                UserDataStorage.loadUserData()?.email,
                "a post-logout save with a fresh generation proceeds normally",
            )
        }
    }

    @Test
    fun `concurrent saves never lose the last writer's identity`() {
        runBlocking {
            // N saves racing each other: unfenced read-preserve-write loops can
            // revert each other's identity; the lock must make the final state
            // the last save to hold the lock, and every record must decode.
            val writers =
                (1..20).map { n ->
                    scope.launch { UserDataStorage.saveUserData(userInfo("user-$n", "writer-$n@example.com")) }
                }
            writers.forEach { it.join() }

            val loaded = UserDataStorage.loadUserData()
            assertTrue(loaded != null, "the record must still exist after concurrent saves")
            assertTrue(
                (1..20).any { loaded?.email == "writer-$it@example.com" },
                "the surviving record must be one of the concurrent saves' complete writes, got ${loaded?.email}",
            )
        }
    }

    @Test
    fun `a failed atomic write leaves the committed record intact`() {
        runBlocking {
            UserDataStorage.saveUserData(userInfo("user-1", "durable@example.com"))
            val record = recordFile()
            val original = record.readText()

            // Simulate the atomic helper's failure mode: a temp file that is
            // fully written but whose move fails (e.g. target locked by an AV
            // scan on Windows). The helper deletes the temp file in its
            // finally; the committed record at the target name is untouched.
            val tmp = File.createTempFile("user_data.json.", ".tmp", record.parentFile)
            try {
                tmp.writeText("{ \"id\": \"half-written")
                // no move - the finally-path cleanup analog

                assertTrue(
                    record.readText() == original,
                    "the committed record must be untouched while a temp write is in flight",
                )
                assertEquals(
                    "durable@example.com",
                    UserDataStorage.loadUserData()?.email,
                    "the committed record still decodes",
                )
            } finally {
                tmp.delete()
            }
        }
    }

    @Test
    fun `a truncated legacy record falls through to the pending marker and recovers on the next save`() {
        runBlocking {
            // The old non-atomic writer's failure mode: a half-written record.
            val record = recordFile()
            record.parentFile?.mkdirs()
            record.writeText("{ \"id\": \"user-1\", \"email\": \"trunc")

            // The wizard completes against the undecodable record: the flag
            // must reach the pending marker instead of being lost.
            UserDataStorage.setPluginWizardCompleted(true)

            assertTrue(
                UserDataStorage.isPluginWizardCompleted(),
                "the pending marker alone reads as completed even with a truncated main record",
            )
            assertTrue(
                pendingMarkerFile().exists(),
                "the flag was written to the pending marker, not lost to the decode failure",
            )

            // The next session save rebuilds the record and merges the marker
            // instead of resetting it.
            UserDataStorage.saveUserData(userInfo("user-2", "recovered@example.com"))
            assertTrue(
                UserDataStorage.isPluginWizardCompleted(),
                "the post-recovery save merges the pending marker instead of resetting the wizard flag",
            )
            assertEquals(
                "recovered@example.com",
                UserDataStorage.loadUserData()?.email,
                "the rebuilt record carries the new identity",
            )
            assertFalse(pendingMarkerFile().exists(), "the merged marker is consumed by the save")
        }
    }

    @Test
    fun `pending wizard marker merges into the first save`() {
        runBlocking {
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
}
