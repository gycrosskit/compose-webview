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
) = AppWebView(
    request, visible, modifier, onEvent, onController,
    pageOwnerActive = null,
)

/**
 * pageOwnerActive=null 不下发归属属性，首次保留原生旧行为；启用显式归属后必须用 false 撤销，null 不重置已下发值。
 * 显式 true 可在系统选择器短暂隐藏时保留已有选择请求。
 * pictureInPicturePageActive 仅在当前视频页仍拥有播放会话时为 true，不能用进程前后台状态代替归属。
 * onViewUpdate 在初始化及每次更新时先配置宿主原生扩展属性；事件、归属、可见性与 request 由本入口随后统一下发。
 * 保留旧五参数重载，使已有位置参数和尾随 onController lambda 的含义不变。
 */
@Composable
fun AppWebView(
    request: WebViewRequest,
    visible: Boolean = true,
    modifier: Modifier = Modifier,
    onEvent: (WebViewEvent) -> Unit = {},
    onController: (GYWebView?) -> Unit = {},
    pageOwnerActive: Boolean? = null,
    pictureInPicturePageActive: Boolean = false,
    onViewUpdate: (GYWebView) -> Unit = {},
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
            view.getViewAttr().pageOwnerActive(false)
            view.getViewAttr().pictureInPicturePageActive(false)
            view.getViewAttr().visible(false)
            view.stopLoading()
        }
    }
    MakeKuiklyComposeNode(
        factory = { view },
        modifier = modifier,
        viewInit = {
            onViewUpdate(this)
            getViewEvent().onEvent { if (acceptingEvents[0]) currentEvent(it) }
            pageOwnerActive?.let { getViewAttr().pageOwnerActive(it) }
            getViewAttr().pictureInPicturePageActive(pictureInPicturePageActive)
            getViewAttr().visible(visible)
            getViewAttr().request(request)
            submitted[0] = request
        },
        viewUpdate = { node ->
            onViewUpdate(node)
            pageOwnerActive?.let { node.getViewAttr().pageOwnerActive(it) }
            node.getViewAttr().pictureInPicturePageActive(pictureInPicturePageActive)
            node.getViewAttr().visible(visible)
            if (submitted[0] != request) {
                node.getViewAttr().request(request)
                submitted[0] = request
            }
        },
    )
}
