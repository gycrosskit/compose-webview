package io.github.gycrosskit.composewebview

/** WKWebView 当前没有与 Android WebView 启动任务等价的显式预热动作。 */
object IosAppWebViewWarmup : AppWebViewWarmup {
    override fun warmUp() = Unit
}
