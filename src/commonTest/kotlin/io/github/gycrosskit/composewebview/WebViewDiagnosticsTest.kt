package io.github.gycrosskit.composewebview

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebViewDiagnosticsTest {
    @AfterTest
    fun resetLogSink() {
        AppWebViewRuntime.installLogSink { _, _, _ -> }
    }

    @Test
    fun `media stop log distinguishes request from native completion`() {
        val messages = mutableListOf<String>()
        AppWebViewRuntime.installLogSink { _, message, _ -> messages += message }
        val trace = WebViewPerformanceTrace("ios", "media-test", nowMillis = { 100L })
        trace.mediaSuspension(true, "release", completed = false)
        trace.mediaSuspension(true, "release", completed = true)
        assertEquals(2, messages.size)
        assertTrue(messages[0].contains("completed=false"))
        assertTrue(messages[1].contains("completed=true"))
        assertTrue(messages.all { "suspended=true reason=release" in it && "id=media-test" in it })
    }

    @Test
    fun `log description keeps complete url and original length`() {
        val url = "https://example.test/play?token=secret&video=7#chapter"

        assertEquals(
            "$url rawLength=${url.length}",
            describeWebViewUrl(url),
        )
    }

    @Test
    fun `html content keeps complete base url`() {
        assertEquals(
            "html(length=14, base=https://example.test/page?kk=secret#section)",
            describeWebViewContent(
                WebViewContent.Html(
                    html = "<p>content</p>",
                    baseUrl = "https://example.test/page?kk=secret#section",
                ),
            ),
        )
    }

    @Test
    fun `performance trace records deterministic durations with complete query values`() {
        val messages = mutableListOf<String>()
        val times = ArrayDeque(listOf(100L, 120L, 135L, 180L, 200L))
        AppWebViewRuntime.installLogSink { _, message, _ -> messages += message }
        val trace = WebViewPerformanceTrace(
            platform = "test",
            instanceId = "1",
            nowMillis = { times.removeFirst() },
        )

        trace.created()
        trace.load(WebViewContent.Url("https://example.test/page?token=secret"))
        trace.pageStarted("https://example.test/page?token=secret")
        trace.pageFinished("https://example.test/page?token=secret")
        trace.released("https://example.test/page?token=secret")

        assertTrue(messages.any { "createdToLoadMs=20" in it })
        assertTrue(messages.any { "loadToStartMs=15" in it })
        assertTrue(messages.any { "loadToFinishMs=60" in it && "startToFinishMs=45" in it })
        assertTrue(messages.any { "lifetimeMs=100" in it })
        assertTrue(messages.count { "token=secret" in it } == 4)
    }

    @Test
    fun `performance trace records entry first visible and web metrics`() {
        val messages = mutableListOf<String>()
        val times = ArrayDeque(listOf(100L, 120L, 130L, 150L, 180L))
        AppWebViewRuntime.installLogSink { _, message, _ -> messages += message }
        val trace = WebViewPerformanceTrace(
            platform = "test",
            instanceId = "2",
            pageEnteredAtMillis = 90L,
            nowMillis = { times.removeFirst() },
        )

        trace.created(7L)
        trace.load(WebViewContent.Url("https://example.test/page"))
        trace.pageStarted("https://example.test/page")
        trace.firstContentVisible("https://example.test/page")
        trace.performanceMetric(WebViewPerformanceMetric.FIRST_CONTENTFUL_PAINT, 42L)
        trace.pageFinished("https://example.test/page")

        assertTrue(messages.any { "entryToCreateMs=10" in it && "constructorMs=7" in it })
        assertTrue(messages.any { "entryToVisibleMs=60" in it && "loadToVisibleMs=30" in it })
        assertTrue(messages.any { "name=FIRST_CONTENTFUL_PAINT" in it && "navigationDurationMs=42" in it })
    }
}
