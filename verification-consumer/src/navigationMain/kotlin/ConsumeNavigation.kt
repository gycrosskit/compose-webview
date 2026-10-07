package io.github.gycrosskit.webview.consumer
import io.github.gycrosskit.composewebview.AppWebViewState
fun consumeNavigation(state: AppWebViewState, result: (Boolean) -> Unit): Boolean {
    state.goBack(result)
    state.exitFullscreen(result)
    return state.snapshot.canGoForward && state.goForward()
}
