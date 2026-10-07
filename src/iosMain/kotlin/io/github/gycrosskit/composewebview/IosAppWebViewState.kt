package io.github.gycrosskit.composewebview

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLRequestReturnCacheDataDontLoad
import platform.Foundation.NSURLRequestReturnCacheDataElseLoad
import platform.Foundation.NSURLRequestUseProtocolCachePolicy
import platform.Foundation.create
import platform.Foundation.setValue
import platform.WebKit.WKWebView

/**
 * iOS WebView 的可观察状态与命令入口。
 *
 * [webView] 和加载状态只属于当前组合位置；原生 View 重建后，旧实例的异步回调不得覆盖新页面。
 */
@Stable
actual class AppWebViewState actual internal constructor() {
    private val mutableSnapshot = mutableStateOf(WebViewSnapshot())

    /** 当前主文档的不可变 UI 快照。 */
    actual val snapshot: WebViewSnapshot
        get() = mutableSnapshot.value

    /** 当前组合位置绑定的 WKWebView；释放时必须由 [detach] 清空。 */
    private var webView: WKWebView? = null

    private var loadState = IosWebViewLoadState()
    private var callbackGeneration = 0
    private val pendingFullscreenReplies = mutableSetOf<() -> Unit>()
    internal var fullscreenExitHandler: ((WKWebView, (Boolean) -> Unit) -> Boolean)? = null
    internal var javascriptAllowed: ((WKWebView) -> Boolean)? = null
    internal var pageMessageReply: ((String, String) -> Boolean)? = null
    internal var pageMessageCancel: (() -> Unit)? = null
    private val mutablePageMessageInstanceKey = mutableStateOf(0)
    internal val pageMessageInstanceKey: Int get() = mutablePageMessageInstanceKey.value
    internal var rebuildPageMessagesOnReload = false

    actual fun replyPageMessage(replyId: String, data: String): Boolean =
        pageMessageReply?.invoke(replyId, data) ?: false

    actual fun reload() {
        val target = webView ?: return
        if (rebuildPageMessagesOnReload) {
            pageMessageCancel?.invoke()
            invalidateJavascriptCallbacks()
            if (!isAttached(target)) return
            mutablePageMessageInstanceKey.value++
            return
        }
        val content = loadState.content ?: return
        if (!loadState.hasCommittedPage || content.isBlankWebViewContent()) {
            // 首航失败可能尚无可 reload 的文档；重放原始声明式请求，保留请求头和缓存策略。
            loadContent(target, content, loadState.cachePolicy)
            return
        }
        // 已有页面可能经过站内导航或 POST，不能用入口 GET 覆盖其当前位置。
        mutableSnapshot.value = snapshot.copy(isLoading = true, error = null, hasVisibleContent = false)
        target.reload()
    }

    /** 前台恢复仅补偿尚未提交的首次连接失败；手动重试不消耗或重置此预算。 */
    internal fun retryInitialNetworkFailure(): Boolean {
        val target = webView ?: return false
        if (!loadState.takeInitialNetworkRetry(snapshot)) return false
        AppWebViewRuntime.log(
            AppWebViewLogLevel.INFO,
            "initial-network-retry platform=ios role=main id=${target.hashCode().toString(16)} " +
                "code=${snapshot.error?.errorCode}",
        )
        loadContent(target, checkNotNull(loadState.content), loadState.cachePolicy)
        return true
    }

    actual fun goBack(): Boolean {
        val target = webView ?: return false
        if (requestFullscreenExit(target, { consumed -> if (!consumed) goBackInHistory(target) }, {})) return true
        return goBackInHistory(target)
    }

    actual fun goBack(callback: (Boolean) -> Unit) {
        val target = webView ?: run { callback(false); return }
        if (requestFullscreenExit(target, { consumed -> callback(consumed || goBackInHistory(target)) }, { callback(false) })) return
        callback(goBackInHistory(target))
    }

    actual fun goForward(): Boolean {
        val target = webView ?: return false
        if (!target.canGoForward) return false
        invalidateJavascriptCallbacks()
        if (!isAttached(target) || !target.canGoForward) return false
        pageMessageCancel?.invoke()
        target.goForward()
        updateNavigation(target)
        return true
    }

    actual fun exitFullscreen(callback: (Boolean) -> Unit) {
        val target = webView ?: run { callback(false); return }
        if (!requestFullscreenExit(target, callback, { callback(false) })) callback(false)
    }

    private fun requestFullscreenExit(
        target: WKWebView,
        callback: (Boolean) -> Unit,
        onCancelled: () -> Unit,
    ): Boolean {
        val generation = callbackGeneration
        var settled = false
        val cancel = { if (!settled) { settled = true; onCancelled() } }
        pendingFullscreenReplies += cancel
        val accepted = fullscreenExitHandler?.invoke(target) { consumed ->
            if (!settled) {
                settled = true
                pendingFullscreenReplies -= cancel
                // 取消交付 false，但不能作为脚本失败去回退下一份文档的历史。
                if (isAttached(target) && callbackGeneration == generation) callback(consumed)
                else onCancelled()
            }
        } == true
        if (!accepted) { settled = true; pendingFullscreenReplies -= cancel }
        return accepted
    }

    private fun goBackInHistory(target: WKWebView): Boolean {
        if (!isAttached(target) || !target.canGoBack) return false
        invalidateJavascriptCallbacks()
        if (!isAttached(target) || !target.canGoBack) return false
        pageMessageCancel?.invoke()
        target.goBack()
        updateNavigation(target)
        return true
    }

    actual fun stopLoading() {
        pageMessageCancel?.invoke()
        webView?.stopLoading()
        mutableSnapshot.value = snapshot.copy(isLoading = false)
    }

    actual fun evaluateJavascript(script: String, callback: ((String?) -> Unit)?) {
        val target = webView ?: return
        if (target.hidden || javascriptAllowed?.invoke(target) == false) return
        val generation = callbackGeneration
        target.evaluateJavaScript(script) { result, _ ->
            // 页面离开或安全配置变化会重建 WKWebView；旧实例的异步结果不能回写新页面。
            if (generation == callbackGeneration && isAttached(target) && !target.hidden && javascriptAllowed?.invoke(target) != false) callback?.invoke(result?.toString())
        }
    }

    /** 绑定新实例，并清除只属于上一实例的声明式内容身份。 */
    internal fun attach(target: WKWebView) {
        webView = target
        javascriptAllowed = null
        fullscreenExitHandler = null
        pageMessageReply = null
        pageMessageCancel = null
        rebuildPageMessagesOnReload = false
        loadState = IosWebViewLoadState()
        mutableSnapshot.value = snapshot.copy(hasVisibleContent = false)
        updateNavigation(target)
        // 取消回调可能同步绑定另一个 owner；先完成本次状态更新，避免覆盖它。
        invalidateJavascriptCallbacks()
    }

    /** 只解绑匹配实例，避免旧实例迟到释放时误清理已经重建的新实例。 */
    internal fun detach(target: WKWebView) {
        if (!isAttached(target)) return
        javascriptAllowed = null
        fullscreenExitHandler = null
        pageMessageReply = null
        pageMessageCancel = null
        rebuildPageMessagesOnReload = false
        webView = null
        loadState = IosWebViewLoadState()
        mutableSnapshot.value = snapshot.copy(
            canGoBack = false,
            canGoForward = false,
            isLoading = false,
            hasVisibleContent = false,
        )
        invalidateJavascriptCallbacks()
    }

    /**
     * 内容值变化时才提交原生加载；[onLoadRequested] 用于让协调器在导航前启动性能追踪。
     */
    internal fun loadIfChanged(
        target: WKWebView,
        content: WebViewContent,
        cachePolicy: WebViewCachePolicy,
        onLoadRequested: () -> Unit,
    ) {
        if (!isAttached(target) || !loadState.begin(content, cachePolicy)) return
        onLoadRequested()
        loadContent(target, content, cachePolicy)
    }

    private fun loadContent(
        target: WKWebView,
        content: WebViewContent,
        cachePolicy: WebViewCachePolicy,
    ) {
        mutableSnapshot.value = snapshot.copy(
            isLoading = true,
            progress = 0,
            hasVisibleContent = false,
            error = null,
        )
        if (content.isBlankWebViewContent()) {
            loadFailed(
                target,
                WebViewLoadError(
                    kind = WebViewErrorKind.EMPTY_CONTENT,
                    url = (content as? WebViewContent.Url)?.url,
                ),
            )
            return
        }
        when (content) {
            is WebViewContent.Url -> loadUrl(target, content, cachePolicy)
            is WebViewContent.Html -> loadHtml(target, content)
        }
    }

    internal fun pageStarted(target: WKWebView) {
        if (!isAttached(target)) return
        invalidateJavascriptCallbacks()
        if (!isAttached(target)) return
        mutableSnapshot.value = snapshot.copy(
            currentUrl = target.URL?.absoluteString,
            progress = 0,
            isLoading = true,
            error = null,
        )
    }

    internal fun pageFinished(target: WKWebView) {
        if (!isAttached(target)) return
        loadState.markCommitted()
        mutableSnapshot.value = snapshot.copy(
            title = target.title,
            currentUrl = target.URL?.absoluteString,
            progress = 100,
            isLoading = false,
            hasVisibleContent = true,
            canGoBack = target.canGoBack,
            canGoForward = target.canGoForward,
            error = null,
        )
    }

    internal fun pageCommitted(target: WKWebView): Boolean {
        if (!isAttached(target)) return false
        mutableSnapshot.value = snapshot.copy(hasVisibleContent = true)
        return true
    }

    /** didCommit 早于可见内容；此后重试不能重放声明式入口，避免覆盖站内导航。 */
    internal fun navigationCommitted(target: WKWebView) {
        if (isAttached(target)) loadState.markCommitted()
    }

    internal fun progressChanged(target: WKWebView, value: Int) {
        if (!isAttached(target)) return
        val progress = value.coerceIn(0, 100)
        // didFinish 后取消的采样协程仍可能带着旧值恢复；完成态只能由下一次 pageStarted 重新打开。
        if (!shouldAcceptIosProgress(snapshot, progress)) return
        mutableSnapshot.value = snapshot.copy(
            progress = progress,
            // WebKit 在导航刚提交和完成回调前可能短暂返回 loading=false，不能据此提前冻结进度。
            isLoading = progress < 100,
        )
    }

    internal fun navigationCancelled(target: WKWebView) {
        if (!isAttached(target)) return
        mutableSnapshot.value = snapshot.copy(isLoading = false)
    }

    internal fun loadFailed(target: WKWebView, error: WebViewLoadError) {
        if (!isAttached(target)) return
        mutableSnapshot.value = snapshot.copy(progress = 0, isLoading = false, error = error)
    }

    /**
     * Objective-C 回调可能为同一个原生 WKWebView 生成不同 Kotlin 包装引用，因此不能使用 `===`。
     * NSObject 的相等语义仍按原生对象身份判断，同时可以拒绝上一实例的迟到回调。
     */
    internal fun isAttached(target: WKWebView): Boolean = webView == target

    internal fun invalidateJavascriptCallbacks() {
        callbackGeneration++
        val pending = pendingFullscreenReplies.toList()
        pendingFullscreenReplies.clear()
        pending.forEach { it() }
    }

    private fun updateNavigation(target: WKWebView) {
        mutableSnapshot.value = snapshot.copy(
            currentUrl = target.URL?.absoluteString,
            canGoBack = target.canGoBack,
            canGoForward = target.canGoForward,
        )
    }
}

