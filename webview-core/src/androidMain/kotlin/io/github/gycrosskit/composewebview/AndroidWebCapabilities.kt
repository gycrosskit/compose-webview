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
 * Android WebView 能力门面；UI 线程创建和调用，原生实例释放时撤销请求，系统权限在途的暂时隐藏保留定位回执，所属页面退出时 destroy。
 * @param activity 提供权限与 ActivityResult 生命周期的 Activity，不能传 Application。
 * @param request 获取当前最新声明式请求。
 * @param isAttached 确认原生实例仍属于可见页面；文件与媒体 UI 的边界。
 * @param isOwnerActive 确认原生实例仍属于有效页面；定位系统授权回执不依赖可见性。
 * @param onPermissionSettingsRequired 系统拒绝且不提供重试说明时交付设置提示；不得自动打开设置页。
 * @param showPermissionExplanation 替换用途说明 UI，返回撤销句柄；系统权限申请仍由组件执行。
 */
class AndroidWebCapabilities(
    activity: ComponentActivity,
    request: () -> WebViewRequest,
    isAttached: (WebView) -> Boolean,
    onPermissionSettingsRequired: (Set<WebViewPermission>) -> Unit,
    showPermissionExplanation: ((Set<WebViewPermission>, (Boolean) -> Unit) -> (() -> Unit))?,
    filePicker: AndroidWebFilePicker?,
    isOwnerActive: (WebView) -> Boolean,
) {
    constructor(
        activity: ComponentActivity,
        request: () -> WebViewRequest,
        isAttached: (WebView) -> Boolean,
        onPermissionSettingsRequired: (Set<WebViewPermission>) -> Unit,
        showPermissionExplanation: ((Set<WebViewPermission>, (Boolean) -> Unit) -> (() -> Unit))?,
        filePicker: AndroidWebFilePicker?,
    ) : this(activity, request, isAttached, onPermissionSettingsRequired, showPermissionExplanation, filePicker, isAttached)

    constructor(
        activity: ComponentActivity,
        request: () -> WebViewRequest,
        isAttached: (WebView) -> Boolean,
        onPermissionSettingsRequired: (Set<WebViewPermission>) -> Unit,
        showPermissionExplanation: ((Set<WebViewPermission>, (Boolean) -> Unit) -> (() -> Unit))?,
    ) : this(activity, request, isAttached, onPermissionSettingsRequired, showPermissionExplanation, null)

    constructor(
        activity: ComponentActivity,
        request: () -> WebViewRequest,
        isAttached: (WebView) -> Boolean,
        onPermissionSettingsRequired: (Set<WebViewPermission>) -> Unit,
    ) : this(activity, request, isAttached, onPermissionSettingsRequired, null)

    private val permissions = AndroidWebPermissionController(
        activity = activity,
        onPermissionSettingsRequired = onPermissionSettingsRequired,
        showPermissionExplanation = showPermissionExplanation,
    )
    private val fileChooser = AndroidWebFileChooserController(
        activity = activity,
        request = request,
        isAttached = isAttached,
        permissions = permissions,
        delegate = filePicker,
    )
    private val mediaCapture = AndroidWebMediaPermissionController(
        activity = activity,
        request = request,
        isAttached = isAttached,
        permissions = permissions,
    )

    private val geolocation = AndroidWebGeolocationController(request, isOwnerActive, permissions, isAttached)

    fun requestGeolocation(owner: WebView, origin: String?, callback: android.webkit.GeolocationPermissions.Callback?) =
        geolocation.request(owner, origin, callback)

    fun cancelGeolocation(owner: WebView) = geolocation.cancel(owner)

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
        val mediaRequest = mediaCapture.pendingRequest(owner)
        geolocation.cancel(owner)
        fileChooser.release(owner)
        // 文件取消回执可同步发起新媒体请求，只撤销进入 release 时的请求。
        mediaCapture.release(owner, mediaRequest)
    }

    /** 系统选择器或定位权限在途时保留回执；新 UI 仍需可见页面。 */
    fun pauseForSystemPicker(owner: WebView): Boolean {
        val mediaRequest = mediaCapture.pendingRequest(owner)
        val locationSuspended = geolocation.pause(owner)
        val suspended = fileChooser.suspendForExternal(owner)
        if (!suspended) fileChooser.pause(owner)
        mediaCapture.release(owner, mediaRequest)
        return suspended || locationSuspended
    }

    /** 暂时隐藏保留系统定位授权；已交给当前文档的文件保留至文档结束。 */
    fun pause(owner: WebView) {
        val mediaRequest = mediaCapture.pendingRequest(owner)
        geolocation.pause(owner)
        fileChooser.pause(owner)
        mediaCapture.release(owner, mediaRequest)
    }

    fun resume(owner: WebView) = fileChooser.resume(owner)

    /** 页面结束时撤销全部请求并注销 ActivityResult 入口；此后不可复用。 */
    fun destroy() {
        geolocation.destroy()
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

/** 平台权限流程只交付固定警告信息，不转发可能含页面数据的异常。 */
fun logWebWarning(message: String, error: Throwable? = null) {
    AppWebViewRuntime.log(AppWebViewLogLevel.WARNING, message)
}

/** 平台权限流程只交付固定错误信息，不转发可能含页面数据的异常。 */
fun logWebError(message: String, error: Throwable? = null) {
    AppWebViewRuntime.log(AppWebViewLogLevel.ERROR, message)
}
