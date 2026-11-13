package io.github.gycrosskit.composewebview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeGeolocationTest {
    private val request = WebViewRequest(
        WebViewContent.Url("https://trusted.test/location"),
        WebViewSettings(javaScriptEnabled = true),
        WebViewSecurity(trustedOrigins = WebViewTrustPolicy(trustedHostSuffixes = setOf("trusted.test")),
            geolocationEnabled = true),
    )

    @Test fun adapterIsExplicitAndRequiresTrustedHttps() {
        assertTrue(request.pageMessageChannels.isEmpty())
        assertTrue(request.scripts.isEmpty())
        val adapted = request.withNativeGeolocation()
        assertEquals(setOf(NATIVE_GEOLOCATION_CHANNEL), adapted.pageMessageChannels)
        assertEquals(WebViewScriptInjectionTime.DOCUMENT_START_REQUIRED, adapted.scripts.single().injectionTime)
        assertNull(adapted.earlyScriptSource())
        assertTrue(requireNotNull(adapted.earlyScriptSource(atDocumentStart = true)).contains("navigator.geolocation"))
        assertEquals(adapted, WebViewWire.decodeRequest(WebViewWire.encodeRequest(adapted)))
        assertTrue(adapted.scripts.single().onlyForTrustedMainFrame)
        for (value in listOf(
            request.copy(content = WebViewContent.Url("http://trusted.test/location")),
            request.copy(content = WebViewContent.Url("https://evil.test/location")),
            request.copy(settings = request.settings.copy(javaScriptEnabled = false)),
            request.copy(security = request.security.copy(geolocationEnabled = false)),
        )) assertFailsWith<IllegalArgumentException> { value.withNativeGeolocation() }
    }

    @Test fun onlyBoundedTypedMessagesAreAccepted() {
        val raw = """{"id":"12","action":"get","options":{"enableHighAccuracy":true,"maximumAge":0,"timeout":null}}"""
        val parsed = assertIs<NativeGeolocationRequest.GetPosition>(parseNativeGeolocationRequest(raw))
        assertEquals("12", parsed.id)
        assertEquals(NativeGeolocationOptions(true, 0, null), parsed.options)
        assertIs<NativeGeolocationRequest.Cancel>(parseNativeGeolocationRequest("""{"id":"12","action":"cancel"}"""))
        for (invalid in listOf(
            raw.replace("true", "\"true\""), raw.replace(":0", ":-1"), raw.replace(":0", ":4294967296"),
            raw.replace("null", "\"1000\""), raw.replace("\"12\"", "12"), raw.replace("\"12\"", "\"0\""),
            raw.replace("\"get\"", "[]"), "x".repeat(1025), """{"id":"12","action":"cancel","url":"https://evil.test"}""",
        )) assertNull(parseNativeGeolocationRequest(invalid), invalid)
    }

    @Test fun coarseAccuracyAndOriginalTimestampArePreserved() {
        val raw = nativeGeolocationPositionReply("1", 31.0, 121.0, 3500.0, 123456789L)
        val position = Json.parseToJsonElement(raw).jsonObject.getValue("position").jsonObject
        assertEquals("123456789", position.getValue("timestamp").toString())
        assertEquals("3500.0", position.getValue("coords").jsonObject.getValue("accuracy").toString())
        assertFailsWith<IllegalArgumentException> { nativeGeolocationPositionReply("1", 91.0, 0.0, 1.0, 1) }
        assertFailsWith<IllegalArgumentException> { nativeGeolocationPositionReply("1", 0.0, 0.0, Double.NaN, 1) }
        assertTrue(nativeGeolocationErrorReply("1", NativeGeolocationError.PERMISSION_DENIED, "denied").contains("\"code\":1"))
    }
}
