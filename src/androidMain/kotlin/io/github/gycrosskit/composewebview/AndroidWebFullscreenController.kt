package io.github.gycrosskit.composewebview

import android.content.pm.ActivityInfo
import android.graphics.Color as AndroidColor
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Android H5 全屏内核。它只控制 Chromium 的媒体 View，不依赖宿主业务主题、资源或播放器模型。
 *
 * Overlay 虽然挂在 Activity decorView，组合、轮询和 Window 状态仍随当前 AppWebView 释放，避免页面退出后
 * 遗留横屏、沉浸栏或持有旧 WebView。
 */
internal class AndroidWebFullscreenController(
    private val activity: ComponentActivity,
    private val lifecycleOwner: LifecycleOwner,
    private val scope: CoroutineScope,
    private val onVisibilityChanged: (Boolean) -> Unit,
) {
    private var container: FrameLayout? = null
    private var controlsView: ComposeView? = null
    private var callback: WebChromeClient.CustomViewCallback? = null
    private var webView: WebView? = null
    private var nativeVideoView: VideoView? = null
    private var pollingJob: Job? = null
    private var hideControlsJob: Job? = null
    private var controlsVisible by mutableStateOf(true)
    private var playing by mutableStateOf(true)
    private var currentSeconds by mutableFloatStateOf(0f)
    private var durationSeconds by mutableFloatStateOf(0f)
    private var fullscreenGeneration = 0L
    private var previousRequestedOrientation: Int? = null
    private var previousStatusVisible = true
    private var previousNavigationVisible = true
    private var previousBarsBehavior = 0
    private var previousSystemUi = 0

    fun attach(target: WebView) {
        webView = target
    }

    fun detach(target: WebView) {
        if (webView === target) webView = null
    }

    fun show(view: View, customViewCallback: WebChromeClient.CustomViewCallback) {
        if (container != null) {
            customViewCallback.onCustomViewHidden()
            return
        }
        fullscreenGeneration++
        previousRequestedOrientation = activity.requestedOrientation
        val decor = activity.window.decorView
        val insets = ViewCompat.getRootWindowInsets(decor)
        previousStatusVisible = insets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
        previousNavigationVisible = insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true
        previousSystemUi = decor.systemUiVisibility
        previousBarsBehavior = WindowInsetsControllerCompat(activity.window, decor).systemBarsBehavior
        callback = customViewCallback
        nativeVideoView = findVideoView(view)
        controlsView = ComposeView(activity).apply {
            elevation = CONTROLS_ELEVATION
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                FullscreenVideoControls(
                    visible = controlsVisible,
                    playing = playing,
                    currentSeconds = currentSeconds,
                    durationSeconds = durationSeconds,
                    onSurfaceTap = {
                        if (controlsVisible) concealControls() else revealControls()
                    },
                    onNavigateBack = ::hide,
                    onTogglePlayback = ::togglePlayback,
                )
            }
        }
        container = FrameLayout(activity).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setViewTreeOnBackPressedDispatcherOwner(activity)
            setBackgroundColor(AndroidColor.BLACK)
            (view.parent as? ViewGroup)?.removeView(view)
            addView(view, matchParentLayoutParams())
            controlsView?.let {
                addView(it, matchParentLayoutParams())
                it.bringToFront()
            }
        }.also { overlay ->
            activity.addContentView(overlay, matchParentLayoutParams())
        }
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        revealControls()
        startProgressPolling()
        onVisibilityChanged(true)
    }

    fun hide(): Boolean {
        val overlay = container ?: return false
        fullscreenGeneration++
        val hiddenGeneration = fullscreenGeneration
        pollingJob?.cancel()
        pollingJob = null
        hideControlsJob?.cancel()
        hideControlsJob = null
        controlsView?.disposeComposition()
        controlsView = null
        nativeVideoView = null
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        container = null
        val hiddenCallback = callback
        callback = null
        restoreWindow()
        previousRequestedOrientation = null
        hiddenCallback?.onCustomViewHidden()
        if (fullscreenGeneration == hiddenGeneration && container == null) onVisibilityChanged(false)
        return true
    }

    fun release() {
        hide()
        webView = null
    }

    private fun restoreWindow() {
        if (activity.isDestroyed) return
        activity.requestedOrientation = previousRequestedOrientation
            ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            systemBarsBehavior = previousBarsBehavior
            if (previousStatusVisible) show(WindowInsetsCompat.Type.statusBars()) else hide(WindowInsetsCompat.Type.statusBars())
            if (previousNavigationVisible) show(WindowInsetsCompat.Type.navigationBars()) else hide(WindowInsetsCompat.Type.navigationBars())
        }
        activity.window.decorView.systemUiVisibility = previousSystemUi
        ViewCompat.requestApplyInsets(activity.window.decorView)
        activity.window.decorView.requestLayout()
    }

    private fun revealControls() {
        controlsVisible = true
        hideControlsJob?.cancel()
        hideControlsJob = scope.launch {
            delay(CONTROLS_HIDE_DELAY_MILLIS)
            controlsVisible = false
        }
    }

    private fun concealControls() {
        hideControlsJob?.cancel()
        controlsVisible = false
    }

    private fun togglePlayback() {
        revealControls()
        nativeVideoView?.let { video ->
            if (video.isPlaying) video.pause() else video.start()
            playing = video.isPlaying
            return
        }
        val owner = webView
        val generation = fullscreenGeneration
        owner?.evaluateJavascript(WEB_VIDEO_TOGGLE_SCRIPT) { result ->
            if (container == null || owner !== webView || generation != fullscreenGeneration) return@evaluateJavascript
            parseWebVideoState(result)?.let { playing = !it.paused }
        }
    }

    private fun startProgressPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive && container != null) {
                nativeVideoView?.let { video ->
                    currentSeconds = video.currentPosition / MILLIS_PER_SECOND
                    durationSeconds = video.duration.coerceAtLeast(0) / MILLIS_PER_SECOND
                    playing = video.isPlaying
                }
                if (nativeVideoView == null) {
                    val owner = webView
                    val generation = fullscreenGeneration
                    owner?.evaluateJavascript(WEB_VIDEO_STATE_SCRIPT) { raw ->
                        if (container == null || owner !== webView || generation != fullscreenGeneration) return@evaluateJavascript
                        parseWebVideoState(raw)?.let { state ->
                            currentSeconds = state.currentSeconds
                            durationSeconds = state.durationSeconds
                            playing = !state.paused
                        }
                    }
                }
                delay(PROGRESS_INTERVAL_MILLIS)
            }
        }
    }

    private fun matchParentLayoutParams() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    private fun findVideoView(root: View): VideoView? {
        if (root is VideoView) return root
        if (root !is ViewGroup) return null
        repeat(root.childCount) { index ->
            findVideoView(root.getChildAt(index))?.let { return it }
        }
        return null
    }

    private companion object {
        const val PROGRESS_INTERVAL_MILLIS = 500L
        const val CONTROLS_HIDE_DELAY_MILLIS = 3_500L
        const val CONTROLS_ELEVATION = 1_000f
        const val MILLIS_PER_SECOND = 1_000f
    }
}

