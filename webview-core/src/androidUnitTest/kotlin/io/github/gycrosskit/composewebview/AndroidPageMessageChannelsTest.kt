package io.github.gycrosskit.composewebview

import android.os.Looper
import android.webkit.WebView
import androidx.webkit.WebViewFeature
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [PageChannelFeatures::class])
class AndroidPageMessageChannelsTest {
    private val url = "https://page.test/initial"
    private val request = WebViewRequest(
        WebViewContent.Url(url),
        settings = WebViewSettings(javaScriptEnabled = true),
        pageMessageChannels = setOf("PageReady"),
    )

    private fun pinnedRequest() = request.copy(
        navigationPolicy = WebViewNavigationPolicy(allowedUrls = setOf(url)),
    )

    private fun view() = WebView(RuntimeEnvironment.getApplication())

    private fun WebView.receiver(name: String) =
        shadowOf(this).getJavascriptInterface(name) as JavascriptMessageReceiver

    private fun flushMessages() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun `missing document start rejects geolocation and preserves a fixed page channel`() {
        val adapted = pinnedRequest().copy(security = WebViewSecurity(
            trustedOrigins = WebViewTrustPolicy(trustedHostSuffixes = setOf("page.test")),
            geolocationEnabled = true,
        )).withNativeGeolocation()
        val owner = view()
        val events = mutableListOf<WebViewEvent>()
        val session = AndroidPageMessageChannels(owner, { adapted }, { true }, documentStartInstalled = false,
            documentStartToken = null, onEvent = events::add)
        assertEquals(listOf(WebViewEvent.CapabilityUnsupported(WebViewCapability.NATIVE_GEOLOCATION)), events)
        assertNull(shadowOf(owner).getJavascriptInterface("ComposeWebViewPage_NativeGeolocation"))
        owner.receiver("PageReady").postMessage("ready")
        flushMessages()
        assertTrue(session.reply((events.last() as WebViewEvent.PageMessage).replyId, "still usable"))
        assertTrue(shadowOf(owner).lastEvaluatedJavascript.contains("still usable"))
        assertNull(adapted.earlyScriptSource())
    }

    @Test fun `browser normalized owner URL preserves initial inbound and reply`() {
        val initial = request.copy(
            content = WebViewContent.Url("https://page.test"),
            navigationPolicy = WebViewNavigationPolicy(allowedUrls = setOf("https://page.test/")),
        )
        val owner = view()
        owner.loadUrl("https://page.test/")
        val events = mutableListOf<WebViewEvent>()
        val session = AndroidPageMessageChannels(owner, { initial }, { true }, events::add)
        session.onPageStarted("https://page.test/")
        val receiver = owner.receiver("PageReady")
        receiver.postMessage("normalized")
        flushMessages()
        assertTrue(session.reply((events.single() as WebViewEvent.PageMessage).replyId, "reply"))
        owner.loadUrl("https://page.test/?next")
        receiver.postMessage("different query")
        flushMessages()
        assertEquals(1, events.size)
        session.revoke()
    }

    @Test fun `protected channel accepts document token and replies once for initial document`() {
        val owner = view()
        val events = mutableListOf<WebViewEvent>()
        val session = AndroidPageMessageChannels(owner, { request }, { true }, documentStartInstalled = true,
            documentStartToken = "document", onEvent = events::add)
        assertNull(shadowOf(owner).getJavascriptInterface("PageReady"))
        val receiver = owner.receiver("ComposeWebViewPage_PageReady")
        receiver.postMessage("raw")
        receiver.postMessage("stale\u001Epage-one\u001Ewrong generation")
        receiver.postMessage("document\u001Epage-one\u001E" + "中".repeat(22000))
        flushMessages()
        assertTrue(events.isEmpty())

        receiver.postMessage("document\u001Epage-one\u001Eready")
        flushMessages()
        val message = events.single() as WebViewEvent.PageMessage
        assertEquals("ready", message.data)
        assertFalse(session.reply(message.replyId, "x".repeat(65537)))
        assertTrue(session.reply(message.replyId, "reply"))
        assertTrue(shadowOf(owner).lastEvaluatedJavascript.contains("""__GY_ANDROID_PAGE_MESSAGE_REPLY__("page-one","PageReady","reply")"""))
        assertFalse(session.reply(message.replyId, "second reply"))

        session.onPageStarted(url)
        receiver.postMessage("document\u001Epage-one\u001Equeued before same URL reload")
        session.onPageStarted(url)
        flushMessages()
        assertEquals(1, events.size)
        assertNull(shadowOf(owner).getJavascriptInterface("ComposeWebViewPage_PageReady"))
    }

    @Test fun `physical owner replacement discards old callbacks and keeps new channel`() {
        val old = view()
        var currentOwner = old
        val events = mutableListOf<WebViewEvent>()
        val initial = pinnedRequest()
        val oldSession = AndroidPageMessageChannels(old, { initial }, { currentOwner === old }, events::add)
        val oldReceiver = old.receiver("PageReady")
        oldReceiver.postMessage("queued old")
        val fresh = view()
        currentOwner = fresh
        oldSession.revoke()
        AndroidPageMessageChannels(fresh, { initial }, { currentOwner === fresh }, events::add)
        oldReceiver.postMessage("old reference after revocation")
        flushMessages()
        assertTrue(events.isEmpty())
        fresh.receiver("PageReady").postMessage("fresh inline")
        flushMessages()
        assertEquals("fresh inline", (events.single() as WebViewEvent.PageMessage).data)
    }

