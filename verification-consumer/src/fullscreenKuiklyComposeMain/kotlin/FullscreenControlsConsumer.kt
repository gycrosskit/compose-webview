package verification

import androidx.compose.runtime.Composable
import io.github.gycrosskit.composewebview.WebFullscreenLabels
import io.github.gycrosskit.composewebview.kuikly.FullscreenPlayerOverlay
import io.github.gycrosskit.composewebview.kuikly.GYWebView

/** 候选 API 探针；只有发布包含本次能力的精确版本后才能用于 remoteOnly 消费。 */
@Composable fun FullscreenControlsApi(view: GYWebView, exit: () -> Unit) {
    FullscreenPlayerOverlay(view, labels = WebFullscreenLabels("Back", "Play", "Pause", "Controls"), onExitFullscreen = exit)
}
