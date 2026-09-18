package ai.rever.boss.app.browser

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.NavigateBrowserRequest
import ai.rever.boss.ipc.proto.services.NavigationEventType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for the navigate scheme gate and the reload completion
 * cycle (#911): `javascript:`/`data:` URLs are refused before any state is
 * touched, and reload no longer strands a window in `loading`.
 */
class BrowserServiceImplNavigateTest {
    private val service = BrowserServiceImpl()

    @Test
    fun `javascript URLs are refused without touching window state`() =
        runBlocking {
            val req =
                NavigateBrowserRequest
                    .newBuilder()
                    .setWindowId("w1")
                    .setUrl("javascript:alert(document.domain)")
                    .build()
            val response = service.navigate(req)

            assertFalse(response.success)
            assertTrue(response.errorMessage.contains("unsupported URL scheme"))
            // No navigation events may be emitted for a refused URL: take the
            // first emission within a short window instead of collecting the
            // (unbounded) flow forever.
            val first = withTimeoutOrNull(500) { service.onNavigationEvent(Empty.getDefaultInstance()).first() }
            assertNull(first, "expected no navigation events, got $first")
        }

    @Test
    fun `data URLs are refused`() =
        runBlocking {
            val req =
                NavigateBrowserRequest
                    .newBuilder()
                    .setWindowId("w1")
                    .setUrl("data:text/html,<script>1</script>")
                    .build()
            val response = service.navigate(req)

            assertFalse(response.success)
        }

    @Test
    fun `http and https URLs still navigate`() =
        runBlocking {
            val req =
                NavigateBrowserRequest
                    .newBuilder()
                    .setWindowId("w1")
                    .setUrl("https://example.com")
                    .build()
            val ok = service.navigate(req)
            assertTrue(ok.success)
            assertEquals("https://example.com", ok.finalUrl)
        }

    @Test
    fun `reload completes the loading cycle instead of stranding the window`() =
        runBlocking {
            val req =
                NavigateBrowserRequest
                    .newBuilder()
                    .setWindowId("w1")
                    .setUrl("https://example.com")
                    .build()
            service.navigate(req)
            service.reload(Empty.getDefaultInstance())

            val info = service.getPageInfo(Empty.getDefaultInstance())
            assertFalse(info.isLoading, "window must not report loading forever after reload")
        }
}
