package io.github.gycrosskit.composewebview

/** 将 WKWebView 的非原子 progress/loading 采样转换成稳定的页面进度。 */
internal class IosWebViewProgressTracker(
    private val idleSamplesAfterLoading: Int = DEFAULT_IDLE_SAMPLES_AFTER_LOADING,
    private val idleSamplesBeforeLoading: Int = DEFAULT_IDLE_SAMPLES_BEFORE_LOADING,
) {
    private var lastProgress = -1
    private var observedLoading = false
    private var idleSamples = 0

    init {
        require(idleSamplesAfterLoading > 0)
        require(idleSamplesBeforeLoading >= idleSamplesAfterLoading)
    }

    fun sample(estimatedProgress: Double, isLoading: Boolean): IosWebViewProgressSample {
        val progress = (estimatedProgress * 100.0).toInt().coerceIn(0, 100)
        if (isLoading) {
            observedLoading = true
            idleSamples = 0
        } else {
            idleSamples++
        }
        val completed = when {
            observedLoading && !isLoading && progress >= 100 -> true
            observedLoading && !isLoading -> idleSamples >= idleSamplesAfterLoading
            !observedLoading && !isLoading -> idleSamples >= idleSamplesBeforeLoading
            else -> false
        }
        // 新导航提交后的第一拍可能仍带着上一页的 100%，确认完成前最多展示 99%。
        val visibleProgress = if (completed) 100 else progress.coerceAtMost(99)
        val changed = visibleProgress != lastProgress
        lastProgress = visibleProgress
        return IosWebViewProgressSample(
            progress = visibleProgress,
            changed = changed,
            completed = completed,
        )
    }

    private companion object {
        // WebKit 完成主导航后给 delegate 留出 200ms；漏回调时仍能可靠收起进度条。
        const val DEFAULT_IDLE_SAMPLES_AFTER_LOADING = 4
        // 首次导航回调可能晚于 loadRequest，最多等待 1s 后结束无效的加载状态。
        const val DEFAULT_IDLE_SAMPLES_BEFORE_LOADING = 20
    }
}

internal data class IosWebViewProgressSample(
    val progress: Int,
    val changed: Boolean,
    val completed: Boolean,
)
