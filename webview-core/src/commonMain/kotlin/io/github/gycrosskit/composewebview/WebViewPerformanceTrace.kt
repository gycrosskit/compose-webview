package io.github.gycrosskit.composewebview

import kotlin.time.TimeSource

/** 记录完整页面地址与单调时钟耗时，便于从日志直接还原 H5 现场。 */
class WebViewPerformanceTrace(
    private val platform: String,
    private val instanceId: String,
    private val role: String = "main",
    private val pageEnteredAtMillis: Long? = null,
    private val nowMillis: () -> Long = ::monotonicNowMillis,
) {
    private val createdAtMillis = nowMillis()
    private var loadRequestedAtMillis: Long? = null
    private var pageStartedAtMillis: Long? = null

    fun created(creationDurationMillis: Long? = null) {
        val creation = creationDurationMillis?.coerceAtLeast(0L) ?: -1L
        log(
            AppWebViewLogLevel.INFO,
            "created entryToCreateMs=${createdAtMillis.since(pageEnteredAtMillis)} " +
                "constructorMs=$creation",
        )
    }

    fun load(content: WebViewContent) {
        val now = nowMillis()
        loadRequestedAtMillis = now
        pageStartedAtMillis = null
        log(
            AppWebViewLogLevel.INFO,
            "load createdToLoadMs=${now.since(createdAtMillis)} target=${describeWebViewContent(content)}",
        )
    }

    fun pageStarted(url: String?) {
        val now = nowMillis()
        pageStartedAtMillis = now
        log(
            AppWebViewLogLevel.INFO,
            "page-start loadToStartMs=${now.since(loadRequestedAtMillis)} url=${describeWebViewUrl(url)}",
        )
    }

    fun pageFinished(url: String?) {
        val now = nowMillis()
        log(
            AppWebViewLogLevel.INFO,
            "page-finish loadToFinishMs=${now.since(loadRequestedAtMillis)} " +
                "startToFinishMs=${now.since(pageStartedAtMillis)} url=${describeWebViewUrl(url)}",
        )
    }

    fun firstContentVisible(url: String?) {
        val now = nowMillis()
        log(
            AppWebViewLogLevel.INFO,
            "first-visible entryToVisibleMs=${now.since(pageEnteredAtMillis)} " +
                "loadToVisibleMs=${now.since(loadRequestedAtMillis)} " +
                "startToVisibleMs=${now.since(pageStartedAtMillis)} url=${describeWebViewUrl(url)}",
        )
    }

    fun performanceMetric(name: WebViewPerformanceMetric, navigationDurationMillis: Long) {
        log(
            AppWebViewLogLevel.INFO,
            "web-metric name=$name navigationDurationMs=${navigationDurationMillis.coerceAtLeast(0L)}",
        )
    }

    fun pageFailed(error: WebViewLoadError) {
        val now = nowMillis()
        log(
            AppWebViewLogLevel.WARNING,
            "page-failed loadToFailureMs=${now.since(loadRequestedAtMillis)} " +
                "kind=${error.kind} code=${error.errorCode} http=${error.httpStatus} " +
                "url=${describeWebViewUrl(error.url)}",
        )
    }

    fun released(url: String?) {
        val now = nowMillis()
        log(
            AppWebViewLogLevel.INFO,
            "release lifetimeMs=${now.since(createdAtMillis)} url=${describeWebViewUrl(url)}",
        )
    }

    fun mediaSuspension(suspended: Boolean, reason: String, completed: Boolean) {
        log(
            AppWebViewLogLevel.INFO,
            "media-suspension suspended=$suspended reason=$reason completed=$completed " +
                "lifetimeMs=${nowMillis().since(createdAtMillis)}",
        )
    }

    private fun log(level: AppWebViewLogLevel, event: String) {
        AppWebViewRuntime.log(
            level,
            "$event platform=$platform role=$role id=$instanceId",
        )
    }
}

fun describeWebViewContent(content: WebViewContent): String = when (content) {
    is WebViewContent.Url -> describeWebViewUrl(content.url)
    is WebViewContent.Html ->
        "html(length=${content.html.length}, base=${rawWebViewUrl(content.baseUrl)})"
}

private fun rawWebViewUrl(value: String?): String {
    val raw = value?.trim().orEmpty()
    return raw.ifBlank { "<empty>" }
}

fun describeWebViewUrl(value: String?): String {
    val raw = value?.trim().orEmpty()
    return "${rawWebViewUrl(value)} rawLength=${raw.length}"
}

private fun Long.since(startMillis: Long?): Long = startMillis?.let { (this - it).coerceAtLeast(0L) } ?: -1L

private val monotonicOrigin = TimeSource.Monotonic.markNow()
fun webViewMonotonicNowMillis(): Long = monotonicOrigin.elapsedNow().inWholeMilliseconds

private fun monotonicNowMillis(): Long = webViewMonotonicNowMillis()
