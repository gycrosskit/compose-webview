package io.github.gycrosskit.composewebview

import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 统一处理主帧级 WebView 生命周期和错误的 Client。
 *
 * 子资源失败不会覆盖整页内容；主帧网络、HTTP、SSL 和渲染进程错误通过
 * [Listener.onPageLoadFailed] 汇总。一次导航最多派发一次失败，避免多个系统回调重复刷新 UI。
 */
open class AppWebViewClient(
    protected val listener: Listener,
) : WebViewClient() {

    /** 接收主帧加载状态；实现方不应在回调中直接持有 WebView。 */
    interface Listener {
        /** 新的主帧导航开始。 */
        fun onPageLoadStarted(url: String?) {}

        /** 主帧成功结束且本次导航没有已记录错误。 */
        fun onPageLoadFinished(url: String?) {}

        /** Chromium 已提交首个可绘制主文档；同一实例只由状态层消费第一次。 */
        fun onPageCommitVisible(url: String?) {}

        /** 主帧加载失败，错误已转换为平台无关的页面模型。 */
        fun onPageLoadFailed(error: WebViewLoadError)
    }

    /** 系统可能先报告错误再回调 onPageFinished，因此暂存到本轮主框架导航结束。 */
    private var pendingError: WebViewLoadError? = null
    /** SSL、渲染进程和结束回调可能报告同一失败，只允许向状态层派发一次。 */
    private var failureDispatched = false
    /** 极少数旧内核不回调 commit visible，完成时需要补发一次首屏可见。 */
    private var commitVisibleDispatched = false

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        if (!view.isActiveAppWebView()) {
            WebViewDiagnostics.ignoredCallback(view, "client-page-start")
            return
        }
        pendingError = null
        failureDispatched = false
        commitVisibleDispatched = false
        WebViewDiagnostics.pageStarted(view, url)
        listener.onPageLoadStarted(url)
        super.onPageStarted(view, url, favicon)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        if (!view.isActiveAppWebView()) {
            WebViewDiagnostics.ignoredCallback(view, "client-page-finish")
            return
        }
        val error = pendingError
        if (error != null) {
            dispatchFailure(error)
        } else {
            dispatchCommitVisible(view, url)
            WebViewDiagnostics.pageFinished(view, url)
            listener.onPageLoadFinished(url)
        }
        super.onPageFinished(view, url)
    }

    override fun onPageCommitVisible(view: WebView, url: String?) {
        if (!view.isActiveAppWebView()) {
            WebViewDiagnostics.ignoredCallback(view, "client-page-commit-visible")
            return
        }
        dispatchCommitVisible(view, url)
        super.onPageCommitVisible(view, url)
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        WebViewErrorHelper.fromResourceError(request, error)?.let {
            pendingError = it
            WebViewDiagnostics.loadFailure(view, it)
        }
        super.onReceivedError(view, request, error)
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        WebViewErrorHelper.fromHttpError(request, errorResponse)?.let {
            pendingError = it
            WebViewDiagnostics.loadFailure(view, it)
        }
        super.onReceivedHttpError(view, request, errorResponse)
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        val loadError = WebViewErrorHelper.fromSslError(error.url)
        pendingError = loadError
        WebViewDiagnostics.loadFailure(view, loadError)
        dispatchFailure(loadError)
        super.onReceivedSslError(view, handler, error)
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val loadError = WebViewErrorHelper.fromRenderProcessGone(detail.didCrash())
            pendingError = loadError
            WebViewDiagnostics.loadFailure(view, loadError)
            dispatchFailure(loadError)
            return true
        }
        return super.onRenderProcessGone(view, detail)
    }

    /**
     * 主动上报整页失败，例如加载地址由接口返回而接口本身失败。
     * 与系统错误共用单次派发保护，同一轮导航中重复调用只通知一次。
     */
    fun reportFailure(error: WebViewLoadError) {
        pendingError = error
        dispatchFailure(error)
    }

    /** 保证一次主框架导航最多产生一个整页错误状态。 */
    private fun dispatchFailure(error: WebViewLoadError) {
        if (failureDispatched) return
        failureDispatched = true
        listener.onPageLoadFailed(error)
    }

    private fun dispatchCommitVisible(view: WebView, url: String?) {
        if (commitVisibleDispatched) return
        commitVisibleDispatched = true
        WebViewDiagnostics.firstContentVisible(view, url)
        listener.onPageCommitVisible(url)
    }
}
