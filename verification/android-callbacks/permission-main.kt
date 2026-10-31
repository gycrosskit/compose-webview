package io.github.gycrosskit.composewebview

import android.Manifest
import android.app.AlertDialog
import androidx.activity.ComponentActivity

fun main() {
    val camera = Manifest.permission.CAMERA
    val explanationActivity = ComponentActivity()
    val explanationResults = mutableListOf<Boolean>()
    val explanationController = AndroidWebPermissionController(explanationActivity) { error("Explanation cancellation has no settings hint") }
    fun requestExplanation() = explanationController.request(permissions = listOf(camera),
        purpose = WebPermissionPurpose.MEDIA_CAPTURE, stillAllowed = { true }, onResult = explanationResults::add)
    requestExplanation()
    val firstExplanation = AlertDialog.latest!!
    firstExplanation.cancelByUser()
    requestExplanation()
    check(AlertDialog.latest !== firstExplanation && explanationResults == listOf(false))
    AlertDialog.latest!!.cancelByUser()
    check(explanationResults == listOf(false, false) && explanationActivity.activityResultRegistry.launchers.single().launches == 0)
    explanationController.destroy()

    val activity = ComponentActivity()
    val settings = mutableListOf<Set<WebViewPermission>>()
    val results = mutableListOf<Boolean>()
    val controller = AndroidWebPermissionController(activity) { settings += it }
    val launcher = activity.activityResultRegistry.launchers.single()
    fun request(key: Any = Any(), allowed: () -> Boolean = { true }) {
        controller.request(key, listOf(camera), WebPermissionPurpose.MEDIA_CAPTURE, allowed, results::add)
    }
    fun deliver(value: Map<String, Boolean>) = launcher.deliver(value)

    request()
    check(launcher.launches == 0 && settings.isEmpty()) // First use still shows the native explanation.
    request() // Both requests arrived before either had actually requested platform permission.
    AlertDialog.latest!!.acknowledge()
    check(launcher.launches == 1)
    deliver(mapOf(camera to false))
    check(results == listOf(false) && settings.isEmpty() && launcher.launches == 2)
    deliver(mapOf(camera to false))
    check(results == listOf(false, false) && settings.isEmpty()) // Queue progress cannot upgrade a first request.
    request()
    check(launcher.launches == 3)
    deliver(mapOf(camera to false))
    check(results.last() == false && settings == listOf(setOf(WebViewPermission.CAMERA)))
    request()
    check(launcher.launches == 4)
    deliver(emptyMap())
    check(results == listOf(false, false, false, false) && settings.size == 1)
    request()
    check(launcher.launches == 5) // Empty cancellation must not become a pre-request settings block.
    activity.rationale += camera
    deliver(mapOf(camera to false))
    check(results == listOf(false, false, false, false, false) && settings.size == 1)
    activity.rationale -= camera
    request()
    check(launcher.launches == 6) // Even a previous settings hint cannot replace a new platform request.
    activity.grants += camera
    deliver(mapOf(camera to false))
    check(results.last() && settings.size == 1) // Current permission state wins over a stale false entry.
    activity.grants.clear()

    request()
    activity.packageManager.policyRevoked += camera
    deliver(mapOf(camera to false))
    check(!results.last() && settings.size == 1) // Device policy cannot be repaired in app settings.
    activity.packageManager.policyRevoked.clear()

    val cancelled = Any()
    request(cancelled)
    val launches = launcher.launches
    controller.cancel(cancelled)
    val before = results.size
    request()
    check(launcher.launches == launches) // Old ActivityResult keeps the in-flight barrier.
    deliver(mapOf(camera to false))
    check(results.size == before && settings.size == 1 && launcher.launches == launches + 1)
    activity.grants += camera
    deliver(mapOf(camera to true))
    check(results.size == before + 1 && results.last())
    activity.grants.clear()

    var allowed = true
    request(allowed = { allowed })
    allowed = false
    deliver(mapOf(camera to false))
    check(!results.last() && settings.size == 1) // Hidden/old owner cannot receive a settings hint.
    allowed = true
    request(allowed = { allowed })
    allowed = false
    activity.grants += camera
    deliver(mapOf(camera to true))
    check(!results.last()) // A late grant still fails the owner gate.
    activity.grants.clear()

    val stale = Any()
    request(stale)
    controller.cancel(stale)
    val count = results.size
    activity.grants += camera
    deliver(mapOf(camera to true))
    check(results.size == count && settings.size == 1) // Explicit cancellation never delivers a late grant.
    controller.destroy()

    val microphone = Manifest.permission.RECORD_AUDIO
    val grantActivity = ComponentActivity()
    val grantSettings = mutableListOf<Set<WebViewPermission>>()
    val grantResults = mutableListOf<Boolean>()
    val grantController = AndroidWebPermissionController(grantActivity) { grantSettings += it }
    fun requestMicrophone() = grantController.request(permissions = listOf(microphone),
        purpose = WebPermissionPurpose.MEDIA_CAPTURE, stillAllowed = { true }, onResult = grantResults::add)
    requestMicrophone()
    AlertDialog.latest!!.acknowledge()
    val grantLauncher = grantActivity.activityResultRegistry.launchers.single()
    grantActivity.grants += microphone
    grantLauncher.deliver(mapOf(microphone to true))
    check(grantResults == listOf(true) && grantSettings.isEmpty())
    grantActivity.grants.clear()
    requestMicrophone()
    check(grantLauncher.launches == 2) // Revocation after a real grant still reaches the platform.
    grantLauncher.deliver(mapOf(microphone to false))
    check(grantResults == listOf(true, false) && grantSettings == listOf(setOf(WebViewPermission.MICROPHONE)))
    grantController.destroy()

    val reentrantActivity = ComponentActivity()
    var oldAllowed = true
    val reentrantResults = mutableListOf<Pair<String, Boolean>>()
    lateinit var reentrant: AndroidWebPermissionController
    reentrant = AndroidWebPermissionController(reentrantActivity) {
        oldAllowed = false
        reentrant.request(permissions = listOf(camera), purpose = WebPermissionPurpose.MEDIA_CAPTURE,
            stillAllowed = { true }, onResult = { reentrantResults += "new" to it })
    }
    reentrant.request(permissions = listOf(camera), purpose = WebPermissionPurpose.MEDIA_CAPTURE,
        stillAllowed = { oldAllowed }, onResult = { reentrantResults += "old" to it })
    val reentrantLauncher = reentrantActivity.activityResultRegistry.launchers.single()
    reentrantLauncher.deliver(mapOf(camera to false))
    check(reentrantLauncher.launches == 2 && reentrantResults == listOf("old" to false))
    reentrantActivity.grants += camera
    reentrantLauncher.deliver(mapOf(camera to true))
    check(reentrantResults == listOf("old" to false, "new" to true))
    reentrant.destroy()
    println("PASS: production Android permission controller: explanation cancellation/retry, empty/cancel retry, first/repeated denial, grant revocation, platform/policy recheck, queue barrier and late-owner rejection")
}
