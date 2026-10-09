package io.github.gycrosskit.composewebview

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptExecutionException
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewOutcomeReceiver
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.util.WeakHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [PageChannelFeatures::class, PageChannelProvider::class])
class AndroidPageMessageChannelsTest {
    private val request = WebViewRequest(
        WebViewContent.Url("https://page.test/initial"),
        settings = WebViewSettings(javaScriptEnabled = true),
        pageMessageChannels = setOf("PageReady"),
    )

    @Before fun resetProvider() { PageChannelProvider.listeners.clear() }

    @Test fun `browser normalized owner URL preserves initial inbound and reply`() {
        val request = request.copy(
            content = WebViewContent.Url("https://page.test"),
            navigationPolicy = WebViewNavigationPolicy(allowedUrls = setOf("https://page.test/")),
        )
        val owner = WebView(RuntimeEnvironment.getApplication())
        owner.loadUrl("https://page.test/")
        val events = mutableListOf<WebViewEvent>()
        val session = AndroidPageMessageChannels(owner, { request }, { true }, events::add)
        session.onPageStarted("https://page.test/")
        val listener = PageChannelProvider.listeners.getValue(owner).getValue("PageReady")
        val proxy = PageChannelReply()
        listener.onPostMessage(owner, WebMessageCompat("normalized"), Uri.parse("https://page.test"), true, proxy)
        val replyId = (events.single() as WebViewEvent.PageMessage).replyId
        assertTrue(session.reply(replyId, "reply"))
        owner.loadUrl("https://page.test/?next")
        listener.onPostMessage(owner, WebMessageCompat("different query"), Uri.parse("https://page.test"), true, proxy)
        assertEquals(1, events.size)
        session.revoke()
    }

    @Test fun `early raw message is frame checked and replies through its own proxy`() {
        val owner = WebView(RuntimeEnvironment.getApplication())
        val events = mutableListOf<WebViewEvent>()
        val session = AndroidPageMessageChannels(owner, { request }, { true }, events::add)
        val listener = PageChannelProvider.listeners.getValue(owner).getValue("PageReady")
        val proxy = PageChannelReply()
        fun send(origin: String, main: Boolean, value: String) = listener.onPostMessage(
            owner, WebMessageCompat(value), Uri.parse(origin), main, proxy,
        )
        // 首段 inline 可以早于 commit/finished，WebView.url 尚未有值。
        send("https://page.test", true, "raw ready")
        val message = events.single() as WebViewEvent.PageMessage
        assertEquals("raw ready", message.data)
        assertTrue(session.reply(message.replyId, "raw reply"))
        assertFalse(session.reply(message.replyId, "second reply"))
        assertEquals(listOf("raw reply"), proxy.messages)
        send("https://evil.test", true, "foreign")
        send("https://page.test", false, "iframe")
        send("https://page.test", true, "中".repeat(22000))
        assertEquals(1, events.size)
        assertFalse(session.reply(message.replyId, "x".repeat(65537)))
        session.onPageStarted("https://page.test/initial")
        session.onPageStarted("https://page.test/initial")
        send("https://page.test", true, "same URL reload")
        assertEquals(1, events.size)
        assertFalse(session.reply(message.replyId, "stale reply"))
    }

    @Test fun `old SDK name routing revives same owner but cannot cross fresh physical owner`() {
        val old = WebView(RuntimeEnvironment.getApplication())
        var routedToNewGeneration = 0
        WebViewCompat.addWebMessageListener(old, "PageReady", setOf("*")) { _, _, _, _, _ -> }
        val savedJsObject = PageChannelProvider.JsObject(old, "PageReady")
        WebViewCompat.removeWebMessageListener(old, "PageReady")
        WebViewCompat.addWebMessageListener(old, "PageReady", setOf("*")) { _, _, _, _, _ -> routedToNewGeneration++ }
        savedJsObject.postMessage("old document")
        assertEquals(1, routedToNewGeneration)
        WebViewCompat.removeWebMessageListener(old, "PageReady")

        var currentOwner = old
        val events = mutableListOf<WebViewEvent>()
        val oldSession = AndroidPageMessageChannels(old, { request }, { currentOwner === old }, events::add)
        val queuedOldCallback = PageChannelProvider.listeners.getValue(old).getValue("PageReady")
        oldSession.revoke()
        val fresh = WebView(RuntimeEnvironment.getApplication())
        currentOwner = fresh
        AndroidPageMessageChannels(fresh, { request }, { currentOwner === fresh }, events::add)
        savedJsObject.postMessage("old object after replacement")
        queuedOldCallback.onPostMessage(old, WebMessageCompat("queued old inbound"), Uri.parse("https://page.test"), true, PageChannelReply())
        assertTrue(events.isEmpty())
        PageChannelProvider.JsObject(fresh, "PageReady").postMessage("fresh inline")
        assertEquals("fresh inline", (events.single() as WebViewEvent.PageMessage).data)
    }

    @Test fun `CMP reload synchronously revokes before pending instance replacement`() {
        val owner = WebView(RuntimeEnvironment.getApplication())
        owner.loadUrl("https://page.test/initial")
        val state = AppWebViewState()
        state.attach(owner)
        val events = mutableListOf<WebViewEvent>()
        state.pageMessageChannels = AndroidPageMessageChannels(owner, { request }, { state.isAttached(owner) }, events::add)
        val pending = PageChannelProvider.listeners.getValue(owner).getValue("PageReady")
        val proxy = PageChannelReply()
        pending.onPostMessage(owner, WebMessageCompat("ready"), Uri.parse("https://page.test"), true, proxy)
        val replyId = (events.single() as WebViewEvent.PageMessage).replyId
        state.reload()
        assertEquals(1, state.instanceKey)
        assertSame(owner, state.webView)
        assertFalse(state.replyPageMessage(replyId, "late"))
        pending.onPostMessage(owner, WebMessageCompat("late"), Uri.parse("https://page.test"), true, proxy)
        assertEquals(1, events.size)
    }

