package ai.rever.boss.cli

import ai.rever.boss.plugin.launchpad.DevPluginArtifacts
import ai.rever.boss.plugin.launchpad.PluginManifest
import ai.rever.boss.plugin.launchpad.PluginPermission
import ai.rever.boss.plugin.launchpad.PluginScaffolder
import ai.rever.boss.plugin.launchpad.PluginValidator
import ai.rever.boss.plugin.launchpad.launchpadJson
import ai.rever.boss.utils.ReloadResult
import ai.rever.boss.utils.SingleInstanceManager
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile

/**
 * Scaffolds a new third-party plugin project.
 * Usage: boss plugin init <name> [--template <type>] [--dir <path>] [--force] [--json]
 */
@Suppress("TooGenericExceptionCaught")
class BossPluginInitCommand : CliktCommand(name = "init") {
    override fun help(context: Context) = "Scaffolds a new plugin project"

    private val logger = BossLogger.forComponent("BossPluginInitCommand")

    val name by argument(help = "Plugin name (e.g. 'my-tools')")
    val template by option(
        "-t",
        "--template",
        help = "Plugin template: mcp-tool, ui-panel, background-service, full (default: mcp-tool)",
    ).default("mcp-tool")
    val dir by option("-d", "--dir", help = "Target directory path")
    val force by option("-f", "--force", help = "Purge and overwrite existing target directory").flag(default = false)
    val json by option("--json", help = "Output machine-readable JSON").flag(default = false)

    override fun run() {
        val targetDir = if (dir != null) File(dir!!).absoluteFile else File(name).absoluteFile

        try {
            val result =
                PluginScaffolder.scaffold(
                    name = name,
                    templateName = template,
                    targetDir = targetDir,
                    force = force,
                )

            if (json) {
                val payload =
                    buildJsonObject {
                        put("status", "scaffolded")
                        put("pluginId", result.pluginId)
                        put("template", template)
                        put("targetDir", result.targetDirectory.absolutePath.replace('\\', '/'))
                        put(
                            "files",
                            buildJsonArray {
                                result.filesCreated.forEach { file ->
                                    val relPath = file.relativeTo(result.targetDirectory).path.replace('\\', '/')
                                    add(relPath)
                                }
                            },
                        )
                    }
                echo(payload.toString())
            } else {
                echo(
                    "[✓] Plugin '${result.pluginId}' scaffolded successfully at " +
                        result.targetDirectory.absolutePath,
                )
                echo("Template: $template")
                echo("Generated files:")
                result.filesCreated.forEach { file ->
                    val relPath = file.relativeTo(result.targetDirectory).path.replace('\\', '/')
                    echo("  - $relPath")
                }
            }
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Failed to scaffold plugin"
            logger.error(LogCategory.SYSTEM, "Failed to scaffold plugin: $errorMsg", error = e)
            if (json) {
                val errPayload =
                    buildJsonObject {
                        put("status", "error")
                        put("error", errorMsg)
                    }
                echo(errPayload.toString())
            } else {
                echo("Error: $errorMsg", err = true)
            }
            throw ProgramResult(1)
        }
    }
}

/**
 * Validates a plugin directory or packaged archive (.jar / .zip).
 * Usage: boss plugin validate [<path>] [--json]
 */
class BossPluginValidateCommand : CliktCommand(name = "validate") {
    override fun help(context: Context) = "Validates a plugin manifest and bytecode structure"

    private val logger = BossLogger.forComponent("BossPluginValidateCommand")

    val path by argument(help = "Path to plugin directory or JAR/ZIP archive")
        .path(canBeDir = true, canBeFile = true)
        .default(Paths.get("."))
    val json by option("--json", help = "Output machine-readable JSON").flag(default = false)

