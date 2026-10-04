package io.github.gycrosskit.composewebview

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSDate
import platform.WebKit.WKWebsiteDataStore
import platform.WebKit.WKWebsiteDataTypeDiskCache
import platform.WebKit.WKWebsiteDataTypeMemoryCache
import kotlin.coroutines.resume

/** WebKit completion 前不交付清理完成；取消不取消系统已开始的删除。 */
@OptIn(ExperimentalForeignApi::class)
class IosWebViewDataCleaner {
    suspend fun clearResourceCache() = removeData(
        setOf(WKWebsiteDataTypeDiskCache, WKWebsiteDataTypeMemoryCache),
    )

    suspend fun clearWebsiteData() = removeData(WKWebsiteDataStore.allWebsiteDataTypes())

    private suspend fun removeData(types: Set<*>) = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            WKWebsiteDataStore.defaultDataStore().removeDataOfTypes(
                dataTypes = types,
                modifiedSince = NSDate(timeIntervalSinceReferenceDate = -978_307_200.0),
            ) {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }
}
