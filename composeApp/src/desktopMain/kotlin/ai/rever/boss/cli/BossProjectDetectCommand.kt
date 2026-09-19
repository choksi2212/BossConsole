package ai.rever.boss.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Static project-type detector.
 *
 * Walks a directory and reads marker files (`build.gradle.kts`,
 * `package.json`, `Cargo.toml`, `pyproject.toml`, `go.mod`,
 * `pom.xml`, ...) to identify the languages, build tools, package
 * managers, and test frameworks in use. Pure read - no execution, no
 * network, no `git` invocations.
 *
 * The output is a JSON-friendly shape so an agent or CI step can decide
 * "is this a Kotlin project" without parsing free text. The order of
 * detected items reflects confidence: a marker file like `build.gradle.kts`
 * is stronger evidence than counting `.kt` source files (the latter can
 * be generated, vendored, or just snippets).
 *
 * Usage:
 *   boss project-detect [--path <dir>] [--json]
 *
 * Exit codes: 0 always (the detector is informational). 2 if the path is
 * missing or not a directory.
 */
class BossProjectDetectCommand : CliktCommand(name = "project-detect") {
    override fun help(context: Context) = "Identifies the languages, build tools, and test frameworks in a project directory"

    private val detector = ProjectDetector()

    val path by option("--path", help = "Directory to inspect (defaults to current directory)").default(".")
    val json by option("--json", help = "Output the report as JSON").flag(default = false)

    override fun run() {
        val root = File(path).absoluteFile
        if (!root.exists()) {
            echo("Error: path does not exist: $path", err = true)
            throw ProgramResult(2)
        }
        if (!root.isDirectory) {
            echo("Error: not a directory: $path", err = true)
            throw ProgramResult(2)
        }
        val report = detector.detect(root)
        renderAndExit(report, json)
    }

    private fun renderAndExit(
        report: ProjectReport,
        json: Boolean,
    ) {
        if (json) {
            echo(ProjectDetectJson.encode(report))
        } else {
            echo("Project at ${report.root}")
            if (report.languages.isEmpty()) {
                echo("  languages: (none detected)")
            } else {
                echo("  languages:")
                for (lang in report.languages) echo("    - $lang")
            }
            if (report.buildTools.isEmpty()) {
                echo("  build tools: (none detected)")
            } else {
                echo("  build tools:")
                for (bt in report.buildTools) echo("    - $bt")
            }
            if (report.packageManagers.isEmpty()) {
                echo("  package managers: (none detected)")
            } else {
                echo("  package managers:")
                for (pm in report.packageManagers) echo("    - $pm")
            }
            if (report.testFrameworks.isEmpty()) {
                echo("  test frameworks: (none detected)")
            } else {
                echo("  test frameworks:")
                for (tf in report.testFrameworks) echo("    - $tf")
            }
            if (report.frameworks.isEmpty()) {
                echo("  frameworks: (none detected)")
            } else {
                echo("  frameworks:")
                for (fw in report.frameworks) echo("    - $fw")
            }
            if (report.markers.isNotEmpty()) {
                echo("  marker files:")
                for (m in report.markers) echo("    - $m")
            }
        }
    }
}

data class ProjectReport(
    val root: String,
    val languages: List<String>,
    val buildTools: List<String>,
    val packageManagers: List<String>,
    val testFrameworks: List<String>,
    val frameworks: List<String>,
    val markers: List<String>,
)

/**
 * Pure detector. The [Marker] table is the single source of truth: every
 * finding is keyed off a file at a known path, and the same file can
 * drive multiple categories (a `build.gradle.kts` is both a Kotlin
 * marker and a Gradle marker).
 *
 * A path that exists at any depth within the project is counted; we do
 * not require the marker to be at the root, because a Gradle project
 * checked into a monorepo subdirectory is still a Gradle project.
 */
class ProjectDetector {
    /**
     * Each entry pairs a path glob against a list of contributions.
     * The glob is matched against the project's file tree; contributions
     * accumulate in [ProjectReport] in the order they are encountered.
     */
    private data class Marker(
        val id: String,
        val path: String,
        val contributes: List<Contribution>,
    )

    /**
     * One finding. A single marker can contribute to several categories,
     * so [Contribution] is a single key/value pair, not a list.
     */
    private data class Contribution(
        val bucket: String,
        val value: String,
    )

