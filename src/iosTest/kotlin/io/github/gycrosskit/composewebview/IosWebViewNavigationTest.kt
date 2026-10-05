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

    @Test fun `only accepted current main documents revoke old tokens before native allow`() {
        var tokens = 0
        var active = true
        fun decide(url: String, main: Boolean = true, blocked: Boolean = false) = completeIosWebNavigation(
            WebViewNavigationRequest(url, main, false), blocked, { active },
            { WebViewNavigationDecision.ALLOW }, {}, {}, { tokens++ },
        )
        // 首次内部 HTML、同源跳转、重定向和 reload 都经过同一提交点。
        for (url in listOf("about:blank", "https://safe.test/page", "https://safe.test/redirect", "https://safe.test/redirect")) {
            val before = tokens
            assertEquals(WebViewNavigationDecision.ALLOW, decide(url))
            assertEquals(before + 1, tokens)
        }
        assertEquals(WebViewNavigationDecision.ALLOW, decide("https://safe.test/frame", main = false))
        assertEquals(4, tokens)
        assertEquals(WebViewNavigationDecision.BLOCK, decide("https://foreign.test", blocked = true))
        active = false
        assertEquals(WebViewNavigationDecision.BLOCK, decide("https://safe.test/late"))
        assertEquals(4, tokens)
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
