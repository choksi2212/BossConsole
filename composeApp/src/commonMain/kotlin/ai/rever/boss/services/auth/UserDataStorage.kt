package ai.rever.boss.services.auth

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.services.supabase.models.UserInfo
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.LogSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persistent storage for user data to survive app restarts
 *
 * WHY THIS EXISTS (Important - Not a Workaround!):
 * ================================================
 *
 * This is the CORRECT solution for custom authentication providers with Supabase.
 * It is NOT a hack or temporary workaround - it's the recommended pattern.
 *
 * Background:
 * -----------
 * Supabase Auth was designed for built-in authentication providers (OAuth, email/password, magic links).
 * When you use a built-in provider, Supabase generates JWT tokens that include full user information,
 * and the Supabase-KT client automatically populates session.user with this data.
 *
 * Custom Authentication Providers (like Passkeys):
 * ------------------------------------------------
 * For custom authentication providers (WebAuthn/passkeys), we implement the authentication
 * logic ourselves:
 *
 * 1. Client verifies passkey signature (Touch ID, Windows Hello, etc.)
 * 2. Edge Function generates Supabase-compatible JWT tokens
 * 3. Client imports session using auth.importSession()
 * 4. **Problem**: Supabase-KT intentionally does NOT populate session.user from custom JWTs
 *    - This is by design, not a bug
 *    - Custom JWTs don't include the user metadata that built-in providers include
 *    - The session.user property remains null
 *
 * 5. **Solution**: UserDataStorage persists user information separately
 *    - We store user data (id, email, createdAt) in local storage
 *    - This data persists across app restarts
 *    - SessionManager coordinates between Supabase auth (JWT tokens) and UserDataStorage (user info)
 *
 * Why Not Use Magic Links Instead?
 * --------------------------------
 * Magic links would populate session.user, but they:
 * - Break the passwordless/biometric UX flow
 * - Add unnecessary friction (email verification step)
 * - Defeat the purpose of passkey authentication
 * - Are less secure (email interception risk)
 *
 * The Correct Pattern:
 * -------------------
 * For custom authentication providers with Supabase:
 * 1. Implement authentication logic yourself (verify passkey, etc.)
 * 2. Generate Supabase-compatible JWT tokens on the backend
 * 3. Use importSession() to establish the Supabase session (for API access)
 * 4. Persist user data separately (UserDataStorage) for app state
 * 5. Use SessionManager to coordinate both
 *
 * This pattern is used by many Supabase applications that implement custom auth providers.
 *
 * Related Documentation:
 * ---------------------
 * - See SessionManager.kt for session orchestration logic
 * - See PasskeyAuthService.kt for passkey authentication implementation
 * - See CoreAuthService.kt for session initialization and restoration
 *
 * Storage Location:
 * ----------------
 * User data is stored in: ~/.boss/user_data.json
 * This file is automatically created and managed by this service.
 */
object UserDataStorage {
    /** Redirected by [resetForTesting] for hermetic unit tests; production code never reassigns it. */
    internal var storageFile: File = BossDirectories.resolve("user_data.json")

