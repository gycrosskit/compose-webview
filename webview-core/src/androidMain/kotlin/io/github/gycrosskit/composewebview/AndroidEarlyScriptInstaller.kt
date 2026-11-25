package io.github.gycrosskit.composewebview

import android.webkit.WebView
import androidx.webkit.ScriptHandler
import java.util.UUID
import java.util.WeakHashMap

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
        var performanceInstalled = false
        var documentStartReady = false
    }
    private val installations = mutableMapOf<WebView, Installation>()

    /** 供同一实例的页面通道使用真实注册结果，不能把 feature 声明当作安装成功。 */
    fun hasDocumentStartScripts(view: WebView): Boolean = installations[view]?.documentStartReady == true

    fun pageChannelToken(view: WebView): String? = if (hasDocumentStartScripts(view)) registeredPageChannelToken(view) else null

    fun injectPerformanceScript(view: WebView) {
        if (installations[view]?.performanceInstalled == true) {
            view.evaluateJavascript(ANDROID_PERFORMANCE_SCRIPT, null)
        }
    }

    /** UI 线程注册 document-start 与性能消息；旧内核通过完成回调兜底，每个原生实例安装一次并在释放时 remove。 */
    fun install(view: WebView, request: WebViewRequest) {
        AndroidDocumentStartScripts.checkUiThread()
        if (!request.settings.javaScriptEnabled || view in installations) return
        val installed = Installation()
        installations[view] = installed
        try {
            view.addJavascriptInterface(JavascriptMessageReceiver { data ->
                if (installations[view] !== installed || !view.isActiveAppWebView()) return@JavascriptMessageReceiver
                val (metric, duration) = parseWebViewPerformanceMessage(data) ?: return@JavascriptMessageReceiver
                onMetric(view, metric, duration)
            }, PERFORMANCE_MESSAGE_HANDLER)
            installed.performanceInstalled = true
            val registrar = AndroidDocumentStartScripts.resolve()
            if (registrar != null) {
                fun add(source: String, origins: Set<String>) {
                    if (installations[view] !== installed) return
                    val handler = registrar.add(view, source, origins)
                    if (installations[view] === installed) installed.handlers += handler
                    else runCatching(handler::remove)
                }
                val bridge = (if (requireDocumentToken) "window.__GY_WEBVIEW_TOKEN_REQUIRED__=true;" else "") + APP_WEB_BRIDGE_SCRIPT
                if (request.security.appBridgeEnabled && request.navigationPolicy.navigationOrigins != null) {
                    val trust = request.security.trustedOrigins
                    add(
                        "(function(){if(!(${trust.javascriptTrustExpression()}))return;$bridge})();",
                        trust.documentStartOriginRules(),
                    )
                } else if (request.canUseAppBridgeAt(request.content.initialOrigin())) {
                    add(
                        bridge,
                        setOf(WILDCARD_ORIGIN_RULE),
                    )
                }
                add(
                    ANDROID_PERFORMANCE_SCRIPT,
                    setOf(WILDCARD_ORIGIN_RULE),
                )
                val pageToken = if ((request.pageMessageChannels - request.directAndroidPageChannels()).isNotEmpty()) {
                    val token = UUID.randomUUID().toString()
                    request.androidPageChannelScript(token)?.let { source ->
                        add(source, setOf(WILDCARD_ORIGIN_RULE))
                        token
                    }
                } else null
                val rules = request.earlyScriptOriginRules()
                request.earlyScriptSource(atDocumentStart = true)?.takeIf { rules.isNotEmpty() }?.let { source ->
                    add(source, rules)
                }
                if (installations[view] === installed) {
                    installed.documentStartReady = true
                    if (pageToken != null) channelTokens[view] = pageToken
                }
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
        channelTokens.remove(view)
        // 先移除对象；旧 script handle 的清理重入不能撤销新的注册。
        if (installed.performanceInstalled) {
            runCatching { view.removeJavascriptInterface(PERFORMANCE_MESSAGE_HANDLER) }
        }
        installed.handlers.forEach { handler -> runCatching(handler::remove) }
    }

    companion object {
        private val channelTokens = WeakHashMap<WebView, String>()
        internal fun registeredPageChannelToken(view: WebView): String? = channelTokens[view]

        private const val WILDCARD_ORIGIN_RULE = "*"
        private val ANDROID_PERFORMANCE_SCRIPT =
            "(function(){if(window!==window.top)return;$WEB_VIEW_PERFORMANCE_SCRIPT})();"
    }
}
