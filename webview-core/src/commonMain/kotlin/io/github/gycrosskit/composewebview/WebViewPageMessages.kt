package io.github.gycrosskit.composewebview

import io.ktor.http.Url
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** 具名通道只属于初始完整页面，既不扩张到同源其他路径，也不授予高权限 Bridge。 */
fun WebViewRequest.canUsePageMessageChannelsAt(url: String?): Boolean =
    settings.javaScriptEnabled && pageMessageChannels.isNotEmpty() &&
        content.initialOrigin()?.toHttpOrigin() != null &&
        url?.pageMessageDocumentUrl() == content.initialOrigin()?.pageMessageDocumentUrl() &&
        url != null && allowsNavigation(WebViewNavigationRequest(url, true, false))

/** 仅规范化 authority 和空路径；path/query/fragment 保留原字节，不能退化成同源授权。 */
internal fun String.pageMessageDocumentUrl(): String? = runCatching {
    if (toHttpOrigin() == null) return null
    val parsed = Url(this)
    val suffix = substringAfter("://").dropWhile { it !in "/?#" }
    val path = if (suffix.startsWith('/')) suffix else "/$suffix"
    val port = if (parsed.port == parsed.protocol.defaultPort) "" else ":${parsed.port}"
    "${parsed.protocol.name.lowercase()}://${parsed.host.lowercase()}$port$path"
}.getOrNull()

/** 两种平台的原生入站与回复都按 UTF-8 字节限制消息，正文不参与日志。 */
fun isValidPageMessageData(data: String): Boolean =
    data.length <= 65536 && data.encodeToByteArray().size <= 65536

internal fun isValidPageMessageChannel(channel: String): Boolean =
    channel.matches(Regex("[a-zA-Z][a-zA-Z0-9_]{0,79}")) && channel !in RESERVED_PAGE_CHANNELS &&
        !channel.startsWith("ComposeWebView") && !channel.startsWith("GYWebView")

private val RESERVED_PAGE_CHANNELS = setOf(
    "window", "self", "top", "parent", "frames", "document", "location", "navigator", "webkit",
    "globalThis", "console", "history", "performance", "JSON", "Object", "Array", "Function",
    "Promise", "eval", "undefined", "NaN", "Infinity", "onmessage", "postMessage", "name",
    "constructor", "prototype", "JSAndroidBridge", "WebViewJavascriptBridge",
)

/** iOS document-start 安装；nonce 被每个文档闭包捕获，不动态读取后续文档的 token。 */
fun WebViewRequest.pageMessageScript(documentToken: String): String? {
    if (!canUsePageMessageChannelsAt(content.initialOrigin()) &&
        !canUsePageMessageChannelsAt(content.initialOrigin()?.pageMessageDocumentUrl())) return null
    return WEB_VIEW_PAGE_MESSAGE_SCRIPT
        .replace("__GY_PAGE_TOKEN_JSON__", JsonPrimitive(documentToken).toString())
        .replace("__GY_PAGE_CHANNELS_JSON__", JsonArray(pageMessageChannels.map(::JsonPrimitive)).toString())
        .replace("__GY_PAGE_URL_JSON__", JsonPrimitive(requireNotNull(content.initialOrigin())).toString())
}

/** 排队回复再次核对文档 nonce，不能把上一文档的回复投递给新页面。 */
fun pageMessageReplyScript(documentToken: String, channel: String, data: String): String =
    "(function(){var reply=window.__GY_WEBVIEW_PAGE_MESSAGE_REPLY__;return typeof reply==='function'&&reply(" +
        listOf(documentToken, channel, data).joinToString(",") { JsonPrimitive(it).toString() } + ");})();"

/** 由 ios/generate-scripts.py 同步到原生 Pod，协议只维护一份。 */
val WEB_VIEW_PAGE_MESSAGE_SCRIPT = """
    (function(token, channels, url) {
      try {
        var parsed = new URL(url);
        var tail = url.substring(url.indexOf('://') + 3).replace(/^[^/?#]*/, '');
        url = parsed.protocol + '//' + parsed.host + (tail[0] === '/' ? tail : '/' + tail);
      } catch (_) { return; }
      if (window !== window.top || location.href !== url) return;
      var handler = window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.ComposeWebViewPageMessage;
      if (!handler || typeof handler.postMessage !== 'function') return;
      var entries = Object.create(null);
      channels.forEach(function(channel) {
        var entry = {onmessage: null, postMessage: function(data) {
          if (typeof data !== 'string') return;
          handler.postMessage({token: token, channel: channel, data: data});
        }};
        entries[channel] = entry;
        Object.defineProperty(window, channel, {value: entry, configurable: true});
      });
      window.__GY_WEBVIEW_PAGE_MESSAGE_REPLY__ = function(expectedToken, channel, data) {
        if (token !== expectedToken || window !== window.top || location.href !== url || typeof data !== 'string') return false;
        var entry = entries[channel];
        if (!entry || typeof entry.onmessage !== 'function') return false;
        entry.onmessage({data: data});
        return true;
      };
    })(__GY_PAGE_TOKEN_JSON__, __GY_PAGE_CHANNELS_JSON__, __GY_PAGE_URL_JSON__);
""".trimIndent()
