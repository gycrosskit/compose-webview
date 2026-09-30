package io.github.gycrosskit.composewebview

import io.ktor.http.Url
import io.ktor.http.decodeURLPart

/**
 * 清理网页在标题初始化阶段可能产生的控制符和零宽占位符。
 *
 * 可见标题才允许覆盖 App 页面标题；只含零宽字符的 `document.title` 在界面上等同空值。
 */
fun String?.normalizedWebViewTitle(): String? {
    val value = this
        ?.filterNot { character ->
            character.isISOControl() || character in WEB_TITLE_INVISIBLE_CHARACTERS
        }
        ?.trim()
        .orEmpty()
    return value.takeIf { title -> title.any { !it.isWhitespace() } }
}

private val WEB_TITLE_INVISIBLE_CHARACTERS = setOf(
    '\u200B',
    '\u200C',
    '\u200D',
    '\u2060',
    '\uFEFF',
)

/** URL、域名及路径占位标题统一在共享页面边界过滤。 */
fun usableWebTitle(title: String?, pageUrl: String?): String? {
    val value = title.normalizedWebViewTitle() ?: return null
    if (value.equals("about:blank", ignoreCase = true)) return null
    if (value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)) return null
    if ('/' in value || '\\' in value) return null
    val page = runCatching { pageUrl?.takeIf(String::isNotBlank)?.let(::Url) }.getOrNull()
    val segment = page?.encodedPath?.trimEnd('/')?.substringAfterLast('/').orEmpty()
    val decodedSegment = runCatching { segment.decodeURLPart() }.getOrDefault(segment)
    if (segment.isNotEmpty() && (value.equals(segment, true) || value.equals(decodedSegment, true))) return null
    if (page?.host?.let { value.equals(it, ignoreCase = true) } == true) return null
    return value
}
