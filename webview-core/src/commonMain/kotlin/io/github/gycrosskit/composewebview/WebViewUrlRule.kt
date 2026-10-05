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
    data class HostSuffix(
        val suffix: String,
        /** 可选协议限定；与域名条件同时满足，避免把 HTTP 商城导航当作 HTTPS 接管。 */
        val scheme: String? = null,
        /** false 只匹配子域，保留父域页面在 WebView 内的原有行为。 */
        val includeRoot: Boolean = true,
        /** 拒绝 authority 中任何 userinfo，包括空 @；默认 false 保留旧的 URL 匹配行为。 */
        val rejectUserInfo: Boolean = false,
    ) : WebViewUrlRule {
        private val normalizedSuffix = normalizeRuleHost(suffix)
        init { require(scheme == null || scheme.matches(Regex("[a-z][a-z0-9+.-]*"))) }

        override fun matches(url: String): Boolean {
            if (rejectUserInfo && (!url.contains("://") || url.trim().any { it.code <= 0x20 || it == '\\' })) return false
            if (rejectUserInfo && url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#').contains('@')) return false
            if (scheme != null && runCatching { Url(url.trim()).protocol.name.lowercase() }.getOrNull() != scheme) return false
            val host = parsedHost(url) ?: return false
            return (includeRoot && host == normalizedSuffix) || host.endsWith(".$normalizedSuffix")
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
    val rawHost = Url(value.trim()).host.lowercase()
    if (rawHost.startsWith('.') || rawHost.endsWith("..")) null
    else rawHost.removeSuffix(".").takeIf { it.isNotBlank() && it.split('.').all(String::isNotBlank) }
}.getOrNull()
