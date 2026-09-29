package io.github.gycrosskit.composewebview

import kotlin.test.Test
import kotlin.test.assertEquals

class IosWebViewNavigationTest {
    @Test
    fun `released instance cancels delayed gesture result without routing or loading`() {
        assertDiscarded { released = true }
    }

    @Test
    fun `replaced instance cancels delayed gesture result without routing or loading`() {
        assertDiscarded { attached = false }
    }

    @Test
    fun `page released by route callback cannot load popup afterward`() {
        val fixture = NavigationFixture()
        val result = fixture.finish(route = {
            fixture.released = true
            WebViewNavigationDecision.ALLOW
        })

        assertEquals(WebViewNavigationDecision.BLOCK, result)
        assertEquals(1, fixture.routes)
        assertEquals(0, fixture.loads)
        assertEquals(0, fixture.blocked)
    }

    @Test
    fun `active popup routes and loads once then cancels original navigation`() {
        val fixture = NavigationFixture()

        assertEquals(WebViewNavigationDecision.BLOCK, fixture.finish())
        assertEquals(1, fixture.routes)
        assertEquals(1, fixture.loads)
        assertEquals(0, fixture.blocked)
    }

    @Test
    fun `trusted current window navigation remains allowed`() {
        val fixture = NavigationFixture()

        assertEquals(
            WebViewNavigationDecision.ALLOW,
            fixture.finish(target = WebViewNavigationTarget.CURRENT_WINDOW),
        )
        assertEquals(1, fixture.routes)
        assertEquals(0, fixture.loads)
    }

    @Test
    fun `trust rejection still cancels active navigation and clears progress`() {
        val fixture = NavigationFixture()

        assertEquals(WebViewNavigationDecision.BLOCK, fixture.finish(blockedByTrust = true))
        assertEquals(0, fixture.loads)
        assertEquals(1, fixture.blocked)
    }

    private fun assertDiscarded(invalidate: NavigationFixture.() -> Unit) {
        val fixture = NavigationFixture()
        // 对应 evaluateJavaScript 已发起但其 completion 尚未执行的窗口。
        val delayedGestureCallback = { fixture.finish() }
        fixture.invalidate()

        assertEquals(WebViewNavigationDecision.BLOCK, delayedGestureCallback())
        assertEquals(0, fixture.routes)
        assertEquals(0, fixture.loads)
        assertEquals(0, fixture.blocked)
    }
}

private class NavigationFixture {
    var released = false
    var attached = true
    var routes = 0
    var loads = 0
    var blocked = 0

    fun finish(
        target: WebViewNavigationTarget = WebViewNavigationTarget.NEW_WINDOW,
        blockedByTrust: Boolean = false,
        route: () -> WebViewNavigationDecision = { WebViewNavigationDecision.ALLOW },
    ): WebViewNavigationDecision = completeIosWebNavigation(
        navigation = WebViewNavigationRequest("https://example.test", true, true, target),
        blockedByTrust = blockedByTrust,
        isActive = { !released && attached },
        route = {
            routes++
            route()
        },
        loadPopup = { loads++ },
        onBlocked = { blocked++ },
    )
}
