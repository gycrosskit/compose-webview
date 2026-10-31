package io.github.gycrosskit.webview.consumer

import com.tencent.kuikly.core.base.ViewContainer
import io.github.gycrosskit.composewebview.WebViewEvent
import io.github.gycrosskit.composewebview.kuikly.GYWebView

fun ViewContainer<*, *>.consumeKuikly() {
    GYWebView {
        val webView = this
        attr {
            pageOwnerActive(true)
            pictureInPicturePageActive(true)
            request(consumerRequest())
            size(320f, 480f)
        }
        event {
            onEvent { event ->
                // 消费探针不展示业务 UI，明确取消本次说明以结束原生等待。
                when (event) {
                    is WebViewEvent.PermissionExplanationRequested -> webView.completePermissionExplanation(event.requestId, false)
                    is WebViewEvent.PermissionExplanationCancelled -> Unit
                    else -> Unit
                }
            }
        }
    }
}
