package io.github.gycrosskit.composewebview

import android.Manifest
import android.webkit.PermissionRequest
import android.webkit.WebView
import androidx.activity.ComponentActivity

/** 管理 WebView 音视频 PermissionRequest，确保配置变更和销毁后不会误授权。 */
internal class AndroidWebMediaPermissionController(
    private val activity: ComponentActivity,
    private val request: () -> WebViewRequest,
    private val isAttached: (WebView) -> Boolean,
    private val permissions: AndroidWebPermissionController,
) {
    private var pendingRequest: PermissionRequest? = null
    private var pendingOwner: WebView? = null
    private var destroyed = false

    fun request(owner: WebView, requestValue: PermissionRequest?) {
        val platformRequest = requestValue ?: return
        if (destroyed || !isAttached(owner)) {
            platformRequest.deny()
            return
        }
        cancel()
        if (!allowed(owner, platformRequest)) {
            platformRequest.deny()
            return
        }
        val requiredPermissions = webViewMediaPermissions(platformRequest.resources)
        if (requiredPermissions == null) {
            platformRequest.deny()
            return
        }
        val resources = platformRequest.resources
        val missing = requiredPermissions.filterNot(permissions::isGranted)
        if (missing.isEmpty()) {
            platformRequest.grant(resources)
            return
        }
        pendingRequest = platformRequest
        pendingOwner = owner
        permissions.request(
            key = platformRequest,
            permissions = missing,
            purpose = WebPermissionPurpose.MEDIA_CAPTURE,
            stillAllowed = { pendingRequest === platformRequest && allowed(owner, platformRequest) },
            onResult = permissionResult@ { granted ->
                activity.runOnUiThread {
                    if (pendingRequest !== platformRequest) return@runOnUiThread
                    pendingRequest = null
                    pendingOwner = null
                    if (granted && allowed(owner, platformRequest)) platformRequest.grant(resources)
                    else platformRequest.deny()
                }
            },
        )
    }

    /** Chromium 撤销的是具体请求，旧实例的迟到取消不能影响新实例。 */
    fun cancel(requestValue: PermissionRequest?) {
        if (requestValue != null && pendingRequest === requestValue) cancel(deny = false)
    }

    fun release(owner: WebView) {
        if (pendingOwner === owner) cancel()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        cancel()
    }

    private fun cancel(deny: Boolean = true) {
        val old = pendingRequest
        pendingRequest = null
        pendingOwner = null
        if (old != null) {
            permissions.cancel(old)
            if (deny) activity.runOnUiThread(old::deny)
        }
    }

    private fun allowed(owner: WebView, platformRequest: PermissionRequest): Boolean =
        !destroyed && isAttached(owner) && request().security.run {
            mediaCaptureEnabled && trustedOrigins.isTrusted(platformRequest.origin.toString())
        }
}

internal fun webViewMediaPermissions(resources: Array<String>): List<String>? {
    if (resources.isEmpty()) return null
    return resources.map { resource ->
        when (resource) {
            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
            else -> return null
        }
    }.distinct()
}
