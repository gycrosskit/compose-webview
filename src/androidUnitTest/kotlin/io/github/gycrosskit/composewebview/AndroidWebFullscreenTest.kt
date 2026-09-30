package io.github.gycrosskit.composewebview

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidWebFullscreenTest {
    @Test
    fun `formats fullscreen video duration without app resources`() {
        assertEquals("00:00", formatVideoTime(Float.NaN))
        assertEquals("01:49", formatVideoTime(109f))
        assertEquals("01:01:01", formatVideoTime(3_661f))
    }
}
