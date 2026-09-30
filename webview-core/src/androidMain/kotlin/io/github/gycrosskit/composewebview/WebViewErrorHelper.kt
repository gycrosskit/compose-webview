package io.github.gycrosskit.composewebview

import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebViewClient

/**
 * WebView 平台错误判定的唯一映射入口。
 *
 * 平台回调先在这里转换为不依赖业务资源的 [WebViewLoadError]。只有主帧失败会被转换为整页错误，
 * 图片、脚本等子资源失败返回 `null`，避免可用页面被错误页覆盖。
 */
object WebViewErrorHelper {

    /** 将现代 WebView 资源错误转换为主帧页面错误；子资源错误返回 `null`。 */
    fun fromResourceError(
        request: WebResourceRequest,
        error: WebResourceError,
    ): WebViewLoadError? {
        if (!request.isForMainFrame) return null
        val code = error.errorCode
        val kind = kindFromErrorCode(code)
        val description = error.description?.toString().orEmpty()
        return WebViewLoadError(
            kind = kind,
            message = description,
            url = request.url?.toString(),
            errorCode = code,
            isMainFrame = true,
        )
    }

    /** 将主帧 HTTP 非成功响应转换为页面错误；子资源响应返回 `null`。 */
    fun fromHttpError(
        request: WebResourceRequest,
        response: WebResourceResponse,
    ): WebViewLoadError? {
        if (!request.isForMainFrame) return null
        val status = response.statusCode
        if (status < 400) return null
        return WebViewLoadError(
            kind = WebViewErrorKind.HTTP,
            url = request.url?.toString(),
            httpStatus = status,
            isMainFrame = true,
        )
    }

    /** 将 WebView 渲染进程退出转换为需要重建实例的错误。 */
    fun fromRenderProcessGone(didCrash: Boolean): WebViewLoadError {
        return WebViewLoadError(
            kind = WebViewErrorKind.RENDER_PROCESS,
            message = if (didCrash) "WebView render process crashed" else "WebView render process reclaimed",
        )
    }

    /** 将已被 Client 拒绝继续加载的 SSL 错误转换为页面错误。 */
    fun fromSslError(url: String?): WebViewLoadError {
        return WebViewLoadError(
            kind = WebViewErrorKind.SSL,
            url = url,
        )
    }

    /** 表示业务侧没有提供可加载的 URL 或 HTML。 */
    fun fromEmptyContent(): WebViewLoadError {
        return WebViewLoadError(
            kind = WebViewErrorKind.EMPTY_CONTENT,
        )
    }

    private fun kindFromErrorCode(code: Int): WebViewErrorKind {
        return when (code) {
            WebViewClient.ERROR_HOST_LOOKUP,
            WebViewClient.ERROR_CONNECT,
            WebViewClient.ERROR_TIMEOUT,
            WebViewClient.ERROR_IO,
            WebViewClient.ERROR_PROXY_AUTHENTICATION,
            WebViewClient.ERROR_AUTHENTICATION,
            -> WebViewErrorKind.NETWORK
            WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> WebViewErrorKind.SSL
            else -> WebViewErrorKind.UNKNOWN
        }
    }
}
