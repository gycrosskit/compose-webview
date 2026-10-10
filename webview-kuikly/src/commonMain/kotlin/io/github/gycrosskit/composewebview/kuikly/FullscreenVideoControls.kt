package io.github.gycrosskit.composewebview.kuikly

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.gycrosskit.composewebview.WebFullscreenLabels
import io.github.gycrosskit.composewebview.WebVideoState
import io.github.gycrosskit.composewebview.WEB_VIDEO_STATE_SCRIPT
import io.github.gycrosskit.composewebview.WEB_VIDEO_TOGGLE_SCRIPT
import io.github.gycrosskit.composewebview.parseWebVideoState
import io.github.gycrosskit.composewebview.formatWebVideoTime
import com.tencent.kuikly.compose.foundation.Canvas
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.interaction.MutableInteractionSource
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.text.BasicText
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Size
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.Path
import com.tencent.kuikly.compose.ui.semantics.contentDescription
import com.tencent.kuikly.compose.ui.semantics.semantics
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 网页层级的 KuiklyCompose 控制层，移除即取消轮询、动作和自动隐藏。
 * 仅在当前页面仍能承载全屏内容时挂载；Android 原生全屏应通过 factory 注入控件，iOS 系统播放器不能被此层覆盖。
 */
@Composable
fun FullscreenPlayerOverlay(
    view: GYWebView,
    labels: WebFullscreenLabels = WebFullscreenLabels(),
    progressColor: Color = Color(0xff5b9cff),
    onExitFullscreen: () -> Unit,
) {
    key(view) {
        var visible by remember { mutableStateOf(true) }
        var interaction by remember { mutableStateOf(0) }
        var state by remember { mutableStateOf(WebVideoState(0f, 0f, true)) }
        val scope = rememberCoroutineScope()
        fun reveal() {
            visible = true
            interaction++
        }
        LaunchedEffect(visible, interaction) {
            if (visible) {
                delay(3_500)
                visible = false
            }
        }
        LaunchedEffect(view) {
            while (isActive) {
                parseWebVideoState(evaluatePlayerScript(view, WEB_VIDEO_STATE_SCRIPT))?.let { state = it }
                delay(500)
            }
        }
        FullscreenVideoControls(
            labels = labels,
            progressColor = progressColor,
            visible = visible,
            playing = !state.paused,
            currentSeconds = state.currentSeconds,
            durationSeconds = state.durationSeconds,
            onSurfaceTap = {
                visible = !visible
                interaction++
            },
            onNavigateBack = onExitFullscreen,
            onTogglePlayback = {
                reveal()
                scope.launch {
                    parseWebVideoState(evaluatePlayerScript(view, WEB_VIDEO_TOGGLE_SCRIPT))?.let { state = it }
                }
            },
        )
    }
}

private suspend fun evaluatePlayerScript(view: GYWebView, script: String): String? =
    // 原生在导航/隐藏时可丢弃回执，有界等待避免仍挂载的 overlay 永久停住。
    withTimeoutOrNull(1_000) {
        suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(script) { result ->
                if (continuation.isActive) continuation.resume(result)
            }
        }
    }

/** 无状态控制 UI；用于已有镜像 owner 的宿主，不启动额外轮询。 */
@Composable
fun FullscreenVideoControls(
    labels: WebFullscreenLabels,
    progressColor: Color = Color(0xff5b9cff),
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
            .fillMaxSize().zIndex(2f)
            .clickable(interactionSource = interaction, indication = null, onClickLabel = labels.toggleControls, onClick = onSurfaceTap),
    ) {
        if (!visible) return@Box
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .size(52.dp)
                .semantics { contentDescription = labels.back }.clickable(onClick = onNavigateBack),
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
            Box(Modifier.size(44.dp).semantics { contentDescription = if (playing) labels.pause else labels.play }.clickable(onClick = onTogglePlayback), contentAlignment = Alignment.Center) {
                PlaybackIcon(playing)
            }
            val progress = if (durationSeconds > 0f) {
                (currentSeconds / durationSeconds).coerceIn(0f, 1f)
            } else 0f
            Canvas(Modifier.weight(1f).height(20.dp).padding(horizontal = 10.dp)) {
                val centerY = size.height / 2
                drawLine(Color.White.copy(alpha = .28f), Offset(0f, centerY), Offset(size.width, centerY), strokeWidth = 5f)
                drawLine(progressColor, Offset(0f, centerY), Offset(size.width * progress, centerY), strokeWidth = 5f)
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
