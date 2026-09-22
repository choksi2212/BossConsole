package ai.rever.boss.plugin.updater

import ai.rever.boss.plugin.repository.PluginInfo
import ai.rever.boss.plugin.repository.PluginRepository
import ai.rever.boss.plugin.repository.PluginRepositoryManager
import ai.rever.boss.plugin.repository.PluginSearchFilter
import ai.rever.boss.plugin.repository.PluginSearchResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `updateAll` must run the same identity gate as `updatePlugin` (BossConsole#927).
 *
 * `updateAll` is a convenience over `updatePlugin` and historically called it without a
 * `verifyDownload` lambda, which left the security boundary fail-open: a sweep that bundled a
 * mismatched jar would unload every running instance before `installPlugin` ever read the
 * manifest. Threading the verifier through `updateAll` closes that.
 *
 * The focused test below proves the boundary: a rejecting verifier refuses the entire sweep
 * without ever invoking `unloadPlugin` or `loadPlugin`. A second test confirms the verifier
 * is threaded per plugin and a passing verifier lets every swap complete.
 */
class PluginUpdateManagerUpdateAllGateTest {
    private val pluginA = "ai.rever.boss.plugin.dynamic.a"
    private val pluginB = "ai.rever.boss.plugin.dynamic.b"

    private fun candidate(id: String) =
        PluginInfo(
            pluginId = id,
            displayName = id,
            version = "2.0.0",
        )

    private fun manager(): PluginUpdateManager {
        val repos =
            PluginRepositoryManager().apply {
                addRepository(FakeMultiPluginRepository(listOf(candidate(pluginA), candidate(pluginB))))
            }
        return PluginUpdateManager(repositoryManager = repos, hostBossVersion = "9.9.9")
    }

    @Test
    fun `updateAll with a rejecting verifier refuses every swap without unloading anything`() =
        runTest {
            val mgr = manager()
            mgr.checkForUpdates(mapOf(pluginA to "1.0.0", pluginB to "1.0.0"))

            val unloadCalls = mutableListOf<String>()
            val loadCalls = mutableListOf<String>()

            val results =
                mgr.updateAll(
                    downloadDir = "/tmp",
                    unloadPlugin = { pluginId ->
                        unloadCalls += pluginId
                        Result.success(Unit)
                    },
                    loadPlugin = { pluginId ->
                        loadCalls += pluginId
                        Result.success(Unit)
                    },
                    verifyDownload = {
                        Result.failure(IllegalStateException("identity mismatch"))
                    },
                )

            assertTrue(
                results[pluginA]?.isFailure == true,
                "plugin A's update must fail when the verifier rejects",
            )
            assertTrue(
                results[pluginB]?.isFailure == true,
                "plugin B's update must fail when the verifier rejects",
            )
            assertEquals(
                "identity mismatch",
                results[pluginA]?.exceptionOrNull()?.message,
            )
            assertEquals(
                "identity mismatch",
                results[pluginB]?.exceptionOrNull()?.message,
            )
            assertTrue(
                unloadCalls.isEmpty(),
                "the running instances must not be unloaded when verifyDownload rejects - " +
                    "unloadPlugin was called for: $unloadCalls",
            )
            assertTrue(
                loadCalls.isEmpty(),
                "the new jars must not be loaded when verifyDownload rejects - " +
                    "loadPlugin was called for: $loadCalls",
            )
        }

    @Test
    fun `updateAll threads the verifier through every per-plugin swap`() =
        runTest {
            val mgr = manager()
            mgr.checkForUpdates(mapOf(pluginA to "1.0.0", pluginB to "1.0.0"))

            val unloadCalls = mutableListOf<String>()
            val loadCalls = mutableListOf<String>()
            val verifyCalls = mutableListOf<String>()

            val results =
                mgr.updateAll(
                    downloadDir = "/tmp",
                    unloadPlugin = { pluginId ->
                        unloadCalls += pluginId
                        Result.success(Unit)
                    },
                    loadPlugin = { pluginId ->
                        loadCalls += pluginId
                        Result.success(Unit)
                    },
                    verifyDownload = { downloadedPath ->
                        verifyCalls += downloadedPath
                        Result.success(Unit)
                    },
                )

            assertTrue(results[pluginA]?.isSuccess == true)
            assertTrue(results[pluginB]?.isSuccess == true)
            assertEquals(listOf(pluginA, pluginB).toSet(), unloadCalls.toSet())
            assertEquals(listOf(pluginA, pluginB).toSet(), loadCalls.toSet())
            // The verifier was called once per plugin update - one path per download.
            assertEquals(2, verifyCalls.size, "verifyDownload must run once per plugin in the sweep")
        }
}

/**
 * Minimal remote [PluginRepository] that resolves to a fixed list of plugins.
 *
 * Used only by the gate test above, which needs two plugins to prove the gate fires once per
 * plugin in a sweep and refuses the whole batch on a single rejection.
 */
private class FakeMultiPluginRepository(
    private val plugins: List<PluginInfo>,
    override val id: String = "fake-multi",
    override val name: String = "Fake Multi",
) : PluginRepository {
    override val isLocal = false
    override val isAvailable = true

    override suspend fun listPlugins(): Result<List<PluginInfo>> = Result.success(plugins)

    override suspend fun searchPlugins(filter: PluginSearchFilter): Result<PluginSearchResult> =
        Result.success(PluginSearchResult(plugins, totalCount = plugins.size))

    override suspend fun getPlugin(pluginId: String): Result<PluginInfo?> =
        Result.success(plugins.firstOrNull { it.pluginId == pluginId })

    override suspend fun getPluginVersions(pluginId: String): Result<List<PluginInfo>> =
        Result.success(plugins.filter { it.pluginId == pluginId })

    override suspend fun downloadPlugin(
        pluginId: String,
        version: String?,
        targetPath: String,
        onProgress: ((Float) -> Unit)?,
    ): Result<String> = Result.success(targetPath)

    override fun getDownloadProgress(pluginId: String): Flow<Float>? = null

    override suspend fun refresh(): Result<Unit> = Result.success(Unit)
}
