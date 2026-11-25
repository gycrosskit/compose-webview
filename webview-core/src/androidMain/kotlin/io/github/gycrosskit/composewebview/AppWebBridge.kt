package io.github.gycrosskit.composewebview

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.annotation.Keep
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.UUID
import java.util.WeakHashMap

/** 每个物理 WebView 只服务一次页面加载；撤销后不能同名重装，旧 JS 对象会按名称路由新 listener。 */
class AndroidPageMessageChannels private constructor(
    private val owner: WebView,
    private val request: () -> WebViewRequest,
    private val isOwner: () -> Boolean,
    private val onEvent: (WebViewEvent) -> Unit,
    documentStartInstalled: Boolean?,
) {
    /** 原通道无需早期脚本；原生定位须由下方重载确认本实例已完成注册。 */
    constructor(owner: WebView, request: () -> WebViewRequest, isOwner: () -> Boolean, onEvent: (WebViewEvent) -> Unit) :
        this(owner, request, isOwner, onEvent, null)

    constructor(owner: WebView, request: () -> WebViewRequest, isOwner: () -> Boolean,
        documentStartInstalled: Boolean, onEvent: (WebViewEvent) -> Unit) :
        this(owner, request, isOwner, onEvent, documentStartInstalled)

    private val initialRequest = request()
    private val initialUrl = initialRequest.content.initialOrigin()
    private val requestedChannels = initialRequest.pageMessageChannels.toSet()
    private var channels = requestedChannels
    private val proxies = mutableMapOf<String, JavaScriptReplyProxy>()
    private val replies = mutableMapOf<String, (String) -> Unit>()
    private var active = initialRequest.canUsePageMessageChannelsAt(initialUrl) ||
        initialRequest.canUsePageMessageChannelsAt(initialUrl?.pageMessageDocumentUrl())
    private var started = false
    private var useJavascriptInterface = false
    private val installed = mutableSetOf<String>()

    init {
        if (active && !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            // 验证页已将导航限定到单个完整 URL。定位仍需要真正的 document-start。
            val pinnedUrl = initialRequest.navigationPolicy.allowedUrls?.singleOrNull()?.pageMessageDocumentUrl()
            useJavascriptInterface = pinnedUrl != null && pinnedUrl == initialUrl?.pageMessageDocumentUrl()
            channels = if (useJavascriptInterface) channels - NATIVE_GEOLOCATION_CHANNEL else emptySet()
            if (channels.isEmpty()) {
                active = false
                onEvent(WebViewEvent.CapabilityUnsupported(WebViewCapability.PAGE_MESSAGE_CHANNEL))
            } else if (NATIVE_GEOLOCATION_CHANNEL in requestedChannels) {
                onEvent(WebViewEvent.CapabilityUnsupported(WebViewCapability.NATIVE_GEOLOCATION))
            }
        }
        if (active && NATIVE_GEOLOCATION_CHANNEL in channels &&
            initialRequest.scripts.any { it.injectionTime == WebViewScriptInjectionTime.DOCUMENT_START_REQUIRED } &&
            documentStartInstalled != true) {
            channels = channels - NATIVE_GEOLOCATION_CHANNEL
            onEvent(WebViewEvent.CapabilityUnsupported(WebViewCapability.NATIVE_GEOLOCATION))
            if (channels.isEmpty() || !isOwner() || !owner.isActiveAppWebView()) active = false
        }
        if (active) try {
            if (useJavascriptInterface) installJavascriptInterfaces() else installWebMessageListeners()
        } catch (_: Exception) {
            revoke()
            onEvent(WebViewEvent.CapabilityUnsupported(WebViewCapability.PAGE_MESSAGE_CHANNEL))
        }
    }

    private fun installWebMessageListeners() {
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
                proxies[channel] = proxy
                emitMessage(channel, data, proxy::postMessage)
            }
            installed += channel
        }
    }

    private fun installJavascriptInterfaces() {
        val url = requireNotNull(initialUrl?.pageMessageDocumentUrl()).asJavascriptString()
        for (channel in channels) {
            val name = channel.asJavascriptString()
            owner.addJavascriptInterface(JavascriptMessageReceiver { data ->
                if (!allowed() || !isValidPageMessageData(data)) return@JavascriptMessageReceiver
                emitMessage(channel, data) { response ->
                    val value = response.asJavascriptString()
                    owner.evaluateJavascript(
                        "(function(){if(location.href!==$url)return;var channel=window[$name];" +
                            "if(channel&&typeof channel.onmessage==='function')channel.onmessage({data:$value});})();",
                        null,
                    )
                }
            }, channel)
            installed += channel
        }
    }

    private fun emitMessage(channel: String, data: String, reply: (String) -> Unit) {
        if (replies.size >= 128) return
        val replyId = UUID.randomUUID().toString()
        replies[replyId] = reply
        onEvent(WebViewEvent.PageMessage(channel, data, replyId))
    }

    private fun allowed(): Boolean {
        if (!active || !isOwner() || !owner.isActiveAppWebView()) return false
        val current = request()
        return current.content == initialRequest.content && current.pageMessageChannels == requestedChannels &&
            current.canUsePageMessageChannelsAt(owner.url ?: initialUrl?.pageMessageDocumentUrl())
    }

    fun reply(replyId: String, data: String): Boolean {
        if (!allowed() || !isValidPageMessageData(data)) return false
        val submit = replies.remove(replyId) ?: return false
        return runCatching { submit(data); true }.getOrDefault(false)
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
        for (channel in installed) runCatching {
            if (useJavascriptInterface) owner.removeJavascriptInterface(channel)
            else WebViewCompat.removeWebMessageListener(owner, channel)
        }
        installed.clear()
    }
}

