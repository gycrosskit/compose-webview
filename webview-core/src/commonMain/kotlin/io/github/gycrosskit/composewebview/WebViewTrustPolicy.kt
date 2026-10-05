package io.github.gycrosskit.composewebview

import io.ktor.http.Url

/**
 * 高权限能力的 HTTPS 来源白名单，路径、查询参数和 fragment 不参与授权。
 *
 * 精确地址按 scheme、host、有效端口匹配；域后缀允许目标域及其子域，并沿用现有兼容语义，不限制 HTTPS
 * 端口。无效地址和非 HTTPS 地址不会进入白名单。实例不可变，切换页面时应创建新策略。
 * @param urls 精确 HTTPS 地址，默认空；复制并标准化来源，不保留路径、查询和 fragment。
 * @param trustedHostSuffixes 域及子域的 HTTPS 授权后缀，默认空；使用最小必要范围。
 */
class WebViewTrustPolicy(
    urls: Collection<String> = emptyList(),
    trustedHostSuffixes: Set<String> = emptySet(),
) {
    private val origins: Set<WebViewOrigin> = urls.mapNotNull(::originOf).toSet()
    private val hostSuffixes: Set<String> = trustedHostSuffixes.mapNotNull(::normalizeHostSuffix).toSet()

    /** 用于原生 wire 的标准化 HTTPS 来源，不包含路径和查询数据。 */
    val urls: Set<String>
        get() = origins.map { "https://${it.host}" + if (it.port == 443) "" else ":${it.port}" }.toSet()

    /** 已复制并规范化的域后缀；不含前后点，空集合不授权任何域。 */
    val trustedHostSuffixes: Set<String>
        get() = hostSuffixes

    /** 当前策略是否没有任何有效可信来源。 */
    val isEmpty: Boolean
        get() = origins.isEmpty() && hostSuffixes.isEmpty()

    /** 空值、无效地址和非 HTTPS 地址均不可信。 */
    fun isTrusted(url: String?): Boolean {
        val origin = url?.let(::originOf) ?: return false
        return origin in origins || hostSuffixes.any { suffix ->
            origin.host == suffix || origin.host.endsWith(".$suffix")
        }
    }

    /** AndroidX WebKit document-start API 可表达的 HTTPS 来源规则。 */
    internal fun documentStartOriginRules(): Set<String> = buildSet {
        origins.forEach { origin ->
            add(
                if (origin.port == HTTPS_DEFAULT_PORT) {
                    "$HTTPS_SCHEME://${origin.host}"
                } else {
                    "$HTTPS_SCHEME://${origin.host}:${origin.port}"
                },
            )
        }
        hostSuffixes.forEach { suffix ->
            add("$HTTPS_SCHEME://$suffix")
            add("$HTTPS_SCHEME://*.$suffix")
        }
    }

    /** WKUserScript 没有来源过滤参数，使用同一份标准化策略生成页面内只读门禁。 */
    internal fun javascriptTrustExpression(): String {
        val exact = origins.joinToString(" || ") { origin ->
            val port = origin.port.toString()
            "(location.hostname === '${origin.host}' && " +
                "(location.port || '$HTTPS_DEFAULT_PORT') === '$port')"
        }
        val suffixes = hostSuffixes.joinToString(" || ") { suffix ->
            "(location.hostname === '$suffix' || location.hostname.endsWith('.$suffix'))"
        }
        val hostExpression = listOf(exact, suffixes).filter(String::isNotBlank).joinToString(" || ")
        return if (hostExpression.isBlank()) {
            "false"
        } else {
            "location.protocol === 'https:' && ($hostExpression)"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is WebViewTrustPolicy && origins == other.origins && hostSuffixes == other.hostSuffixes

    override fun hashCode(): Int = 31 * origins.hashCode() + hostSuffixes.hashCode()

    override fun toString(): String =
        "WebViewTrustPolicy(origins=${origins.size}, hostSuffixes=${hostSuffixes.size})"

    private fun originOf(value: String): WebViewOrigin? = runCatching {
        val parsed = Url(value.trim())
        val scheme = parsed.protocol.name.lowercase()
        val host = parsed.host.lowercase().trimEnd('.')
        if (scheme != HTTPS_SCHEME || host.isBlank()) return null
        WebViewOrigin(scheme = scheme, host = host, port = parsed.port)
    }.getOrNull()

    private fun normalizeHostSuffix(value: String): String? = value
        .trim()
        .trimStart('.')
        .trimEnd('.')
        .lowercase()
        .takeIf { suffix ->
            suffix.isNotBlank() &&
                !suffix.contains('/') &&
                !suffix.contains(':') &&
                suffix.split('.').all { label ->
                    label.isNotBlank() &&
                        label.length <= MAX_HOST_LABEL_LENGTH &&
                        label.first().isLetterOrDigit() &&
                        label.last().isLetterOrDigit() &&
                        label.all { it.isLetterOrDigit() || it == '-' }
                }
        }

    private data class WebViewOrigin(
        val scheme: String,
        val host: String,
        val port: Int,
    )

    private companion object {
        const val HTTPS_SCHEME = "https"
        const val HTTPS_DEFAULT_PORT = 443
        const val MAX_HOST_LABEL_LENGTH = 63
    }
}
