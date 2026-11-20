package io.github.gycrosskit.composewebview

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/** IO 线程只检查策略；调用方应把拒绝事件切回 UI 线程并复核 owner。 */
fun WebViewRequest.interceptAndroidRequest(
    resource: WebResourceRequest,
    onNavigationBlocked: (WebViewNavigationRequest) -> Unit,
): WebResourceResponse? {
    val url = resource.url.toString()
    // shortcut: 系统不报告子资源重定向目标，需要严格网络隔离时由宿主系统策略约束。
    if (!allowsHttpRequest(url)) {
        if (resource.isForMainFrame) onNavigationBlocked(WebViewNavigationRequest(url, true, resource.hasGesture()))
        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
    }
    val scheme = resource.url.scheme?.lowercase()
    if (resource.isForMainFrame && resource.method != "GET" && scheme in setOf("http", "https")) {
        val navigation = WebViewNavigationRequest(url, true, resource.hasGesture())
        val policy = navigationPolicy
        val restricted = policy.navigationOrigins?.allowAnyHttpOrigin == false ||
            policy.allowedUrls.isNotEmpty() || policy.allowedOrigins.isNotEmpty() ||
            policy.blockedRules.isNotEmpty() || !policy.allowedSchemes.containsAll(setOf("http", "https"))
        // 受限主框架须保守处理，Android 不报告保留 POST 的所有重定向。
        if (!allowsNavigation(navigation) || restricted) {
            onNavigationBlocked(navigation)
            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
        }
    }
    return if (blockedResourceRules.any { it.matches(url) })
        WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0))) else null
}
