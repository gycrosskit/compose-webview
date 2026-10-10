package io.github.gycrosskit.composewebview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebVideoStateTest {
    @Test fun mirrorRequiresBoundedTripleAndFiniteNumbers() {
        assertEquals(WebVideoState(1f, 20f, false), parseWebVideoState("[1,20,false]"))
        assertEquals(WebVideoState(0f, 0f, true), parseWebVideoState("[-1,-20,true]"))
        for (value in listOf(null, "null", "{}", "[1,20,false,4]", "[1,NaN,false]", "[\"1\",20,false]", "[1,1e999,false]", "[1,20,0]", "[1,20,\"false\"]", "[" + "1".repeat(1025) + ",20,false]")) {
            assertNull(parseWebVideoState(value), value)
        }
    }

    @Test fun timeUsesTheSameFormatWithoutHostResources() {
        assertEquals("00:00", formatWebVideoTime(Float.NaN))
        assertEquals("00:00", formatWebVideoTime(Float.POSITIVE_INFINITY))
        assertEquals("00:00", formatWebVideoTime(-1f))
        assertEquals("01:49", formatWebVideoTime(109f))
        assertEquals("01:01:01", formatWebVideoTime(3661f))
    }
}
