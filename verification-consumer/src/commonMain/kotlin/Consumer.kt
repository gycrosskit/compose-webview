package io.github.gycrosskit.webview.consumer

import io.github.gycrosskit.composewebview.WebViewCapability
import io.github.gycrosskit.composewebview.WebViewEvent
import io.github.gycrosskit.composewebview.WebViewNavigationPolicy
import io.github.gycrosskit.composewebview.WebViewContent
import io.github.gycrosskit.composewebview.WebViewRequest
import io.github.gycrosskit.composewebview.WebViewWire

fun consumerRequest() = WebViewRequest(
    WebViewContent.Url("https://example.com/"),
    navigationPolicy = WebViewNavigationPolicy(allowedOrigins = setOf("https://example.com")),
)
fun captureUnsupported() = WebViewEvent.CapabilityUnsupported(WebViewCapability.FILE_CAPTURE)
fun consumerWire() = WebViewWire.encodeRequest(consumerRequest())
