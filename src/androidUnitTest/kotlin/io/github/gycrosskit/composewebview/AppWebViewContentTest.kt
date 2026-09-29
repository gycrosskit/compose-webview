package io.github.gycrosskit.composewebview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppWebViewContentTest {

    @Test
    fun `equal URL content is loaded only once across recomposition`() {
        val state = AppWebViewState()
        val content = WebViewContent.Url(
            url = "https://example.test/page",
            additionalHeaders = mapOf("X-Source" to "app"),
        )

        assertTrue(state.markContentForLoad(content))
        assertFalse(state.markContentForLoad(content.copy()))
    }

    @Test
    fun `changed URL headers or HTML request a new load`() {
        val state = AppWebViewState()
        val url = WebViewContent.Url("https://example.test/page")

        assertTrue(state.markContentForLoad(url))
        assertTrue(state.markContentForLoad(url.copy(additionalHeaders = mapOf("X-Version" to "2"))))
        assertTrue(state.markContentForLoad(WebViewContent.Html("<p>content</p>")))
    }
}