/** 每次新声明都有独立首航与恢复预算；同一声明成功提交后由 WebKit 刷新当前文档，保留站内导航/POST。 */
internal class IosWebViewLoadState {
    var content: WebViewContent? = null
        private set
    var cachePolicy: WebViewCachePolicy = WebViewCachePolicy.DEFAULT
        private set
    var hasCommittedPage: Boolean = false
        private set
    private var initialNetworkRetryUsed = false

    fun begin(content: WebViewContent, cachePolicy: WebViewCachePolicy): Boolean {
        if (this.content == content) return false
        this.content = content
        this.cachePolicy = cachePolicy
        hasCommittedPage = false
        initialNetworkRetryUsed = false
        return true
    }

    fun markCommitted() {
        hasCommittedPage = true
    }

    fun takeInitialNetworkRetry(snapshot: WebViewSnapshot): Boolean {
        val error = snapshot.error ?: return false
        val initialUrl = (content as? WebViewContent.Url)?.url ?: return false
        val url = NSURL.URLWithString(initialUrl) ?: return false
        val scheme = url.scheme?.lowercase()
        if (hasCommittedPage || initialNetworkRetryUsed || snapshot.isLoading ||
            (scheme != "http" && scheme != "https") || url.host.isNullOrBlank() ||
            !error.isMainFrame || error.kind != WebViewErrorKind.NETWORK ||
            error.errorCode !in INITIAL_CONNECTION_FAILURE_CODES
        ) return false
        initialNetworkRetryUsed = true
        return true
    }
}

