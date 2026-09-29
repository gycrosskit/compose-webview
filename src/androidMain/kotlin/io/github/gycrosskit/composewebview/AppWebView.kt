package io.github.gycrosskit.composewebview

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/** 使用公共状态监听器创建业务定制 [WebViewClient] 的工厂。 */
typealias WebViewClientFactory = (AppWebViewClient.Listener) -> WebViewClient

/** 使用公共状态监听器创建业务定制 `WebChromeClient` 的工厂。 */
typealias WebChromeClientFactory = (WebView, AppWebChromeClient.Listener) -> android.webkit.WebChromeClient

/**
 * Compose WebView 内核，负责实例创建、配置同步、内容去重加载、生命周期转发和安全释放。
 *
 * 业务差异通过 Client factory 和 [configure] 扩展；factory 返回的 Client 必须继续把加载、进度和
 * 标题事件交给传入的 Listener，否则 [state] 不会更新。[configure] 会在实例创建时执行一次，
 * 不应捕获需要随重组变化的临时状态。
 * 相等的 [content] 在 Compose 重组时不会重新加载；离开组合后实例会解绑所有 Client 和子 View 并销毁，
 * 模块只用弱引用记录诊断状态，不持有 Activity 或业务对象的进程级强引用。
 *
 * [onNavigationRequest] 返回 `true` 表示导航已被业务接管，WebView 不再加载该地址。
 * [onRelease] 只用于释放业务附加资源，公共销毁流程会在其后继续执行。
 */
@Composable
fun AppWebView(
    content: WebViewContent,
    visible: Boolean = true,
    modifier: Modifier = Modifier,
    state: AppWebViewState = rememberAppWebViewState(),
    config: WebViewSettings = WebViewSettings(),
    backgroundColor: Color = Color.Transparent,
    onNavigationRequest: (Uri) -> Boolean = { false },
    webViewClientFactory: WebViewClientFactory? = null,
    webChromeClientFactory: WebChromeClientFactory? = null,
    configure: WebView.() -> Unit = {},
    onRelease: (WebView) -> Unit = {},
    pageEnteredAtMillis: Long? = null,
) {
    val fontScale = LocalDensity.current.fontScale
    val darkTheme = isSystemInDarkTheme()
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentNavigationRequest by rememberUpdatedState(onNavigationRequest)
    val currentConfigure by rememberUpdatedState(configure)
    val currentOnRelease by rememberUpdatedState(onRelease)

    DisposableEffect(lifecycleOwner, state, state.instanceKey) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> state.resumeWebView()
                Lifecycle.Event.ON_PAUSE -> state.pauseWebView()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    key(state.instanceKey) {
        AndroidView(
            factory = { context ->
                val creationStartedAtNanos = SystemClock.elapsedRealtimeNanos()
                WebView(context).apply {
                    WebViewDiagnostics.created(
                        view = this,
                        pageEnteredAtMillis = pageEnteredAtMillis,
                        creationDurationMillis =
                            (SystemClock.elapsedRealtimeNanos() - creationStartedAtNanos) / 1_000_000L,
                    )
                    state.attach(this)
                    applyAppWebViewConfig(config, state, fontScale, darkTheme, backgroundColor)

                    val listener = state.createWebViewClientListener(this)
                    webViewClient = webViewClientFactory?.invoke(listener)
                        ?: DefaultAppWebViewClient(listener) { currentNavigationRequest(it) }

                    val chromeListener = state.createWebChromeClientListener(this)
                    webChromeClient = webChromeClientFactory?.invoke(this, chromeListener)
                        ?: AppWebChromeClient(chromeListener)

                    currentConfigure(this)
                }
            },
            update = { target ->
                // 原生 WebView 在部分设备上仍会绘制到已经收起的 Compose 父布局之外。
                target.visibility = if (visible) View.VISIBLE else View.INVISIBLE
                target.applyAppWebViewConfig(
                    config = config,
                    state = state,
                    fontScale = fontScale,
                    darkTheme = darkTheme,
                    backgroundColor = backgroundColor,
                )
                target.loadIfChanged(content, state)
            },
            onRelease = { target ->
                val renderProcessGone = state.isRenderProcessGone(target)
                try {
                    currentOnRelease(target)
                } finally {
                    state.detach(target)
                    WebViewDiagnostics.markReleased(target, renderProcessGone)
                    target.releaseAppWebView(renderProcessGone)
                }
            },
            modifier = modifier,
        )
    }
}

/** 默认 Client 只提供主框架状态转发和声明式导航拦截，不加入任何业务路由。 */
private class DefaultAppWebViewClient(
    listener: AppWebViewClient.Listener,
    private val onNavigationRequest: (Uri) -> Boolean,
) : AppWebViewClient(listener) {
    override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
        return onNavigationRequest(request.url)
    }

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        return onNavigationRequest(Uri.parse(url))
    }
}

