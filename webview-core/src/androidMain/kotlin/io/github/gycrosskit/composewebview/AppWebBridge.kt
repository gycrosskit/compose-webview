package io.github.gycrosskit.composewebview

import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.JavaScriptReplyProxy
import java.util.UUID

/** 每个物理 WebView 只服务一次页面加载；撤销后不能同名重装，旧 JS 对象会按名称路由新 listener。 */
class AndroidPageMessageChannels(
    private val owner: WebView,
    private val request: () -> WebViewRequest,
    private val isOwner: () -> Boolean,
    private val onEvent: (WebViewEvent) -> Unit,
) {
    private val initialRequest = request()
    private val initialUrl = initialRequest.content.initialOrigin()
    private val channels = initialRequest.pageMessageChannels.toSet()
    private val proxies = mutableMapOf<String, JavaScriptReplyProxy>()
    private val replies = mutableMapOf<String, JavaScriptReplyProxy>()
    private var active = initialRequest.canUsePageMessageChannelsAt(initialUrl)
    private var started = false
    private val installed = mutableSetOf<String>()

    init {
        if (active && !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            active = false
            onEvent(WebViewEvent.CapabilityUnsupported(WebViewCapability.PAGE_MESSAGE_CHANNEL))
        }
        if (active) try {
            val origin = requireNotNull(initialUrl?.toHttpOrigin())
            val rules = buildSet {
                add("${origin.scheme}://${origin.host}:${origin.port}")
                if (!origin.host.startsWith('[')) add("${origin.scheme}://${origin.host}.:${origin.port}")
            }
            for (channel in channels) {
                WebViewCompat.addWebMessageListener(owner, channel, rules) {
                    view, message, origin, mainFrame, proxy ->
                    if (view !== owner || !allowed() || !mainFrame ||
                        initialUrl?.toHttpOrigin() != origin.toString().toHttpOrigin() ||
                        message.type != WebMessageCompat.TYPE_STRING
                    ) return@addWebMessageListener
                    val data = message.data ?: return@addWebMessageListener
                    if (!isValidPageMessageData(data)) return@addWebMessageListener
                    val previous = proxies[channel]
                    // 一个主文档/通道只有一个 proxy；不同 proxy 表示文档身份已改变。
                    if (previous != null && previous !== proxy) {
                        revoke()
                        return@addWebMessageListener
                    }
                    if (replies.size >= 128) return@addWebMessageListener
                    proxies[channel] = proxy
                    val replyId = UUID.randomUUID().toString()
                    replies[replyId] = proxy
                    onEvent(WebViewEvent.PageMessage(channel, data, replyId))
                }
                installed += channel
            }
        } catch (_: Exception) {
            revoke()
            onEvent(WebViewEvent.CapabilityUnsupported(WebViewCapability.PAGE_MESSAGE_CHANNEL))
        }
    }

    private fun allowed(): Boolean {
        if (!active || !isOwner() || !owner.isActiveAppWebView()) return false
        val current = request()
        return current.content == initialRequest.content && current.pageMessageChannels == channels &&
            current.canUsePageMessageChannelsAt(initialUrl) &&
            (owner.url == null || current.canUsePageMessageChannelsAt(owner.url))
    }

    fun reply(replyId: String, data: String): Boolean {
        if (!allowed() || !isValidPageMessageData(data)) return false
        val proxy = replies.remove(replyId) ?: return false
        return runCatching { proxy.postMessage(data); true }.getOrDefault(false)
    }

    /** 新实例的第一次开始属于声明加载；任何后续开始都关闭本轮通道。 */
    fun onPageStarted(url: String?) {
        if (started || (url != null && !initialRequest.canUsePageMessageChannelsAt(url))) revoke()
        started = true
    }

    fun revoke() {
        active = false
        proxies.clear()
        replies.clear()
        for (channel in installed) runCatching { WebViewCompat.removeWebMessageListener(owner, channel) }
        installed.clear()
    }
}

/** 只解析已通过真实 frame 来源校验的消息；业务 handler 留在页面。 */
internal fun dispatchAppWebBridgeMessage(
    raw: String,
    onMessage: (handlerName: String, data: String) -> Unit,
) {
    val message = parseAppWebBridgeMessage(raw) ?: return
    WebViewDiagnostics.bridge(event = "message", handlerName = message.handlerName)
    onMessage(message.handlerName, message.data)
}

