package io.github.gycrosskit.composewebview

import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebView
import androidx.activity.ComponentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AndroidWebFullscreenTest {
    @Test fun oldJavascriptReceiptCannotUpdateTheNextFullscreenAndReleaseCancelsOwner() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val receipts = mutableListOf<ValueCallback<String>>()
        val owner = object : WebView(activity) {
            override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
                resultCallback?.let(receipts::add)
            }
        }
        val visibility = mutableListOf<Boolean>()
        val controller = AndroidWebFullscreenController(activity, activity, scope) { visibility += it }
        var hidden = 0
        controller.attach(owner)
        controller.show(View(activity)) { hidden++ }
        assertTrue(receipts.isNotEmpty())
        val old = receipts.first()
        assertTrue(controller.hide())
        controller.show(View(activity)) { hidden++ }
        old.onReceiveValue("[99,100,false]")
        val current = AndroidWebFullscreenController::class.java.getDeclaredField("currentSeconds\$delegate").apply { isAccessible = true }
        val state = current.get(controller) as androidx.compose.runtime.MutableFloatState
        assertEquals(0f, state.floatValue)
        controller.release()
        assertFalse(controller.hide())
        assertEquals(2, hidden)
        assertEquals(listOf(true, false, true, false), visibility)
        scope.cancel()
        activity.finish()
    }
}
