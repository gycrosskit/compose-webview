package io.github.gycrosskit.composewebview

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/** 导出真实生产 generator 的脚本，供 verification/early-scripts 的 Node 行为回归执行。 */
class EarlyScriptsExportTest {
    @Test fun exportProductionScripts() {
        val output = File(checkNotNull(System.getProperty("webview.earlyScriptsOutput")))
        check(!output.exists() || output.deleteRecursively())
        check(output.mkdirs())
        val settings = WebViewSettings(
            javaScriptEnabled = true,
            mixedContentPolicy = WebViewMixedContentPolicy.NEVER_ALLOW,
        )
        val trust = WebViewTrustPolicy(trustedHostSuffixes = setOf("trusted.test"))
        assertTrue(trust.isTrusted("https://sub.trusted.test:65535/path"))
        assertFalse(trust.isTrusted("https://trusted.test.."))
        val request = WebViewRequest(
            content = WebViewContent.Url("https://trusted.test"),
            settings = settings,
            security = WebViewSecurity(trustedOrigins = trust),
            scripts = listOf(WebViewScript(
                "port-guard", "window.hits = (window.hits || 0) + 1;",
                WebViewScriptInjectionTime.DOCUMENT_START, true,
            )),
        )
        assertEquals(setOf("*"), request.earlyScriptOriginRules())
        output.resolve("production.js").writeText(checkNotNull(request.earlyScriptSource()))

        val page = request.copy(
            content = WebViewContent.Url("http://legacy.test/captcha"),
            security = WebViewSecurity(pageBridgeEnabled = true),
        )
        assertEquals(setOf("http://legacy.test:80", "http://legacy.test.:80"), page.earlyScriptOriginRules())
        output.resolve("page.js").writeText(checkNotNull(page.earlyScriptSource()))

        val channels = WebViewRequest(
            content = page.content,
            settings = settings,
            pageMessageChannels = setOf("earlyChannel"),
        )
        output.resolve("channels.js").writeText(checkNotNull(channels.pageMessageScript("document-A")))
    }
}
