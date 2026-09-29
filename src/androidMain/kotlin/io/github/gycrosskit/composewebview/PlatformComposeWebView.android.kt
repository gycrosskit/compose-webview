package io.github.gycrosskit.composewebview

import android.os.Message
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.ByteArrayInputStream

/** Android 继续由 shared 绘制 Material 进度条。 */
actual val platformWebViewRendersLoadingProgress: Boolean = false

/** Android actual 复用既有实例门禁、内容去重、Lifecycle 和释放内核。 */
@Composable
internal actual fun PlatformAppWebView(
    request: WebViewRequest,
    modifier: Modifier,
    state: AppWebViewState,
    callbacks: WebViewCallbacks,
    pageEnteredAtMillis: Long,
    visible: Boolean,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val currentRequest by rememberUpdatedState(request)
    val currentCallbacks by rememberUpdatedState(callbacks)
    val activity = remember(context) { context.findComponentActivity() }
    val capabilities = remember(activity) {
        activity?.let {
                AndroidWebCapabilities(
                    activity = it,
                    request = { currentRequest },
                    isAttached = state::isAttached,
                onPermissionSettingsRequired = { permissions ->
                    currentCallbacks.onEvent(WebViewEvent.PermissionSettingsRequired(permissions))
                },
            )
        }
    }
    val popupRouter = remember {
        AndroidPopupRouter(request = { currentRequest }, callbacks = { currentCallbacks })
    }
    val fullscreenController = remember(activity, lifecycleOwner, scope) {
        activity?.let {
            AndroidWebFullscreenController(
                activity = it,
                lifecycleOwner = lifecycleOwner,
                scope = scope,
                onVisibilityChanged = { visible ->
                    currentCallbacks.onEvent(WebViewEvent.FullscreenChanged(visible))
                },
            )
        }
    }
    val fullscreenBackHandler = remember(fullscreenController) {
        { fullscreenController?.hide() == true }
    }
    DisposableEffect(capabilities, popupRouter, fullscreenController, state) {
        state.installBackInterceptor(fullscreenBackHandler)
        onDispose {
            state.installBackInterceptor(null)
            fullscreenController?.release()
            capabilities?.destroy()
            popupRouter.release()
        }
    }
    val earlyScriptInstaller = remember {
        AndroidEarlyScriptInstaller { view, metric, duration ->
            WebViewDiagnostics.performanceMetric(view, metric, duration)
            currentCallbacks.onEvent(WebViewEvent.PerformanceMetric(metric, duration))
        }
    }

    // 高权限来源集合变化时重建实例，保证旧页面的 Bridge/Client 不继续服务新安全边界。
    key(request.security, request.scripts, request.settings.javaScriptEnabled) {
        AppWebView(
            content = request.content,
            visible = visible,
            modifier = modifier.fillMaxSize(),
            state = state,
            config = request.settings,
            webViewClientFactory = { listener ->
                commonWebViewClient(
                    listener = listener,
                    request = { currentRequest },
                    callbacks = { currentCallbacks },
                )
            },
            webChromeClientFactory = { owner, listener ->
                object : AppWebChromeClient(
                    object : AppWebChromeClient.Listener {
                        override fun onProgressChanged(progress: Int) {
                            listener.onProgressChanged(progress)
                            currentCallbacks.onEvent(WebViewEvent.ProgressChanged(progress))
                        }

                        override fun onReceivedTitle(title: String?, url: String?) {
                            listener.onReceivedTitle(title, url)
                            currentCallbacks.onEvent(WebViewEvent.TitleChanged(title))
                        }
                    },
                ) {
                    override fun onShowFileChooser(
                        webView: WebView?,
                        filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                        fileChooserParams: FileChooserParams?,
                    ): Boolean = capabilities?.showFileChooser(
                        webView,
                        filePathCallback,
                        fileChooserParams,
                    ) ?: false

                    override fun onPermissionRequest(request: PermissionRequest?) {
                        capabilities?.requestMedia(owner, request) ?: request?.deny()
                    }

                    override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                        capabilities?.cancelMedia(request)
                    }

                    override fun onCreateWindow(
                        view: WebView,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: Message,
                    ): Boolean = popupRouter.createWindow(view, isUserGesture, resultMsg)

                    override fun onCloseWindow(window: WebView) = popupRouter.close(window)

                    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                        if (view == null || callback == null) return
                        fullscreenController?.show(view, callback) ?: callback.onCustomViewHidden()
                    }

                    override fun onHideCustomView() {
                        fullscreenController?.hide()
                    }
                }
            },
            configure = {
                fullscreenController?.attach(this)
                earlyScriptInstaller.install(this, currentRequest)
                if (currentRequest.canUseAppBridgeAt(currentRequest.content.initialOrigin())) {
                    installAppWebBridge(request = { currentRequest }) { handlerName, data ->
                        currentCallbacks.onEvent(
                            WebViewEvent.BridgeMessage(WebViewBridgeMessage(handlerName, data)),
                        )
                    }
                }
            },
            onRelease = {
                capabilities?.release(it)
                fullscreenController?.hide()
                fullscreenController?.detach(it)
                earlyScriptInstaller.remove(it)
                it.removeAppWebBridge()
            },
            pageEnteredAtMillis = pageEnteredAtMillis,
        )
    }
}

