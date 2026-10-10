package verification

import com.tencent.kuikly.core.render.android.IKuiklyRenderExport
import io.github.gycrosskit.composewebview.AndroidDefaultWebFullscreenControls
import io.github.gycrosskit.composewebview.WebFullscreenLabels
import io.github.gycrosskit.composewebview.kuikly.registerGYWebView

fun IKuiklyRenderExport.registerComponentFullscreenControls() = registerGYWebView(
    fullscreenControlsFactory = { context, actions ->
        AndroidDefaultWebFullscreenControls(context, actions, WebFullscreenLabels("Back", "Play", "Pause", "Controls"))
    },
)
