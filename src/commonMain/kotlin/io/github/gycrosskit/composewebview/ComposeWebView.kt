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
 * 所有命令在所属平台 UI 线程调用；实例释放后迟到的脚本结果不再交付。
 */
@Stable
expect class AppWebViewState internal constructor() {
    /** 当前主文档的只读状态快照，可直接驱动加载、错误和返回 UI。 */
    val snapshot: WebViewSnapshot

    /** 刷新当前页面；尚未提交的新声明会重放其内容与请求头，空内容报告 [WebViewErrorKind.EMPTY_CONTENT]。 */
    fun reload()

    /** 优先退出全屏，再返回网页历史；iOS 全屏时 true 表示异步请求已受理，最终结果用回调重载。 */
    fun goBack(): Boolean

    /** 优先退出全屏，再返回历史；回调至多一次交付最终消费结果；文档更换或释放取消时 false，取消不回退新文档历史。 */
    fun goBack(callback: (Boolean) -> Unit)

    /** 前进网页历史；未绑定实例或没有历史时返回 false。 */
    fun goForward(): Boolean

    /** 退出当前全屏，回调至多一次交付原生最终结果；未全屏或文档/owner结束取消时 false，不交付迟到成功。 */
    fun exitFullscreen(callback: (Boolean) -> Unit = {})

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

/**
 * CMP 网页入口，原生实例随组合位置释放；相等内容不会因重组重复加载。
 * @param request 内容、设置与能力门禁；安全配置变化可能重建原生实例。
 * @param visible 是否显示，默认 true；权限与异步操作的撤销由平台生命周期门禁执行。
 * @param modifier 布局与外观约束。
 * @param state 仅属于当前组合位置的状态，默认由 remember 创建。
 * @param callbacks 平台事件与同步导航回调，默认允许基础导航且忽略事件。
 * @param pageEnteredAtMillis [currentWebViewPerformanceTimeMillis] 同源的毫秒值；null 使用首次组合时刻。
 */
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
