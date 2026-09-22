package ai.rever.boss.app.browser

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import com.google.protobuf.ByteString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * gRPC implementation of BrowserService.
 *
 * Tracks navigation state per window using an in-memory map and streams
 * events via a SharedFlow. JxBrowser runs in the composeApp process -
 * this service handles routing and state management for multi-window
 * browser coordination over IPC.
 *
 * Security: [navigate] refuses schemes outside [ALLOWED_NAVIGATION_SCHEMES]
 * BEFORE writing any state, emitting any navigation event, or logging the
 * resolved URL - so `javascript:` (which the engine would otherwise execute
 * in the page context) and `data:` (which it would load) never reach the
 * engine. The refusal log itself goes through [redactUserInfo] so an
 * embedded credential in the refused URL still does not reach the log
 * file. [reload] resets its loading flag and emits the matching COMPLETED
 * event so consumers of [getPageInfo] do not see `isLoading=true` forever.
 * See issue #911.
 */
@Suppress("TooManyFunctions")
class BrowserServiceImpl : BrowserServiceGrpcKt.BrowserServiceCoroutineImplBase() {
    private val logger = LoggerFactory.getLogger(BrowserServiceImpl::class.java)

    /** Per-window page state snapshot. */
    private data class PageState(
        val windowId: String,
        val url: String,
        val title: String,
        val canGoBack: Boolean = false,
        val canGoForward: Boolean = false,
        val isLoading: Boolean = false,
    )

    private val windowStates = ConcurrentHashMap<String, PageState>()
    private val navigationEvents = MutableSharedFlow<BrowserNavigationEvent>(extraBufferCapacity = 128)

    override suspend fun navigate(request: NavigateBrowserRequest): NavigateBrowserResponse {
        val url = request.url.trim()
        val refusal = validateNavigation(request.windowId, url)
        if (refusal != null) return refusal

        logger.info("navigate: windowId={}, url={}", request.windowId, redactUserInfo(url))

        val prev = windowStates[request.windowId]
        val newState =
            PageState(
                windowId = request.windowId,
                url = url,
                title = url,
                canGoBack = prev != null,
            )
        windowStates[request.windowId] = newState

        emitStartAndComplete(request.windowId, url, url)
        return buildSuccess(url)
    }

    override suspend fun executeJS(request: ExecuteJSRequest): ExecuteJSResponse {
        logger.debug("executeJS: windowId={}, scriptLen={}", request.windowId, request.script.length)
        // JS execution requires JxBrowser which runs in the composeApp process.
        return ExecuteJSResponse
            .newBuilder()
            .setSuccess(false)
            .setErrorMessage("JS execution requires JxBrowser (composeApp process)")
            .build()
    }

    override fun onNavigationEvent(request: Empty): Flow<BrowserNavigationEvent> =
        flow {
            navigationEvents.collect { event -> emit(event) }
        }

    override suspend fun getFavicon(request: GetFaviconRequest): GetFaviconResponse {
        logger.debug("getFavicon: url={}", redactUserInfo(request.url))
        return GetFaviconResponse
            .newBuilder()
            .setFaviconBytes(ByteString.EMPTY)
            .setContentType("")
            .build()
    }

    override suspend fun getPageInfo(request: Empty): PageInfoResponse {
        if (windowStates.size > 1) {
            logger.warn(
                "getPageInfo called with {} windows tracked - returning first window only; use a window-specific RPC for multi-window support",
                windowStates.size,
            )
        }
        val state = windowStates.values.firstOrNull()
        return PageInfoResponse
            .newBuilder()
            .setUrl(state?.url ?: "")
            .setTitle(state?.title ?: "")
            .setCanGoBack(state?.canGoBack ?: false)
            .setCanGoForward(state?.canGoForward ?: false)
            .setIsLoading(state?.isLoading ?: false)
            .build()
    }

    override suspend fun goBack(request: Empty): Empty {
        logger.debug("goBack")
        emitStartedOnly()
        return Empty.getDefaultInstance()
    }

    override suspend fun goForward(request: Empty): Empty {
        logger.debug("goForward")
        emitStartedOnly()
        return Empty.getDefaultInstance()
    }

