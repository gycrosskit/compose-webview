package io.github.gycrosskit.composewebview

import android.Manifest
import android.app.AlertDialog
import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity

/** 选择能力归宿主；本控制器持有权限、来源、结果和页面所有权。 */
internal class AndroidWebFileChooserController(
    private val activity: ComponentActivity,
    private val request: () -> WebViewRequest,
    private val isAttached: (WebView) -> Boolean,
    private val permissions: AndroidWebPermissionController,
    private val delegate: AndroidWebFilePicker? = null,
) {
    private var callback: ValueCallback<Array<Uri>>? = null
    private var owner: WebView? = null
    private var pending: AndroidWebFilePickerRequest? = null
    private var permissionKey: Any? = null
    private var cancelPicker: (() -> Unit)? = null
    private var pickerInFlight = false
    private var suspendedOwner: WebView? = null
    private var pendingResult: Pair<Array<Uri>, () -> Unit>? = null
    private var launchAfterResume = false
    private var captureChoice: AlertDialog? = null
    private val delivered = mutableMapOf<WebView, MutableList<() -> Unit>>()
    private var acceptedTypes: List<String> = emptyList()
    private var acceptedExtensions: List<String> = emptyList()
    private var maxFiles = 1
    private var generation = 0L
    private var destroyed = false

    fun show(webView: WebView?, result: ValueCallback<Array<Uri>>?, params: WebChromeClient.FileChooserParams?): Boolean {
        trace("selector-enter")
        if (webView == null || !isAttached(webView)) { result?.onReceiveValue(null); return true }
        val previous = generation
        cancel()
        if (generation != previous + 1) { result?.onReceiveValue(null); return true }
        if (destroyed || delegate == null || !allowed(webView)) { trace("selector-unavailable"); result?.onReceiveValue(null); return true }
        if (result == null) return false
        callback = result; owner = webView
        val ticket = ++generation
        acceptedTypes = normalizeWebViewMimeTypes(params?.acceptTypes.orEmpty())
        acceptedExtensions = params?.acceptTypes.orEmpty().flatMap { it.split(',') }.map { it.trim().lowercase() }
            .filter { it.startsWith('.') && MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.drop(1)) == null }
        val multiple = params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        maxFiles = if (multiple) 10 else 1
        val capture = params?.isCaptureEnabled == true && request().security.mediaCaptureEnabled
        val raw = params?.acceptTypes?.toList().orEmpty()
        val image = accepts(acceptedTypes, "image/"); val video = accepts(acceptedTypes, "video/")
        if (capture && image && video) {
            captureChoice = AlertDialog.Builder(activity).setItems(arrayOf(activity.getString(R.string.web_capture_photo), activity.getString(R.string.web_capture_video))) { _, which ->
                captureChoice = null
                if (ticket != generation || !allowed(webView)) { cancel(); return@setItems }
                val isVideo = which == 1
                val prefix = if (isVideo) "video/" else "image/"
                val selected = acceptedTypes.filter { it.startsWith(prefix) }.ifEmpty { listOf("${prefix}*") }
                pending = AndroidWebFilePickerRequest(selected, multiple, true)
                launchWithPermission(capture = true, video = isVideo, webView)
            }.setOnCancelListener { cancel() }.create().also { it.show() }
        } else {
            pending = AndroidWebFilePickerRequest(raw, multiple, capture)
            launchWithPermission(capture, video && !image, webView)
        }
        return true
    }

    fun destroy() { if (!destroyed) { destroyed = true; cancel(); delivered.values.flatten().forEach { it() }; delivered.clear() } }
    fun release(view: WebView) {
        val ticket = generation
        delivered.remove(view)?.forEach { it() }
        if (ticket == generation && owner === view) cancel()
    }
    fun pause(view: WebView) { if (owner === view) cancel() }
    fun suspendForExternal(view: WebView): Boolean {
        if ((!pickerInFlight && !permissions.isSystemRequestInFlight(permissionKey)) || owner !== view) return false
        suspendedOwner = view; return true
    }
    fun resume(view: WebView) {
        if (suspendedOwner !== view) return
        suspendedOwner = null
        pendingResult?.let { pendingResult = null; complete(it.first, it.second); return }
        if (launchAfterResume) { launchAfterResume = false; if (pending != null && owner === view) launch(view) }
    }
    private fun launchWithPermission(capture: Boolean, video: Boolean, view: WebView) {
        val missing = capturePermissions(capture, video).filterNot(permissions::isGranted)
        if (missing.isEmpty()) { launch(view); return }
        val key = Any(); permissionKey = key
        permissions.request(key = key, permissions = missing, purpose = WebPermissionPurpose.FILE_CAPTURE,
            stillAllowed = { owner === view && (isAttached(view) || suspendedOwner === view) && request().security.run {
                fileChooserEnabled && mediaCaptureEnabled && trustedOrigins.isTrusted(view.url)
            } }, onResult = permissionResult@ { granted ->
                if (permissionKey !== key) return@permissionResult
                permissionKey = null
                if (granted && pending != null && suspendedOwner === view && !isAttached(view)) launchAfterResume = true
                else if (granted && pending != null) launch(view) else cancel()
            })
    }
    private fun launch(view: WebView) {
        val ticket = generation
        activity.runOnUiThread {
            if (ticket != generation) return@runOnUiThread
            val picker = delegate; val input = pending
            if (picker == null || input == null || callback == null || owner !== view || !allowed(view)) { cancel(); return@runOnUiThread }
            trace("picker-start")
            pickerInFlight = true
            try {
                val cancel = picker.pick(input) { outcome ->
                    trace(when (outcome) {
                        is AndroidWebFilePickerResult.Selected -> "picker-selected"
                        AndroidWebFilePickerResult.Cancelled -> "picker-cancelled"
                        AndroidWebFilePickerResult.PermissionDenied -> "picker-permission-denied"
                        AndroidWebFilePickerResult.PermissionBlocked -> "picker-permission-blocked"
                        AndroidWebFilePickerResult.PermissionRestricted -> "picker-permission-restricted"
                        is AndroidWebFilePickerResult.Failed -> "picker-failed"
                    })
                    if (ticket != generation || owner !== view) {
                        trace("picker-stale")
                        if (outcome is AndroidWebFilePickerResult.Selected) outcome.release()
                    } else {
                        pickerInFlight = false; cancelPicker = null; pending = null
                        if (outcome is AndroidWebFilePickerResult.Selected) {
                            val uris = outcome.files.map { it.uri }.toTypedArray()
                            if (uris.size in 1..maxFiles && outcome.files.all(::validFile)) complete(uris, outcome.release)
                            else { trace("picker-invalid"); complete(null); outcome.release() }
                        } else complete(null)
                    }
                }
                if (ticket == generation && pickerInFlight) cancelPicker = cancel else cancel()
            } catch (_: Exception) { trace("picker-launch-failed"); if (ticket == generation) cancel() }
        }
    }
    private fun allowed(view: WebView): Boolean = !destroyed && isAttached(view) && request().security.run { fileChooserEnabled && trustedOrigins.isTrusted(view.url) }
    private fun complete(result: Array<Uri>?, release: () -> Unit = {}) {
        val currentOwner = owner
        if (result != null && suspendedOwner != null && suspendedOwner === currentOwner && currentOwner != null && !isAttached(currentOwner)) {
            pendingResult = result to release; return
        }
        val permitted = result != null && currentOwner != null && allowed(currentOwner)
        val current = callback
        callback = null; owner = null; suspendedOwner = null; pendingResult = null; pending = null
        if (permitted) delivered.getOrPut(requireNotNull(currentOwner)) { mutableListOf() }.add(release) else release()
        current?.onReceiveValue(if (permitted) result else null)
    }
    private fun validFile(file: AndroidWebPickedFile): Boolean = runCatching {
        val mime = canonicalFileMime(file.mimeType.lowercase())
        val validType = (acceptedTypes.isEmpty() && acceptedExtensions.isEmpty()) || acceptedTypes.any {
            it == "*/*" || it == mime || (it.endsWith("/*") && mime.startsWith(it.dropLast(1)))
        } || acceptedExtensions.any { file.name.lowercase().endsWith(it) }
        file.uri.scheme == "content" && !file.uri.authority.isNullOrBlank() && mime.isNotBlank() && file.size in 1L..50L * 1024L * 1024L && validType
    }.getOrDefault(false)
    private fun cancel() {
        generation++
        captureChoice?.dismiss(); captureChoice = null
        val pickerCancel = cancelPicker; cancelPicker = null; pickerInFlight = false
        val key = permissionKey; permissionKey = null; launchAfterResume = false
        val releasePending = pendingResult?.second; pendingResult = null
        complete(null)
        releasePending?.invoke()
        pickerCancel?.invoke()
        if (key != null) permissions.cancel(key)
    }
    private fun trace(stage: String) { runCatching { AppWebViewRuntime.log(AppWebViewLogLevel.DEBUG, "file-picker $stage") } }
}

internal fun normalizeWebViewMimeTypes(values: Array<out String>): List<String> = values.flatMap { it.split(',') }.map { it.trim().lowercase() }
    .map { if (it.startsWith('.')) MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.drop(1)) ?: it else it }
    .map(::canonicalFileMime)
    .filter { it == "*/*" || MIME_TYPE_PATTERN.matches(it) }.distinct()
private fun accepts(types: List<String>, prefix: String): Boolean = types.isEmpty() || types.any { it.startsWith(prefix) || it == "*/*" }
internal fun capturePermissions(capture: Boolean, video: Boolean): List<String> = when {
    !capture -> emptyList()
    video -> listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    else -> listOf(Manifest.permission.CAMERA)
}
private val MIME_TYPE_PATTERN = Regex("^[a-z0-9][a-z0-9.+-]*/[a-z0-9*][a-z0-9.+*-]*$")
private fun canonicalFileMime(value: String): String = when (value) {
    "image/jpg" -> "image/jpeg"
    "video/mov" -> "video/quicktime"
    "*" -> "*/*"
    else -> value
}
