package io.github.gycrosskit.composewebview

import android.Manifest
import androidx.activity.ComponentActivity

fun main() {
    val camera = Manifest.permission.CAMERA
    val activity = ComponentActivity()
    val completions = mutableListOf<(Boolean) -> Unit>()
    val cancellations = mutableListOf<Int>()
    val results = mutableListOf<Pair<String, Boolean>>()
    var cancelReentry: (() -> Unit)? = null
    val controller = AndroidWebPermissionController(activity, showPermissionExplanation = { permissions, complete ->
        check(permissions == setOf(WebViewPermission.CAMERA))
        val index = completions.size
        completions += complete
        { cancellations += index; cancelReentry?.also { cancelReentry = null }?.invoke(); Unit }
    }) { error("No system rejection has occurred") }
    val launcher = activity.activityResultRegistry.launchers.single()
    fun request(name: String, key: Any = Any(), allowed: () -> Boolean = { true }) {
        controller.request(key, listOf(camera), WebPermissionPurpose.MEDIA_CAPTURE, allowed) { results += name to it }
    }
    request("denied")
    check(launcher.launches == 0 && completions.size == 1)
    completions[0](false)
    check(results == listOf("denied" to false) && cancellations.isEmpty())
    val oldKey = Any()
    request("old", oldKey)
    check(!controller.isSystemRequestInFlight(oldKey))
    cancelReentry = { request("new") }
    controller.cancel(oldKey)
    check(cancellations == listOf(1) && completions.size == 3)
    completions[1](true)
    check(launcher.launches == 0) // Old host confirmation cannot authorize the reentrant request.
    completions[2](false)
    check(results.last() == "new" to false)
    var visible = true
    request("hidden", allowed = { visible })
    visible = false
    completions.last()(true)
    check(launcher.launches == 0 && results.last() == "hidden" to false)
    visible = true
    run {
        val reentrantActivity = ComponentActivity()
        val old = Any()
        val replacement = Any()
        val cancelled = mutableListOf<Int>()
        var count = 0
        lateinit var reentrant: AndroidWebPermissionController
        reentrant = AndroidWebPermissionController(reentrantActivity, showPermissionExplanation = { _, _ ->
            val index = count++
            if (index == 0) {
                reentrant.cancel(old)
                reentrant.request(replacement, listOf(camera), WebPermissionPurpose.MEDIA_CAPTURE, { true }) {}
            }
            { cancelled += index; Unit }
        }) { error("No system request") }
        reentrant.request(old, listOf(camera), WebPermissionPurpose.MEDIA_CAPTURE, { true }) {}
        check(count == 2 && cancelled == listOf(0))
        reentrant.cancel(replacement)
        check(cancelled == listOf(0, 1)) // The old late cancel handle did not overwrite its replacement.
        reentrant.destroy()
    }
    val confirmedKey = Any()
    request("confirmed", confirmedKey)
    val confirmed = completions.last()
    confirmed(true)
    check(controller.isSystemRequestInFlight(confirmedKey) && launcher.launches == 1)
    confirmed(true)
    confirmed(false)
    check(launcher.launches == 1) // Host reply is one-shot even before ActivityResult arrives.
    activity.grants += camera
    launcher.deliver(mapOf(camera to true))
    check(results.last() == "confirmed" to true)
    controller.destroy()
    confirmed(true)
    check(launcher.launches == 1)
    println("PASS: production Android HOST explanation: reject, cancel/reentry, stale and duplicate replies, hidden recheck, system grant")
}
