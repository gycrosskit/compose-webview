package io.github.gycrosskit.composewebview

/** NATIVE 保留平台说明；HOST 等待宿主说明回执，仍由原生申请和验证系统权限。 */
enum class WebViewPermissionPresentation { NATIVE, HOST }

/**
 * 单个网页实例的声明式输入；事件回调由具体 UI 控件单独持有。
 *
 * @property content 需要加载的 URL 或 HTML。
 * @property settings 不含平台常量的网页设置。
 * @property security 高权限来源门禁。
 * @property scripts 按声明时机在可信主文档执行的命名脚本。
 * @property blockedResourceRules 平台支持时应拦截的子资源规则，默认空。
 * @property navigationPolicy 原生同步导航门禁；默认允许 HTTP/HTTPS 并拒绝新窗口。
 * @property permissionPresentation HOST 交由宿主显示用途说明并回执；默认 NATIVE 保留原生说明。
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
    val permissionPresentation: WebViewPermissionPresentation = WebViewPermissionPresentation.NATIVE,
    /** 敏感页面仅记录元数据；原生实例开启后直到销毁不可恢复正文诊断。 */
    val sensitiveDiagnostics: Boolean = false,
    /** HTTP 请求的精确来源；null 保留历史行为，空集合拒绝 HTTP。不是内核网络安全边界。 */
    val allowedHttpOrigins: Set<String>? = null,
) {
    init {
        require(allowedHttpOrigins == null || allowedHttpOrigins.all { it.toHttpOrigin()?.scheme == "http" }) {
            "allowedHttpOrigins requires valid HTTP origins"
        }
        require(pageMessageChannels.size <= 16 && pageMessageChannels.all(::isValidPageMessageChannel)) {
            "Invalid page message channel"
        }
        require(pageMessageChannels.isEmpty() || content.initialOrigin()?.toHttpOrigin() != null) {
            "Page message channels require an initial HTTP(S) URL"
        }
    }
}

/** 仅约束可观察的 HTTP 请求；HTTPS 以及非网络地址沿用其他门禁。 */
fun WebViewRequest.allowsHttpRequest(url: String): Boolean {
    val origins = allowedHttpOrigins ?: return true
    if (!url.trim().startsWith("http:", ignoreCase = true)) return true
    val target = url.toHttpOrigin() ?: return false
    return origins.any { it.toHttpOrigin() == target }
}