    override suspend fun reload(request: Empty): Empty {
        logger.debug("reload")
        val state = windowStates.values.firstOrNull() ?: return Empty.getDefaultInstance()
        // Reload flips the slot to loading, then back to not-loading once the STARTED
        // event has been emitted. The actual engine load lives in another process; the
        // service here only mirrors its state, so consumers of getPageInfo() see
        // isLoading settle instead of staying true forever. The same STARTED-then-COMPLETED
        // pair navigate() emits covers any consumer tracking page-finished-loading.
        windowStates[state.windowId] = state.copy(isLoading = true)
        val ts = System.currentTimeMillis()
        navigationEvents.tryEmit(
            BrowserNavigationEvent
                .newBuilder()
                .setWindowId(state.windowId)
                .setUrl(state.url)
                .setTitle(state.title)
                .setEventType(NavigationEventType.NAVIGATION_EVENT_TYPE_STARTED)
                .setTimestamp(ts)
                .build(),
        )
        val settled = state.copy(isLoading = false)
        windowStates[state.windowId] = settled
        navigationEvents.tryEmit(
            BrowserNavigationEvent
                .newBuilder()
                .setWindowId(settled.windowId)
                .setUrl(settled.url)
                .setTitle(settled.title)
                .setEventType(NavigationEventType.NAVIGATION_EVENT_TYPE_COMPLETED)
                .setTimestamp(ts + 1)
                .build(),
        )
        return Empty.getDefaultInstance()
    }

    /**
     * Returns a refusal response, or null if [url] passes validation. The refusal is
     * decided BEFORE any state write, navigation event, or resolved-URL log entry -
     * the only thing this method emits is a single WARN line that already goes
     * through [redactUserInfo], so an embedded credential in the refused URL still
     * does not reach the log file. The blank-URL case has nothing to redact (the
     * string is empty after trim) and logs as `<blank>`.
     */
    @Suppress("ReturnCount")
    private fun validateNavigation(
        windowId: String,
        url: String,
    ): NavigateBrowserResponse? {
        if (url.isBlank()) {
            logger.info("navigate: windowId={}, url=<blank>", windowId)
            return NavigateBrowserResponse
                .newBuilder()
                .setSuccess(false)
                .setErrorMessage("URL must not be blank")
                .build()
        }
        val scheme = schemeFor(url)
        if (scheme == null || scheme !in ALLOWED_NAVIGATION_SCHEMES) {
            // Log the rejected URL with its userinfo masked - the request itself is denied,
            // but the scheme name and the safe form are still useful in the log.
            logger.warn(
                "navigate refused: windowId={}, scheme={}, url={}",
                windowId,
                scheme ?: "<none>",
                redactUserInfo(url),
            )
            return NavigateBrowserResponse
                .newBuilder()
                .setSuccess(false)
                .setErrorMessage(
                    "Refusing to navigate to scheme '${scheme ?: "<none>"}'; allowed: " +
                        ALLOWED_NAVIGATION_SCHEMES.sorted().joinToString(", "),
                ).build()
        }
        return null
    }

    private fun emitStartedOnly() {
        navigationEvents.tryEmit(
            BrowserNavigationEvent
                .newBuilder()
                .setEventType(NavigationEventType.NAVIGATION_EVENT_TYPE_STARTED)
                .setTimestamp(System.currentTimeMillis())
                .build(),
        )
    }

    private fun emitStartAndComplete(
        windowId: String,
        url: String,
        title: String,
    ) {
        val ts = System.currentTimeMillis()
        navigationEvents.tryEmit(
            BrowserNavigationEvent
                .newBuilder()
                .setWindowId(windowId)
                .setUrl(url)
                .setTitle(title)
                .setEventType(NavigationEventType.NAVIGATION_EVENT_TYPE_STARTED)
                .setTimestamp(ts)
                .build(),
        )
        navigationEvents.tryEmit(
            BrowserNavigationEvent
                .newBuilder()
                .setWindowId(windowId)
                .setUrl(url)
                .setTitle(title)
                .setEventType(NavigationEventType.NAVIGATION_EVENT_TYPE_COMPLETED)
                .setTimestamp(ts + 1)
                .build(),
        )
    }

