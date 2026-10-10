package io.github.gycrosskit.composewebview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Android 默认全屏控件；状态、计时、动作归属与窗口仍由 AndroidWebFullscreenHost 管理。 */
class AndroidDefaultWebFullscreenControls(
    context: Context,
    private val actions: AndroidWebFullscreenActions,
    private val labels: WebFullscreenLabels = WebFullscreenLabels(),
) : FrameLayout(context), AndroidWebFullscreenControls {
    override val view: View get() = this
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val playPath = Path()
    private var playing = false
    private var progress = 0f

    private val back = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val side = dp(26).toFloat()
            val saved = canvas.save()
            canvas.translate((width - side) / 2, (height - side) / 2)
            paint.color = Color.WHITE
            paint.strokeWidth = 4f
            canvas.drawLine(side * .72f, side * .16f, side * .28f, side * .5f, paint)
            canvas.drawLine(side * .28f, side * .5f, side * .72f, side * .84f, paint)
            canvas.restoreToCount(saved)
        }
    }.apply {
        contentDescription = labels.back
        setOnClickListener { actions.exit() }
    }

    private val playback = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val side = dp(24).toFloat()
            val saved = canvas.save()
            canvas.translate((width - side) / 2, (height - side) / 2)
            paint.color = Color.WHITE
            if (playing) {
                canvas.drawRect(side * .2f, side * .15f, side * .42f, side * .85f, paint)
                canvas.drawRect(side * .58f, side * .15f, side * .8f, side * .85f, paint)
            } else {
                playPath.reset()
                playPath.moveTo(side * .25f, side * .12f)
                playPath.lineTo(side * .84f, side * .5f)
                playPath.lineTo(side * .25f, side * .88f)
                playPath.close()
                canvas.drawPath(playPath, paint)
            }
            canvas.restoreToCount(saved)
        }
    }.apply {
        setOnClickListener { actions.togglePlayback() }
    }

    // 正式 CMP 的进度条只展示镜像，不提供拖动快进。
    private val timeline = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val start = paddingLeft.toFloat()
            val end = (width - paddingRight).toFloat().coerceAtLeast(start)
            val center = height / 2f
            paint.strokeWidth = 5f
            paint.color = Color.argb(71, 255, 255, 255)
            canvas.drawLine(start, center, end, center, paint)
            paint.color = Color.rgb(91, 156, 255)
            canvas.drawLine(start, center, start + (end - start) * progress, center, paint)
        }
    }.apply {
        setPadding(dp(10), 0, dp(10), 0)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val time = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 14f
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL
        setSingleLine()
    }

    init {
        // SDK 在原生容器分发 ACTION_DOWN 时显示控件；这里消费画面点击，避免穿透到网页控件。
        contentDescription = labels.toggleControls
        isClickable = true
        setOnClickListener { actions.revealControls() }
        addView(back, LayoutParams(dp(52), dp(52), Gravity.TOP or Gravity.START).apply {
            topMargin = dp(12)
            marginStart = dp(12)
        })
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.argb(184, 0, 0, 0))
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addView(playback, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(timeline, LinearLayout.LayoutParams(0, dp(20), 1f))
            addView(time, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        addView(bar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    override fun update(state: AndroidWebFullscreenState) {
        playing = state.playing
        progress = if (state.durationSeconds > 0f) (state.currentSeconds / state.durationSeconds).coerceIn(0f, 1f) else 0f
        playback.contentDescription = if (playing) labels.pause else labels.play
        time.text = "${formatWebVideoTime(state.currentSeconds)}/${formatWebVideoTime(state.durationSeconds)}"
        playback.invalidate()
        timeline.invalidate()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
