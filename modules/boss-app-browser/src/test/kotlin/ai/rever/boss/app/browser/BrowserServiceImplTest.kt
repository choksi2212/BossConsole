package ai.rever.boss.app.browser

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.BrowserNavigationEvent
import ai.rever.boss.ipc.proto.services.GetFaviconRequest
import ai.rever.boss.ipc.proto.services.NavigateBrowserRequest
import ai.rever.boss.ipc.proto.services.NavigationEventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests the security and state fixes for issue #911:
 *  - `javascript:` (and `data:`) URLs are rejected by [BrowserServiceImpl.navigate]
 *    before they reach the engine; only the allowlist passes.
 *  - URLs are masked through [BrowserServiceImpl.redactUserInfo] before they hit the log,
 *    so embedded userinfo (e.g. `https://user:Secret@host/`) never reaches the log file.
 *  - [BrowserServiceImpl.reload] clears its loading flag and emits the matching COMPLETED
 *    event so consumers of `getPageInfo` do not see `isLoading=true` forever.
 *
 * The gRPC request/response classes come from `:boss-ipc`'s generated proto sources; the
 * service is constructed directly because the IPC transport is irrelevant to these checks.
 */
class BrowserServiceImplTest {
    private val service = BrowserServiceImpl()

    // ---- schemeFor ---------------------------------------------------------

    @Test
    fun `schemeFor recognises http and https`() {
        assertEquals("http", BrowserServiceImpl.schemeFor("http://example.com/"))
        assertEquals("https", BrowserServiceImpl.schemeFor("https://example.com/path"))
    }

    @Test
    fun `schemeFor lowercases the scheme`() {
        assertEquals("https", BrowserServiceImpl.schemeFor("HTTPS://example.com/"))
    }

    @Test
    fun `schemeFor recognises schemes without an authority separator`() {
        // RFC 3986: a scheme is followed by ':'. javascript: and data: never carry '://'.
        assertEquals("javascript", BrowserServiceImpl.schemeFor("javascript:alert(document.domain)"))
        assertEquals("data", BrowserServiceImpl.schemeFor("data:text/html,<script>alert(1)</script>"))
        assertEquals("file", BrowserServiceImpl.schemeFor("file:///tmp/x.html"))
    }

    @Test
    fun `schemeFor returns null for relative URLs and non-URL strings`() {
        assertEquals(null, BrowserServiceImpl.schemeFor("example.com/page"))
        assertEquals(null, BrowserServiceImpl.schemeFor("/absolute/path"))
        assertEquals(null, BrowserServiceImpl.schemeFor(""))
        assertEquals(null, BrowserServiceImpl.schemeFor(":no-scheme"))
        // digits are not valid as the first character of a scheme (RFC 3986)
        assertEquals(null, BrowserServiceImpl.schemeFor("1http://example.com/"))
    }

    // ---- redactUserInfo ----------------------------------------------------

    @Test
    fun `redactUserInfo replaces user-pass-host with REDACTED at-host`() {
        assertEquals(
            "https://[REDACTED]@internal-host/",
            BrowserServiceImpl.redactUserInfo("https://admin:Secret123@internal-host/"),
        )
    }

    @Test
    fun `redactUserInfo handles URLs without credentials unchanged`() {
        assertEquals(
            "https://example.com/path?q=1",
            BrowserServiceImpl.redactUserInfo("https://example.com/path?q=1"),
        )
    }

    @Test
    fun `redactUserInfo handles ftp and file schemes`() {
        assertEquals(
            "ftp://[REDACTED]@ftp.example.com/pub",
            BrowserServiceImpl.redactUserInfo("ftp://user:pw@ftp.example.com/pub"),
        )
        // file:// URLs usually carry no userinfo, but the redaction must not corrupt them either way.
        assertEquals(
            "file:///tmp/x.html",
            BrowserServiceImpl.redactUserInfo("file:///tmp/x.html"),
        )
    }

