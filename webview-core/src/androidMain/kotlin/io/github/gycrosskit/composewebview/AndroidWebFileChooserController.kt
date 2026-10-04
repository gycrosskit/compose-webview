package io.github.gycrosskit.composewebview

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/** 管理文件选择、Photo Picker 与拍摄输出，权限申请统一交给权限控制器串行执行。 */
internal class AndroidWebFileChooserController(
    private val activity: ComponentActivity,
    private val request: () -> WebViewRequest,
    private val isAttached: (WebView) -> Boolean,
    private val permissions: AndroidWebPermissionController,
) {
    private var callback: ValueCallback<Array<Uri>>? = null
    private var callbackOwner: WebView? = null
    private var cameraOutputUri: Uri? = null
    private var pendingIntent: Intent? = null
    private var permissionKey: Any? = null
    private var externalResultInFlight = false
    private var requestId = 0L
    private var destroyed = false
    private val key = UUID.randomUUID().toString()
    private val fileLauncher = activity.activityResultRegistry.register(
        "cmp_web_file_$key",
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        externalResultInFlight = false
        handleResult(result.resultCode, result.data)
    }
    private val photoPickerLauncher = activity.activityResultRegistry.register(
        "cmp_web_photo_$key",
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        externalResultInFlight = false
        complete(uri?.let { arrayOf(it) })
    }

    fun show(
        webView: WebView?,
        callbackValue: ValueCallback<Array<Uri>>?,
        params: WebChromeClient.FileChooserParams?,
    ): Boolean {
        if (webView == null || !isAttached(webView)) {
            callbackValue?.onReceiveValue(null)
            return true
        }
        cancel()
        if (destroyed) {
            callbackValue?.onReceiveValue(null)
            return true
        }
        if (callbackValue == null) return false
        // ActivityResult 没有本次网页请求 ID；旧系统页面返回前不能把结果交给新实例。
        if (externalResultInFlight) {
            callbackValue.onReceiveValue(null)
            return true
        }
        val current = request()
        if (!current.security.fileChooserEnabled ||
            !current.security.trustedOrigins.isTrusted(webView?.url)
        ) {
            logWebWarning("已拦截非可信来源或未启用的文件选择请求")
            callbackValue.onReceiveValue(null)
            return true
        }

        callback = callbackValue
        callbackOwner = webView
        val currentRequestId = ++requestId
        val acceptTypes = normalizeWebViewMimeTypes(params?.acceptTypes.orEmpty())
        val captureEnabled = params?.isCaptureEnabled == true && current.security.mediaCaptureEnabled
        val allowMultiple = params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        val acceptsImage = accepts(acceptTypes, "image/")
        val acceptsVideo = accepts(acceptTypes, "video/")

        if (!captureEnabled && !allowMultiple && acceptsImage && !acceptsVideo && Build.VERSION.SDK_INT >= 33) {
            activity.runOnUiThread {
                if (requestId != currentRequestId) return@runOnUiThread
                if (callbackOwner !== webView || !allowed(webView)) {
                    cancel()
                    return@runOnUiThread
                }
                runCatching {
                    externalResultInFlight = true
                    photoPickerLauncher.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                }.onFailure {
                    externalResultInFlight = false
                    logWebWarning("系统 Photo Picker 启动失败，回退到 GET_CONTENT", it)
                    launchLegacyChooser(acceptTypes, captureEnabled, allowMultiple, currentRequestId, webView)
                }
            }
            return true
        }

        val intent = buildLegacyChooserIntent(acceptTypes, captureEnabled, allowMultiple)
        if (intent == null) cancel() else launchWithPermission(intent, webView)
        return true
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        cancel()
        fileLauncher.unregister()
        photoPickerLauncher.unregister()
    }

    fun release(owner: WebView) {
        if (callbackOwner === owner) cancel()
    }

    private fun launchLegacyChooser(
        acceptTypes: List<String>,
        captureEnabled: Boolean,
        allowMultiple: Boolean,
        currentRequestId: Long,
        webView: WebView?,
    ) {
        if (requestId != currentRequestId || callback == null) return
        val intent = buildLegacyChooserIntent(acceptTypes, captureEnabled, allowMultiple)
        if (intent == null) cancel() else launchWithPermission(intent, webView)
    }

    private fun buildLegacyChooserIntent(
        acceptTypes: List<String>,
        captureEnabled: Boolean,
        allowMultiple: Boolean,
    ): Intent? {
        val acceptsImage = accepts(acceptTypes, "image/")
        val acceptsVideo = accepts(acceptTypes, "video/")
        if (captureEnabled) {
            return when {
                acceptsVideo && !acceptsImage -> createCaptureIntent(video = true)
                acceptsImage -> createCaptureIntent(video = false)
                else -> null
            }
        }
        return Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
            when (acceptTypes.size) {
                0 -> type = "*/*"
                1 -> type = acceptTypes.first()
                else -> {
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes.toTypedArray())
                }
            }
        }
    }

    private fun createCaptureIntent(video: Boolean): Intent? = runCatching {
        val directory = if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val extension = if (video) ".mp4" else ".jpg"
        val prefix = if (video) "webview_video_" else "webview_capture_"
        val outputDirectory = activity.getExternalFilesDir(directory)
            ?: File(activity.cacheDir, "webview_capture/$directory")
        val output = File(outputDirectory, "$prefix${System.currentTimeMillis()}$extension")
            .also { it.parentFile?.mkdirs() }
        cameraOutputUri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.composewebview.fileprovider",
            output,
        )
        Intent(if (video) MediaStore.ACTION_VIDEO_CAPTURE else MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, cameraOutputUri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }.onFailure {
        cameraOutputUri = null
        logWebError("无法创建 WebView 拍摄输出文件", it)
    }.getOrNull()

    private fun launchWithPermission(intent: Intent, webView: WebView?) {
        val missing = capturePermissions(intent.action).filterNot(permissions::isGranted)
        if (missing.isEmpty()) {
            launch(intent)
            return
        }
        pendingIntent = intent
        val key = Any()
        permissionKey = key
        permissions.request(
            key = key,
            permissions = missing,
            purpose = WebPermissionPurpose.FILE_CAPTURE,
            stillAllowed = {
                callbackOwner === webView && webView != null && isAttached(webView) &&
                    request().security.run {
                        fileChooserEnabled && mediaCaptureEnabled && trustedOrigins.isTrusted(webView.url)
                    }
            },
            onResult = permissionResult@ { granted ->
                if (permissionKey !== key) return@permissionResult
                val pending = pendingIntent
                pendingIntent = null
                permissionKey = null
                if (granted && pending != null) launch(pending) else cancel()
            },
        )
    }

    private fun launch(intent: Intent) {
        val currentRequestId = requestId
        val owner = callbackOwner
        activity.runOnUiThread {
            if (requestId != currentRequestId) return@runOnUiThread
            if (callback == null || owner == null || !allowed(owner)) {
                cancel()
                return@runOnUiThread
            }
            externalResultInFlight = true
            runCatching { fileLauncher.launch(intent) }
                .onFailure {
                    externalResultInFlight = false
                    logWebError("无法启动 WebView 文件选择器", it)
                    cancel()
                }
        }
    }

    private fun handleResult(resultCode: Int, data: Intent?) {
        complete(resolveResult(resultCode, data))
    }

    private fun allowed(owner: WebView): Boolean = !destroyed && isAttached(owner) &&
        request().security.run { fileChooserEnabled && trustedOrigins.isTrusted(owner.url) }

    private fun complete(result: Array<Uri>?) {
        val current = callback
        val permitted = callbackOwner?.let(::allowed) == true
        callback = null
        callbackOwner = null
        cameraOutputUri = null
        current?.onReceiveValue(if (permitted) result else null)
    }

    private fun resolveResult(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != Activity.RESULT_OK) return null
        data?.clipData?.let { selected ->
            return Array(selected.itemCount) { index -> selected.getItemAt(index).uri }
        }
        data?.data?.let { return arrayOf(it) }
        return cameraOutputUri?.let { arrayOf(it) }
    }

    private fun cancel() {
        requestId++
        val key = permissionKey
        permissionKey = null
        if (key != null) permissions.cancel(key)
        pendingIntent = null
        complete(null)
    }
}

internal fun normalizeWebViewMimeTypes(values: Array<out String>): List<String> = values
    .flatMap { it.split(',') }
    .map { it.trim().lowercase() }
    .filter { it == "*/*" || MIME_TYPE_PATTERN.matches(it) }
    .distinct()

private fun accepts(types: List<String>, prefix: String): Boolean =
    types.isEmpty() || types.any { it.startsWith(prefix) || it == "*/*" }

internal fun capturePermissions(action: String?): List<String> = when (action) {
    MediaStore.ACTION_IMAGE_CAPTURE -> listOf(Manifest.permission.CAMERA)
    MediaStore.ACTION_VIDEO_CAPTURE -> listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    else -> emptyList()
}

private val MIME_TYPE_PATTERN = Regex("^[a-z0-9][a-z0-9.+-]*/[a-z0-9*][a-z0-9.+*-]*$")
