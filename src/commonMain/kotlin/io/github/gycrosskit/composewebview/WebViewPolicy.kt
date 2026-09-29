package io.github.gycrosskit.composewebview

import io.ktor.http.Url

/**
 * 当前主框架是否允许安装应用 Bridge。
 *
 * Android 和 iOS 必须复用同一判断，避免一端只检查能力开关、另一端同时检查可信来源。
 */
internal fun WebViewRequest.canUseAppBridgeAt(url: String?): Boolean =
    when {
        security.appBridgeEnabled -> security.trustedOrigins.isTrusted(url)
        security.pageBridgeEnabled -> content.initialOrigin().sameHttpOriginAs(url)
        else -> false
    }

/** Bridge 消息必须来自当前请求允许的主框架，子框架即使同源也不能调用应用能力。 */
internal fun WebViewRequest.canReceiveAppBridgeMessage(sourceUrl: String?, isMainFrame: Boolean): Boolean =
    isMainFrame && canUseAppBridgeAt(sourceUrl)

/**
 * Bridge 开启后，主框架只能停留在对应来源；子资源和 iframe 仍由各自策略处理。
 */
internal fun WebViewRequest.shouldBlockMainFrameNavigation(
    url: String?,
    isMainFrame: Boolean,
): Boolean = isMainFrame &&
    (security.appBridgeEnabled || security.pageBridgeEnabled) &&
    !canUseAppBridgeAt(url)

/** 统一两端的脚本来源门禁，业务脚本不能绕过 [WebViewScript.onlyForTrustedMainFrame]。 */
internal fun WebViewRequest.canInject(script: WebViewScript, url: String?): Boolean =
    !script.onlyForTrustedMainFrame || security.trustedOrigins.isTrusted(url)

/** URL 页面以自身为初始来源，内联 HTML 只能使用显式 baseUrl 建立来源。 */
internal fun WebViewContent.initialOrigin(): String? = when (this) {
    is WebViewContent.Url -> url
    is WebViewContent.Html -> baseUrl
}

/** URL 和 HTML 使用同一空内容语义，保证双端首次加载与 reload 的结果一致。 */
internal fun WebViewContent.isBlankWebViewContent(): Boolean = when (this) {
    is WebViewContent.Url -> url.isBlank()
    is WebViewContent.Html -> html.isBlank()
}

/** 低权限页面 Bridge 允许历史 HTTP，但只能留在初始页面的精确来源。 */
private fun String?.sameHttpOriginAs(other: String?): Boolean {
    val source = this?.toHttpOrigin() ?: return false
    return source == other?.toHttpOrigin()
}

private fun String.toHttpOrigin(): WebViewHttpOrigin? = runCatching {
    val parsed = Url(trim())
    val scheme = parsed.protocol.name.lowercase()
    val host = parsed.host.lowercase().trimEnd('.')
    if (scheme !in HTTP_SCHEMES || host.isBlank()) return null
    WebViewHttpOrigin(scheme, host, parsed.port)
}.getOrNull()

private data class WebViewHttpOrigin(
    val scheme: String,
    val host: String,
    val port: Int,
)

private val HTTP_SCHEMES = setOf("http", "https")
