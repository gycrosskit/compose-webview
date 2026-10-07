package io.github.gycrosskit.webview.consumer

import io.github.gycrosskit.composewebview.WebViewContent
import io.github.gycrosskit.composewebview.WebViewEvent
import io.github.gycrosskit.composewebview.WebViewRequest

fun pageChannelRequest() = WebViewRequest(
    WebViewContent.Url("https://example.test/start"),
    pageMessageChannels = setOf("Verification"),
)

fun pageMessage(channel: String, data: String, replyId: String) =
    WebViewEvent.PageMessage(channel, data, replyId)
