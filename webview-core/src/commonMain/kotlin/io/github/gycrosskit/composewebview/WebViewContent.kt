package io.github.gycrosskit.composewebview

/** 平台网页组件可加载内容的不可变描述。 */
sealed interface WebViewContent {
    /**
     * 远程页面地址。
     *
     * [additionalHeaders] 只随本次顶层请求发送，不能视为全部子资源的通用请求头。
     * @property url 非空页面地址；可能包含敏感查询参数，日志策略由宿主负责。
     * @property additionalHeaders 本次主文档请求头，默认空；不得写入换行或不可信凭据。
     */
    data class Url(
        val url: String,
        val additionalHeaders: Map<String, String> = emptyMap(),
    ) : WebViewContent

    /**
     * 直接加载的 HTML 文本。
     *
     * [baseUrl] 决定相对链接解析和页面来源；高权限能力必须使用可被信任策略验证的 HTTPS 地址。
     * @property html 非空 HTML 正文，可能包含业务敏感内容。
     * @property baseUrl 相对地址基准，默认 null；无基准时不能获得来源授权。
     * @property mimeType MIME 类型，默认 text/html；OHOS 接受合法 type/subtype 并传递给 ArkWeb，实际渲染范围由内核决定。
     * @property encoding 文本编码，默认 UTF-8；平台支持范围由原生内核决定。iOS 未知或无法无损表示的编码报告 LOAD_EXCEPTION。
     * @property historyUrl 历史记录显示地址，默认 null；不会替代 baseUrl 的来源授权。iOS 只接受 null 或与 baseUrl 相等，其他值报告 LOAD_EXCEPTION。
     */
    data class Html(
        val html: String,
        val baseUrl: String? = null,
        val mimeType: String = "text/html",
        val encoding: String = "UTF-8",
        val historyUrl: String? = null,
    ) : WebViewContent
}
