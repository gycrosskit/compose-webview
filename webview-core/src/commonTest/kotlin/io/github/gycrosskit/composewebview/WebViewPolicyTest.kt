package io.github.gycrosskit.composewebview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFails

/** 固化 Android/iOS 必须共用的高权限来源判断，防止平台 actual 各自演进后产生安全差异。 */
class WebViewPolicyTest {
    private val trustedUrl = "https://trusted.example/page"
    private val untrustedUrl = "https://untrusted.example/page"
    private val request = WebViewRequest(
        content = WebViewContent.Url(trustedUrl),
        settings = WebViewSettings(javaScriptEnabled = true),
        security = WebViewSecurity(
            trustedOrigins = WebViewTrustPolicy(listOf(trustedUrl)),
            appBridgeEnabled = true,
        ),
    )

    @Test
    fun bridgeAndMainFrameNavigationUseSameTrustPolicy() {
        assertTrue(request.canUseAppBridgeAt(trustedUrl))
        assertFalse(request.canUseAppBridgeAt(untrustedUrl))
        assertFalse(request.shouldBlockMainFrameNavigation(trustedUrl, isMainFrame = true))
        assertTrue(request.shouldBlockMainFrameNavigation(untrustedUrl, isMainFrame = true))
        assertFalse(request.shouldBlockMainFrameNavigation(untrustedUrl, isMainFrame = false))
        assertTrue(request.canReceiveAppBridgeMessage(trustedUrl, isMainFrame = true))
        assertFalse(request.canReceiveAppBridgeMessage(trustedUrl, isMainFrame = false))
        assertFalse(request.canReceiveAppBridgeMessage(untrustedUrl, isMainFrame = false))
        assertFalse(request.canReceiveAppBridgeMessage(untrustedUrl, isMainFrame = true))
    }

    @Test
    fun pageBridgeAllowsOnlyTheInitialHttpOrigin() {
        val pageRequest = WebViewRequest(
            content = WebViewContent.Url("http://legacy.example.test/rules"),
            security = WebViewSecurity(pageBridgeEnabled = true),
        )

        assertTrue(pageRequest.canUseAppBridgeAt("http://legacy.example.test/next"))
        assertFalse(pageRequest.canUseAppBridgeAt("https://legacy.example.test/next"))
        assertFalse(pageRequest.canUseAppBridgeAt("http://other.example.test/next"))
        assertFalse(
            pageRequest.shouldBlockMainFrameNavigation(
                "http://legacy.example.test/next",
                isMainFrame = true,
            ),
        )
        assertTrue(
            pageRequest.shouldBlockMainFrameNavigation(
                "http://other.example.test/next",
                isMainFrame = true,
            ),
        )
    }

    @Test
    fun scriptGateHonorsTrustedMainFrameFlag() {
        val trustedOnly = WebViewScript(id = "trusted", source = "window.run()")
        val unrestricted = trustedOnly.copy(id = "public", onlyForTrustedMainFrame = false)

        assertTrue(request.canInject(trustedOnly, trustedUrl))
        assertFalse(request.canInject(trustedOnly, untrustedUrl))
        assertTrue(request.canInject(unrestricted, untrustedUrl))
    }

    @Test
    fun initialOriginDependsOnContentType() {
        assertEquals(trustedUrl, request.content.initialOrigin())
        assertEquals(
            "https://inline.example",
            WebViewContent.Html("<p>content</p>", baseUrl = "https://inline.example").initialOrigin(),
        )
        assertEquals(null, WebViewContent.Html("<p>content</p>").initialOrigin())
    }

    @Test
    fun blankContentUsesSameCrossPlatformSemantics() {
        assertTrue(WebViewContent.Url("  ").isBlankWebViewContent())
        assertTrue(WebViewContent.Html("\n").isBlankWebViewContent())
        assertFalse(WebViewContent.Url(trustedUrl).isBlankWebViewContent())
        assertFalse(WebViewContent.Html("<p>content</p>").isBlankWebViewContent())
    }
    @Test
    fun manualJavascriptRequiresEnabledScriptAndApprovedOrigin() {
        val trusted = WebViewRequest(
            WebViewContent.Url("https://safe.example"),
            WebViewSettings(javaScriptEnabled = true),
            WebViewSecurity(WebViewTrustPolicy(listOf("https://safe.example"))),
        )
        assertTrue(trusted.canEvaluateJavascriptAt("https://safe.example/page"))
        assertFalse(trusted.canEvaluateJavascriptAt("https://safe.example.evil.test"))
        assertFalse(trusted.copy(settings = WebViewSettings()).canEvaluateJavascriptAt("https://safe.example"))
        val legacy = trusted.copy(content = WebViewContent.Url("http://legacy.example"), security = WebViewSecurity(pageBridgeEnabled = true))
        assertTrue(legacy.canEvaluateJavascriptAt("http://legacy.example/page"))
        assertFalse(legacy.canEvaluateJavascriptAt("http://legacy.example:8080/page"))
    }

