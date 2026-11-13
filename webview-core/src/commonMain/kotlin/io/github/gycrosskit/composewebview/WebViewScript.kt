package io.github.gycrosskit.composewebview

/** DOCUMENT_START 允许旧内核延后补注入；DOCUMENT_START_REQUIRED 不允许这种兜底。 */
enum class WebViewScriptInjectionTime {
    DOCUMENT_START,
    /** 必须先于页面脚本执行；不支持时不得在页面可见或完成后补注入。 */
    DOCUMENT_START_REQUIRED,
    DOM_READY,
    DOCUMENT_FINISHED,
}

/**
 * 平台网页组件执行的命名脚本。
 *
 * [onlyForTrustedMainFrame] 默认为 true，业务脚本不得通过关闭该门禁绕过来源校验。
 *
 * @property id 供诊断和去重使用的稳定脚本标识。
 * @property source 非空可信 JavaScript 源码；不得直接拼接外部输入或记录敏感正文。
 * @property injectionTime 脚本注入时机，默认 DOM_READY。
 * @property onlyForTrustedMainFrame 是否限定获准主文档：可信 HTTP(S)，或明确 pageBridge 的初始同源 HTTP(S) 页面。
 */
data class WebViewScript(
    val id: String,
    val source: String,
    val injectionTime: WebViewScriptInjectionTime = WebViewScriptInjectionTime.DOM_READY,
    val onlyForTrustedMainFrame: Boolean = true,
) {
    init {
        require(id.isNotBlank()) { "WebViewScript.id 不能为空" }
        require(id.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }) {
            "WebViewScript.id 只能包含字母、数字、点、下划线和连字符"
        }
        require(source.isNotBlank()) { "WebViewScript.source 不能为空" }
    }
}
