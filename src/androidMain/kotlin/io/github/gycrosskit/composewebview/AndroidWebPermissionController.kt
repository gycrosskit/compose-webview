package io.github.gycrosskit.composewebview

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.util.UUID

/** 串行管理 WebView 的原生权限说明、系统申请与当前进程拒绝历史。 */
internal class AndroidWebPermissionController(
    private val activity: ComponentActivity,
    private val onPermissionSettingsRequired: (Set<WebViewPermission>) -> Unit,
) {
    private data class PendingPermission(
        val key: Any,
        val permissions: Set<String>,
        val purpose: WebPermissionPurpose,
        val stillAllowed: () -> Boolean,
        val onResult: (Boolean) -> Unit,
    ) {
        var cancelled = false
    }

    private val queue = ArrayDeque<PendingPermission>()
    private var active: PendingPermission? = null
    private var explanation: AlertDialog? = null
    private var systemRequestInFlight = false
    private val declinedExplanations = mutableSetOf<String>()
    private var destroyed = false
    private val launcher = activity.activityResultRegistry.register(
        "cmp_web_permission_${UUID.randomUUID()}",
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val pending = active ?: return@register
        active = null
        systemRequestInFlight = false
        if (pending.cancelled) {
            launchNext()
            return@register
        }
        val granted = pending.permissions.all(::isGranted)
        val stillAllowed = pending.stillAllowed()
        pending.onResult(granted && stillAllowed)
        launchNext()
    }

    fun request(
        key: Any = Any(),
        permissions: Collection<String>,
        purpose: WebPermissionPurpose,
        stillAllowed: () -> Boolean,
        onResult: (Boolean) -> Unit,
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            activity.runOnUiThread { request(key, permissions, purpose, stillAllowed, onResult) }
            return
        }
        if (destroyed) {
            onResult(false)
            return
        }
        if (!stillAllowed()) {
            onResult(false)
            return
        }
        val normalized = permissions.filter(String::isNotBlank).toSet()
        if (normalized.isEmpty() || normalized.all(::isGranted)) {
            onResult(true)
            return
        }
        queue.addLast(PendingPermission(key, normalized, purpose, stillAllowed, onResult))
        launchNext()
    }

    /** 取消业务请求；系统弹窗已发出时仍占住 active，直到旧 ActivityResult 返回。 */
    fun cancel(key: Any) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            activity.runOnUiThread { cancel(key) }
            return
        }
        queue.removeAll { it.key === key }
        val pending = active?.takeIf { it.key === key } ?: return
        pending.cancelled = true
        if (systemRequestInFlight) return
        active = null
        explanation?.setOnCancelListener(null)
        explanation?.setOnDismissListener(null)
        explanation?.dismiss()
        explanation = null
        launchNext()
    }

    fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    fun destroy() {
        if (destroyed) return
        destroyed = true
        queue.clear()
        active = null
        systemRequestInFlight = false
        explanation?.setOnCancelListener(null)
        explanation?.setOnDismissListener(null)
        explanation?.dismiss()
        explanation = null
        launcher.unregister()
    }

    private fun launchNext() {
        if (destroyed || active != null) return
        val next = queue.removeFirstOrNull() ?: return
        if (!next.stillAllowed()) {
            next.onResult(false)
            launchNext()
            return
        }
        val missing = next.permissions.filterNot(::isGranted)
        if (missing.isEmpty()) {
            next.onResult(true)
            launchNext()
            return
        }
        val blocked = missing.filter { permission ->
            resolveWebPermissionRequestAction(
                requestedBefore = WebPermissionSessionHistory.wasRequested(permission),
                shouldShowRationale = activity.shouldShowRequestPermissionRationale(permission),
            ) == WebPermissionRequestAction.SHOW_SETTINGS
        }
        if (blocked.isNotEmpty()) {
            logWebWarning("WebView 媒体权限已被系统阻止，本次引导用户前往设置")
            next.onResult(false)
            onPermissionSettingsRequired(blocked.toWebViewPermissions())
            launchNext()
            return
        }
        if (missing.any(declinedExplanations::contains)) {
            next.onResult(false)
            launchNext()
            return
        }
        active = next
        if (missing.all(WebPermissionSessionHistory::wasRequested)) {
            launchSystemPermission(next, missing)
        } else {
            showExplanation(next, missing)
        }
    }

    private fun showExplanation(next: PendingPermission, missing: List<String>) {
        val content = activity.webPermissionExplanation(next.purpose, missing)
        var launchAfterDismiss = false
        runCatching {
            explanation = AlertDialog.Builder(activity)
                .setTitle(content.title)
                .setMessage(content.message)
                .setPositiveButton(R.string.web_permission_acknowledge) { _, _ ->
                    launchAfterDismiss = true
                }
                .setOnCancelListener {
                    if (destroyed || active !== next || next.cancelled) return@setOnCancelListener
                    declinedExplanations += missing
                    completeExplanation(next, granted = false)
                }
                .setOnDismissListener {
                    if (!destroyed && active === next && !next.cancelled && launchAfterDismiss) {
                        launchSystemPermission(next, missing)
                    }
                }
                .create()
                .also {
                    it.setCanceledOnTouchOutside(false)
                    it.show()
                }
        }.onFailure {
            explanation = null
            active = null
            logWebError("无法展示 WebView 权限用途说明", it)
            next.onResult(false)
            launchNext()
        }
    }

    private fun launchSystemPermission(next: PendingPermission, missing: List<String>) {
        if (destroyed || active !== next || next.cancelled) return
        explanation = null
        if (!next.stillAllowed()) {
            completeExplanation(next, granted = false)
            return
        }
        systemRequestInFlight = true
        runCatching { launcher.launch(missing.toTypedArray()) }
            .onSuccess { WebPermissionSessionHistory.markRequested(missing) }
            .onFailure {
                systemRequestInFlight = false
                active = null
                logWebError("无法请求 WebView 媒体权限", it)
                next.onResult(false)
                launchNext()
            }
    }

    private fun completeExplanation(next: PendingPermission, granted: Boolean) {
        if (destroyed || active !== next || next.cancelled) return
        explanation = null
        active = null
        next.onResult(granted)
        launchNext()
    }
}

