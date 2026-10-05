package io.github.gycrosskit.composewebview

import android.annotation.SuppressLint
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * 在 UI 线程应用声明式配置；CMP 与 Kuikly 都从实例初始 User-Agent 追加后缀，不触发页面加载。
 * @param defaultUserAgent 实例创建时的 User-Agent；不得使用已经追加后缀的值。
 * @param fontScale 系统字体倍率，1.0 表示标准；最终缩放按 config 的百分比边界限制。
 * @param darkTheme 当前系统深色主题，旧内核可能不支持算法暗化。
 * @param backgroundColor Android ARGB 颜色值。
 * @param previous 上次返回的配置快照，默认 null，供跳过相等 Setter。
 */
@SuppressLint("SetJavaScriptEnabled")
fun WebView.applyWebViewConfig(
    config: WebViewSettings,
    defaultUserAgent: String?,
    fontScale: Float,
    darkTheme: Boolean,
    backgroundColor: Int,
    previous: AppliedAppWebViewConfig? = null,
): AppliedAppWebViewConfig {
    val resolvedTextZoom = calculateWebViewTextZoom(fontScale, config)
    val resolvedDarkening = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        darkTheme && config.algorithmicDarkeningAllowed
    } else {
        null
    }
    val resolvedBackgroundColor = backgroundColor
    val resolvedConfig = AppliedAppWebViewConfig(
        config = config,
        textZoom = resolvedTextZoom,
        algorithmicDarkeningAllowed = resolvedDarkening,
        backgroundColor = resolvedBackgroundColor,
    )
    if (previous == resolvedConfig) return resolvedConfig

    settings.apply {
        if (previous?.config?.javaScriptEnabled != config.javaScriptEnabled) {
            javaScriptEnabled = config.javaScriptEnabled
        }
        if (previous?.config?.domStorageEnabled != config.domStorageEnabled) {
            domStorageEnabled = config.domStorageEnabled
        }
        if (previous?.config?.allowFileAccess != config.allowFileAccess) {
            allowFileAccess = config.allowFileAccess
        }
        if (previous?.config?.allowContentAccess != config.allowContentAccess) {
            allowContentAccess = config.allowContentAccess
        }
        if (previous?.config?.mixedContentPolicy != config.mixedContentPolicy) {
            mixedContentMode = config.androidMixedContentMode
        }
        if (previous?.config?.cachePolicy != config.cachePolicy) {
            cacheMode = config.androidCacheMode
        }
        if (previous?.config?.supportMultipleWindows != config.supportMultipleWindows) {
            setSupportMultipleWindows(config.supportMultipleWindows)
        }
        if (
            previous?.config?.javaScriptCanOpenWindowsAutomatically !=
            config.javaScriptCanOpenWindowsAutomatically
        ) {
            javaScriptCanOpenWindowsAutomatically = config.javaScriptCanOpenWindowsAutomatically
        }
        if (previous?.config?.mediaPlaybackRequiresUserGesture != config.mediaPlaybackRequiresUserGesture) {
            mediaPlaybackRequiresUserGesture = config.mediaPlaybackRequiresUserGesture
        }
        if (previous?.config?.loadsImagesAutomatically != config.loadsImagesAutomatically) {
            loadsImagesAutomatically = config.loadsImagesAutomatically
        }
        if (previous?.config?.blockNetworkImage != config.blockNetworkImage) {
            blockNetworkImage = config.blockNetworkImage
        }
        if (previous?.config?.builtInZoomControls != config.builtInZoomControls) {
            builtInZoomControls = config.builtInZoomControls
        }
        if (previous?.config?.displayZoomControls != config.displayZoomControls) {
            displayZoomControls = config.displayZoomControls
        }
        if (previous?.config?.supportZoom != config.supportZoom) setSupportZoom(config.supportZoom)
        if (previous?.config?.useWideViewPort != config.useWideViewPort) {
            useWideViewPort = config.useWideViewPort
        }
        if (previous?.config?.loadWithOverviewMode != config.loadWithOverviewMode) {
            loadWithOverviewMode = config.loadWithOverviewMode
        }
        if (previous?.textZoom != resolvedTextZoom) textZoom = resolvedTextZoom
        val resolvedUserAgent = buildUserAgent(defaultUserAgent, config.userAgentSuffix)
        if (userAgentString != resolvedUserAgent) userAgentString = resolvedUserAgent
        if (
            resolvedDarkening != null &&
            previous?.algorithmicDarkeningAllowed != resolvedDarkening &&
            WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)
        ) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, resolvedDarkening)
        }
    }
    if (previous?.config?.acceptsThirdPartyCookies != config.acceptsThirdPartyCookies) {
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, config.acceptsThirdPartyCookies)
    }
    if (previous?.backgroundColor != resolvedBackgroundColor) {
        setBackgroundColor(resolvedBackgroundColor)
    }
    return resolvedConfig
}

/**
 * 已解析动态环境值后的配置快照，仅用于跳过相等原生 Setter。
 * @property config 原始中立配置。
 * @property textZoom 字体缩放百分比，100 为标准。
 * @property algorithmicDarkeningAllowed 解析后的暗化开关；null 表示平台不支持。
 * @property backgroundColor Android ARGB 背景色。
 */
data class AppliedAppWebViewConfig(
    val config: WebViewSettings,
    val textZoom: Int,
    val algorithmicDarkeningAllowed: Boolean?,
    val backgroundColor: Int,
)

/** 始终从实例初始 User-Agent 追加后缀，避免多次重组重复拼接。 */
private fun buildUserAgent(defaultUserAgent: String?, suffix: String?): String? {
    val base = defaultUserAgent.orEmpty()
    val normalizedSuffix = suffix?.trim().orEmpty()
    return if (normalizedSuffix.isBlank()) base else "$base $normalizedSuffix"
}
