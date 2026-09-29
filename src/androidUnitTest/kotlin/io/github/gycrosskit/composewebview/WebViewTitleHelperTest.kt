package io.github.gycrosskit.composewebview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewTitleHelperTest {
    @Test
    fun `snapshot is reused until an event publishes new state`() {
        val state = AppWebViewState()
        val initial = state.snapshot

        assertSame(initial, state.snapshot)

        state.onLoadStarted("https://example.test/page")
        val loading = state.snapshot

        assertNotSame(initial, loading)
        assertSame(loading, state.snapshot)
        assertEquals("https://example.test/page", loading.currentUrl)
        assertTrue(loading.isLoading)
    }

    @Test
    fun `keeps real document title`() {
        assertEquals(
            "健康资讯",
            usableWebTitle(
                title = " 健康资讯 ",
                pageUrl = "https://example.test/news/detail?id=1",
            ),
        )
    }

    @Test
    fun `rejects url host and path placeholder titles`() {
        val pageUrl = "https://example.test/pages/Checkin"

        assertNull(usableWebTitle(pageUrl, pageUrl))
        assertNull(usableWebTitle("example.test", pageUrl))
        assertNull(usableWebTitle("Checkin", pageUrl))
    }

    @Test
    fun `malformed page url does not discard valid title`() {
        assertEquals(
            "用户协议",
            usableWebTitle("用户协议", "not a valid url"),
        )
    }

    @Test
    fun `late progress cannot reopen completed or cancelled navigation`() {
        val state = AppWebViewState()
        state.onLoadStarted("https://example.test/first")
        state.onLoadFinished("https://example.test/first", canGoBack = false)
        state.onProgressChanged(80)
        assertFalse(state.snapshot.isLoading)
        assertEquals(100, state.snapshot.progress)
        state.onLoadStarted("https://example.test/second")
        state.onProgressChanged(30)
        assertTrue(state.snapshot.isLoading)
        assertEquals(30, state.snapshot.progress)
        state.stopLoading()
        state.onProgressChanged(40)
        assertFalse(state.snapshot.isLoading)
        assertEquals(30, state.snapshot.progress)
    }

    @Test
    fun `new navigation clears previous document title`() {
        val state = AppWebViewState()
        state.onReceivedTitle("第一页", "https://example.test/first")

        state.onLoadStarted("https://example.test/second")

        assertNull(state.snapshot.title)
    }
}
