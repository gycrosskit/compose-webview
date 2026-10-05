package io.github.gycrosskit.composewebview

import kotlin.time.TimeSource

/**
 * 记录完整页面地址与单调时钟耗时，便于从日志直接还原 H5 现场。
 * 同一实例的调用由所属平台 UI 线程串行执行；日志保留完整 URL，宿主日志接收器应控制敏感数据留存。
 * @param platform 平台诊断标识。
 * @param instanceId 网页实例标识，不使用用户或设备标识。
 * @param role 实例角色，默认 main。
 * @param pageEnteredAtMillis 页面进入的同源单调时钟毫秒值，默认 null 表示未知；不可传 epoch 时间。
 * @param nowMillis 单调毫秒时钟，默认 [webViewMonotonicNowMillis]，测试可注入。
 */
class WebViewPerformanceTrace(
    private val platform: String,
    private val instanceId: String,
    private val role: String = "main",
    private val pageEnteredAtMillis: Long? = null,
    private val nowMillis: () -> Long = ::webViewMonotonicNowMillis,
) {
    private val createdAtMillis = nowMillis()
    private var loadRequestedAtMillis: Long? = null
    private var pageStartedAtMillis: Long? = null

    /** 记录原生构造耗时，单位毫秒；null 表示未知，负值按 0 记录。 */
    fun created(creationDurationMillis: Long? = null) {
        val creation = creationDurationMillis?.coerceAtLeast(0L) ?: -1L
        log(
            AppWebViewLogLevel.INFO,
            "created entryToCreateMs=${createdAtMillis.since(pageEnteredAtMillis)} " +
                "constructorMs=$creation",
        )
    }

    /** 开始一次声明式加载并重置本次导航计时；HTML 仅记录长度与基准地址。 */
    fun load(content: WebViewContent) {
        val now = nowMillis()
        loadRequestedAtMillis = now
        pageStartedAtMillis = null
        log(
            AppWebViewLogLevel.INFO,
            "load createdToLoadMs=${now.since(createdAtMillis)} target=${describeWebViewContent(content)}",
        )
    }

    /** 记录主文档开始，url 可为 null。 */
    fun pageStarted(url: String?) {
        val now = nowMillis()
        pageStartedAtMillis = now
        log(
            AppWebViewLogLevel.INFO,
            "page-start loadToStartMs=${now.since(loadRequestedAtMillis)} url=${describeWebViewUrl(url)}",
        )
    }

    /** 记录主文档完成；不宣称所有子资源都已加载。 */
    fun pageFinished(url: String?) {
        val now = nowMillis()
        log(
            AppWebViewLogLevel.INFO,
            "page-finish loadToFinishMs=${now.since(loadRequestedAtMillis)} " +
                "startToFinishMs=${now.since(pageStartedAtMillis)} url=${describeWebViewUrl(url)}",
        )
    }

    /** 记录首个可绘制内容相对进入、加载和导航的毫秒耗时。 */
    fun firstContentVisible(url: String?) {
        val now = nowMillis()
        log(
            AppWebViewLogLevel.INFO,
            "first-visible entryToVisibleMs=${now.since(pageEnteredAtMillis)} " +
                "loadToVisibleMs=${now.since(loadRequestedAtMillis)} " +
                "startToVisibleMs=${now.since(pageStartedAtMillis)} url=${describeWebViewUrl(url)}",
        )
    }

    /** 记录页面 Navigation/Paint Timing 指标，单位毫秒，负值按 0 记录。 */
    fun performanceMetric(name: WebViewPerformanceMetric, navigationDurationMillis: Long) {
        log(
            AppWebViewLogLevel.INFO,
            "web-metric name=$name navigationDurationMs=${navigationDurationMillis.coerceAtLeast(0L)}",
        )
    }

    /** 记录失败类型、错误码及完整来源地址，不解释业务错误正文。 */
    fun pageFailed(error: WebViewLoadError) {
        val now = nowMillis()
        log(
            AppWebViewLogLevel.WARNING,
            "page-failed loadToFailureMs=${now.since(loadRequestedAtMillis)} " +
                "kind=${error.kind} code=${error.errorCode} http=${error.httpStatus} " +
                "url=${describeWebViewUrl(error.url)}",
        )
    }

    /** 记录所属原生实例释放及其总生命周期耗时，单位毫秒。 */
    fun released(url: String?) {
        val now = nowMillis()
        log(
            AppWebViewLogLevel.INFO,
            "release lifetimeMs=${now.since(createdAtMillis)} url=${describeWebViewUrl(url)}",
        )
    }

    /** 记录停媒/恢复的发起与完成，reason 为平台生命周期标签。 */
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

/** 生成诊断描述；URL 保留原值，HTML 正文仅记录长度，baseUrl 仍为完整地址。 */
fun describeWebViewContent(content: WebViewContent): String = when (content) {
    is WebViewContent.Url -> describeWebViewUrl(content.url)
    is WebViewContent.Html ->
        "html(length=${content.html.length}, base=${rawWebViewUrl(content.baseUrl)})"
}

private fun rawWebViewUrl(value: String?): String {
    val raw = value?.trim().orEmpty()
    return raw.ifBlank { "<empty>" }
}

/** 返回裁剪两端空白后的完整地址与字符数；不自动脱敏查询参数。 */
fun describeWebViewUrl(value: String?): String {
    val raw = value?.trim().orEmpty()
    return "${rawWebViewUrl(value)} rawLength=${raw.length}"
}

private fun Long.since(startMillis: Long?): Long = startMillis?.let { (this - it).coerceAtLeast(0L) } ?: -1L

private val monotonicOrigin = TimeSource.Monotonic.markNow()
/** 返回本进程共用单调时钟起点的毫秒值，适用于耗时差值，不能当作 Unix 时间。 */
fun webViewMonotonicNowMillis(): Long = monotonicOrigin.elapsedNow().inWholeMilliseconds
