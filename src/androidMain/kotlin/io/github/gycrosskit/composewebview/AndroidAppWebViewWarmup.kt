package io.github.gycrosskit.composewebview

import android.content.Context
import android.os.SystemClock
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewOutcomeReceiver
import androidx.webkit.WebViewStartUpConfig
import androidx.webkit.WebViewStartUpResult
import androidx.webkit.WebViewStartupException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 使用 Jetpack WebKit 官方异步入口执行一次进程级 WebView 预热。 */
class AndroidAppWebViewWarmup(
    context: Context,
) : AppWebViewWarmup {
    private val applicationContext = context.applicationContext
    private val requested = AtomicBoolean(false)

    override fun warmUp() {
        if (!requested.compareAndSet(false, true)) return
        val startedAtNanos = SystemClock.elapsedRealtimeNanos()
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "app-webview-startup").apply { isDaemon = true }
        }
        val config = WebViewStartUpConfig.Builder(executor).build()
        AppWebViewRuntime.log(AppWebViewLogLevel.INFO, "startup-request platform=android")
        runCatching {
            WebViewCompat.startUpWebView(
                applicationContext,
                config,
                object : WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException> {
                    override fun onResult(result: WebViewStartUpResult) {
                        val durationMillis = elapsedMillis(startedAtNanos)
                        AppWebViewRuntime.log(
                            AppWebViewLogLevel.INFO,
                            "startup-finish platform=android durationMs=$durationMillis " +
                                "uiBlockingLocations=${result.getUiThreadBlockingStartUpLocations().orEmpty().size} " +
                                "backgroundBlockingLocations=${result.getNonUiThreadBlockingStartUpLocations().orEmpty().size}",
                        )
                        executor.shutdown()
                    }

                    override fun onError(error: WebViewStartupException) {
                        logFailure(startedAtNanos, error)
                        executor.shutdown()
                    }
                },
            )
        }.onFailure { error ->
            logFailure(startedAtNanos, error)
            executor.shutdown()
        }
    }

    private fun logFailure(startedAtNanos: Long, error: Throwable) {
        AppWebViewRuntime.log(
            AppWebViewLogLevel.WARNING,
            "startup-failed platform=android durationMs=${elapsedMillis(startedAtNanos)}",
            error,
        )
    }

    private fun elapsedMillis(startedAtNanos: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startedAtNanos) / NANOS_PER_MILLI

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
