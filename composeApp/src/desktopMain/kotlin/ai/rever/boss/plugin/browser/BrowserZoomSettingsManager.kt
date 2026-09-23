package ai.rever.boss.plugin.browser

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Per-domain zoom settings for a website.
 */
@Serializable
data class DomainZoomSettings(
    val domain: String,
    val zoomLevel: Double,
    val lastUpdated: Long = System.currentTimeMillis(),
)

/**
 * Container for all zoom settings data.
 */
@Serializable
data class BrowserZoomSettingsData(
    val domainSettings: Map<String, DomainZoomSettings> = emptyMap(),
    val defaultZoomLevel: Double = 1.0,
)

/**
 * Manager for persisting per-domain zoom settings.
 *
 * Stores zoom preferences in ~/.boss/browser-zoom-settings.json
 * so users can have different zoom levels for different websites.
 */
object BrowserZoomSettingsManager {
    /**
     * Serializes the two save entry points against each other AND holds the
     * read-modify-write mutators ([setZoomForDomain], [clearDomainZoom],
     * [clearAllSettings]) as a single critical section (#1051): without it,
     * a save interleaved with a mutator can read state the mutator has not
     * yet committed, and two concurrent mutators can each read the same
     * state and overwrite each other's write.
     */
    private val saveLock = Any()
    private val logger = BossLogger.forComponent("BrowserZoomSettingsManager")

    /** Overridable so tests exercise the real read/write path without touching `~/.boss`. */
    internal var settingsFile: File = BossDirectories.resolve("browser-zoom-settings.json")

    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    /**
     * `@Volatile` so the mutator's write is visible to readers on other
     * threads without taking [saveLock] (#1051). The lock is what makes
     * the read-modify-write atomic; the volatile is what guarantees a
     * later reader does not see stale state through CPU caching.
     */
    @Volatile
    private var settings = BrowserZoomSettingsData()

    init {
        loadSettings()
    }

    /**
     * Get the zoom level for a domain.
     * Returns 1.0 (100%) if no custom zoom is set.
     */
    fun getZoomForDomain(domain: String): Double {
        val normalizedDomain = normalizeDomain(domain)
        return settings.domainSettings[normalizedDomain]?.zoomLevel ?: settings.defaultZoomLevel
    }

    /**
     * Set the zoom level for a domain.
     * If zoomLevel is 1.0 (100%), removes the domain entry.
     *
     * The read, the conditional mutation, and the assignment all run under
     * [saveLock] so a concurrent mutator cannot interleave and overwrite a
     * change (#1051). The companion [saveSettings] / [saveSettingsSync] take
     * the same lock around the write, so a save cannot read a half-applied
     * state either.
     */
    fun setZoomForDomain(
        domain: String,
        zoomLevel: Double,
    ) {
        val normalizedDomain = normalizeDomain(domain)

        synchronized(saveLock) {
            settings =
                if (kotlin.math.abs(zoomLevel - 1.0) < 0.001) {
                    // Remove entry if zoom is reset to 100%
                    settings.copy(
                        domainSettings = settings.domainSettings - normalizedDomain,
                    )
                } else {
                    // Update or add entry
                    settings.copy(
                        domainSettings =
                            settings.domainSettings + (
                                normalizedDomain to
                                    DomainZoomSettings(
                                        domain = normalizedDomain,
                                        zoomLevel = zoomLevel,
                                        lastUpdated = System.currentTimeMillis(),
                                    )
                            ),
                    )
                }
        }
    }

    /**
     * Load settings from disk.
     */
    internal fun loadSettings() {
        try {
            if (settingsFile.exists()) {
                val content = settingsFile.readText()
                settings = json.decodeFromString<BrowserZoomSettingsData>(content)
            }
        } catch (e: Exception) {
            // Self-heal instead of silent data loss (#925): a corrupt file is
            // renamed aside so the fault is diagnosable AND does not re-fail
            // every launch, and the previous per-domain zoom levels are lost
            // only when no backup survives - not on any decode hiccup.
            logger.warn(LogCategory.BROWSER, "Error loading zoom settings", error = e)
            moveCorruptSettingsAside(settingsFile)
            settings = BrowserZoomSettingsData()
        }
    }

