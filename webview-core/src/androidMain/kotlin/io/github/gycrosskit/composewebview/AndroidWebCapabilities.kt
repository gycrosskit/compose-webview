package io.github.gycrosskit.composewebview

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity

/**
 * Android WebView 能力门面；UI 线程创建和调用，原生实例释放/隐藏时撤销请求，所属页面退出时 destroy。
 * @param activity 提供权限与 ActivityResult 生命周期的 Activity，不能传 Application。
 * @param request 获取当前最新声明式请求。
 * @param isAttached 确認原生实例仍属于可见页面；异步结果交付前再次检查。
 * @param onPermissionSettingsRequired 系统已禁止再次询问时交付所需设置权限；不得自动打开设置页。
 */
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

    /** 处理文件选择；true 表示接管结果回调，撤销以 null 完成本次请求。 */
    fun showFileChooser(
        webView: WebView?,
        callback: ValueCallback<Array<Uri>>?,
        params: WebChromeClient.FileChooserParams?,
    ): Boolean = fileChooser.show(webView, callback, params)

    /** 按能力开关和来源申请网页音视频权限；迟到授权不能交付旧文档。 */
    fun requestMedia(owner: WebView, requestValue: PermissionRequest?) {
        mediaCapture.request(owner, requestValue)
    }

    /** 取消指定网页权限请求，null 安全忽略。 */
    fun cancelMedia(requestValue: PermissionRequest?) = mediaCapture.cancel(requestValue)

    /** 撤销指定实例的文件与媒体请求；系统选择器返回前保留关联门禁。 */
    fun release(owner: WebView) {
        mediaCapture.release(owner)
        fileChooser.release(owner)
    }

    /** 页面结束时撤销全部请求并注销 ActivityResult 入口；此后不可复用。 */
    fun destroy() {
        fileChooser.destroy()
        mediaCapture.destroy()
        permissions.destroy()
    }
}

/** 沿 ContextWrapper 查找 Activity；没有 ComponentActivity 时返回 null。 */
tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}

/** 同步交付可恢复的平台警告，异常可为空；日志可能含页面地址。 */
fun logWebWarning(message: String, error: Throwable? = null) {
    AppWebViewRuntime.log(AppWebViewLogLevel.WARNING, message, error)
}

/** 同步交付平台错误，异常可为空；日志内容不自动脱敏。 */
fun logWebError(message: String, error: Throwable? = null) {
    AppWebViewRuntime.log(AppWebViewLogLevel.ERROR, message, error)
}
