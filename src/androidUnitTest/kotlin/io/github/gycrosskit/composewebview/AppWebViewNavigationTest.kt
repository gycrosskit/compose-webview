package io.github.gycrosskit.composewebview

import android.webkit.WebView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AppWebViewNavigationTest {
    private class HistoryView : WebView(RuntimeEnvironment.getApplication()) {
        var forwardAvailable = true
        var backAvailable = false
        var forwards = 0
        var backs = 0
        override fun canGoForward() = forwardAvailable
        override fun canGoBack() = backAvailable
        override fun goForward() { forwards++; forwardAvailable = false; backAvailable = true }
        override fun goBack() { backs++; forwardAvailable = true; backAvailable = false }
    }

    @Test fun forwardAndBackPublishBothDirectionsAndDetachClearsHistory() {
        val state = AppWebViewState()
        assertFalse(state.goForward())
        val view = HistoryView()
        state.attach(view)
        assertTrue(state.snapshot.canGoForward)
        assertTrue(state.goForward())
        assertEquals(1, view.forwards)
        assertTrue(state.snapshot.canGoBack)
        assertFalse(state.snapshot.canGoForward)
        assertFalse(state.goForward())
        state.goBack { assertTrue(it) }
        assertEquals(1, view.backs)
        assertTrue(state.snapshot.canGoForward)
        state.detach(view)
        assertFalse(state.snapshot.canGoForward)
        assertFalse(state.snapshot.canGoBack)
        assertFalse(state.goForward())
    }

    @Test fun backConsumesFullscreenBeforeHistoryAndExitDoesNotNavigate() {
        val state = AppWebViewState()
        val view = HistoryView().apply { backAvailable = true }
        state.attach(view)
        var fullscreen = true
        state.installBackInterceptor { fullscreen.also { fullscreen = false } }
        state.goBack { assertTrue(it) }
        assertEquals(0, view.backs)
        state.exitFullscreen { assertFalse(it) }
        assertEquals(0, view.backs)
        state.goBack { assertTrue(it) }
        assertEquals(1, view.backs)
    }

    @Test fun lateOldReleaseDoesNotClearNewHistory() {
        val state = AppWebViewState()
        val old = HistoryView().apply { forwardAvailable = false }
        val current = HistoryView()
        state.attach(old)
        state.attach(current)
        state.detach(old)
        assertTrue(state.goForward())
        assertEquals(0, old.forwards)
        assertEquals(1, current.forwards)
    }
}
