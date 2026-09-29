package io.github.gycrosskit.composewebview

/**
 * JS 手势查询完成后再确认实例所有权；业务路由也可能同步关闭页面，因此原生加载前还需复核一次。
 *
 * 返回 BLOCK 仍必须回调 WebKit 的 decisionHandler，不能因页面销毁而遗留未完成的导航决策。
 */
internal fun completeIosWebNavigation(
    navigation: WebViewNavigationRequest,
    blockedByTrust: Boolean,
    isActive: () -> Boolean,
    route: (WebViewNavigationRequest) -> WebViewNavigationDecision,
    loadPopup: () -> Unit,
    onBlocked: () -> Unit,
): WebViewNavigationDecision {
    if (!isActive()) {
        AppWebViewRuntime.log(AppWebViewLogLevel.INFO, "忽略已失活实例的 iOS 网页导航回调")
        return WebViewNavigationDecision.BLOCK
    }
    val decision = route(navigation)
    if (!isActive()) {
        AppWebViewRuntime.log(AppWebViewLogLevel.INFO, "iOS 网页路由期间实例已失活，取消后续原生导航")
        return WebViewNavigationDecision.BLOCK
    }
    if (blockedByTrust || decision == WebViewNavigationDecision.BLOCK) {
        if (navigation.isMainFrame) onBlocked()
        return WebViewNavigationDecision.BLOCK
    }
    if (navigation.target == WebViewNavigationTarget.NEW_WINDOW) {
        // target=_blank 沿用当前网页加载，原始新窗口导航必须取消，避免重复请求。
        loadPopup()
        return WebViewNavigationDecision.BLOCK
    }
    return WebViewNavigationDecision.ALLOW
}
