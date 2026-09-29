package io.github.gycrosskit.composewebview

import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * 将网页加载进度和标题转发给公共状态层的 ChromeClient。
 *
 * 文件选择、全屏视频等高权限能力不会在这里直接授权；业务 ChromeClient 应在校验页面来源和运行时
 * 权限后自行接管。
 */
open class AppWebChromeClient(
    private val listener: Listener,
) : WebChromeClient() {

    /** WebView 壳层关心的 Chrome 事件，默认实现均不改变行为。 */
    interface Listener {
        /** 加载进度已归一化到 0..100。 */
        fun onProgressChanged(progress: Int) {}

        /** 页面上报标题；页面初始化阶段可能为空或只是临时 URL 文本。 */
        fun onReceivedTitle(title: String?, url: String?) {}
    }

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        listener.onProgressChanged(newProgress.coerceIn(0, 100))
        super.onProgressChanged(view, newProgress)
    }

    override fun onReceivedTitle(view: WebView?, title: String?) {
        listener.onReceivedTitle(title, view?.url)
        super.onReceivedTitle(view, title)
    }
}
