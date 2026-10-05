package io.github.gycrosskit.webview.consumer

import com.tencent.kuikly.core.base.ViewContainer
import io.github.gycrosskit.composewebview.kuikly.GYWebView

fun ViewContainer<*, *>.consumeKuikly() {
    GYWebView {
        attr { request(consumerRequest()); size(320f, 480f) }
        event { onEvent { } }
    }
}
