package io.github.gycrosskit.composewebview.kuikly

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.WebView
import io.github.gycrosskit.composewebview.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AndroidNativeLifecycleTest {
    @Test fun closedPopupNeverRoutesLateClientCallbacks() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val parent = WebView(activity)
        activity.setContentView(parent)
        var routes = 0
        val router = AndroidPopupRouter({ WebViewRequest(WebViewContent.Url("https://safe.test")) }) {
            routes++; WebViewNavigationDecision.ALLOW
        }
        val transport = parent.WebViewTransport()
        val message = Message.obtain(Handler(Looper.getMainLooper())).apply { obj = transport }
        assertTrue(router.createWindow(parent, true, message))
        val popup = transport.webView
        val client = shadowOf(popup).webViewClient
        router.release()
        client.onPageStarted(popup, "https://safe.test/late", null)
        assertEquals(0, routes)
        assertNull(shadowOf(parent).lastLoadedUrl)
        activity.finish()
    }

    @Test fun chromeAndErrorEventsRequireCurrentUnreleasedOwner() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val old = WebView(activity); val next = WebView(activity)
        WebViewDiagnostics.created(old); WebViewDiagnostics.created(next)
        var owner: WebView = old
        var progress = 0; var title = 0; var failed = 0
        val chrome = AppWebChromeClient(object : AppWebChromeClient.Listener {
            override fun onProgressChanged(value: Int) { progress++ }
            override fun onReceivedTitle(value: String?, url: String?) { title++ }
        }) { it === old && owner === old }
        val client = AppWebViewClient(object : AppWebViewClient.Listener {
            override fun onPageLoadFailed(error: WebViewLoadError) { failed++ }
        }) { it === old && owner === old }
        client.onPageStarted(old, "https://safe.test", null)
        chrome.onProgressChanged(old, 10); chrome.onReceivedTitle(old, "valid")
        assertEquals(1, progress); assertEquals(1, title)
        owner = next
        chrome.onProgressChanged(old, 20); chrome.onReceivedTitle(old, "late")
        client.onRenderProcessGone(old, object : android.webkit.RenderProcessGoneDetail() {
            override fun didCrash() = false
            override fun rendererPriorityAtExit() = 0
        })
        client.reportFailure(WebViewLoadError(WebViewErrorKind.NETWORK))
        assertEquals(1, progress); assertEquals(1, title); assertEquals(0, failed)
        owner = old; WebViewDiagnostics.markReleased(old)
        chrome.onProgressChanged(old, 30); chrome.onReceivedTitle(old, "released")
        assertEquals(1, progress); assertEquals(1, title)
        activity.finish()
    }
}
