package io.github.gycrosskit.composewebview.kuikly

import android.net.Uri
import android.app.Activity
import android.os.Handler
import android.os.Message
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import io.github.gycrosskit.composewebview.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [RecordingWebView::class, LegacyWebViewFeatures::class])
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidPostNavigationNativeTest {
    private val allowed = "https://page.test/form"
    private fun resource(url: String, main: Boolean = true, method: String = "POST") = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(url)
        override fun isForMainFrame() = main
        override fun isRedirect() = false
        override fun hasGesture() = true
        override fun getMethod() = method
        override fun getRequestHeaders() = emptyMap<String, String>()
    }

    @Test fun `Kuikly uses latest policy for POST and drops replaced owner notifications`() {
        val native = GYWebViewNative(RuntimeEnvironment.getApplication())
        val events = mutableListOf<Map<String, Any?>>()
        native.setProp("onEvent", { event: Any? ->
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
            @Suppress("UNCHECKED_CAST") events.add(event as Map<String, Any?>)
        })
        val request = WebViewRequest(WebViewContent.Url(allowed))
        native.setProp("request", WebViewWire.encodeRequest(request))
        val owner = native.getChildAt(0) as WebView
        val client = Shadow.extract<RecordingWebView>(owner).webViewClient
        assertNull(client.shouldInterceptRequest(owner, resource(allowed)))
        val restricted = request.copy(navigationPolicy = WebViewNavigationPolicy(allowedUrls = setOf(allowed)))
        native.setProp("request", WebViewWire.encodeRequest(restricted))
        assertSame(owner, native.getChildAt(0))
        var response: android.webkit.WebResourceResponse? = null
        Thread { response = client.shouldInterceptRequest(owner, resource("https://page.test/other")) }.apply { start(); join() }
        assertNotNull(response)
        assertTrue(events.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("navigation", events.single()["type"])
        assertEquals(true, events.single()["blocked"])
        assertNotNull(client.shouldInterceptRequest(owner, resource(allowed)))
        assertNull(client.shouldInterceptRequest(owner, resource("https://page.test/other", main = false)))
        assertNull(client.shouldInterceptRequest(owner, resource(allowed, method = "GET")))
        native.setProp("request", WebViewWire.encodeRequest(restricted.copy(
            content = WebViewContent.Url("$allowed?fresh"),
            navigationPolicy = WebViewNavigationPolicy(allowedUrls = setOf("$allowed?fresh")),
        )))
        assertNotSame(owner, native.getChildAt(0))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, events.size)
        native.onDestroy()
    }

    @Test fun `popup POST never sends its body or replays a parent GET and GET fallback still routes`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val parent = WebView(activity)
        activity.setContentView(parent)
        var routes = 0
        val rejected = mutableListOf<WebViewNavigationRequest>()
        val router = AndroidPopupRouter({ WebViewRequest(WebViewContent.Url(allowed)) }, {
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
            rejected += it
        }) { routes++; WebViewNavigationDecision.ALLOW }
        fun popup(): Pair<WebView, android.webkit.WebViewClient> {
            val transport = parent.WebViewTransport()
            assertTrue(router.createWindow(parent, true, Message.obtain(Handler(Looper.getMainLooper())).apply { obj = transport }))
            return requireNotNull(transport.webView).let { it to Shadow.extract<RecordingWebView>(it).webViewClient }
        }
        val (post, postClient) = popup()
        postClient.onPageStarted(post, allowed, null)
        assertNull(shadowOf(parent).lastLoadedUrl)
        var response: android.webkit.WebResourceResponse? = null
        Thread { response = postClient.shouldInterceptRequest(post, resource(allowed)) }.apply { start(); join() }
        assertNotNull(response)
        assertTrue(rejected.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(WebViewNavigationRequest(allowed, true, true, WebViewNavigationTarget.NEW_WINDOW)), rejected)
        assertEquals(0, routes)
        assertNull(shadowOf(parent).lastLoadedUrl)
        val (get, getClient) = popup()
        assertNotNull(getClient.shouldInterceptRequest(get, resource(allowed, method = "GET")))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, routes)
        assertEquals(allowed, shadowOf(parent).lastLoadedUrl)
        getClient.shouldInterceptRequest(get, resource("https://page.test/late", method = "GET"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, routes)
        router.release()
        activity.finish()
    }
}
