package io.github.gycrosskit.webview.consumer

import io.github.gycrosskit.composewebview.AppWebViewState
import io.github.gycrosskit.composewebview.WebViewEvent

fun replyPageMessage(state: AppWebViewState, message: WebViewEvent.PageMessage): Boolean =
    state.replyPageMessage(message.replyId, message.data)
