package io.github.gycrosskit.composewebview

import kotlin.test.*

class WebViewWireTest {
    @Test fun requestRoundTripAndMalformedSecurityIsRejected() {
        val request = WebViewRequest(
            WebViewContent.Html("<h1>中文</h1>", "https://safe.example/base"),
            settings = WebViewSettings(javaScriptEnabled = true, userAgentSuffix = "test"),
            security = WebViewSecurity(WebViewTrustPolicy(listOf("https://safe.example/path")), appBridgeEnabled = true),
            scripts = listOf(WebViewScript("ready", "window.ready = true")),
            blockedResourceRules = listOf(WebViewUrlRule.HostSuffix("ads.example")),
            navigationPolicy = WebViewNavigationPolicy(blockedRules = listOf(WebViewUrlRule.Contains("/exit")), allowedOrigins = setOf("https://safe.example")),
        )
        assertEquals(request, WebViewWire.decodeRequest(WebViewWire.encodeRequest(request)))
        assertFails { WebViewWire.decodeRequest("""{"content":{"type":"url","url":"https://safe.example"},"security":{"appBridgeEnabled":true}}""") }
        assertFails { WebViewWire.decodeRequest("""{"content":{"type":"other"}}""") }
        assertFails { WebViewWire.decodeRequest("""{"content":{"type":"url","url":"https://safe.example"},"navigationPolicy":{"allowedOrigins":["https://safe.example:0"]}}""") }
    }
    @Test fun nativeEventDictionariesDecodeAndRejectInvalidProgress() {
        assertEquals(WebViewEvent.BridgeMessage(WebViewBridgeMessage("sample", "{\"value\":1}")), WebViewWire.decodeEvent("""{"type":"bridgeMessage","handlerName":"sample","data":"{\"value\":1}"}"""))
        assertEquals(WebViewEvent.HistoryChanged(true, false, null), WebViewWire.decodeEvent("""{"type":"historyChanged","canGoBack":true,"canGoForward":false,"url":null}"""))
        assertFails { WebViewWire.decodeEvent("""{"type":"progressChanged","progress":101}""") }
        val unsupported = WebViewEvent.CapabilityUnsupported(WebViewCapability.FILE_CAPTURE)
        assertEquals(unsupported, WebViewWire.decodeEvent("""{"type":"capabilityUnsupported","capability":"FILE_CAPTURE"}"""))
        assertEquals(mapOf("type" to "capabilityUnsupported", "capability" to "FILE_CAPTURE"), WebViewWire.eventValues(unsupported))
    }
}
