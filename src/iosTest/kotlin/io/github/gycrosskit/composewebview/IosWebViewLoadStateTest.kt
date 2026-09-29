package io.github.gycrosskit.composewebview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IosWebViewLoadStateTest {
    @Test
    fun `initial retry preserves declared content headers and cache policy`() {
        val content = WebViewContent.Url(
            "https://example.test/agreement",
            additionalHeaders = mapOf("X-Document-Version" to "2"),
        )
        val state = IosWebViewLoadState()
        assertTrue(state.begin(content, WebViewCachePolicy.NO_CACHE))

        assertTrue(state.takeInitialNetworkRetry(failure()))

        assertFalse(state.hasCommittedPage)
        assertSame(content, state.content)
        assertEquals(content.additionalHeaders, (state.content as WebViewContent.Url).additionalHeaders)
        assertEquals(WebViewCachePolicy.NO_CACHE, state.cachePolicy)
    }

    @Test
    fun `only the four initial connection errors can use automatic retry`() {
        for (code in listOf(-1009, -1005, -1001, -1004)) {
            assertTrue(initialState().takeInitialNetworkRetry(failure(code)), "code=$code")
        }
        for (code in listOf(-999, -1000, -1002, -1003, -1011, -1022, -1200, -1202, null)) {
            assertFalse(initialState().takeInitialNetworkRetry(failure(code)), "code=$code")
        }
    }

    @Test
    fun `other error domains and non-main-frame failures do not retry`() {
        for (kind in WebViewErrorKind.entries.filter { it != WebViewErrorKind.NETWORK }) {
            assertFalse(initialState().takeInitialNetworkRetry(failure(kind = kind)), "kind=$kind")
        }
        assertFalse(initialState().takeInitialNetworkRetry(failure(isMainFrame = false)))
    }

    @Test
    fun `absent error or in-flight request does not consume retry budget`() {
        val state = initialState()

        assertFalse(state.takeInitialNetworkRetry(WebViewSnapshot()))
        assertFalse(state.takeInitialNetworkRetry(failure().copy(isLoading = true)))
        assertTrue(state.takeInitialNetworkRetry(failure()))
    }

    @Test
    fun `html empty content and absent declaration cannot automatically retry`() {
        assertFalse(IosWebViewLoadState().takeInitialNetworkRetry(failure()))
        for (content in listOf(WebViewContent.Html("<p>Agreement</p>"), WebViewContent.Url(" "))) {
            val state = IosWebViewLoadState()
            state.begin(content, WebViewCachePolicy.DEFAULT)
            assertFalse(state.takeInitialNetworkRetry(failure()))
        }
    }

    @Test
    fun `automatic retry only accepts absolute http and https urls`() {
        val unsupportedUrls = listOf(
            "file:///agreement.html",
            "about:blank",
            "data:text/html,hello",
            "custom://page",
            "relative/path",
            "https://",
        )
        for (url in unsupportedUrls) {
            val state = IosWebViewLoadState()
            state.begin(WebViewContent.Url(url), WebViewCachePolicy.DEFAULT)
            assertFalse(state.takeInitialNetworkRetry(failure()), "url=$url")
        }
        for (url in listOf("http://example.test", "HTTPS://example.test")) {
            val state = IosWebViewLoadState()
            state.begin(WebViewContent.Url(url), WebViewCachePolicy.DEFAULT)
            assertTrue(state.takeInitialNetworkRetry(failure()), "url=$url")
        }
    }

    @Test
    fun `equal declaration and repeated errors do not renew automatic budget`() {
        val state = initialState()
        assertTrue(state.takeInitialNetworkRetry(failure()))

        assertFalse(state.begin(WebViewContent.Url(INITIAL_URL), WebViewCachePolicy.DEFAULT))
        assertFalse(state.takeInitialNetworkRetry(failure(-1001)))
        assertFalse(state.takeInitialNetworkRetry(failure(-1009)))
    }

    @Test
    fun `new declaration before first commit receives its own recovery budget`() {
        val state = initialState()
        assertTrue(state.takeInitialNetworkRetry(failure()))

        assertTrue(
            state.begin(WebViewContent.Url("https://example.test/terms"), WebViewCachePolicy.CACHE_ONLY),
        )

        assertTrue(state.takeInitialNetworkRetry(failure()))
        assertEquals(WebViewCachePolicy.CACHE_ONLY, state.cachePolicy)
    }

    @Test
    fun `committed document keeps native reload ownership across failures and declarations`() {
        val state = initialState()
        state.markCommitted()

        assertTrue(state.hasCommittedPage)
        assertFalse(state.takeInitialNetworkRetry(failure()))
        state.begin(WebViewContent.Url("https://example.test/next"), WebViewCachePolicy.DEFAULT)
        assertTrue(state.hasCommittedPage)
        assertFalse(state.takeInitialNetworkRetry(failure()))
    }

    @Test
    fun `unattached web view state cannot automatically retry`() {
        assertFalse(AppWebViewState().retryInitialNetworkFailure())
    }

    private fun initialState() = IosWebViewLoadState().apply {
        begin(WebViewContent.Url(INITIAL_URL), WebViewCachePolicy.DEFAULT)
    }

    private fun failure(
        code: Int? = -1009,
        kind: WebViewErrorKind = WebViewErrorKind.NETWORK,
        isMainFrame: Boolean = true,
    ) = WebViewSnapshot(
        isLoading = false,
        error = WebViewLoadError(kind = kind, errorCode = code, isMainFrame = isMainFrame),
    )
}

private const val INITIAL_URL = "https://example.test/agreement"
