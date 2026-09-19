package ai.rever.boss.ipc.services

import ai.rever.boss.ipc.auth.IpcCall
import ai.rever.boss.ipc.auth.ProcessAuthority
import ai.rever.boss.ipc.proto.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * gRPC implementation of the KernelService.
 *
 * This runs in the kernel process and handles:
 * - Child process registration
 * - Heartbeat monitoring
 * - Process status queries
 * - Shutdown requests
 */
class KernelServiceImpl(
    private val onProcessRegistered: suspend (String, ProcessManifest, String) -> Unit = { _, _, _ -> },
    private val onShutdownRequested: suspend (String, Boolean) -> Boolean = { _, _ -> true },
) : KernelServiceGrpcKt.KernelServiceCoroutineImplBase() {
    private val logger = LoggerFactory.getLogger(KernelServiceImpl::class.java)

    // Track registered processes and their IPC addresses
    private val registeredProcesses = ConcurrentHashMap<String, RegisteredProcessInfo>()

    // Heartbeat tracking
    private val lastHeartbeats = ConcurrentHashMap<String, Long>()

    override suspend fun registerProcess(request: RegisterProcessRequest): RegisterProcessResponse {
        val manifest = request.manifest
        val processId = manifest.processId
        val caller = IpcCall.requireOwnProcess(processId)
        IpcCall.requirePermission(caller.expectedAddress != null && request.ipcAddress == caller.expectedAddress)

        logger.info(
            "Process registering: id={}, type={}, name={}, ipc={}",
            processId,
            manifest.processType,
            manifest.displayName,
            request.ipcAddress,
        )

        // Notify the kernel's process registry
        try {
            onProcessRegistered(processId, manifest, request.ipcAddress)
        } catch (e: Exception) {
            logger.error("Error in process registration callback for {}", processId, e)
            return RegisterProcessResponse
                .newBuilder()
                .setSuccess(false)
                .setErrorMessage("Registration callback failed")
                .build()
        }

        IpcCall.requireOwnProcess(processId)
        registeredProcesses[processId] =
            RegisteredProcessInfo(
                manifest = manifest,
                ipcAddress = request.ipcAddress,
                registeredAt = System.currentTimeMillis(),
            )
        lastHeartbeats[processId] = System.currentTimeMillis()

        // Build service address map for the child process
        val serviceAddresses =
            registeredProcesses
                .filter { it.key != processId }
                .mapValues { it.value.ipcAddress }

        logger.info("Process registered successfully: id={}", processId)

        return RegisterProcessResponse
            .newBuilder()
            .setSuccess(true)
            .setAssignedProcessId(processId)
            .putAllServiceAddresses(serviceAddresses)
            .build()
    }

    override fun heartbeat(requests: Flow<HeartbeatPing>): Flow<HeartbeatPong> =
        flow {
            requests.collect { ping ->
                val processId = ping.processId
                IpcCall.requireOwnProcess(processId)
                lastHeartbeats[processId] = System.currentTimeMillis()

                // Update metrics if provided
                if (ping.hasMetrics()) {
                    registeredProcesses[processId]?.lastMetrics?.set(ping.metrics)
                }

                emit(
                    HeartbeatPong
                        .newBuilder()
                        .setProcessId(processId)
                        .setTimestamp(System.currentTimeMillis())
                        .setAcknowledged(true)
                        .build(),
                )
            }
        }

    override suspend fun requestShutdown(request: ShutdownRequest): ShutdownResponse {
        val processId = request.processId
        IpcCall.requireProcessControl(processId)
        logger.info("Shutdown requested for process: id={}, force={}", processId, request.force)

        val success =
            try {
                onShutdownRequested(processId, request.force)
            } catch (e: Exception) {
                logger.error("Error shutting down process {}", processId, e)
                false
            }

        if (success) {
            deregisterProcess(processId)
        }

        return ShutdownResponse
            .newBuilder()
            .setSuccess(success)
            .build()
    }

    /**
     * Deregisters a process (e.g. on crash, shutdown, or exit).
     * Removes the process from [registeredProcesses] and [lastHeartbeats] so dead processes
     * do not report RUNNING and stale ipcAddresses are not handed to new registrants (#1180).
     */
    fun deregisterProcess(processId: String) {
        registeredProcesses.remove(processId)
        lastHeartbeats.remove(processId)
        logger.info("Process deregistered: id={}", processId)
    }

    /**
     * Evicts any registered processes whose heartbeat has timed out (#1180).
     * Returns the list of evicted process IDs.
     */
    fun evictTimedOutProcesses(): List<String> {
        val evicted = mutableListOf<String>()
        for ((id, info) in registeredProcesses) {
            val intervalMs = heartbeatInterval(info)
            if (isHeartbeatTimedOut(id, intervalMs * HEARTBEAT_TIMEOUT_MULTIPLIER)) {
                deregisterProcess(id)
                evicted.add(id)
            }
        }
        return evicted
    }

    override suspend fun getProcessStatus(request: ProcessStatusRequest): ProcessStatusResponse {
        val processId = request.processId
        IpcCall.requireProcessControl(processId)
        val info =
            registeredProcesses[processId]
                ?: return ProcessStatusResponse
                    .newBuilder()
                    .setProcessId(processId)
                    .setState(ProcessState.PROCESS_STATE_STOPPED)
                    .build()

        val intervalMs = heartbeatInterval(info)
        val isTimedOut = isHeartbeatTimedOut(processId, intervalMs * HEARTBEAT_TIMEOUT_MULTIPLIER)
        val state =
            if (isTimedOut) {
                ProcessState.PROCESS_STATE_CRASHED
            } else {
                ProcessState.PROCESS_STATE_RUNNING
            }

        return ProcessStatusResponse
            .newBuilder()
            .setProcessId(processId)
            .setState(state)
            .setStartTime(info.registeredAt)
            .apply {
                info.lastMetrics.get()?.let { setMetrics(it) }
                lastHeartbeats[processId]?.let { /* timestamp tracked internally */ }
            }.build()
    }

    override suspend fun listProcesses(request: Empty): ListProcessesResponse {
        val caller = IpcCall.current()
        val statuses =
            registeredProcesses
                .filterKeys {
                    it == caller.processId || caller.authority != ProcessAuthority.PROCESS
                }.map { (id, info) ->
                    val intervalMs = heartbeatInterval(info)
                    val isTimedOut = isHeartbeatTimedOut(id, intervalMs * HEARTBEAT_TIMEOUT_MULTIPLIER)
                    val state =
                        if (isTimedOut) {
                            ProcessState.PROCESS_STATE_CRASHED
                        } else {
                            ProcessState.PROCESS_STATE_RUNNING
                        }
                    ProcessStatusResponse
                        .newBuilder()
                        .setProcessId(id)
                        .setState(state)
                        .setStartTime(info.registeredAt)
                        .apply { info.lastMetrics.get()?.let { setMetrics(it) } }
                        .build()
                }

        return ListProcessesResponse
            .newBuilder()
            .addAllProcesses(statuses)
            .build()
    }

    /**
     * Get the last heartbeat timestamp for a process.
     * Returns null if the process has never sent a heartbeat.
     */
    fun getLastHeartbeat(processId: String): Long? = lastHeartbeats[processId]

    /**
     * Check if a process has timed out (no heartbeat within threshold).
     */
    fun isHeartbeatTimedOut(
        processId: String,
        thresholdMs: Long,
    ): Boolean {
        val lastBeat = lastHeartbeats[processId] ?: return true
        return System.currentTimeMillis() - lastBeat > thresholdMs
    }

    /**
     * Get count of registered processes.
     */
    val registeredCount: Int get() = registeredProcesses.size

    private fun heartbeatInterval(info: RegisteredProcessInfo): Long {
        val interval = info.manifest.healthContract.heartbeatIntervalMs
        return if (interval > 0) interval else DEFAULT_HEARTBEAT_INTERVAL_MS
    }

    companion object {
        const val DEFAULT_HEARTBEAT_INTERVAL_MS = 5_000L
        const val HEARTBEAT_TIMEOUT_MULTIPLIER = 3
    }
}

internal data class RegisteredProcessInfo(
    val manifest: ProcessManifest,
    val ipcAddress: String,
    val registeredAt: Long,
    val lastMetrics: AtomicReference<ProcessHealthMetrics?> = AtomicReference(null),
)
