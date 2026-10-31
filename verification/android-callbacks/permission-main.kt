package io.github.gycrosskit.composewebview

import android.Manifest
import android.app.AlertDialog
import androidx.activity.ComponentActivity

fun main() {
    val camera = Manifest.permission.CAMERA
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
    AlertDialog.latest!!.acknowledge()
    check(launcher.launches == 1)
    deliver(emptyMap())
    check(results == listOf(false) && settings.isEmpty())
    request()
    check(launcher.launches == 2) // Empty cancellation must not become a pre-request settings block.
    activity.rationale += camera
    deliver(mapOf(camera to false))
    check(results == listOf(false, false) && settings.isEmpty())
    request()
    check(launcher.launches == 3)
    activity.rationale -= camera
    deliver(mapOf(camera to false))
    check(results.last() == false && settings == listOf(setOf(WebViewPermission.CAMERA)))
    request()
    check(launcher.launches == 4) // Even a previous settings hint cannot replace a new platform request.
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
    deliver(emptyMap())
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
    println("PASS: production Android permission controller: explanation, empty/cancel retry, first/repeated denial, platform/policy recheck, queue barrier and late-owner rejection")
}
