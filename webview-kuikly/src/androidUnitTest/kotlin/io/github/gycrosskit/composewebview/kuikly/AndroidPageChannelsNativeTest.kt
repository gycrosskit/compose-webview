package io.github.gycrosskit.composewebview.kuikly

import android.os.Looper
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.gycrosskit.composewebview.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [RecordingWebView::class, NativePageFeatures::class])
class AndroidPageChannelsNativeTest {
    private val request = WebViewRequest(
        WebViewContent.Url("https://page.test/initial"),
        settings = WebViewSettings(javaScriptEnabled = true),
        pageMessageChannels = setOf("PageReady"),
        navigationPolicy = WebViewNavigationPolicy(allowedUrls = setOf("https://page.test/initial")),
    )

    private fun javascriptReceiver(owner: WebView) =
        requireNotNull(shadowOf(owner).getJavascriptInterface("PageReady"))

    private fun send(receiver: Any, data: String) {
        receiver.javaClass.getMethod("postMessage", String::class.java).invoke(receiver, data)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    @Config(shadows = [RecordingWebView::class, NativeDocumentStartFeatures::class, NativeDocumentStartFailureProvider::class])
    fun `actual early add failure reports only native geolocation unsupported and preserves page initialization`() {
        val native = GYWebViewNative(RuntimeEnvironment.getApplication())
        val events = mutableListOf<Map<String, Any?>>()
        native.setProp("onEvent", { event: Any? -> @Suppress("UNCHECKED_CAST") events.add(event as Map<String, Any?>) })
        val adapted = request.copy(security = WebViewSecurity(
            WebViewTrustPolicy(listOf("https://page.test")), geolocationEnabled = true)).withNativeGeolocation()
        native.setProp("request", WebViewWire.encodeRequest(adapted))
        val owner = native.getChildAt(0) as WebView
        assertEquals(listOf("NATIVE_GEOLOCATION"), events.filter { it["type"] == "capabilityUnsupported" }.map { it["capability"] })
        assertNull(shadowOf(owner).getJavascriptInterface("ComposeWebViewPage_NativeGeolocation"))
        send(javascriptReceiver(owner), "initialization")
        val id = events.single { it["type"] == "pageMessage" }["replyId"]
        native.call("replyPageMessage", """{"replyId":"$id","data":"ready"}""") {}
        assertTrue(shadowOf(owner).lastEvaluatedJavascript.contains("channel.onmessage({data:\"ready\"})"))
        native.onDestroy()
    }

    @Test fun `business bridge follows trusted navigation and rejects queued old documents`() {
        val native = GYWebViewNative(RuntimeEnvironment.getApplication())
        val events = mutableListOf<Map<String, Any?>>()
        native.setProp("onEvent", { event: Any? -> @Suppress("UNCHECKED_CAST") events.add(event as Map<String, Any?>) })
        val request = WebViewRequest(WebViewContent.Url("https://other.test/start"),
            settings = WebViewSettings(javaScriptEnabled = true),
            security = WebViewSecurity(WebViewTrustPolicy(listOf("https://trusted.test")), appBridgeEnabled = true),
            navigationPolicy = WebViewNavigationPolicy(navigationOrigins = WebViewNavigationOrigins(
                exactOrigins = setOf("https://other.test", "https://trusted.test"))))
        native.setProp("request", WebViewWire.encodeRequest(request))
        val owner = native.getChildAt(0) as WebView
        val receiver = shadowOf(owner).getJavascriptInterface("ComposeWebViewBridgeTransport")
        val postMessage = receiver.javaClass.getMethod("postMessage", String::class.java)
        val client = Shadow.extract<RecordingWebView>(owner).webViewClient
        fun token(): String = native.javaClass.getDeclaredField("documentToken").let { it.isAccessible = true; it.get(native) as String }
        fun send(documentToken: String? = null) {
            val prefix = if (documentToken == null) "" else "$documentToken\u001E"
            postMessage.invoke(receiver, "${prefix}echo\u001F{}")
            shadowOf(Looper.getMainLooper()).idle()
        }
        val oldToken = token()
        send(oldToken)
        assertEquals(0, events.count { it["type"] == "bridgeMessage" })
        val trustedUrl = "https://trusted.test/page"
        owner.loadUrl(trustedUrl)
        client.onPageStarted(owner, trustedUrl, null)
        send(oldToken)
        send()
        send(token())
        assertEquals(1, events.count { it["type"] == "bridgeMessage" })
        native.setProp("visible", false)
        send(token())
        assertEquals(1, events.count { it["type"] == "bridgeMessage" })
        native.onDestroy()
    }

    @Test fun `explicit owner preserves early page channel reply in background and revokes on departure`() {
        val native = GYWebViewNative(RuntimeEnvironment.getApplication())
        val events = mutableListOf<Map<String, Any?>>()
        native.setProp("onEvent", { event: Any? -> @Suppress("UNCHECKED_CAST") events.add(event as Map<String, Any?>) })
        native.setProp("pageOwnerActive", true)
        native.setProp("request", WebViewWire.encodeRequest(request))
        val owner = native.getChildAt(0) as WebView
        val receiver = javascriptReceiver(owner)
        send(receiver, "initial")
        val replyId = events.single { it["type"] == "pageMessage" }.getValue("replyId") as String
        native.setProp("visible", false)
        var result: Any? = null
        native.call("replyPageMessage", "{\"replyId\":\"$replyId\",\"data\":\"background\"}") { result = (it as Map<*, *>)["result"] }
        assertEquals(true, result)
        assertTrue(shadowOf(owner).lastEvaluatedJavascript.contains("channel.onmessage({data:\"background\"})"))
        send(receiver, "background")
        val pendingId = events.last { it["type"] == "pageMessage" }.getValue("replyId") as String
        owner.loadUrl("https://other.test/other")
        send(receiver, "cross-origin")
        owner.loadUrl("https://page.test/initial")
        assertEquals(2, events.count { it["type"] == "pageMessage" })
        native.setProp("pageOwnerActive", false)
        native.call("replyPageMessage", "{\"replyId\":\"$pendingId\",\"data\":\"stale\"}") { result = (it as Map<*, *>)["result"] }
        assertEquals(false, result)
        assertTrue(shadowOf(owner).lastEvaluatedJavascript.contains("channel.onmessage({data:\"background\"})"))
        native.setProp("visible", true)
        send(receiver, "stale")
        assertEquals(2, events.count { it["type"] == "pageMessage" })
        native.onDestroy()
    }

    @Test fun `Kuikly early message single reply hide reload and replacement stay owner scoped`() {
        val native = GYWebViewNative(RuntimeEnvironment.getApplication())
        val events = mutableListOf<Map<String, Any?>>()
        native.setProp("onEvent", { event: Any? -> @Suppress("UNCHECKED_CAST") events.add(event as Map<String, Any?>) })
        native.setProp("request", WebViewWire.encodeRequest(request))
        val first = native.getChildAt(0) as WebView
        val firstReceiver = javascriptReceiver(first)
        send(firstReceiver, "inline")
        val early = events.single { it["type"] == "pageMessage" }
        val replyId = early.getValue("replyId") as String
        val params = "{\"replyId\":\"$replyId\",\"data\":\"reply\"}"
        var callbackResult: Any? = null
        native.call("replyPageMessage", params) { callbackResult = (it as Map<*, *>)["result"] }
        assertEquals(true, callbackResult)
        assertTrue(shadowOf(first).lastEvaluatedJavascript.contains("channel.onmessage({data:\"reply\"})"))
        native.call("replyPageMessage", params) { callbackResult = (it as Map<*, *>)["result"] }
        assertEquals(false, callbackResult)
        native.setProp("visible", false)
        native.setProp("visible", true)
        send(firstReceiver, "after show")
        assertEquals(1, events.count { it["type"] == "pageMessage" })
        native.call("reload", null, null)
        val fresh = native.getChildAt(0) as WebView
        assertNotSame(first, fresh)
        assertEquals(0, Shadow.extract<RecordingWebView>(first).reloadInvocations)
        val freshReceiver = javascriptReceiver(fresh)
        send(freshReceiver, "fresh")
        send(firstReceiver, "queued old")
        assertEquals(2, events.count { it["type"] == "pageMessage" })
        val freshId = events.last { it["type"] == "pageMessage" }.getValue("replyId") as String
        native.setProp("request", WebViewWire.encodeRequest(request.copy(content = WebViewContent.Url("https://page.test/replacement"),
            navigationPolicy = WebViewNavigationPolicy(allowedUrls = setOf("https://page.test/replacement")))))
        assertNotSame(fresh, native.getChildAt(0))
        native.call("replyPageMessage", "{\"replyId\":\"$freshId\",\"data\":\"late\"}") { callbackResult = (it as Map<*, *>)["result"] }
        assertEquals(false, callbackResult)
        val last = native.getChildAt(0) as WebView
        val lastReceiver = javascriptReceiver(last)
        val client = Shadow.extract<RecordingWebView>(last).webViewClient
        send(lastReceiver, "before stop")
        val cancelledId = events.last { it["type"] == "pageMessage" }.getValue("replyId") as String
        native.call("stopLoading", null, null)
        native.call("replyPageMessage", "{\"replyId\":\"$cancelledId\",\"data\":\"cancelled\"}") { callbackResult = (it as Map<*, *>)["result"] }
        assertEquals(false, callbackResult)
        client.onPageStarted(last, last.url, null)
        last.loadUrl("https://page.test/replacement")
        client.onPageStarted(last, last.url, null)
        send(lastReceiver, "self reload")
        assertEquals(3, events.count { it["type"] == "pageMessage" })
        native.onDestroy()
        send(lastReceiver, "destroyed")
        assertEquals(3, events.count { it["type"] == "pageMessage" })
    }
}

@Implements(value = WebViewFeature::class, isInAndroidSdk = false)
class NativePageFeatures {
    companion object {
        @JvmStatic @Implementation fun isFeatureSupported(feature: String): Boolean = false
    }
}

@Implements(value = WebViewFeature::class, isInAndroidSdk = false)
class NativeDocumentStartFeatures {
    companion object {
        @JvmStatic @Implementation fun isFeatureSupported(feature: String): Boolean =
            feature == WebViewFeature.DOCUMENT_START_SCRIPT
    }
}

@Implements(value = WebViewCompat::class, isInAndroidSdk = false)
class NativeDocumentStartFailureProvider {
    companion object {
        @JvmStatic @Implementation fun addDocumentStartJavaScript(owner: WebView, source: String, origins: Set<String>): ScriptHandler =
            error("provider failed to register document-start")
    }
}
