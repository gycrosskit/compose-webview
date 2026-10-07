package io.github.gycrosskit.composewebview

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import platform.CoreGraphics.CGRectMake
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import kotlin.test.*

@OptIn(ExperimentalForeignApi::class)
class IosWebViewCommandTest {
    private fun view() = WKWebView(CGRectMake(0.0, 0.0, 100.0, 100.0), WKWebViewConfiguration())

    @Test fun fullscreenReplyReportsSuccessAndFailureWithoutInventingHistory() {
        val state = AppWebViewState()
        val owner = view()
        state.attach(owner)
        var completion: ((Boolean) -> Unit)? = null
        state.fullscreenExitHandler = { target, callback ->
            assertTrue(state.isAttached(target)); completion = callback; true
        }
        var result: Boolean? = null
        state.goBack { result = it }
        assertNull(result)
        completion!!(true)
        assertEquals(true, result)
        result = null
        state.goBack { result = it }
        completion!!(false)
        assertEquals(false, result)
        assertFalse(state.goForward())
    }

    @Test fun replacementNavigationAndReleaseCancelOnceWithoutLateSuccess() {
        val state = AppWebViewState()
        val old = view()
        state.attach(old)
        var completion: ((Boolean) -> Unit)? = null
        state.fullscreenExitHandler = { _, callback -> completion = callback; true }
        val results = mutableListOf<Boolean>()
        state.goBack { results += it }
        val current = view()
        state.attach(current)
        state.detach(old)
        assertEquals(listOf(false), results)
        completion!!(false)
        completion!!(true)
        assertEquals(listOf(false), results)
        state.fullscreenExitHandler = { _, callback -> completion = callback; true }
        state.exitFullscreen { results += it }
        state.invalidateJavascriptCallbacks()
        assertEquals(listOf(false, false), results)
        completion!!(true)
        assertEquals(listOf(false, false), results)
        state.goBack { results += it }
        state.detach(current)
        assertEquals(listOf(false, false, false), results)
        completion!!(true)
        assertEquals(listOf(false, false, false), results)
    }

    @Test fun detachCancellationCanAttachAndFinishNewOwner() {
        val state = AppWebViewState()
        val old = view()
        val current = view()
        state.attach(old)
        state.fullscreenExitHandler = { _, _ -> true }
        state.exitFullscreen { result ->
            assertFalse(result)
            state.attach(current)
            state.pageFinished(current)
        }
        state.detach(old)
        assertTrue(state.isAttached(current))
        assertTrue(state.snapshot.hasVisibleContent)
    }

    @Test fun attachCancellationPreservesReentrantOwnerSnapshot() {
        val state = AppWebViewState()
        val old = view()
        val outer = view()
        val reentrant = view()
        state.attach(old)
        state.fullscreenExitHandler = { _, _ -> true }
        state.exitFullscreen { result ->
            assertFalse(result)
            state.attach(reentrant)
            state.pageFinished(reentrant)
        }
        state.attach(outer)
        assertTrue(state.isAttached(reentrant))
        assertTrue(state.snapshot.hasVisibleContent)
    }

    @Test fun lateCoordinatorAttachDoesNotReplaceCurrentOwnerHandlers() {
        val state = AppWebViewState()
        val old = view()
        val current = view()
        val scope = CoroutineScope(Dispatchers.Main)
        val coordinator = IosWebViewCoordinator(
            state, { WebViewRequest(WebViewContent.Html("old")) }, { WebViewCallbacks() }, scope, 0L,
        )
        try {
            state.attach(old)
            state.attach(current)
            val fullscreen: (WKWebView, (Boolean) -> Unit) -> Boolean = { _, _ -> false }
            val javascript: (WKWebView) -> Boolean = { false }
            state.fullscreenExitHandler = fullscreen
            state.javascriptAllowed = javascript
            coordinator.attach(old, 0L)
            assertSame(fullscreen, state.fullscreenExitHandler)
            assertSame(javascript, state.javascriptAllowed)
        } finally {
            coordinator.release(old)
            state.detach(current)
            scope.cancel()
        }
    }
}
