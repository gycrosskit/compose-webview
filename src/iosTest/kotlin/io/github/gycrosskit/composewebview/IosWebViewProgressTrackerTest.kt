package io.github.gycrosskit.composewebview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosWebViewProgressTrackerTest {
    @Test
    fun `progress changes while WebKit reports loading`() {
        val tracker = IosWebViewProgressTracker()

        assertEquals(10, tracker.sample(0.10, isLoading = true).progress)
        assertEquals(55, tracker.sample(0.55, isLoading = true).progress)
        assertFalse(tracker.sample(0.55, isLoading = true).changed)
    }

    @Test
    fun `estimated progress one completes after navigation has started`() {
        val tracker = IosWebViewProgressTracker()
        tracker.sample(0.20, isLoading = true)
        val sample = tracker.sample(1.0, isLoading = false)

        assertEquals(100, sample.progress)
        assertTrue(sample.completed)
    }

    @Test
    fun `stale previous progress does not finish a newly submitted navigation`() {
        val sample = IosWebViewProgressTracker().sample(1.0, isLoading = false)

        assertEquals(99, sample.progress)
        assertFalse(sample.completed)
    }

    @Test
    fun `idle fallback completes when finish callback is missing`() {
        val tracker = IosWebViewProgressTracker(
            idleSamplesAfterLoading = 2,
            idleSamplesBeforeLoading = 3,
        )
        tracker.sample(0.60, isLoading = true)

        assertFalse(tracker.sample(0.80, isLoading = false).completed)
        val completed = tracker.sample(0.80, isLoading = false)

        assertEquals(100, completed.progress)
        assertTrue(completed.completed)
    }

    @Test
    fun `idle before navigation does not finish on first sample`() {
        val tracker = IosWebViewProgressTracker(
            idleSamplesAfterLoading = 2,
            idleSamplesBeforeLoading = 3,
        )

        assertFalse(tracker.sample(0.0, isLoading = false).completed)
        assertFalse(tracker.sample(0.0, isLoading = false).completed)
        assertTrue(tracker.sample(0.0, isLoading = false).completed)
    }

    @Test
    fun `late progress cannot reopen a finished page`() {
        val finished = WebViewSnapshot(progress = 100, isLoading = false)

        assertFalse(shouldAcceptIosProgress(finished, progress = 85))
        assertTrue(shouldAcceptIosProgress(finished, progress = 100))
        assertTrue(
            shouldAcceptIosProgress(
                WebViewSnapshot(progress = 0, isLoading = true),
                progress = 15,
            ),
        )
    }
}
