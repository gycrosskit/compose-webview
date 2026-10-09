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

    @Test fun browserNormalizedInitialDocumentKeepsExactNavigationPolicy() {
        for ((declared, actual) in listOf(
            "https://page.test" to "https://page.test/",
            "HTTPS://PAGE.TEST:443?q=a%20b#part" to "https://page.test/?q=a%20b#part",
            "http://PAGE.TEST:80/initial.html?q=1#part" to "http://page.test/initial.html?q=1#part",
        )) {
            val request = request().copy(content = WebViewContent.Url(declared))
            assertTrue(request.canUsePageMessageChannelsAt(actual), declared)
            for (other in listOf("$actual/other", "$actual?extra=1", "$actual#next")) {
                assertFalse(request.canUsePageMessageChannelsAt(other), other)
            }
            assertFalse(request.copy(navigationPolicy = WebViewNavigationPolicy(allowedUrls = setOf(declared)))
                .canUsePageMessageChannelsAt(actual))
            val canonicalPolicy = request.copy(navigationPolicy = WebViewNavigationPolicy(allowedUrls = setOf(actual)))
            assertTrue(canonicalPolicy.canUsePageMessageChannelsAt(actual))
            assertNotNull(canonicalPolicy.pageMessageScript("token"))
        }
        val request = request().copy(content = WebViewContent.Url("https://page.test"))
        for (other in listOf("https://page.test/?", "https://page.test/#", "https://page.test./", "https://page.test:8443/")) {
            assertFalse(request.canUsePageMessageChannelsAt(other), other)
        }
    }

    @Test fun normalizedDocumentKeepsEscapesQueryOrderAndFragmentsDistinct() {
        val declared = "https://page.test/a%2Fb?q=a%20b&q=second#part"
        val request = request().copy(content = WebViewContent.Url(declared))
        for (other in listOf(
            "https://page.test/a/b?q=a%20b&q=second#part",
            "https://page.test/a%2fb?q=a%20b&q=second#part",
            "https://page.test/a%2Fb?q=a+b&q=second#part",
            "https://page.test/a%2Fb?q=second&q=a%20b#part",
            "https://user@page.test/a%2Fb?q=a%20b&q=second#part",
            "https://page.test/A%2Fb?q=a%20b&q=second#part",
        )) assertFalse(request.canUsePageMessageChannelsAt(other), other)
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
