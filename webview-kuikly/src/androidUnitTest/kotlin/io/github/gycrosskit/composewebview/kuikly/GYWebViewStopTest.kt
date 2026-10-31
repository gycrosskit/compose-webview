package io.github.gycrosskit.composewebview.kuikly

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.*
import androidx.activity.ComponentActivity
import io.github.gycrosskit.composewebview.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWebView

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [StopWebViewShadow::class, LegacyWebViewFeatures::class])
class GYWebViewStopTest {
    private val request = WebViewRequest(
        WebViewContent.Url("https://safe.test/page"),
        settings = WebViewSettings(javaScriptEnabled = true),
        security = WebViewSecurity(WebViewTrustPolicy(listOf("https://safe.test")), fileChooserEnabled = true, mediaCaptureEnabled = true),
    )
    @Test fun systemPickerPauseKeepsPendingRequestButPageLeaveCancelsIt() {
        for (leave in listOf(false, true)) {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            val activity = controller.get()
            val native = GYWebViewNative(activity)
            activity.setContentView(native)
            native.setProp("request", WebViewWire.encodeRequest(request))
            val owner = native.getChildAt(0) as WebView
            var replies = 0
            shadowOf(owner).webChromeClient.onShowFileChooser(owner, { result -> assertNull(result); replies++ }, StopFileParams())
            val launch = shadowOf(activity).nextStartedActivityForResult
            assertNotNull(launch)
            native.setProp("visible", false)
            if (!leave) controller.pause()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(if (leave) 1 else 0, replies)
            if (!leave) {
                controller.resume()
                native.setProp("visible", true)
            }
            activity.activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_CANCELED, null)
            assertEquals(1, replies)
            native.onDestroy()
        }
    }
    @Test fun independentNavigationKeepsTrustedFileAndMediaCapabilities() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        shadowOf(activity.application).grantPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val native = GYWebViewNative(activity)
        val independent = request.copy(navigationPolicy = WebViewNavigationPolicy(
            navigationOrigins = WebViewNavigationOrigins(exactOrigins = setOf("https://safe.test", "https://other.test"))))
        native.setProp("request", WebViewWire.encodeRequest(independent))
        val owner = native.getChildAt(0) as WebView
        val chrome = shadowOf(owner).webChromeClient
        var results = 0
        chrome.onShowFileChooser(owner, { result -> assertNull(result); results++ }, StopFileParams())
        val launch = shadowOf(activity).nextStartedActivityForResult
        assertNotNull(launch)
        assertEquals(0, results)
        val trusted = StopPermission(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE, PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        chrome.onPermissionRequest(trusted)
        assertEquals(1, trusted.grants)
        native.call("stopLoading", null, null)
        assertEquals(1, results)
        activity.activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_CANCELED, null)
        owner.loadUrl("https://other.test/page")
        var denied = 0
        chrome.onShowFileChooser(owner, { result -> assertNull(result); denied++ }, StopFileParams())
        val untrusted = StopPermission()
        chrome.onPermissionRequest(untrusted)
        assertEquals(1, denied)
        assertEquals(1, untrusted.denials)
        native.onDestroy()
    }
    @Test fun stopDiscardsOldJavascriptButKeepsTheOwnerUsable() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val native = GYWebViewNative(activity)
        native.setProp("request", WebViewWire.encodeRequest(request))
        val owner = native.getChildAt(0) as WebView
        val shadow = Shadow.extract<StopWebViewShadow>(owner)
        var replies = 0
        native.call("evaluateJavascript", "{\"script\":\"old()\"}") { replies++ }
        val old = shadow.callbacks.last()
        native.call("stopLoading", null, null)
        old.onReceiveValue("old")
        assertEquals(0, replies)
        assertSame(owner, native.getChildAt(0))
        native.call("evaluateJavascript", "{\"script\":\"new()\"}") { replies++ }
        shadow.callbacks.last().onReceiveValue("new")
        assertEquals(1, replies)
        native.call("reload", null, null)
        assertSame(owner, native.getChildAt(0))
        native.onDestroy()
    }
    @Test fun fileCancellationCannotCancelReentrantMediaRequestOnSameOwner() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val native = GYWebViewNative(activity)
        native.setProp("request", WebViewWire.encodeRequest(request))
        val owner = native.getChildAt(0) as WebView
        val chrome = shadowOf(owner).webChromeClient
        val old = StopPermission()
        val fresh = StopPermission()
        chrome.onPermissionRequest(old)
        var files = 0
        chrome.onShowFileChooser(owner, { result ->
            assertNull(result); files++
            chrome.onPermissionRequest(fresh)
        }, StopFileParams())
        native.call("stopLoading", null, null)
        assertEquals(1, files)
        assertEquals(1, old.denials)
        assertEquals(0, fresh.denials)
        chrome.onPermissionRequestCanceled(old)
        assertEquals(0, fresh.denials)
        native.call("stopLoading", null, null)
        assertEquals(1, fresh.denials)
        assertEquals(1, files)
        native.onDestroy()
    }

    @Test fun stopSettlesFileAndMediaOnceAndDoesNotStopReentrantOwner() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val native = GYWebViewNative(activity)
        native.setProp("request", WebViewWire.encodeRequest(request))
        val owner = native.getChildAt(0) as WebView
        val chrome = shadowOf(owner).webChromeClient
        val permission = StopPermission()
        chrome.onPermissionRequest(permission)
        var fileResults = 0
        chrome.onShowFileChooser(owner, { result ->
            assertNull(result); fileResults++
            native.setProp("request", WebViewWire.encodeRequest(request.copy(content = WebViewContent.Url("https://safe.test/new"))))
        }, StopFileParams())
        val launch = shadowOf(activity).nextStartedActivityForResult
        native.call("stopLoading", null, null)
        assertEquals(1, permission.denials)
        assertEquals(1, fileResults)
        val fresh = native.getChildAt(0) as WebView
        assertNotSame(owner, fresh)
        assertEquals(0, Shadow.extract<StopWebViewShadow>(fresh).stops)
        activity.activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_OK, Intent().setData(Uri.parse("content://old/file")))
        chrome.onPermissionRequestCanceled(permission)
        assertEquals(1, fileResults)
        assertEquals(0, permission.grants)
        shadowOf(activity.application).grantPermissions(Manifest.permission.CAMERA)
        val next = StopPermission()
        shadowOf(fresh).webChromeClient.onPermissionRequest(next)
        assertEquals(1, next.grants)
        var freshFiles = 0
        shadowOf(fresh).webChromeClient.onShowFileChooser(fresh, { result -> assertNull(result); freshFiles++ }, StopFileParams())
        assertEquals(0, freshFiles)
        native.onDestroy()
        assertEquals(1, freshFiles)
    }
}
@Implements(WebView::class)
class StopWebViewShadow : ShadowWebView() {
    val callbacks = mutableListOf<ValueCallback<String>>()
    var stops = 0
    @Implementation override fun evaluateJavascript(script: String, callback: ValueCallback<String>?) { if (callback != null) callbacks += callback }
    @Implementation fun stopLoading() { stops++ }
}
private class StopPermission(private val resources: Array<String> = arrayOf(RESOURCE_VIDEO_CAPTURE)) : PermissionRequest() {
    var grants = 0; var denials = 0
    override fun getOrigin() = Uri.parse("https://safe.test")
    override fun getResources() = resources
    override fun grant(resources: Array<out String>) { grants++ }
    override fun deny() { denials++ }
}
private class StopFileParams : WebChromeClient.FileChooserParams() {
    override fun getMode() = MODE_OPEN
    override fun getAcceptTypes() = arrayOf("application/pdf")
    override fun isCaptureEnabled() = false
    override fun getTitle(): CharSequence? = null
    override fun getFilenameHint(): String? = null
    override fun createIntent() = Intent(Intent.ACTION_GET_CONTENT)
}
