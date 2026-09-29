package io.github.gycrosskit.composewebview

import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

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
) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
    WebViewDiagnostics.bridge(event = "install", view = this)
    WebViewCompat.addWebMessageListener(this, APP_WEB_BRIDGE_TRANSPORT, setOf("*")) {
        sourceView, message, sourceOrigin, isMainFrame, _ ->
        if (!sourceView.isActiveAppWebView() ||
            !request().canReceiveAppBridgeMessage(sourceOrigin.toString(), isMainFrame) ||
            message.type != WebMessageCompat.TYPE_STRING
        ) return@addWebMessageListener
        dispatchAppWebBridgeMessage(message.data ?: return@addWebMessageListener, onMessage)
    }
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
        window.JSAndroidBridge = {
          handleJSBridgeMessage: function(handlerName, data) {
            transport.postMessage(String(handlerName) + '\u001F' +
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