    private val markers: List<Marker> =
        listOf(
            // Kotlin / Java ecosystem
            Marker(
                id = "build.gradle.kts",
                path = "build.gradle.kts",
                contributes =
                    listOf(
                        Contribution("languages", "Kotlin"),
                        Contribution("languages", "Java"),
                        Contribution("buildTools", "Gradle (Kotlin DSL)"),
                    ),
            ),
            Marker(
                id = "build.gradle",
                path = "build.gradle",
                contributes =
                    listOf(
                        Contribution("languages", "Java"),
                        Contribution("languages", "Groovy"),
                        Contribution("buildTools", "Gradle (Groovy DSL)"),
                    ),
            ),
            Marker(
                id = "settings.gradle.kts",
                path = "settings.gradle.kts",
                contributes = listOf(Contribution("buildTools", "Gradle (Kotlin DSL)")),
            ),
            Marker(
                id = "pom.xml",
                path = "pom.xml",
                contributes =
                    listOf(
                        Contribution("languages", "Java"),
                        Contribution("buildTools", "Maven"),
                        Contribution("packageManagers", "Maven"),
                    ),
            ),
            Marker(
                id = "gradle.lockfile",
                path = "gradle.lockfile",
                contributes = listOf(Contribution("packageManagers", "Gradle dependency lock")),
            ),
            // Node / TypeScript
            Marker(
                id = "package.json",
                path = "package.json",
                contributes =
                    listOf(
                        Contribution("languages", "JavaScript"),
                        Contribution("packageManagers", "npm"),
                    ),
            ),
            Marker(
                id = "package-lock.json",
                path = "package-lock.json",
                contributes = listOf(Contribution("packageManagers", "npm")),
            ),
            Marker(
                id = "yarn.lock",
                path = "yarn.lock",
                contributes = listOf(Contribution("packageManagers", "Yarn")),
            ),
            Marker(
                id = "pnpm-lock.yaml",
                path = "pnpm-lock.yaml",
                contributes = listOf(Contribution("packageManagers", "pnpm")),
            ),
            Marker(
                id = "tsconfig.json",
                path = "tsconfig.json",
                contributes = listOf(Contribution("languages", "TypeScript")),
            ),
            // Rust
            Marker(
                id = "Cargo.toml",
                path = "Cargo.toml",
                contributes =
                    listOf(
                        Contribution("languages", "Rust"),
                        Contribution("buildTools", "Cargo"),
                        Contribution("packageManagers", "Cargo"),
                    ),
            ),
            // Go
            Marker(
                id = "go.mod",
                path = "go.mod",
                contributes =
                    listOf(
                        Contribution("languages", "Go"),
                        Contribution("buildTools", "Go modules"),
                    ),
            ),
            // Python
            Marker(
                id = "pyproject.toml",
                path = "pyproject.toml",
                contributes =
                    listOf(
                        Contribution("languages", "Python"),
                        Contribution("packageManagers", "pip"),
                    ),
            ),
            Marker(
                id = "requirements.txt",
                path = "requirements.txt",
                contributes =
                    listOf(
                        Contribution("languages", "Python"),
                        Contribution("packageManagers", "pip"),
                    ),
            ),
            Marker(
                id = "setup.py",
                path = "setup.py",
                contributes =
                    listOf(
                        Contribution("languages", "Python"),
                        Contribution("packageManagers", "setuptools"),
                    ),
            ),
            Marker(
                id = "Pipfile",
                path = "Pipfile",
                contributes =
                    listOf(
                        Contribution("languages", "Python"),
                        Contribution("packageManagers", "pipenv"),
                    ),
            ),
            Marker(
                id = "poetry.lock",
                path = "poetry.lock",
                contributes =
                    listOf(
                        Contribution("languages", "Python"),
                        Contribution("packageManagers", "poetry"),
                    ),
            ),
            // Ruby
            Marker(
                id = "Gemfile",
                path = "Gemfile",
                contributes =
                    listOf(
                        Contribution("languages", "Ruby"),
                        Contribution("packageManagers", "Bundler"),
                    ),
            ),
            // JavaScript / TypeScript frameworks (common ones, picked up from package.json deps)
            Marker(
                id = "next.config.js",
                path = "next.config.js",
                contributes = listOf(Contribution("frameworks", "Next.js")),
            ),
            Marker(
                id = "next.config.ts",
                path = "next.config.ts",
                contributes = listOf(Contribution("frameworks", "Next.js")),
            ),
            Marker(
                id = "nuxt.config.ts",
                path = "nuxt.config.ts",
                contributes = listOf(Contribution("frameworks", "Nuxt")),
            ),
            Marker(
                id = "angular.json",
                path = "angular.json",
                contributes = listOf(Contribution("frameworks", "Angular")),
            ),
            Marker(
                id = "vue.config.js",
                path = "vue.config.js",
                contributes = listOf(Contribution("frameworks", "Vue")),
            ),
            Marker(
                id = "svelte.config.js",
                path = "svelte.config.js",
                contributes = listOf(Contribution("frameworks", "Svelte")),
            ),
            // Test frameworks (marker-based; reading the manifest for the
            // test runner is in [detectFromPackageJson] below)
            Marker(
                id = "pytest.ini",
                path = "pytest.ini",
                contributes = listOf(Contribution("testFrameworks", "pytest")),
            ),
            Marker(
                id = "conftest.py",
                path = "conftest.py",
                contributes = listOf(Contribution("testFrameworks", "pytest")),
            ),
            Marker(
                id = "go test (any *_test.go)",
                path = "go-test",
                contributes = listOf(Contribution("testFrameworks", "Go testing")),
            ),
            Marker(
                id = "cargo test (any tests/ dir)",
                path = "cargo-test",
                contributes = listOf(Contribution("testFrameworks", "Cargo test")),
            ),
        )

