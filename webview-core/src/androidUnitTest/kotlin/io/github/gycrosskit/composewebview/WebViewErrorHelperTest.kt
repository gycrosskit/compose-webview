package io.github.gycrosskit.composewebview

import android.net.http.SslError
import android.net.Uri
import android.webkit.TestWebResourceError
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
    @Test
    fun `certificate reasons use primary error and never invent a cause`() {
        for ((code, reason) in listOf(
            SslError.SSL_EXPIRED to WebViewSslReason.CERTIFICATE_EXPIRED,
            SslError.SSL_NOTYETVALID to WebViewSslReason.CERTIFICATE_NOT_YET_VALID,
            SslError.SSL_DATE_INVALID to WebViewSslReason.CERTIFICATE_DATE_INVALID,
            SslError.SSL_IDMISMATCH to WebViewSslReason.CERTIFICATE_HOST_MISMATCH,
            SslError.SSL_UNTRUSTED to WebViewSslReason.CERTIFICATE_UNTRUSTED,
            SslError.SSL_INVALID to null,
        )) {
            val error = WebViewErrorHelper.fromSslError("https://failed.test", code)
            assertEquals(code, error.errorCode)
            assertEquals(reason, error.sslReason)
        }
        assertNull(WebViewErrorHelper.fromSslError(null).errorCode)
        assertNull(WebViewErrorHelper.fromSslError(null).sslReason)
    }

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
            val error = TestWebResourceError(code, "native detail")
            assertEquals(WebViewLoadError(kind, "native detail", errorCode = code,
                sslReason = if (code == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE) WebViewSslReason.TLS_HANDSHAKE_FAILED else null),
                WebViewErrorHelper.fromResourceError(request(true), error))
            assertNull(WebViewErrorHelper.fromResourceError(request(false), error))
        }
    }
}
