package io.github.gycrosskit.composewebview

import kotlin.test.*

class WebViewPageMessagesTest {
    private val url = "http://page.test/initial.html"
    private fun request() = WebViewRequest(
        WebViewContent.Url(url), settings = WebViewSettings(javaScriptEnabled = true),
        pageMessageChannels = setOf("earlyChannel"),
    )

    @Test fun explicitLowPrivilegeChannelUsesExactInitialPage() {
        val request = request()
        assertTrue(request.canUsePageMessageChannelsAt(url))
        for (other in listOf(null, "http://page.test/other", "$url?q=1", "https://page.test/initial.html")) {
            assertFalse(request.canUsePageMessageChannelsAt(other))
        }
        assertFalse(request.security.appBridgeEnabled)
        assertFalse(request.canUseAppBridgeAt(url))
        assertFalse(request.copy(pageMessageChannels = emptySet()).canUsePageMessageChannelsAt(url))
        assertFalse(request.copy(settings = WebViewSettings()).canUsePageMessageChannelsAt(url))
        assertFalse(request.copy(navigationPolicy = WebViewNavigationPolicy(allowedUrls = setOf("http://page.test/other"))).canUsePageMessageChannelsAt(url))
    }

    @Test fun channelNamesCannotReplaceExistingBridgeOrFrameGlobals() {
        for (channel in listOf("window", "top", "constructor", "__proto__", "webkit", "JSAndroidBridge", "GYWebViewBridge", "ComposeWebViewEvent", "a.b", "a'", "1early", "x".repeat(81))) {
            assertFailsWith<IllegalArgumentException>(channel) { request().copy(pageMessageChannels = setOf(channel)) }
        }
        assertFails { request().copy(pageMessageChannels = (0..16).map { "channel$it" }.toSet()) }
        assertFails { request().copy(content = WebViewContent.Html("<p>x</p>")) }
    }

    @Test fun wirePreservesChannelsAndOpaqueReplyAndRejectsWrongTypes() {
        val request = request()
        assertEquals(request, WebViewWire.decodeRequest(WebViewWire.encodeRequest(request)))
        val event = WebViewEvent.PageMessage("earlyChannel", "{\"中文\":1}", "opaque-id")
        val values = WebViewWire.eventValues(event)
        assertEquals("pageMessage", values["type"])
        assertEquals(event, WebViewWire.decodeEvent("""{"type":"pageMessage","channel":"earlyChannel","data":"{\"中文\":1}","replyId":"opaque-id"}"""))
        for (value in listOf("true", "\"earlyChannel\"", "[1]", "null")) {
            assertFails { WebViewWire.decodeRequest("""{"content":{"type":"url","url":"$url"},"pageMessageChannels":$value}""") }
        }
        assertFalse(isValidPageMessageData("字".repeat(22000)))
        assertTrue(isValidPageMessageData("x".repeat(65536)))
        assertFalse(isValidPageMessageData("x".repeat(65537)))
    }
}
