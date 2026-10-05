package io.github.gycrosskit.composewebview

/**
 * 把中立 URL 规则编码为 WebKit Content Blocker JSON。
 *
 * 该格式只由 iOS WKWebView 消费，因此实现留在 iosMain；纯 Kotlin 写法仅用于让 iosTest 固化转义和相似域边界。
 */
internal fun List<WebViewUrlRule>.toWebKitContentRuleList(): String = joinToString(
    prefix = "[",
    postfix = "]",
    separator = ",",
) { rule ->
    val filter = when (rule) {
        is WebViewUrlRule.Contains -> ".*${rule.value.regexEscaped()}.*"
        is WebViewUrlRule.ExactHost ->
            "^[a-zA-Z][a-zA-Z0-9+.-]*://${rule.host.normalizedRuleHost().regexEscaped()}" +
                "(:[0-9]+)?(/.*)?$"
        is WebViewUrlRule.HostSuffix ->
            "^${rule.scheme?.regexEscaped() ?: "[a-zA-Z][a-zA-Z0-9+.-]*"}://(${if (rule.rejectUserInfo) "[^./:@]+" else "[^./]+"}\\.)${if (rule.includeRoot) "*" else "+"}" +
                "${rule.suffix.normalizedRuleHost().regexEscaped()}(:[0-9]+)?(/.*)?$"
    }
    val caseSensitive = rule is WebViewUrlRule.Contains && !rule.ignoreCase
    """{"trigger":{"url-filter":"${filter.jsonEscaped()}","url-filter-is-case-sensitive":$caseSensitive},"action":{"type":"block"}}"""
}

private fun String.regexEscaped(): String = buildString(length * 2) {
    this@regexEscaped.forEach { character ->
        if (character in REGEX_META_CHARACTERS) append('\\')
        append(character)
    }
}

/** JSON 字符串转义必须覆盖控制字符，否则合法的 Contains 规则可能让整批 WebKit 规则编译失败。 */
private fun String.jsonEscaped(): String = buildString(length) {
    this@jsonEscaped.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < JSON_CONTROL_CHARACTER_LIMIT) {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
}

private fun String.normalizedRuleHost(): String = trim().trimStart('.').trimEnd('.').lowercase()

private const val JSON_CONTROL_CHARACTER_LIMIT = 0x20
private const val REGEX_META_CHARACTERS = "\\.^$|?*+()[]{}-"
