package ai.rever.boss.services.auth

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.services.supabase.models.UserInfo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Hermetic coverage for [UserDataStorage.setPluginWizardCompleted] around a torn
 * user_data.json - the state a pre-atomic-write `writeText` left on existing installs, and the
 * one #762 kept reproducing: the wizard completes, the decode throws, nothing is persisted,
 * and the wizard runs again on the next launch.
 */
class UserDataStorageWizardTest {
    private lateinit var workDir: File
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    @BeforeTest
    fun setUp() {
        workDir = Files.createTempDirectory("user-data-wizard-test-").toFile()
        UserDataStorage.resetForTesting(workDir)
    }

    @AfterTest
    fun tearDown() {
        // Point the singleton back at the user's directory before anything else uses it.
        UserDataStorage.resetForTesting(BossDirectories.rootDir)
        workDir.deleteRecursively()
    }

    @Test
    fun `completion is persisted via the pending marker when the stored record is corrupt`() =
        runBlocking {
            UserDataStorage.storageFile.writeText("{ this is not a record")

            UserDataStorage.setPluginWizardCompleted(true)

            assertTrue(
                UserDataStorage.pendingWizardCompletedFile.exists(),
                "the flag must survive the torn record via the pending marker",
            )
            assertEquals("true", UserDataStorage.pendingWizardCompletedFile.readText().trim())
            assertTrue(
                UserDataStorage.isPluginWizardCompleted(),
                "the completed wizard must not re-run on the next launch",
            )
        }

    @Test
    fun `completion updates an intact record and leaves no pending marker`() =
        runBlocking {
            val user = UserInfo(id = "u-1", email = "a@example.com", createdAt = "2026-01-01T00:00:00Z")
            UserDataStorage.saveUserData(user)

            UserDataStorage.setPluginWizardCompleted(true)

            val stored =
                json.decodeFromString(
                    UserDataStorage.StoredUserData.serializer(),
                    UserDataStorage.storageFile.readText(),
                )
            assertTrue(stored.pluginWizardCompleted)
            assertFalse(UserDataStorage.pendingWizardCompletedFile.exists())
        }

    @Test
    fun `completion without a record goes to the pending marker`() =
        runBlocking {
            UserDataStorage.setPluginWizardCompleted(true)

            assertTrue(UserDataStorage.pendingWizardCompletedFile.exists())
            assertFalse(UserDataStorage.storageFile.exists())
        }

    /**
     * The logout generation fence (BossConsole#762, review follow-up on the merged #795):
     * a save that entered before logout but acquires the lock only after clearUserData ran
     * must NOT recreate user_data.json. Deterministic via the internal seam: hand the save
     * the generation it *would have* captured before the interleaved clear.
     */
    @Test
    fun `a save that acquires the lock only after logout cannot resurrect the cleared record`() =
        runBlocking {
            val user = UserInfo(id = "u1", email = "fenced@example.com", createdAt = "2026-09-17T00:00:00Z")
            UserDataStorage.saveUserData(user)
            assertTrue(UserDataStorage.storageFile.exists())

            // The generation a save that entered BEFORE logout would have captured.
            val staleGeneration = UserDataStorage.generationForTest()

            // Logout runs to completion while the save is "waiting".
            UserDataStorage.clearUserData()
            assertFalse(UserDataStorage.storageFile.exists(), "logout deleted the record")

            // The waiting save acquires the lock with its pre-logout generation: the fence must skip it.
            UserDataStorage.doSaveUserData(user, authenticatedVia = null, generationAtEntry = staleGeneration)
            assertFalse(
                UserDataStorage.storageFile.exists(),
                "a save entered before logout but executed after clearUserData must not recreate the record",
            )

            // A save entered AFTER logout (fresh generation) still works.
            UserDataStorage.saveUserData(user)
            assertEquals("fenced@example.com", UserDataStorage.loadUserData()?.email)
        }
}
