package io.github.gycrosskit.webview.consumer

import io.github.gycrosskit.composewebview.WebViewCapability
import io.github.gycrosskit.composewebview.WebViewEvent
import io.github.gycrosskit.composewebview.WebViewNavigationPolicy
import io.github.gycrosskit.composewebview.WebViewContent
import io.github.gycrosskit.composewebview.WebViewRequest
import io.github.gycrosskit.composewebview.WebViewPermissionPresentation
import io.github.gycrosskit.composewebview.WebViewWire

fun consumerRequest() = WebViewRequest(
    WebViewContent.Url("https://example.com/"),
    navigationPolicy = WebViewNavigationPolicy(allowedOrigins = setOf("https://example.com")),
    permissionPresentation = WebViewPermissionPresentation.HOST,
)
fun captureUnsupported() = WebViewEvent.CapabilityUnsupported(WebViewCapability.FILE_CAPTURE)
fun consumerWire() = WebViewWire.encodeRequest(consumerRequest())
