package io.github.gycrosskit.composewebview

import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** 在首个 load 前安装性能采集和业务早期脚本，并在实例释放时解除消息入口。
 * @param onMetric 在 UI 回调中接收指标，第三个参数为非负导航耗时毫秒。
 */
class AndroidEarlyScriptInstaller(
    private val onMetric: (WebView, WebViewPerformanceMetric, Long) -> Unit,
) {
    private val handlers = mutableMapOf<WebView, List<ScriptHandler>>()
    private val messageListeners = mutableSetOf<WebView>()

    /** UI 线程注册 document-start 与性能消息；旧内核通过完成回调兜底，每个原生实例安装一次并在释放时 remove。 */
    fun install(view: WebView, request: WebViewRequest) {
        if (!request.settings.javaScriptEnabled) return
        val installedHandlers = mutableListOf<ScriptHandler>()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                view,
                PERFORMANCE_MESSAGE_HANDLER,
                setOf(WILDCARD_ORIGIN_RULE),
            ) { sourceView, message, _, isMainFrame, _ ->
                if (!isMainFrame || !sourceView.isActiveAppWebView()) return@addWebMessageListener
                val (metric, duration) = parseWebViewPerformanceMessage(message.data.orEmpty())
                    ?: return@addWebMessageListener
                onMetric(sourceView, metric, duration)
            }
            messageListeners += view
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                request.canUseAppBridgeAt(request.content.initialOrigin())
            ) {
                installedHandlers += WebViewCompat.addDocumentStartJavaScript(
                    view,
                    APP_WEB_BRIDGE_SCRIPT,
                    setOf(WILDCARD_ORIGIN_RULE),
                )
            }
            installedHandlers += WebViewCompat.addDocumentStartJavaScript(
                view,
                WEB_VIEW_PERFORMANCE_SCRIPT,
                setOf(WILDCARD_ORIGIN_RULE),
            )
            val rules = request.earlyScriptOriginRules()
            request.earlyScriptSource()?.takeIf { rules.isNotEmpty() }?.let { source ->
                installedHandlers += WebViewCompat.addDocumentStartJavaScript(view, source, rules)
            }
        }
        handlers[view] = installedHandlers
    }

    /** UI 线程撤销此实例的脚本与消息监听；释放/重建时调用。 */
    fun remove(view: WebView) {
        handlers.remove(view).orEmpty().forEach { handler -> runCatching(handler::remove) }
        if (messageListeners.remove(view) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        ) {
            runCatching { WebViewCompat.removeWebMessageListener(view, PERFORMANCE_MESSAGE_HANDLER) }
        }
    }

    private companion object {
        const val WILDCARD_ORIGIN_RULE = "*"
    }
}
