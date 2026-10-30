package io.github.gycrosskit.composewebview

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class WebViewNavigationPolicyTest {
    @Test fun explicitAnyPortSuffixRetainsSchemeHostAndBridgeBoundaries() {
        val origins = WebViewNavigationOrigins(hostSuffixAnyPortOrigins = setOf("http://shop.example", "https://shop.example"))
        val request = WebViewRequest(WebViewContent.Url("https://safe.example"), security = WebViewSecurity(
            WebViewTrustPolicy(listOf("https://safe.example")), appBridgeEnabled = true),
            navigationPolicy = WebViewNavigationPolicy(navigationOrigins = origins))
        assertEquals(request, WebViewWire.decodeRequest(WebViewWire.encodeRequest(request)))
        for (url in listOf("http://shop.example:8080/a", "https://child.shop.example:9443/a")) {
            assertTrue(request.allowsNavigation(WebViewNavigationRequest(url, true, false)), url)
            assertFalse(request.canReceiveAppBridgeMessage(url, true), url)
        }
        for (url in listOf("http://shop.example.evil:8080", "http://user@shop.example:8080", "https://evilshop.example:9443"))
            assertFalse(request.allowsNavigation(WebViewNavigationRequest(url, true, false)), url)
        assertFalse(request.copy(navigationPolicy = WebViewNavigationPolicy(allowedSchemes = setOf("https"), navigationOrigins = origins))
            .allowsNavigation(WebViewNavigationRequest("http://shop.example:8080", true, false)))
        kotlin.test.assertFails { WebViewNavigationOrigins(hostSuffixAnyPortOrigins = setOf("https://shop.example:8443")) }
        kotlin.test.assertFails { WebViewNavigationOrigins(hostSuffixAnyPortOrigins = setOf("https://[::1]")) }
    }
    @Test fun independentOriginsDoNotGrantBridgeAndRetainLegacyDefault() {
        val start = "https://safe.example/start"
        val legacy = WebViewRequest(WebViewContent.Url(start), security = WebViewSecurity(
            WebViewTrustPolicy(listOf("https://safe.example")), appBridgeEnabled = true))
        val policy = WebViewNavigationOrigins(setOf("http://legacy.example:8080"), setOf("https://shop.example:8443"))
        val request = legacy.copy(navigationPolicy = WebViewNavigationPolicy(navigationOrigins = policy))
        assertEquals(request, WebViewWire.decodeRequest(WebViewWire.encodeRequest(request)))
        fun nav(url: String) = WebViewNavigationRequest(url, true, false)
        for (url in listOf("http://legacy.example:8080/a", "https://shop.example:8443", "https://a.shop.example:8443/a")) {
            assertFalse(legacy.allowsNavigation(nav(url)))
            assertTrue(request.allowsNavigation(nav(url)))
            assertFalse(request.shouldBlockMainFrameNavigation(url, true))
            assertFalse(request.canReceiveAppBridgeMessage(url, true))
        }
        for (url in listOf("http://shop.example:8443", "https://shop.example", "https://badshop.example:8443", "https://shop.example.evil:8443", "https://user@shop.example:8443")) {
            assertFalse(request.allowsNavigation(nav(url)), url)
        }
        assertFalse(request.canReceiveAppBridgeMessage(start, false))
        assertFalse(request.copy(navigationPolicy = WebViewNavigationPolicy(navigationOrigins = WebViewNavigationOrigins())).allowsNavigation(nav(start)))
        kotlin.test.assertFails { WebViewWire.decodeRequest("""{"content":{"type":"url","url":"$start"},"navigationPolicy":{"navigationOrigins":{}}}""") }
    }
    @Test fun mallUserInfoIsAnOptionalStrictCondition() {
        val rule = WebViewUrlRule.HostSuffix("jd.com", "https", false, rejectUserInfo = true)
        for (url in listOf("https://user@shop.jd.com/a", "https://user:password@shop.jd.com", "https://@shop.jd.com", "https://:@shop.jd.com", "https://shop.jd.com.evil/a", "https://shop.jd.com..", "https://shop..jd.com", "http://shop.jd.com", "https://jd.com")) assertFalse(rule.matches(url), url)
        assertTrue(rule.matches("https://shop.jd.com:8443/path"))
        assertTrue(rule.copy(rejectUserInfo = false).matches("https://user@shop.jd.com/path"))
        val request = WebViewRequest(WebViewContent.Url("https://safe.example"), navigationPolicy = WebViewNavigationPolicy(blockedRules = listOf(rule)))
        assertEquals(request, WebViewWire.decodeRequest(WebViewWire.encodeRequest(request)))
    }

    @Test fun mallSchemeAndSubdomainRuleRoundTripsAndCombinesWithOriginPolicy() {
        val rule = WebViewUrlRule.HostSuffix("jd.com", scheme = "https", includeRoot = false)
        val request = WebViewRequest(WebViewContent.Url("https://safe.example"), navigationPolicy = WebViewNavigationPolicy(
            allowedOrigins = setOf("https://safe.example", "https://shop.jd.com", "https://jd.com"), blockedRules = listOf(rule)))
        assertEquals(request, WebViewWire.decodeRequest(WebViewWire.encodeRequest(request)))
        for (url in listOf("https://shop.jd.com/item", "HTTPS://SHOP.JD.COM:443/item", "https://a.b.jd.com/item")) assertTrue(rule.matches(url), url)
        for (url in listOf("http://shop.jd.com/item", "https://jd.com/item", "https://shop.jd.com.evil/item", "https://eviljd.com/item")) assertFalse(rule.matches(url), url)
        assertTrue(request.allowsNavigation(WebViewNavigationRequest("https://jd.com/item", true, false)))
        assertFalse(request.allowsNavigation(WebViewNavigationRequest("https://shop.jd.com/item", true, false)))
        assertFalse(request.allowsNavigation(WebViewNavigationRequest("https://other.example/item", true, false)))
    }
    @Test fun navigationOriginsDoNotGrantBridgeAndRemainIndependentOfJavascript() {
        for (javascript in listOf(false, true)) {
            val request = WebViewRequest(
                WebViewContent.Url("https://safe.example/start"),
                settings = WebViewSettings(javaScriptEnabled = javascript),
                navigationPolicy = WebViewNavigationPolicy(
                    allowedOrigins = setOf("https://safe.example", "https://other.example"),
                    blockedRules = listOf(WebViewUrlRule.Contains("/shop")),
                ),
            )
            for (origin in listOf("https://safe.example", "https://other.example")) {
                assertTrue(request.allowsNavigation(WebViewNavigationRequest("$origin/page", true, false)))
                assertFalse(request.canUseAppBridgeAt("$origin/page"))
                assertFalse(request.canReceiveAppBridgeMessage("$origin/page", true))
                assertFalse(request.allowsNavigation(WebViewNavigationRequest("$origin/shop", true, false)))
            }
        }
    }

    @Test fun copyAndCallerMutableSetRetainTheirExistingNavigationSemantics() {
        val origins = mutableSetOf("https://safe.example")
        val policy = WebViewNavigationPolicy(allowedOrigins = origins)
        val copy = policy.copy(allowNewWindows = true)
        val independent = policy.copy(allowedOrigins = setOf("https://other.example"))
        fun navigation(host: String) = WebViewNavigationRequest("https://$host/page", true, false)
        assertTrue(policy.allows(navigation("safe.example")))
        assertFalse(policy.allows(navigation("other.example")))
        origins.clear()
        origins.add("https://other.example")
        assertFalse(policy.allows(navigation("safe.example")))
        assertTrue(policy.allows(navigation("other.example")))
        assertTrue(copy.allows(navigation("other.example")))
        assertEquals(independent, policy)
        assertEquals(independent.hashCode(), policy.hashCode())
    }

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