    override fun run() {
        val target = path.toFile().absoluteFile
        val result = PluginValidator.validate(target)
        val report = PluginValidator.toReport(target.absolutePath, result)

        if (json) {
            val jsonText = launchpadJson.encodeToString(report)
            echo(jsonText)
            if (!report.success) {
                throw ProgramResult(1)
            }
        } else {
            echo("Validating plugin at ${target.absolutePath}...")
            result.checks.forEach { check ->
                val mark = if (check.passed) "[✓]" else "[✗]"
                echo("$mark ${check.name}: ${check.message}")
            }
            if (report.success) {
                echo("\n[✓] Validation passed (${report.checksPassed}/${report.totalChecks} checks passed)")
            } else {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Validation failed with ${report.failures.size} errors at ${target.absolutePath}",
                )
                echo("\nValidation failed with ${report.failures.size} errors:", err = true)
                report.failures.forEach { echo("  [✗] ${it.checkName}: ${it.message}", err = true) }
                throw ProgramResult(1)
            }
        }
    }
}

/**
 * Stages and links a plugin to BossConsole and dispatches a live hot-reload signal.
 * Usage: boss plugin link [<path>] [--json]
 */
@Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "TooGenericExceptionCaught")
class BossPluginLinkCommand : CliktCommand(name = "link") {
    override fun help(context: Context) = "Links a local plugin into BossConsole development environment"

    private val logger = BossLogger.forComponent("BossPluginLinkCommand")

    val path by argument(help = "Path to plugin directory or JAR/ZIP archive")
        .path(canBeDir = true, canBeFile = true)
        .default(Paths.get("."))
    val json by option("--json", help = "Output machine-readable JSON").flag(default = false)

