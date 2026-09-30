package io.github.gycrosskit.composewebview

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebViewNavigationPolicyTest {
    @Test fun policyBlocksSchemesWindowsRulesAndUntrustedBridgeNavigation() {
        val request = WebViewRequest(
            WebViewContent.Url("https://safe.example/start"),
            security = WebViewSecurity(WebViewTrustPolicy(listOf("https://safe.example")), appBridgeEnabled = true),
            navigationPolicy = WebViewNavigationPolicy(blockedRules = listOf(WebViewUrlRule.Contains("/blocked"))),
        )
        fun navigation(url: String, target: WebViewNavigationTarget = WebViewNavigationTarget.CURRENT_WINDOW) =
            WebViewNavigationRequest(url, true, false, target)
        assertTrue(request.allowsNavigation(navigation("https://safe.example/page")))
        assertFalse(request.allowsNavigation(navigation("https://safe.example/blocked")))
        assertFalse(request.allowsNavigation(navigation("https://safe.example.evil.test/page")))
        assertFalse(request.allowsNavigation(navigation("javascript:alert(1)")))
        assertFalse(request.allowsNavigation(navigation("https://safe.example/page", WebViewNavigationTarget.NEW_WINDOW)))
    }

    @Test fun readOnlyMainFrameOriginsAreExactAndComposeWithBlockedRoutes() {
        val request = WebViewRequest(
            WebViewContent.Url("https://SAFE.example/start"),
            settings = WebViewSettings(javaScriptEnabled = false),
            navigationPolicy = WebViewNavigationPolicy(
                allowedOrigins = setOf("https://safe.example:443/path", "http://legacy.example:80"),
                blockedRules = listOf(WebViewUrlRule.Contains("/shop")),
            ),
        )
        fun navigation(url: String, mainFrame: Boolean = true) = WebViewNavigationRequest(url, mainFrame, false)
        assertTrue(request.allowsNavigation(navigation("https://safe.example/page?next=other")))
        assertTrue(request.allowsNavigation(navigation("http://legacy.example/page")))
        for (url in listOf("http://safe.example/page", "https://safe.example:8443/page", "https://safe.example.evil.test/page", "https://other.example", "https://safe.example/shop", "data:text/html,<h1>later</h1>")) {
            assertFalse(request.allowsNavigation(navigation(url)), url)
        }
        assertTrue(request.allowsNavigation(navigation("https://other.example/frame", mainFrame = false)))
        assertTrue(request.allowsNavigation(navigation("https://safe.example/shop", mainFrame = false)))
        assertTrue(WebViewNavigationPolicy().allows(navigation("https://other.example")))
        for (url in listOf("javascript:alert(1)", "https://safe.example:0", "https://safe.example:65536", "https://user@safe.example", "https://user:password@safe.example", "not a URL")) {
            kotlin.test.assertFailsWith<IllegalArgumentException>(message = url) { WebViewNavigationPolicy(allowedOrigins = setOf(url)) }
        }
    }

    @Test fun htmlInitialExceptionRequiresInternalMainFrameWithoutGesture() {
        val initial = WebViewNavigationRequest("data:text/html,<h1>initial</h1>", true, false)
        assertTrue(initial.isInternalHtmlInitialNavigation())
        assertFalse(initial.copy(hasUserGesture = true).isInternalHtmlInitialNavigation())
        assertFalse(initial.copy(isMainFrame = false).isInternalHtmlInitialNavigation())
        assertFalse(initial.copy(target = WebViewNavigationTarget.NEW_WINDOW).isInternalHtmlInitialNavigation())
        assertFalse(initial.copy(url = "https://safe.example").isInternalHtmlInitialNavigation())
        assertFalse(initial.copy(url = "data:application/javascript,alert(1)").isInternalHtmlInitialNavigation())
        assertFalse(WebViewNavigationPolicy().allows(initial))
    }
}
