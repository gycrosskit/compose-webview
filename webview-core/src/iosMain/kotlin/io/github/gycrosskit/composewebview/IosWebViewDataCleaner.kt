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
/** 切至 Main 并清理默认共享数据存储的磁盘/内存资源缓存；保留 Cookie 与网站登录数据。 */
    suspend fun clearResourceCache() = removeData(
        setOf(WKWebsiteDataTypeDiskCache, WKWebsiteDataTypeMemoryCache),
    )

/** 切至 Main 并清理默认共享数据存储全部网站数据，包含 Cookie；取消等待不能撤销已发起的系统删除。 */
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
