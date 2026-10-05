package io.github.gycrosskit.composewebview.kuikly

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Message
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.FrameLayout
import com.tencent.kuikly.core.render.android.export.IKuiklyRenderViewExport
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import com.tencent.kuikly.core.render.android.IKuiklyRenderExport
import io.github.gycrosskit.composewebview.*
import java.io.ByteArrayInputStream
import org.json.JSONObject
import java.util.UUID

/** Kuikly 原生生命周期持有系统 WebView，完全不依赖 Compose 状态或运行时。 */
class GYWebViewNative(context: Context) : FrameLayout(context), IKuiklyRenderViewExport {
    private var request: WebViewRequest? = null
    private var onEvent: KuiklyRenderCallback? = null
    private var webView: WebView? = null
    private var destroyed = false
    private var crashed = false
    private var initialHtmlNavigation = false
    private var callbackGeneration = 0L
    private var documentToken = UUID.randomUUID().toString()
    private var pageVisible = true
    private var finishedScriptsInjected = false
    private var fullscreen: View? = null
    private var fullscreenSystemUiVisibility: Int? = null
    private var fullscreenBarsVisible: Boolean? = null
    private var fullscreenBarsBehavior: Int? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var capabilities: AndroidWebCapabilities? = null
    private var popupRouter: AndroidPopupRouter? = null
    private val earlyScripts = AndroidEarlyScriptInstaller { owner, metric, duration ->
        if (owner === webView) emit(WebViewEvent.PerformanceMetric(metric, duration))
    }