// NSURLErrorDomain 由平台失败回调分类保证；取消、TLS、ATS 和任意页面错误不能触发自动重放。
private val INITIAL_CONNECTION_FAILURE_CODES = setOf(-1009, -1005, -1001, -1004)

/** 页面完成后拒绝同一导航的迟到进度；新导航会先把快照重置为 loading。 */
internal fun shouldAcceptIosProgress(snapshot: WebViewSnapshot, progress: Int): Boolean =
    snapshot.isLoading || snapshot.progress < 100 || progress >= 100

/** 非空 URL 仍可能无法被 NSURL 解析，此时按内容错误返回，不向 WebKit 提交请求。 */
private fun AppWebViewState.loadUrl(
    target: WKWebView,
    content: WebViewContent.Url,
    cachePolicy: WebViewCachePolicy,
) {
    val url = NSURL.URLWithString(content.url)
    if (url == null) {
        loadFailed(target, WebViewLoadError(WebViewErrorKind.EMPTY_CONTENT, url = content.url))
        return
    }
    val request = NSMutableURLRequest(
        url,
        cachePolicy.toIosCachePolicy(),
        DEFAULT_REQUEST_TIMEOUT_SECONDS,
    )
    content.additionalHeaders.forEach { (name, value) ->
        request.setValue(value, forHTTPHeaderField = name)
    }
    target.loadRequest(request)
}