    @Test fun `CMP forward synchronously revokes the old document before navigating`() {
        var forwards = 0
        val owner = object : WebView(RuntimeEnvironment.getApplication()) {
            override fun canGoForward() = true
            override fun goForward() { forwards++ }
        }
        owner.loadUrl("https://page.test/initial")
        val state = AppWebViewState()
        state.attach(owner)
        val events = mutableListOf<WebViewEvent>()
        state.pageMessageChannels = AndroidPageMessageChannels(owner, { request }, { state.isAttached(owner) }, events::add)
        val listener = PageChannelProvider.listeners.getValue(owner).getValue("PageReady")
        val proxy = PageChannelReply()
        listener.onPostMessage(owner, WebMessageCompat("ready"), Uri.parse("https://page.test"), true, proxy)
        val reply = (events.single() as WebViewEvent.PageMessage).replyId
        assertTrue(state.goForward())
        assertEquals(1, forwards)
        assertFalse(state.replyPageMessage(reply, "late"))
        listener.onPostMessage(owner, WebMessageCompat("late old document"), Uri.parse("https://page.test"), true, proxy)
        assertEquals(1, events.size)
    }

    @Test fun `cancel clears pending replies and bounded queue cannot silently switch documents`() {
        val owner = WebView(RuntimeEnvironment.getApplication())
        owner.loadUrl("https://page.test/initial")
        val state = AppWebViewState()
        state.attach(owner)
        val events = mutableListOf<WebViewEvent.PageMessage>()
        val session = AndroidPageMessageChannels(owner, { request }, { state.isAttached(owner) }) {
            if (it is WebViewEvent.PageMessage) events += it
        }
        state.pageMessageChannels = session
        val pending = PageChannelProvider.listeners.getValue(owner).getValue("PageReady")
        val proxy = PageChannelReply()
        repeat(129) { pending.onPostMessage(owner, WebMessageCompat("message $it"), Uri.parse("https://page.test"), true, proxy) }
        assertEquals(128, events.size)
        assertTrue(state.replyPageMessage(events.first().replyId, "reply"))
        pending.onPostMessage(owner, WebMessageCompat("after consume"), Uri.parse("https://page.test"), true, proxy)
        assertEquals(129, events.size)
        state.stopLoading()
        assertFalse(state.replyPageMessage(events.last().replyId, "cancelled"))
        pending.onPostMessage(owner, WebMessageCompat("after cancel"), Uri.parse("https://page.test"), true, proxy)
        assertEquals(129, events.size)

        val nextOwner = WebView(RuntimeEnvironment.getApplication())
        val replacement = AndroidPageMessageChannels(nextOwner, { request }, { true }) {}
        val nextListener = PageChannelProvider.listeners.getValue(nextOwner).getValue("PageReady")
        nextListener.onPostMessage(nextOwner, WebMessageCompat("one"), Uri.parse("https://page.test"), true, proxy)
        nextListener.onPostMessage(nextOwner, WebMessageCompat("new document proxy"), Uri.parse("https://page.test"), true, PageChannelReply())
        assertTrue(PageChannelProvider.listeners.getValue(nextOwner).isEmpty())
        replacement.revoke()
    }

    @Test
    @Config(shadows = [PageChannelUnsupportedFeatures::class, PageChannelProvider::class])
    fun `unsupported provider rejects channel without delaying page load or installing fallback`() {
        val owner = WebView(RuntimeEnvironment.getApplication())
        val events = mutableListOf<WebViewEvent>()
        val session = AndroidPageMessageChannels(owner, { request }, { true }, events::add)
        owner.loadUrl("https://page.test/initial")
        assertEquals("https://page.test/initial", owner.url)
        assertEquals(listOf(WebViewEvent.CapabilityUnsupported(WebViewCapability.PAGE_MESSAGE_CHANNEL)), events)
        assertTrue(PageChannelProvider.listeners.isEmpty())
        assertFalse(session.reply("missing", "reply"))
    }
}

class PageChannelReply : JavaScriptReplyProxy() {
    val messages = mutableListOf<String>()
    override fun postMessage(message: String) { messages += message }
    override fun postMessage(arrayBuffer: ByteArray) { error("String protocol only") }
    override fun executeJavaScript(script: String, receiver: WebViewOutcomeReceiver<String, JavaScriptExecutionException>?) {}
}

@Implements(value = WebViewFeature::class, isInAndroidSdk = false)
class PageChannelFeatures {
    companion object {
        @JvmStatic @Implementation fun isFeatureSupported(feature: String): Boolean = feature == WebViewFeature.WEB_MESSAGE_LISTENER
    }
}

@Implements(value = WebViewFeature::class, isInAndroidSdk = false)
class PageChannelUnsupportedFeatures {
    companion object {
        @JvmStatic @Implementation fun isFeatureSupported(feature: String): Boolean = false
    }
}

/** 模拟 Chromium JsBinding 保存名称、postMessage 时动态找当前映射，不能假设旧对象绑定旧 listener。 */
@Implements(value = WebViewCompat::class, isInAndroidSdk = false)
class PageChannelProvider {
    class JsObject(private val owner: WebView, private val channel: String) {
        private val proxy = PageChannelReply()
        fun postMessage(data: String) {
            listeners[owner]?.get(channel)?.onPostMessage(owner, WebMessageCompat(data), Uri.parse("https://page.test"), true, proxy)
        }
    }
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
