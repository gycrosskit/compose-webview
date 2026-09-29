package io.github.gycrosskit.composewebview

/** HTTPS 页面加载 HTTP 子资源时的策略。 */
enum class WebViewMixedContentPolicy {
    NEVER_ALLOW,
    COMPATIBILITY,
    ALWAYS_ALLOW,
}

/** 平台网页缓存读取策略。 */
enum class WebViewCachePolicy {
    DEFAULT,
    NO_CACHE,
    CACHE_ELSE_NETWORK,
    CACHE_ONLY,
}

/**
 * 不含平台常量的网页设置快照。
 *
 * 高风险能力默认关闭；平台实现负责把枚举映射为自己的 WebView/WKWebView 配置。
 *
 * @property javaScriptEnabled 是否执行页面 JavaScript。
 * @property domStorageEnabled 是否允许网页使用 DOM Storage。
 * @property allowFileAccess 是否允许访问平台文件 URL。
 * @property allowContentAccess 是否允许访问平台内容 URL。
 * @property mixedContentPolicy HTTPS 页面加载 HTTP 子资源时的策略。
 * @property cachePolicy 平台网页缓存读取策略。
 * @property acceptsThirdPartyCookies Android 是否允许第三方资源携带 Cookie；仅可信且确有跨域会话需求的页面开启。
 * @property supportMultipleWindows 是否支持网页创建新窗口。
 * @property javaScriptCanOpenWindowsAutomatically 是否允许脚本自动创建窗口。
 * @property mediaPlaybackRequiresUserGesture 媒体播放是否必须由用户手势触发。
 * @property loadsImagesAutomatically Android 是否自动加载页面图片；iOS 使用 WebKit 默认行为。
 * @property blockNetworkImage Android 是否阻止网络图片；iOS 使用 WebKit 默认行为。
 * @property builtInZoomControls 是否提供手势缩放能力。
 * @property displayZoomControls 是否展示平台缩放按钮。
 * @property supportZoom 是否允许页面缩放。
 * @property useWideViewPort 是否使用页面 viewport 或宽视口布局。
 * @property loadWithOverviewMode 是否在首次加载时缩放到页面宽度。
 * @property followSystemFontScale 网页文字是否跟随系统字体倍率。
 * @property minimumTextZoomPercent 网页文字缩放下限，范围 50..500。
 * @property maximumTextZoomPercent 网页文字缩放上限，且不小于下限。
 * @property algorithmicDarkeningAllowed 是否允许平台对网页执行算法变暗。
 * @property userAgentSuffix 追加到平台默认 User-Agent 的标识。
 */
data class WebViewSettings(
    val javaScriptEnabled: Boolean = false,
    val domStorageEnabled: Boolean = true,
    val allowFileAccess: Boolean = false,
    val allowContentAccess: Boolean = false,
    val mixedContentPolicy: WebViewMixedContentPolicy = WebViewMixedContentPolicy.NEVER_ALLOW,
    val cachePolicy: WebViewCachePolicy = WebViewCachePolicy.DEFAULT,
    val acceptsThirdPartyCookies: Boolean = false,
    val supportMultipleWindows: Boolean = false,
    val javaScriptCanOpenWindowsAutomatically: Boolean = false,
    val mediaPlaybackRequiresUserGesture: Boolean = true,
    val loadsImagesAutomatically: Boolean = true,
    val blockNetworkImage: Boolean = false,
    val builtInZoomControls: Boolean = true,
    val displayZoomControls: Boolean = false,
    val supportZoom: Boolean = true,
    val useWideViewPort: Boolean = true,
    val loadWithOverviewMode: Boolean = true,
    val followSystemFontScale: Boolean = true,
    val minimumTextZoomPercent: Int = 100,
    val maximumTextZoomPercent: Int = 200,
    val algorithmicDarkeningAllowed: Boolean = true,
    val userAgentSuffix: String? = null,
) {
    init {
        require(minimumTextZoomPercent in MIN_TEXT_ZOOM..MAX_TEXT_ZOOM)
        require(maximumTextZoomPercent in minimumTextZoomPercent..MAX_TEXT_ZOOM)
    }

    private companion object {
        const val MIN_TEXT_ZOOM = 50
        const val MAX_TEXT_ZOOM = 500
    }
}
