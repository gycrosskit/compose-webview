package io.github.gycrosskit.composewebview

import android.webkit.WebView
import java.util.Collections
import java.util.WeakHashMap

/**
 * WebView 生命周期诊断和存活门禁的唯一入口。
 *
 * 日志中的 URL 保留查询参数值和 fragment，便于直接核对传入 WebView 的完整地址。
 * 使用弱引用记录实例状态，不延长 WebView 生命周期；未由 [AppWebView] 创建的实例默认视为可用。
 */
object WebViewDiagnostics {
    /**
     * Key 使用弱引用，诊断门禁不会延长 WebView 生命周期；同步包装用于兼容主线程外的迟到 SDK 回调。
     */
    private val viewStates = Collections.synchronizedMap(WeakHashMap<WebView, DiagnosticState>())

    /** 注册一个刚创建的 WebView，并输出便于串联后续事件的实例 ID。 */
    fun created(
        view: WebView,
        role: String = "main",
        pageEnteredAtMillis: Long? = null,
        creationDurationMillis: Long? = null,
    ) {
        val trace = WebViewPerformanceTrace(
            platform = "android",
            instanceId = view.logId(),
            role = role,
            pageEnteredAtMillis = pageEnteredAtMillis,
        )
        viewStates[view] = DiagnosticState(trace = trace)
        trace.created(creationDurationMillis)
    }

    /** 在销毁前立即标记，阻止已经排队的页面和 JS 回调继续操作实例。 */
    fun markReleased(view: WebView, renderProcessGone: Boolean = false) {
        viewStates[view]?.apply {
            released = true
            trace.released(view.url)
        }
        log(
            AppWebViewLogLevel.INFO,
            "release-state platform=android id=${view.logId()} renderProcessGone=$renderProcessGone",
        )
    }

    /** destroy 调用链已经执行完毕。 */
    internal fun releaseFinished(view: WebView) {
        log(AppWebViewLogLevel.INFO, "release-finished id=${view.logId()}")
    }

    /** 记录单个释放步骤异常，调用方仍会继续执行后续步骤。 */
    internal fun releaseFailure(view: WebView, step: String, throwable: Throwable) {
        log(
            AppWebViewLogLevel.WARNING,
            "release-step-failed id=${view.logId()} step=$step",
            throwable,
        )
    }

    /** 返回 false 表示实例已经进入公共释放流程，调用方必须停止 load/evaluate 等操作。 */
    internal fun isActive(view: WebView): Boolean = viewStates[view]?.released != true

    /** 记录去重后真正提交给 WebView 的声明式内容。 */
    internal fun load(view: WebView, content: WebViewContent) {
        viewStates[view]?.trace?.load(content)
    }

    /** 记录主框架导航开始和完整 URL。 */
    internal fun pageStarted(view: WebView, url: String?) {
        viewStates[view]?.trace?.pageStarted(url)
    }

    /** 记录没有整页错误的主框架导航完成。 */
    internal fun pageFinished(view: WebView, url: String?) {
        viewStates[view]?.trace?.pageFinished(url)
    }

    /** 记录主文档首次提交到渲染树的时间点。 */
    internal fun firstContentVisible(view: WebView, url: String?) {
        viewStates[view]?.trace?.firstContentVisible(url)
    }

    internal fun performanceMetric(
        view: WebView,
        name: WebViewPerformanceMetric,
        navigationDurationMillis: Long,
    ) {
        viewStates[view]?.trace?.performanceMetric(name, navigationDurationMillis)
    }

    /** 记录被实例绑定或释放门禁拒绝的迟到回调。 */
    internal fun ignoredCallback(view: WebView, callback: String) {
        log(AppWebViewLogLevel.DEBUG, "ignore stale callback=$callback id=${view.logId()}")
    }

    /** 记录稳定错误分类、状态码和完整 URL。 */
    internal fun loadFailure(view: WebView, error: WebViewLoadError) {
        viewStates[view]?.trace?.pageFailed(error)
    }

    /** Bridge 只记录生命周期和 handler 名，不记录可能携带用户数据的消息正文。 */
    internal fun bridge(event: String, view: WebView? = null, handlerName: String? = null) {
        log(
            AppWebViewLogLevel.DEBUG,
            buildString {
                append("bridge-").append(event)
                view?.let { append(" id=").append(it.logId()) }
                handlerName?.let { append(" handler=").append(it.take(80)) }
            },
        )
    }

    private fun WebView.logId(): String = Integer.toHexString(System.identityHashCode(this))

    private fun log(
        level: AppWebViewLogLevel,
        message: String,
        error: Throwable? = null,
    ) {
        AppWebViewRuntime.log(level, message, error)
    }

    private data class DiagnosticState(
        val trace: WebViewPerformanceTrace,
        var released: Boolean = false,
    )
}

/** 供业务 WebView 回调在注入脚本、继续导航前执行统一的存活检查。 */
fun WebView.isActiveAppWebView(): Boolean = WebViewDiagnostics.isActive(this)
