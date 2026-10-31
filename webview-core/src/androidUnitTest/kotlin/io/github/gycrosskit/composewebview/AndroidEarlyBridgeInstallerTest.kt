package io.github.gycrosskit.composewebview

import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [EarlyBridgeFeatures::class, EarlyBridgeProvider::class])
class AndroidEarlyBridgeInstallerTest {
    @Test fun `independent navigation installs early bridge only for trusted origin rules`() {
        EarlyBridgeProvider.scripts.clear()
        val request = WebViewRequest(WebViewContent.Url("https://other.test"),
            settings = WebViewSettings(javaScriptEnabled = true),
            security = WebViewSecurity(WebViewTrustPolicy(listOf("https://trusted.test:8443")), appBridgeEnabled = true),
            navigationPolicy = WebViewNavigationPolicy(navigationOrigins = WebViewNavigationOrigins(
                exactOrigins = setOf("https://other.test", "https://trusted.test:8443"))))
        val view = WebView(RuntimeEnvironment.getApplication())
        AndroidEarlyScriptInstaller { _, _, _ -> }.apply { requireDocumentToken = true }.install(view, request)
        val bridge = EarlyBridgeProvider.scripts.single { it.first.contains("ComposeWebViewBridgeTransport") }
        assertEquals(setOf("https://trusted.test:8443", "https://trusted.test.:8443"), bridge.second)
        assertTrue(bridge.first.contains("location.protocol === 'https:'"))
        assertTrue(bridge.first.contains("window.__GY_WEBVIEW_TOKEN_REQUIRED__=true;"))
        assertFalse(bridge.second.contains("https://other.test"))
    }
}

@Implements(value = WebViewFeature::class, isInAndroidSdk = false)
class EarlyBridgeFeatures {
    companion object {
        @JvmStatic @Implementation fun isFeatureSupported(feature: String): Boolean =
            feature == WebViewFeature.WEB_MESSAGE_LISTENER || feature == WebViewFeature.DOCUMENT_START_SCRIPT
    }
}

@Implements(value = WebViewCompat::class, isInAndroidSdk = false)
class EarlyBridgeProvider {
    companion object {
        val scripts = mutableListOf<Pair<String, Set<String>>>()
        @JvmStatic @Implementation fun addWebMessageListener(owner: WebView, name: String, rules: Set<String>, listener: WebViewCompat.WebMessageListener) = Unit
        @JvmStatic @Implementation fun addDocumentStartJavaScript(owner: WebView, script: String, rules: Set<String>): ScriptHandler {
            scripts += script to rules
            return ScriptHandler { }
        }
    }
}