    private fun buildSuccess(url: String): NavigateBrowserResponse =
        NavigateBrowserResponse
            .newBuilder()
            .setSuccess(true)
            .setFinalUrl(url)
            .setTitle(url)
            .build()

    companion object {
        /**
         * Schemes accepted by [navigate]. `javascript:` is deliberately rejected - the engine
         * would otherwise evaluate it in the current page's context, which is the same authority
         * a compromised tool would need to steal page state, and the issue #911 reproduction
         * printed the raw URL verbatim in the log. `data:` is rejected for the same reason
         * (the engine would load it without a scheme gate). Adding a scheme here is the
         * decision to execute it - a missing scheme falls through to a refusal.
         */
        internal val ALLOWED_NAVIGATION_SCHEMES = setOf("http", "https", "file", "ftp")

        /**
         * Parse the scheme of [url] (lowercased), or null if the input has no scheme. RFC 3986:
         * scheme = alpha *( alpha / digit / "+" / "-" / "." ), terminated by ':'. Returns null on
         * anything that does not match that shape, so [navigate] refuses it. ASCII tab / CR / LF /
         * other control characters are explicitly rejected here - `Char.isLetterOrDigit` excludes
         * them already, but pinning the rule at one place means a future edit cannot regress it
         * for a single smuggling case ("java\tscript:alert(1)" - the tab would have passed an
         * `isLetterOrDigit` test if a future refactor dropped the negative branch).
         */
        @Suppress("ReturnCount", "ComplexCondition")
        internal fun schemeFor(url: String): String? {
            val colon = url.indexOf(':')
            val raw = if (colon in 1..32) url.substring(0, colon) else return null
            if (raw.isEmpty() || !raw[0].isLetter()) return null
            var i = 1
            while (i < raw.length) {
                val c = raw[i]
                // ASCII control characters (tab=0x09, LF=0x0A, CR=0x0D, plus 0x00-0x1F and 0x7F)
                // are not letters, digits, or the allowed punctuation, so the existing
                // negative branch rejects them. Tested explicitly so a refactor cannot
                // accidentally drop the control-char case.
                if (c.code in 0x00..0x1F || c.code == 0x7F) return null
                if (!(c.isLetterOrDigit() || c == '+' || c == '-' || c == '.')) return null
                i++
            }
            return raw.lowercase()
        }

        /**
         * Replace a URL's userinfo (`user[:pass]@`) with `[REDACTED]@` for safe logging.
         *
         * Always finds the LAST `@` inside the authority section, so the redaction is
         * independent of whether [java.net.URI] accepts the input - that parser throws on
         * whitespace, raw `<`/`>`, an invalid percent escape, and other reserved
         * characters, and an "unchanged on parse failure" fallback left the credential
         * in the log, which is the very thing this helper exists to mask.
         *
         * The authority is bounded on the left by `://` (non-authority schemes like
         * `mailto:`, `javascript:`, `data:` carry no userinfo and pass through) and on
         * the right by the first `/`, `?`, `#`, or end of string. Inside that range the
         * LAST `@` ends the userinfo - the rest of the authority is the host, which is
         * safe to log. If there is no `@`, the URL had no userinfo and is returned
         * verbatim.
         */
        @Suppress("ReturnCount")
        internal fun redactUserInfo(url: String): String {
            if (url.isEmpty()) return url
            val schemeEnd = url.indexOf("://")
            if (schemeEnd < 0) return url
            val authStart = schemeEnd + 3
            var authEnd = url.length
            for (idx in authStart until url.length) {
                val c = url[idx]
                if (c == '/' || c == '?' || c == '#') {
                    authEnd = idx
                    break
                }
            }
            val authority = url.substring(authStart, authEnd)
            val lastAt = authority.lastIndexOf('@')
            if (lastAt < 0) return url
            val cut = authStart + lastAt
            return url.substring(0, authStart) + "[REDACTED]" + url.substring(cut)
        }
    }
}
