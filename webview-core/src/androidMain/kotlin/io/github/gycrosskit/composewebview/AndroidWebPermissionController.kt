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

/** 串行管理 WebView 的原生权限说明与系统申请。 */
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
        // 入队后才发生的申请不能把同时到达的首次请求升级为永久拒绝。
        var previouslyRequested = permissions.filter(WebPermissionSessionHistory::wasRequested).toSet()
    }

    private val queue = ArrayDeque<PendingPermission>()
    private var active: PendingPermission? = null
    private var explanation: AlertDialog? = null
    private var systemRequestInFlight = false
    private var destroyed = false
    private val launcher = activity.activityResultRegistry.register(
        "cmp_web_permission_${UUID.randomUUID()}",
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val pending = active ?: return@register
        active = null
        systemRequestInFlight = false
        if (pending.cancelled) {
            launchNext()
            return@register
        }
        // 首次拒绝和空回执不能认定永久拒绝；历史只作为本次真实拒绝的前置条件。
        val blocked = pending.permissions.filter { permission ->
            permission in pending.previouslyRequested && results[permission] == false && !isGranted(permission) &&
                !activity.packageManager.isPermissionRevokedByPolicy(permission, activity.packageName) &&
                !activity.shouldShowRequestPermissionRationale(permission)
        }
        if (blocked.isNotEmpty() && pending.stillAllowed()) {
            onPermissionSettingsRequired(blocked.toWebViewPermissions())
        }
        val granted = results.isNotEmpty() && pending.permissions.all(::isGranted)
        pending.onResult(granted && !destroyed && pending.stillAllowed())
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
        next.previouslyRequested = next.previouslyRequested.intersect(missing.toSet())
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

/** 进程内历史避免重复用途说明；只有历史加本次系统拒绝才能交付设置提示。 */
private object WebPermissionSessionHistory {
    private val requestedPermissions = mutableSetOf<String>()

    fun wasRequested(permission: String): Boolean = permission in requestedPermissions

    fun markRequested(permissions: Collection<String>) {
        requestedPermissions += permissions
    }
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
