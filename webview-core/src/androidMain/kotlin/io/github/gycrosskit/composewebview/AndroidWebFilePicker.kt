package io.github.gycrosskit.composewebview

import android.net.Uri

/** 文件选择由可信宿主注入；宿主须在后台验证 URI 可读性、真实 MIME、名称和大小。 */
data class AndroidWebFilePickerRequest(val acceptTypes: List<String>, val multiple: Boolean, val capture: Boolean)
data class AndroidWebPickedFile(val uri: Uri, val name: String, val mimeType: String, val size: Long)
sealed interface AndroidWebFilePickerResult {
    data class Selected(val files: List<AndroidWebPickedFile>, val release: () -> Unit) : AndroidWebFilePickerResult
    data object Cancelled : AndroidWebFilePickerResult
    data object PermissionDenied : AndroidWebFilePickerResult
    data object PermissionBlocked : AndroidWebFilePickerResult
    data object PermissionRestricted : AndroidWebFilePickerResult
    data class Failed(val message: String) : AndroidWebFilePickerResult
}
fun interface AndroidWebFilePicker {
    /** 返回的句柄用于撤销尚未完成的请求；已返回的文件由 Selected.release 管理。 */
    fun pick(request: AndroidWebFilePickerRequest, complete: (AndroidWebFilePickerResult) -> Unit): () -> Unit
}
