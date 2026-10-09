package io.github.gycrosskit.composewebview

import android.net.Uri
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidPostNavigationTest {
    private val allowed = "https://page.test/form?mode=one#form"
    private fun resource(url: String, main: Boolean = true, method: String = "POST") = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(url)
        override fun isForMainFrame() = main
        override fun isRedirect() = false
        override fun hasGesture() = true
        override fun getMethod() = method
        override fun getRequestHeaders() = emptyMap<String, String>()
    }

    @Test fun `CMP blocks POST before network with main thread owner scoped events`() {
        val owner = WebView(RuntimeEnvironment.getApplication())
        var attached = true
        val events = mutableListOf<WebViewEvent>()
        var changed = 0
        var callbacks = 0
        val request = WebViewRequest(WebViewContent.Url(allowed), navigationPolicy = WebViewNavigationPolicy(allowedUrls = setOf(allowed)))
        val client = commonWebViewClient(
            listener = object : AppWebViewClient.Listener { override fun onPageLoadFailed(error: WebViewLoadError) {} },
            request = { request },
            callbacks = { WebViewCallbacks(onNavigationRequest = { callbacks++; WebViewNavigationDecision.ALLOW }, onEvent = {
                assertEquals(Looper.getMainLooper(), Looper.myLooper())
                events += it
            }) },
            onDocumentChanged = { changed++ }, documentToken = { "token" }, pageMessageChannels = { null },
            isOwner = { it === owner && attached }, isOwnerCallback = { attached },
        )
        var response: android.webkit.WebResourceResponse? = null
        Thread { response = client.shouldInterceptRequest(owner, resource("https://page.test/other")) }.apply { start(); join() }
        assertNotNull(response)
        assertTrue(events.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(WebViewEvent.Navigation(WebViewNavigationRequest("https://page.test/other", true, true), true)), events)
        assertEquals(0, callbacks)
        assertEquals(0, changed)
        // 即使首地址在白名单，307/308 可保留 POST，原生回调不能检查后续地址。
        assertNotNull(client.shouldInterceptRequest(owner, resource(allowed)))
        assertNull(client.shouldInterceptRequest(owner, resource("https://page.test/other", main = false)))
        assertNull(client.shouldInterceptRequest(owner, resource(allowed, method = "GET")))
        attached = false
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, events.size)
    }

    @Test fun `POST restriction covers exact origins deny rules and schemes without affecting resources`() {
        val request = WebViewRequest(WebViewContent.Url(allowed))
        val rejected = mutableListOf<WebViewNavigationRequest>()
        val restrictedPolicies = listOf(
            WebViewNavigationPolicy(allowedUrls = setOf(allowed)),
            WebViewNavigationPolicy(allowedOrigins = setOf("https://page.test")),
            WebViewNavigationPolicy(blockedRules = listOf(WebViewUrlRule.ExactHost("other.test"))),
            WebViewNavigationPolicy(allowedSchemes = setOf("https")),
        )
        for (policy in restrictedPolicies) {
            val current = request.copy(navigationPolicy = policy)
            assertNotNull(current.interceptAndroidRequest(resource(allowed), rejected::add))
            assertNull(current.interceptAndroidRequest(resource(allowed, main = false), rejected::add))
            assertNull(current.interceptAndroidRequest(resource(allowed, method = "GET"), rejected::add))
        }
        assertEquals(4, rejected.size)
        assertNull(request.interceptAndroidRequest(resource(allowed), rejected::add))
        val bridge = request.copy(security = WebViewSecurity(appBridgeEnabled = true, trustedOrigins = WebViewTrustPolicy(listOf(allowed))))
        assertNull(bridge.interceptAndroidRequest(resource(allowed), rejected::add))
        assertNotNull(bridge.interceptAndroidRequest(resource("https://other.test"), rejected::add))
        val resources = request.copy(blockedResourceRules = listOf(WebViewUrlRule.ExactHost("other.test")))
        assertNotNull(resources.interceptAndroidRequest(resource("https://other.test", main = false), rejected::add))
        assertEquals(5, rejected.size)
    }

    @Test fun `GET redirect still uses exact navigation policy and synchronous callback`() {
        val owner = WebView(RuntimeEnvironment.getApplication())
        val events = mutableListOf<WebViewEvent>()
        val request = WebViewRequest(WebViewContent.Url(allowed), navigationPolicy = WebViewNavigationPolicy(allowedUrls = setOf(allowed)))
        val client = commonWebViewClient(
            listener = object : AppWebViewClient.Listener { override fun onPageLoadFailed(error: WebViewLoadError) {} },
            request = { request }, callbacks = { WebViewCallbacks(onEvent = events::add) },
            onDocumentChanged = {}, documentToken = { "token" }, pageMessageChannels = { null },
            isOwner = { it === owner }, isOwnerCallback = { true },
        )
        assertFalse(client.shouldOverrideUrlLoading(owner, resource(allowed, method = "GET")))
        assertTrue(client.shouldOverrideUrlLoading(owner, resource(allowed.replace("mode=one", "mode=two"), method = "GET")))
        assertEquals(listOf(false, true), events.filterIsInstance<WebViewEvent.Navigation>().map { it.blocked })
    }
}
