package io.github.gycrosskit.composewebview

import org.junit.Assert.assertEquals
import org.junit.Test

class AppWebBridgeTest {
    @Test
    fun `forwards handler name and raw data without business parsing`() {
        var received: Pair<String, String>? = null
        val onMessage: (String, String) -> Unit = { handlerName, data -> received = handlerName to data }

        dispatchAppWebBridgeMessage("videoEnded\u001F{\"code\":0}", onMessage)

        assertEquals("videoEnded" to "{\"code\":0}", received)
        received = null
        dispatchAppWebBridgeMessage("missing separator", onMessage)
        assertEquals(null, received)
    }

}
