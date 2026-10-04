package io.github.gycrosskit.webview.consumer

import android.content.Context
import io.github.gycrosskit.composewebview.AndroidWebViewDataCleaner

suspend fun consumeWebData(context: Context) {
    val cleaner = AndroidWebViewDataCleaner(context)
    cleaner.clearResourceCache()
    cleaner.clearWebsiteData()
}
