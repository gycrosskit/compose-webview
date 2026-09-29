package io.github.gycrosskit.composewebview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * 网页事件与导航决策回调；平台实现必须始终调用重组后的最新实例。
 *
 * @property onNavigationRequest 主框架发起导航时同步返回处理结果；回调内不应执行阻塞操作。
 * @property onEvent 接收加载、Bridge、全屏等平台中立事件；事件可能来自原生异步回调。
 */
class WebViewCallbacks(
    val onNavigationRequest: (WebViewNavigationRequest) -> WebViewNavigationDecision = {
        WebViewNavigationDecision.ALLOW
    },
    val onEvent: (WebViewEvent) -> Unit = {},
) {
    companion object {
        /** 无业务回调时复用单例，避免 Composable 默认参数在每次重组创建新对象。 */
        val Default = WebViewCallbacks()
    }
}

/**
 * commonMain 不公开或持有原生网页 View 的状态与命令入口。
 *
 * 状态只应由 [rememberAppWebViewState] 在当前组合位置持有，不能放入 ViewModel 或跨页面复用。
 */
@Stable
expect class AppWebViewState internal constructor() {
    /** 当前主文档的只读状态快照，可直接驱动加载、错误和返回 UI。 */
    val snapshot: WebViewSnapshot

    /** 重新加载当前声明式内容；空内容仍返回 [WebViewErrorKind.EMPTY_CONTENT]。 */
    fun reload()

    /** 优先消费网页内部返回。@return true 表示已处理，false 时调用方可退出当前页面。 */
    fun goBack(): Boolean

    /** 停止当前主文档加载；未绑定原生实例时安全忽略。 */
    fun stopLoading()

    /**
     * 在当前页面执行脚本；未绑定实例时不执行且不触发 [callback]。
     * 调用方只能向可信页面注入固定或经过严格转义的脚本，不能直接拼接外部输入。
     */
    fun evaluateJavascript(script: String, callback: ((String?) -> Unit)? = null)
}

/** 创建并记住仅属于当前组合位置的网页状态。 */
@Composable
fun rememberAppWebViewState(): AppWebViewState = remember { AppWebViewState() }

/** `true` 表示平台网页实例已自行绘制加载进度，shared 不得再叠加 Compose 进度条。 */
expect val platformWebViewRendersLoadingProgress: Boolean

/** 不暴露平台 Client、原生 View 或释放回调的 CMP WebView 入口。 */
@Composable
fun AppWebView(
    request: WebViewRequest,
    visible: Boolean = true,
    modifier: Modifier = Modifier,
    state: AppWebViewState = rememberAppWebViewState(),
    callbacks: WebViewCallbacks = WebViewCallbacks.Default,
    pageEnteredAtMillis: Long? = null,
) {
    val resolvedPageEnteredAtMillis = remember(pageEnteredAtMillis) {
        pageEnteredAtMillis ?: currentWebViewPerformanceTimeMillis()
    }
    PlatformAppWebView(request, modifier, state, callbacks, resolvedPageEnteredAtMillis, visible)
}

/** 供页面在开始前置接口请求时记录与 WebView 性能轨迹相同的单调时钟起点。 */
fun currentWebViewPerformanceTimeMillis(): Long = webViewMonotonicNowMillis()

@Composable
internal expect fun PlatformAppWebView(
    request: WebViewRequest,
    modifier: Modifier,
    state: AppWebViewState,
    callbacks: WebViewCallbacks,
    pageEnteredAtMillis: Long,
    visible: Boolean,
)
