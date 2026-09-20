package ai.rever.boss.components.plugin.panels.left_top

import ai.rever.boss.plugin.api.FileNodeData
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.extractFileName
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.Project
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

// Global project state with persistence - manages shared recent projects list
// Note: Selected project is per-window via WindowProjectState. This object only manages recent projects.
object ProjectState {
    private val logger = BossLogger.forComponent("ProjectState")
    private const val MAX_RECENT_PROJECTS = 10
    private const val RECENT_PROJECTS_FILE = "recent-projects.json"

    /**
     * Forward-compatible JSON: a future field added to [Project] must not brick loading on every
     * installed build. The CLI's `BossRecentCommand` already sets `ignoreUnknownKeys = true` for
     * exactly this reason (the comment on its `Json` instance: "the parser in the production code
     * uses Json { ignoreUnknownKeys = true } so a field added in the future ..."); the host had
     * drifted away from that contract.
     */
    private val json =
        Json {
            prettyPrint = false
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /**
     * Overridable so tests exercise the real read/write path against a hermetic file, matching
     * `RecentFilesManager.settingsFile` and `RecentBrowserPagesManager.settingsFile`. Production
     * code never reassigns it.
     */
    internal var recentProjectsFile: File =
        File(ai.rever.boss.plugin.pathutils.BossDirectories.rootDir, RECENT_PROJECTS_FILE)

    // Recent projects list - loaded from disk on init (shared across all windows)
    private val _recentProjects = MutableStateFlow<List<Project>>(emptyList())
    val recentProjects: StateFlow<List<Project>> = _recentProjects.asStateFlow()

    private val ioScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)

    /**
     * Retained so [resetForTesting] can cancel it: the init load reads [recentProjectsFile] at
     * execution time, and a test that re-points the file must not have the first load merge
     * the real user's list into its hermetic state. Mirrors
     * [ai.rever.boss.dashboard.RecentFilesManager.initialLoadJob].
     */
    private var initialLoadJob: Job? = null

    init {
        // Load recent projects from disk on startup (async to avoid blocking main thread)
        initialLoadJob =
            ioScope.launch {
                loadRecentProjects()
            }
    }

    /**
     * Redirect [recentProjectsFile] to a hermetic test file and clear the in-memory list, so
     * a test that runs after another has not already populated `_recentProjects` with stale
     * entries. Cancels the init's deferred load so it cannot merge the real user's file in
     * after this redirect.
     *
     * Tests must call this again with the real path before finishing, so the singleton is
     * left where the app and other tests expect it - matching
     * [ai.rever.boss.dashboard.RecentFilesManager.resetForTesting] and
     * [ai.rever.boss.window.WindowAppearanceSettingsManager.resetForTesting].
     */
    internal fun resetForTesting(testFile: File) {
        initialLoadJob?.cancel()
        initialLoadJob = null
        recentProjectsFile = testFile
        _recentProjects.value = emptyList()
    }

    /**
     * Remove a project from the recent projects list.
     */
    fun removeRecentProject(projectPath: String) {
        val updated = _recentProjects.value.filter { it.path != projectPath }
        _recentProjects.value = updated

        // Save to disk (async)
        ioScope.launch {
            saveRecentProjects()
        }
    }

    /**
     * Update recent projects list without changing the global selected project.
     * Called by per-window project states when they select a project.
     */
    fun updateRecentProjects(project: Project) {
        applyUpdate(listOf(project))
    }

    /**
     * Apply a set of recent projects at once. `updateRecentProjects` accepts a single
     * [Project] because every public caller hands it one (a window's own selection), and
     * tests need to seed several entries in one call so the resulting file is the
     * round-trip target.
     */
    private fun applyUpdate(projects: List<Project>) {
        val now = System.currentTimeMillis()
        val stamped = projects.map { it.copy(lastOpened = now) }

        val updated = _recentProjects.value.toMutableList()
        val stampedPaths = stamped.map { it.path }.toSet()
        updated.removeAll { it.path in stampedPaths }
        updated.addAll(0, stamped)
        while (updated.size > MAX_RECENT_PROJECTS) {
            updated.removeLast()
        }

        _recentProjects.value = updated
        ioScope.launch { saveRecentProjects() }
    }