    override fun run() {
        val inputPath = path.toAbsolutePath()

        if (!Files.exists(inputPath)) {
            val msg = "Target path does not exist: $inputPath"
            logger.error(LogCategory.SYSTEM, msg)
            if (json) {
                echo(
                    buildJsonObject {
                        put("status", "error")
                        put("error", msg)
                    }.toString(),
                )
            } else {
                echo("Error: $msg", err = true)
            }
            throw ProgramResult(1)
        }

        val targetJarPath =
            try {
                PluginValidator.resolveStagingTarget(inputPath)
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to resolve staging target JAR"
                logger.error(LogCategory.SYSTEM, msg, error = e)
                if (json) {
                    echo(
                        buildJsonObject {
                            put("status", "error")
                            put("error", msg)
                        }.toString(),
                    )
                } else {
                    echo("Error: $msg", err = true)
                }
                throw ProgramResult(1)
            }

        val targetJarFile = targetJarPath.toFile()
        val validation = PluginValidator.validate(targetJarFile)
        if (!validation.isValid) {
            val errMsg = "Cannot link invalid plugin at ${targetJarFile.absolutePath}"
            logger.error(LogCategory.SYSTEM, errMsg)
            val report = PluginValidator.toReport(targetJarFile.absolutePath, validation)
            if (json) {
                echo(launchpadJson.encodeToString(report))
            } else {
                echo("$errMsg:", err = true)
                validation.checks.filter { !it.passed }.forEach {
                    echo("  [✗] ${it.name}: ${it.message}", err = true)
                }
            }
            throw ProgramResult(1)
        }

        val manifest =
            try {
                PluginValidator.readManifestFromJar(targetJarFile)
                    ?: error("Failed to extract plugin manifest from JAR: ${targetJarFile.name}")
            } catch (e: Exception) {
                val msg = "Failed to extract plugin manifest from JAR: ${e.message}"
                logger.error(LogCategory.SYSTEM, msg, error = e)
                if (json) {
                    echo(
                        buildJsonObject {
                            put("status", "error")
                            put("error", msg)
                        }.toString(),
                    )
                } else {
                    echo("Error: $msg", err = true)
                }
                throw ProgramResult(1)
            }
        val pluginId = manifest.pluginId

        // Version-rotated staging to prevent Windows file locking collisions
        val pluginDevBase = File(DevPluginArtifacts.stagingRoot(), pluginId)
        val timestamp = System.currentTimeMillis()
        val versionDir = File(pluginDevBase, "v$timestamp")
        if (!versionDir.exists() && !versionDir.mkdirs()) {
            val msg = "Failed to create version staging directory: ${versionDir.absolutePath}"
            logger.error(LogCategory.SYSTEM, msg)
            if (json) {
                echo(
                    buildJsonObject {
                        put("status", "error")
                        put("error", msg)
                    }.toString(),
                )
            } else {
                echo("Error: $msg", err = true)
            }
            throw ProgramResult(1)
        }
        val partJar = File(versionDir, "$pluginId.jar.part")
        val stagedJar = File(versionDir, "$pluginId.jar")

        try {
            targetJarFile.copyTo(partJar, overwrite = true)
            try {
                Files.move(
                    partJar.toPath(),
                    stagedJar.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (
                @Suppress("SwallowedException") e: AtomicMoveNotSupportedException,
            ) {
                logger.debug(
                    LogCategory.SYSTEM,
                    "ATOMIC_MOVE not supported, falling back to REPLACE_EXISTING",
                    mapOf("pluginId" to pluginId),
                )
                Files.move(
                    partJar.toPath(),
                    stagedJar.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (e: Exception) {
            val msg = "Failed to stage plugin in ${stagedJar.absolutePath}: ${e.message}"
            logger.error(LogCategory.SYSTEM, msg, error = e)
            if (json) {
                echo(
                    buildJsonObject {
                        put("status", "error")
                        put("error", msg)
                    }.toString(),
                )
            } else {
                echo("Error: $msg", err = true)
            }
            throw ProgramResult(1)
        }

        val reloadResult = SingleInstanceManager.reloadDevPlugin(pluginId)
        when (reloadResult) {
            is ReloadResult.Success -> {
                if (json) {
                    val payload =
                        buildJsonObject {
                            put("status", "linked_and_reloaded")
                            put("pluginId", pluginId)
                            put("running", true)
                            put("stagedPath", stagedJar.absolutePath.replace('\\', '/'))
                        }
                    echo(payload.toString())
                } else {
                    echo(
                        "[✓] Plugin '$pluginId' staged at ${stagedJar.absolutePath} " +
                            "and reload signal confirmed by BossConsole.",
                    )
                }
            }

            is ReloadResult.HostOffline -> {
                if (json) {
                    val payload =
                        buildJsonObject {
                            put("status", "staged")
                            put("pluginId", pluginId)
                            put("running", false)
                            put("stagedPath", stagedJar.absolutePath.replace('\\', '/'))
                            put("notice", "BossConsole is offline; plugin staged for next launch")
                        }
                    echo(payload.toString())
                } else {
                    echo(
                        "[✓] Plugin '$pluginId' staged at ${stagedJar.absolutePath}. " +
                            "BossConsole is offline; plugin staged for next launch.",
                    )
                }
            }

            is ReloadResult.TimedOut -> {
                if (json) {
                    val payload =
                        buildJsonObject {
                            put("status", "staged_unconfirmed")
                            put("pluginId", pluginId)
                            put("running", true)
                            put("stagedPath", stagedJar.absolutePath.replace('\\', '/'))
                            put("notice", reloadResult.message)
                        }
                    echo(payload.toString())
                } else {
                    echo(
                        "[~] Plugin '$pluginId' staged at ${stagedJar.absolutePath}. " +
                            "${reloadResult.message}. It is picked up at the next launch.",
                    )
                }
            }

            is ReloadResult.Failed -> {
                val msg = "Plugin '$pluginId' staged, but reload failed: ${reloadResult.reason}"
                logger.error(LogCategory.SYSTEM, msg)
                if (json) {
                    val payload =
                        buildJsonObject {
                            put("status", "linked_reload_failed")
                            put("pluginId", pluginId)
                            put("running", true)
                            put("error", reloadResult.reason)
                            put("stagedPath", stagedJar.absolutePath.replace('\\', '/'))
                        }
                    echo(payload.toString())
                    throw ProgramResult(1)
                } else {
                    echo(msg, err = true)
                    throw ProgramResult(1)
                }
            }
        }
    }
}

/**
 * Reads a plugin manifest from a source directory or packaged JAR and prints what it declares.
 *
 * Surfaces the fields that matter before a plugin is installed or linked: identity
 * (id, display name, version), the API version it requires, the entrypoint class, the
 * permissions it asks for, and the MCP tools it registers. Read-only: `boss inspect` never
 * touches the host, the plugin loader, or the filesystem beyond reading the manifest.
 *
 * Accepts the same inputs as [BossPluginLinkCommand] - a directory containing a manifest
 * (either a top-level `plugin.json` or `src/main/resources/META-INF/boss-plugin/plugin.json`),
 * or a packaged `.jar`/`.zip` whose manifest lives at `META-INF/boss-plugin/plugin.json`.
 * The same `build/libs` resolution applies to a directory.
 *
 * Usage:
 *   boss plugin inspect [<path>] [--json]
 */
@Suppress("TooGenericExceptionCaught")
class BossPluginInspectCommand : CliktCommand(name = "inspect") {
    override fun help(context: Context) =
        "Prints what a plugin declares: identity, permissions, MCP tools, and entrypoint"

    private val logger = BossLogger.forComponent("BossPluginInspectCommand")

    val path by argument(help = "Path to plugin directory or packaged JAR/ZIP archive")
        .path(canBeDir = true, canBeFile = true)
        .default(Paths.get("."))
    val json by option("--json", help = "Emit structured JSON instead of the human-readable report").flag(default = false)

    override fun run() {
        val inputPath = path.toAbsolutePath()
        val report =
            try {
                inspectTarget(inputPath.toFile())
            } catch (e: Exception) {
                val msg = "Failed to inspect plugin at ${inputPath}: ${e.message ?: "unknown error"}"
                logger.error(LogCategory.SYSTEM, msg, error = e)
                if (json) {
                    echo(
                        buildJsonObject {
                            put("status", "error")
                            put("error", msg)
                        }.toString(),
                        err = true,
                    )
                } else {
                    echo("Error: $msg", err = true)
                }
                throw ProgramResult(1)
            }

        when (report) {
            is InspectReport.Error -> {
                if (json) {
                    echo(
                        buildJsonObject {
                            put("status", "error")
                            put("error", report.message)
                        }.toString(),
                        err = true,
                    )
                } else {
                    echo("Error: ${report.message}", err = true)
                }
                throw ProgramResult(1)
            }

            is InspectReport.Ok -> {
                if (json) {
                    echo(launchpadJson.encodeToString(inspectPayload(report)))
                } else {
                    echo(formatHumanInspectReport(report))
                }
            }
        }
    }
}

/** The two outcomes `boss plugin inspect` can hand back. */
internal sealed interface InspectReport {
    data class Ok(
        val source: InspectSource,
        val manifest: PluginManifest,
    ) : InspectReport

    data class Error(val message: String) : InspectReport
}

/** Where the manifest was read from, so the report can describe what it looked at. */
internal sealed interface InspectSource {
    /** A project directory holding a manifest file. */
    data class Directory(
        val rootPath: String,
        val manifestPath: String,
    ) : InspectSource

    /** A packaged JAR or ZIP archive. */
    data class Archive(
        val archivePath: String,
        val archiveSizeBytes: Long,
        val entryCount: Int,
    ) : InspectSource
}

/**
 * Reads a manifest from a directory or archive and returns a structured report.
 *
 * The directory branch prefers `src/main/resources/META-INF/boss-plugin/plugin.json`, then
 * `plugin.json` at the directory root - the same precedence the validator uses - so an
 * unbuilt source tree and a freshly-scaffolded one both work without `gradle build` having
 * to run first. The archive branch opens the file as a JAR (a ZIP archive uses the same
 * entry layout) and reads `META-INF/boss-plugin/plugin.json` from inside.
 */
@Suppress("ReturnCount", "TooGenericExceptionCaught")
internal fun inspectTarget(target: File): InspectReport {
    if (!target.exists()) {
        return InspectReport.Error("Target path does not exist: ${target.absolutePath}")
    }
    if (target.isDirectory) {
        return inspectDirectory(target)
    }
    if (target.name.endsWith(".jar") || target.name.endsWith(".zip")) {
        return inspectArchive(target)
    }
    return InspectReport.Error(
        "Target must be a directory or a .jar/.zip archive: ${target.absolutePath}",
    )
}

private fun inspectDirectory(dir: File): InspectReport {
    val candidates =
        listOf(
            File(dir, "src/main/resources/META-INF/boss-plugin/plugin.json"),
            File(dir, "plugin.json"),
        )
    val manifestFile = candidates.firstOrNull { it.isFile }
    if (manifestFile == null) {
        return InspectReport.Error(
            "plugin.json not found in directory (looked for src/main/resources/META-INF/boss-plugin/plugin.json " +
                "and plugin.json): ${dir.absolutePath}",
        )
    }
    val manifest =
        try {
            launchpadJson.decodeFromString<PluginManifest>(manifestFile.readText())
        } catch (e: Exception) {
            return InspectReport.Error(
                "Unable to parse ${manifestFile.relativeTo(dir).path.replace('\\', '/')}: ${e.message ?: "unknown error"}",
            )
        }
    return InspectReport.Ok(
        source = InspectSource.Directory(
            rootPath = dir.absolutePath.replace('\\', '/'),
            manifestPath = manifestFile.relativeTo(dir).path.replace('\\', '/'),
        ),
        manifest = manifest,
    )
}

private fun inspectArchive(archive: File): InspectReport {
    val size = archive.length()
    val manifest =
        try {
            JarFile(archive).use { jar ->
                val entry = jar.getJarEntry("META-INF/boss-plugin/plugin.json")
                if (entry == null) {
                    return InspectReport.Error(
                        "META-INF/boss-plugin/plugin.json not found in archive: ${archive.absolutePath}",
                    )
                }
                val content =
                    jar.getInputStream(entry).use { stream ->
                        stream.readBytes().toString(Charsets.UTF_8)
                    }
                launchpadJson.decodeFromString<PluginManifest>(content)
            }
        } catch (e: Exception) {
            return InspectReport.Error(
                "Unable to read manifest from ${archive.absolutePath}: ${e.message ?: "unknown error"}",
            )
        }
    val entryCount =
        try {
            JarFile(archive).use { jar -> jar.size() }
        } catch (_: Exception) {
            -1
        }
    return InspectReport.Ok(
        source = InspectSource.Archive(
            archivePath = archive.absolutePath.replace('\\', '/'),
            archiveSizeBytes = size,
            entryCount = entryCount,
        ),
        manifest = manifest,
    )
}

/**
 * Human-readable `boss plugin inspect` report. Sections appear in the order a person asks
 * them: identity first ("what is this?"), then host requirements ("will it run on my
 * version?"), then what it can do ("what does it want?").
 *
 * `manifestVersion` and `systemPlugin` / `canUnload` are flagged only when the manifest
 * deviates from the defaults a `boss plugin init` template emits - the defaults are what
 * everyone ships, and printing them again is noise.
 */
internal fun formatHumanInspectReport(report: InspectReport.Ok): String =
    buildString {
        val m = report.manifest
        appendLine("Plugin: ${m.resolvedDisplayName().ifBlank { m.resolvedPluginId() }}")
        appendLine("  ID:          ${m.resolvedPluginId()}")
        if (m.resolvedDisplayName().isNotBlank() && m.resolvedDisplayName() != m.resolvedPluginId()) {
            appendLine("  Display:     ${m.resolvedDisplayName()}")
        }
        appendLine("  Version:     ${m.version}")
        appendLine("  API:         ${m.resolvedApiVersion()}")
        appendLine("  Entrypoint:  ${m.resolvedMainClass()}")
        if (m.author.isNotBlank()) appendLine("  Author:      ${m.author}")
        if (m.license.isNotBlank()) appendLine("  License:     ${m.license}")
        if (m.description.isNotBlank()) {
            appendLine("  Description:")
            appendLine(m.description.prependIndent("    "))
        }
        if (m.systemPlugin) appendLine("  [flag] systemPlugin: true - read-only host-installed plugin")
        if (!m.canUnload) appendLine("  [flag] canUnload: false - pinned in memory once loaded")

        appendLine()
        appendLine("Permissions (${m.permissions.size}):")
        if (m.permissions.isEmpty()) {
            appendLine("  (none)")
        } else {
            for (permission in m.permissions) {
                val known = PluginPermission.fromIdentifier(permission)
                val label = if (known != null) permission else "$permission (unrecognised)"
                appendLine("  - $label")
            }
        }

        appendLine()
        appendLine("MCP Tools (${m.mcpTools.size}):")
        if (m.mcpTools.isEmpty()) {
            appendLine("  (none)")
        } else {
            for (tool in m.mcpTools) {
                val adminTag = if (tool.adminOnly) " [admin]" else ""
                appendLine("  - ${tool.name}$adminTag")
                if (tool.description.isNotBlank()) {
                    appendLine(tool.description.prependIndent("    "))
                }
            }
        }

        appendLine()
        appendLine("Source:")
        when (val source = report.source) {
            is InspectSource.Directory -> {
                appendLine("  Directory:   ${source.rootPath}")
                appendLine("  Manifest:    ${source.manifestPath}")
            }
            is InspectSource.Archive -> {
                appendLine("  Archive:     ${source.archivePath}")
                appendLine("  Size:        ${source.archiveSizeBytes} bytes")
                if (source.entryCount >= 0) appendLine("  Entries:     ${source.entryCount}")
            }
        }
    }.trimEnd()

/** JSON payload for `--json`. Mirrors the sections of the human report, in the same order. */
internal fun inspectPayload(report: InspectReport.Ok) =
    buildJsonObject {
        put("status", "ok")
        val m = report.manifest
        put("pluginId", m.resolvedPluginId())
        put("displayName", m.resolvedDisplayName())
        put("version", m.version)
        put("apiVersion", m.resolvedApiVersion())
        put("mainClass", m.resolvedMainClass())
        if (m.author.isNotBlank()) put("author", m.author)
        if (m.license.isNotBlank()) put("license", m.license)
        if (m.description.isNotBlank()) put("description", m.description)
        put("manifestVersion", m.manifestVersion)
        put("systemPlugin", m.systemPlugin)
        put("canUnload", m.canUnload)
        put(
            "permissions",
            buildJsonArray {
                for (permission in m.permissions) {
                    addJsonObject {
                        put("id", permission)
                        val known = PluginPermission.fromIdentifier(permission)
                        put("recognised", known != null)
                        if (known != null) put("category", known.name)
                    }
                }
            },
        )
        put(
            "mcpTools",
            buildJsonArray {
                for (tool in m.mcpTools) {
                    addJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("adminOnly", tool.adminOnly)
                    }
                }
            },
        )
        put("source", sourcePayload(report.source))
    }

private fun sourcePayload(source: InspectSource) =
    when (source) {
        is InspectSource.Directory ->
            buildJsonObject {
                put("type", "directory")
                put("rootPath", source.rootPath)
                put("manifestPath", source.manifestPath)
            }

        is InspectSource.Archive ->
            buildJsonObject {
                put("type", "archive")
                put("archivePath", source.archivePath)
                put("archiveSizeBytes", source.archiveSizeBytes)
                if (source.entryCount >= 0) put("entryCount", source.entryCount)
            }
    }
