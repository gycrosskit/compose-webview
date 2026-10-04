package io.github.gycrosskit.webview.consumer

import io.github.gycrosskit.composewebview.IosWebViewDataCleaner

suspend fun consumeWebData() {
    val cleaner = IosWebViewDataCleaner()
    cleaner.clearResourceCache()
    cleaner.clearWebsiteData()
}
