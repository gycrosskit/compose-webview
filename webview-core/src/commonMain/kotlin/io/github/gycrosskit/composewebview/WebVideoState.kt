package io.github.gycrosskit.composewebview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull

/** H5 视频镜像；没有自己的播放器、快进或学习业务。 */
data class WebVideoState(val currentSeconds: Float, val durationSeconds: Float, val paused: Boolean)

/** 宿主注入本地化无障碍文案，两套 UI 引擎不引用应用资源。 */
data class WebFullscreenLabels(
    val back: String = "返回",
    val play: String = "播放",
    val pause: String = "暂停",
    val toggleControls: String = "显示或隐藏视频控件",
)

/** 仅接收有界 JSON 数值/布尔三元组，不接受字符串数字或非有限时长。 */
fun parseWebVideoState(raw: String?): WebVideoState? {
    if (raw == null || raw.length > 1024) return null
    val values = runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return null
    if (values.size != 3) return null
    val current = values[0] as? JsonPrimitive ?: return null
    val duration = values[1] as? JsonPrimitive ?: return null
    val paused = values[2] as? JsonPrimitive ?: return null
    if (current.isString || duration.isString || paused.isString) return null
    val position = current.floatOrNull?.takeIf(Float::isFinite) ?: return null
    val length = duration.floatOrNull?.takeIf(Float::isFinite) ?: return null
    return WebVideoState(position.coerceAtLeast(0f), length.coerceAtLeast(0f), paused.booleanOrNull ?: return null)
}

/** 与系统计时格式一致；非法/负值显示零。 */
fun formatWebVideoTime(seconds: Float): String {
    val total = seconds.takeIf(Float::isFinite)?.toInt()?.coerceAtLeast(0) ?: 0
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val remaining = total % 60
    val parts = if (hours > 0) listOf(hours, minutes, remaining) else listOf(minutes, remaining)
    return parts.joinToString(":") { it.toString().padStart(2, '0') }
}

// 只读主文档及可访问 iframe；同源限制仍由 WebView 执行。
private const val WEB_VIDEO_FIND_FUNCTION = """
    function nativeFindVideo(doc) {
      try {
        var direct = doc.querySelector('video');
        if (direct) return direct;
        var frames = doc.querySelectorAll('iframe');
        for (var i = 0; i < frames.length; i++) {
          try { var nested = nativeFindVideo(frames[i].contentDocument); if (nested) return nested; } catch (_) {}
        }
      } catch (_) {}
      return null;
    }
"""

/** 镜像当前 H5 媒体，不读取跨来源 iframe。 */
const val WEB_VIDEO_STATE_SCRIPT = """
    (function() {
      $WEB_VIDEO_FIND_FUNCTION
      var video = nativeFindVideo(document);
      return video ? [video.currentTime || 0, video.duration || 0, !!video.paused] : [0, 0, true];
    })();
"""

/** 切换当前 H5 媒体并返回相同镜像；play 仍受内核用户手势策略限制。 */
const val WEB_VIDEO_TOGGLE_SCRIPT = """
    (function() {
      $WEB_VIDEO_FIND_FUNCTION
      var video = nativeFindVideo(document);
      if (!video) return [0, 0, true];
      if (video.paused) video.play(); else video.pause();
      return [video.currentTime || 0, video.duration || 0, !!video.paused];
    })();
"""