@Composable
private fun FullscreenVideoControls(
    visible: Boolean,
    playing: Boolean,
    currentSeconds: Float,
    durationSeconds: Float,
    onSurfaceTap: () -> Unit,
    onNavigateBack: () -> Unit,
    onTogglePlayback: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = interaction, indication = null, onClick = onSurfaceTap),
    ) {
        if (!visible) return@Box
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .size(52.dp)
                .clickable(onClick = onNavigateBack),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(26.dp)) {
                drawLine(Color.White, Offset(size.width * .72f, size.height * .16f), Offset(size.width * .28f, size.height * .5f), strokeWidth = 4f)
                drawLine(Color.White, Offset(size.width * .28f, size.height * .5f), Offset(size.width * .72f, size.height * .84f), strokeWidth = 4f)
            }
        }
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = .72f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).clickable(onClick = onTogglePlayback), contentAlignment = Alignment.Center) {
                PlaybackIcon(playing)
            }
            val progress = if (durationSeconds > 0f) {
                (currentSeconds / durationSeconds).coerceIn(0f, 1f)
            } else 0f
            Canvas(Modifier.weight(1f).height(20.dp).padding(horizontal = 10.dp)) {
                val centerY = size.height / 2
                drawLine(Color.White.copy(alpha = .28f), Offset(0f, centerY), Offset(size.width, centerY), strokeWidth = 5f)
                drawLine(Color(0xff5b9cff), Offset(0f, centerY), Offset(size.width * progress, centerY), strokeWidth = 5f)
            }
            BasicText(
                text = "${formatWebVideoTime(currentSeconds)}/${formatWebVideoTime(durationSeconds)}",
                style = TextStyle(color = Color.White, fontSize = 14.sp),
            )
        }
    }
}

@Composable
private fun PlaybackIcon(playing: Boolean) {
    Canvas(Modifier.size(24.dp)) {
        if (playing) {
            val barWidth = size.width * .22f
            drawRect(Color.White, Offset(size.width * .2f, size.height * .15f), Size(barWidth, size.height * .7f))
            drawRect(Color.White, Offset(size.width * .58f, size.height * .15f), Size(barWidth, size.height * .7f))
        } else {
            val path = Path().apply {
                moveTo(size.width * .25f, size.height * .12f)
                lineTo(size.width * .84f, size.height * .5f)
                lineTo(size.width * .25f, size.height * .88f)
                close()
            }
            drawPath(path, Color.White)
        }
    }
}
