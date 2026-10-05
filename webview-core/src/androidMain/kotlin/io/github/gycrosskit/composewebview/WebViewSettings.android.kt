package io.github.gycrosskit.composewebview

import android.webkit.WebSettings
import kotlin.math.roundToInt

/** 只有原生 Setter 需要 Android 常量，配置快照始终复用中立的 WebViewSettings。 */
val WebViewSettings.androidMixedContentMode: Int
    get() = when (mixedContentPolicy) {
        WebViewMixedContentPolicy.NEVER_ALLOW -> WebSettings.MIXED_CONTENT_NEVER_ALLOW
        WebViewMixedContentPolicy.COMPATIBILITY -> WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        WebViewMixedContentPolicy.ALWAYS_ALLOW -> WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
    }

/** 映射中立缓存策略到 Android WebSettings 常量，不修改全局 HTTP 缓存。 */
val WebViewSettings.androidCacheMode: Int
    get() = when (cachePolicy) {
        WebViewCachePolicy.DEFAULT -> WebSettings.LOAD_DEFAULT
        WebViewCachePolicy.NO_CACHE -> WebSettings.LOAD_NO_CACHE
        WebViewCachePolicy.CACHE_ELSE_NETWORK -> WebSettings.LOAD_CACHE_ELSE_NETWORK
        WebViewCachePolicy.CACHE_ONLY -> WebSettings.LOAD_CACHE_ONLY
    }

/** 将系统字体倍率转换为配置允许范围内的 WebView `textZoom` 百分比。 */
fun calculateWebViewTextZoom(
    fontScale: Float,
    config: WebViewSettings,
): Int {
    if (!config.followSystemFontScale) return 100
    return (fontScale * 100f)
        .roundToInt()
        .coerceIn(config.minimumTextZoomPercent, config.maximumTextZoomPercent)
}
