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

    fun reload() = command("reload")
    fun stopLoading() = command("stopLoading")
    fun goBack(callback: (Boolean) -> Unit = {}) = command("goBack") { callback(it as? Boolean ?: false) }
    fun goForward(callback: (Boolean) -> Unit = {}) = command("goForward") { callback(it as? Boolean ?: false) }
    fun exitFullscreen(callback: (Boolean) -> Unit = {}) = command("exitFullscreen") { callback(it as? Boolean ?: false) }
    fun evaluateJavascript(script: String, callback: (String?) -> Unit = {}) {
        command("evaluateJavascript", JSONObject().put("script", script).toString()) { callback(it as? String) }
    }

    private fun command(method: String, params: String? = null, callback: ((Any?) -> Unit)? = null) {
        performTaskWhenRenderViewDidLoad {
            renderView?.callMethod(method, params) { result -> callback?.invoke((result as? JSONObject)?.opt("result")) }
        }
    }

    companion object { const val VIEW_NAME = "GYWebView" }
}

class GYWebViewAttr : Attr() {
    fun request(value: WebViewRequest): GYWebViewAttr {
        "request" with WebViewWire.encodeRequest(value)
        return this
    }

    fun visible(value: Boolean): GYWebViewAttr {
        "visible" with value
        return this
    }
}

/** 导航事件只供宿主观察；同步拦截由 request.navigationPolicy 决定。 */
class GYWebViewEvent : Event() {
    fun onEvent(handler: (WebViewEvent) -> Unit) {
        register("onEvent") { params ->
            val event = (params as? JSONObject)?.let { WebViewWire.decodeEvent(it.toString()) }
            if (event != null) handler(event)
        }
    }
}

fun ViewContainer<*, *>.GYWebView(init: GYWebView.() -> Unit) = addChild(GYWebView(), init)
