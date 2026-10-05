package io.github.gycrosskit.composewebview.kuikly

import android.webkit.ValueCallback
import android.webkit.WebView
import androidx.webkit.WebViewFeature
import io.github.gycrosskit.composewebview.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWebView

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [RecordingWebView::class, LegacyWebViewFeatures::class])
class GYWebViewNativeTest {
    private val finished = "window.finished=(window.finished||0)+1;"
    private val request = WebViewRequest(
        content = WebViewContent.Url("https://trusted.test/page"),
        settings = WebViewSettings(javaScriptEnabled = true),
        security = WebViewSecurity(trustedOrigins = WebViewTrustPolicy(listOf("https://trusted.test"))),
        scripts = listOf(
            WebViewScript("ready", "window.ready=true;"),
            WebViewScript("finished", finished, WebViewScriptInjectionTime.DOCUMENT_FINISHED),
        ),
    )

    @Test
    fun `hidden document initializes once and resumes without reload`() {
        val native = GYWebViewNative(RuntimeEnvironment.getApplication())
        native.setProp("visible", false)
        native.setProp("request", WebViewWire.encodeRequest(request))
        val owner = native.getChildAt(0) as WebView
        val shadow = Shadow.extract<RecordingWebView>(owner)
        val client = shadow.webViewClient
        client.onPageStarted(owner, owner.url, null)
        client.onPageCommitVisible(owner, owner.url)
        assertTrue(shadow.scripts.any { it.contains("window.ready=true;") })
        client.onPageFinished(owner, owner.url)
        client.onPageFinished(owner, owner.url)
        assertEquals(1, shadow.scripts.count { it == finished })
        val beforeResume = shadow.scripts.size
        native.call("evaluateJavascript", "{\"script\":\"hiddenBusinessCall()\"}", null)
        assertEquals(beforeResume, shadow.scripts.size)
        native.setProp("visible", true)
        assertEquals(beforeResume, shadow.scripts.size)
        assertEquals(0, shadow.reloadInvocations)
        assertTrue(shadow.wasOnResumeCalled())
        owner.loadUrl("https://trusted.test/next")
        client.onPageStarted(owner, owner.url, null)
        client.onPageFinished(owner, owner.url)
        assertEquals(2, shadow.scripts.count { it == finished })
        var delivered = 0
        native.call("evaluateJavascript", "{\"script\":\"1+1\"}") { delivered++ }
        val pending = shadow.lastEvaluatedJavascriptCallback
        native.setProp("visible", false)
        native.setProp("visible", true)
        pending.onReceiveValue("2")
        assertEquals(0, delivered)
        native.onDestroy()
    }

    @Test
    fun `replaced and destroyed owners cannot initialize scripts`() {
        val native = GYWebViewNative(RuntimeEnvironment.getApplication())
        native.setProp("request", WebViewWire.encodeRequest(request))
        val old = native.getChildAt(0) as WebView
        val oldShadow = Shadow.extract<RecordingWebView>(old)
        val oldClient = oldShadow.webViewClient
        native.setProp("request", WebViewWire.encodeRequest(request.copy(content = WebViewContent.Url("https://trusted.test/new"))))
        oldClient.onPageCommitVisible(old, old.url)
        oldClient.onPageFinished(old, old.url)
        assertTrue(oldShadow.scripts.isEmpty())
        val current = native.getChildAt(0) as WebView
        val currentShadow = Shadow.extract<RecordingWebView>(current)
        val currentClient = currentShadow.webViewClient
        native.onDestroy()
        currentClient.onPageFinished(current, current.url)
        assertTrue(currentShadow.scripts.isEmpty())
    }

    @Test
    fun `disabled javascript and untrusted document retain injection gates`() {
        val native = GYWebViewNative(RuntimeEnvironment.getApplication())
        native.setProp("visible", false)
        native.setProp("request", WebViewWire.encodeRequest(request.copy(settings = WebViewSettings())))
        var owner = native.getChildAt(0) as WebView
        var shadow = Shadow.extract<RecordingWebView>(owner)
        shadow.webViewClient.onPageFinished(owner, owner.url)
        assertTrue(shadow.scripts.isEmpty())
        native.setProp("request", WebViewWire.encodeRequest(request))
        owner = native.getChildAt(0) as WebView
        shadow = Shadow.extract(owner)
        owner.loadUrl("https://untrusted.test/page")
        shadow.webViewClient.onPageStarted(owner, owner.url, null)
        shadow.webViewClient.onPageFinished(owner, owner.url)
        assertFalse(shadow.scripts.contains(finished))
        assertTrue(shadow.scripts.any { it.contains("location.protocol") })
        native.onDestroy()
    }
}

/** 替身仅记录系统 WebView 调用；测试执行生产 Native View 和真实 Client。 */
@Implements(WebView::class)
class RecordingWebView : ShadowWebView() {
    val scripts = mutableListOf<String>()
    @Implementation
    override fun evaluateJavascript(script: String, callback: ValueCallback<String>?) {
        scripts += script
        super.evaluateJavascript(script, callback)
    }
}

/** 强制旧内核路径，让回归覆盖 document-start 不可用时的生产兜底。 */
@Implements(value = WebViewFeature::class, isInAndroidSdk = false)
class LegacyWebViewFeatures {
    companion object {
        @JvmStatic
        @Implementation
        fun isFeatureSupported(feature: String): Boolean = false
    }
}
