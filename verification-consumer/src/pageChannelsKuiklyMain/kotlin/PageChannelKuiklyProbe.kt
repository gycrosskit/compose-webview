package io.github.gycrosskit.webview.consumer

import io.github.gycrosskit.composewebview.WebViewEvent
import io.github.gycrosskit.composewebview.kuikly.GYWebView

fun replyPageMessage(view: GYWebView, message: WebViewEvent.PageMessage, reply: (Boolean) -> Unit) =
    view.replyPageMessage(message.replyId, message.data, reply)
