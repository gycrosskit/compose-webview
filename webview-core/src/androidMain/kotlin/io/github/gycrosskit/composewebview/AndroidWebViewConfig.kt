package io.github.gycrosskit.composewebview

import android.annotation.SuppressLint
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/** 共享原生配置边界，CMP 与 Kuikly 都从实例初始 User-Agent 追加后缀。 */
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

/** 已解析动态环境值后的配置快照，只用于判断是否需要调用原生 Setter。 */
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