    fun detect(root: File): ProjectReport {
        val contributions = mutableMapOf<String, MutableSet<String>>()
        val foundMarkers = mutableListOf<String>()
        val seenPaths = mutableSetOf<String>()

        for (marker in markers) {
            // Match by exact path or by file-name only (no directory), so
            // monorepo subprojects with a build.gradle.kts in foo/bar/ are
            // picked up.
            val matches = findMatchingFiles(root, marker)
            if (matches.isNotEmpty()) {
                val pathKey = marker.path
                if (seenPaths.add(pathKey)) {
                    foundMarkers += pathKey
                    for (c in marker.contributes) {
                        contributions.getOrPut(c.bucket) { mutableSetOf() }.add(c.value)
                    }
                }
            }
        }

        // Special-case: package.json dependencies are inspected for known
        // test runners and frameworks that don't have a marker of their own.
        val packageJson = findFile(root, "package.json")
        if (packageJson != null) {
            contributionsOfPackageJson(packageJson, contributions)
        }

        // Test framework detection by source files: Go test is the only
        // one without a marker file at the project root, so we look for
        // any *_test.go file as a Go test indicator.
        if (rootHasFileMatching(root, "*_test.go")) {
            contributions.getOrPut("testFrameworks") { mutableSetOf() }.add("Go testing")
        }
        if (rootHasFileMatching(root, "Cargo.toml") &&
            (rootHasDirMatching(root, "tests") || rootHasFileMatching(root, "**/tests/*.rs"))
        ) {
            contributions.getOrPut("testFrameworks") { mutableSetOf() }.add("Cargo test")
        }

        return ProjectReport(
            root = root.absolutePath,
            languages = sorted(contributions["languages"]),
            buildTools = sorted(contributions["buildTools"]),
            packageManagers = sorted(contributions["packageManagers"]),
            testFrameworks = sorted(contributions["testFrameworks"]),
            frameworks = sorted(contributions["frameworks"]),
            markers = foundMarkers.sorted(),
        )
    }

    private fun sorted(set: MutableSet<String>?): List<String> = set?.sorted() ?: emptyList()

    private fun findMatchingFiles(
        root: File,
        marker: Marker,
    ): List<File> {
        // For most markers, look at the exact filename at any depth.
        val name = File(marker.path).name
        if (marker.path.contains("/")) return findFilesByRelativePath(root, marker.path)
        return root.walkTopDown().filter { it.isFile && it.name == name }.toList()
    }

    private fun findFile(
        root: File,
        relativePath: String,
    ): File? = root.walkTopDown().firstOrNull { it.isFile && it.relativePath(root) == relativePath }

    private fun findFilesByRelativePath(
        root: File,
        relativePath: String,
    ): List<File> = root.walkTopDown().filter { it.isFile && it.relativePath(root) == relativePath }.toList()

    private fun File.relativePath(root: File): String =
        if (this.absolutePath.startsWith(root.absolutePath)) {
            this.absolutePath
                .removePrefix(root.absolutePath)
                .trimStart('/', '\\')
                .replace('\\', '/')
        } else {
            this.name
        }

