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
import java.util.UUID

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
    val initialNavigation = request.content.initialOrigin()?.let { WebViewNavigationRequest(it, true, false) }
    if (initialNavigation != null && !request.allowsNavigation(initialNavigation)) {
        DisposableEffect(request) {
            callbacks.onEvent(WebViewEvent.Navigation(initialNavigation, true))
            onDispose {}
        }
        return
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val currentRequest by rememberUpdatedState(request)
    val currentCallbacks by rememberUpdatedState(callbacks)
    val currentVisible by rememberUpdatedState(visible)
    val bridgeToken = remember { arrayOf(UUID.randomUUID().toString()) }
    val activity = remember(context) { context.findComponentActivity() }
    val capabilities = remember(activity) {
        activity?.let {
                AndroidWebCapabilities(
                    activity = it,
                    request = { currentRequest },
                    isAttached = { currentVisible && state.isAttached(it) },
                onPermissionSettingsRequired = { permissions ->
                    currentCallbacks.onEvent(WebViewEvent.PermissionSettingsRequired(permissions))
                },
            )
        }
    }
    val popupRouter = remember {
        AndroidPopupRouter(request = { currentRequest }, onNavigation = { navigation ->
            if (shouldBlockNavigation(navigation, currentRequest, currentCallbacks)) WebViewNavigationDecision.BLOCK
            else WebViewNavigationDecision.ALLOW
        })
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
        state.cancelPendingCapabilities = { owner ->
            popupRouter.release()
            capabilities?.release(owner)
        }
        onDispose {
            state.cancelPendingCapabilities = null
            state.installBackInterceptor(null)
            fullscreenController?.release()
            capabilities?.destroy()
            popupRouter.release()
        }
    }
    DisposableEffect(visible, capabilities, fullscreenController, state) {
        state.invalidateJavascriptCallbacks()
        bridgeToken[0] = UUID.randomUUID().toString()
        state.webView?.evaluateJavascript("window.__GY_WEBVIEW_DOCUMENT_TOKEN__='${bridgeToken[0]}';", null)
        if (!visible) {
            state.pageMessageChannels?.revoke()
            state.webView?.let { capabilities?.release(it) }
            popupRouter.release()
            fullscreenController?.hide()
        }
        onDispose {}
    }
    val earlyScriptInstaller = remember {
        AndroidEarlyScriptInstaller { view, metric, duration ->
            if (!state.isAttached(view)) return@AndroidEarlyScriptInstaller
            WebViewDiagnostics.performanceMetric(view, metric, duration)
            currentCallbacks.onEvent(WebViewEvent.PerformanceMetric(metric, duration))
        }
    }

    // 高权限来源集合变化时重建实例，保证旧页面的 Bridge/Client 不继续服务新安全边界。
    key(request.security, request.scripts, request.settings.javaScriptEnabled, request.navigationPolicy,
        request.pageMessageChannels, request.content.takeIf { request.pageMessageChannels.isNotEmpty() }) {
        val channelOwners = remember { mutableMapOf<WebView, AndroidPageMessageChannels>() }
        AppWebView(
            content = request.content,
            visible = visible,
            modifier = modifier.fillMaxSize(),
            state = state,
            config = request.settings,
            webViewClientFactory = { listener ->
                val owner = requireNotNull(state.webView)
                commonWebViewClient(
                    listener = listener,
                    request = { currentRequest },
                    callbacks = { currentCallbacks },
                    onDocumentChanged = {
                        state.invalidateJavascriptCallbacks()
                        bridgeToken[0] = UUID.randomUUID().toString()
                        capabilities?.release(it)
                        popupRouter.release()
                    },
                    documentToken = { bridgeToken[0] },
                    pageMessageChannels = { channelOwners[owner] },
                    isOwner = { it === owner && state.isAttached(owner) },
                    isOwnerCallback = { state.isAttached(owner) },
                )
            },
            webChromeClientFactory = { owner, listener ->
                object : AppWebChromeClient(
                    object : AppWebChromeClient.Listener {
                        override fun onProgressChanged(progress: Int) {
                            if (!state.isAttached(owner)) return
                            listener.onProgressChanged(progress)
                            currentCallbacks.onEvent(WebViewEvent.ProgressChanged(progress))
                        }

                        override fun onReceivedTitle(title: String?, url: String?) {
                            if (!state.isAttached(owner)) return
                            listener.onReceivedTitle(title, url)
                            currentCallbacks.onEvent(WebViewEvent.TitleChanged(title))
                        }
                    },
                    isOwner = { it === owner && state.isAttached(owner) },
                ) {
                    override fun onShowFileChooser(
                        webView: WebView?,
                        filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                        fileChooserParams: FileChooserParams?,
                    ): Boolean {
                        if (!state.isAttached(owner) || webView !== owner) { filePathCallback?.onReceiveValue(null); return true }
                        return capabilities?.showFileChooser(webView, filePathCallback, fileChooserParams)
                            ?: run { filePathCallback?.onReceiveValue(null); true }
                    }

                    override fun onPermissionRequest(request: PermissionRequest?) {
                        if (!state.isAttached(owner)) request?.deny()
                        else capabilities?.requestMedia(owner, request) ?: request?.deny()
                    }

                    override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                        if (state.isAttached(owner)) capabilities?.cancelMedia(request)
                    }

                    override fun onCreateWindow(
                        view: WebView,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: Message,
                    ): Boolean = view === owner && state.isAttached(owner) && currentVisible && popupRouter.createWindow(view, isUserGesture, resultMsg)

                    override fun onCloseWindow(window: WebView) = popupRouter.close(window)

                    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                        if (view == null || callback == null) return
                        if (!state.isAttached(owner) || !currentVisible) { callback.onCustomViewHidden(); return }
                        fullscreenController?.show(view, callback) ?: callback.onCustomViewHidden()
                    }

                    override fun onHideCustomView() {
                        if (state.isAttached(owner)) fullscreenController?.hide()
                    }
                }
            },
            configure = {
                state.javascriptAllowed = { currentRequest.canEvaluateJavascriptAt(it.url) }
                fullscreenController?.attach(this)
                earlyScriptInstaller.install(this, currentRequest)
                if (currentRequest.pageMessageChannels.isNotEmpty()) {
                    val owner = this
                    val session = AndroidPageMessageChannels(
                        owner, { currentRequest }, { currentVisible && state.isAttached(owner) },
                    ) { currentCallbacks.onEvent(it) }
                    channelOwners[owner] = session
                    state.pageMessageChannels = session
                    if (!currentVisible) session.revoke()
                }
                if (currentRequest.canUseAppBridgeAt(currentRequest.content.initialOrigin())) {
                    val owner = this
                    installAppWebBridge(request = { currentRequest }, documentToken = { bridgeToken[0] }) { handlerName, data ->
                        if (!currentVisible || !state.isAttached(owner)) return@installAppWebBridge
                        currentCallbacks.onEvent(
                            WebViewEvent.BridgeMessage(WebViewBridgeMessage(handlerName, data)),
                        )
                    }
                }
            },
            onRelease = {
                channelOwners.remove(it)?.revoke()
                if (state.webView === it) {
                    state.pageMessageChannels = null
                }
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
    onDocumentChanged: (WebView) -> Unit,
    documentToken: () -> String,
    pageMessageChannels: () -> AndroidPageMessageChannels?,
    isOwner: (WebView) -> Boolean,
    isOwnerCallback: () -> Boolean,
) = object : AppWebViewClient(
    object : AppWebViewClient.Listener {
        override fun onPageLoadStarted(url: String?) {
            if (!isOwnerCallback()) return
            listener.onPageLoadStarted(url)
            callbacks().onEvent(WebViewEvent.PageStarted(url))
        }

        override fun onPageLoadFinished(url: String?) {
            if (!isOwnerCallback()) return
            listener.onPageLoadFinished(url)
            callbacks().onEvent(WebViewEvent.PageFinished(url))
        }

        override fun onPageCommitVisible(url: String?) {
            if (!isOwnerCallback()) return
            listener.onPageCommitVisible(url)
            callbacks().onEvent(WebViewEvent.FirstContentVisible(url))
        }

        override fun onPageLoadFailed(error: WebViewLoadError) {
            if (!isOwnerCallback()) return
            pageMessageChannels()?.revoke()
            listener.onPageLoadFailed(error)
            callbacks().onEvent(WebViewEvent.LoadFailed(error))
        }
    },
    isOwner = isOwner,
) {
    private var initialHtmlNavigation = request().content is WebViewContent.Html

    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
        if (!isOwner(view)) return
        pageMessageChannels()?.onPageStarted(url)
        initialHtmlNavigation = false
        onDocumentChanged(view)
        super.onPageStarted(view, url, favicon)
    }

    private fun route(view: WebView, navigation: WebViewNavigationRequest): Boolean {
        if (!isOwner(view)) return true
        val internalHtmlLoad = initialHtmlNavigation && navigation.isInternalHtmlInitialNavigation()
        if (navigation.isMainFrame) initialHtmlNavigation = false
        val blocked = shouldBlockNavigation(navigation, request(), callbacks(), internalHtmlLoad)
        if (!blocked && navigation.isMainFrame && !internalHtmlLoad && isOwner(view)) pageMessageChannels()?.revoke()
        if (!blocked && navigation.isMainFrame && isOwner(view)) onDocumentChanged(view)
        return blocked || !isOwner(view)
    }

    override fun shouldOverrideUrlLoading(view: WebView, navigation: WebResourceRequest): Boolean =
        route(view, WebViewNavigationRequest(navigation.url.toString(), navigation.isForMainFrame, navigation.hasGesture()))

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        route(view, WebViewNavigationRequest(url, true, false))

    override fun shouldInterceptRequest(
        view: WebView,
        resource: WebResourceRequest,
    ): WebResourceResponse? = if (!isOwner(view)) null else if (
        request().blockedResourceRules.any { it.matches(resource.url.toString()) }
    ) {
        WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
    } else {
        super.shouldInterceptRequest(view, resource)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        if (!view.isActiveAppWebView() || !isOwner(view)) return
        view.evaluateJavascript("window.__GY_WEBVIEW_DOCUMENT_TOKEN__='${documentToken()}';", null)
        super.onPageFinished(view, url)
        if (!view.isActiveAppWebView() || !isOwner(view)) return
        val current = request()
        if (current.settings.javaScriptEnabled && current.canUseAppBridgeAt(url)) view.injectAppWebBridge()
        // 旧内核不支持 document-start 或页面未触发 commit 时，在完成回调保证脚本最终执行。
        if (current.settings.javaScriptEnabled) view.evaluateJavascript(WEB_VIEW_PERFORMANCE_SCRIPT, null)
        current.earlyScriptSource()?.let { view.evaluateJavascript(it, null) }
        current.finishedScriptsAt(url).forEach { script -> view.evaluateJavascript(script.source, null) }
    }

    override fun onPageCommitVisible(view: WebView, url: String?) {
        if (!view.isActiveAppWebView() || !isOwner(view)) return
        view.evaluateJavascript("window.__GY_WEBVIEW_DOCUMENT_TOKEN__='${documentToken()}';", null)
        super.onPageCommitVisible(view, url)
        if (!view.isActiveAppWebView() || !isOwner(view)) return
        val current = request()
        if (current.settings.javaScriptEnabled && current.canUseAppBridgeAt(url)) view.injectAppWebBridge()
        if (current.settings.javaScriptEnabled) view.evaluateJavascript(WEB_VIEW_PERFORMANCE_SCRIPT, null)
        current.earlyScriptSource()?.let { view.evaluateJavascript(it, null) }
    }
}

private fun shouldBlockNavigation(
    navigation: WebViewNavigationRequest,
    request: WebViewRequest,
    callbacks: WebViewCallbacks,
    internalHtmlLoad: Boolean = false,
): Boolean {
    val blockedByPolicy = !internalHtmlLoad && !request.allowsNavigation(navigation)
    val blockedByCallback = callbacks.onNavigationRequest(navigation) == WebViewNavigationDecision.BLOCK
    val blocked = blockedByPolicy || blockedByCallback
    callbacks.onEvent(WebViewEvent.Navigation(navigation, blocked))
    return blocked
}