    @Test fun pageScriptsUseTheSameOriginAndJavascriptGateAsManualEvaluation() {
        val early = WebViewScript("captcha", "window.ready = true", WebViewScriptInjectionTime.DOCUMENT_START)
        val finished = early.copy(injectionTime = WebViewScriptInjectionTime.DOCUMENT_FINISHED)
        for ((initial, registration) in listOf(
            "http://legacy.example/captcha" to "http://legacy.example:80",
            "http://legacy.example:8080/captcha" to "http://legacy.example:8080",
            "https://legacy.example:8443/captcha" to "https://legacy.example:8443",
        )) {
            val page = WebViewRequest(WebViewContent.Url(initial), WebViewSettings(javaScriptEnabled = true),
                WebViewSecurity(pageBridgeEnabled = true), listOf(early, finished))
            assertTrue(page.canInject(early, initial))
            assertEquals(listOf(finished), page.finishedScriptsAt(initial))
            assertTrue(registration in page.earlyScriptOriginRules())
            assertTrue(requireNotNull(page.earlyScriptSource()).contains("window !== window.top"))
            for (other in listOf("https://evil.example/captcha", "http://legacy.example.evil/captcha", "http://legacy.example:9090/captcha")) {
                assertFalse(page.canInject(early, other), other)
                assertTrue(page.finishedScriptsAt(other).isEmpty(), other)
            }
            assertFalse(page.canReceiveAppBridgeMessage(initial, isMainFrame = false))
            val disabled = page.copy(settings = WebViewSettings())
            assertFalse(disabled.canInject(early, initial))
            assertTrue(disabled.earlyScriptOriginRules().isEmpty())
            assertTrue(disabled.finishedScriptsAt(initial).isEmpty())
            val noBridge = page.copy(security = WebViewSecurity())
            assertFalse(noBridge.canInject(early, initial))
            assertTrue(noBridge.earlyScriptOriginRules().isEmpty())
        }
        assertFails { WebViewSecurity(WebViewTrustPolicy(listOf(trustedUrl)), appBridgeEnabled = true, pageBridgeEnabled = true) }
        val httpTrust = WebViewTrustPolicy(listOf("http://legacy.example"))
        assertTrue(httpTrust.isEmpty)
        assertFalse(httpTrust.isTrusted("http://legacy.example"))
        assertFails { WebViewSecurity(httpTrust, appBridgeEnabled = true) }
    }

    @Test fun exactMainFrameUrlsComposeWithExistingPolicyAndStrictWire() {
        val url = "http://legacy.example:8080/captcha?mode=v2"
        val policy = WebViewNavigationPolicy(allowedUrls = setOf(url), allowedOrigins = setOf("http://legacy.example:8080"))
        val page = WebViewRequest(WebViewContent.Url(url), navigationPolicy = policy)
        assertEquals(page, WebViewWire.decodeRequest(WebViewWire.encodeRequest(page)))
        fun navigation(target: String, main: Boolean = true) = WebViewNavigationRequest(target, main, false)
        assertTrue(page.allowsNavigation(navigation(url)))
        for (other in listOf("http://legacy.example:8080/other?mode=v2", "$url&extra=1", "$url#next",
            "HTTP://legacy.example:8080/captcha?mode=v2", "http://legacy.example/captcha?mode=v2", "https://legacy.example:8080/captcha?mode=v2")) {
            assertFalse(page.allowsNavigation(navigation(other)), other)
            assertTrue(page.allowsNavigation(navigation(other, main = false)), other)
        }
        assertFalse(policy.copy(allowedSchemes = setOf("https")).allows(navigation(url)))
        assertFalse(policy.copy(allowedOrigins = setOf("http://other.example")).allows(navigation(url)))
        assertFalse(policy.copy(blockedRules = listOf(WebViewUrlRule.Contains("/captcha"))).allows(navigation(url)))
        assertTrue(WebViewNavigationPolicy().allows(navigation(url)))
        for (invalid in listOf("null", "\"$url\"", "[1]", "[\"javascript:evil\"]", "[\"http://user@legacy.example/captcha\"]",
            "[\"http://legacy.example:0/captcha\"]", "[\" $url\"]")) {
            assertFails(message = invalid) { WebViewWire.decodeRequest("""{"content":{"type":"url","url":"$url"},"navigationPolicy":{"allowedUrls":$invalid}}""") }
        }
    }
}
