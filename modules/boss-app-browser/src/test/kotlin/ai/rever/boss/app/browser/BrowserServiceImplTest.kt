package ai.rever.boss.app.browser

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.BrowserNavigationEvent
import ai.rever.boss.ipc.proto.services.NavigateBrowserRequest
import ai.rever.boss.ipc.proto.services.NavigationEventType
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The scheme gate and the page-state contract of the gRPC browser service (#911).
 *
 * `javascript:` executes script in the page's own context and `data:` smuggles a document,
 * so Navigate refuses both — and every other scheme outside the allowlist — before any
 * state is written, any event is emitted or the URL is logged. What the gate lets through
 * still reaches the response and the navigation events untouched: redaction belongs to the
 * log, not to the service contract.
 */
class BrowserServiceImplTest {
    private val startedThenCompleted =
        listOf(
            NavigationEventType.NAVIGATION_EVENT_TYPE_STARTED,
            NavigationEventType.NAVIGATION_EVENT_TYPE_COMPLETED,
        )

    private val navigableUrls =
        listOf(
            "http://a.example/",
            "https://b.example/p",
        )

    private fun navigateRequest(url: String): NavigateBrowserRequest =
        NavigateBrowserRequest
            .newBuilder()
            .setUrl(url)
            .setWindowId("w1")
            .build()

    /** Runs [block] with a live navigation-event subscription, and returns what it emitted. */
    private suspend fun recordedEvents(
        service: BrowserServiceImpl,
        block: suspend () -> Unit,
    ): List<BrowserNavigationEvent> =
        coroutineScope {
            val events = mutableListOf<BrowserNavigationEvent>()
            val collector =
                launch {
                    service.onNavigationEvent(Empty.getDefaultInstance()).collect { events.add(it) }
                }
            // The SharedFlow has no replay, so the collector must be live before block runs…
            delay(100)
            block()
            // …and its emissions resume the collector through this dispatcher's queue, so
            // the drain must finish before the collector is cancelled.
            delay(100)
            collector.cancel()
            events.toList()
        }

    @Test
    fun `navigable schemes navigate and emit started then completed`() {
        runBlocking {
            for (url in navigableUrls) {
                val service = BrowserServiceImpl()
                val events =
                    recordedEvents(service) {
                        val response = service.navigate(navigateRequest(url))
                        assertTrue(response.success, url)
                        assertEquals(url, response.finalUrl, url)
                    }
                assertEquals(startedThenCompleted, events.map { it.eventType }, url)
                val info = service.getPageInfo(Empty.getDefaultInstance())
                assertEquals(url, info.url, url)
                assertFalse(info.isLoading, url)
            }
        }
    }

    @Test
    fun `javascript and data urls are refused before any state or event`() {
        runBlocking {
            for (url in listOf("javascript:alert(document.domain)", "data:text/html,<b>hi</b>")) {
                val service = BrowserServiceImpl()
                val events =
                    recordedEvents(service) {
                        val response = service.navigate(navigateRequest(url))
                        assertFalse(response.success, url)
                        assertTrue(response.errorMessage.contains("not navigable"), url)
                    }
                assertTrue(events.isEmpty(), url)
                assertEquals("", service.getPageInfo(Empty.getDefaultInstance()).url, url)
            }
        }
    }

    @Test
    fun `a scheme hidden behind case or a control character is still refused`() {
        runBlocking {
            val sneaky =
                listOf(
                    "JAVASCRIPT:alert(document.domain)",
                    "jav\tascript:alert(document.domain)",
                    "java\nscript:alert(document.domain)",
                    "\u0000javascript:alert(document.domain)",
                )
            for (url in sneaky) {
                val service = BrowserServiceImpl()
                val response = service.navigate(navigateRequest(url))
                assertFalse(response.success, url)
                assertEquals("", response.finalUrl, url)
            }
        }
    }

    @Test
    fun `schemeless and blank urls are refused`() {
        runBlocking {
            for (url in listOf("example.com/why", "localhost:8080", "   ", "")) {
                val service = BrowserServiceImpl()
                val response = service.navigate(navigateRequest(url))
                assertFalse(response.success, url)
                assertTrue(response.errorMessage.isNotEmpty(), url)
            }
        }
    }

    @Test
    fun `file and ftp urls are refused like javascript`() {
        runBlocking {
            for (url in listOf("file:///etc/passwd", "ftp://files.example/x")) {
                val service = BrowserServiceImpl()
                val response = service.navigate(navigateRequest(url))
                assertFalse(response.success, url)
                assertTrue(response.errorMessage.contains("not navigable"), url)
            }
        }
    }

    @Test
    fun `a refusal message carries the scheme name and nothing else from the url`() {
        runBlocking {
            val service = BrowserServiceImpl()
            val response = service.navigate(navigateRequest("javascript:alert(document.cookie)"))
            assertFalse(response.success)
            assertTrue(response.errorMessage.contains("javascript"), response.errorMessage)
            assertFalse(
                "alert(document.cookie)" in response.errorMessage,
                "the payload must not reach the error message: ${response.errorMessage}",
            )
        }
    }

    @Test
    fun `an overlong scheme is refused and capped in the message`() {
        runBlocking {
            val scheme = "x".repeat(200)
            val service = BrowserServiceImpl()
            val response = service.navigate(navigateRequest("$scheme:payload"))
            assertFalse(response.success)
            assertTrue(response.errorMessage.contains("'x"), response.errorMessage)
            val capped = "x".repeat(33)
            assertFalse(capped in response.errorMessage, "the echoed scheme is capped at 32 chars")
        }
    }

    @Test
    fun `a refused navigate leaves the current page alone`() {
        runBlocking {
            val service = BrowserServiceImpl()
            assertTrue(service.navigate(navigateRequest("https://good.example/")).success)
            assertFalse(service.navigate(navigateRequest("javascript:alert(1)")).success)
            val info = service.getPageInfo(Empty.getDefaultInstance())
            assertEquals("https://good.example/", info.url)
            assertFalse(info.isLoading)
        }
    }

    @Test
    fun `reload reports the page settled and never wedges it loading`() {
        runBlocking {
            val service = BrowserServiceImpl()
            assertTrue(service.navigate(navigateRequest("https://example.com/")).success)
            service.reload(Empty.getDefaultInstance())
            val events =
                recordedEvents(service) {
                    service.reload(Empty.getDefaultInstance())
                }
            assertEquals(startedThenCompleted, events.map { it.eventType })
            assertFalse(service.getPageInfo(Empty.getDefaultInstance()).isLoading)
        }
    }

    @Test
    fun `the response contract is not redacted - userinfo stays in finalUrl and events`() {
        runBlocking {
            val url = "https://user:secret@example.com/path?q=1"
            val service = BrowserServiceImpl()
            val events =
                recordedEvents(service) {
                    val response = service.navigate(navigateRequest(url))
                    assertTrue(response.success)
                    assertEquals(url, response.finalUrl)
                }
            assertEquals(listOf(url, url), events.map { it.url })
        }
    }

    /**
     * #911's leak is one missing call in an argument list, so the log wiring is pinned by a
     * source check rather than review vigilance - the same reason composeApp's
     * BrowserUrlLogConventionTest scans its guarded files.
     *
     * It requires describeUri, not the weaker redactUrlUserInfo: the convention test was
     * written specifically to reject sanitizers that leave the query and fragment (an
     * OAuth code, a presigned signature) in the log, and describeUri is the one that
     * drops them - plus the userinfo, for the `https:user:secret@host` shape that
     * redactUrlUserInfo returns unchanged.
     */
    @Test
    fun `every url logged by the service goes through describeUri`() {
        val offenders =
            serviceSource()
                .readText()
                .lineSequence()
                .filter { "logger." in it && "url={}" in it }
                .filterNot { "LogSanitizer.describeUri(" in it }
                .toList()
        assertEquals(emptyList<String>(), offenders)
    }

    private fun serviceSource(): File {
        var dir = File(System.getProperty("user.dir"))
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
        }
        val root = checkNotNull(dir) { "repo root not found above ${System.getProperty("user.dir")}" }
        val source =
            File(
                root,
                "modules/boss-app-browser/src/main/kotlin/ai/rever/boss/app/browser/BrowserServiceImpl.kt",
            )
        check(source.isFile) { "guarded file moved or renamed" }
        return source
    }
}
