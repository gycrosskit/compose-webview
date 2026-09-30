package io.github.gycrosskit.webview.consumer

import com.tencent.kuikly.core.base.ViewContainer
import io.github.gycrosskit.composewebview.WebViewCapability
import io.github.gycrosskit.composewebview.WebViewEvent
import io.github.gycrosskit.composewebview.WebViewNavigationPolicy
import io.github.gycrosskit.composewebview.WebViewContent
import io.github.gycrosskit.composewebview.WebViewRequest
import io.github.gycrosskit.composewebview.WebViewWire
import io.github.gycrosskit.composewebview.kuikly.GYWebView

fun consumerRequest() = WebViewRequest(
    WebViewContent.Url("https://example.com/"),
    navigationPolicy = WebViewNavigationPolicy(allowedOrigins = setOf("https://example.com")),
)
fun captureUnsupported() = WebViewEvent.CapabilityUnsupported(WebViewCapability.FILE_CAPTURE)
fun consumerWire() = WebViewWire.encodeRequest(consumerRequest())

fun ViewContainer<*, *>.consumeKuikly() {
    GYWebView {
        attr { request(consumerRequest()); size(320f, 480f) }
        event { onEvent { } }
    }
}