internal enum class WebPermissionPurpose {
    FILE_CAPTURE,
    MEDIA_CAPTURE,
}

internal data class WebPermissionExplanation(
    val title: String,
    val message: String,
)

internal data class WebPermissionExplanationResources(
    val permissionName: Int,
    val scenario: Int,
    val impact: Int,
)

/** 权限名称、使用场景和拒绝影响由原生代码选择，H5 不能绕过或替换这段说明。 */
internal fun webPermissionExplanationResources(
    purpose: WebPermissionPurpose,
    permissions: Collection<String>,
): WebPermissionExplanationResources {
    val camera = Manifest.permission.CAMERA in permissions
    val microphone = Manifest.permission.RECORD_AUDIO in permissions
    val permissionName = when {
        camera && microphone -> R.string.web_permission_name_camera_microphone
        microphone -> R.string.web_permission_name_microphone
        else -> R.string.web_permission_name_camera
    }
    val scenario = when {
        purpose == WebPermissionPurpose.FILE_CAPTURE && microphone -> R.string.web_permission_scenario_upload_video
        purpose == WebPermissionPurpose.FILE_CAPTURE -> R.string.web_permission_scenario_upload_photo
        camera && microphone -> R.string.web_permission_scenario_av_call
        microphone -> R.string.web_permission_scenario_audio_call
        else -> R.string.web_permission_scenario_video_capture
    }
    val impact = when {
        camera && microphone -> R.string.web_permission_impact_camera_microphone
        microphone -> R.string.web_permission_impact_microphone
        else -> R.string.web_permission_impact_camera
    }
    return WebPermissionExplanationResources(permissionName, scenario, impact)
}

private fun Context.webPermissionExplanation(
    purpose: WebPermissionPurpose,
    permissions: Collection<String>,
): WebPermissionExplanation {
    val resources = webPermissionExplanationResources(purpose, permissions)
    return WebPermissionExplanation(
        title = getString(R.string.web_permission_explanation_title),
        message = buildString {
            appendLine(getString(resources.permissionName))
            appendLine()
            appendLine(getString(resources.scenario))
            append(getString(resources.impact))
        },
    )
}

/** WebView 权限历史只保留在当前进程，重启后重新根据系统事实判断。 */
private object WebPermissionSessionHistory {
    private val requestedPermissions = mutableSetOf<String>()

    fun wasRequested(permission: String): Boolean = permission in requestedPermissions

    fun markRequested(permissions: Collection<String>) {
        requestedPermissions += permissions
    }
}

internal enum class WebPermissionRequestAction {
    REQUEST_PERMISSION,
    SHOW_SETTINGS,
}

/** Android 只有在当前进程已经请求且系统不再允许弹窗时，才能确认需要设置页恢复。 */
internal fun resolveWebPermissionRequestAction(
    requestedBefore: Boolean,
    shouldShowRationale: Boolean,
): WebPermissionRequestAction = if (requestedBefore && !shouldShowRationale) {
    WebPermissionRequestAction.SHOW_SETTINGS
} else {
    WebPermissionRequestAction.REQUEST_PERMISSION
}

internal fun Collection<String>.toWebViewPermissions(): Set<WebViewPermission> = mapNotNullTo(
    linkedSetOf(),
) { permission ->
    when (permission) {
        Manifest.permission.CAMERA -> WebViewPermission.CAMERA
        Manifest.permission.RECORD_AUDIO -> WebViewPermission.MICROPHONE
        else -> null
    }
}
