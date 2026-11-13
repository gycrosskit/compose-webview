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
    /** Kuikly 的消息必须绑定当前文档；普通原生调用方沿用无 token 合同。 */
    var requireDocumentToken: Boolean = false
    private class Installation {
        val handlers = mutableListOf<ScriptHandler>()
        var messageListener = false
        var documentStartReady = false
    }
    private val installations = mutableMapOf<WebView, Installation>()

    /** 供同一实例的页面通道使用真实注册结果，不能把 feature 声明当作安装成功。 */
    fun hasDocumentStartScripts(view: WebView): Boolean = installations[view]?.documentStartReady == true

    /** UI 线程注册 document-start 与性能消息；旧内核通过完成回调兜底，每个原生实例安装一次并在释放时 remove。 */
    fun install(view: WebView, request: WebViewRequest) {
        AndroidDocumentStartScripts.checkUiThread()
        if (!request.settings.javaScriptEnabled || view in installations) return
        val installed = Installation()
        installations[view] = installed
        try {
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
                installed.messageListener = true
            }
            val registrar = AndroidDocumentStartScripts.resolve()
            if (registrar != null) {
                fun add(source: String, origins: Set<String>) {
                    if (installations[view] !== installed) return
                    val handler = registrar.add(view, source, origins)
                    if (installations[view] === installed) installed.handlers += handler
                    else runCatching(handler::remove)
                }
                val bridge = (if (requireDocumentToken) "window.__GY_WEBVIEW_TOKEN_REQUIRED__=true;" else "") + APP_WEB_BRIDGE_SCRIPT
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                    request.security.appBridgeEnabled && request.navigationPolicy.navigationOrigins != null) {
                    val trust = request.security.trustedOrigins
                    add(
                        "(function(){if(!(${trust.javascriptTrustExpression()}))return;$bridge})();",
                        trust.documentStartOriginRules(),
                    )
                } else if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                    request.canUseAppBridgeAt(request.content.initialOrigin())) {
                    add(
                        bridge,
                        setOf(WILDCARD_ORIGIN_RULE),
                    )
                }
                add(
                    WEB_VIEW_PERFORMANCE_SCRIPT,
                    setOf(WILDCARD_ORIGIN_RULE),
                )
                val rules = request.earlyScriptOriginRules()
                request.earlyScriptSource(atDocumentStart = true)?.takeIf { rules.isNotEmpty() }?.let { source ->
                    add(source, rules)
                }
                if (installations[view] === installed) installed.documentStartReady = true
            }
        } catch (_: Exception) {
            if (installations[view] === installed) remove(view)
        } catch (_: LinkageError) {
            if (installations[view] === installed) remove(view)
        }
    }

    /** UI 线程撤销此实例的脚本与消息监听；释放/重建时调用。 */
    fun remove(view: WebView) {
        AndroidDocumentStartScripts.checkUiThread()
        val installed = installations.remove(view) ?: return
        // 名称路由的 listener 先移除；旧 script handle 的清理重入不能再撤销新 listener。
        if (installed.messageListener) {
            runCatching { WebViewCompat.removeWebMessageListener(view, PERFORMANCE_MESSAGE_HANDLER) }
        }
        installed.handlers.forEach { handler -> runCatching(handler::remove) }
    }

    private companion object {
        const val WILDCARD_ORIGIN_RULE = "*"
    }
}
