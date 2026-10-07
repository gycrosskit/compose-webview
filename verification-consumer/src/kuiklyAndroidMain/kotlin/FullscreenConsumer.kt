package io.github.gycrosskit.webview.consumer

import android.content.pm.ActivityInfo
import android.widget.TextView
import com.tencent.kuikly.core.render.android.IKuiklyRenderExport
import io.github.gycrosskit.composewebview.AndroidWebFullscreenControls
import io.github.gycrosskit.composewebview.AndroidWebFullscreenState
import io.github.gycrosskit.composewebview.kuikly.registerGYWebView

/** 真实公开 API 接线探针：宿主声明方向与原生 View，组件负责同层级和释放。 */
fun IKuiklyRenderExport.registerWebWithHostControls() = registerGYWebView(
    fullscreenOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
    fullscreenControlsFactory = { context, actions ->
        object : AndroidWebFullscreenControls {
            override val view = TextView(context).apply { setOnClickListener { actions.exit() } }
            override fun update(state: AndroidWebFullscreenState) {
                view.text = "${state.currentSeconds}/${state.durationSeconds}"
            }
        }
    },
)

/** 默认注册仍保留 Kotlin/Java 的原有入口。 */
fun IKuiklyRenderExport.registerDefaultWeb() = registerGYWebView()
