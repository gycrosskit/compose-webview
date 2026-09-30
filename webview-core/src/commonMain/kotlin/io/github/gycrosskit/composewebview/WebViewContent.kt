package io.github.gycrosskit.composewebview

/** 平台网页组件可加载内容的不可变描述。 */
sealed interface WebViewContent {
    /**
     * 远程页面地址。
     *
     * [additionalHeaders] 只随本次顶层请求发送，不能视为全部子资源的通用请求头。
     */
    data class Url(
        val url: String,
        val additionalHeaders: Map<String, String> = emptyMap(),
    ) : WebViewContent

    /**
     * 直接加载的 HTML 文本。
     *
     * [baseUrl] 决定相对链接解析和页面来源；高权限能力必须使用可被信任策略验证的 HTTPS 地址。
     */
    data class Html(
        val html: String,
        val baseUrl: String? = null,
        val mimeType: String = "text/html",
        val encoding: String = "UTF-8",
        val historyUrl: String? = null,
    ) : WebViewContent
}
