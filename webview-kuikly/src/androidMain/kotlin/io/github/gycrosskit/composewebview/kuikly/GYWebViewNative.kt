package io.github.gycrosskit.composewebview.kuikly

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Message
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.webkit.WebViewFeature
import androidx.lifecycle.Lifecycle
import com.tencent.kuikly.core.render.android.export.IKuiklyRenderViewExport
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import com.tencent.kuikly.core.render.android.IKuiklyRenderExport
import io.github.gycrosskit.composewebview.*
import org.json.JSONObject
import java.util.UUID

/** Kuikly 原生生命周期持有系统 WebView，完全不依赖 Compose 状态或运行时。 */
class GYWebViewNative @JvmOverloads constructor(
    context: Context,
    private val fullscreenOrientation: Int? = null,
    private val fullscreenControlsFactory: ((Context, AndroidWebFullscreenActions) -> AndroidWebFullscreenControls)? = null,
    private val filePicker: io.github.gycrosskit.composewebview.AndroidWebFilePicker? = null,
) : FrameLayout(context), IKuiklyRenderViewExport {
    @Volatile private var request: WebViewRequest? = null
    private var onEvent: KuiklyRenderCallback? = null
    private var webView: WebView? = null
    private var sensitiveDiagnostics = false
    private var destroyed = false
    private var crashed = false
    private var initialHtmlNavigation = false
    private var callbackGeneration = 0L
    private var requestGeneration = 0L
    private var documentToken = UUID.randomUUID().toString()
    private var discardEarlyBridgeMessages = false
    private var pageVisible = true
    private var pageOwnerActive: Boolean? = null
    private val ownerActive: Boolean get() = pageOwnerActive ?: pageVisible
    private val canPresentUi: Boolean get() = ownerActive && pageVisible
    private var appBridgeUnsupportedReported = false
    private var finishedScriptsInjected = false
    private var fullscreenHost: AndroidWebFullscreenHost? = null
    private var capabilities: AndroidWebCapabilities? = null
    private class PendingPermissionExplanation(val requestId: String, val owner: WebView?, val generation: Long, val complete: (Boolean) -> Unit) {
        var resolved = false
    }
    private var permissionExplanation: PendingPermissionExplanation? = null
    private var popupRouter: AndroidPopupRouter? = null
    private var pageMessageChannels: AndroidPageMessageChannels? = null
    private val earlyScripts = AndroidEarlyScriptInstaller { owner, metric, duration ->
        if (owner === webView) emit(WebViewEvent.PerformanceMetric(metric, duration))
    }.apply { requireDocumentToken = true }

    override fun setProp(propKey: String, propValue: Any): Boolean = when (propKey) {
        "request" -> {
            try {
                val decoded = WebViewWire.decodeRequest(propValue as String)
                sensitiveDiagnostics = sensitiveDiagnostics || decoded.sensitiveDiagnostics
                val next = decoded.copy(sensitiveDiagnostics = sensitiveDiagnostics)
                if (sensitiveDiagnostics) {
                    webView?.let(WebViewDiagnostics::protectSensitiveDiagnostics)
                    popupRouter?.protectSensitiveDiagnostics()
                }
                if (next != request && !destroyed) {
                    val previous = request
                    if (webView != null && !crashed && previous?.copy(navigationPolicy = next.navigationPolicy) == next) {
                        request = next
                    } else {
                        val generation = ++requestGeneration
                        releaseWebView()
                        if (!destroyed && generation == requestGeneration) {
                            request = next
                            createWebView(next)
                        }
                    }
                }
            } catch (error: Exception) {
                releaseWebView()
                request = null
                emit(WebViewEvent.LoadFailed(WebViewLoadError(WebViewErrorKind.LOAD_EXCEPTION, error.message.orEmpty())))
            }
            true
        }
        "pageOwnerActive" -> {
            val wasActive = ownerActive
            pageOwnerActive = propValue as? Boolean ?: false
            updatePageState(wasActive)
            true
        }
        "visible" -> {
            val wasActive = ownerActive
            pageVisible = propValue as? Boolean ?: false
            updatePageState(wasActive)
            true
        }
        "onEvent" -> { onEvent = propValue as? KuiklyRenderCallback; true }
        else -> super<IKuiklyRenderViewExport>.setProp(propKey, propValue)
    }

    private fun updatePageState(wasActive: Boolean) {
        if (wasActive != ownerActive) {
            if (!ownerActive) discardEarlyBridgeMessages = true
            documentToken = UUID.randomUUID().toString()
            val owner = webView
            if (request?.settings?.javaScriptEnabled == true && request?.canUseAppBridgeAt(owner?.url) == true) {
                owner?.evaluateJavascript(bridgeTokenScript(ownerActive && !discardEarlyBridgeMessages), null)
            }
        }
        if (!ownerActive) {
            callbackGeneration++
            pageMessageChannels?.revoke()
        }
        if (!canPresentUi) {
            val oldPopupRouter = popupRouter
            val oldFullscreenHost = fullscreenHost
            webView?.let { owner ->
                val activity = context.findComponentActivity()
                val oldCapabilities = capabilities
                if (pageOwnerActive == null) oldCapabilities?.cancelGeolocation(owner)
                if (pageOwnerActive == false) oldCapabilities?.release(owner)
                else if (activity != null && oldCapabilities?.pauseForSystemPicker(owner) == true) {
                    // 旧调用方未声明归属时，仍按原有 Activity 时序识别真正离页。
                    if (pageOwnerActive == null) Handler(Looper.getMainLooper()).post {
                        if (owner === webView && !pageVisible && pageOwnerActive == null && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) capabilities?.pause(owner)
                    }
                } else oldCapabilities?.pause(owner)
            }
            oldPopupRouter?.release()
            oldFullscreenHost?.hide()
        } else webView?.let { capabilities?.resume(it) }
        visibility = if (canPresentUi) View.VISIBLE else View.INVISIBLE
        if (ownerActive) webView?.onResume() else webView?.onPause()
    }

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        val owner = webView?.takeUnless { destroyed || crashed }
        val result: Any? = when (method) {
            "reload" -> {
                owner?.let { capabilities?.cancelGeolocation(it) }
                if (crashed || request?.pageMessageChannels?.isNotEmpty() == true) { val current = request; releaseWebView(); if (current != null) createWebView(current) }
                else owner?.reload()
                owner != null || webView != null
            }
            "goBack" -> if (exitFullscreen()) true else owner?.let { if (it.canGoBack()) { pageMessageChannels?.revoke(); capabilities?.cancelGeolocation(it); it.goBack(); true } else false } ?: false
            "goForward" -> owner?.let { if (it.canGoForward()) { pageMessageChannels?.revoke(); capabilities?.cancelGeolocation(it); it.goForward(); true } else false } ?: false
            "stopLoading" -> {
                discardEarlyBridgeMessages = true
                callbackGeneration++
                pageMessageChannels?.revoke()
                // 先停止旧加载；撤销回执可能同步创建新 owner，之后不能再 stop。
                owner?.stopLoading()
                popupRouter?.release()
                owner?.let { capabilities?.release(it) }
                owner != null
            }
            "exitFullscreen" -> exitFullscreen()
            "completePermissionExplanation" -> {
                val value = runCatching { JSONObject(params ?: "{}") }.getOrNull()
                val requestId = value?.opt("requestId") as? String
                val accepted = value?.opt("accepted") as? Boolean
                val pending = permissionExplanation
                if (!destroyed && !crashed && canPresentUi && pending != null && pending.owner === webView &&
                    pending.generation == callbackGeneration && requestId == pending.requestId && accepted != null) {
                    permissionExplanation = null
                    pending.resolved = true
                    pending.complete(accepted)
                    true
                } else false
            }
            "evaluateJavascript" -> {
                val script = runCatching { JSONObject(params ?: "{}").getString("script") }.getOrNull()
                if (owner != null && script != null && ownerActive && request?.canEvaluateJavascriptAt(owner.url) == true) {
                    val generation = callbackGeneration
                    owner.evaluateJavascript(script) { value ->
                        if (owner === webView && !destroyed && !crashed && ownerActive && generation == callbackGeneration) callback?.invoke(mapOf("result" to value))
                    }
                } else if (!destroyed) callback?.invoke(mapOf("result" to null))
                return null
            }
            "replyPageMessage" -> {
                val reply = runCatching { JSONObject(params ?: "{}") }.getOrNull()
                val replyId = reply?.opt("replyId") as? String
                val data = reply?.opt("data") as? String
                if (replyId != null && data != null) pageMessageChannels?.reply(replyId, data) == true else false
            }
            else -> return super<IKuiklyRenderViewExport>.call(method, params, callback)
        }
        callback?.invoke(mapOf("result" to result))
        return null
    }

    private fun createWebView(current: WebViewRequest) {
        if (current.content.isBlankWebViewContent()) {
            emit(WebViewEvent.LoadFailed(WebViewErrorHelper.fromEmptyContent()))
            return
        }
        val origin = current.content.initialOrigin()
        if (origin != null && !current.allowsNavigation(WebViewNavigationRequest(origin, true, false))) {
            emit(WebViewEvent.Navigation(WebViewNavigationRequest(origin, true, false), true))
            return
        }
        crashed = false
        appBridgeUnsupportedReported = false
        val owner = WebView(context)
        webView = owner
        WebViewDiagnostics.created(owner, "kuikly", sensitiveDiagnostics = sensitiveDiagnostics)
        val hostActivity = context.findComponentActivity()
        capabilities = hostActivity?.let {
            AndroidWebCapabilities(it, { requireNotNull(request) }, { target -> target === webView && canPresentUi && !destroyed && !crashed },
                { emit(WebViewEvent.PermissionSettingsRequired(it)) },
                if (current.permissionPresentation == WebViewPermissionPresentation.HOST) ::showPermissionExplanation else null, filePicker,
                { target -> target === webView && ownerActive && !destroyed && !crashed })
        }
        popupRouter = AndroidPopupRouter({ requireNotNull(request) }, { emit(WebViewEvent.Navigation(it, true)) }) { navigation ->
            if (route(navigation)) WebViewNavigationDecision.BLOCK else WebViewNavigationDecision.ALLOW
        }
        owner.applyWebViewConfig(current.settings, owner.settings.userAgentString, resources.configuration.fontScale,
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES, Color.TRANSPARENT)
        owner.settings.setGeolocationEnabled(current.security.geolocationEnabled)
        owner.webViewClient = object : AppWebViewClient(object : AppWebViewClient.Listener {
            override fun onPageLoadStarted(url: String?) { if (owner !== webView || destroyed) return;
                pageMessageChannels?.onPageStarted(url)
                exitFullscreen()
                initialHtmlNavigation = false
                finishedScriptsInjected = false
                discardEarlyBridgeMessages = !ownerActive
                callbackGeneration++; documentToken = UUID.randomUUID().toString()
                val oldCapabilities = capabilities
                val oldPopupRouter = popupRouter
                oldCapabilities?.release(owner)
                oldPopupRouter?.release()
                if (owner !== webView || destroyed) return
                emit(WebViewEvent.PageStarted(url)); history(owner) }
            override fun onPageLoadFinished(url: String?) { if (owner !== webView || destroyed) return; emit(WebViewEvent.PageFinished(url)); history(owner) }
            override fun onPageCommitVisible(url: String?) { if (owner !== webView || destroyed) return; emit(WebViewEvent.FirstContentVisible(url)) }
            override fun onPageLoadFailed(error: WebViewLoadError) {
                if (owner !== webView || destroyed) return
                pageMessageChannels?.revoke()
                if (error.kind == WebViewErrorKind.RENDER_PROCESS) {
                    crashed = true
                    WebViewDiagnostics.markReleased(owner, true)
                    capabilities?.release(owner)
                }
                emit(WebViewEvent.LoadFailed(error))
            }
        }, isOwner = { it === owner && owner === webView && !destroyed }) {
            override val verifyServerCertificate: Boolean
                get() = request?.settings?.verifyServerCertificate != false
            override fun shouldOverrideUrlLoading(view: WebView, navigation: WebResourceRequest): Boolean =
                if (view !== owner || owner !== webView || destroyed) true else route(WebViewNavigationRequest(navigation.url.toString(), navigation.isForMainFrame, navigation.hasGesture()))
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = if (view !== owner || owner !== webView || destroyed) true else route(WebViewNavigationRequest(url, true, false))
            private val mainHandler = Handler(Looper.getMainLooper())
            override fun shouldInterceptRequest(view: WebView, resource: WebResourceRequest): WebResourceResponse? =
                request?.interceptAndroidRequest(resource) { navigation ->
                    mainHandler.post {
                        if (view === owner && owner === webView && !destroyed && !crashed) emit(WebViewEvent.Navigation(navigation, true))
                    }
                }
            override fun onPageCommitVisible(view: WebView, url: String?) { super.onPageCommitVisible(view, url); inject(view, false) }
            override fun onPageFinished(view: WebView, url: String?) { super.onPageFinished(view, url); inject(view, true) }
        }
        owner.webChromeClient = object : AppWebChromeClient(object : AppWebChromeClient.Listener {
            override fun onProgressChanged(progress: Int) { if (owner === webView) emit(WebViewEvent.ProgressChanged(progress)) }
            override fun onReceivedTitle(title: String?, url: String?) { if (owner === webView) emit(WebViewEvent.TitleChanged(title)) }
        }, isOwner = { it === owner && owner === webView && !destroyed }) {
            override fun onShowFileChooser(view: WebView?, callback: android.webkit.ValueCallback<Array<android.net.Uri>>?, params: FileChooserParams?): Boolean {
                if (view !== owner || owner !== webView || destroyed || !canPresentUi) { callback?.onReceiveValue(null); return true }
                val current = capabilities
                if (current != null) return current.showFileChooser(view, callback, params)
                callback?.onReceiveValue(null)
                return true
            }
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: android.webkit.GeolocationPermissions.Callback?) {
                if (owner !== webView || destroyed || !canPresentUi) callback?.invoke(origin.orEmpty(), false, false)
                else capabilities?.requestGeolocation(owner, origin, callback) ?: callback?.invoke(origin.orEmpty(), false, false)
            }
            override fun onGeolocationPermissionsHidePrompt() { if (owner === webView && !destroyed) capabilities?.cancelGeolocation(owner) }
            override fun onPermissionRequest(value: PermissionRequest?) {
                if (owner !== webView || destroyed || !canPresentUi) value?.deny()
                else capabilities?.requestMedia(owner, value) ?: value?.deny()
            }
            override fun onPermissionRequestCanceled(value: PermissionRequest?) { if (owner === webView && !destroyed) capabilities?.cancelMedia(value) }
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean =
                view === owner && owner === webView && canPresentUi && !destroyed && (popupRouter?.createWindow(view, isUserGesture, resultMsg) ?: false)
            override fun onCloseWindow(window: WebView) { popupRouter?.close(window) }
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null || callback == null) return
                if (owner !== webView || destroyed) { callback.onCustomViewHidden(); return }
                val generation = callbackGeneration
                val host = fullscreenHost ?: activity?.let { target ->
                    AndroidWebFullscreenHost(target, fullscreenOrientation, fullscreenControlsFactory) {
                        if (owner === webView && !destroyed) emit(WebViewEvent.FullscreenChanged(it))
                    }.also { fullscreenHost = it }
                }
                if (host == null) callback.onCustomViewHidden()
                else host.show(owner, view, callback) { owner === webView && canPresentUi && !destroyed && !crashed && generation == callbackGeneration && WebViewDiagnostics.isActive(owner) }
            }
            override fun onHideCustomView() { if (owner === webView && !destroyed) exitFullscreen() }
        }
        earlyScripts.install(owner, current)
        if (current.pageMessageChannels.isNotEmpty()) {
            pageMessageChannels = AndroidPageMessageChannels(
                owner, { requireNotNull(request) }, { owner === webView && ownerActive && !destroyed && !crashed }, ::emit,
            )
            if (!ownerActive) pageMessageChannels?.revoke()
        }
        if (current.security.appBridgeEnabled || current.security.pageBridgeEnabled) owner.installAppWebBridge({ requireNotNull(request) }, { documentToken }) { handler, data ->
            if (owner === webView && ownerActive && !destroyed && !crashed) emit(WebViewEvent.BridgeMessage(WebViewBridgeMessage(handler, data)))
        }
        addView(owner, LayoutParams(-1, -1))
        if (!ownerActive) owner.onPause()
        when (val content = current.content) {
            is WebViewContent.Url -> owner.loadUrl(content.url, content.additionalHeaders)
            is WebViewContent.Html -> {
                initialHtmlNavigation = true
                owner.loadDataWithBaseURL(content.baseUrl, content.html, content.mimeType, content.encoding, content.historyUrl)
            }
        }
    }

    private fun route(navigation: WebViewNavigationRequest): Boolean {
        val internalHtmlLoad = initialHtmlNavigation && navigation.isInternalHtmlInitialNavigation()
        if (navigation.isMainFrame) initialHtmlNavigation = false
        val blocked = (!internalHtmlLoad && request?.allowsNavigation(navigation) != true) || destroyed || crashed || pageOwnerActive == false
        if (!blocked && navigation.isMainFrame && !internalHtmlLoad) {
            val owner = webView
            val oldCapabilities = capabilities
            pageMessageChannels?.revoke()
            if (owner != null) oldCapabilities?.release(owner)
        }
        emit(WebViewEvent.Navigation(navigation, blocked))
        return blocked
    }

    private fun inject(owner: WebView, finished: Boolean) {
        val current = request ?: return
        if (owner !== webView || destroyed || crashed || pageOwnerActive == false || !current.settings.javaScriptEnabled) return
        // 当前文档可在后台初始化；只有撤销归属才丢弃旧业务消息。
        if (current.canUseAppBridgeAt(owner.url)) {
            owner.evaluateJavascript(bridgeTokenScript(ownerActive && !discardEarlyBridgeMessages), null)
            owner.injectAppWebBridge()
        }
        owner.evaluateJavascript(WEB_VIEW_PERFORMANCE_SCRIPT, null)
        current.earlyScriptSource()?.let { owner.evaluateJavascript(it, null) }
        if (finished && !finishedScriptsInjected) {
            finishedScriptsInjected = true
            current.finishedScriptsAt(owner.url).forEach { owner.evaluateJavascript(it.source, null) }
        }
        // 在文档回调中报告，避免创建节点时宿主尚未绑定 controller；不阻断网页或降级来源校验。
        if (!appBridgeUnsupportedReported && current.canUseAppBridgeAt(owner.url) &&
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            appBridgeUnsupportedReported = true
            emit(WebViewEvent.CapabilityUnsupported(WebViewCapability.APP_BRIDGE))
        }
    }

    private fun history(owner: WebView) {
        if (owner === webView && !crashed) emit(WebViewEvent.HistoryChanged(owner.canGoBack(), owner.canGoForward(), owner.url))
    }

    private fun bridgeTokenScript(deliver: Boolean): String =
        "window.__GY_WEBVIEW_DOCUMENT_TOKEN__ = ${JSONObject.quote(documentToken)};" +
            "if(typeof window.__GY_WEBVIEW_BRIDGE_TOKEN_READY__==='function')window.__GY_WEBVIEW_BRIDGE_TOKEN_READY__($deliver);"

    private fun emit(event: WebViewEvent) { if (!destroyed && pageOwnerActive != false) onEvent?.invoke(WebViewWire.eventValues(event)) }

    private fun showPermissionExplanation(permissions: Set<WebViewPermission>, complete: (Boolean) -> Unit): () -> Unit {
        val pending = PendingPermissionExplanation(UUID.randomUUID().toString(), webView, callbackGeneration, complete)
        permissionExplanation = pending
        emit(WebViewEvent.PermissionExplanationRequested(pending.requestId, permissions))
        return {
            if (!pending.resolved) {
                pending.resolved = true
                if (permissionExplanation === pending) permissionExplanation = null
                // 同步重入后仍须撤掉旧说明，且不能清掉新请求；销毁也交付取消。
                onEvent?.invoke(WebViewWire.eventValues(WebViewEvent.PermissionExplanationCancelled(pending.requestId)))
            }
        }
    }

    private fun exitFullscreen(): Boolean = fullscreenHost?.hide() ?: false

    private fun releaseWebView() {
        callbackGeneration++
        val owner = webView
        val oldCapabilities = capabilities
        val oldPopupRouter = popupRouter
        val oldChannels = pageMessageChannels
        val oldFullscreen = fullscreenHost
        val wasCrashed = crashed
        webView = null
        capabilities = null
        popupRouter = null
        pageMessageChannels = null
        fullscreenHost = null
        initialHtmlNavigation = false
        finishedScriptsInjected = false
        crashed = false
        oldChannels?.revoke()
        oldFullscreen?.hide()
        oldPopupRouter?.release()
        if (owner == null) { oldCapabilities?.destroy(); return }
        oldCapabilities?.release(owner)
        oldCapabilities?.destroy()
        WebViewDiagnostics.markReleased(owner, wasCrashed)
        earlyScripts.remove(owner)
        owner.removeAppWebBridge()
        if (!wasCrashed) { runCatching { owner.onPause() }; runCatching { owner.stopLoading() } }
        removeView(owner)
        owner.webChromeClient = null
        owner.webViewClient = android.webkit.WebViewClient()
        runCatching { owner.destroy() }
    }

    override fun onDestroy() {
        if (destroyed) return
        destroyed = true
        releaseWebView()
         onEvent = null; request = null
        super<IKuiklyRenderViewExport>.onDestroy()
    }
}

/** 在宿主 registerExternalRenderView 中调用。 */
@JvmOverloads
fun IKuiklyRenderExport.registerGYWebView(
    fullscreenOrientation: Int? = null,
    fullscreenControlsFactory: ((Context, AndroidWebFullscreenActions) -> AndroidWebFullscreenControls)? = null,
) = registerGYWebView(fullscreenOrientation, null, fullscreenControlsFactory)

fun IKuiklyRenderExport.registerGYWebView(
    fullscreenOrientation: Int?,
    filePicker: io.github.gycrosskit.composewebview.AndroidWebFilePicker?,
    fullscreenControlsFactory: ((Context, AndroidWebFullscreenActions) -> AndroidWebFullscreenControls)? = null,
) = renderViewExport(GYWebView.VIEW_NAME, { GYWebViewNative(it, fullscreenOrientation, fullscreenControlsFactory, filePicker) })
