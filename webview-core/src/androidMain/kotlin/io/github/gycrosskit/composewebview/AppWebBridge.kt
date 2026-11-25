package io.github.gycrosskit.composewebview

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.annotation.Keep
import org.json.JSONObject
import java.util.UUID
import java.util.WeakHashMap

/** 每个物理 WebView 只服务一次页面加载；撤销后旧对象及回执都失效。 */
class AndroidPageMessageChannels(
    private val owner: WebView,
    private val request: () -> WebViewRequest,
    private val isOwner: () -> Boolean,
    documentStartInstalled: Boolean,
    private val documentStartToken: String?,
    private val onEvent: (WebViewEvent) -> Unit,
) {
    constructor(owner: WebView, request: () -> WebViewRequest, isOwner: () -> Boolean, onEvent: (WebViewEvent) -> Unit) :
        this(owner, request, isOwner, false, AndroidEarlyScriptInstaller.registeredPageChannelToken(owner), onEvent)

    constructor(owner: WebView, request: () -> WebViewRequest, isOwner: () -> Boolean,
        documentStartInstalled: Boolean, onEvent: (WebViewEvent) -> Unit) :
        this(owner, request, isOwner, documentStartInstalled, AndroidEarlyScriptInstaller.registeredPageChannelToken(owner), onEvent)

    private val initialRequest = request()
    private val initialUrl = initialRequest.content.initialOrigin()
    private val requestedChannels = initialRequest.pageMessageChannels.toSet()
    private val directChannels = if (documentStartToken == null) {
        requestedChannels - NATIVE_GEOLOCATION_CHANNEL
    } else initialRequest.directAndroidPageChannels()
    private data class Reply(val channel: String, val documentId: String?)
    private val replies = mutableMapOf<String, Reply>()
    private var active = initialRequest.canUsePageMessageChannelsAt(initialUrl) ||
        initialRequest.canUsePageMessageChannelsAt(initialUrl?.pageMessageDocumentUrl())
    private var started = false
    private val installed = mutableSetOf<String>()

    init {
        if (active) {
            val requiresBootstrap = requestedChannels - directChannels
            val secured = when {
                documentStartToken == null -> emptySet()
                documentStartInstalled -> requiresBootstrap
                else -> requiresBootstrap - NATIVE_GEOLOCATION_CHANNEL
            }
            val unavailable = requiresBootstrap - secured
            if (NATIVE_GEOLOCATION_CHANNEL in unavailable) {
                onEvent(WebViewEvent.CapabilityUnsupported(WebViewCapability.NATIVE_GEOLOCATION))
            }
            val available = directChannels + secured
            active = active && available.isNotEmpty() && isOwner() && owner.isActiveAppWebView()
            if (active) try {
                for (channel in available) install(channel, channel in directChannels)
            } catch (_: Exception) {
                revoke()
                onEvent(WebViewEvent.CapabilityUnsupported(WebViewCapability.PAGE_MESSAGE_CHANNEL))
            }
        }
    }

    private fun install(channel: String, direct: Boolean) {
        val name = if (direct) channel else securedAndroidPageChannelName(channel)
        owner.addJavascriptInterface(JavascriptMessageReceiver { raw ->
            if (!allowed()) return@JavascriptMessageReceiver
            val message = (if (direct) raw else appWebBridgePayload(raw, documentStartToken))
                ?: return@JavascriptMessageReceiver
            val separator = if (direct) -1 else message.indexOf('\u001E')
            if (!direct && separator !in 1..64) return@JavascriptMessageReceiver
            val data = message.substring(separator + 1)
            if (!isValidPageMessageData(data) || replies.size >= 128) return@JavascriptMessageReceiver
            val replyId = UUID.randomUUID().toString()
            replies[replyId] = Reply(channel, if (direct) null else message.substring(0, separator))
            onEvent(WebViewEvent.PageMessage(channel, data, replyId))
        }, name)
        installed += name
    }

    private fun allowed(): Boolean {
        if (!active || !isOwner() || !owner.isActiveAppWebView()) return false
        val current = request()
        return current.content == initialRequest.content && current.pageMessageChannels == requestedChannels &&
            current.canUsePageMessageChannelsAt(owner.url ?: initialUrl?.pageMessageDocumentUrl())
    }

    fun reply(replyId: String, data: String): Boolean {
        if (!allowed() || !isValidPageMessageData(data)) return false
        val reply = replies.remove(replyId) ?: return false
        val url = requireNotNull(initialUrl?.pageMessageDocumentUrl()).asJavascriptString()
        val name = reply.channel.asJavascriptString()
        val value = data.asJavascriptString()
        val script = if (reply.documentId == null) {
            "(function(){if(location.href!==$url)return;var channel=window[$name];" +
                "if(channel&&typeof channel.onmessage==='function')channel.onmessage({data:$value});})();"
        } else {
            val id = reply.documentId.asJavascriptString()
            "window.__GY_ANDROID_PAGE_MESSAGE_REPLY__ && window.__GY_ANDROID_PAGE_MESSAGE_REPLY__($id,$name,$value);"
        }
        return runCatching {
            owner.evaluateJavascript(script, null)
            true
        }.getOrDefault(false)
    }

    /** 新实例的第一次开始属于声明加载；任何后续开始都关闭本轮通道。 */
    fun onPageStarted(url: String?) {
        if (started || (url != null && !initialRequest.canUsePageMessageChannelsAt(url))) revoke()
        started = true
    }

    fun revoke() {
        active = false
        replies.clear()
        for (channel in installed) runCatching { owner.removeJavascriptInterface(channel) }
        installed.clear()
    }
}

