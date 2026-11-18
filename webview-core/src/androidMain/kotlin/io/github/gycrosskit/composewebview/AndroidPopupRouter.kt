package io.github.gycrosskit.composewebview

import android.os.Message
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import java.io.ByteArrayInputStream

/** `window.open` 只解析目标并交给中立导航回调，临时 WebView 不安装 Bridge。
 * UI 线程调用，页面退出时 release 解除全部临时实例。
 * @param request 获取当前声明式输入。
 * @param onNavigation 同步中立路由，宿主可拒绝或接管；不可阻塞 UI。
 */
class AndroidPopupRouter(
    private val request: () -> WebViewRequest,
    private val onNavigationBlocked: (WebViewNavigationRequest) -> Unit,
    private val onNavigation: (WebViewNavigationRequest) -> WebViewNavigationDecision,
) {
    constructor(
        request: () -> WebViewRequest,
        onNavigation: (WebViewNavigationRequest) -> WebViewNavigationDecision,
    ) : this(request, {}, onNavigation)

    private val popups = mutableSetOf<WebView>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 接管 Chromium 临时弹窗并在当前页面路由，返回 false 表示拒绝；临时实例由 close/release 销毁。 */
    fun createWindow(parent: WebView, hasUserGesture: Boolean, resultMessage: Message): Boolean {
        if (!parent.isActiveAppWebView() || !parent.isShown) return false
        val transport = resultMessage.obj as? WebView.WebViewTransport ?: return false
        lateinit var popup: WebView
        var routed = false
        fun route(url: String?, method: String?) {
            val target = url?.takeUnless { it.isBlank() || it == "about:blank" } ?: return
            if (routed || popup !in popups || !parent.isActiveAppWebView() || !parent.isShown) return
            val current = request()
            val navigation = WebViewNavigationRequest(
                url = target,
                isMainFrame = true,
                hasUserGesture = hasUserGesture,
                target = WebViewNavigationTarget.NEW_WINDOW,
            )
            // 旧回调未提供方法，等待 IO 回调确认后再处理启用新策略的弹窗。
            if (method == null && current.navigationPolicy.userLinkPolicy != WebViewUserLinkPolicy.DEFAULT) return
            routed = true
            if (method != null && method != "GET") {
                onNavigationBlocked(navigation)
                close(popup)
                return
            }
            fun allowsPopup(request: WebViewRequest): Boolean =
                request.allowsHttpRequest(target) && !request.shouldBlockMainFrameNavigation(target, true) &&
                    (request.navigationPolicy.navigationOrigins == null || request.allowsActiveUserLink(navigation))
            val allowed = allowsPopup(current)
            val decision = onNavigation(navigation)
            if (popup in popups && parent.isActiveAppWebView() && parent.isShown && allowed &&
                allowsPopup(request()) && decision == WebViewNavigationDecision.ALLOW) {
                parent.loadUrl(target)
            }
            // 宿主打开新页时可能同步隐藏父页，临时 WebView 仍由本次请求释放。
            close(popup)
        }
        popup = WebView(parent.context).apply {
            WebViewDiagnostics.created(this, "popup", sensitiveDiagnostics = WebViewDiagnostics.isSensitive(parent))
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    route(request.url.toString(), request.method)
                    return true
                }

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    route(url, null)
                    return true
                }

                override fun shouldInterceptRequest(view: WebView, resource: WebResourceRequest): WebResourceResponse? {
                    if (!resource.isForMainFrame) return null
                    val url = resource.url.toString()
                    val method = resource.method
                    // 临时窗口只解析目标；POST body 不可取得，不能先联网或偷换成 parent GET。
                    mainHandler.post {
                        if (popup !in popups || routed || !parent.isActiveAppWebView() || !parent.isShown) return@post
                        route(url, method)
                    }
                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }
            }
        }
        popups += popup
        transport.webView = popup
        resultMessage.sendToTarget()
        return true
    }

    /** 移出持有集合后销毁指定临时弹窗，避免迟到回调再次路由。 */
    fun close(target: WebView) {
        if (!popups.remove(target)) return
        WebViewDiagnostics.markReleased(target)
        runCatching { target.stopLoading() }
        runCatching { target.webChromeClient = null }
        runCatching { target.webViewClient = android.webkit.WebViewClient() }
        runCatching { target.destroy() }
    }

    /** 父实例提升保护时，已存在的临时窗口也必须先于释放日志脱敏。 */
    fun protectSensitiveDiagnostics() = popups.forEach(WebViewDiagnostics::protectSensitiveDiagnostics)

    /** 页面退出时释放全部临时弹窗；所有调用位于 UI 线程。 */
    fun release() = popups.toList().forEach(::close)
}
