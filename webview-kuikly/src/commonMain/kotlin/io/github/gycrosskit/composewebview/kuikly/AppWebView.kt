package io.github.gycrosskit.composewebview.kuikly

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.tencent.kuikly.compose.extension.MakeKuiklyComposeNode
import com.tencent.kuikly.compose.ui.Modifier
import io.github.gycrosskit.composewebview.WebViewEvent
import io.github.gycrosskit.composewebview.WebViewRequest

/**
 * KuiklyCompose 网页入口，复用三端 GYWebView；宿主仍需注册同名原生 View 并提供尺寸。
 * request 相等时不重发；visible 变化只更新可见性，导航策略变化仍由原生保留 DOM。
 * 同步导航门禁使用 request.navigationPolicy；onEvent 是观察回调，不能撤销已经发生的导航。
 * onController 提供当前节点的命令入口，离开 Composition 回报 null，宿主不可跨页面保留它。
 */
@Composable
fun AppWebView(
    request: WebViewRequest,
    visible: Boolean = true,
    modifier: Modifier = Modifier,
    onEvent: (WebViewEvent) -> Unit = {},
    onController: (GYWebView?) -> Unit = {},
) {
    val currentEvent by rememberUpdatedState(onEvent)
    val view = remember { GYWebView() }
    val acceptingEvents = remember(view) { booleanArrayOf(true) }
    val submitted = remember(view) { arrayOf<WebViewRequest?>(null) }
    DisposableEffect(view, onController) {
        onController(view)
        onDispose { onController(null) }
    }
    DisposableEffect(view) {
        acceptingEvents[0] = true
        onDispose {
            acceptingEvents[0] = false
            // 在 Renderer 销毁原生节点前撤销权限、全屏与本次异步操作。
            view.getViewAttr().visible(false)
            view.stopLoading()
        }
    }
    MakeKuiklyComposeNode(
        factory = { view },
        modifier = modifier,
        viewInit = {
            getViewEvent().onEvent { if (acceptingEvents[0]) currentEvent(it) }
            getViewAttr().visible(visible)
            getViewAttr().request(request)
            submitted[0] = request
        },
        viewUpdate = { node ->
            node.getViewAttr().visible(visible)
            if (submitted[0] != request) {
                node.getViewAttr().request(request)
                submitted[0] = request
            }
        },
    )
}
