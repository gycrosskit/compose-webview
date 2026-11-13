package io.github.gycrosskit.composewebview.kuikly

import android.net.Uri
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import androidx.activity.ComponentActivity
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.gycrosskit.composewebview.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWebView

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [RecordingWebView::class, LegacyWebViewFeatures::class, BridgeWebViewCompat::class, RecordingSslErrorHandler::class, TestSslCertificate::class])
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
    fun `certificate compatibility is explicit and old owners still cancel`() {
        for (verify in listOf(true, false)) {
            val native = GYWebViewNative(RuntimeEnvironment.getApplication())
            val events = mutableListOf<Map<*, *>>()
            native.setProp("onEvent", { raw: Any? -> events += raw as Map<*, *> } as KuiklyRenderCallback)
            native.setProp("request", WebViewWire.encodeRequest(request.copy(settings = request.settings.copy(verifyServerCertificate = verify))))
            val owner = native.getChildAt(0) as WebView
            val client = Shadow.extract<RecordingWebView>(owner).webViewClient
            val handler = Shadow.newInstanceOf(android.webkit.SslErrorHandler::class.java)
            val recorded = Shadow.extract<RecordingSslErrorHandler>(handler)
            val error = android.net.http.SslError(android.net.http.SslError.SSL_UNTRUSTED,
                android.net.http.SslCertificate("test", "test", "2020", "2021"), "https://trusted.test/page")
            client.onReceivedSslError(owner, handler, error)
            assertEquals(if (verify) 0 else 1, recorded.proceeded)
            assertEquals(verify, recorded.cancelled > 0)
            assertEquals(verify, events.any { it["type"] == "loadFailed" && it["kind"] == "SSL" })
            native.onDestroy()
            val staleHandler = Shadow.newInstanceOf(android.webkit.SslErrorHandler::class.java)
            client.onReceivedSslError(owner, staleHandler, error)
            val stale = Shadow.extract<RecordingSslErrorHandler>(staleHandler)
            assertEquals(0, stale.proceeded)
            assertTrue(stale.cancelled > 0)
        }
    }

    @Test
    fun `unsupported app bridge reports once after load and leaves the document working`() {
        val native = GYWebViewNative(RuntimeEnvironment.getApplication())
        val events = mutableListOf<Map<*, *>>()
        native.setProp("onEvent", { raw: Any? -> events += raw as Map<*, *> } as KuiklyRenderCallback)
        val trusted = request.copy(security = request.security.copy(appBridgeEnabled = true))
        native.setProp("request", WebViewWire.encodeRequest(trusted))
        val owner = native.getChildAt(0) as WebView
        val client = Shadow.extract<RecordingWebView>(owner).webViewClient
        assertEquals("https://trusted.test/page", owner.url)
        assertTrue(events.isEmpty())
        client.onPageCommitVisible(owner, owner.url)
        client.onPageFinished(owner, owner.url)
        client.onPageStarted(owner, owner.url, null)
        client.onPageFinished(owner, owner.url)
        assertEquals(1, events.count { it["capability"] == "APP_BRIDGE" })
        assertTrue(events.none { it["type"] == "loadFailed" })
        assertTrue(events.any { it["type"] == "pageFinished" })
        native.setProp("request", WebViewWire.encodeRequest(trusted.copy(content = WebViewContent.Url("https://trusted.test/new"))))
        client.onPageFinished(owner, owner.url)
        assertEquals(1, events.count { it["capability"] == "APP_BRIDGE" })
        val replacement = native.getChildAt(0) as WebView
        Shadow.extract<RecordingWebView>(replacement).webViewClient.onPageFinished(replacement, replacement.url)
        assertEquals(2, events.count { it["capability"] == "APP_BRIDGE" })
        native.onDestroy()
    }

    @Test
    fun `untrusted disabled javascript and ordinary pages do not report app bridge unsupported`() {
        val bridgeSecurity = request.security.copy(appBridgeEnabled = true)
        val untrusted = request.copy(
            content = WebViewContent.Url("https://other.test/page"),
            security = bridgeSecurity,
            navigationPolicy = WebViewNavigationPolicy(
                navigationOrigins = WebViewNavigationOrigins(exactOrigins = setOf("https://other.test"))),
        )
        for (candidate in listOf(request, request.copy(settings = WebViewSettings(), security = bridgeSecurity), untrusted)) {
            val native = GYWebViewNative(RuntimeEnvironment.getApplication())
            val events = mutableListOf<Map<*, *>>()
            native.setProp("onEvent", { raw: Any? -> events += raw as Map<*, *> } as KuiklyRenderCallback)
            native.setProp("request", WebViewWire.encodeRequest(candidate))
            val owner = native.getChildAt(0) as WebView
            Shadow.extract<RecordingWebView>(owner).webViewClient.onPageFinished(owner, owner.url)
            assertTrue(events.none { it["capability"] == "APP_BRIDGE" })
            native.onDestroy()
        }
    }

    @Test
    fun `supported app bridge does not report unsupported`() {
        LegacyWebViewFeatures.appBridgeSupported = true
        val native = GYWebViewNative(RuntimeEnvironment.getApplication())
        try {
            val events = mutableListOf<Map<*, *>>()
            native.setProp("onEvent", { raw: Any? -> events += raw as Map<*, *> } as KuiklyRenderCallback)
            native.setProp("request", WebViewWire.encodeRequest(request.copy(security = request.security.copy(appBridgeEnabled = true))))
            val owner = native.getChildAt(0) as WebView
            Shadow.extract<RecordingWebView>(owner).webViewClient.onPageFinished(owner, owner.url)
            assertTrue(events.none { it["capability"] == "APP_BRIDGE" })
        } finally {
            native.onDestroy()
            LegacyWebViewFeatures.appBridgeSupported = false
        }
    }

    @Test
    fun `sensitive diagnostics latch covers first load redirects failures replacement and stale callbacks`() {
        val messages = mutableListOf<String>()
        AppWebViewRuntime.installLogSink { _, message, error -> assertNull(error); messages += message }
        val native = GYWebViewNative(RuntimeEnvironment.getApplication())
        try {
            val sensitive = request.copy(content = WebViewContent.Url("https://trusted.test/page?token=private"), sensitiveDiagnostics = true)
            native.setProp("request", WebViewWire.encodeRequest(sensitive))
            val owner = native.getChildAt(0) as WebView
            val client = Shadow.extract<RecordingWebView>(owner).webViewClient
            client.onPageStarted(owner, "https://trusted.test/redirect?token=private", null)
            client.onPageCommitVisible(owner, owner.url)
            WebViewDiagnostics.load(owner, WebViewContent.Html("private body", "https://trusted.test/?private"))
            WebViewDiagnostics.loadFailure(owner, WebViewLoadError(WebViewErrorKind.NETWORK, "private error", "https://trusted.test/?private"))
            WebViewDiagnostics.releaseFailure(owner, "stop", IllegalStateException("private exception"))
            native.setProp("request", WebViewWire.encodeRequest(request.copy(content = WebViewContent.Url("https://trusted.test/new?token=private"))))
            assertTrue(WebViewDiagnostics.isSensitive(native.getChildAt(0) as WebView))
            client.onPageFinished(owner, "https://trusted.test/stale?token=private")
            native.call("reload", null, null)
            native.onDestroy()
            assertTrue(messages.none { "trusted.test" in it || "private" in it })
            assertTrue(messages.any { "kind=NETWORK" in it })
        } finally {
            native.onDestroy()
            AppWebViewRuntime.installLogSink { _, _, _ -> }
        }
    }

    @Test
    fun `host permission explanation is one-shot and cancellation preserves a reentrant owner`() {
        val activityController = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val native = GYWebViewNative(activityController.get())
        val hostRequest = request.copy(
            security = request.security.copy(mediaCaptureEnabled = true),
            permissionPresentation = WebViewPermissionPresentation.HOST,
        )
        val events = mutableListOf<Map<*, *>>()
        var replaceOnCancel = false
        var hideOnRequest = false
        var replacement: WebView? = null
        val onEvent: KuiklyRenderCallback = { raw ->
            val event = raw as? Map<*, *> ?: error("Native event must be a map")
            events += event
            if (event["type"] == "permissionExplanationRequested" && hideOnRequest) {
                hideOnRequest = false
                native.setProp("visible", false)
            }
            if (event["type"] == "permissionExplanationCancelled" && replaceOnCancel) {
                replaceOnCancel = false
                native.setProp("request", WebViewWire.encodeRequest(hostRequest.copy(content = WebViewContent.Url("https://trusted.test/reentrant"))))
                replacement = native.getChildAt(native.childCount - 1) as WebView
            }
        }
        native.setProp("onEvent", onEvent)
        native.setProp("request", WebViewWire.encodeRequest(hostRequest))
        fun ask(): RecordingPermissionRequest {
            val owner = native.getChildAt(native.childCount - 1) as WebView
            val pending = RecordingPermissionRequest()
            Shadow.extract<RecordingWebView>(owner).webChromeClient.onPermissionRequest(pending)
            return pending
        }
        fun id(): String = events.last { it["type"] == "permissionExplanationRequested" }["requestId"] as String
        fun complete(requestId: String, accepted: String): Boolean? {
            var result: Boolean? = null
            native.call("completePermissionExplanation", "{\"requestId\":\"$requestId\",\"accepted\":$accepted}") { result = (it as? Map<*, *>)?.get("result") as? Boolean }
            return result
        }
        val declined = ask()
        val declinedId = id()
        assertEquals(listOf("CAMERA"), events.last()["permissions"])
        assertEquals(false, complete("stale", "true"))
        assertEquals(false, complete(declinedId, "\"true\""))
        assertEquals(true, complete(declinedId, "false"))
        assertEquals(1, declined.denials)
        assertEquals(false, complete(declinedId, "true"))
        assertTrue(events.none { it["type"] == "permissionExplanationCancelled" })

        val hidden = ask()
        val hiddenId = id()
        native.setProp("visible", false)
        assertTrue(events.any { it["type"] == "permissionExplanationCancelled" && it["requestId"] == hiddenId })
        assertEquals(1, hidden.denials)
        assertEquals(false, complete(hiddenId, "true"))
        native.setProp("visible", true)
        hideOnRequest = true
        val synchronouslyHidden = ask()
        val synchronousId = id()
        assertEquals(1, synchronouslyHidden.denials)
        assertTrue(events.any { it["type"] == "permissionExplanationCancelled" && it["requestId"] == synchronousId })
        assertEquals(false, complete(synchronousId, "true"))
        native.setProp("visible", true)

        ask()
        val replacedId = id()
        replaceOnCancel = true
        native.setProp("request", WebViewWire.encodeRequest(hostRequest.copy(content = WebViewContent.Url("https://trusted.test/obsolete"))))
        assertSame(replacement, native.getChildAt(0))
        assertEquals(1, native.childCount)
        assertEquals(false, complete(replacedId, "true"))
        val confirmed = ask()
        val confirmedId = id()
        assertEquals(true, complete(confirmedId, "true"))
        assertEquals(false, complete(confirmedId, "true"))
        assertEquals(0, confirmed.grants) // 用途确认不等同于系统授权。
        native.onDestroy()
        assertEquals(false, complete(confirmedId, "true"))
        activityController.pause().stop().destroy()
    }

    @Test
    fun `early bridge queue is discarded on hide and stop until a new document starts`() {
        val native = GYWebViewNative(RuntimeEnvironment.getApplication())
        val trusted = request.copy(security = WebViewSecurity(
            trustedOrigins = WebViewTrustPolicy(listOf("https://trusted.test")), appBridgeEnabled = true))
        native.setProp("request", WebViewWire.encodeRequest(trusted))
        val owner = native.getChildAt(0) as WebView
        val shadow = Shadow.extract<RecordingWebView>(owner)
        val client = shadow.webViewClient
        client.onPageStarted(owner, owner.url, null)
        client.onPageCommitVisible(owner, owner.url)
        assertTrue(shadow.scripts.last { it.contains("__GY_WEBVIEW_BRIDGE_TOKEN_READY__") }.contains("__GY_WEBVIEW_BRIDGE_TOKEN_READY__(true)"))
        native.setProp("visible", false)
        native.setProp("visible", true)
        assertTrue(shadow.scripts.last().contains("__GY_WEBVIEW_BRIDGE_TOKEN_READY__(false)"))
        client.onPageStarted(owner, owner.url, null)
        native.call("stopLoading", null, null)
        client.onPageCommitVisible(owner, owner.url)
        assertTrue(shadow.scripts.last { it.contains("__GY_WEBVIEW_BRIDGE_TOKEN_READY__") }.contains("__GY_WEBVIEW_BRIDGE_TOKEN_READY__(false)"))
        client.onPageStarted(owner, owner.url, null)
        client.onPageCommitVisible(owner, owner.url)
        assertTrue(shadow.scripts.last { it.contains("__GY_WEBVIEW_BRIDGE_TOKEN_READY__") }.contains("__GY_WEBVIEW_BRIDGE_TOKEN_READY__(true)"))
        native.onDestroy()
    }

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
    fun `explicit owner retains document token initialization and JS callbacks while hidden`() {
        val native = GYWebViewNative(RuntimeEnvironment.getApplication())
        native.setProp("pageOwnerActive", true)
        native.setProp("request", WebViewWire.encodeRequest(request.copy(
            security = request.security.copy(appBridgeEnabled = true))))
        val owner = native.getChildAt(0) as WebView
        val shadow = Shadow.extract<RecordingWebView>(owner)
        val client = shadow.webViewClient
        client.onPageStarted(owner, owner.url, null)
        client.onPageCommitVisible(owner, owner.url)
        val beforeHide = shadow.scripts.last { it.contains("__GY_WEBVIEW_BRIDGE_TOKEN_READY__") }
        var delivered = 0
        native.call("evaluateJavascript", "{\"script\":\"1+1\"}") { delivered++ }
        val pending = shadow.lastEvaluatedJavascriptCallback
        native.setProp("visible", false)
        assertFalse(shadow.wasOnPauseCalled())
        pending.onReceiveValue("2")
        assertEquals(1, delivered)
        client.onPageFinished(owner, owner.url)
        assertEquals(beforeHide, shadow.scripts.last { it.contains("__GY_WEBVIEW_BRIDGE_TOKEN_READY__") })
        assertEquals(1, shadow.scripts.count { it == finished })
        native.call("evaluateJavascript", "{\"script\":\"backgroundData()\"}") { delivered++ }
        val revoked = shadow.lastEvaluatedJavascriptCallback
        native.setProp("pageOwnerActive", false)
        revoked.onReceiveValue("stale")
        assertEquals(1, delivered)
        assertTrue(shadow.wasOnPauseCalled())
        assertTrue(shadow.scripts.last().contains("__GY_WEBVIEW_BRIDGE_TOKEN_READY__(false)"))
        native.setProp("visible", true)
        assertTrue(shadow.scripts.last().contains("__GY_WEBVIEW_BRIDGE_TOKEN_READY__(false)"))
        assertEquals(0, shadow.reloadInvocations)
        native.onDestroy()
    }

    @Test
    fun `native geolocation system permission survives hidden owner and revoked owner rejects late result`() {
        for (revoke in listOf(false, true)) {
            val activityController = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            val activity = activityController.get()
            org.robolectric.Shadows.shadowOf(activity.application).denyPermissions(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            val native = GYWebViewNative(activity)
            val events = mutableListOf<Map<*, *>>()
            native.setProp("onEvent", { raw: Any? -> events += raw as Map<*, *> } as KuiklyRenderCallback)
            native.setProp("pageOwnerActive", true)
            native.setProp("request", WebViewWire.encodeRequest(request.copy(
                security = request.security.copy(geolocationEnabled = true),
                permissionPresentation = WebViewPermissionPresentation.HOST)))
            val owner = native.getChildAt(0) as WebView
            val chrome = Shadow.extract<RecordingWebView>(owner).webChromeClient
            val results = mutableListOf<Boolean>()
            chrome.onGeolocationPermissionsShowPrompt("https://trusted.test") { _, allowed, retain ->
                assertFalse(retain)
                results += allowed
            }
            events.lastOrNull { it["type"] == "permissionExplanationRequested" }?.let { explanation ->
                native.call("completePermissionExplanation", "{\"requestId\":\"${explanation["requestId"]}\",\"accepted\":true}", null)
            }
            val permission = requireNotNull(org.robolectric.Shadows.shadowOf(activity).lastRequestedPermission)
            native.setProp("visible", false)
            assertTrue(results.isEmpty())
            if (revoke) native.setProp("pageOwnerActive", false)
            org.robolectric.Shadows.shadowOf(activity.application).grantPermissions(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            activity.activityResultRegistry.dispatchResult(permission.requestCode, mapOf(android.Manifest.permission.ACCESS_COARSE_LOCATION to true))
            assertEquals(listOf(!revoke), results)
            chrome.onGeolocationPermissionsShowPrompt("https://trusted.test") { _, allowed, _ -> results += allowed }
            assertEquals(listOf(!revoke, false), results) // Hidden owner cannot begin a new permission request.
            native.onDestroy()
            activityController.pause().stop().destroy()
        }
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
        var appBridgeSupported = false
        @JvmStatic
        @Implementation
        fun isFeatureSupported(feature: String): Boolean = appBridgeSupported && feature == WebViewFeature.WEB_MESSAGE_LISTENER
    }
}

private class RecordingPermissionRequest : PermissionRequest() {
    var grants = 0
    var denials = 0
    override fun getOrigin(): Uri = Uri.parse("https://trusted.test")
    override fun getResources(): Array<String> = arrayOf(RESOURCE_VIDEO_CAPTURE)
    override fun grant(resources: Array<String>) { grants++ }
    override fun deny() { denials++ }
}

/** 仅替代系统消息传输注册；能力开关和 Native 加载、事件逻辑仍走生产代码。 */
@Implements(value = WebViewCompat::class, isInAndroidSdk = false)
class BridgeWebViewCompat {
    companion object {
        @JvmStatic @Implementation
        fun addWebMessageListener(owner: WebView, name: String, rules: Set<String>, listener: WebViewCompat.WebMessageListener) = Unit
        @JvmStatic @Implementation
        fun removeWebMessageListener(owner: WebView, name: String) = Unit
    }
}

@Implements(android.webkit.SslErrorHandler::class)
class RecordingSslErrorHandler {
    var proceeded = 0
    var cancelled = 0
    @Implementation fun proceed() { proceeded++ }
    @Implementation fun cancel() { cancelled++ }
}

// 回调策略测试不读取证书内容，跳过 API 28 沙盒缺失的内部证书解析依赖。
@Implements(android.net.http.SslCertificate::class)
class TestSslCertificate {
    @Implementation fun __constructor__(issuedTo: String, issuedBy: String, validNotBefore: java.util.Date?, validNotAfter: java.util.Date?, x509Certificate: java.security.cert.X509Certificate?) = Unit
}
