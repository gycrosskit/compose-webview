package io.github.gycrosskit.composewebview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 固化 Android/iOS 必须共用的高权限来源判断，防止平台 actual 各自演进后产生安全差异。 */
class WebViewPolicyTest {
    private val trustedUrl = "https://trusted.example/page"
    private val untrustedUrl = "https://untrusted.example/page"
    private val request = WebViewRequest(
        content = WebViewContent.Url(trustedUrl),
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
}
