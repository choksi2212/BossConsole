package ai.rever.boss.plugin.repository.remote

import ai.rever.boss.plugin.loader.FileHashing
import ai.rever.boss.plugin.loader.PluginSignatureEnforcement
import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import ai.rever.boss.plugin.loader.PluginSignatureVerifier
import ai.rever.boss.plugin.loader.PluginStoreTrust
import ai.rever.boss.plugin.loader.SignatureVerificationResult
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.boss.plugin.repository.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository that connects to the remote plugin store (Supabase Edge Function).
 *
 * Features:
 * - List and search plugins from the remote store
 * - Download plugins with progress tracking
 * - SHA-256 verification of downloaded JARs
 * - Local caching of downloaded JARs
 *
 * @param downloadCache Cache for downloaded plugin JARs
 */
class RemotePluginRepository(
    private val downloadCache: PluginDownloadCache = PluginDownloadCache(),
    private val storeVerifier: PluginSignatureVerifier = PluginSignatureVerifier(PluginStoreTrust.TRUSTED_KEYS),
    // Injectable for tests so the downloadPlugin wiring (verify on cache
    // hits, verify-before-cache on fresh downloads) is exercisable without
    // the real store.
    private val downloadInfoProvider: suspend (pluginId: String, version: String?) -> DownloadInfoResponse = { pluginId, version ->
        if (version != null) {
            PluginStoreClient.getDownloadUrl(pluginId, version)
        } else {
            PluginStoreClient.getDownloadUrl(pluginId)
        }
    },
    private val copyCachedJar: (File, File) -> Unit = { source, target ->
        source.copyTo(target, overwrite = true)
        Unit
    },
) : PluginRepository {
    private val logger = BossLogger.forComponent("RemotePluginRepository")

    // Cache availability must never decide whether a verified download succeeds.
    private fun <T> cacheOrNull(
        operation: String,
        action: () -> T,
    ): T? =
        try {
            action()
        } catch (cancelled: CancellationException) {
            // CancellationException extends IllegalStateException; it must be handled before cache refusals.
            throw cancelled
        } catch (failure: java.io.IOException) {
            cacheUnavailable(operation, failure)
        } catch (failure: IllegalStateException) {
            cacheUnavailable(operation, failure)
        } catch (failure: SecurityException) {
            cacheUnavailable(operation, failure)
        } catch (failure: IllegalArgumentException) {
            cacheUnavailable(operation, failure)
        }

    private fun cacheUnavailable(
        operation: String,
        failure: Exception,
    ): Nothing? {
        logger.warn(
            LogCategory.SYSTEM,
            "Plugin cache unavailable; continuing without cache",
            mapOf("operation" to operation, "failureType" to failure.javaClass.simpleName),
        )
        return null
    }

    /**
     * Enforce the store's anchor signature for a JAR whose SHA-256 has
     * already been verified to equal [sha256]. The signature must cover the
     * canonical anchor `pluginId|version|sha256` — binding identity and
     * version so that one legitimately signed store artifact can't be
     * substituted for another by rewriting the version row.
     *
     * When the caller requested a specific version, [requestedVersion] must
     * also equal the store-reported [versionLabel] — a mismatch fails before
     * any signature math. Precision on the guarantee: downgrade protection
     * therefore holds for EXPLICIT-version installs only. A "latest" install
     * ([requestedVersion] = null) has no independent notion of what the
     * newest version should be — a DB-write attacker who bumps an older,
     * legitimately signed version's published_at can serve it as latest and
     * its anchor verifies fine. Accepted residual risk under the same
     * DB-write threat model tracked in BossConsole#102.
     *
     * A rejected artifact triggers [onVerificationFailure] (cleanup: delete
     * the downloaded file, purge the cache entry, …) before throwing; a
     * missing signature currently warns and allows (rollout phase — flips to
     * hard-fail once the store backfill is complete and enforcement is
     * enabled).
     */
    internal fun enforceStoreSignature(
        sha256: String,
        signature: String?,
        pluginId: String,
        versionLabel: String,
        requestedVersion: String?,
        onVerificationFailure: () -> Unit,
    ) {
        if (requestedVersion != null && requestedVersion != versionLabel) {
            onVerificationFailure()
            throw DownloadException(
                "Store returned version $versionLabel but $requestedVersion was requested",
                pluginId,
                id,
            )
        }
        if (signature == null) {
            if (PluginSignatureEnforcement.enforceUnsigned) {
                onVerificationFailure()
                throw DownloadException(
                    "Store plugin has no signature and signature enforcement is enabled",
                    pluginId,
                    id,
                )
            }
            logger.warn(
                LogCategory.NETWORK,
                "Store plugin is unsigned - allowing for now, will be rejected once signature enforcement is enabled",
                mapOf(
                    "pluginId" to pluginId,
                    "version" to versionLabel,
                ),
            )
            return
        }
        val anchor = PluginStoreTrust.versionAnchor(pluginId, versionLabel, sha256)
        val result = storeVerifier.verifySignedMessage(anchor, signature)
        if (!result.isVerified) {
            onVerificationFailure()
            val failure = result as? SignatureVerificationResult.Failed
            logger.error(
                LogCategory.NETWORK,
                "Plugin signature verification failed",
                mapOf(
                    "pluginId" to pluginId,
                    "version" to versionLabel,
                ),
                failure?.error,
            )
            throw DownloadException(
                "Plugin signature verification failed: ${failure?.reason ?: "unknown"}",
                pluginId,
                id,
            )
        }
        logger.info(
            LogCategory.NETWORK,
            "Plugin signature verified",
            mapOf(
                "pluginId" to pluginId,
                "version" to versionLabel,
            ),
        )
    }

    /**
     * Delete a rejected/stale artifact, logging when the delete doesn't take
     * (e.g. a Windows file lock) so leftover artifacts are observable — the
     * install has already failed, so a survivor can't load, but it shouldn't
     * linger silently either.
     */
    private fun deleteOrWarn(
        file: File,
        context: String,
    ) {
        if (!file.delete() && file.exists()) {
            logger.warn(
                LogCategory.NETWORK,
                "Failed to delete $context - leftover file remains",
                mapOf(
                    "path" to file.absolutePath,
                ),
            )
        }
    }

    /**
     * Sibling staging file for a download that hasn't yet been verified.
     *
     * The downloader streams bytes here, hashes and signs them, and only on
     * PASS promotes the file over `targetPath` with [promoteStaged]. A failed
     * download — interrupted response, bad hash, bad signature — therefore
     * leaves the existing live JAR and its `.sig` sidecar untouched.
     *
     * The path lives in the same directory as `targetPath` so `Files.move`
     * stays within one filesystem (the only case its `ATOMIC_MOVE` is
     * guaranteed). The filename is unique per call so two concurrent
     * downloads of the same plugin do not stomp each other. The `.part`
     * suffix deliberately does NOT end in `.jar`, so a `*.part` left over
     * from a kill is ignored by the directory scan at startup.
     */
    private fun stagedSibling(targetPath: String): File {
        val target = File(targetPath)
        val parent = target.absoluteFile.parentFile
        // createTempFile produces `<name><random>.<suffix>`. The random tail
        // is enough to keep two concurrent callers on the same targetPath
        // from sharing bytes; the parent directory is taken off the
        // targetPath so a symlink at targetPath does not push staging bytes
        // outside the intended plugin directory.
        return Files.createTempFile(parent.toPath(), target.name, ".part").toFile()
    }

    /**
     * Atomically replace `targetPath` with [staged], falling back to a
     * non-atomic move on filesystems that do not support atomic rename
     * (some Windows configurations).
     *
     * `Files.move` with `REPLACE_EXISTING` replaces the entry at the target
     * path; a symlink at that path is unlinked and a regular file takes its
     * place, so unverified bytes cannot be written outside the plugin
     * directory by following a symlink.
     */
    private fun promoteStaged(
        staged: File,
        targetPath: String,
    ) {
        val target = File(targetPath)
        try {
            Files.move(
                staged.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Best-effort cleanup of [staged]. Used on every failure path so a
     * rejected download does not leave half-written bytes at a name that
     * would silently be ignored by the directory scan - the same
     * `deleteOrWarn` shape that the rest of this class uses.
     */
    private fun discardStaged(
        staged: File,
        context: String,
    ) {
        deleteOrWarn(staged, context)
    }

    private val downloadHttpClient =
        HttpClient(CIO) {
            engine {
                requestTimeout = 300_000 // 5 minutes for large downloads
            }
        }

    /**
     * Cached plugin list from last refresh.
     */
    private var cachedPlugins: List<PluginInfo> = emptyList()

    /**
     * Active download progress flows by plugin ID.
     */
    private val downloadProgress = ConcurrentHashMap<String, MutableStateFlow<Float>>()

    override val id: String = "supabase-store"
    override val name: String = "BOSS Plugin Store"
    override val isLocal: Boolean = false

    override val isAvailable: Boolean
        get() = PluginStoreConfig.isInitialized && checkAvailability()

    private fun checkAvailability(): Boolean {
        // Check if config is initialized - actual health check is async
        return PluginStoreConfig.isInitialized
    }

    override suspend fun listPlugins(): Result<List<PluginInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!PluginStoreConfig.isInitialized) {
                    logger.warn(LogCategory.NETWORK, "Plugin store not initialized")
                    return@runCatching emptyList()
                }

                val response =
                    PluginStoreClient.listPlugins(
                        page = 1,
                        pageSize = 100, // Get first 100 plugins
                        sortBy = "downloads",
                    )

                val plugins = response.plugins.map { it.toPluginInfo() }
                cachedPlugins = plugins

                logger.info(
                    LogCategory.NETWORK,
                    "Listed remote plugins",
                    mapOf(
                        "count" to plugins.size,
                        "totalCount" to response.totalCount,
                    ),
                )

                plugins
            }.onStoreFailure { e ->
                logger.error(LogCategory.NETWORK, "Failed to list remote plugins", error = e)
            }
        }

    override suspend fun searchPlugins(filter: PluginSearchFilter): Result<PluginSearchResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!PluginStoreConfig.isInitialized) {
                    return@runCatching PluginSearchResult(
                        plugins = emptyList(),
                        totalCount = 0,
                        page = filter.page,
                        pageSize = filter.pageSize,
                    )
                }

                val response = PluginStoreClient.searchPlugins(filter)
                val plugins = response.plugins.map { it.toPluginInfo() }

                logger.debug(
                    LogCategory.NETWORK,
                    "Searched remote plugins",
                    mapOf(
                        "query" to filter.query,
                        "resultCount" to plugins.size,
                        "totalCount" to response.totalCount,
                    ),
                )

                PluginSearchResult(
                    plugins = plugins,
                    totalCount = response.totalCount,
                    page = response.page,
                    pageSize = response.pageSize,
                )
            }.onStoreFailure { e ->
                logger.error(LogCategory.NETWORK, "Failed to search remote plugins", error = e)
            }
        }

    override suspend fun getPlugin(pluginId: String): Result<PluginInfo?> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!PluginStoreConfig.isInitialized) {
                    return@runCatching null
                }

                val response = PluginStoreClient.getPlugin(pluginId)
                response?.toPluginInfo()
            }.onStoreFailure { e ->
                logger.error(LogCategory.NETWORK, "Failed to get remote plugin", mapOf("pluginId" to pluginId), e)
            }
        }

    override suspend fun getPluginVersions(pluginId: String): Result<List<PluginInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!PluginStoreConfig.isInitialized) {
                    return@runCatching emptyList()
                }

                val response =
                    PluginStoreClient.getPlugin(pluginId)
                        ?: return@runCatching emptyList()

                // Convert each version to PluginInfo
                response.versions.map { version ->
                    PluginInfo(
                        pluginId = response.pluginId,
                        displayName = response.displayName,
                        version = version.version,
                        description = response.description,
                        author = response.authorName,
                        url = response.homepageUrl,
                        type = parsePluginType(response.type),
                        apiVersion = response.apiVersion,
                        minBossVersion = version.minBossVersion,
                        minIpcVersion = version.minIpcVersion,
                        size = version.jarSize,
                        sha256 = version.sha256,
                        dependencies = version.dependencies.map { it.pluginId },
                        changelog = version.changelog,
                        verified = response.verified,
                    )
                }
            }.onStoreFailure { e ->
                logger.error(LogCategory.NETWORK, "Failed to get plugin versions", mapOf("pluginId" to pluginId), e)
            }
        }

    override suspend fun downloadPlugin(
        pluginId: String,
        version: String?,
        targetPath: String,
        onProgress: ((Float) -> Unit)?,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!PluginStoreConfig.isInitialized) {
                    throw DownloadException("Plugin store not initialized", pluginId, id)
                }

                // Get download info
                val downloadInfo = downloadInfoProvider(pluginId, version)

                // Check cache first. getCachedJar only returns a file whose
                // SHA-256 equals downloadInfo.sha256, so the signature check
                // below binds the cached bytes to the store key too — a JAR
                // cached during the warn-and-allow window doesn't dodge
                // enforcement through the cache path.
                val cachedFile =
                    cacheOrNull("lookup") {
                        downloadCache.getCachedJar(pluginId, downloadInfo.version, downloadInfo.sha256)
                    }
                if (cachedFile != null) {
                    logger.info(
                        LogCategory.NETWORK,
                        "Using cached JAR",
                        mapOf(
                            "pluginId" to pluginId,
                            "version" to downloadInfo.version,
                        ),
                    )
                    // Verify BEFORE copying so a rejected artifact never lands at
                    // targetPath; on failure the poisoned entry is purged through
                    // the cache's own API.
                    enforceStoreSignature(
                        sha256 = downloadInfo.sha256,
                        signature = downloadInfo.signature,
                        pluginId = pluginId,
                        versionLabel = downloadInfo.version,
                        requestedVersion = version,
                        onVerificationFailure = {
                            cacheOrNull("purge") { downloadCache.removeCachedJar(pluginId, downloadInfo.version) }
                        },
                    )
                    // Copy into a sibling `.part` rather than truncating
                    // targetPath in place: a copy or promotion failure must
                    // leave the previously installed JAR and its `.sig`
                    // sidecar untouched. The signature has already been
                    // verified above, so the promote is the only step that
                    // can fail here. A cache copy that returns false falls
                    // through to the fresh-download path, matching the
                    // pre-fix behaviour where a cache write failure
                    // transparently retried over the network.
                    val staged = stagedSibling(targetPath)
                    val cacheCopySucceeded =
                        try {
                            val copied =
                                cacheOrNull("copy") {
                                    copyCachedJar(cachedFile, staged)
                                    true
                                } == true
                            if (copied) {
                                try {
                                    promoteStaged(staged, targetPath)
                                    true
                                } catch (t: Throwable) {
                                    discardStaged(staged, "failed cache promote")
                                    throw t
                                }
                            } else {
                                false
                            }
                        } finally {
                            if (staged.exists()) discardStaged(staged, "leftover cache stage")
                        }
                    if (cacheCopySucceeded) {
                        PluginSignatureSidecar.persist(targetPath, downloadInfo.signature)
                        // Nothing was fetched, but the caller still needs completed progress.
                        onProgress?.invoke(1f)
                        return@runCatching targetPath
                    }
                }

                // Initialize progress tracking
                val progressFlow = MutableStateFlow(0f)
                downloadProgress[pluginId] = progressFlow

                try {
                    // Stream into a sibling `.part` so an interrupted or hostile
                    // response never overwrites the live JAR. Promotion to
                    // targetPath happens only after the bytes have been hashed
                    // and the signature has been verified against the store key.
                    //
                    // Allocated INSIDE the cleanup scope so a failing
                    // Files.createTempFile still clears the progress flow -
                    // otherwise the entry keyed by pluginId would be left
                    // pointing at a flow nothing else would remove.
                    val staged = stagedSibling(targetPath)
                    try {
                        logger.info(
                            LogCategory.NETWORK,
                            "Downloading plugin",
                            mapOf(
                                "pluginId" to pluginId,
                                "version" to downloadInfo.version,
                                "size" to downloadInfo.size,
                            ),
                        )

                        // Download with progress tracking into the staged file.
                        downloadHttpClient.prepareGet(downloadInfo.downloadUrl).execute { response ->
                            val channel = response.bodyAsChannel()
                            val totalBytes = response.headers[io.ktor.http.HttpHeaders.ContentLength]?.toLongOrNull() ?: downloadInfo.size
                            var downloadedBytes = 0L
                            // The callback drives UI state that is copied on every write,
                            // and an 8KB buffer means thousands of writes for one jar - so
                            // it fires on whole-percent steps only. The flow keeps its
                            // per-chunk resolution, which nothing re-renders.
                            var lastPercent = -1

                            staged.outputStream().use { output ->
                                val buffer = ByteArray(8192)
                                while (!channel.isClosedForRead) {
                                    val bytes = channel.readAvailable(buffer)
                                    if (bytes > 0) {
                                        output.write(buffer, 0, bytes)
                                        downloadedBytes += bytes
                                        if (totalBytes > 0) {
                                            val fraction = downloadedBytes.toFloat() / totalBytes
                                            progressFlow.value = fraction
                                            val percent = ((downloadedBytes * 100) / totalBytes).toInt()
                                            if (percent != lastPercent) {
                                                lastPercent = percent
                                                onProgress?.invoke(fraction)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Verify SHA-256 — every published version must have a real
                        // hash. A blank or placeholder value is treated as a mismatch
                        // so tampered or unhashed JARs never load. The hash runs on
                        // the staged file; the live JAR is still untouched.
                        val actualSha256 = FileHashing.sha256(staged)
                        if (!actualSha256.equals(downloadInfo.sha256, ignoreCase = true)) {
                            discardStaged(staged, "hash-mismatched download")
                            throw DownloadException(
                                "SHA-256 mismatch. Expected: ${downloadInfo.sha256}, Got: $actualSha256",
                                pluginId,
                                id,
                            )
                        }

                        // Verify the store's signature over that hash. The checksum
                        // above binds the local bytes to the hash; the signature binds
                        // the hash to the store's signing key, so a rewritten DB row
                        // or storage object can't smuggle a different JAR through.
                        enforceStoreSignature(
                            sha256 = actualSha256,
                            signature = downloadInfo.signature,
                            pluginId = pluginId,
                            versionLabel = downloadInfo.version,
                            requestedVersion = version,
                            onVerificationFailure = { discardStaged(staged, "rejected download") },
                        )

                        // Atomically replace the live JAR. Files.move does NOT
                        // follow a symlink at targetPath, so an attacker who
                        // placed one there cannot use the download to overwrite
                        // an arbitrary file outside the plugin directory.
                        try {
                            promoteStaged(staged, targetPath)
                        } catch (t: Throwable) {
                            discardStaged(staged, "failed promote")
                            throw t
                        }

                        // Persist the signature beside the JAR so load-time
                        // verification (which every install path funnels through) can
                        // re-check it independently of this download path. Only
                        // happens after the bytes are in their final position.
                        PluginSignatureSidecar.persist(targetPath, downloadInfo.signature)

                        // Cache the downloaded JAR
                        cacheOrNull("write") { downloadCache.cacheJar(pluginId, downloadInfo.version, File(targetPath)) }

                        progressFlow.value = 1f
                        onProgress?.invoke(1f)

                        logger.info(
                            LogCategory.NETWORK,
                            "Plugin downloaded successfully",
                            mapOf(
                                "pluginId" to pluginId,
                                "version" to downloadInfo.version,
                                "path" to targetPath,
                            ),
                        )

                        targetPath
                    } finally {
                        // After promoteStaged the file no longer exists; on every
                        // other exit path the staged file is what would otherwise
                        // linger in the plugin directory.
                        if (staged.exists()) discardStaged(staged, "stale download stage")
                    }
                } finally {
                    // Two-arg remove: if a concurrent download of another version
                    // of the same plugin replaced our entry (progress is keyed by
                    // pluginId alone — pre-existing), don't yank its flow out.
                    downloadProgress.remove(pluginId, progressFlow)
                }
            }.onStoreFailure { e ->
                logger.error(LogCategory.NETWORK, "Failed to download plugin", mapOf("pluginId" to pluginId), e)
            }
        }

    override fun getDownloadProgress(pluginId: String): Flow<Float>? = downloadProgress[pluginId]?.asStateFlow()

    override suspend fun refresh(): Result<Unit> = listPlugins().map { }

    /**
     * Rate a plugin in the remote store.
     *
     * Requires authentication (access token set in PluginStoreConfig).
     *
     * @param pluginId The plugin ID to rate
     * @param rating Rating from 1-5
     * @param review Optional review text
     * @return Result indicating success or failure
     */
    suspend fun ratePlugin(
        pluginId: String,
        rating: Int,
        review: String = "",
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = PluginStoreClient.ratePlugin(pluginId, rating, review)
                if (!response.success) {
                    throw PluginStoreException(response.error ?: "Failed to rate plugin")
                }
                logger.info(
                    LogCategory.NETWORK,
                    "Plugin rated",
                    mapOf(
                        "pluginId" to pluginId,
                        "rating" to rating,
                    ),
                )
            }.onStoreFailure { e ->
                logger.error(LogCategory.NETWORK, "Failed to rate plugin", mapOf("pluginId" to pluginId), e)
            }
        }

    /**
     * Check if the remote store is healthy.
     */
    suspend fun checkHealth(): Boolean =
        withContext(Dispatchers.IO) {
            if (!PluginStoreConfig.isInitialized) return@withContext false
            PluginStoreClient.checkHealth()
        }

    // ============================================================================
    // Helper Functions
    // ============================================================================

    private fun parsePluginType(type: String): ai.rever.boss.plugin.api.PluginType =
        when (type.lowercase()) {
            "tab" -> ai.rever.boss.plugin.api.PluginType.TAB
            "hybrid", "mixed" -> ai.rever.boss.plugin.api.PluginType.MIXED
            else -> ai.rever.boss.plugin.api.PluginType.PANEL
        }

    /**
     * `onFailure` for a store call, with one rule the file used to state only on [downloadPlugin]:
     * a caller's cancellation is not a network failure and must not arrive as one.
     *
     * `runCatching` catches Throwable, so a cancelled request used to come back as `Result.failure`
     * with a `CancellationException` inside, [handler] logged it at ERROR as a fault that never
     * happened, and the caller saw a failed lookup rather than its own cancellation. Dismissing the
     * dependency dialog while the store was slow produced one such ERROR per in-flight lookup; with
     * a host log file those lines now survive the process, so a reader would go hunting a store
     * outage that did not occur. Rethrowing here lets the cancellation reach the caller's own
     * handler, which is what `withContext` would have done had nothing caught it.
     */
    private inline fun <T> Result<T>.onStoreFailure(handler: (Throwable) -> Unit): Result<T> =
        onFailure { e ->
            if (e is CancellationException) throw e
            handler(e)
        }
}