    @Test
    fun `redactUserInfo returns non-URL input unchanged`() {
        // URIs that throw on construction must fall back to the original string
        // so logging never panics on a malformed value.
        assertEquals("not a url at all", BrowserServiceImpl.redactUserInfo("not a url at all"))
        assertEquals("", BrowserServiceImpl.redactUserInfo(""))
    }

    @Test
    fun `redactUserInfo leaves a bare javascript scheme alone for redaction`() {
        // The point of javascript: being refused is that nothing about it reaches a log.
        // redactUserInfo on its own must not panic on a non-URL scheme-only string.
        assertEquals(
            "javascript:alert(document.domain)",
            BrowserServiceImpl.redactUserInfo("javascript:alert(document.domain)"),
        )
    }

    @Test
    fun `redactUserInfo redacts credentials when URI parser rejects the input for whitespace`() {
        // URI("https://user:Secret@host/ bad") throws URISyntaxException on the space.
        // A naive "unchanged on parse failure" fallback would leak "Secret" to the log;
        // the parser-independent redaction must still mask the credential.
        val redacted = BrowserServiceImpl.redactUserInfo("https://user:Secret@host/ bad")
        assertFalse(
            redacted.contains("Secret"),
            "credential leaked through whitespace in URL, was: $redacted",
        )
        assertTrue(
            redacted.contains("[REDACTED]@"),
            "redaction marker must appear before the host, was: $redacted",
        )
    }

    @Test
    fun `redactUserInfo redacts credentials when URI parser rejects the input for invalid percent`() {
        // A trailing '%' is an invalid percent escape; URI throws on it. The
        // parser-independent path must still find the LAST '@' and redact it.
        val redacted = BrowserServiceImpl.redactUserInfo("https://user:Secret@host/%")
        assertFalse(
            redacted.contains("Secret"),
            "credential leaked through invalid percent in URL, was: $redacted",
        )
        assertTrue(
            redacted.contains("[REDACTED]@"),
            "redaction marker must appear before the host, was: $redacted",
        )
    }

    @Test
    fun `redactUserInfo redacts through the LAST at sign in the authority`() {
        // RFC 3986: userinfo ends at the LAST '@' before the host. Multiple '@'
        // characters in the authority therefore name everything up to the last one
        // as userinfo.
        val redacted = BrowserServiceImpl.redactUserInfo("https://a:b@host@cms/path?q=1")
        assertFalse(
            redacted.contains("a:b"),
            "userinfo before the last '@' leaked, was: $redacted",
        )
        assertTrue(
            redacted.contains("[REDACTED]@"),
            "redaction marker must replace the userinfo, was: $redacted",
        )
        assertTrue(
            redacted.contains("@cms/path"),
            "host part after the last '@' must be preserved, was: $redacted",
        )
    }

    // ---- schemeFor: ASCII control and smuggling cases ---------------------

    @Test
    fun `schemeFor rejects ASCII tab CR LF in the scheme`() {
        // Smuggling case: a tab inside the scheme name must not pass the letter
        // / digit / punctuation test.
        assertEquals(null, BrowserServiceImpl.schemeFor("java\tscript:alert(1)"))
        assertEquals(null, BrowserServiceImpl.schemeFor("java\nscript:alert(1)"))
        assertEquals(null, BrowserServiceImpl.schemeFor("java\rscript:alert(1)"))
        assertEquals(null, BrowserServiceImpl.schemeFor("\thttps://example.com/"))
    }

    @Test
    fun `schemeFor rejects scheme names that start with a digit or punctuation`() {
        // Smuggling case: a non-letter first char cannot form an RFC 3986 scheme.
        assertEquals(null, BrowserServiceImpl.schemeFor("1http://example.com/"))
        assertEquals(null, BrowserServiceImpl.schemeFor("+http://example.com/"))
        assertEquals(null, BrowserServiceImpl.schemeFor(".http://example.com/"))
    }

    // ---- navigate: scheme gate --------------------------------------------

