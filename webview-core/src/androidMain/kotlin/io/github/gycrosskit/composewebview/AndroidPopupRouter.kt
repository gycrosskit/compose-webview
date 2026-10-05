package io.github.gycrosskit.composewebview

import android.os.Message
import android.webkit.WebResourceRequest
import android.webkit.WebView

/** `window.open` 只解析目标并交给中立导航回调，临时 WebView 不安装 Bridge。
 * UI 线程调用，页面退出时 release 解除全部临时实例。
 * @param request 获取当前声明式输入。
 * @param onNavigation 同步中立路由，宿主可拒绝或接管；不可阻塞 UI。
 */
class AndroidPopupRouter(
    private val request: () -> WebViewRequest,
    private val onNavigation: (WebViewNavigationRequest) -> WebViewNavigationDecision,
) {
    private val popups = mutableSetOf<WebView>()

    /** 接管 Chromium 临时弹窗并在当前页面路由，返回 false 表示拒绝；临时实例由 close/release 销毁。 */
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
            val decision = onNavigation(
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

    /** 移出持有集合后销毁指定临时弹窗，避免迟到回调再次路由。 */
    fun close(target: WebView) {
        if (!popups.remove(target)) return
        WebViewDiagnostics.markReleased(target)
        runCatching { target.stopLoading() }
        runCatching { target.webChromeClient = null }
        runCatching { target.webViewClient = android.webkit.WebViewClient() }
        runCatching { target.destroy() }
    }

    /** 页面退出时释放全部临时弹窗；所有调用位于 UI 线程。 */
    fun release() = popups.toList().forEach(::close)
}
