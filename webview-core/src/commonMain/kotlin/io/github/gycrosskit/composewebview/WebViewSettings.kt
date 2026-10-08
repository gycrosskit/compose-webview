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
 * @property javaScriptEnabled 是否执行页面 JavaScript。 默认 false。
 * @property domStorageEnabled Android/OHOS 是否允许 DOM Storage；WKWebView 没有对应公开禁用开关。默认 true。
 * @property allowFileAccess 是否允许访问平台文件 URL。 默认 false。
 * @property allowContentAccess 是否允许访问平台内容 URL。 默认 false。
 * @property mixedContentPolicy HTTPS 页面加载 HTTP 子资源策略。iOS COMPATIBILITY 采用严格规则；ALWAYS_ALLOW 仍受 WebKit/ATS 策略约束。默认 NEVER_ALLOW。
 * @property cachePolicy 平台网页缓存读取策略。 默认 DEFAULT。
 * @property acceptsThirdPartyCookies Android 是否允许第三方资源携带 Cookie；仅可信且确有跨域会话需求的页面开启。 默认 false。
 * @property supportMultipleWindows 是否支持网页创建新窗口。 默认 false。
 * @property javaScriptCanOpenWindowsAutomatically 是否允许脚本自动创建窗口。 默认 false。
 * @property mediaPlaybackRequiresUserGesture 媒体播放是否必须由用户手势触发。 默认 true。
 * @property loadsImagesAutomatically Android/OHOS 是否自动加载页面图片；iOS 无法以公开原生规则保真禁止 data 图片，使用 WebKit 默认行为。 默认 true。
 * @property blockNetworkImage 是否阻止 HTTP(S) 网络图片；iOS 使用 Content Rule List，保留 data/本地资源。 默认 false。
 * @property builtInZoomControls Android 内置缩放控件开关；其他平台使用 supportZoom。默认 true。
 * @property displayZoomControls Android 是否展示平台缩放按钮；iOS/OHOS 没有等价按钮。默认 false。
 * @property supportZoom 是否允许页面缩放；iOS false 使用 author viewport 禁缩放，系统可访问性策略优先。默认 true。
 * @property useWideViewPort Android 宽视口开关；iOS/OHOS 遵循网页 viewport 与内核布局。默认 true。
 * @property loadWithOverviewMode Android 首次 overview 缩放；iOS/OHOS 遵循内核布局。默认 true。
 * @property followSystemFontScale Android/OHOS 网页文字跟随系统字体；iOS 无等价原生 textZoom 开关。默认 true。
 * @property minimumTextZoomPercent 网页文字缩放下限，范围 50..500。 默认 100。
 * @property maximumTextZoomPercent 网页文字缩放上限，且不小于下限。 默认 200。
 * @property algorithmicDarkeningAllowed Android/OHOS 是否允许平台算法暗化；iOS 无等价公开 API。默认 true。
 * @property userAgentSuffix 追加到平台默认 User-Agent 的标识，不能包含换行；不得使用用户凭据。默认 null。
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
