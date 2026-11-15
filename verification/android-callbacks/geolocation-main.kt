package io.github.gycrosskit.composewebview

import android.Manifest
import android.webkit.GeolocationPermissions
import android.webkit.WebView
import androidx.activity.ComponentActivity

fun main() {
    val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
    run {
        val activity = ComponentActivity()
        val owner = WebView()
        var explanation: ((Boolean) -> Unit)? = null
        val permissions = AndroidWebPermissionController(activity,
            showPermissionExplanation = { _, complete -> explanation = complete; {} },
            onPermissionSettingsRequired = {})
        val controller = AndroidWebGeolocationController({ WebViewRequest() }, { true }, permissions)
        val results = mutableListOf<Boolean>()
        controller.request(owner, "https://safe.test", GeolocationPermissions.Callback { _, allow, _ -> results += allow })
        val old = requireNotNull(explanation)
        check(!controller.pause(owner))
        old(true)
        check(results == listOf(false))
        check(activity.activityResultRegistry.launchers.single().launches == 0)
        controller.destroy()
        permissions.destroy()
    }
    fun scenario(grant: Boolean, revoke: Boolean = false) {
        val activity = ComponentActivity()
        val owner = WebView()
        var active = true
        var visible = true
        var explanation: ((Boolean) -> Unit)? = null
        val permissions = AndroidWebPermissionController(activity,
            showPermissionExplanation = { _, complete -> explanation = complete; {} },
            onPermissionSettingsRequired = {})
        val controller = AndroidWebGeolocationController({ WebViewRequest() }, { active }, permissions, { visible })
        val results = mutableListOf<Boolean>()
        val callback = GeolocationPermissions.Callback { _, allowed, retain -> check(!retain); results += allowed }
        controller.request(owner, "https://other.test", callback)
        check(results == listOf(false) && explanation == null) // Trusted cross-origin is still rejected.
        results.clear()
        controller.request(owner, "https://safe.test", callback)
        val launcher = activity.activityResultRegistry.launchers.single()
        explanation?.invoke(true)
        check(launcher.launches == 1)
        visible = false
        check(controller.pause(owner))
        check(results.isEmpty()) // System ON_PAUSE must not settle the web callback early.
        if (revoke) {
            active = false
            controller.cancel(owner)
            check(results == listOf(false))
        }
        if (grant) activity.grants += coarse
        launcher.deliver(mapOf(coarse to grant))
        check(results == listOf(grant && !revoke)) // Delivered once, even before ON_RESUME.
        controller.destroy()
        permissions.destroy()
    }
    scenario(grant = true)
    scenario(grant = false)
    scenario(grant = true, revoke = true)
    run {
        val activity = ComponentActivity()
        val results = mutableListOf<Boolean>()
        val permissions = AndroidWebPermissionController(activity, onPermissionSettingsRequired = {})
        permissions.request(permissions = listOf(coarse), purpose = WebPermissionPurpose.GEOLOCATION,
            stillAllowed = { true }, canPresentUi = { false }, onResult = { results += it })
        check(results == listOf(false))
        check(activity.activityResultRegistry.launchers.single().launches == 0)
        permissions.destroy()
    }
    println("PASS: production Android geolocation: system pause, grant/deny, owner revoke, same-origin and hidden UI gate")
}
