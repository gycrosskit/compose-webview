package io.github.gycrosskit.composewebview

import kotlin.test.*

class WebViewHttpOriginsTest {
    @Test fun exactHttpOriginsApplyToNavigationAndWire() {
        val legacy = WebViewRequest(WebViewContent.Url("https://page.test"))
        assertTrue(legacy.allowsHttpRequest("http://other.test"))
        for (origins in listOf(emptySet(), setOf("http://Allowed.test:80"))) {
            val request = legacy.copy(allowedHttpOrigins = origins)
            assertEquals(origins, WebViewWire.decodeRequest(WebViewWire.encodeRequest(request)).allowedHttpOrigins)
            for (url in listOf("http://other.test", "http://allowed.test:81", "http://allowed.test.evil", "http://user@allowed.test")) {
                assertFalse(request.allowsHttpRequest(url), url)
                assertFalse(request.allowsNavigation(WebViewNavigationRequest(url, true, false)), url)
            }
            assertTrue(request.allowsHttpRequest("https://other.test/resource"))
            assertEquals(origins.isNotEmpty(), request.allowsHttpRequest("http://allowed.test/path"))
        }
        assertNull(WebViewWire.decodeRequest(WebViewWire.encodeRequest(legacy)).allowedHttpOrigins)
        for (bad in listOf("https://allowed.test", "http://user@allowed.test", "http://allowed.test:0")) {
            assertFailsWith<IllegalArgumentException> { legacy.copy(allowedHttpOrigins = setOf(bad)) }
        }
    }
}