/** 固定 URL 的历史验证页会在首段 inline 脚本直接发送 READY。 */
internal fun WebViewRequest.directAndroidPageChannels(): Set<String> {
    val pinned = navigationPolicy.allowedUrls?.singleOrNull()?.pageMessageDocumentUrl()
    return if (pinned != null && pinned == content.initialOrigin()?.pageMessageDocumentUrl()) {
        pageMessageChannels - NATIVE_GEOLOCATION_CHANNEL
    } else emptySet()
}

internal fun securedAndroidPageChannelName(channel: String) = "ComposeWebViewPage_" + channel

/** 受限通道在主文档内捕获 token，底层原生对象本身不提供来源信息。 */
internal fun WebViewRequest.androidPageChannelScript(token: String): String? {
    val initial = content.initialOrigin() ?: return null
    val url = initial.pageMessageDocumentUrl() ?: return null
    if (!canUsePageMessageChannelsAt(initial) && !canUsePageMessageChannelsAt(url)) return null
    val channels = pageMessageChannels - directAndroidPageChannels()
    if (channels.isEmpty()) return null
    val source = channels.joinToString("") { channel ->
        val name = channel.asJavascriptString()
        val transport = securedAndroidPageChannelName(channel).asJavascriptString()
        "install($name,$transport);"
    }
    return """
        (function(){
          if(window!==window.top || location.href!==${url.asJavascriptString()}) return;
          var token=${token.asJavascriptString()};
          var id=Date.now().toString(36)+Math.random().toString(36).slice(2);
          var channels=Object.create(null);
          function install(name,transportName) {
            var transport=window[transportName];
            if(!transport || typeof transport.postMessage!=='function') return;
            channels[name]=window[name]={onmessage:null,postMessage:function(data) {
              if(typeof data==='string') transport.postMessage(token+'\u001E'+id+'\u001E'+data);
            }};
          }
          $source
          window.__GY_ANDROID_PAGE_MESSAGE_REPLY__=function(documentId,name,data) {
            if(documentId!==id || location.href!==${url.asJavascriptString()}) return;
            var channel=channels[name];
            if(channel&&typeof channel.onmessage==='function') channel.onmessage({data:data});
          };
        })();
    """.trimIndent()
}

internal fun String.asJavascriptString(): String =
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