    override fun setProp(propKey: String, propValue: Any): Boolean = when (propKey) {
        "request" -> {
            try {
                val next = WebViewWire.decodeRequest(propValue as String)
                if (next != request && !destroyed) {
                    releaseWebView()
                    request = next
                    createWebView(next)
                }
            } catch (error: Exception) {
                releaseWebView()
                request = null
                emit(WebViewEvent.LoadFailed(WebViewLoadError(WebViewErrorKind.LOAD_EXCEPTION, error.message.orEmpty())))
            }
            true
        }
        "visible" -> {
            pageVisible = propValue as? Boolean ?: false
            if (!pageVisible) { callbackGeneration++; webView?.let { capabilities?.release(it) } }
            visibility = if (pageVisible) View.VISIBLE else View.INVISIBLE
            if (pageVisible) webView?.onResume() else { exitFullscreen(); webView?.onPause() }
            true
        }
        "onEvent" -> { onEvent = propValue as? KuiklyRenderCallback; true }
        else -> super<IKuiklyRenderViewExport>.setProp(propKey, propValue)
    }

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        val owner = webView?.takeUnless { destroyed || crashed }
        val result: Any? = when (method) {
            "reload" -> {
                if (crashed) { val current = request; releaseWebView(); if (current != null) createWebView(current) }
                else owner?.reload()
                owner != null || webView != null
            }
            "goBack" -> if (exitFullscreen()) true else owner?.let { if (it.canGoBack()) { it.goBack(); true } else false } ?: false
            "goForward" -> owner?.let { if (it.canGoForward()) { it.goForward(); true } else false } ?: false
            "stopLoading" -> { owner?.stopLoading(); owner != null }
            "exitFullscreen" -> exitFullscreen()
            "evaluateJavascript" -> {
                val script = runCatching { JSONObject(params ?: "{}").getString("script") }.getOrNull()
                if (owner != null && script != null && pageVisible && request?.canEvaluateJavascriptAt(owner.url) == true) {
                    val generation = callbackGeneration
                    owner.evaluateJavascript(script) { value ->
                        if (owner === webView && !destroyed && !crashed && pageVisible && generation == callbackGeneration) callback?.invoke(mapOf("result" to value))
                    }
                } else if (!destroyed) callback?.invoke(mapOf("result" to null))
                return null
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
        val owner = WebView(context)
        webView = owner
        WebViewDiagnostics.created(owner, "kuikly")
        val hostActivity = context.findComponentActivity()
        capabilities = hostActivity?.let {
            AndroidWebCapabilities(it, { requireNotNull(request) }, { target -> target === webView && pageVisible && !destroyed && !crashed },
                { emit(WebViewEvent.PermissionSettingsRequired(it)) })
        }
        popupRouter = AndroidPopupRouter({ requireNotNull(request) }) { navigation ->
            if (route(navigation)) WebViewNavigationDecision.BLOCK else WebViewNavigationDecision.ALLOW
        }
        owner.applyWebViewConfig(current.settings, owner.settings.userAgentString, resources.configuration.fontScale,
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES, Color.TRANSPARENT)
        owner.webViewClient = object : AppWebViewClient(object : AppWebViewClient.Listener {
            override fun onPageLoadStarted(url: String?) { if (owner !== webView || destroyed) return;
                initialHtmlNavigation = false
                finishedScriptsInjected = false
                callbackGeneration++; documentToken = UUID.randomUUID().toString(); capabilities?.release(owner)
                emit(WebViewEvent.PageStarted(url)); history(owner) }
            override fun onPageLoadFinished(url: String?) { if (owner !== webView || destroyed) return; emit(WebViewEvent.PageFinished(url)); history(owner) }
            override fun onPageCommitVisible(url: String?) { if (owner !== webView || destroyed) return; emit(WebViewEvent.FirstContentVisible(url)) }
            override fun onPageLoadFailed(error: WebViewLoadError) {
                if (owner !== webView || destroyed) return
                if (error.kind == WebViewErrorKind.RENDER_PROCESS) {
                    crashed = true
                    WebViewDiagnostics.markReleased(owner, true)
                }
                emit(WebViewEvent.LoadFailed(error))
            }
        }) {
            override fun shouldOverrideUrlLoading(view: WebView, navigation: WebResourceRequest): Boolean =
                route(WebViewNavigationRequest(navigation.url.toString(), navigation.isForMainFrame, navigation.hasGesture()))
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = route(WebViewNavigationRequest(url, true, false))
            override fun shouldInterceptRequest(view: WebView, resource: WebResourceRequest): WebResourceResponse? =
                if (request?.blockedResourceRules?.any { it.matches(resource.url.toString()) } == true)
                    WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0))) else null
            override fun onPageCommitVisible(view: WebView, url: String?) { super.onPageCommitVisible(view, url); inject(view, false) }
            override fun onPageFinished(view: WebView, url: String?) { super.onPageFinished(view, url); inject(view, true) }
        }
        owner.webChromeClient = object : AppWebChromeClient(object : AppWebChromeClient.Listener {
            override fun onProgressChanged(progress: Int) { if (owner === webView) emit(WebViewEvent.ProgressChanged(progress)) }
            override fun onReceivedTitle(title: String?, url: String?) { if (owner === webView) emit(WebViewEvent.TitleChanged(title)) }
        }) {
            override fun onShowFileChooser(view: WebView?, callback: android.webkit.ValueCallback<Array<android.net.Uri>>?, params: FileChooserParams?): Boolean {
                val current = capabilities
                if (current != null) return current.showFileChooser(view, callback, params)
                callback?.onReceiveValue(null)
                return true
            }
            override fun onPermissionRequest(value: PermissionRequest?) { capabilities?.requestMedia(owner, value) ?: value?.deny() }
            override fun onPermissionRequestCanceled(value: PermissionRequest?) { capabilities?.cancelMedia(value) }
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean =
                popupRouter?.createWindow(view, isUserGesture, resultMsg) ?: false
            override fun onCloseWindow(window: WebView) { popupRouter?.close(window) }
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null || callback == null) return
                exitFullscreen()
                val parent = activity?.window?.decorView as? ViewGroup
                if (parent == null || !pageVisible) { callback.onCustomViewHidden(); return }
                fullscreen = view; fullscreenCallback = callback
                val window = activity?.window
                fullscreenSystemUiVisibility = parent.systemUiVisibility
                fullscreenBarsVisible = ViewCompat.getRootWindowInsets(parent)?.isVisible(WindowInsetsCompat.Type.systemBars())
                if (window != null) {
                    val controller = WindowCompat.getInsetsController(window, parent)
                    fullscreenBarsBehavior = controller.systemBarsBehavior
                    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                }
                parent.addView(view, ViewGroup.LayoutParams(-1, -1))
                emit(WebViewEvent.FullscreenChanged(true))
            }
            override fun onHideCustomView() { exitFullscreen() }
        }
        earlyScripts.install(owner, current)
        if (current.canUseAppBridgeAt(origin)) owner.installAppWebBridge({ requireNotNull(request) }, { documentToken }) { handler, data ->
            if (owner === webView && pageVisible && !destroyed && !crashed) emit(WebViewEvent.BridgeMessage(WebViewBridgeMessage(handler, data)))
        }
        addView(owner, LayoutParams(-1, -1))
        if (!pageVisible) owner.onPause()
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
        val blocked = (!internalHtmlLoad && request?.allowsNavigation(navigation) != true) || destroyed || crashed
        emit(WebViewEvent.Navigation(navigation, blocked))
        return blocked
    }

    private fun inject(owner: WebView, finished: Boolean) {
        val current = request ?: return
        if (owner !== webView || destroyed || crashed || !current.settings.javaScriptEnabled) return
        // 初始化属于当前文档；隐藏只撤销业务消息、权限和显式 JS 的异步回执。
        if (current.canUseAppBridgeAt(owner.url)) {
            owner.evaluateJavascript("window.__GY_WEBVIEW_DOCUMENT_TOKEN__ = ${JSONObject.quote(documentToken)};", null)
            owner.injectAppWebBridge()
        }
        owner.evaluateJavascript(WEB_VIEW_PERFORMANCE_SCRIPT, null)
        current.earlyScriptSource()?.let { owner.evaluateJavascript(it, null) }
        if (finished && !finishedScriptsInjected) {
            finishedScriptsInjected = true
            current.finishedScriptsAt(owner.url).forEach { owner.evaluateJavascript(it.source, null) }
        }
    }

    private fun history(owner: WebView) {
        if (owner === webView && !crashed) emit(WebViewEvent.HistoryChanged(owner.canGoBack(), owner.canGoForward(), owner.url))
    }

    private fun emit(event: WebViewEvent) { if (!destroyed) onEvent?.invoke(WebViewWire.eventValues(event)) }

    private fun exitFullscreen(): Boolean {
        val view = fullscreen ?: return false
        fullscreen = null
        val parent = view.parent as? ViewGroup
        parent?.removeView(view)
        val window = activity?.window
        if (parent != null && window != null) {
            val controller = WindowCompat.getInsetsController(window, parent)
            fullscreenBarsBehavior?.let { controller.systemBarsBehavior = it }
            if (fullscreenBarsVisible == true) controller.show(WindowInsetsCompat.Type.systemBars())
            fullscreenSystemUiVisibility?.let { parent.systemUiVisibility = it }
        }
        fullscreenSystemUiVisibility = null; fullscreenBarsVisible = null; fullscreenBarsBehavior = null
        val callback = fullscreenCallback; fullscreenCallback = null
        callback?.onCustomViewHidden()
        emit(WebViewEvent.FullscreenChanged(false))
        return true
    }

    private fun releaseWebView() {
        callbackGeneration++
        initialHtmlNavigation = false
        finishedScriptsInjected = false
        exitFullscreen()
        val owner = webView ?: return
        webView = null
        popupRouter?.release(); popupRouter = null
        capabilities?.release(owner); capabilities?.destroy(); capabilities = null
        WebViewDiagnostics.markReleased(owner, crashed)
        earlyScripts.remove(owner)
        owner.removeAppWebBridge()
        if (!crashed) { runCatching { owner.onPause() }; runCatching { owner.stopLoading() } }
        removeView(owner)
        owner.webChromeClient = null
        owner.webViewClient = android.webkit.WebViewClient()
        runCatching { owner.destroy() }
        crashed = false
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
fun IKuiklyRenderExport.registerGYWebView() = renderViewExport(GYWebView.VIEW_NAME, { GYWebViewNative(it) })
