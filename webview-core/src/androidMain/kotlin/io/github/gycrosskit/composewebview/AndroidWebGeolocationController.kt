package io.github.gycrosskit.composewebview

import android.Manifest
import android.webkit.GeolocationPermissions
import android.webkit.WebView

/** 标准定位只按内核提供的 origin 授权，允许当前可信页面的同源 iframe。 */
internal class AndroidWebGeolocationController(
    private val request: () -> WebViewRequest,
    private val isAttached: (WebView) -> Boolean,
    private val permissions: AndroidWebPermissionController,
) {
    private class Pending(val owner: WebView, val origin: String, val callback: GeolocationPermissions.Callback)
    private var pending: Pending? = null
    private var destroyed = false

    fun request(owner: WebView, origin: String?, callback: GeolocationPermissions.Callback?) {
        if (callback == null) return
        val next = Pending(owner, origin.orEmpty(), callback)
        val old = pending
        pending = null
        if (old != null) { permissions.cancel(old); old.callback.invoke(old.origin, false, false) }
        // 取消旧请求可能同步重入；不能覆盖重入后的 owner。
        if (pending != null || !allowed(next)) { callback.invoke(next.origin, false, false); return }
        pending = next
        permissions.request(key = next, permissions = listOf(Manifest.permission.ACCESS_COARSE_LOCATION),
            purpose = WebPermissionPurpose.GEOLOCATION,
            stillAllowed = { pending === next && allowed(next) },
            onResult = { granted ->
                if (pending === next) {
                    pending = null
                    next.callback.invoke(next.origin, granted && allowed(next), false)
                }
            })
    }

    fun cancel(owner: WebView) {
        val old = pending?.takeIf { it.owner === owner } ?: return
        pending = null
        permissions.cancel(old)
        old.callback.invoke(old.origin, false, false)
    }

    fun destroy() { destroyed = true; pending?.owner?.let(::cancel) }

    private fun allowed(value: Pending): Boolean {
        val current = request()
        val source = value.origin.toHttpOrigin() ?: return false
        return !destroyed && isAttached(value.owner) && value.owner.isActiveAppWebView() &&
            current.security.geolocationEnabled && current.security.trustedOrigins.isTrusted(value.owner.url) &&
            current.security.trustedOrigins.isTrusted(value.origin) && source == value.owner.url?.toHttpOrigin()
    }
}
