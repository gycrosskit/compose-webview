package io.github.gycrosskit.composewebview

import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WebViewErrorHelperTest {
    private fun request(mainFrame: Boolean) = object : WebResourceRequest {
        override fun getUrl(): Uri? = null
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }

    @Test
    fun `native errors normalize kinds and retain codes only for main documents`() {
        for ((code, kind) in listOf(
            WebViewClient.ERROR_TIMEOUT to WebViewErrorKind.TIMEOUT,
            WebViewClient.ERROR_FAILED_SSL_HANDSHAKE to WebViewErrorKind.SSL,
            WebViewClient.ERROR_CONNECT to WebViewErrorKind.NETWORK,
            WebViewClient.ERROR_IO to WebViewErrorKind.NETWORK,
            WebViewClient.ERROR_UNKNOWN to WebViewErrorKind.UNKNOWN,
        )) {
            val error = object : WebResourceError() {
                override fun getErrorCode() = code
                override fun getDescription() = "native detail"
            }
            assertEquals(WebViewLoadError(kind, "native detail", errorCode = code),
                WebViewErrorHelper.fromResourceError(request(true), error))
            assertNull(WebViewErrorHelper.fromResourceError(request(false), error))
        }
    }
}