    @Test fun `cancel clears pending replies and queue is bounded`() {
        val owner = view()
        owner.loadUrl(url)
        val initial = pinnedRequest()
        val events = mutableListOf<WebViewEvent.PageMessage>()
        val session = AndroidPageMessageChannels(owner, { initial }, { true }) {
            if (it is WebViewEvent.PageMessage) events += it
        }
        val receiver = owner.receiver("PageReady")
        repeat(129) { receiver.postMessage("message $it") }
        flushMessages()
        assertEquals(128, events.size)
        assertTrue(session.reply(events.first().replyId, "reply"))
        receiver.postMessage("after consume")
        flushMessages()
        assertEquals(129, events.size)
        session.revoke()
        assertFalse(session.reply(events.last().replyId, "cancelled"))
        receiver.postMessage("after cancel")
        flushMessages()
        assertEquals(129, events.size)
    }

    @Test
    @Config(shadows = [PageChannelUnsupportedFeatures::class])
    fun `fixed captcha document works without message listener support`() = assertFixedCaptchaChannel()

    @Test
    fun `fixed captcha document uses the same transport with message listener support`() = assertFixedCaptchaChannel()

    private fun assertFixedCaptchaChannel() {
        val captcha = pinnedRequest().copy(pageMessageChannels = setOf("smsCaptcha"))
        val owner = view()
        val events = mutableListOf<WebViewEvent>()
        val session = AndroidPageMessageChannels(owner, { captcha }, { true }, events::add)
        val shadow = shadowOf(owner)
        val receiver = owner.receiver("smsCaptcha")
        receiver.postMessage("READY")
        flushMessages()
        val event = events.single() as WebViewEvent.PageMessage
        assertEquals("smsCaptcha", event.channel)
        assertEquals("READY", event.data)
        owner.loadUrl(url)
        session.onPageStarted(url)
        assertTrue(session.reply(event.replyId, "config"))
        assertTrue(shadow.lastEvaluatedJavascript.contains("config"))
        assertFalse(session.reply(event.replyId, "duplicate"))
        receiver.postMessage("queued before reload")
        session.onPageStarted(url)
        flushMessages()
        assertEquals(listOf(event), events)
        assertNull(shadow.getJavascriptInterface("smsCaptcha"))
    }

    @Test
    @Config(shadows = [PageChannelUnsupportedFeatures::class])
    fun `business bridge accepts current document without listener and drops revoked callbacks`() {
        val url = "https://page.test/initial"
        val request = request.copy(security = WebViewSecurity(
            appBridgeEnabled = true,
            trustedOrigins = WebViewTrustPolicy(urls = listOf(url)),
        ))
        var token = "one"
        val received = mutableListOf<String>()
        val owner = WebView(RuntimeEnvironment.getApplication())
        owner.installAppWebBridge({ request }, { token }) { handler, _ -> received += handler }
        owner.loadUrl(url)
        owner.injectAppWebBridge()
        assertTrue(owner.hasAppWebBridge())
        val shadow = shadowOf(owner)
        assertTrue(shadow.lastEvaluatedJavascript.contains("WebViewJavascriptBridgeReady"))
        val receiver = shadow.getJavascriptInterface("ComposeWebViewBridgeTransport") as JavascriptMessageReceiver
        receiver.postMessage("one\u001EmerchantTransfer\u001F{}")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("merchantTransfer"), received)
        receiver.postMessage("one\u001Estale\u001F{}")
        token = "two"
        shadowOf(Looper.getMainLooper()).idle()
        receiver.postMessage("two\u001Ereleased\u001F{}")
        owner.removeAppWebBridge()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("merchantTransfer"), received)
        assertFalse(owner.hasAppWebBridge())
    }

    @Test fun `old constructor keeps an ordinary unpinned channel usable`() {
        val owner = view()
        val events = mutableListOf<WebViewEvent>()
        val session = AndroidPageMessageChannels(owner, { request }, { true }, events::add)
        owner.loadUrl(url)
        assertEquals(url, owner.url)
        owner.receiver("PageReady").postMessage("ready")
        flushMessages()
        val message = events.single() as WebViewEvent.PageMessage
        assertEquals("ready", message.data)
        assertTrue(session.reply(message.replyId, "config"))
        assertNull(shadowOf(owner).getJavascriptInterface("ComposeWebViewPage_PageReady"))
        assertFalse(session.reply("missing", "reply"))
    }
}

@Implements(value = WebViewFeature::class, isInAndroidSdk = false)
class PageChannelFeatures {
    companion object {
        @JvmStatic @Implementation
        fun isFeatureSupported(feature: String): Boolean = feature == WebViewFeature.WEB_MESSAGE_LISTENER
    }
}

@Implements(value = WebViewFeature::class, isInAndroidSdk = false)
class PageChannelUnsupportedFeatures {
    companion object {
        @JvmStatic @Implementation fun isFeatureSupported(feature: String): Boolean = false
    }
}
