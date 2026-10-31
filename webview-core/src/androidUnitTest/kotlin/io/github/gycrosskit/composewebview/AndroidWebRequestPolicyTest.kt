package io.github.gycrosskit.composewebview

import android.net.Uri
import android.webkit.WebResourceRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AndroidWebRequestPolicyTest {
    private val allowed = "https://page.test/form?mode=one#form"
    private fun resource(url: String, main: Boolean = true, method: String = "POST") = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(url)
        override fun isForMainFrame() = main
        override fun isRedirect() = false
        override fun hasGesture() = true
        override fun getMethod() = method
        override fun getRequestHeaders() = emptyMap<String, String>()
    }

    @Test fun `POST restriction covers exact origins deny rules and schemes without affecting resources`() {
        val request = WebViewRequest(WebViewContent.Url(allowed))
        val rejected = mutableListOf<WebViewNavigationRequest>()
        val restrictedPolicies = listOf(
            WebViewNavigationPolicy(allowedUrls = setOf(allowed)),
            WebViewNavigationPolicy(allowedOrigins = setOf("https://page.test")),
            WebViewNavigationPolicy(navigationOrigins = WebViewNavigationOrigins(exactOrigins = setOf("https://page.test"))),
            WebViewNavigationPolicy(blockedRules = listOf(WebViewUrlRule.ExactHost("other.test"))),
            WebViewNavigationPolicy(allowedSchemes = setOf("https")),
        )
        for (policy in restrictedPolicies) {
            val current = request.copy(navigationPolicy = policy)
            assertNotNull(current.interceptAndroidRequest(resource(allowed), rejected::add))
            assertNull(current.interceptAndroidRequest(resource(allowed, main = false), rejected::add))
            assertNull(current.interceptAndroidRequest(resource(allowed, method = "GET"), rejected::add))
        }
        assertEquals(5, rejected.size)
        assertNull(request.interceptAndroidRequest(resource(allowed), rejected::add))
        val bridge = request.copy(security = WebViewSecurity(appBridgeEnabled = true, trustedOrigins = WebViewTrustPolicy(listOf(allowed))))
        assertNull(bridge.interceptAndroidRequest(resource(allowed), rejected::add))
        assertNotNull(bridge.interceptAndroidRequest(resource("https://other.test"), rejected::add))
        val resources = request.copy(blockedResourceRules = listOf(WebViewUrlRule.ExactHost("other.test")))
        assertNotNull(resources.interceptAndroidRequest(resource("https://other.test", main = false), rejected::add))
        assertEquals(6, rejected.size)
    }

}
