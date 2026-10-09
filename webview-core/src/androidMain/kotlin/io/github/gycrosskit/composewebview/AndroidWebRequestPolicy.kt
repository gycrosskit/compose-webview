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
    if (resource.isForMainFrame && resource.method != "GET" && resource.url.scheme?.lowercase() in setOf("http", "https")) {
        val navigation = WebViewNavigationRequest(url, true, resource.hasGesture())
        val policy = navigationPolicy
        val restricted = policy.allowedUrls.isNotEmpty() || policy.allowedOrigins.isNotEmpty() ||
            policy.blockedRules.isNotEmpty() || !policy.allowedSchemes.containsAll(setOf("http", "https"))
        // POST 跳过 override，intercept 又不覆盖 redirect；受限主文档不能安全放行非 GET 首请求。
        if (!allowsNavigation(navigation) || restricted) {
            onNavigationBlocked(navigation)
            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
        }
    }
    return if (blockedResourceRules.any { it.matches(url) })
        WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0))) else null
}
