package io.github.gycrosskit.composewebview.kuikly

import com.tencent.kuikly.core.base.Attr
import com.tencent.kuikly.core.base.DeclarativeBaseView
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.event.Event
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import io.github.gycrosskit.composewebview.WebViewEvent
import io.github.gycrosskit.composewebview.WebViewRequest
import io.github.gycrosskit.composewebview.WebViewWire

/** 宿主需注册同名原生 View，并显式设置组件尺寸。 */
class GYWebView : DeclarativeBaseView<GYWebViewAttr, GYWebViewEvent>() {
    override fun viewName(): String = VIEW_NAME
    override fun createAttr() = GYWebViewAttr()
    override fun createEvent() = GYWebViewEvent()

    /** 原生 View 加载后刷新当前页面；首次加载失败由平台重放声明式输入。 */
    fun reload() = command("reload")
    /** 原生 View 加载后停止加载，并由平台撤销本次异步能力请求。 */
    fun stopLoading() = command("stopLoading")
    /** 优先退出全屏，再消费网页返回；回调 true 表示原生已消费，默认忽略结果。 */
    fun goBack(callback: (Boolean) -> Unit = {}) = command("goBack") { callback(it as? Boolean ?: false) }
    /** 请求前进，回调 true 表示存在并消费历史项，默认忽略结果。 */
    fun goForward(callback: (Boolean) -> Unit = {}) = command("goForward") { callback(it as? Boolean ?: false) }
    /** 退出原生全屏，回调 true 表示已消费；默认忽略结果。 */
    fun exitFullscreen(callback: (Boolean) -> Unit = {}) = command("exitFullscreen") { callback(it as? Boolean ?: false) }
    /** 执行可信脚本，原生未授权/执行失败可返回 null；输入不可拼接未转义外部数据，旧页面结果可能被丢弃。 */
    fun evaluateJavascript(script: String, callback: (String?) -> Unit = {}) {
        command("evaluateJavascript", JSONObject().put("script", script).toString()) { callback(it as? String) }
    }

    /** 单次回复当前可见文档；callback 的 true 表示原生接受并提交，不能证明 H5 已处理。 */
    fun replyPageMessage(replyId: String, data: String, callback: (Boolean) -> Unit = {}) {
        command("replyPageMessage", JSONObject().put("replyId", replyId).put("data", data).toString()) {
            callback(it as? Boolean ?: false)
        }
    }

    private fun command(method: String, params: String? = null, callback: ((Any?) -> Unit)? = null) {
        performTaskWhenRenderViewDidLoad {
            renderView?.callMethod(method, params) { result -> callback?.invoke((result as? JSONObject)?.opt("result")) }
        }
    }

    /** 原生注册名称，Android/iOS/OHOS 必须保持一致。 */
    companion object { const val VIEW_NAME = "GYWebView" }
}

/** 随 Kuikly 渲染任务下发网页声明与可见性；输入变化撤销旧页面的异步操作。 */
class GYWebViewAttr : Attr() {
    /** 编码中立输入为 JSON；模型构造负责公共契约校验，平台仍可拒绝不支持的配置，安全变化可能重建实例；仅 navigationPolicy 变化即时更新门禁并保留当前 DOM。 */
    fun request(value: WebViewRequest): GYWebViewAttr {
        "request" with WebViewWire.encodeRequest(value)
        return this
    }

    /** 设置可见性，初始默认 true；隐藏由原生撤销权限与显式 JS 回执并退出全屏；当前文档初始化继续。 */
    fun visible(value: Boolean): GYWebViewAttr {
        "visible" with value
        return this
    }
}

/** 导航事件只供宿主观察；同步拦截由 request.navigationPolicy 决定。 */
class GYWebViewEvent : Event() {
    /** 注册网页事件；导航只是观察事件，同步决定由 navigationPolicy 下发，正文可能含敏感业务数据。 */
    fun onEvent(handler: (WebViewEvent) -> Unit) {
        register("onEvent") { params ->
            val event = (params as? JSONObject)?.let { WebViewWire.decodeEvent(it.toString()) }
            if (event != null) handler(event)
        }
    }
}

/** 添加网页组件并配置其属性/事件；宿主须先注册原生组件并设置尺寸。 */
fun ViewContainer<*, *>.GYWebView(init: GYWebView.() -> Unit) = addChild(GYWebView(), init)