    /**
     * Save settings to disk.
     */
    suspend fun saveSettings() {
        withContext(Dispatchers.IO) {
            try {
                settingsFile.parentFile?.mkdirs()
                synchronized(saveLock) {
                    settingsFile.atomicWriteText(json.encodeToString(settings))
                }
            } catch (e: Exception) {
                logger.warn(LogCategory.BROWSER, "Error saving zoom settings", error = e)
            }
        }
    }

    /**
     * Save settings synchronously (for use in non-coroutine contexts).
     */
    fun saveSettingsSync() {
        try {
            settingsFile.parentFile?.mkdirs()
            synchronized(saveLock) {
                settingsFile.atomicWriteText(json.encodeToString(settings))
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Error saving zoom settings (sync)", error = e)
        }
    }

    /**
     * Normalize domain to handle variations.
     * Removes www. prefix and converts to lowercase.
     */
    private fun normalizeDomain(domain: String): String =
        domain
            .lowercase()
            .removePrefix("www.")
            .trim()

    /**
     * Extract domain from a URL.
     */
    fun extractDomain(url: String): String? =
        try {
            java.net
                .URL(url)
                .host
                .let { normalizeDomain(it) }
        } catch (e: Exception) {
            logger.debug(
                LogCategory.BROWSER,
                "URL has no parsable host - no per-domain zoom",
                mapOf("error" to e.toString()),
            )
            null
        }

    /**
     * Get all stored domain zoom settings.
     */
    fun getAllDomainSettings(): Map<String, DomainZoomSettings> = settings.domainSettings.toMap()

    /**
     * Clear zoom setting for a specific domain. R-M-W under [saveLock] so
     * a concurrent [setZoomForDomain] for the same domain cannot re-introduce
     * the entry this is removing (#1051).
     */
    fun clearDomainZoom(domain: String) {
        val normalizedDomain = normalizeDomain(domain)
        synchronized(saveLock) {
            settings =
                settings.copy(
                    domainSettings = settings.domainSettings - normalizedDomain,
                )
        }
    }

    /**
     * Clear all domain zoom settings. R-M-W under [saveLock] (#1051).
     */
    fun clearAllSettings() {
        synchronized(saveLock) {
            settings = BrowserZoomSettingsData()
        }
    }
}

/**
 * Renames a corrupt settings file to `<name>.corrupt.<millis>.<uuid>` beside
 * its live path (#925, #1051): the fault stays diagnosable, the live name is
 * freed for a fresh write on the next save, and the next launch does not
 * re-read and re-fail the same bytes. Pure file operation - unit-testable
 * standalone.
 *
 * Two refinements over the original helper (#1051):
 *
 * - **The aside name is collision-proof.** A `System.currentTimeMillis()`
 *   timestamp only has millisecond resolution, so two quarantines in the
 *   same millisecond rename to the same target - POSIX `rename(2)` then
 *   replaces the earlier backup, and Win32 `MoveFile` reports
 *   `ERROR_ALREADY_EXISTS`. A `UUID` random component guarantees distinct
 *   asides even at sub-millisecond spacing.
 * - **`File.renameTo`'s return value is checked.** It returns false on
 *   Windows when the destination exists (the source path the next launch
 *   will try to read) and silently in many other failure modes; a silent
 *   no-op here is exactly the failure mode that re-fails the same decode
 *   on the next launch.
 */
internal fun moveCorruptSettingsAside(
    file: File,
    now: () -> Long = { System.currentTimeMillis() },
    renameFn: (File, File) -> Boolean = File::renameTo,
) {
    // Deliberately quiet: the caller already logged the decode failure; this
    // is the recovery step, and its own failure must not mask the original.
    runCatching {
        // The millisecond timestamp is kept for human diagnosis - a series of
        // crashes within the same second stays observable in filename order -
        // but the UUID random component is what guarantees no two quarantines
        // land on the same aside name.
        val aside = File(file.absolutePath + ".corrupt." + now() + "." + UUID.randomUUID().toString())
        if (!renameFn(file, aside)) {
            // renameTo fails on Windows when the destination already exists
            // and in other platform-specific cases; the live file would
            // otherwise still be where the next launch tries to read it.
            BossLogger.forComponent("BrowserZoomSettingsManager").warn(
                LogCategory.BROWSER,
                "Could not rename corrupt settings aside",
                mapOf("file" to file.absolutePath, "aside" to aside.absolutePath),
            )
        }
    }
}
