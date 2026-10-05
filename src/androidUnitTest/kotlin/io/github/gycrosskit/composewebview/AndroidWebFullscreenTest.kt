package io.github.gycrosskit.composewebview

import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AndroidWebFullscreenTest {
    @Test fun mirrorRequiresBoundedTripleAndFiniteNumbers() {
        val parser = Class.forName("io.github.gycrosskit.composewebview.AndroidWebFullscreenControllerKt")
            .getDeclaredMethod("parseVideoState", String::class.java).apply { isAccessible = true }
        kotlin.test.assertNotNull(parser.invoke(null, "[1,20,false]"))
        for (value in listOf(null, "[1,20,false,4]", "[1,NaN,false]", "[\"1\",20,false]", "[1,1e999,false]", "[1,20,0]", "[" + "1".repeat(1025) + ",20,false]")) kotlin.test.assertNull(parser.invoke(null, value))
    }

    @Test
    fun `formats fullscreen video duration without app resources`() {
        assertEquals("00:00", formatVideoTime(Float.NaN))
        assertEquals("01:49", formatVideoTime(109f))
        assertEquals("01:01:01", formatVideoTime(3_661f))
    }
}
