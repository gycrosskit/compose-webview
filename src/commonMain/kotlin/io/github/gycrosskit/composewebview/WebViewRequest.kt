package io.github.gycrosskit.composewebview

/**
 * 单个网页实例的声明式输入；事件回调由具体 CMP 控件单独持有。
 *
 * @property content 需要加载的 URL 或 HTML。
 * @property settings 不含平台常量的网页设置。
 * @property security 高权限来源门禁。
 * @property scripts 按声明时机在可信主文档执行的命名脚本。
 * @property blockedResourceRules 平台支持时应拦截的子资源规则。
 */
data class WebViewRequest(
    val content: WebViewContent,
    val settings: WebViewSettings = WebViewSettings(),
    val security: WebViewSecurity = WebViewSecurity(),
    val scripts: List<WebViewScript> = emptyList(),
    val blockedResourceRules: List<WebViewUrlRule> = emptyList(),
)
