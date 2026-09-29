package io.github.gycrosskit.composewebview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppWebViewConfigTest {

    @Test
    fun `all neutral policies retain Android settings values`() {
        assertEquals(
            listOf(1, 2, 0),
            WebViewMixedContentPolicy.entries.map { WebViewSettings(mixedContentPolicy = it).androidMixedContentMode },
        )
        assertEquals(
            listOf(-1, 2, 1, 3),
            WebViewCachePolicy.entries.map { WebViewSettings(cachePolicy = it).androidCacheMode },
        )
    }

    @Test
    fun `mixed content is blocked by default`() {
        assertEquals(
            android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW,
            WebViewSettings().androidMixedContentMode,
        )
    }

    @Test
    fun `base page rendering configuration loads images and uses default cache`() {
        val config = WebViewSettings()

        assertEquals(android.webkit.WebSettings.LOAD_DEFAULT, config.androidCacheMode)
        assertFalse(config.acceptsThirdPartyCookies)
        assertTrue(config.domStorageEnabled)
        assertTrue(config.loadsImagesAutomatically)
        assertFalse(config.blockNetworkImage)
        assertTrue(config.useWideViewPort)
        assertTrue(config.loadWithOverviewMode)
    }

    @Test
    fun `android mapping keeps third party cookies disabled unless page opts in`() {
        assertFalse(WebViewSettings().acceptsThirdPartyCookies)
        assertTrue(
            WebViewSettings(acceptsThirdPartyCookies = true)
                .acceptsThirdPartyCookies,
        )
    }

    @Test
    fun `text zoom follows large system font scale`() {
        val config = WebViewSettings()

        assertEquals(130, calculateWebViewTextZoom(fontScale = 1.3f, config = config))
        assertEquals(200, calculateWebViewTextZoom(fontScale = 2.5f, config = config))
    }

    @Test
    fun `text zoom remains standard when system scale is disabled`() {
        val config = WebViewSettings(followSystemFontScale = false)

        assertEquals(100, calculateWebViewTextZoom(fontScale = 1.8f, config = config))
    }

    @Test
    fun `text zoom does not shrink below accessible default`() {
        val config = WebViewSettings()

        assertEquals(100, calculateWebViewTextZoom(fontScale = 0.85f, config = config))
    }
}
