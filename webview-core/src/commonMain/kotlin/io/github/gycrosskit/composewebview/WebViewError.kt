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

/**
 * 跨平台网页加载失败快照。
 *
 * [message] 是平台原始说明，只用于诊断或稳定文案无法覆盖时的兜底，不能直接承载业务提示语义。
 *
 * @property kind 稳定错误分类。
 * @property message 平台原始错误说明。
 * @property url 失败的主框架地址。
 * @property errorCode 平台网络错误码。
 * @property httpStatus HTTP 响应状态码。
 * @property isMainFrame 是否属于主文档失败。
 */
data class WebViewLoadError(
    val kind: WebViewErrorKind,
    val message: String = "",
    val url: String? = null,
    val errorCode: Int? = null,
    val httpStatus: Int? = null,
    val isMainFrame: Boolean = true,
)
