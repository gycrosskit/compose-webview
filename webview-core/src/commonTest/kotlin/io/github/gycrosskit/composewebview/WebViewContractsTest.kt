package io.github.gycrosskit.composewebview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebViewContractsTest {
    @Test
    fun bridgeMessagesRequireHandlerAndSeparatorOnBothPlatforms() {
        assertEquals(
            WebViewBridgeMessage("videoEnded", "{\"code\":0}"),
            parseAppWebBridgeMessage("videoEnded\u001F{\"code\":0}"),
        )
        assertNull(parseAppWebBridgeMessage("videoEnded"))
        assertNull(parseAppWebBridgeMessage("\u001F{}"))
    }

    @Test
    fun percentEncodedPlaceholderTitlesAreRejectedOnBothPlatforms() {
        assertNull(usableWebTitle("积分", "https://example.test/%E7%A7%AF%E5%88%86"))
        assertNull(usableWebTitle("%E7%A7%AF%E5%88%86", "https://example.test/%E7%A7%AF%E5%88%86"))
        assertEquals("真正的标题", usableWebTitle("真正的标题", "https://example.test/%E7%A7%AF%E5%88%86"))
    }

    @Test
    fun highRiskCapabilitiesRequireTrustedHttpsOrigin() {
        assertFailsWith<IllegalArgumentException> {
            WebViewSecurity(appBridgeEnabled = true)
        }
        assertFailsWith<IllegalArgumentException> {
            WebViewSecurity(
                trustedOrigins = WebViewTrustPolicy(listOf("http://unsafe.example")),
                fileChooserEnabled = true,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WebViewSecurity(
                trustedOrigins = WebViewTrustPolicy(listOf("https://safe.example")),
                appBridgeEnabled = true,
                pageBridgeEnabled = true,
            )
        }

        WebViewSecurity(
            trustedOrigins = WebViewTrustPolicy(listOf("https://safe.example")),
            appBridgeEnabled = true,
            fileChooserEnabled = true,
            mediaCaptureEnabled = true,
        )

        // 页面 Bridge 仅传递受业务白名单约束的低权限消息，可兼容历史 HTTP 协议页。
        WebViewSecurity(pageBridgeEnabled = true)
    }

    @Test
    fun contentValueEqualityCanDriveRecompositionDeduplication() {
        val first = WebViewContent.Url(
            url = "https://example.test/page",
            additionalHeaders = mapOf("X-Page" to "1"),
        )
        val same = first.copy()
        val changed = first.copy(additionalHeaders = mapOf("X-Page" to "2"))

        assertEquals(first, same)
        assertFalse(first == changed)
    }

    @Test
    fun settingsRejectInvalidTextZoomRange() {
        assertFailsWith<IllegalArgumentException> {
            WebViewSettings(minimumTextZoomPercent = 49)
        }
        assertFailsWith<IllegalArgumentException> {
            WebViewSettings(minimumTextZoomPercent = 200, maximumTextZoomPercent = 199)
        }
    }

    @Test
    fun baseSettingsKeepImagesAndProtocolCacheEnabled() {
        val settings = WebViewSettings()

        assertEquals(WebViewCachePolicy.DEFAULT, settings.cachePolicy)
        assertFalse(settings.acceptsThirdPartyCookies)
        assertTrue(settings.loadsImagesAutomatically)
        assertFalse(settings.blockNetworkImage)
        assertTrue(settings.useWideViewPort)
        assertTrue(settings.loadWithOverviewMode)
    }

    @Test
    fun earlyScriptsAreGuardedScheduledAndIdempotent() {
        val request = WebViewRequest(
            content = WebViewContent.Url("https://safe.example/page"),
            settings = WebViewSettings(javaScriptEnabled = true),
            security = WebViewSecurity(
                trustedOrigins = WebViewTrustPolicy(listOf("https://safe.example/page")),
            ),
            scripts = listOf(WebViewScript(id = "layout-fix", source = "window.fixed = true")),
        )

        val source = requireNotNull(request.earlyScriptSource())

        assertTrue("location.protocol === 'https:'" in source)
        assertTrue("=== 'safe.example'" in source)
        assertTrue("DOMContentLoaded" in source)
        assertTrue("__COMPOSE_WEBVIEW_NATIVE_SCRIPT_IDS__['layout-fix']" in source)
        assertEquals(setOf("https://safe.example", "https://safe.example."), request.earlyScriptOriginRules())
    }

    @Test
    fun unrestrictedEarlyScriptUsesWildcardWithoutTrustGate() {
        val request = WebViewRequest(
            content = WebViewContent.Url("https://public.example/page"),
            settings = WebViewSettings(javaScriptEnabled = true),
            scripts = listOf(
                WebViewScript(
                    id = "public-script",
                    source = "window.publicReady = true",
                    onlyForTrustedMainFrame = false,
                ),
            ),
        )

        val source = requireNotNull(request.earlyScriptSource())

        assertFalse("location.protocol === 'https:'" in source)
        assertEquals(setOf("*"), request.earlyScriptOriginRules())
    }

    @Test
    fun javascriptDisabledSuppressesDeclaredScriptsAndOversizedBridgeMessages() {
        val request = WebViewRequest(WebViewContent.Url("https://safe.example"), scripts = listOf(
            WebViewScript("early", "alert(1)", onlyForTrustedMainFrame = false),
            WebViewScript("finished", "alert(2)", WebViewScriptInjectionTime.DOCUMENT_FINISHED, false)))
        assertNull(request.earlyScriptSource())
        assertTrue(request.earlyScriptOriginRules().isEmpty())
        assertTrue(request.finishedScriptsAt("https://safe.example").isEmpty())
        assertNull(parseAppWebBridgeMessage("h\u001F" + "字".repeat(30000)))
        assertNull(parseAppWebBridgeMessage("h".repeat(81) + "\u001F{}"))
    }

    @Test
    fun scriptIdsRejectJavascriptSyntaxCharacters() {
        assertFailsWith<IllegalArgumentException> {
            WebViewScript(id = "layout'];alert(1)//", source = "window.fixed = true")
        }
    }

    @Test
    fun performanceMessagesOnlyAcceptKnownNonNegativeMetrics() {
        assertEquals(
            WebViewPerformanceMetric.DNS_LOOKUP to 12L,
            parseWebViewPerformanceMessage("perf:dns:12.8"),
        )
        assertEquals(
            WebViewPerformanceMetric.TCP_CONNECT to 23L,
            parseWebViewPerformanceMessage("perf:tcp:23"),
        )
        assertEquals(
            WebViewPerformanceMetric.TLS_HANDSHAKE to 34L,
            parseWebViewPerformanceMessage("perf:tls:34"),
        )
        assertEquals(
            WebViewPerformanceMetric.REQUEST to 45L,
            parseWebViewPerformanceMessage("perf:request:45"),
        )
        assertEquals(
            WebViewPerformanceMetric.RESPONSE to 56L,
            parseWebViewPerformanceMessage("perf:response:56"),
        )
        assertEquals(
            WebViewPerformanceMetric.FIRST_CONTENTFUL_PAINT to 123L,
            parseWebViewPerformanceMessage("perf:fcp:123.9"),
        )
        assertEquals(
            WebViewPerformanceMetric.TIME_TO_FIRST_BYTE to 45L,
            parseWebViewPerformanceMessage("perf:ttfb:45"),
        )
        assertNull(parseWebViewPerformanceMessage("perf:fcp:-1"))
        assertNull(parseWebViewPerformanceMessage("perf:request:NaN"))
        assertNull(parseWebViewPerformanceMessage("payload:fcp:12"))
    }

    @Test
    fun performanceScriptCollectsNonOverlappingNavigationPhases() {
        assertTrue("navigation.domainLookupStart" in WEB_VIEW_PERFORMANCE_SCRIPT)
        assertTrue("navigation.secureConnectionStart" in WEB_VIEW_PERFORMANCE_SCRIPT)
        assertTrue("duration(navigation.connectStart, secureConnectionStart)" in WEB_VIEW_PERFORMANCE_SCRIPT)
        assertTrue("duration(secureConnectionStart, navigation.connectEnd)" in WEB_VIEW_PERFORMANCE_SCRIPT)
        assertTrue("duration(navigation.requestStart, navigation.responseStart)" in WEB_VIEW_PERFORMANCE_SCRIPT)
        assertTrue("duration(navigation.responseStart, navigation.responseEnd)" in WEB_VIEW_PERFORMANCE_SCRIPT)
    }

    @Test
    fun urlRulesMatchWithoutLookalikeHostBypass() {
        assertTrue(WebViewUrlRule.Contains("web_report").matches("https://a.test/web_report?id=1"))
        assertTrue(WebViewUrlRule.ExactHost("shop.example").matches("https://shop.example/path"))
        assertFalse(WebViewUrlRule.ExactHost("shop.example").matches("https://sub.shop.example/path"))
        assertTrue(WebViewUrlRule.HostSuffix("shop.example").matches("https://sub.shop.example/path"))
        assertFalse(WebViewUrlRule.HostSuffix("shop.example").matches("https://shop.example.evil.test/path"))
    }

    @Test
    fun invisibleWebTitleDoesNotReplaceNativeTitle() {
        assertNull("\u200B\u2060\n".normalizedWebViewTitle())
        assertEquals("积分商场", "\u200B 积分商场 \uFEFF".normalizedWebViewTitle())
    }
}