/**
 * 将平台加载回调绑定到创建 Listener 时的实例。
 *
 * WebView 销毁后仍可能收到 Chromium 队列中的迟到回调，必须先验证实例仍是 State 当前绑定对象。
 */
private fun AppWebViewState.createWebViewClientListener(target: WebView) = object : AppWebViewClient.Listener {
    override fun onPageLoadStarted(url: String?) {
        if (!isAttached(target)) {
            WebViewDiagnostics.ignoredCallback(target, "page-start")
            return
        }
        onLoadStarted(url)
    }

    override fun onPageLoadFinished(url: String?) {
        if (!isAttached(target)) {
            WebViewDiagnostics.ignoredCallback(target, "page-finish")
            return
        }
        onLoadFinished(target.url ?: url, target.canGoBack())
    }

    override fun onPageCommitVisible(url: String?) {
        if (!isAttached(target)) {
            WebViewDiagnostics.ignoredCallback(target, "page-commit-visible")
            return
        }
        this@createWebViewClientListener.onPageCommitVisible()
    }

    override fun onPageLoadFailed(error: WebViewLoadError) {
        if (!isAttached(target)) {
            WebViewDiagnostics.ignoredCallback(target, "page-failure")
            return
        }
        onLoadFailed(error)
    }
}

/** 标题和进度同样只允许当前存活实例更新，避免重建后的旧回调覆盖新页面。 */
private fun AppWebViewState.createWebChromeClientListener(target: WebView) = object : AppWebChromeClient.Listener {
    override fun onProgressChanged(progress: Int) {
        if (isAttached(target)) this@createWebChromeClientListener.onProgressChanged(progress)
    }

    override fun onReceivedTitle(title: String?, url: String?) {
        if (isAttached(target)) this@createWebChromeClientListener.onReceivedTitle(title, url)
    }
}

/**
 * 把声明式配置同步到已存在实例。
 *
 * 此函数可以在每次重组执行，但只能修改 WebSettings 和显示属性，不能触发页面加载或创建新的 WebView。
 */
@SuppressLint("SetJavaScriptEnabled")
private fun WebView.applyAppWebViewConfig(
    config: WebViewSettings,
    state: AppWebViewState,
    fontScale: Float,
    darkTheme: Boolean,
    backgroundColor: Color,
) {
    val resolvedTextZoom = calculateWebViewTextZoom(fontScale, config)
    val resolvedDarkening = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        darkTheme && config.algorithmicDarkeningAllowed
    } else {
        null
    }
    val resolvedBackgroundColor = if (backgroundColor == Color.Transparent) {
        AndroidColor.TRANSPARENT
    } else {
        backgroundColor.toArgb()
    }
    val resolvedConfig = AppliedAppWebViewConfig(
        config = config,
        textZoom = resolvedTextZoom,
        algorithmicDarkeningAllowed = resolvedDarkening,
        backgroundColor = resolvedBackgroundColor,
    )
    val previous = state.appliedConfig
    if (previous == resolvedConfig) return

    settings.apply {
        if (previous?.config?.javaScriptEnabled != config.javaScriptEnabled) {
            javaScriptEnabled = config.javaScriptEnabled
        }
        if (previous?.config?.domStorageEnabled != config.domStorageEnabled) {
            domStorageEnabled = config.domStorageEnabled
        }
        if (previous?.config?.allowFileAccess != config.allowFileAccess) {
            allowFileAccess = config.allowFileAccess
        }
        if (previous?.config?.allowContentAccess != config.allowContentAccess) {
            allowContentAccess = config.allowContentAccess
        }
        if (previous?.config?.mixedContentPolicy != config.mixedContentPolicy) {
            mixedContentMode = config.androidMixedContentMode
        }
        if (previous?.config?.cachePolicy != config.cachePolicy) {
            cacheMode = config.androidCacheMode
        }
        if (previous?.config?.supportMultipleWindows != config.supportMultipleWindows) {
            setSupportMultipleWindows(config.supportMultipleWindows)
        }
        if (
            previous?.config?.javaScriptCanOpenWindowsAutomatically !=
            config.javaScriptCanOpenWindowsAutomatically
        ) {
            javaScriptCanOpenWindowsAutomatically = config.javaScriptCanOpenWindowsAutomatically
        }
        if (previous?.config?.mediaPlaybackRequiresUserGesture != config.mediaPlaybackRequiresUserGesture) {
            mediaPlaybackRequiresUserGesture = config.mediaPlaybackRequiresUserGesture
        }
        if (previous?.config?.loadsImagesAutomatically != config.loadsImagesAutomatically) {
            loadsImagesAutomatically = config.loadsImagesAutomatically
        }
        if (previous?.config?.blockNetworkImage != config.blockNetworkImage) {
            blockNetworkImage = config.blockNetworkImage
        }
        if (previous?.config?.builtInZoomControls != config.builtInZoomControls) {
            builtInZoomControls = config.builtInZoomControls
        }
        if (previous?.config?.displayZoomControls != config.displayZoomControls) {
            displayZoomControls = config.displayZoomControls
        }
        if (previous?.config?.supportZoom != config.supportZoom) setSupportZoom(config.supportZoom)
        if (previous?.config?.useWideViewPort != config.useWideViewPort) {
            useWideViewPort = config.useWideViewPort
        }
        if (previous?.config?.loadWithOverviewMode != config.loadWithOverviewMode) {
            loadWithOverviewMode = config.loadWithOverviewMode
        }
        if (previous?.textZoom != resolvedTextZoom) textZoom = resolvedTextZoom
        val resolvedUserAgent = buildUserAgent(state.defaultUserAgent, config.userAgentSuffix)
        if (userAgentString != resolvedUserAgent) userAgentString = resolvedUserAgent
        if (
            resolvedDarkening != null &&
            previous?.algorithmicDarkeningAllowed != resolvedDarkening &&
            WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)
        ) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, resolvedDarkening)
        }
    }
    if (previous?.config?.acceptsThirdPartyCookies != config.acceptsThirdPartyCookies) {
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, config.acceptsThirdPartyCookies)
    }
    if (previous?.backgroundColor != resolvedBackgroundColor) {
        setBackgroundColor(resolvedBackgroundColor)
    }
    state.appliedConfig = resolvedConfig
}

