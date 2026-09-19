package ai.rever.boss.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BossProjectDetectTest {
    private val detector = ProjectDetector()
    private val tempDirs = mutableListOf<File>()

    private fun tempDir(): File {
        val d = Files.createTempDirectory("boss-project-detect-test").toFile()
        tempDirs += d
        return d
    }

    private fun writeFile(
        parent: File,
        relativePath: String,
        content: String = "",
    ): File {
        val target = File(parent, relativePath)
        target.parentFile?.mkdirs()
        target.writeText(content)
        return target
    }

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `kotlin gradle project is detected`() {
        val root = tempDir()
        writeFile(root, "build.gradle.kts")
        writeFile(root, "settings.gradle.kts")
        val report = detector.detect(root)
        assertTrue("Kotlin" in report.languages, "expected Kotlin in ${report.languages}")
        assertTrue("Gradle (Kotlin DSL)" in report.buildTools, "expected Gradle in ${report.buildTools}")
        assertTrue(report.markers.contains("build.gradle.kts"))
    }

    @Test
    fun `maven project is detected`() {
        val root = tempDir()
        writeFile(root, "pom.xml")
        val report = detector.detect(root)
        assertTrue("Java" in report.languages)
        assertTrue("Maven" in report.buildTools)
        assertTrue("Maven" in report.packageManagers)
    }

    @Test
    fun `rust cargo project is detected`() {
        val root = tempDir()
        writeFile(root, "Cargo.toml")
        writeFile(root, "Cargo.lock")
        val report = detector.detect(root)
        assertTrue("Rust" in report.languages)
        assertTrue("Cargo" in report.buildTools)
        assertTrue("Cargo" in report.packageManagers)
    }

    @Test
    fun `node project with package-lock is detected as npm`() {
        val root = tempDir()
        writeFile(root, "package.json")
        writeFile(root, "package-lock.json")
        val report = detector.detect(root)
        assertTrue("JavaScript" in report.languages)
        assertTrue("npm" in report.packageManagers)
    }

    @Test
    fun `node project with yarn lockfile is detected as Yarn`() {
        val root = tempDir()
        writeFile(root, "package.json")
        writeFile(root, "yarn.lock")
        val report = detector.detect(root)
        assertTrue("Yarn" in report.packageManagers)
        assertFalse("package-lock.json" in report.markers, "yarn project has no package-lock.json marker")
    }

    @Test
    fun `pnpm lockfile wins over yarn lockfile`() {
        val root = tempDir()
        writeFile(root, "package.json")
        writeFile(root, "yarn.lock")
        writeFile(root, "pnpm-lock.yaml")
        val report = detector.detect(root)
        assertTrue("pnpm" in report.packageManagers)
        assertTrue("Yarn" in report.packageManagers, "all three lockfiles can coexist, report all")
    }

    @Test
    fun `typescript is detected via tsconfig`() {
        val root = tempDir()
        writeFile(root, "package.json")
        writeFile(root, "tsconfig.json")
        val report = detector.detect(root)
        assertTrue("TypeScript" in report.languages)
        assertTrue("JavaScript" in report.languages, "TS implies JS too")
    }

    @Test
    fun `python project with poetry is detected`() {
        val root = tempDir()
        writeFile(root, "pyproject.toml")
        writeFile(root, "poetry.lock")
        val report = detector.detect(root)
        assertTrue("Python" in report.languages)
        assertTrue("poetry" in report.packageManagers)
    }

    @Test
    fun `pytest is detected as a test framework`() {
        val root = tempDir()
        writeFile(root, "pytest.ini")
        val report = detector.detect(root)
        assertTrue("pytest" in report.testFrameworks)
    }

    @Test
    fun `go project with _test dot go files is detected with Go testing`() {
        val root = tempDir()
        writeFile(root, "go.mod")
        writeFile(root, "main.go")
        writeFile(root, "main_test.go")
        val report = detector.detect(root)
        assertTrue("Go" in report.languages)
        assertTrue("Go testing" in report.testFrameworks, "_test.go implies Go testing")
    }

    @Test
    fun `package-json dependencies feed test framework detection`() {
        val root = tempDir()
        writeFile(
            root,
            "package.json",
            """{"name":"x","devDependencies":{"jest":"^29.0.0","@playwright/test":"^1.0.0"}}""",
        )
        val report = detector.detect(root)
        assertTrue("Jest" in report.testFrameworks)
        assertTrue("Playwright" in report.testFrameworks)
    }

    @Test
    fun `package-json dependencies feed framework detection`() {
        val root = tempDir()
        writeFile(
            root,
            "package.json",
            """{"name":"x","dependencies":{"react":"^18.0.0","next":"^14.0.0"}}""",
        )
        val report = detector.detect(root)
        assertTrue("React" in report.frameworks)
        assertTrue("Next.js" in report.frameworks)
    }

    @Test
    fun `angular is detected from angular dot json`() {
        val root = tempDir()
        writeFile(root, "angular.json")
        val report = detector.detect(root)
        assertTrue("Angular" in report.frameworks)
    }

    @Test
    fun `nested build file in a subdirectory still counts the project`() {
        val root = tempDir()
        writeFile(root, "subdir/build.gradle.kts")
        val report = detector.detect(root)
        assertTrue("Kotlin" in report.languages)
        assertTrue("Gradle (Kotlin DSL)" in report.buildTools)
    }

    @Test
    fun `a directory with no markers reports empty buckets`() {
        val root = tempDir()
        writeFile(root, "README.md")
        val report = detector.detect(root)
        assertEquals(emptyList(), report.languages)
        assertEquals(emptyList(), report.buildTools)
        assertEquals(emptyList(), report.packageManagers)
        assertEquals(emptyList(), report.testFrameworks)
        assertEquals(emptyList(), report.frameworks)
    }

    @Test
    fun `mixed java and kotlin gradle project lists both languages`() {
        val root = tempDir()
        writeFile(root, "build.gradle.kts")
        val report = detector.detect(root)
        assertTrue("Kotlin" in report.languages)
        assertTrue("Java" in report.languages)
    }

    @Test
    fun `marker files list is sorted alphabetically`() {
        val root = tempDir()
        writeFile(root, "Cargo.toml")
        writeFile(root, "build.gradle.kts")
        writeFile(root, "package.json")
        val report = detector.detect(root)
        assertEquals(report.markers.sorted(), report.markers, "markers must be sorted")
    }
}
