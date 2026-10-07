package io.github.gycrosskit.composewebview.kuikly

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptExecutionException
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewOutcomeReceiver
import io.github.gycrosskit.composewebview.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import java.util.WeakHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [RecordingWebView::class, NativePageFeatures::class, NativePageProvider::class])
class AndroidPageChannelsNativeTest {
    private val request = WebViewRequest(
        WebViewContent.Url("https://page.test/initial"),
        settings = WebViewSettings(javaScriptEnabled = true),
        pageMessageChannels = setOf("PageReady"),
    )

    @Test fun `Kuikly early message single reply hide reload and replacement stay owner scoped`() {
        NativePageProvider.listeners.clear()
        val native = GYWebViewNative(RuntimeEnvironment.getApplication())
        val events = mutableListOf<Map<String, Any?>>()
        native.setProp("onEvent", { event: Any? -> @Suppress("UNCHECKED_CAST") events.add(event as Map<String, Any?>) })
        native.setProp("request", WebViewWire.encodeRequest(request))
        val first = native.getChildAt(0) as WebView
        val firstProxy = NativePageReply()
        val firstListener = NativePageProvider.listeners.getValue(first).getValue("PageReady")
        firstListener.onPostMessage(first, WebMessageCompat("inline"), Uri.parse("https://page.test"), true, firstProxy)
        val early = events.single { it["type"] == "pageMessage" }
        val replyId = early.getValue("replyId") as String
        val params = "{\"replyId\":\"$replyId\",\"data\":\"reply\"}"
        var callbackResult: Any? = null
        native.call("replyPageMessage", params) { callbackResult = (it as Map<*, *>)["result"] }
        assertEquals(true, callbackResult)
        assertEquals(listOf("reply"), firstProxy.messages)
        native.call("replyPageMessage", params) { callbackResult = (it as Map<*, *>)["result"] }
        assertEquals(false, callbackResult)
        native.setProp("visible", false)
        native.setProp("visible", true)
        firstListener.onPostMessage(first, WebMessageCompat("after show"), Uri.parse("https://page.test"), true, firstProxy)
        assertEquals(1, events.count { it["type"] == "pageMessage" })
        native.call("reload", null, null)
        val fresh = native.getChildAt(0) as WebView
        assertNotSame(first, fresh)
        assertEquals(0, Shadow.extract<RecordingWebView>(first).reloadInvocations)
        val freshListener = NativePageProvider.listeners.getValue(fresh).getValue("PageReady")
        val freshProxy = NativePageReply()
        freshListener.onPostMessage(fresh, WebMessageCompat("fresh"), Uri.parse("https://page.test"), true, freshProxy)
        firstListener.onPostMessage(first, WebMessageCompat("queued old"), Uri.parse("https://page.test"), true, firstProxy)
        assertEquals(2, events.count { it["type"] == "pageMessage" })
        val freshId = events.last { it["type"] == "pageMessage" }.getValue("replyId") as String
        native.setProp("request", WebViewWire.encodeRequest(request.copy(content = WebViewContent.Url("https://page.test/replacement"))))
        assertNotSame(fresh, native.getChildAt(0))
        native.call("replyPageMessage", "{\"replyId\":\"$freshId\",\"data\":\"late\"}") { callbackResult = (it as Map<*, *>)["result"] }
        assertEquals(false, callbackResult)
        assertTrue(freshProxy.messages.isEmpty())
        val last = native.getChildAt(0) as WebView
        val lastListener = NativePageProvider.listeners.getValue(last).getValue("PageReady")
        val client = Shadow.extract<RecordingWebView>(last).webViewClient
        val lastProxy = NativePageReply()
        lastListener.onPostMessage(last, WebMessageCompat("before stop"), Uri.parse("https://page.test"), true, lastProxy)
        val cancelledId = events.last { it["type"] == "pageMessage" }.getValue("replyId") as String
        native.call("stopLoading", null, null)
        native.call("replyPageMessage", "{\"replyId\":\"$cancelledId\",\"data\":\"cancelled\"}") { callbackResult = (it as Map<*, *>)["result"] }
        assertEquals(false, callbackResult)
        assertTrue(lastProxy.messages.isEmpty())
        client.onPageStarted(last, last.url, null)
        last.loadUrl("https://page.test/replacement")
        client.onPageStarted(last, last.url, null)
        lastListener.onPostMessage(last, WebMessageCompat("self reload"), Uri.parse("https://page.test"), true, NativePageReply())
        assertEquals(3, events.count { it["type"] == "pageMessage" })
        native.onDestroy()
        lastListener.onPostMessage(last, WebMessageCompat("destroyed"), Uri.parse("https://page.test"), true, NativePageReply())
        assertEquals(3, events.count { it["type"] == "pageMessage" })
    }
}

class NativePageReply : JavaScriptReplyProxy() {
    val messages = mutableListOf<String>()
    override fun postMessage(message: String) { messages += message }
    override fun postMessage(arrayBuffer: ByteArray) { error("String protocol only") }
    override fun executeJavaScript(script: String, receiver: WebViewOutcomeReceiver<String, JavaScriptExecutionException>?) {}
}

@Implements(value = WebViewFeature::class, isInAndroidSdk = false)
class NativePageFeatures {
    companion object {
        @JvmStatic @Implementation fun isFeatureSupported(feature: String): Boolean = feature == WebViewFeature.WEB_MESSAGE_LISTENER
    }
}

@Implements(value = WebViewCompat::class, isInAndroidSdk = false)
class NativePageProvider {
    companion object {
        val listeners = WeakHashMap<WebView, MutableMap<String, WebViewCompat.WebMessageListener>>()
        @JvmStatic @Implementation
        fun addWebMessageListener(owner: WebView, name: String, rules: Set<String>, listener: WebViewCompat.WebMessageListener) {
            listeners.getOrPut(owner) { mutableMapOf() }[name] = listener
        }
        @JvmStatic @Implementation
        fun removeWebMessageListener(owner: WebView, name: String) { listeners[owner]?.remove(name) }
    }
}