    @Test
    fun `navigate refuses javascript URLs`() =
        runBlocking {
            val response =
                service.navigate(
                    NavigateBrowserRequest
                        .newBuilder()
                        .setWindowId("win-1")
                        .setUrl("javascript:alert(document.domain)")
                        .build(),
                )
            assertFalse(response.success, "javascript: must be refused")
            assertTrue(
                response.errorMessage.contains("javascript"),
                "error message should name the rejected scheme, was: ${response.errorMessage}",
            )
        }

    @Test
    fun `navigate refuses data URLs`() =
        runBlocking {
            val response =
                service.navigate(
                    NavigateBrowserRequest
                        .newBuilder()
                        .setWindowId("win-1")
                        .setUrl("data:text/html,<script>alert(1)</script>")
                        .build(),
                )
            assertFalse(response.success, "data: must be refused")
            assertTrue(response.errorMessage.contains("data"), "error should name scheme")
        }

    @Test
    fun `navigate refuses relative URLs and bare paths`() =
        runBlocking {
            val response =
                service.navigate(
                    NavigateBrowserRequest
                        .newBuilder()
                        .setWindowId("win-1")
                        .setUrl("/relative/path")
                        .build(),
                )
            assertFalse(response.success, "URLs without a scheme must be refused")
        }

    @Test
    fun `navigate accepts http and https`() =
        runBlocking {
            val http =
                service.navigate(
                    NavigateBrowserRequest
                        .newBuilder()
                        .setWindowId("win-1")
                        .setUrl("http://example.com/")
                        .build(),
                )
            assertTrue(http.success)
            assertEquals("http://example.com/", http.finalUrl)

            val https =
                service.navigate(
                    NavigateBrowserRequest
                        .newBuilder()
                        .setWindowId("win-1")
                        .setUrl("https://example.com/path")
                        .build(),
                )
            assertTrue(https.success)
            assertEquals("https://example.com/path", https.finalUrl)
        }

    @Test
    fun `navigate accepts file and ftp URLs`() =
        runBlocking {
            val file =
                service.navigate(
                    NavigateBrowserRequest
                        .newBuilder()
                        .setWindowId("win-1")
                        .setUrl("file:///tmp/page.html")
                        .build(),
                )
            assertTrue(file.success, "file: must be allowed")

            val ftp =
                service.navigate(
                    NavigateBrowserRequest
                        .newBuilder()
                        .setWindowId("win-1")
                        .setUrl("ftp://ftp.example.com/pub/file")
                        .build(),
                )
            assertTrue(ftp.success, "ftp: must be allowed")
        }

    @Test
    fun `navigate rejects blank URLs`() =
        runBlocking {
            val response =
                service.navigate(
                    NavigateBrowserRequest
                        .newBuilder()
                        .setWindowId("win-1")
                        .setUrl("   ")
                        .build(),
                )
            assertFalse(response.success)
            assertTrue(response.errorMessage.contains("blank"))
        }

    @Test
    fun `a refused javascript URL records no page state`() =
        runBlocking {
            service.navigate(
                NavigateBrowserRequest
                    .newBuilder()
                    .setWindowId("win-refused")
                    .setUrl("javascript:alert(1)")
                    .build(),
            )
            // The refused request never writes to windowStates, so getPageInfo returns
            // the empty default - no loading flag set, no URL recorded.
            val info = service.getPageInfo(Empty.getDefaultInstance())
            assertFalse(info.isLoading, "no loading state after a refused navigation")
        }

