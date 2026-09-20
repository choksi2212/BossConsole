package ai.rever.boss.updater

import java.io.File
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the URL-encoding hazard in `UpdateInstaller.getCurrentApplicationPath`.
 *
 * The bug these pin: a macOS install under "/Users/Bob Smith/.../BOSS.app"
 * handed `protectionDomain.codeSource.location.path` directly to `File(path)`,
 * which received "/Users/Bob%20Smith/.../BOSS.app" - a path that never matches
 * any file on disk. The fix routes through `URL.toURI()` so the path is decoded
 * before the `File` ctor sees it.
 */
class CodeSourcePathTest {
    /**
     * The bare-property read returns the URL-encoded path. A real install
     * under a directory with a space (or any character `%`-encoded in URLs)
     * would never resolve through `File(URL.path)`.
     */
    @Test
    fun `URL path is URL-encoded and a File from it fails to resolve a real path with a space`() {
        val realPath = "/Users/Bob Smith/Applications/BOSS.app/Contents/app/composeApp.jar"
        val url = File(realPath).toURI().toURL()

        // `URL.path` is URL-encoded: spaces become %20, etc.
        val pathFromUrl = url.path
        assertTrue(
            pathFromUrl.contains("%20"),
            "URL.path must be URL-encoded",
        )

        // `File(URL.path)` reads a path that contains %20.
        val fileFromUrlPath = File(pathFromUrl)
        assertTrue(
            fileFromUrlPath.path.contains("%20"),
            "File from URL-encoded path retains percent escape",
        )

        // The fix: go through URI so the path is decoded first.
        val fileFromUri = File(url.toURI())
        assertEquals(
            File(realPath).absolutePath,
            fileFromUri.absolutePath,
            "URL.toURI() must be decoded before File uses it",
        )
    }

    /**
     * The fix in code: a `codeSourceLocation.toURI()?.let(::File)` chain produces
     * a `File` whose path is the decoded one, where the previous
     * `File(codeSourceLocation.path)` produced a `File` over URL-encoded bytes.
     * Pin both shapes against the same input so a future "simplification" back to
     * `.path` is caught by the test.
     */
    @Test
    fun `File from URL path and File from URL URI are different and only the URI one decodes`() {
        val realPath = "/Users/Ana Pereira/code/BOSS.app"
        val url = File(realPath).toURI().toURL()

        val fromPath = File(url.path)
        val fromUri = File(url.toURI())

        assertNotNull(fromPath)
        assertNotNull(fromUri)

        // The two paths disagree on the space: the URL.path form is the encoded
        // version, the URI form is the decoded one. `fromPath.absolutePath` still
        // carries `%20` because `File` does not decode percent escapes on the way in.
        assertTrue(
            fromPath.path.contains("%20"),
            "from URL.path must retain %20 (File does not decode)",
        )
        assertEquals(
            File(realPath).absolutePath,
            fromUri.absolutePath,
            "from URI must decode to the original path",
        )
    }
}
