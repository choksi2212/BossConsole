package ai.rever.boss.mcp

import ai.rever.boss.plugin.logging.LogSanitizer
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Append-only persistent journal and real-time telemetry buffer for MCP tool executions.
 *
 * Saves records to `~/.boss/mcp-calls.jsonl` with size-based rotation (active file + up to 5 backups).
 * Arguments and error snippets are sanitized via [LogSanitizer] before persistence to prevent
 * credential leaks.
 *
 * Scope note: Tool discovery, RBAC permissions, and kill-switch blocks are enforced upstream
 * in tool resolution; unpermitted or unregistered tool calls are blocked before reaching
 * policy checks and the operation ledger.
 */
class McpOperationLedger(
    private val ledgerFile: File? = null,
    private val maxFileSizeBytes: Long = 10L * 1024 * 1024, // 10 MB
    private val maxBackupIndex: Int = 5,
    private val ringBufferCapacity: Int = 100,
) {
    /** The actual optional persistence destination, for operator-facing inspection. */
    val persistencePath: String? get() = ledgerFile?.absolutePath

    private val logger = BossLogger.forComponent("McpOperationLedger")
    private val writeLock = Any()
    private val json = Json { ignoreUnknownKeys = true }

    private val _recentOperations = MutableStateFlow<List<McpOperationRecord>>(emptyList())
    val recentOperations: StateFlow<List<McpOperationRecord>> = _recentOperations.asStateFlow()

    private val _totalCalls = MutableStateFlow(0L)
    val totalCalls: StateFlow<Long> = _totalCalls.asStateFlow()

    private val _totalErrors = MutableStateFlow(0L)
    val totalErrors: StateFlow<Long> = _totalErrors.asStateFlow()

    /**
     * Record a tool execution, rejection, or timeout.
     * Never throws - I/O failures are logged without disrupting tool return.
     */
    @Suppress("LongParameterList") // One complete audit record, matching the persisted schema.
    fun record(
        toolName: String,
        providerId: String,
        policyApplied: McpPolicyAction,
        approvalDisposition: McpApprovalDisposition,
        durationMs: Long,
        isError: Boolean,
        rawArgs: Map<String, Any?>,
        errorSnippet: String? = null,
    ): McpOperationRecord {
        val sanitized = sanitizeArguments(rawArgs)
        val sanitizedErrorSnippet = errorSnippet?.let { McpArgumentSanitizer.sanitizeMessage(it).take(4096) }
        val record =
            McpOperationRecord(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                toolName = toolName,
                providerId = providerId,
                policyApplied = policyApplied,
                approvalDisposition = approvalDisposition,
                durationMs = durationMs,
                isError = isError,
                sanitizedArgs = sanitized,
                errorSnippet = sanitizedErrorSnippet,
            )

        // 1. Update in-memory telemetry ring buffer
        _recentOperations.update { current ->
            (listOf(record) + current).take(ringBufferCapacity)
        }
        _totalCalls.update { it + 1 }
        if (isError) {
            _totalErrors.update { it + 1 }
        }

        // 2. Persist to file with rotation
        persistRecord(record)

        return record
    }

    @Suppress("TooGenericExceptionCaught") // Audit failure must not change the already-completed tool result.
    private fun persistRecord(record: McpOperationRecord) {
        val file = ledgerFile ?: return
        synchronized(writeLock) {
            try {
                rotateIfNeeded(file)
                file.parentFile?.mkdirs()
                file.appendText(json.encodeToString(record) + "\n")
            } catch (t: Exception) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Failed to append record to MCP operation ledger",
                    mapOf("path" to file.path, "error" to (t.message ?: t::class.simpleName)),
                )
            }
        }
    }

    // Rotation walks numbered backups under a single write lock.
    @Suppress("NestedBlockDepth", "TooGenericExceptionCaught", "ReturnCount")
    private fun rotateIfNeeded(file: File) {
        if (!file.exists() || file.length() < maxFileSizeBytes) return

        try {
            val parent = file.parentFile ?: return
            // Shift older backups: .4 -> .5, .3 -> .4, etc. Falls back to copy+delete
            // when renameTo fails (Windows holds the destination open for antivirus /
            // search indexer) so a stuck rotation does not stop the active file
            // being capped - the unbounded growth this path was meant to prevent.
            for (i in maxBackupIndex - 1 downTo 1) {
                val src = File(parent, "${file.name}.$i")
                val dst = File(parent, "${file.name}.${i + 1}")
                if (src.exists()) {
                    if (dst.exists()) dst.delete()
                    if (!rotateFile(src, dst, "rotated ledger backup")) {
                        return
                    }
                }
            }

            // Move active file to .1
            val backup1 = File(parent, "${file.name}.1")
            if (backup1.exists()) backup1.delete()
            if (!rotateFile(file, backup1, "active ledger")) {
                return
            }

            logger.info(
                LogCategory.SYSTEM,
                "Rotated MCP operation ledger file",
                mapOf("path" to file.path),
            )
        } catch (t: Exception) {
            logger.warn(
                LogCategory.SYSTEM,
                "Failed to rotate MCP operation ledger file",
                mapOf("error" to (t.message ?: t::class.simpleName)),
            )
        }
    }

    /**
     * Copy-then-delete fallback for rotation. `renameTo` returns false on Windows when
     * the destination is open by another process (antivirus, search indexer, file
     * preview); the previous code logged at DEBUG and continued, leaving the active
     * file unbounded. Copying then deleting the source works in every case because
     * copy opens the source for read and writes a fresh destination.
     *
     * Returns true when the destination now holds the source's content and the
     * source is gone; false (and warns) when neither the rename nor the copy
     * succeeded - rotation cannot proceed, so the caller bails out and the next
     * attempt gets another chance rather than half-rotating.
     */
    @Suppress("ReturnCount", "LongParameterList")
    private fun rotateFile(
        src: File,
        dst: File,
        label: String,
    ): Boolean {
        if (src.renameTo(dst)) return true
        val copied =
            runCatching {
                src.copyTo(dst, overwrite = true)
            }.getOrNull()
        if (copied != null && src.delete()) return true
        logger.warn(
            LogCategory.SYSTEM,
            "Could not rotate $label; rotation aborted to avoid unbounded growth",
            mapOf("src" to src.name, "dst" to dst.name),
        )
        return false
    }

    /**
     * Sanitizes map arguments using [McpArgumentSanitizer].
     * Avoids blind length-based string masking so that legitimate arguments
     * like long file paths, URLs, and shell commands are preserved for auditing.
     */
    private fun sanitizeArguments(rawArgs: Map<String, Any?>) = McpArgumentSanitizer.sanitize(rawArgs)
}