    private fun rootHasFileMatching(
        root: File,
        glob: String,
    ): Boolean =
        root.walkTopDown().any { file ->
            if (!file.isFile) return@any false
            val name = file.name
            matchGlob(name, glob) || matchGlob(file.relativePath(root), glob)
        }

    private fun rootHasDirMatching(
        root: File,
        name: String,
    ): Boolean = root.walkTopDown().any { it.isDirectory && it.name == name }

    /**
     * Tiny glob matcher: `*` matches any chars except `/`, `**` matches
     * any chars including `/`, `?` matches a single char. Same shape as
     * the secrets scanner's matcher, kept separate because the inputs
     * here are very short and the cost of a regex compile per call would
     * dominate the walk.
     */
    private fun matchGlob(
        input: String,
        glob: String,
    ): Boolean {
        if (!glob.contains('*') && !glob.contains('?')) return input == glob
        val regex = StringBuilder("^")
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
                    regex.append(".*")
                    i += 2
                }

                c == '*' -> {
                    regex.append("[^/]*")
                }

                c == '?' -> {
                    regex.append("[^/]")
                }

                c == '.' || c == '(' || c == ')' || c == '+' || c == '|' ||
                    c == '^' || c == '$' || c == '{' || c == '}' || c == '\\' -> {
                    regex.append('\\').append(c)
                }

                else -> {
                    regex.append(c)
                }
            }
            i += 1
        }
        regex.append('$')
        return Regex(regex.toString()).matches(input)
    }

    /**
     * Inspect package.json for test runners and frameworks that don't
     * ship a marker file of their own. The file is read in lenient mode
     * because real-world package.jsons routinely have undeclared fields.
     */
    private fun contributionsOfPackageJson(
        file: File,
        sink: MutableMap<String, MutableSet<String>>,
    ) {
        try {
            val obj =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(file.readText(Charsets.UTF_8))
                    .let { it as? kotlinx.serialization.json.JsonObject ?: return }
            val deps =
                obj["dependencies"].let { (it as? kotlinx.serialization.json.JsonObject)?.keys.orEmpty() } +
                    obj["devDependencies"].let { (it as? kotlinx.serialization.json.JsonObject)?.keys.orEmpty() }

            // Test runners
            if (deps.any { it.startsWith("jest") || it == "vitest" }) {
                sink.getOrPut("testFrameworks") { mutableSetOf() }.add(if (deps.any { it.startsWith("jest") }) "Jest" else "Vitest")
            }
            if (deps.contains("mocha")) sink.getOrPut("testFrameworks") { mutableSetOf() }.add("Mocha")
            if (deps.any { it.startsWith("@playwright/test") }) sink.getOrPut("testFrameworks") { mutableSetOf() }.add("Playwright")
            if (deps.contains("cypress")) sink.getOrPut("testFrameworks") { mutableSetOf() }.add("Cypress")

            // Frameworks
            if (deps.contains("react")) sink.getOrPut("frameworks") { mutableSetOf() }.add("React")
            if (deps.contains("vue")) sink.getOrPut("frameworks") { mutableSetOf() }.add("Vue")
            if (deps.contains("@angular/core")) sink.getOrPut("frameworks") { mutableSetOf() }.add("Angular")
            if (deps.contains("svelte")) sink.getOrPut("frameworks") { mutableSetOf() }.add("Svelte")
            if (deps.contains("next")) sink.getOrPut("frameworks") { mutableSetOf() }.add("Next.js")
            if (deps.contains("nuxt")) sink.getOrPut("frameworks") { mutableSetOf() }.add("Nuxt")
            if (deps.contains("express")) sink.getOrPut("frameworks") { mutableSetOf() }.add("Express")
        } catch (_: Exception) {
            // Malformed package.json is not a hard failure; we just lose the
            // dependency-driven findings for that project.
        }
    }
}

private object ProjectDetectJson {
    fun encode(report: ProjectReport): String =
        buildJsonObject {
            put("root", report.root)
            put("languages", buildJsonArray { report.languages.forEach { add(it) } })
            put("buildTools", buildJsonArray { report.buildTools.forEach { add(it) } })
            put("packageManagers", buildJsonArray { report.packageManagers.forEach { add(it) } })
            put("testFrameworks", buildJsonArray { report.testFrameworks.forEach { add(it) } })
            put("frameworks", buildJsonArray { report.frameworks.forEach { add(it) } })
            put("markers", buildJsonArray { report.markers.forEach { add(it) } })
        }.toString()
}
