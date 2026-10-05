package io.github.gycrosskit.composewebview

import io.ktor.http.Url

/**
 * Kuikly 事件没有同步返回值，导航决定必须随请求下发到原生。
 * @property allowedSchemes 允许的规范化小写 scheme，默认 HTTP/HTTPS；平台可以进一步限制。
 * @property blockedRules 主文档拒绝规则，默认空；不用于子资源过滤。
 * @property allowNewWindows 是否允许新窗口导航，默认 false；平台可拒绝或沿用当前窗口。
 * @property allowedOrigins 主文档精确 HTTP/HTTPS 来源白名单，默认空表示不限制来源。
 */
data class WebViewNavigationPolicy(
    val allowedSchemes: Set<String> = setOf("http", "https"),
    val blockedRules: List<WebViewUrlRule> = emptyList(),
    val allowNewWindows: Boolean = false,
    /** 主帧精确来源白名单，与 JS/Bridge 无关；空集合不限制来源。路径和查询不参与匹配。 */
    val allowedOrigins: Set<String> = emptySet(),
) {
    init {
        require(allowedSchemes.all { it.matches(Regex("[a-z][a-z0-9+.-]*")) })
        require(allowedOrigins.all { it.toHttpOrigin() != null }) { "allowedOrigins requires valid HTTP/HTTPS origins" }
    }

    /** 同步检查 scheme、窗口、主文档规则和来源；不负责 JS/Bridge 来源授权。 */
    fun allows(navigation: WebViewNavigationRequest): Boolean {
        val scheme = runCatching { Url(navigation.url).protocol.name.lowercase() }.getOrNull()
        return scheme in allowedSchemes &&
            (navigation.target != WebViewNavigationTarget.NEW_WINDOW || allowNewWindows) &&
            (!navigation.isMainFrame || blockedRules.none { it.matches(navigation.url) }) &&
            (!navigation.isMainFrame || allowedOrigins.isEmpty() ||
                navigation.url.toHttpOrigin()?.let { origin -> allowedOrigins.any { it.toHttpOrigin() == origin } } == true)
    }
}

/** 原生导航回调中同步调用，不能等待 Kotlin 异步事件再决定。 */
fun WebViewRequest.allowsNavigation(navigation: WebViewNavigationRequest): Boolean =
    navigationPolicy.allows(navigation) && !shouldBlockMainFrameNavigation(navigation.url, navigation.isMainFrame)

/** 原生 loadHTML/loadData 的首航例外只能由实例的一次性状态授权，网页后续不能复用。 */
fun WebViewNavigationRequest.isInternalHtmlInitialNavigation(): Boolean =
    isMainFrame && !hasUserGesture && target == WebViewNavigationTarget.CURRENT_WINDOW &&
        (url.startsWith("data:text/html", ignoreCase = true) || url == "about:blank")
