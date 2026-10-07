package io.github.gycrosskit.composewebview

/**
 * 单个网页实例的声明式输入；事件回调由具体 UI 控件单独持有。
 *
 * @property content 需要加载的 URL 或 HTML。
 * @property settings 不含平台常量的网页设置。
 * @property security 高权限来源门禁。
 * @property scripts 按声明时机在可信主文档执行的命名脚本。
 * @property blockedResourceRules 平台支持时应拦截的子资源规则，默认空。
 * @property navigationPolicy 原生同步导航门禁；默认允许 HTTP/HTTPS 并拒绝新窗口。
 * @property pageMessageChannels 初始 HTTP(S) 主文档的具名早期消息入口，默认关闭；不授予应用 Bridge 权限。
 */
data class WebViewRequest(
    val content: WebViewContent,
    val settings: WebViewSettings = WebViewSettings(),
    val security: WebViewSecurity = WebViewSecurity(),
    val scripts: List<WebViewScript> = emptyList(),
    val blockedResourceRules: List<WebViewUrlRule> = emptyList(),
    val navigationPolicy: WebViewNavigationPolicy = WebViewNavigationPolicy(),
    val pageMessageChannels: Set<String> = emptySet(),
) {
    init {
        require(pageMessageChannels.size <= 16 && pageMessageChannels.all(::isValidPageMessageChannel)) {
            "Invalid page message channel"
        }
        require(pageMessageChannels.isEmpty() || content.initialOrigin()?.toHttpOrigin() != null) {
            "Page message channels require an initial HTTP(S) URL"
        }
    }
}