/** 已解析动态环境值后的配置快照，只用于判断是否需要调用原生 Setter。 */
internal data class AppliedAppWebViewConfig(
    val config: WebViewSettings,
    val textZoom: Int,
    val algorithmicDarkeningAllowed: Boolean?,
    val backgroundColor: Int,
)

/** 始终从实例初始 User-Agent 追加后缀，避免多次重组重复拼接。 */
private fun buildUserAgent(defaultUserAgent: String?, suffix: String?): String? {
    val base = defaultUserAgent.orEmpty()
    val normalizedSuffix = suffix?.trim().orEmpty()
    return if (normalizedSuffix.isBlank()) base else "$base $normalizedSuffix"
}

/** 只在声明式内容值变化时加载；相同内容的普通重组不会刷新当前网页。 */
private fun WebView.loadIfChanged(content: WebViewContent, state: AppWebViewState) {
    if (!isActiveAppWebView()) {
        WebViewDiagnostics.ignoredCallback(this, "load")
        return
    }
    if (!state.markContentForLoad(content)) return
    WebViewDiagnostics.load(this, content)
    if (content.isBlankWebViewContent()) {
        state.onLoadFailed(WebViewErrorHelper.fromEmptyContent())
        return
    }
    when (content) {
        is WebViewContent.Url -> loadUrl(content.url, content.additionalHeaders)

        is WebViewContent.Html -> loadDataWithBaseURL(
            content.baseUrl,
            content.html,
            content.mimeType,
            content.encoding,
            content.historyUrl,
        )
    }
}

/**
 * 按“停止执行 → 解除外部引用 → 销毁”的顺序释放实例。
 *
 * 渲染进程已退出时跳过可能再次访问 Chromium 的操作；每一步独立兜底，前一步失败也必须继续 destroy。
 */
private fun WebView.releaseAppWebView(renderProcessGone: Boolean) {
    if (!renderProcessGone) {
        releaseStep("pause") { onPause() }
        releaseStep("stopLoading") { stopLoading() }
        // 不加载 about:blank：它会新建一次异步导航，并可能在紧随其后的 destroy() 后回调 Chromium。
        releaseStep("clearHistory") { clearHistory() }
    }
    // 即使业务 onRelease 遗漏移除，模块自己的 Bridge 也不能继续强持有页面回调。
    releaseStep("removeBridge") { removeAppWebBridge() }
    releaseStep("removeAllViews") { removeAllViews() }
    releaseStep("clearChromeClient") { webChromeClient = null }
    releaseStep("clearWebViewClient") { webViewClient = WebViewClient() }
    releaseStep("destroy") { destroy() }
    WebViewDiagnostics.releaseFinished(this)
}

/** 单个释放步骤失败只记录诊断，不能中断后续资源清理。 */
private inline fun WebView.releaseStep(name: String, block: () -> Unit) {
    runCatching(block).onFailure { error ->
        WebViewDiagnostics.releaseFailure(this, name, error)
    }
}