/** HTML 保持调用方声明的 MIME 与编码；UTF-8 使用 NSData 避免 WebKit 误判旧页面 charset。 */
private fun loadHtml(target: WKWebView, content: WebViewContent.Html) {
    val baseUrl = content.baseUrl?.let(NSURL::URLWithString) ?: ABOUT_BLANK_URL
    if (content.encoding.isUtf8Encoding()) {
        target.loadData(
            data = content.html.toUtf8Data(),
            MIMEType = content.mimeType,
            characterEncodingName = UTF8_ENCODING,
            baseURL = baseUrl,
        )
    } else {
        // 当前业务只生成 UTF-8；未知编码继续交给 WebKit，避免把 UTF-8 字节误标为其他编码。
        target.loadHTMLString(content.html, baseUrl)
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun String.toUtf8Data(): NSData {
    val bytes = encodeToByteArray()
    return bytes.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
    }
}

private fun String.isUtf8Encoding(): Boolean =
    replace("_", "").replace("-", "").equals("utf8", ignoreCase = true)

private fun WebViewCachePolicy.toIosCachePolicy(): ULong = when (this) {
    WebViewCachePolicy.DEFAULT -> NSURLRequestUseProtocolCachePolicy
    WebViewCachePolicy.NO_CACHE -> NSURLRequestReloadIgnoringLocalCacheData
    WebViewCachePolicy.CACHE_ELSE_NETWORK -> NSURLRequestReturnCacheDataElseLoad
    WebViewCachePolicy.CACHE_ONLY -> NSURLRequestReturnCacheDataDontLoad
}

private const val DEFAULT_REQUEST_TIMEOUT_SECONDS = 60.0
private const val UTF8_ENCODING = "UTF-8"
private val ABOUT_BLANK_URL = checkNotNull(NSURL.URLWithString("about:blank"))
