package io.github.gycrosskit.webview.consumer

import androidx.compose.runtime.Composable
import io.github.gycrosskit.composewebview.AppWebView
import io.github.gycrosskit.composewebview.AppWebViewState
import io.github.gycrosskit.composewebview.WebViewSnapshot

@Composable
fun ConsumeCmp() { AppWebView(request = consumerRequest()) }
fun consumeOriginalState(state: AppWebViewState): WebViewSnapshot = state.snapshot
