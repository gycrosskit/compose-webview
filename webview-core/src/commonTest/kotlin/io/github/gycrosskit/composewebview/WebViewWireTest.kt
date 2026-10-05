package io.github.gycrosskit.composewebview

import kotlin.test.*

class WebViewWireTest {
    @Test fun nativeNavigationFlagsRequireJsonBooleans() {
        for (blocked in listOf(true, false)) {
            val event = WebViewWire.decodeEvent("""{"type":"navigation","url":"https://safe.example","isMainFrame":true,"hasUserGesture":false,"blocked":$blocked}""") as WebViewEvent.Navigation
            assertEquals(blocked, event.blocked)
        }
        for (field in listOf("blocked", "isMainFrame", "hasUserGesture")) {
            for (invalid in listOf("0", "1", "\"true\"", "\"false\"")) {
                assertFails { WebViewWire.decodeEvent("""{"type":"navigation","url":"https://safe.example","$field":$invalid}""") }
            }
        }
    }
    @Test fun wrongJsonTypesCannotEnableCapabilitiesOrChangeHeaders() {
        val content = """"content":{"type":"url","url":"https://safe.example"}"""
        for (field in listOf(
            """"settings":{"javaScriptEnabled":"true"}""",
            """"settings":{"minimumTextZoomPercent":"100"}""",
            """"security":{"pageBridgeEnabled":"true"}""",
            """"navigationPolicy":{"allowedSchemes":[1]}""",
        )) {
            assertFails(message = field) { WebViewWire.decodeRequest("{$content,$field}") }
        }
        assertFails { WebViewWire.decodeRequest("""{"content":{"type":"url","url":"https://safe.example","additionalHeaders":{"X-Request":1}}}""") }
        assertFails { WebViewWire.decodeEvent("""{"type":"progressChanged","progress":"100"}""") }
        assertFails { WebViewWire.decodeEvent("""{"type":"fullscreenChanged","isFullscreen":"true"}""") }
        // 省略字段仍保持历史默认值；真实布尔、数字与字符串不受影响。
        val decoded = WebViewWire.decodeRequest("{$content}")
        assertEquals(WebViewSettings(), decoded.settings)
        assertEquals(WebViewSecurity(), decoded.security)
    }
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
