package io.github.gycrosskit.composewebview

/**
 * 平台网页组件准备执行的导航请求。
 *
 * @property url 目标地址。
 * @property isMainFrame 是否为主文档导航。
 * @property hasUserGesture 是否由明确用户手势触发。
 */
data class WebViewNavigationRequest(
    val url: String,
    val isMainFrame: Boolean,
    val hasUserGesture: Boolean,
    val target: WebViewNavigationTarget = WebViewNavigationTarget.CURRENT_WINDOW,
)

/** 区分当前页面导航和 `window.open` 新窗口请求，避免弹窗绕过 shared 路由。 */
enum class WebViewNavigationTarget {
    CURRENT_WINDOW,
    NEW_WINDOW,
}

/** shared 或目标 UI 框架对导航请求的同步决策。 */
enum class WebViewNavigationDecision {
    ALLOW,
    BLOCK,
}
