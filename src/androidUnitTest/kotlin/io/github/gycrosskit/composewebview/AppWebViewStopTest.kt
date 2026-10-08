package io.github.gycrosskit.composewebview

import android.webkit.ValueCallback
import android.webkit.WebView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AppWebViewStopTest {
    private class Probe : WebView(RuntimeEnvironment.getApplication()) {
        val replies = mutableListOf<ValueCallback<String>>()
        var stops = 0
        var reloads = 0
        override fun evaluateJavascript(script: String, callback: ValueCallback<String>?) { if (callback != null) replies += callback }
        override fun stopLoading() { stops++ }
        override fun reload() { reloads++ }
    }

    @Test fun stopRejectsOldJavascriptAndAllowsFreshOperations() {
        val state = AppWebViewState()
        val owner = Probe()
        state.attach(owner)
        var results = 0
        var cancellations = 0
        state.cancelPendingCapabilities = { assertSame(owner, it); cancellations++ }
        state.evaluateJavascript("old()") { results++ }
        val old = owner.replies.last()
        state.stopLoading()
        old.onReceiveValue("old")
        assertEquals(0, results)
        assertEquals(1, cancellations)
        assertEquals(1, owner.stops)
        state.evaluateJavascript("new()") { results++ }
        owner.replies.last().onReceiveValue("new")
        assertEquals(1, results)
        state.reload()
        assertEquals(1, owner.reloads)
        assertTrue(state.isAttached(owner))
    }

    @Test fun capabilityCancellationCanReplaceAndLoadOwnerWithoutOldStopTail() {
        val state = AppWebViewState()
        val old = Probe()
        val next = Probe()
        state.attach(old)
        state.cancelPendingCapabilities = {
            assertSame(old, it)
            state.attach(next)
            state.onLoadStarted("https://new.test")
        }
        state.stopLoading()
        assertTrue(state.isAttached(next))
        assertEquals(0, next.stops)
        assertTrue(state.snapshot.isLoading)
    }
}