    /** Same redirect as [storageFile]; the two files always live in the same directory. */
    internal var pendingWizardCompletedFile: File = BossDirectories.resolve("pending_wizard_completed")
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }
    private val logger = BossLogger.forComponent("UserDataStorage")

    /**
     * Serialises every read-modify-write of [storageFile].
     *
     * `saveUserData` and `setPluginWizardCompleted` both read the file, edit one field, and write
     * the whole record back. Nothing coordinated them, and both are reachable at once: the plugin
     * wizard can finish while a session restore is saving user data, and the loser's field is
     * silently reverted - the wizard re-runs on the next launch because `pluginWizardCompleted`
     * went back to false. `clearUserData` takes the lock too, so logout cannot delete the file
     * between another writer's read and its write and have that writer recreate it.
     */
    private val fileLock = Mutex()

    @Serializable
    data class StoredUserData(
        val id: String,
        val email: String,
        val createdAt: String,
        val authenticatedVia: String? = null, // "passkey", "magic_link", "password", etc.
        val pluginWizardCompleted: Boolean = false, // Whether the plugin install wizard has been completed
    )

    init {
        // Ensure directory exists
        storageFile.parentFile?.mkdirs()
    }

    /**
     * Point both files at [testDir] for hermetic unit testing. Tests must call this again with
     * the real directory (e.g. [BossDirectories.rootDir]) before finishing, so the singleton is
     * left where the app and other tests expect it.
     */
    internal fun resetForTesting(testDir: File) {
        storageFile = testDir.resolve("user_data.json")
        pendingWizardCompletedFile = testDir.resolve("pending_wizard_completed")
    }

    /**
     * The wizard-completed marker written before the user logged in, or false if it is absent or
     * unreadable. Call while holding [fileLock].
     */
    private fun readPendingWizardFlag(): Boolean {
        if (!pendingWizardCompletedFile.exists()) return false
        return try {
            pendingWizardCompletedFile.readText().trim().toBoolean()
        } catch (e: Exception) {
            logger.debug(
                LogCategory.AUTH,
                "Could not read pending wizard-completed marker - assuming false",
                mapOf("error" to e.toString()),
            )
            false
        }
    }

    /**
     * The wizard-completed flag already in [storageFile], or false if it is absent or unreadable.
     * Read so that saving user data preserves it rather than resetting it. Call while holding
     * [fileLock].
     */
    private fun readStoredWizardFlag(): Boolean {
        if (!storageFile.exists()) return false
        return try {
            json.decodeFromString<StoredUserData>(storageFile.readText()).pluginWizardCompleted
        } catch (e: Exception) {
            logger.debug(
                LogCategory.AUTH,
                "Could not read stored wizard status - assuming false",
                mapOf("error" to e.toString()),
            )
            false
        }
    }

    /**
     * Save user data to persistent storage
     *
     * Preserves the pluginWizardCompleted flag if it was previously set,
     * and also merges any pending wizard completion status.
     */
    suspend fun saveUserData(
        user: UserInfo,
        authenticatedVia: String? = null,
    ) {
        withContext(Dispatchers.IO) {
            fileLock.withLock {
                try {
                    // Either source being true means completed: the pending marker is written
                    // before login, the stored flag after.
                    val wizardCompleted = readPendingWizardFlag() || readStoredWizardFlag()

                    val data =
                        StoredUserData(
                            id = user.id,
                            email = user.email,
                            createdAt = user.createdAt,
                            authenticatedVia = authenticatedVia,
                            pluginWizardCompleted = wizardCompleted,
                        )
                    val content = json.encodeToString(data)
                    // Atomic: `writeText` truncates first, so a crash or a concurrent writer leaves a
                    // half-written user_data.json. That parses as corrupt on the next launch, the user
                    // is treated as logged out, and the plugin wizard runs again.
                    storageFile.atomicWriteText(content)
                    logger.debug(
                        LogCategory.AUTH,
                        "Saved user data",
                        mapOf("email" to LogSanitizer.maskEmail(user.email)),
                    )

                    // Clean up pending file if it exists
                    if (pendingWizardCompletedFile.exists()) {
                        pendingWizardCompletedFile.delete()
                    }
                } catch (e: Exception) {
                    logger.error(LogCategory.AUTH, "Error saving user data", error = e)
                }
            }
        }
    }

    /**
     * Load user data from persistent storage
     */
    suspend fun loadUserData(): UserInfo? =
        withContext(Dispatchers.IO) {
            try {
                if (storageFile.exists()) {
                    val content = storageFile.readText()
                    val data = json.decodeFromString<StoredUserData>(content)
                    logger.debug(
                        LogCategory.AUTH,
                        "Loaded user data",
                        mapOf(
                            "email" to LogSanitizer.maskEmail(data.email),
                            "authenticatedVia" to (data.authenticatedVia ?: "unknown"),
                        ),
                    )
                    UserInfo(
                        id = data.id,
                        email = data.email,
                        createdAt = data.createdAt,
                    )
                } else {
                    logger.debug(LogCategory.AUTH, "No stored user data found")
                    null
                }
            } catch (e: Exception) {
                logger.error(LogCategory.AUTH, "Error loading user data", error = e)
                null
            }
        }

    /**
     * Clear stored user data (on logout)
     */
    suspend fun clearUserData() {
        withContext(Dispatchers.IO) {
            fileLock.withLock {
                try {
                    if (storageFile.exists()) {
                        storageFile.delete()
                        logger.debug(LogCategory.AUTH, "Cleared user data")
                    }
                } catch (e: Exception) {
                    logger.error(LogCategory.AUTH, "Error clearing user data", error = e)
                }
            }
        }
    }

    /**
     * Check if the plugin installation wizard has been completed for this user.
     *
     * Checks both the main user_data.json and the pending file (for cases where
     * the wizard was completed before user logged in).
     */
    suspend fun isPluginWizardCompleted(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // First check the main user data file
                if (storageFile.exists()) {
                    try {
                        val content = storageFile.readText()
                        val data = json.decodeFromString<StoredUserData>(content)
                        if (data.pluginWizardCompleted) {
                            return@withContext true
                        }
                    } catch (e: kotlinx.serialization.SerializationException) {
                        logger.error(
                            LogCategory.SYSTEM,
                            "User data file corrupted, will reset on next save",
                            error = e,
                        )
                        // Don't delete here - let next save handle it
                        // Fall through to check pending file
                    } catch (e: Exception) {
                        logger.error(
                            LogCategory.SYSTEM,
                            "Error reading user data file",
                            error = e,
                        )
                        // Fall through to check pending file
                    }
                }

                // Also check the pending file (wizard completed before login)
                if (pendingWizardCompletedFile.exists()) {
                    try {
                        val pendingValue = pendingWizardCompletedFile.readText().trim().toBoolean()
                        if (pendingValue) {
                            return@withContext true
                        }
                    } catch (e: Exception) {
                        logger.error(
                            LogCategory.SYSTEM,
                            "Error reading pending wizard file",
                            error = e,
                        )
                    }
                }

                false
            } catch (e: Exception) {
                logger.error(LogCategory.AUTH, "Error checking plugin wizard status", error = e)
                false
            }
        }
    }

    /**
     * Mark the plugin installation wizard as completed for this user.
     *
     * If user_data.json doesn't exist yet (user not logged in), stores the setting
     * in a separate file that will be merged when the user logs in. If the file exists but is
     * undecodable - the torn state a pre-atomic-write `writeText` left on existing installs -
     * the flag goes to that same pending marker: there is no record to copy it into, and none
     * can be fabricated without the user's identity; [saveUserData] merges the marker into a
     * fresh, whole record on the next login. Without the fallback the wizard would re-run on
     * every launch for exactly the installs issue #762 is about.
     */
    suspend fun setPluginWizardCompleted(completed: Boolean) {
        withContext(Dispatchers.IO) {
            fileLock.withLock {
                try {
                    if (storageFile.exists()) {
                        val content = storageFile.readText()
                        try {
                            val data = json.decodeFromString<StoredUserData>(content)
                            val updatedData = data.copy(pluginWizardCompleted = completed)
                            // Atomic, and under the lock: read and write are one step, so a
                            // saveUserData landing in between cannot have its record
                            // overwritten by this copy of the older one.
                            storageFile.atomicWriteText(json.encodeToString(updatedData))
                            logger.debug(
                                LogCategory.AUTH,
                                "Updated plugin wizard completion status",
                                mapOf(
                                    "completed" to completed,
                                ),
                            )
                        } catch (e: kotlinx.serialization.SerializationException) {
                            // An existing but torn record: the flag has nowhere to go inside it,
                            // so persist it via the pending marker (the pre-login path). The
                            // next saveUserData merges the marker into a fresh, whole record.
                            logger.warn(
                                LogCategory.AUTH,
                                "user_data.json undecodable; persisting wizard status via pending marker",
                                error = e,
                            )
                            pendingWizardCompletedFile.atomicWriteText(completed.toString())
                        }
                    } else {
                        // File doesn't exist yet - store in a temporary pending file
                        // This will be merged when saveUserData is called
                        pendingWizardCompletedFile.atomicWriteText(completed.toString())
                    }
                } catch (e: Exception) {
                    logger.error(LogCategory.AUTH, "Error setting plugin wizard status", error = e)
                }
            }
        }
    }
}
