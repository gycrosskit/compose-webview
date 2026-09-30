package io.github.gycrosskit.composewebview

/**
 * 当前网页实例的不可变状态快照；状态容器由 Compose、Kuikly 等 UI 层自行选择。
 *
 * @property title 过滤平台临时 URL 标题后的页面标题。
 * @property currentUrl 当前主框架地址。
 * @property progress 0..100 的加载进度。
 * @property isLoading 主文档是否仍在加载。
 * @property hasVisibleContent 当前实例是否已经提交过首个可见主文档，用于首屏性能诊断。
 * @property canGoBack 平台网页历史是否可以返回。
 * @property error 最近一次整页级失败。
 */
data class WebViewSnapshot(
    val title: String? = null,
    val currentUrl: String? = null,
    val progress: Int = 0,
    val isLoading: Boolean = false,
    val hasVisibleContent: Boolean = false,
    val canGoBack: Boolean = false,
    val error: WebViewLoadError? = null,
) {
    init {
        require(progress in 0..100)
    }
}
