package io.github.gycrosskit.composewebview

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import platform.CoreGraphics.CGRectMake
import platform.Foundation.*
import platform.WebKit.*
import kotlin.test.*

@OptIn(ExperimentalForeignApi::class)
class IosWebViewFrameTest {
    @Test fun registeredUIKitAppPreservesOwnerOnReentrantHistoryCancellation() {
        if (NSBundle.mainBundle.bundleIdentifier != "io.github.gycrosskit.webview.cmp-navigation-check") return
        val baseUrl = NSProcessInfo.processInfo.environment["WEBVIEW_NAVIGATION_TEST_URL"] as? String
        assertNotNull(baseUrl, "Run verification/ios-cmp-app/verify-navigation.sh for the loopback fixture")
        for (forward in listOf(false, true)) {
            val state = AppWebViewState()
            val old = WKWebView(CGRectMake(0.0, 0.0, 100.0, 100.0), WKWebViewConfiguration())
            val current = WKWebView(CGRectMake(0.0, 0.0, 100.0, 100.0), WKWebViewConfiguration())
            state.attach(old)
            for (page in listOf("/start", "/one", "/two")) {
                old.loadRequest(NSURLRequest.requestWithURL(checkNotNull(NSURL.URLWithString(baseUrl + page))))
                waitUntil("history document $page") { old.URL?.path == page && old.title == "Loaded" && !old.loading }
            }
            assertTrue(old.canGoBack)
            if (forward) {
                old.goBack()
                waitUntil("forward entry") { old.URL?.path == "/one" && old.canGoForward }
            }
            val before = old.URL?.absoluteString
            state.fullscreenExitHandler = { _, _ -> true }
            state.exitFullscreen { consumed ->
                assertFalse(consumed)
                state.attach(current)
                state.pageFinished(current)
            }
            state.fullscreenExitHandler = null
            assertFalse(if (forward) state.goForward() else state.goBack())
            assertTrue(state.isAttached(current))
            assertTrue(state.snapshot.hasVisibleContent)
            repeat(4) { drain() }
            assertEquals(before, old.URL?.absoluteString)
            old.stopLoading()
            state.detach(current)
        }
        println("PASS: CMP UIKit App reentrant cancellation blocks old owner back and forward")
    }

    @Test fun registeredUIKitAppVerifiesGenuineFrameGate() {
        if (NSBundle.mainBundle.bundleIdentifier != "io.github.gycrosskit.webview.cmp-navigation-check") {
            println("SKIP: genuine WK load requires registered UIKit App")
            return
        }
        val state = AppWebViewState()
        val events = mutableListOf<WebViewEvent>()
        val request = WebViewRequest(WebViewContent.Html("<html><body><iframe id='child'></iframe></body></html>", baseUrl = "https://safe.test/"))
        val scope = CoroutineScope(Dispatchers.Main)
        val coordinator = IosWebViewCoordinator(state, { request }, { WebViewCallbacks(onEvent = { events += it }) }, scope, 0L)
        val controller = WKUserContentController()
        controller.addScriptMessageHandler(coordinator, WEB_EVENT_HANDLER)
        val owner = WKWebView(CGRectMake(0.0, 0.0, 100.0, 100.0), WKWebViewConfiguration().apply { userContentController = controller })
        owner.navigationDelegate = coordinator
        state.attach(owner)
        coordinator.attach(owner, 0L)
        try {
            coordinator.loadWhenReady(owner, request.content)
            waitUntil("load: ${state.snapshot}") { state.snapshot.hasVisibleContent }
            var finished = false
            // iframe 直接调用自身 messageHandlers，携带当前主文档 token；真实 frameInfo 仍必须拒绝。
            owner.evaluateJavaScript("""
                var token=window.__GY_WEBVIEW_EVENT_TRANSPORT__.postMessage.toString().match(/token:'([^']+)'/)[1];
                document.getElementById('child').contentWindow.eval(
                  'window.webkit.messageHandlers.ComposeWebViewEvent.postMessage('+JSON.stringify({token:token,value:'fullscreen:1'})+')'
                );
                true;
            """.trimIndent()) { _, error -> assertNull(error); finished = true }
            waitUntil("iframe callback") { finished }
            drain()
            assertTrue(events.none { it is WebViewEvent.FullscreenChanged })
            finished = false
            owner.evaluateJavaScript("window.__GY_WEBVIEW_EVENT_TRANSPORT__.postMessage('fullscreen:1'); true;") { _, error -> assertNull(error); finished = true }
            waitUntil("main message: ${events}") { finished && events.any { it is WebViewEvent.FullscreenChanged } }
            println("PASS: CMP UIKit App genuine iframe rejected with current token, main frame accepted")
        } finally {
            coordinator.release(owner)
            state.detach(owner)
            controller.removeScriptMessageHandlerForName(WEB_EVENT_HANDLER)
            owner.navigationDelegate = null
            scope.cancel()
        }
    }

    private fun waitUntil(stage: String, condition: () -> Boolean) {
        val deadline = NSDate().timeIntervalSince1970 + 10.0
        while (!condition() && NSDate().timeIntervalSince1970 < deadline) drain()
        assertTrue(condition(), "WebKit callback timed out: $stage")
    }

    private fun drain() { NSRunLoop.currentRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.05)) }
}