private fun String.asJavascriptString(): String =
    JSONObject.quote(this).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

internal class JavascriptMessageReceiver(private val receive: (String) -> Unit) {
    @Keep
    @JavascriptInterface
    fun postMessage(data: String?) {
        if (data != null) mainHandler.post { receive(data) }
    }

    private companion object {
        val mainHandler = Handler(Looper.getMainLooper())
    }
}

/** 解析组件信封，业务 handler 留在页面。 */
internal fun dispatchAppWebBridgeMessage(
    raw: String,
    onMessage: (handlerName: String, data: String) -> Unit,
) {
    val message = parseAppWebBridgeMessage(raw) ?: return
    WebViewDiagnostics.bridge(event = "message")
    onMessage(message.handlerName, message.data)
}

/** 在首个 load 前注册，JS 包装脚本在当前主文档内初始化。 */
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
    val registration = Any()
    addJavascriptInterface(JavascriptMessageReceiver { raw ->
        if (appBridgeRegistrations[this] !== registration || !isActiveAppWebView() ||
            !request().canUseAppBridgeAt(url)
        ) return@JavascriptMessageReceiver
        val payload = appWebBridgePayload(raw, documentToken()) ?: return@JavascriptMessageReceiver
        dispatchAppWebBridgeMessage(payload, onMessage)
    }, APP_WEB_BRIDGE_TRANSPORT)
    appBridgeRegistrations[this] = registration
    WebViewDiagnostics.bridge(event = "install", view = this)
}

internal fun appWebBridgePayload(raw: String, documentToken: String?): String? {
    if (documentToken == null) return raw
    val prefix = "$documentToken\u001E"
    return raw.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
}

/** 页面释放时移除 Bridge，缩短 Android 接口在 WebView 中的暴露周期。 */
fun WebView.removeAppWebBridge() {
    if (appBridgeRegistrations.remove(this) == null) return
    WebViewDiagnostics.bridge(event = "remove", view = this)
    runCatching { removeJavascriptInterface(APP_WEB_BRIDGE_TRANSPORT) }
}

/**
 * 注入唯一的 H5 兼容脚本。
 *
 * 脚本只补齐历史页面使用的 `WebViewJavascriptBridge.callHandler` API；视频事件监听、商城响应式修正
 * 等页面能力必须由业务脚本单独注入，不能继续复制本段基础协议。
 */
fun WebView.injectAppWebBridge() {
    if (!isActiveAppWebView() || !hasAppWebBridge()) {
        WebViewDiagnostics.ignoredCallback(this, "bridge-inject")
        return
    }
    WebViewDiagnostics.bridge(event = "inject", view = this)
    evaluateJavascript(APP_WEB_BRIDGE_SCRIPT, null)
}

fun WebView.hasAppWebBridge(): Boolean = this in appBridgeRegistrations

private val appBridgeRegistrations = WeakHashMap<WebView, Any>()
private const val APP_WEB_BRIDGE_TRANSPORT = "ComposeWebViewBridgeTransport"
/** H5 已使用的两个入口属于兼容协议，不能随 Kotlin 包名调整。 */
internal const val APP_WEB_BRIDGE_SCRIPT = """
    (function() {
      try {
        if (window !== window.top) return;
        var transport = window.ComposeWebViewBridgeTransport;
        if (!transport || typeof transport.postMessage !== 'function') return;
        function announceLegacyReady() {
          if (window.__GY_WEBVIEW_LEGACY_READY__ ||
              (window.__GY_WEBVIEW_TOKEN_REQUIRED__ && !window.__GY_WEBVIEW_DOCUMENT_TOKEN__)) return;
          var bridge = window.WebViewJavascriptBridge;
          if (!bridge || typeof bridge.callHandler !== 'function') return;
          window.__GY_WEBVIEW_LEGACY_READY__ = true;
          document.dispatchEvent(new Event('WebViewJavascriptBridgeReady'));
        }
        var pending = window.__GY_WEBVIEW_BRIDGE_PENDING__;
        if (!pending) {
          pending = {messages: [], size: 0};
          window.__GY_WEBVIEW_BRIDGE_PENDING__ = pending;
          window.__GY_WEBVIEW_BRIDGE_TOKEN_READY__ = function(deliver) {
            var messages = pending.messages;
            pending.messages = []; pending.size = 0;
            var token = window.__GY_WEBVIEW_DOCUMENT_TOKEN__;
            if (deliver && token) for (var i = 0; i < messages.length; i++) {
              try { transport.postMessage(String(token) + '\u001E' + messages[i]); } catch (_) {}
            }
            if (deliver) announceLegacyReady();
          };
        }
        function publish(raw) {
          var token = window.__GY_WEBVIEW_DOCUMENT_TOKEN__;
          if (!token && window.__GY_WEBVIEW_TOKEN_REQUIRED__) {
            if (pending.messages.length < 128 && pending.size + raw.length <= 65536) {
              pending.messages.push(raw); pending.size += raw.length;
            }
            return;
          }
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
        announceLegacyReady();
      } catch (e) { console.log('bridge inject error'); }
    })();
"""