    @Test
    fun `a refused navigation emits no navigation events`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val collected = mutableListOf<BrowserNavigationEvent>()
            val job =
                scope.launch {
                    service
                        .onNavigationEvent(Empty.getDefaultInstance())
                        .take(1)
                        .toList(collected)
                }
            kotlinx.coroutines.yield()
            service.navigate(
                NavigateBrowserRequest
                    .newBuilder()
                    .setWindowId("win-refused-events")
                    .setUrl("javascript:alert(1)")
                    .build(),
            )
            // Wait a beat: nothing should arrive, then cancel. A STARTED/COMPLETED pair
            // would have shown up within the flow's 128-entry buffer.
            withTimeoutOrNull(100) { job.join() }
            scope.cancel()
            assertTrue(
                collected.isEmpty(),
                "refused navigation must not emit a navigation event, got $collected",
            )
        }

    // ---- reload: loading flag and COMPLETED event -------------------------

    @Test
    fun `reload resets isLoading to false after the STARTED event`() =
        runBlocking {
            service.navigate(
                NavigateBrowserRequest
                    .newBuilder()
                    .setWindowId("win-reload-state")
                    .setUrl("https://example.com/page")
                    .build(),
            )

            service.reload(Empty.getDefaultInstance())

            // Reload must end with isLoading=false so consumers of getPageInfo do not
            // see the page stuck loading forever (issue #911 reproduction).
            val info = service.getPageInfo(Empty.getDefaultInstance())
            assertFalse(info.isLoading, "reload must clear isLoading")
            assertEquals("https://example.com/page", info.url)
        }

    @Test
    fun `reload emits STARTED then COMPLETED in order`() =
        runBlocking {
            service.navigate(
                NavigateBrowserRequest
                    .newBuilder()
                    .setWindowId("win-ordered")
                    .setUrl("https://example.com/")
                    .build(),
            )

            // Subscribe BEFORE triggering reload so the SharedFlow's tryEmit reaches the
            // collector. The flow is hot and has no replay, so order of subscribe/emit
            // matters.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val collected = mutableListOf<BrowserNavigationEvent>()
            val job =
                scope.launch {
                    service
                        .onNavigationEvent(Empty.getDefaultInstance())
                        .take(2)
                        .toList(collected)
                }
            // Give the subscriber a chance to register.
            kotlinx.coroutines.yield()
            service.reload(Empty.getDefaultInstance())
            withTimeoutOrNull(500) { job.join() }
            scope.cancel()

            assertEquals(2, collected.size, "expected STARTED and COMPLETED, got $collected")
            assertEquals(NavigationEventType.NAVIGATION_EVENT_TYPE_STARTED, collected[0].eventType)
            assertEquals(NavigationEventType.NAVIGATION_EVENT_TYPE_COMPLETED, collected[1].eventType)
        }

    @Test
    fun `two consecutive reloads both clear isLoading`() =
        runBlocking {
            service.navigate(
                NavigateBrowserRequest
                    .newBuilder()
                    .setWindowId("win-double-reload")
                    .setUrl("https://example.com/")
                    .build(),
            )

            service.reload(Empty.getDefaultInstance())
            assertFalse(service.getPageInfo(Empty.getDefaultInstance()).isLoading)
            service.reload(Empty.getDefaultInstance())
            assertFalse(
                service.getPageInfo(Empty.getDefaultInstance()).isLoading,
                "second reload must also clear isLoading",
            )
        }

    @Test
    fun `reload with no window state is a no-op`() =
        runBlocking {
            // The service may already have state from earlier tests in this run; the
            // contract is that reload() never throws and getPageInfo() remains valid.
            service.reload(Empty.getDefaultInstance())
            val info = service.getPageInfo(Empty.getDefaultInstance())
            // isLoading must be false (no pending load).
            assertFalse(info.isLoading)
        }

    // ---- getFavicon --------------------------------------------------------

    @Test
    fun `getFavicon returns empty bytes and the helper masks userinfo`() =
        runBlocking {
            val response =
                service.getFavicon(
                    GetFaviconRequest
                        .newBuilder()
                        .setUrl("https://admin:Secret@internal/")
                        .build(),
                )
            assertEquals(0, response.faviconBytes.size())
            assertEquals("", response.contentType)
            // The contract that prevents the credential reaching the log is the helper
            // itself, which is exercised by the redactUserInfo tests above.
            assertEquals(
                "https://[REDACTED]@internal/",
                BrowserServiceImpl.redactUserInfo("https://admin:Secret@internal/"),
            )
        }
}
