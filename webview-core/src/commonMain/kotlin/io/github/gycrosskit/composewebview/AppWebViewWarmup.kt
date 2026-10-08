package io.github.gycrosskit.composewebview

/**
 * 在隐私授权后提前准备平台网页内核，不加载 URL、不创建业务页面，也不发起网络请求。
 *
 * Android 使用 Jetpack WebKit 官方异步启动能力；当前 iOS WKWebView 不需要对应动作，平台实现保持 no-op。
 */
fun interface AppWebViewWarmup {
    /** 仅在宿主完成隐私授权后调用；重复调用由平台实现去重，不代表已有可加载页面。 */
    fun warmUp()
}