private fun commonWebViewClient(
    listener: AppWebViewClient.Listener,
    request: () -> WebViewRequest,
    callbacks: () -> WebViewCallbacks,
) = object : AppWebViewClient(
    object : AppWebViewClient.Listener {
        override fun onPageLoadStarted(url: String?) {
            listener.onPageLoadStarted(url)
            callbacks().onEvent(WebViewEvent.PageStarted(url))
        }

        override fun onPageLoadFinished(url: String?) {
            listener.onPageLoadFinished(url)
            callbacks().onEvent(WebViewEvent.PageFinished(url))
        }

        override fun onPageCommitVisible(url: String?) {
            listener.onPageCommitVisible(url)
            callbacks().onEvent(WebViewEvent.FirstContentVisible(url))
        }

        override fun onPageLoadFailed(error: WebViewLoadError) {
            listener.onPageLoadFailed(error)
            callbacks().onEvent(WebViewEvent.LoadFailed(error))
        }
    },
) {
    override fun shouldOverrideUrlLoading(view: WebView, navigation: WebResourceRequest): Boolean =
        shouldBlockNavigation(
            url = navigation.url.toString(),
            isMainFrame = navigation.isForMainFrame,
            hasUserGesture = navigation.hasGesture(),
            request = request(),
            callbacks = callbacks(),
        )

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        shouldBlockNavigation(
            url = url,
            isMainFrame = true,
            hasUserGesture = false,
            request = request(),
            callbacks = callbacks(),
        )

    override fun shouldInterceptRequest(
        view: WebView,
        resource: WebResourceRequest,
    ): WebResourceResponse? = if (
        request().blockedResourceRules.any { it.matches(resource.url.toString()) }
    ) {
        WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
    } else {
        super.shouldInterceptRequest(view, resource)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        if (!view.isActiveAppWebView()) return
        super.onPageFinished(view, url)
        val current = request()
        if (current.canUseAppBridgeAt(url)) view.injectAppWebBridge()
        // 旧内核不支持 document-start 或页面未触发 commit 时，在完成回调保证脚本最终执行。
        if (current.settings.javaScriptEnabled) view.evaluateJavascript(WEB_VIEW_PERFORMANCE_SCRIPT, null)
        current.earlyScriptSource()?.let { view.evaluateJavascript(it, null) }
        current.finishedScriptsAt(url).forEach { script -> view.evaluateJavascript(script.source, null) }
    }

    override fun onPageCommitVisible(view: WebView, url: String?) {
        if (!view.isActiveAppWebView()) return
        super.onPageCommitVisible(view, url)
        val current = request()
        if (current.canUseAppBridgeAt(url)) view.injectAppWebBridge()
        if (current.settings.javaScriptEnabled) view.evaluateJavascript(WEB_VIEW_PERFORMANCE_SCRIPT, null)
        current.earlyScriptSource()?.let { view.evaluateJavascript(it, null) }
    }
}

private fun shouldBlockNavigation(
    url: String,
    isMainFrame: Boolean,
    hasUserGesture: Boolean,
    request: WebViewRequest,
    callbacks: WebViewCallbacks,
): Boolean {
    val blockedByTrust = request.shouldBlockMainFrameNavigation(url, isMainFrame)
    val blockedByCallback = callbacks.onNavigationRequest(
        WebViewNavigationRequest(
            url = url,
            isMainFrame = isMainFrame,
            hasUserGesture = hasUserGesture,
            target = WebViewNavigationTarget.CURRENT_WINDOW,
        ),
    ) == WebViewNavigationDecision.BLOCK
    return blockedByTrust || blockedByCallback
}

/** `window.open` 只解析目标并交给中立导航回调，临时 WebView 不安装 Bridge。 */
private class AndroidPopupRouter(
    private val request: () -> WebViewRequest,
    private val callbacks: () -> WebViewCallbacks,
) {
    private val popups = mutableSetOf<WebView>()

    fun createWindow(parent: WebView, hasUserGesture: Boolean, resultMessage: Message): Boolean {
        if (!parent.isActiveAppWebView()) return false
        val transport = resultMessage.obj as? WebView.WebViewTransport ?: return false
        lateinit var popup: WebView
        var routed = false
        fun route(url: String?) {
            val target = url?.takeUnless { it.isBlank() || it == "about:blank" } ?: return
            if (routed) return
            routed = true
            val current = request()
            val trusted =
                !(current.security.appBridgeEnabled || current.security.pageBridgeEnabled) ||
                    current.canUseAppBridgeAt(target)
            val decision = callbacks().onNavigationRequest(
                WebViewNavigationRequest(
                    url = target,
                    isMainFrame = true,
                    hasUserGesture = hasUserGesture,
                    target = WebViewNavigationTarget.NEW_WINDOW,
                ),
            )
            if (trusted && decision == WebViewNavigationDecision.ALLOW && parent.isActiveAppWebView()) {
                parent.loadUrl(target)
            }
            close(popup)
        }
        popup = WebView(parent.context).apply {
            WebViewDiagnostics.created(this, "popup")
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    route(request.url.toString())
                    return true
                }

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    route(url)
                    return true
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    route(url)
                }
            }
        }
        popups += popup
        transport.webView = popup
        resultMessage.sendToTarget()
        return true
    }

    fun close(target: WebView) {
        if (!popups.remove(target)) return
        WebViewDiagnostics.markReleased(target)
        runCatching { target.stopLoading() }
        runCatching { target.webChromeClient = null }
        runCatching { target.webViewClient = android.webkit.WebViewClient() }
        runCatching { target.destroy() }
    }

    fun release() = popups.toList().forEach(::close)
}
