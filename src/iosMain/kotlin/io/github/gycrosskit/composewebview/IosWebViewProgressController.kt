package io.github.gycrosskit.composewebview

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.UIKit.NSLayoutConstraint
import platform.UIKit.UIColor
import platform.UIKit.UIProgressView
import platform.UIKit.UIProgressViewStyle
import platform.WebKit.WKWebView

/** 将 WKWebView 进度采样和原生进度视图生命周期从导航代理中隔离。 */
@OptIn(ExperimentalForeignApi::class)
internal class IosWebViewProgressController(
    private val state: AppWebViewState,
    private val callbacks: () -> WebViewCallbacks,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null
    private var generation = 0L
    private var view: UIProgressView? = null

    fun attach(webView: WKWebView) {
        view = UIProgressView(
            progressViewStyle = UIProgressViewStyle.UIProgressViewStyleDefault,
        ).apply {
            translatesAutoresizingMaskIntoConstraints = false
            trackTintColor = UIColor.clearColor
            progressTintColor = webView.tintColor
            hidden = true
            webView.addSubview(this)
            NSLayoutConstraint.activateConstraints(
                listOf(
                    leadingAnchor.constraintEqualToAnchor(webView.leadingAnchor),
                    trailingAnchor.constraintEqualToAnchor(webView.trailingAnchor),
                    topAnchor.constraintEqualToAnchor(webView.topAnchor),
                ),
            )
        }
    }

    /**
     * Kotlin/Native 当前未暴露 WKWebView 的 KVO 注册入口，因此仅在页面加载生命周期内采样。
     */
    fun observe(webView: WKWebView, restart: Boolean) {
        if (!restart && job?.isActive == true) return
        stop()
        val currentGeneration = generation
        job = scope.launch {
            val tracker = IosWebViewProgressTracker()
            // loadRequest/loadData 会在当前调用栈稍后执行；先让 WebKit 接收导航。
            delay(PROGRESS_SAMPLE_INTERVAL_MILLIS)
            while (isActive) {
                if (currentGeneration != generation) break
                val sample = tracker.sample(
                    estimatedProgress = webView.estimatedProgress,
                    isLoading = webView.loading,
                )
                if (sample.changed) {
                    state.progressChanged(webView, sample.progress)
                    update(sample.progress)
                    callbacks().onEvent(WebViewEvent.ProgressChanged(sample.progress))
                }
                if (sample.completed) break
                delay(PROGRESS_SAMPLE_INTERVAL_MILLIS)
            }
        }
    }

    fun stop() {
        generation++
        job?.cancel()
        job = null
    }

    fun show(progress: Int) {
        view?.apply {
            setProgress(progress.coerceIn(0, 99) / 100f, animated = false)
            hidden = false
        }
    }

    fun hide() {
        view?.apply {
            setProgress(1f, animated = false)
            hidden = true
        }
    }

    fun release() {
        stop()
        view?.removeFromSuperview()
        view = null
    }

    private fun update(progress: Int) {
        if (progress >= 100) {
            hide()
        } else {
            view?.apply {
                hidden = false
                setProgress(progress.coerceAtLeast(0) / 100f, animated = true)
            }
        }
    }
}

private const val PROGRESS_SAMPLE_INTERVAL_MILLIS = 50L
