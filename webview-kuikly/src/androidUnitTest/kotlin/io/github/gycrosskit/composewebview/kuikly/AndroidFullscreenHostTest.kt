package io.github.gycrosskit.composewebview.kuikly

import android.content.Context
import android.content.pm.ActivityInfo
import android.view.MotionEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import io.github.gycrosskit.composewebview.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AndroidFullscreenHostTest {
    @Test fun controlsShareVideoContainerAndOldActionsCannotExitNextFullscreen() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LOW_PROFILE
        val owner = WebView(activity)
        var actions: AndroidWebFullscreenActions? = null
        var controls: FrameLayout? = null
        var updates = 0
        var hidden = 0
        val host = AndroidWebFullscreenHost(activity, ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, { context, next ->
            actions = next
            object : AndroidWebFullscreenControls {
                override val view = FrameLayout(context).also { controls = it }
                override fun update(state: AndroidWebFullscreenState) { updates++ }
            }
        }) {}
        val video = android.widget.VideoView(activity)
        host.show(owner, video, { hidden++ }) { true }
        val container = video.parent as FrameLayout
        val oldControls = controls!!
        val oldActions = actions!!
        assertSame(container, oldControls.parent)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(3500))
        assertEquals(View.GONE, oldControls.visibility)
        assertTrue(updates >= 7)
        val touch = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        container.dispatchTouchEvent(touch); touch.recycle()
        assertEquals(View.VISIBLE, oldControls.visibility)
        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(1, hidden)
        assertNull(video.parent); assertNull(oldControls.parent)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
        assertEquals(View.SYSTEM_UI_FLAG_LOW_PROFILE, activity.window.decorView.systemUiVisibility)
        host.show(owner, View(activity), { hidden++ }) { true }
        oldActions.exit(); oldActions.togglePlayback(); oldActions.revealControls()
        assertEquals(1, hidden)
        assertTrue(host.hide()); assertFalse(host.hide())
        assertEquals(2, hidden)
        activity.finish()
    }

    @Test fun mirrorRejectsOversizedOrNonTriplePageResults() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var raw = "[1,20,false]"
        val owner = object : WebView(activity) {
            override fun evaluateJavascript(script: String, resultCallback: android.webkit.ValueCallback<String>?) {
                resultCallback?.onReceiveValue(raw)
            }
        }
        val states = mutableListOf<AndroidWebFullscreenState>()
        val host = AndroidWebFullscreenHost(activity, controlsFactory = { context, _ ->
            object : AndroidWebFullscreenControls {
                override val view = View(context)
                override fun update(state: AndroidWebFullscreenState) { states += state }
            }
        }, onVisibilityChanged = {})
        host.show(owner, View(activity), {}) { true }
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(1f, states.last().currentSeconds)
        for (value in listOf("[1,20,false,4]", "[\"1\",20,false]", "[1,20,0]", "[1,1e999,false]", "[" + "1".repeat(1025) + ",20,false]")) {
            val count = states.size; raw = value
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            assertEquals(count, states.size)
        }
        host.hide(); activity.finish()
    }

    @Test fun controlsUpdateRevocationCannotLeaveContainerOrToggleOldVideo() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val video = View(activity)
        var allowed = true; var hidden = 0; var javascript = 0
        val owner = object : WebView(activity) {
            override fun evaluateJavascript(script: String, resultCallback: android.webkit.ValueCallback<String>?) { javascript++ }
        }
        var actions: AndroidWebFullscreenActions? = null
        var revokeDuringUpdate = true
        val host = AndroidWebFullscreenHost(activity, controlsFactory = { context, next ->
            actions = next
            object : AndroidWebFullscreenControls {
                override val view = View(context)
                override fun update(state: AndroidWebFullscreenState) { if (revokeDuringUpdate) allowed = false }
            }
        }, onVisibilityChanged = {})
        host.show(owner, video, { hidden++ }) { allowed }
        assertEquals(1, hidden); assertNull(video.parent)
        allowed = true; revokeDuringUpdate = false
        host.show(owner, video, { hidden++ }) { allowed }
        revokeDuringUpdate = true
        val before = javascript
        actions!!.togglePlayback()
        assertEquals(before, javascript)
        assertEquals(2, hidden); assertNull(video.parent)
        activity.finish()
    }

    @Test fun nativeHiddenReentryDoesNotPublishOldFalseOverNewFullscreen() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val owner = WebView(activity)
        val visibility = mutableListOf<Boolean>()
        val host = AndroidWebFullscreenHost(activity, onVisibilityChanged = { visibility += it })
        host.show(owner, View(activity), { host.show(owner, View(activity), {}) { true } }) { true }
        host.hide()
        assertEquals(listOf(true, true), visibility)
        assertTrue(host.hide())
        assertEquals(listOf(true, true, false), visibility)
        activity.finish()
    }

    @Test fun defaultPreservesDirectionAndRevocationCleansControlsOnce() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val owner = WebView(activity)
        val video = View(activity)
        var allowed = true; var hidden = 0
        val host = AndroidWebFullscreenHost(activity, onVisibilityChanged = {})
        host.show(owner, video, { hidden++ }) { allowed }
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
        host.show(owner, View(activity), { hidden++ }) { allowed }
        assertEquals(1, hidden)
        allowed = false
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(2, hidden); assertNull(video.parent)
        activity.finish()
    }

    @Test fun policyUpdateKeepsOwnerAndChromeCallbacksUseCurrentDocument() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val native = GYWebViewNative(activity)
        val events = mutableListOf<Any?>()
        native.setProp("onEvent", { value: Any? -> events.add(value); Unit })
        val original = WebViewRequest(WebViewContent.Url("https://safe.example"))
        native.setProp("request", WebViewWire.encodeRequest(original))
        val field = GYWebViewNative::class.java.getDeclaredField("webView").apply { isAccessible = true }
        val owner = field.get(native) as WebView
        val client = shadowOf(owner).webViewClient
        val chrome = shadowOf(owner).webChromeClient
        val next = original.copy(navigationPolicy = WebViewNavigationPolicy(blockedRules = listOf(WebViewUrlRule.HostSuffix("jd.com", "https", false, true))))
        native.setProp("request", WebViewWire.encodeRequest(next))
        assertSame(owner, field.get(native))
        assertSame(client, shadowOf(owner).webViewClient)
        assertSame(chrome, shadowOf(owner).webChromeClient)
        assertTrue(client.shouldOverrideUrlLoading(owner, "https://shop.jd.com/item"))
        assertFalse(client.shouldOverrideUrlLoading(owner, "https://safe.example/next"))
        chrome.onProgressChanged(owner, 50); chrome.onReceivedTitle(owner, "still-current")
        assertTrue(events.any { (it as? Map<*, *>)?.get("type") == "progressChanged" })
        assertTrue(events.any { (it as? Map<*, *>)?.get("type") == "titleChanged" })
        assertTrue(WebViewDiagnostics.isActive(owner))
        assertEquals("https://safe.example", shadowOf(owner).lastLoadedUrl)
        native.onDestroy(); activity.finish()
    }
}