    /** Test affordance: drive [applyUpdate] from a coroutine and wait for the save to land. */
    internal suspend fun updateRecentProjectsSuspending(projects: List<Project>) {
        applyUpdate(projects)
        // The save is fire-and-forget on `ioScope`. Yield once so the launched coroutine has
        // a chance to run before the test asserts on disk.
        kotlinx.coroutines.yield()
    }

    /** Test affordance: re-run the load against the current [recentProjectsFile]. */
    internal suspend fun reload() {
        // Reset state first so a no-op merge does not leave previous values behind.
        _recentProjects.value = emptyList()
        loadRecentProjects()
    }

    private suspend fun loadRecentProjects() =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val file = recentProjectsFile
                if (file.exists()) {
                    val text = file.readText()
                    val projects = json.decodeFromString<List<Project>>(text)

                    // Filter out projects whose directories no longer exist AND normalize names
                    val validProjects =
                        projects.mapNotNull { project ->
                            val projectDir = java.io.File(project.path)
                            val exists = projectDir.exists() && projectDir.isDirectory
                            if (!exists) {
                                logger.debug(
                                    LogCategory.FILE,
                                    "Removing deleted project from recent",
                                    mapOf(
                                        "name" to project.name,
                                        "path" to project.path,
                                    ),
                                )
                                null
                            } else {
                                // Normalize the name to handle any legacy full paths
                                val normalizedName = project.path.extractFileName()
                                if (normalizedName != project.name) {
                                    logger.debug(
                                        LogCategory.FILE,
                                        "Normalizing project name",
                                        mapOf("old" to project.name, "new" to normalizedName),
                                    )
                                }
                                project.copy(name = normalizedName)
                            }
                        }

                    _recentProjects.value = validProjects
                    logger.debug(
                        LogCategory.FILE,
                        "Loaded recent projects from disk",
                        mapOf(
                            "count" to validProjects.size,
                            "removed" to (projects.size - validProjects.size),
                        ),
                    )

                    // Save cleaned list if any projects were removed
                    if (validProjects.size < projects.size) {
                        saveRecentProjects()
                    }
                }
            } catch (e: Exception) {
                logger.warn(LogCategory.FILE, "Failed to load recent projects", error = e)
            }
        }

    private suspend fun saveRecentProjects() =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val file = recentProjectsFile
                val text = json.encodeToString(ListSerializer(Project.serializer()), _recentProjects.value)
                // Atomic: `writeText` truncates the target and then streams into it, so a crash or
                // a concurrent writer landing mid-write leaves a half-written file that the next
                // load refuses to decode - the user then loses every project. `atomicWriteText`
                // stages the bytes in a sibling temp file and moves it into place.
                file.atomicWriteText(text)
            } catch (e: Exception) {
                logger.warn(LogCategory.FILE, "Failed to save recent projects", error = e)
            }
        }
}

// Note: CodeBaseComponent has been moved to plugin-panel-codebase module
// The legacy component code has been removed - use CodeBasePanelPlugin.registerWithProviders() instead

/** Directory names never shown in file trees, hidden toggle or not. */
internal val scannerSkippedDirectoryNames = setOf("build", "node_modules")

/**
 * Shared visibility filter for the platform scanners — hoisted so the
 * desktop/android actuals can't drift apart on the filter or skip-list.
 */
internal fun isVisibleScanEntry(
    name: String,
    showHidden: Boolean,
): Boolean = (showHidden || !name.startsWith(".")) && name !in scannerSkippedDirectoryNames

// Platform-specific file scanning - uses plugin types
expect fun scanDirectory(path: String): FileNodeData?

/**
 * [scanDirectory] variant that can include hidden (dot) entries.
 * `build`/`node_modules` stay skipped regardless of the flag.
 */
expect fun scanDirectory(
    path: String,
    showHidden: Boolean,
): FileNodeData?

/**
 * IntelliJ's isAlwaysShowPlus() pattern implementation.
 * Quick check if a directory has any children without loading them all.
 * This is much faster than scanning the full directory.
 */
expect fun directoryHasChildren(path: String): Boolean

/**
 * [directoryHasChildren] variant that can also count hidden (dot) entries.
 */
expect fun directoryHasChildren(
    path: String,
    showHidden: Boolean,
): Boolean
