package io.github.gycrosskit.composewebview

import android.Manifest
import android.webkit.GeolocationPermissions
import android.webkit.WebView
import androidx.activity.ComponentActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AndroidWebGeolocationControllerTest {
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val owner = WebView(activity).apply { loadUrl("https://safe.test/page?token=secret") }
    private var visible = true
    private var request = WebViewRequest(WebViewContent.Url("https://safe.test/page"),
        security = WebViewSecurity(WebViewTrustPolicy(listOf("https://safe.test", "https://other.test")), geolocationEnabled = true))
    private var explanation: ((Boolean) -> Unit)? = null
    private val permissions = AndroidWebPermissionController(activity,
        showPermissionExplanation = { _, complete -> explanation = complete; {} }, onPermissionSettingsRequired = {})
    private val controller = AndroidWebGeolocationController({ request }, { it === owner && visible }, permissions)

    @Test
    fun `same origin uses coarse grant without retaining web permission`() {
        shadowOf(activity.application).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val outcomes = mutableListOf<Triple<String, Boolean, Boolean>>()
        controller.request(owner, "https://safe.test", GeolocationPermissions.Callback { origin, allowed, retain -> outcomes += Triple(origin, allowed, retain) })
        assertEquals(listOf(Triple("https://safe.test", true, false)), outcomes)
    }

    @Test
    fun `default disabled cross origin hidden and destroyed requests deny without asking`() {
        val outcomes = mutableListOf<Boolean>()
        val callback = GeolocationPermissions.Callback { _, allowed, retain -> assertFalse(retain); outcomes += allowed }
        request = request.copy(security = request.security.copy(geolocationEnabled = false))
        controller.request(owner, "https://safe.test", callback)
        request = request.copy(security = request.security.copy(geolocationEnabled = true))
        controller.request(owner, "https://other.test", callback)
        visible = false
        controller.request(owner, "https://safe.test", callback)
        visible = true
        controller.destroy()
        controller.request(owner, "https://safe.test", callback)
        assertEquals(listOf(false, false, false, false), outcomes)
        assertNull(explanation)
    }

    @Test
    fun `cancelled explanation cannot grant its old document`() {
        val outcomes = mutableListOf<Boolean>()
        controller.request(owner, "https://safe.test", GeolocationPermissions.Callback { _, allowed, _ -> outcomes += allowed })
        val old = requireNotNull(explanation)
        controller.cancel(owner)
        old(true)
        assertEquals(listOf(false), outcomes)
    }
}
