package io.github.gycrosskit.composewebview

import io.ktor.http.Url

/**
 * 当前主框架是否允许安装应用 Bridge。
 *
 * Android 和 iOS 必须复用同一判断，避免一端只检查能力开关、另一端同时检查可信来源。
 */
fun WebViewRequest.canUseAppBridgeAt(url: String?): Boolean =
    when {
        security.appBridgeEnabled -> security.trustedOrigins.isTrusted(url)
        security.pageBridgeEnabled -> content.initialOrigin().sameHttpOriginAs(url)
        else -> false
    }

/** Bridge 消息必须来自当前请求允许的主框架，子框架即使同源也不能调用应用能力。 */
fun WebViewRequest.canReceiveAppBridgeMessage(sourceUrl: String?, isMainFrame: Boolean): Boolean =
    isMainFrame && canUseAppBridgeAt(sourceUrl)

/**
 * Bridge 开启后，主框架只能停留在对应来源；子资源和 iframe 仍由各自策略处理。
 */
fun WebViewRequest.shouldBlockMainFrameNavigation(
    url: String?,
    isMainFrame: Boolean,
): Boolean = isMainFrame &&
    (security.appBridgeEnabled || security.pageBridgeEnabled) &&
    !canUseAppBridgeAt(url)

/** 可信限定脚本复用手动执行的 JavaScript 开关及来源门禁，不授予初始页面高权限。 */
fun WebViewRequest.canInject(script: WebViewScript, url: String?): Boolean =
    !script.onlyForTrustedMainFrame || canEvaluateJavascriptAt(url)

/** URL 页面以自身为初始来源，内联 HTML 只能使用显式 baseUrl 建立来源。 */
fun WebViewContent.initialOrigin(): String? = when (this) {
    is WebViewContent.Url -> url
    is WebViewContent.Html -> baseUrl
}

/** URL 和 HTML 使用同一空内容语义，保证双端首次加载与 reload 的结果一致。 */
fun WebViewContent.isBlankWebViewContent(): Boolean = when (this) {
    is WebViewContent.Url -> url.isBlank()
    is WebViewContent.Html -> html.isBlank()
}

/** 低权限页面 Bridge 允许历史 HTTP，但只能留在初始页面的精确来源。 */
private fun String?.sameHttpOriginAs(other: String?): Boolean {
    val source = this?.toHttpOrigin() ?: return false
    return source == other?.toHttpOrigin()
}

internal fun String.toHttpOrigin(): WebViewHttpOrigin? = runCatching {
    val value = trim()
    if (value.any { it <= ' ' || it == '\u007f' || it == '\\' }) return null
    val parsed = Url(value)
    val authority = value.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    val scheme = parsed.protocol.name.lowercase()
    val rawHost = parsed.host.lowercase()
    if (rawHost.endsWith("..") || (rawHost.startsWith('[') && rawHost.endsWith('.'))) return null
    val parsedHost = rawHost.removeSuffix(".")
    val host = if (parsedHost.startsWith("[")) canonicalIpv6Host(parsedHost) ?: return null else parsedHost
    if (scheme !in HTTP_SCHEMES || host.isBlank() || parsed.port !in 1..65535 ||
        (!host.startsWith('[') && host.split('.').any(String::isEmpty)) ||
        !value.contains("://") || authority.isBlank() || authority.contains('@') || host.startsWith(".") ||
        parsed.user != null || parsed.password != null || authority.substringAfterLast(':').toIntOrNull() == 0) return null
    WebViewHttpOrigin(scheme, host, parsed.port)
}.getOrNull()

internal data class WebViewHttpOrigin(
    val scheme: String,
    val host: String,
    val port: Int,
)

private val HTTP_SCHEMES = setOf("http", "https")

/** 手动执行脚本允许可信 HTTPS 或明确启用的低权限初始同源页面。 */
fun WebViewRequest.canEvaluateJavascriptAt(url: String?): Boolean =
    settings.javaScriptEnabled && (security.trustedOrigins.isTrusted(url) ||
        (security.pageBridgeEnabled && canUseAppBridgeAt(url)))

/** 按浏览器 URL 语义保存 IPv6，避免展开写法和 IPv4 尾段使原生来源与 JS hostname 不同。 */
private fun canonicalIpv6Host(host: String): String? {
    if (!host.endsWith("]")) return null
    var address = host.substring(1, host.length - 1)
    if (address.contains('.')) {
        val ipv4 = address.substringAfterLast(':').split('.')
        if (ipv4.size != 4 || ipv4.any { !it.matches(Regex("0|[1-9][0-9]{0,2}")) }) return null
        val bytes = ipv4.map { it.toInt() }
        if (bytes.any { it > 255 }) return null
        address = address.substringBeforeLast(':') + ":" +
            ((bytes[0] shl 8) + bytes[1]).toString(16) + ":" + ((bytes[2] shl 8) + bytes[3]).toString(16)
    }
    val sides = address.split("::")
    if (sides.size > 2) return null
    fun pieces(value: String): List<Int>? {
        if (value.isEmpty()) return emptyList()
        val parts = value.split(':')
        if (parts.any { !it.matches(Regex("[0-9a-f]{1,4}")) }) return null
        return parts.map { it.toInt(16) }
    }
    val left = pieces(sides[0]) ?: return null
    val right = if (sides.size == 2) pieces(sides[1]) ?: return null else emptyList()
    val omitted = 8 - left.size - right.size
    if ((sides.size == 1 && omitted != 0) || (sides.size == 2 && omitted < 1)) return null
    val groups = left + List(omitted) { 0 } + right
    var bestStart = -1
    var bestLength = 1
    var index = 0
    while (index < groups.size) {
        if (groups[index] != 0) { index++; continue }
        val start = index
        while (index < groups.size && groups[index] == 0) index++
        if (index - start > bestLength) { bestStart = start; bestLength = index - start }
    }
    val rendered = if (bestStart < 0) groups.joinToString(":") { it.toString(16) } else
        groups.take(bestStart).joinToString(":") { it.toString(16) } + "::" +
            groups.drop(bestStart + bestLength).joinToString(":") { it.toString(16) }
    return "[$rendered]"
}
