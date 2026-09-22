package ai.rever.boss.app.editor

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.language.LanguageIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * gRPC implementation of EditorService.
 *
 * Provides real file I/O using the host filesystem:
 * - OpenFile: reads file from disk, detects language by extension
 * - SaveFile: writes content back to disk (atomically, so a crash mid-write does not tear
 *   the user's source file - the editor's own documents are strictly more valuable than any
 *   ~/.boss persistence the host already atomic-writes, and the old in-place write was
 *   a silent data-loss bug under low-battery shutdowns or process kills - see #885)
 * - DetectMainFunctions: regex-based scan for entry points across multiple languages
 * - GetTokens / NavigateToDefinition: require PSI (in composeApp) — return empty
 *
 * Path gate: every IPC path is canonicalized, refused if it names a Windows system
 * directory or falls outside the user's home directory by canonical path. The previous
 * blocklist was POSIX-only and ran on the raw string, so a symlink inside the workspace
 * pointing at `C:\Windows` or `/etc` passed validation, and `mkdirs()` would happily
 * create parent directories through it.
 */
class EditorServiceImpl : EditorServiceGrpcKt.EditorServiceCoroutineImplBase() {
    private val logger = LoggerFactory.getLogger(EditorServiceImpl::class.java)

    /** Canonical path -> isDirty: tracks files opened in this session. */
    private val openFiles = ConcurrentHashMap<String, Boolean>()

    /** Lower-case, OS-aware prefix blocklist. Always checked on the raw input. */
    private val blockedPrefixes: List<String> =
        buildList {
            add("/etc")
            add("/sys")
            add("/proc")
            if (isWindows()) {
                add("c:/windows")
                add("c:/program files")
                add("c:/program files (x86)")
                add("c:/system volume information")
            }
        }

    /**
     * Canonicalizes [file] so symlinks and `..` are resolved, returning the path the
     * bytes would actually land at. Used by [validatePath], which then enforces the
     * confinement and system-root policy on this resolved path rather than on the
     * raw input string.
     *
     * For a path that exists, `Path.toRealPath()` follows every symlink in the chain
     * (including one sitting where the file itself is) - which is the whole point: a
     * save through a symlink is judged by where the bytes actually land. For a path
     * that does not yet exist, the deepest existing ancestor is real-pathed and the
     * absent tail appended back on, so saving a new file under a freshly-created
     * directory tree still passes the gate.
     */
    internal fun canonicalPath(file: File): Path {
        val absolute = file.toPath().toAbsolutePath().normalize()
        return try {
            absolute.toRealPath()
        } catch (_: IOException) {
            val existingAncestor =
                generateSequence(absolute) { it.parent }
                    .firstOrNull { Files.exists(it) }
                    ?: error("No existing ancestor for $absolute (home is missing)")
            val realAncestor = existingAncestor.toRealPath()
            val tail = realAncestor.relativize(absolute)
            var resolved = realAncestor
            for (name in tail) {
                resolved = resolved.resolve(name)
            }
            resolved.normalize()
        } catch (_: SecurityException) {
            error("Cannot resolve $absolute (security manager blocked realpath)")
        }
    }

    /**
     * Resolves [path] (canonical, symlink-following) and enforces every gate on the
     * RESOLVED path, not the raw string. Returns the resolved [Path] so callers like
     * [saveFile] can write through the validated target - which is what closes the
     * `$HOME/workspace-link/secret.txt` regression in #885, where `workspace-link` is
     * a symlink to an outside directory and the raw path was inside home but the
     * resolved parent was not.
     *
     * Three layers, in this order: (1) the raw string for `..` traversal and blanks;
     * (2) the resolved path against the Windows/POSIX system-path blocklist; (3) the
     * resolved path against the user's home directory. The blocklist runs on the
     * resolved path so a workspace symlink to `C:\Windows` lands at `C:\Windows` and
     * is rejected there, not at the symlink's own name.
     */
    internal fun validatePath(path: String): Path {
        require(path.isNotBlank()) { "Path must not be blank" }
        require(!path.contains("..")) { "Path traversal sequences ('..') are not allowed: $path" }
        val resolved = canonicalPath(File(path))
        val normalized = resolved.toString().replace('\\', '/').lowercase(Locale.ROOT)
        blockedPrefixes.forEach { prefix ->
            require(!normalized.startsWith(prefix)) {
                "Access to system path '$prefix' is not allowed: $path"
            }
        }
        val userHome = canonicalPath(File(System.getProperty("user.home")))
        require(resolved.startsWith(userHome)) {
            "Path '$path' resolves outside the user's home directory: $resolved"
        }
        return resolved
    }

    // Language-specific main/entry-point patterns
    private val mainPatterns =
        listOf(
            Regex("""^\s*(?:suspend\s+)?fun\s+main\s*\("""), // Kotlin
            Regex("""^\s*public\s+static\s+void\s+main\s*\(\s*String"""), // Java
            Regex("""^\s*if\s+__name__\s*==\s*['"]__main__['"]\s*:"""), // Python
            Regex("""^\s*func\s+main\s*\(\s*\)"""), // Go / Swift
            Regex("""^\s*fn\s+main\s*\(\s*\)"""), // Rust
            Regex("""^\s*int\s+main\s*\("""), // C / C++
        )

    override suspend fun openFile(request: OpenFileRequest): OpenFileResponse =
        withContext(Dispatchers.IO) {
            logger.info("openFile: path={}", request.path)
            try {
                val resolved = validatePath(request.path)
                val file = resolved.toFile()
                if (!file.exists() || !file.isFile) {
                    return@withContext OpenFileResponse
                        .newBuilder()
                        .setSuccess(false)
                        .setErrorMessage("File not found: ${request.path}")
                        .build()
                }
                try {
                    val content = file.readText(Charsets.UTF_8)
                    openFiles[resolved.toString()] = false
                    OpenFileResponse
                        .newBuilder()
                        .setSuccess(true)
                        .setContent(content)
                        .setLanguage(languageForFile(file))
                        .build()
                } catch (e: Exception) {
                    logger.warn("openFile read failed: {}", e.message)
                    OpenFileResponse
                        .newBuilder()
                        .setSuccess(false)
                        .setErrorMessage(e.message ?: "Read failed")
                        .build()
                }
            } catch (e: IllegalArgumentException) {
                logger.error("openFile refused for {}: {}", request.path, e.message)
                OpenFileResponse
                    .newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(e.message ?: "Path refused")
                    .build()
            }
        }

    override suspend fun saveFile(request: SaveFileRequest): Empty =
        withContext(Dispatchers.IO) {
            logger.info("saveFile: path={}", request.path)
            try {
                val resolved = validatePath(request.path)
                atomicWriteText(resolved.toString(), request.content, Charsets.UTF_8)
                openFiles[resolved.toString()] = false
            } catch (e: IllegalArgumentException) {
                logger.error("saveFile refused for {}: {}", request.path, e.message)
            } catch (e: Exception) {
                logger.error("saveFile failed for {}: {}", request.path, e.message)
            }
            Empty.getDefaultInstance()
        }

    /**
     * Writes [content] to [path] atomically: a sibling temp file is created, written,
     * then moved onto the target with `ATOMIC_MOVE`. A crash, kill, or write failure
     * mid-stream leaves the previous file at [path] untouched, with the partial bytes
     * isolated in `<name>.<random>.tmp` until cleanup runs.
     *
     * `ATOMIC_MOVE` is dropped for the cross-volume case (a real possibility on an
     * arbitrary path from IPC) and falls back to `REPLACE_EXISTING`. Either way the
     * temp file is removed on the way out.
     */
    internal fun atomicWriteText(
        path: String,
        content: String,
        charset: Charset,
    ) {
        val target = File(path)
        val parent = target.parentFile ?: error("Cannot determine parent directory of $path")
        // mkdirs is safe to call now: validatePath already proved the parent canonical
        // resolves inside the user's home, so even if the raw path traversed a symlink
        // chain, the resulting directories are inside the home.
        parent.mkdirs()
        val tmp =
            Files.createTempFile(
                parent.toPath(),
                ".${target.name}.",
                ".tmp",
            )
        try {
            Files.writeString(tmp, content, charset)
            try {
                Files.move(
                    tmp,
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    override suspend fun getTokens(request: GetTokensRequest): GetTokensResponse {
        // PSI-based tokenization lives in composeApp (kotlin-compiler-embeddable).
        // Return empty — the kernel-side editor proxy uses composeApp's PSI directly.
        logger.debug("getTokens: path={} (PSI not in this process)", request.path)
        return GetTokensResponse.newBuilder().build()
    }

    override suspend fun navigateToDefinition(request: NavigateRequest): NavigateResponse {
        logger.debug("navigateToDefinition: path={} (PSI not in this process)", request.path)
        return NavigateResponse.newBuilder().setFound(false).build()
    }

    override suspend fun detectMainFunctions(request: DetectMainRequest): DetectMainResponse =
        withContext(Dispatchers.IO) {
            logger.info("detectMainFunctions: path={}", request.path)
            val resolved =
                try {
                    validatePath(request.path)
                } catch (e: IllegalArgumentException) {
                    logger.error("detectMainFunctions refused for {}: {}", request.path, e.message)
                    return@withContext DetectMainResponse.newBuilder().build()
                }
            val file = resolved.toFile()
            if (!file.exists() || !file.isFile) return@withContext DetectMainResponse.newBuilder().build()

            val functions = mutableListOf<MainFunctionInfo>()
            try {
                file.readLines(Charsets.UTF_8).forEachIndexed { idx, line ->
                    if (mainPatterns.any { it.containsMatchIn(line) }) {
                        functions +=
                            MainFunctionInfo
                                .newBuilder()
                                .setName("main")
                                .setLine(idx + 1)
                                .setDisplayName("main (line ${idx + 1})")
                                .setQualifiedName("${file.nameWithoutExtension}.main")
                                .build()
                    }
                }
            } catch (e: Exception) {
                logger.warn("detectMainFunctions scan error: {}", e.message)
            }

            DetectMainResponse.newBuilder().addAllFunctions(functions).build()
        }

    override suspend fun listOpenFiles(request: Empty): ListOpenFilesResponse {
        val infos =
            openFiles.entries.map { (path, dirty) ->
                OpenFileInfo
                    .newBuilder()
                    .setPath(path)
                    .setIsModified(dirty)
                    .build()
            }
        return ListOpenFilesResponse.newBuilder().addAllFiles(infos).build()
    }

    // Filename rules take precedence even when a suffix is a known extension
    // (Dockerfile.sh is a Dockerfile). Preserve this service's proto and unknown defaults.
    private fun languageForFile(file: File): String =
        LanguageIds.detect(file.name).takeUnless { it == LanguageIds.TEXT }
            ?: detectLanguage(file.extension)

    /**
     * BossConsole#75: this used to be its own hand-maintained table, independent of
     * (and disagreeing with) `composeApp`'s `EditorLanguages` - most visibly, `.sh`/
     * `.bash`/`.zsh` were `shell` here and `bash` there. Both now read
     * [LanguageIds], the module the two were consolidated into. `proto` stays a local
     * addition: `LanguageIds` is the table shared with `boss-file-types.json`'s
     * default-file-type-association list, and adding an id there means adding the
     * extension to that JSON too - out of scope for a language-id fix.
     */
    internal fun detectLanguage(ext: String): String =
        when (ext.lowercase()) {
            "proto" -> "protobuf"
            else -> LanguageIds.forExtension(ext) ?: "plaintext"
        }

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase(Locale.ROOT).contains("windows")
}
