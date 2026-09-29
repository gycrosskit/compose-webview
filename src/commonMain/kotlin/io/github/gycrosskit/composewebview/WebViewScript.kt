package io.github.gycrosskit.composewebview

/** 脚本注入时机；平台不支持 document-start 时必须在首次可见回调执行等价兜底。 */
enum class WebViewScriptInjectionTime {
    DOCUMENT_START,
    DOM_READY,
    DOCUMENT_FINISHED,
}

/**
 * 平台网页组件执行的命名脚本。
 *
 * [onlyForTrustedMainFrame] 默认为 true，业务脚本不得通过关闭该门禁绕过来源校验。
 *
 * @property id 供诊断和去重使用的稳定脚本标识。
 * @property source 待执行的 JavaScript 源码。
 * @property injectionTime 脚本注入时机。
 * @property onlyForTrustedMainFrame 是否只在可信主文档中执行。
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
