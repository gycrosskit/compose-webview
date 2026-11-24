package io.github.gycrosskit.composewebview

/** 网页主文档整页级错误类型；子资源失败不计入。 */
enum class WebViewErrorKind {
    NETWORK,
    HTTP,
    SSL,
    RENDER_PROCESS,
    EMPTY_CONTENT,
    LOAD_EXCEPTION,
    TIMEOUT,
    UNKNOWN,
}

/** 平台回调能够确认的 TLS/证书原因；缺少证据时保持 null，不从通用 SSL 失败推断证书问题。 */
enum class WebViewSslReason {
    CERTIFICATE_EXPIRED,
    CERTIFICATE_NOT_YET_VALID,
    CERTIFICATE_DATE_INVALID,
    CERTIFICATE_HOST_MISMATCH,
    CERTIFICATE_UNTRUSTED,
    TLS_HANDSHAKE_FAILED,
    TLS_VERSION_OR_CIPHER_MISMATCH,
}

/**
 * 跨平台网页加载失败快照。
 *
 * [message] 是平台原始说明，只用于诊断或稳定文案无法覆盖时的兜底，不能直接承载业务提示语义。
 *
 * @property kind 稳定错误分类。
 * @property message 平台原始错误说明。
 * @property url 失败的主框架地址。
 * @property errorCode 平台网络或证书回调的原始错误码（包含有效的 0）。
 * @property httpStatus HTTP 响应状态码。
 * @property isMainFrame 是否属于主文档失败。
 * @property sslReason 有明确平台证据的 TLS/证书原因，未知或旧 wire 为 null。
 */
data class WebViewLoadError(
    val kind: WebViewErrorKind,
    val message: String = "",
    val url: String? = null,
    val errorCode: Int? = null,
    val httpStatus: Int? = null,
    val isMainFrame: Boolean = true,
    val sslReason: WebViewSslReason? = null,
)
