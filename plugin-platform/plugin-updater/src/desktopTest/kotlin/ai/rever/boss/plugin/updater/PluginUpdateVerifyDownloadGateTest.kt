package ai.rever.boss.plugin.updater

import ai.rever.boss.plugin.repository.PluginInfo
import ai.rever.boss.plugin.repository.PluginRepositoryManager
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A `verifyDownload` lambda must gate `updatePlugin` BEFORE `swapPlugin` runs.
 *
 * Reopens #927: the host's Update path used to swap the downloaded jar into place and only
 * then learn the jar declared a different pluginId (or `ai.rever.boss.plugin.api`, which
 * `installPlugin` routes into `hotSwapApiLayer` - a process-wide unload-all / swap / reload-all).
 * The verify step runs after the download and before `swapPlugin`, so a failure here means the
 * running instance is never unloaded.
 *
 * The default lambda is a no-op so callers that do not need the gate are unchanged - that path
 * is covered by every existing test, and these tests only assert the new contract.
 */
class PluginUpdateVerifyDownloadGateTest {
    private val pluginId = "ai.rever.boss.plugin.dynamic.demo"

    private fun candidate() =
        PluginInfo(
            pluginId = pluginId,
            displayName = "Demo",
            version = "2.0.0",
        )

    private fun manager(): PluginUpdateManager {
        val repos =
            PluginRepositoryManager().apply {
                addRepository(FakeSingleVersionRepository(candidate()))
            }
        return PluginUpdateManager(repositoryManager = repos, hostBossVersion = "9.9.9")
    }

    @Test
    fun `a verifyDownload failure returns the update as failed without unloading`() =
        runTest {
            val mgr = manager()
            mgr.checkForUpdates(mapOf(pluginId to "1.0.0"))

            var unloadCalled = false
            var loadCalled = false
            val result =
                mgr.updatePlugin(
                    pluginId = pluginId,
                    downloadPath = "/tmp/does-not-matter.jar",
                    unloadPlugin = {
                        unloadCalled = true
                        Result.success(Unit)
                    },
                    loadPlugin = {
                        loadCalled = true
                        Result.success(Unit)
                    },
                    verifyDownload = {
                        Result.failure(IllegalStateException("identity mismatch"))
                    },
                )

            assertTrue(result.isFailure, "a verifyDownload failure must surface as a failed update")
            assertFalse(unloadCalled, "the running instance must not be unloaded when verifyDownload fails")
            assertFalse(loadCalled, "the new jar must not be loaded when verifyDownload fails")
            assertEquals("identity mismatch", result.exceptionOrNull()?.message)
        }

    @Test
    fun `a verifyDownload success proceeds to unload and load`() =
        runTest {
            val mgr = manager()
            mgr.checkForUpdates(mapOf(pluginId to "1.0.0"))

            var unloadCalled = false
            var loadCalled = false
            val verifyCalledWith = mutableListOf<String>()
            val result =
                mgr.updatePlugin(
                    pluginId = pluginId,
                    downloadPath = "/tmp/does-not-matter.jar",
                    unloadPlugin = {
                        unloadCalled = true
                        Result.success(Unit)
                    },
                    loadPlugin = {
                        loadCalled = true
                        Result.success(Unit)
                    },
                    verifyDownload = { downloadedPath ->
                        verifyCalledWith += downloadedPath
                        Result.success(Unit)
                    },
                )

            assertTrue(result.isSuccess, "a passing verifyDownload must let the swap complete")
            assertTrue(unloadCalled, "the swap must run after a passing verifyDownload")
            assertTrue(loadCalled, "the load must run after a passing verifyDownload")
            assertEquals(listOf("/tmp/does-not-matter.jar"), verifyCalledWith)
        }

    @Test
    fun `the default verifyDownload is a no-op so existing callers are unchanged`() =
        runTest {
            val mgr = manager()
            mgr.checkForUpdates(mapOf(pluginId to "1.0.0"))

            var unloadCalled = false
            var loadCalled = false
            val result =
                mgr.updatePlugin(
                    pluginId = pluginId,
                    downloadPath = "/tmp/does-not-matter.jar",
                    unloadPlugin = {
                        unloadCalled = true
                        Result.success(Unit)
                    },
                    loadPlugin = {
                        loadCalled = true
                        Result.success(Unit)
                    },
                )

            assertTrue(result.isSuccess, "the default verifyDownload must not block a passing update")
            assertTrue(unloadCalled)
            assertTrue(loadCalled)
        }

    @Test
    fun `a verifyDownload failure is announced via onUpdateFailed`() =
        runTest {
            val mgr = manager()
            mgr.checkForUpdates(mapOf(pluginId to "1.0.0"))

            val failures = mutableListOf<String>()
            mgr.addListener(
                object : UpdateListener {
                    override fun onUpdateFailed(
                        pluginId: String,
                        error: String,
                    ) {
                        failures += error
                    }
                },
            )

            val result =
                mgr.updatePlugin(
                    pluginId = pluginId,
                    downloadPath = "/tmp/does-not-matter.jar",
                    unloadPlugin = { Result.success(Unit) },
                    loadPlugin = { Result.success(Unit) },
                    verifyDownload = { Result.failure(IllegalStateException("identity mismatch")) },
                )

            assertTrue(result.isFailure)
            assertEquals(listOf("identity mismatch"), failures, "the user must hear why the update failed")
        }
}