/** AndroidX 提供原始 frame 来源；不能用 WebView.url 代表消息来源。 */
fun WebView.installAppWebBridge(
    request: () -> WebViewRequest,
    onMessage: (handlerName: String, data: String) -> Unit,
) = installAppWebBridge(request, documentToken = { null }, onMessage = onMessage)

/** Kuikly 在主文档提交后绑定 nonce；旧同源文档排队的消息不能进入新文档。 */
fun WebView.installAppWebBridge(
    request: () -> WebViewRequest,
    documentToken: () -> String?,
    onMessage: (handlerName: String, data: String) -> Unit,
) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
    WebViewDiagnostics.bridge(event = "install", view = this)
    WebViewCompat.addWebMessageListener(this, APP_WEB_BRIDGE_TRANSPORT, setOf("*")) {
        sourceView, message, sourceOrigin, isMainFrame, _ ->
        if (sourceView !== this || !sourceView.isActiveAppWebView() ||
            !request().canReceiveAppBridgeMessage(sourceOrigin.toString(), isMainFrame) ||
            message.type != WebMessageCompat.TYPE_STRING
        ) return@addWebMessageListener
        val raw = message.data ?: return@addWebMessageListener
        val payload = appWebBridgePayload(raw, documentToken()) ?: return@addWebMessageListener
        dispatchAppWebBridgeMessage(payload, onMessage)
    }
}

internal fun appWebBridgePayload(raw: String, documentToken: String?): String? {
    if (documentToken == null) return raw
    val prefix = "$documentToken\u001E"
    return raw.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
}

/** 页面释放时移除 Bridge，缩短 Android 接口在 WebView 中的暴露周期。 */
fun WebView.removeAppWebBridge() {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
    WebViewDiagnostics.bridge(event = "remove", view = this)
    runCatching { WebViewCompat.removeWebMessageListener(this, APP_WEB_BRIDGE_TRANSPORT) }
}

/**
 * 注入唯一的 H5 兼容脚本。
 *
 * 脚本只补齐历史页面使用的 `WebViewJavascriptBridge.callHandler` API；视频事件监听、商城响应式修正
 * 等页面能力必须由业务脚本单独注入，不能继续复制本段基础协议。
 */
fun WebView.injectAppWebBridge() {
    if (!isActiveAppWebView() || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
        WebViewDiagnostics.ignoredCallback(this, "bridge-inject")
        return
    }
    WebViewDiagnostics.bridge(event = "inject", view = this)
    evaluateJavascript(APP_WEB_BRIDGE_SCRIPT, null)
}

private const val APP_WEB_BRIDGE_TRANSPORT = "ComposeWebViewBridgeTransport"
/** H5 已使用的两个入口属于兼容协议，不能随 Kotlin 包名调整。 */
internal const val APP_WEB_BRIDGE_SCRIPT = """
    (function() {
      try {
        if (window !== window.top) return;
        var transport = window.ComposeWebViewBridgeTransport;
        if (!transport || typeof transport.postMessage !== 'function') return;
        function publish(raw) {
          var token = window.__GY_WEBVIEW_DOCUMENT_TOKEN__;
          transport.postMessage(token ? String(token) + '\u001E' + raw : raw);
        }
        window.GYWebViewBridge = {
          postMessage: function(handlerName, data) {
            var value = (data === undefined || data === null) ? '{}' :
              (typeof data === 'object' ? JSON.stringify(data) : String(data));
            publish(String(handlerName) + '\u001F' + value);
          }
        };
        window.JSAndroidBridge = {
          handleJSBridgeMessage: function(handlerName, data) {
            publish(String(handlerName) + '\u001F' +
              String(data === undefined || data === null ? '{}' : data));
          }
        };
        if (typeof WebViewJavascriptBridge === 'undefined') {
          window.WebViewJavascriptBridge = {
            callHandler: function(handlerName, data, responseCallback) {
              var dataStr = (data === undefined || data === null) ? '{}' :
                (typeof data === 'object' ? JSON.stringify(data) : String(data));
              window.JSAndroidBridge.handleJSBridgeMessage(handlerName, dataStr);
            },
            registerHandler: function(handlerName, callback) {}
          };
        }
      } catch (e) { console.log('bridge inject error: ' + e); }
    })();
"""
