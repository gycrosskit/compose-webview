package io.github.gycrosskit.composewebview

import android.webkit.WebView
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * WebView 的可观察 UI 状态和命令入口。
 *
 * 状态应通过 [rememberAppWebViewState] 在页面组合期间持有。内部绑定的 Android WebView 生命周期
 * 由 [AppWebView] 管理，因此本对象不能放入 ViewModel、持久化状态或跨页面复用。
 */
@Stable
actual class AppWebViewState actual internal constructor() {
    private var mutableSnapshot by mutableStateOf(WebViewSnapshot())

    actual val snapshot: WebViewSnapshot
        get() = mutableSnapshot

    /** 渲染进程异常后递增，作为 Compose key 触发 AndroidView 实例重建。 */
    internal var instanceKey by mutableIntStateOf(0)
        private set
    /** 最近一次由声明式入口请求加载的内容，用于拦截重组产生的重复加载。 */
    internal var loadedContent: WebViewContent? = null
    /** 实例创建时的系统 User-Agent，配置重组始终从该基线重新计算。 */
    internal var defaultUserAgent: String? = null
    /** 最近一次真正写入原生 WebSettings/显示属性的值，避免普通重组重复调用平台 Setter。 */
    internal var appliedConfig: AppliedAppWebViewConfig? = null
    /** 只持有当前组合位置创建的实例；[detach] 必须在 destroy 前清空。 */
    private var webView: WebView? = null
    /** 通用平台层可优先消费返回（例如退出 H5 CustomView 全屏），不向 shared 暴露 Android 控制器。 */
    private var backInterceptor: (() -> Boolean)? = null
    /** 标记因渲染进程退出而失效的实例，释放时据此避开不安全的平台调用。 */
    private var renderProcessGoneWebView: WebView? = null

    /**
     * 重新加载当前内容。渲染进程崩溃时会重建 WebView，其余情况调用实例的 `reload()`。
     * 空 URL 或空 HTML 会继续保留为可展示的内容错误，不发起无效加载。
     */
    actual fun reload() {
        if (loadedContent?.isBlankWebViewContent() == true) {
            onLoadFailed(WebViewErrorHelper.fromEmptyContent())
            return
        }
        if (snapshot.error?.kind == WebViewErrorKind.RENDER_PROCESS) {
            mutableSnapshot = snapshot.copy(error = null, hasVisibleContent = false)
            instanceKey++
        } else {
            mutableSnapshot = snapshot.copy(error = null, hasVisibleContent = false)
            webView?.reload()
        }
    }

    /** @return true 表示已在网页历史中返回，false 时应由页面退出。 */
    actual fun goBack(): Boolean {
        if (backInterceptor?.invoke() == true) return true
        val target = webView ?: return false
        if (!target.canGoBack()) return false
        target.goBack()
        updateNavigation(target)
        return true
    }

    /** 停止当前加载并立即将 [isLoading] 置为 `false`。未绑定实例时安全忽略。 */
    actual fun stopLoading() {
        webView?.stopLoading()
        mutableSnapshot = snapshot.copy(isLoading = false)
    }

    /**
     * 在当前页面执行脚本。未绑定实例时不会执行，也不会触发 [callback]。
     * 调用方只能向可信页面注入固定或经过严格转义的脚本，不能直接拼接外部输入。
     */
    actual fun evaluateJavascript(script: String, callback: ((String?) -> Unit)?) {
        val target = webView ?: return
        if (!target.isActiveAppWebView()) return
        target.evaluateJavascript(script) { result ->
            if (webView === target && target.isActiveAppWebView()) callback?.invoke(result)
        }
    }

    /** 绑定新建实例并重置仅属于上一个实例的内容加载记录。 */
    internal fun attach(target: WebView) {
        webView = target
        defaultUserAgent = target.settings.userAgentString
        appliedConfig = null
        loadedContent = null
        mutableSnapshot = snapshot.copy(
            currentUrl = target.url,
            canGoBack = target.canGoBack(),
            hasVisibleContent = false,
        )
    }

    /**
     * 记录本实例即将加载的声明式内容。
     *
     * Compose 重组会多次执行 AndroidView.update；只有内容值真正变化时才返回 `true`，从而避免相同 URL
     * 或 HTML 被重复加载。WebView 内部自行发生的页面导航不会修改该值，因此普通重组也不会把页面强制拉回
     * 初始地址。
     */
    internal fun markContentForLoad(content: WebViewContent): Boolean {
        if (loadedContent == content) return false
        loadedContent = content
        mutableSnapshot = snapshot.copy(hasVisibleContent = false)
        return true
    }

    /**
     * 只解绑仍由 State 持有的目标，防止旧实例迟到释放时误清理已经重建的新实例。
     */
    internal fun detach(target: WebView) {
        if (renderProcessGoneWebView === target) renderProcessGoneWebView = null
        if (webView !== target) return
        webView = null
        defaultUserAgent = null
        appliedConfig = null
        loadedContent = null
        mutableSnapshot = snapshot.copy(canGoBack = false, hasVisibleContent = false)
    }

    /** 新主框架导航开始时清空上一页标题和错误，但保留声明式内容身份。 */
    internal fun onLoadStarted(url: String?) {
        if (renderProcessGoneWebView === webView) renderProcessGoneWebView = null
        mutableSnapshot = snapshot.copy(
            title = null,
            currentUrl = url,
            progress = 0,
            isLoading = true,
            error = null,
        )
    }

    /** 主框架成功完成后同步进度和历史返回能力。 */
    internal fun onLoadFinished(url: String?, canGoBack: Boolean) {
        mutableSnapshot = snapshot.copy(
            currentUrl = url,
            progress = 100,
            isLoading = false,
            hasVisibleContent = true,
            canGoBack = canGoBack,
            error = null,
        )
    }

    /** 记录内核首次提交可见主文档，网页展示不等待该信号。 */
    internal fun onPageCommitVisible() {
        mutableSnapshot = snapshot.copy(hasVisibleContent = true)
    }

    /** 记录整页失败；渲染进程错误会要求下一次 reload 重建 AndroidView。 */
    internal fun onLoadFailed(loadError: WebViewLoadError) {
        if (loadError.kind == WebViewErrorKind.RENDER_PROCESS) renderProcessGoneWebView = webView
        mutableSnapshot = snapshot.copy(progress = 0, isLoading = false, error = loadError)
    }

    /** ChromeClient 进度只能影响加载指示，不负责判定页面最终成功。 */
    internal fun onProgressChanged(value: Int) {
        // 只有 onLoadStarted 能打开新一轮加载，迟到进度不能复活完成、失败或取消状态。
        if (!snapshot.isLoading) return
        mutableSnapshot = snapshot.copy(progress = value.coerceIn(0, 100))
    }

    /** 原生层只交付标题，共享展示边界统一执行跨平台过滤。 */
    internal fun onReceivedTitle(value: String?, url: String?) {
        mutableSnapshot = snapshot.copy(title = value)
    }

    /** 从当前实例同步 WebView 内部历史状态。 */
    internal fun updateNavigation(target: WebView) {
        mutableSnapshot = snapshot.copy(
            currentUrl = target.url,
            canGoBack = target.canGoBack(),
        )
    }

    /** 旧实例的异步回调只能更新创建它时仍绑定的状态。 */
    internal fun isAttached(target: WebView): Boolean = webView === target && target.isActiveAppWebView()

    internal fun installBackInterceptor(interceptor: (() -> Boolean)?) {
        backInterceptor = interceptor
    }

    /** 释放端据此避开已经失效的 Chromium 进程调用。 */
    internal fun isRenderProcessGone(target: WebView): Boolean =
        renderProcessGoneWebView === target

    /** 跟随宿主 Lifecycle 恢复当前实例的脚本与媒体处理。 */
    internal fun resumeWebView() {
        webView?.onResume()
    }

    /** 跟随宿主 Lifecycle 暂停当前实例，不使用会影响全进程的 pauseTimers。 */
    internal fun pauseWebView() {
        webView?.onPause()
    }
}
