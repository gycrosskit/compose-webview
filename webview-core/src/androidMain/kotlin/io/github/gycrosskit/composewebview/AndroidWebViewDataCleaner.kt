package io.github.gycrosskit.composewebview

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** 只清理 WebView 数据；App 账号、图片缓存与清理时机由宿主控制。 */
class AndroidWebViewDataCleaner(context: Context) {
    private val appContext = context.applicationContext

    /** 普通清缓存保留 Cookie、LocalStorage 和 IndexedDB。 */
    suspend fun clearResourceCache() = withContext(Dispatchers.Main.immediate) {
        val view = WebView(appContext)
        try { view.clearCache(true) } finally { view.destroy() }
    }

    /** 切换网页账号/环境时使用；等待 Cookie 删除完成并落盘。 */
    suspend fun clearWebsiteData() = withContext(Dispatchers.Main.immediate) {
        clearResourceCache()
        WebStorage.getInstance().deleteAllData()
        suspendCancellableCoroutine { continuation ->
            val cookies = CookieManager.getInstance()
            cookies.removeAllCookies {
                cookies.flush()
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }
}
