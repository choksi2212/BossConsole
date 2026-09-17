package ai.rever.boss.orchestrator

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID

/**
 * Manages state snapshots for process recovery.
 *
 * Layout: $dataDir/snapshots/{processId}/{timestamp}-{uuid}.snapshot
 * Optional description: $dataDir/snapshots/{processId}/{timestamp}-{uuid}.desc
 */
class SnapshotManager(
    private val dataDir: File,
) {
    private val snapshotsRoot: File =
        File(dataDir, "snapshots").also {
            it.mkdirs()
            applyPosixOwnerPermissions(it.toPath(), isDirectory = true)
        }

    private fun validateProcessId(processId: String) {
        val reserved = processId.substringBefore('.').matches(Regex("(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])"))
        val validFormat = processId.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9._-]{0,199}"))
        require(validFormat && !processId.endsWith('.') && !reserved) {
            "Invalid process ID: $processId"
        }
    }

    private fun snapshotDir(processId: String): File {
        validateProcessId(processId)
        val dir = File(snapshotsRoot, processId)
        val canonicalRoot = snapshotsRoot.canonicalFile
        val canonicalDir = dir.canonicalFile
        require(canonicalDir.toPath().startsWith(canonicalRoot.toPath())) {
            "Process directory escapes snapshots root"
        }
        if (!dir.exists()) {
            dir.mkdirs()
            applyPosixOwnerPermissions(dir.toPath(), isDirectory = true)
        }
        return dir
    }

    /** Persist [data] for [processId] and return the new snapshot ID. */
    fun save(
        processId: String,
        data: ByteArray,
        description: String = "",
    ): String {
        val id = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val dir = snapshotDir(processId)
        val snapshotFile = File(dir, "$timestamp-$id.snapshot")
        atomicWriteFile(snapshotFile, data)
        if (description.isNotBlank()) {
            val descFile = File(dir, "$timestamp-$id.desc")
            atomicWriteFile(descFile, description.toByteArray(Charsets.UTF_8))
        }
        return id
    }

    /** Return the bytes of the most recent snapshot, or null if none exist. */
    fun loadLatest(processId: String): ByteArray? {
        validateProcessId(processId)
        val dir = File(snapshotsRoot, processId)
        if (!dir.exists() || !dir.isDirectory) return null
        return dir
            .listFiles { f -> f.extension == "snapshot" && f.isFile }
            ?.maxByOrNull { it.nameWithoutExtension.substringBefore("-").toLongOrNull() ?: 0L }
            ?.readBytes()
    }

    /** List all snapshots for [processId], most recent first. */
    fun listSnapshots(processId: String): List<SnapshotInfo> {
        validateProcessId(processId)
        val dir = File(snapshotsRoot, processId)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir
            .listFiles { f -> f.extension == "snapshot" && f.isFile }
            ?.map { file ->
                val nameWithoutExt = file.nameWithoutExtension
                val dashIdx = nameWithoutExt.indexOf('-')
                val timestamp = if (dashIdx > 0) nameWithoutExt.substring(0, dashIdx).toLongOrNull() ?: 0L else 0L
                val id = if (dashIdx > 0) nameWithoutExt.substring(dashIdx + 1) else nameWithoutExt
                val descFile = File(dir, "$nameWithoutExt.desc")
                SnapshotInfo(
                    id = id,
                    processId = processId,
                    timestamp = timestamp,
                    sizeBytes = file.length(),
                    description = if (descFile.exists() && descFile.isFile) descFile.readText() else "",
                )
            }?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }

    /** Delete all but the [keepLast] most recent snapshots for [processId]. */
    fun cleanup(
        processId: String,
        keepLast: Int = 5,
    ) {
        validateProcessId(processId)
        val dir = File(snapshotsRoot, processId)
        if (!dir.exists() || !dir.isDirectory) return
        val snapshots =
            dir
                .listFiles { f -> f.extension == "snapshot" && f.isFile }
                ?.sortedByDescending { it.nameWithoutExtension.substringBefore("-").toLongOrNull() ?: 0L }
                ?: return
        snapshots.drop(keepLast).forEach { file ->
            file.delete()
            File(dir, "${file.nameWithoutExtension}.desc").takeIf { it.exists() && it.isFile }?.delete()
        }
    }

    private fun atomicWriteFile(
        target: File,
        content: ByteArray,
    ) {
        val parent = target.parentFile ?: throw IOException("Missing parent directory for $target")
        val tmp = Files.createTempFile(parent.toPath(), ".${target.name}.", ".tmp").toFile()
        try {
            applyPosixOwnerPermissions(tmp.toPath(), isDirectory = false)
            tmp.writeBytes(content)
            moveFile(tmp.toPath(), target.toPath())
        } finally {
            tmp.delete()
        }
    }

    private fun moveFile(
        source: Path,
        target: Path,
    ) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source,
                target,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun applyPosixOwnerPermissions(
        path: Path,
        isDirectory: Boolean,
    ) {
        if (!hasPosix(path)) return
        try {
            val perms =
                if (isDirectory) {
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                    )
                } else {
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                    )
                }
            Files.setPosixFilePermissions(path, perms)
        } catch (_: Exception) {
            // Best effort on POSIX filesystems that disallow permission changes
        }
    }

    private fun hasPosix(path: Path): Boolean = path.fileSystem.supportedFileAttributeViews().contains("posix")
}

data class SnapshotInfo(
    val id: String,
    val processId: String,
    val timestamp: Long,
    val sizeBytes: Long,
    val description: String,
)
