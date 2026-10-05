package io.github.gycrosskit.composewebview

import io.ktor.http.Url

/** 无平台依赖的 URL 匹配规则，用于声明式导航和子资源过滤。 */
sealed interface WebViewUrlRule {
    /** 同步判断地址是否命中；实现不执行网络请求，host 规则对无效地址返回 false。 */
    fun matches(url: String): Boolean

    /**
     * 匹配完整 URL 中的稳定片段；不得用于鉴权来源判断。
     * @property value 非空匹配片段。
     * @property ignoreCase 是否忽略大小写，默认 false。
     */
    data class Contains(
        val value: String,
        val ignoreCase: Boolean = false,
    ) : WebViewUrlRule {
        init {
            require(value.isNotEmpty()) { "URL 匹配片段不能为空" }
        }

        override fun matches(url: String): Boolean = url.contains(value, ignoreCase = ignoreCase)
    }

    /** 精确匹配 host，不比较路径、查询参数和端口。
     * @property host 目标域名，构造时规范化大小写与末尾点。
     */
    data class ExactHost(val host: String) : WebViewUrlRule {
        private val normalizedHost = normalizeRuleHost(host)

        override fun matches(url: String): Boolean = parsedHost(url) == normalizedHost
    }

    /** 匹配目标域及其子域，避免 `example.com.evil.test` 一类相似域绕过。
     * @property suffix 目标域后缀，按完整域标签边界匹配。
     */
    data class HostSuffix(val suffix: String) : WebViewUrlRule {
        private val normalizedSuffix = normalizeRuleHost(suffix)

        override fun matches(url: String): Boolean {
            val host = parsedHost(url) ?: return false
            return host == normalizedSuffix || host.endsWith(".$normalizedSuffix")
        }
    }
}

private fun normalizeRuleHost(value: String): String {
    val normalized = value.trim().trimStart('.').trimEnd('.').lowercase()
    require(
        normalized.isNotBlank() &&
            !normalized.contains('/') &&
            !normalized.contains(':') &&
            normalized.split('.').all(String::isNotBlank),
    ) { "无效 host: $value" }
    return normalized
}

private fun parsedHost(value: String): String? = runCatching {
    Url(value.trim()).host.lowercase().trimEnd('.').takeIf(String::isNotBlank)
}.getOrNull()
