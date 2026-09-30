package io.github.gycrosskit.composewebview

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity

/** Android WebView 能力门面，平台入口保持稳定，具体生命周期由各能力控制器负责。 */
class AndroidWebCapabilities(
    activity: ComponentActivity,
    request: () -> WebViewRequest,
    isAttached: (WebView) -> Boolean,
    onPermissionSettingsRequired: (Set<WebViewPermission>) -> Unit,
) {
    private val permissions = AndroidWebPermissionController(
        activity = activity,
        onPermissionSettingsRequired = onPermissionSettingsRequired,
    )
    private val fileChooser = AndroidWebFileChooserController(
        activity = activity,
        request = request,
        isAttached = isAttached,
        permissions = permissions,
    )
    private val mediaCapture = AndroidWebMediaPermissionController(
        activity = activity,
        request = request,
        isAttached = isAttached,
        permissions = permissions,
    )

    fun showFileChooser(
        webView: WebView?,
        callback: ValueCallback<Array<Uri>>?,
        params: WebChromeClient.FileChooserParams?,
    ): Boolean = fileChooser.show(webView, callback, params)

    fun requestMedia(owner: WebView, requestValue: PermissionRequest?) {
        mediaCapture.request(owner, requestValue)
    }

    fun cancelMedia(requestValue: PermissionRequest?) = mediaCapture.cancel(requestValue)

    fun release(owner: WebView) {
        mediaCapture.release(owner)
        fileChooser.release(owner)
    }

    fun destroy() {
        fileChooser.destroy()
        mediaCapture.destroy()
        permissions.destroy()
    }
}

tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}

fun logWebWarning(message: String, error: Throwable? = null) {
    AppWebViewRuntime.log(AppWebViewLogLevel.WARNING, message, error)
}

fun logWebError(message: String, error: Throwable? = null) {
    AppWebViewRuntime.log(AppWebViewLogLevel.ERROR, message, error)
}
