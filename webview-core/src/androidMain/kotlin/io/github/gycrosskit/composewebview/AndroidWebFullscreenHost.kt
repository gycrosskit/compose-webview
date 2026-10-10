package io.github.gycrosskit.composewebview

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** 宿主控制层读取的原生视频镜像；不拥有业务播放器。
 * @property controlsVisible 只表示控制层显隐，false 不代表退出全屏。
 * @property playing 当前捕获到的视频播放状态。
 * @property currentSeconds 已播放秒数，未知时为 0。
 * @property durationSeconds 总时长秒数，未知时为 0。
 */
data class AndroidWebFullscreenState(val controlsVisible: Boolean, val playing: Boolean, val currentSeconds: Float, val durationSeconds: Float)

/** 工厂创建的 View 与 Chromium 视频处于同一原生容器；退出后 View 会被移除。 */
interface AndroidWebFullscreenControls {
    /** 宿主原生控制视图，factory 每次进入全屏创建一份。 */
    val view: View
    /** UI 镜像在主线程更新；不应持有另一业务播放器。 */
    fun update(state: AndroidWebFullscreenState)
}

/** 只作用于创建控制层时的全屏代次；退出后的旧按钮不能影响下一次全屏。 */
class AndroidWebFullscreenActions internal constructor(private val exitAction: () -> Unit, private val toggleAction: () -> Unit, private val revealAction: () -> Unit) {
    /** 优先退出本次全屏。 */
    fun exit() = exitAction()
    /** 切换当前 Chromium 视频播放。 */
    fun togglePlayback() = toggleAction()
    /** 显示控制并重新开始 3.5 秒计时。 */
    fun revealControls() = revealAction()
}

/** 原生层级、窗口和轮询随当前 owner 撤销；方向与控制 UI 由宿主注册时声明。 */
class AndroidWebFullscreenHost(
    private val activity: Activity,
    private val orientation: Int? = null,
    private val controlsFactory: ((Context, AndroidWebFullscreenActions) -> AndroidWebFullscreenControls)? = null,
    private val onVisibilityChanged: (Boolean) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var container: FrameLayout? = null
    private var controls: AndroidWebFullscreenControls? = null
    private var callback: WebChromeClient.CustomViewCallback? = null
    private var backCallback: OnBackPressedCallback? = null
    private var previousOrientation = 0
    private var previousSystemUi = 0
    private var previousBarsBehavior = 0
    private var previousStatusVisible = true
    private var previousNavigationVisible = true
    private var revision = 0L
    private var state = AndroidWebFullscreenState(true, false, 0f, 0f)
    private var poll: Runnable? = null
    private var hideControls: Runnable? = null

    /** 当前原生 Chrome 回调进入全屏；不授权隐藏、释放或已替换的文档。 */
    fun show(owner: WebView, video: View, result: WebChromeClient.CustomViewCallback, allowed: () -> Boolean) {
        if (container != null || !allowed() || activity.isDestroyed) { result.onCustomViewHidden(); return }
        val parent = activity.window.decorView as? ViewGroup ?: run { result.onCustomViewHidden(); return }
        val generation = ++revision
        fun current() = generation == revision && container != null && allowed()
        val actions = AndroidWebFullscreenActions({ if (current()) hide() }, {
            if (current()) {
                reveal(generation)
                if (current()) {
                    val native = findVideo(video)
                    if (native != null) { if (native.isPlaying) native.pause() else native.start() }
                    else owner.evaluateJavascript(WEB_VIDEO_TOGGLE_SCRIPT, null)
                } else if (generation == revision) hide()
            }
        }, { if (current()) reveal(generation) })
        val nativeControls = try { controlsFactory?.invoke(activity, actions) } catch (_: Exception) { result.onCustomViewHidden(); return }
        if (!allowed() || generation != revision) { result.onCustomViewHidden(); return }
        callback = result
        val insets = ViewCompat.getRootWindowInsets(parent)
        previousStatusVisible = insets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
        previousNavigationVisible = insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true
        previousOrientation = activity.requestedOrientation
        previousSystemUi = parent.systemUiVisibility
        val window = WindowCompat.getInsetsController(activity.window, parent)
        previousBarsBehavior = window.systemBarsBehavior
        controls = nativeControls
        container = object : FrameLayout(activity) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN && current()) reveal(generation)
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            (video.parent as? ViewGroup)?.removeView(video)
            addView(video, FrameLayout.LayoutParams(-1, -1))
            nativeControls?.view?.let { (it.parent as? ViewGroup)?.removeView(it); addView(it, FrameLayout.LayoutParams(-1, -1)); it.bringToFront() }
            parent.addView(this, ViewGroup.LayoutParams(-1, -1))
        }
        orientation?.let { activity.requestedOrientation = it }
        window.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.hide(WindowInsetsCompat.Type.systemBars())
        (activity as? ComponentActivity)?.onBackPressedDispatcher?.let { dispatcher ->
            backCallback = object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { if (current()) hide() else remove() } }.also { dispatcher.addCallback(it) }
        }
        state = AndroidWebFullscreenState(true, false, 0f, 0f)
        reveal(generation)
        if (!current()) { if (generation == revision) hide(); return }
        poll = object : Runnable {
            override fun run() {
                if (!current()) { if (generation == revision) hide(); return }
                val native = findVideo(video)
                if (native != null) publish(state.copy(playing = native.isPlaying, currentSeconds = native.currentPosition.coerceAtLeast(0) / 1000f, durationSeconds = native.duration.coerceAtLeast(0) / 1000f))
                else owner.evaluateJavascript(WEB_VIDEO_STATE_SCRIPT) { raw ->
                    if (!current()) return@evaluateJavascript
                    parseWebVideoState(raw)?.let { value ->
                        publish(state.copy(playing = !value.paused, currentSeconds = value.currentSeconds, durationSeconds = value.durationSeconds))
                    }
                }
                if (current()) handler.postDelayed(this, 500)
            }
        }.also { handler.post(it) }
        onVisibilityChanged(true)
    }

    private fun reveal(generation: Long) {
        hideControls?.let(handler::removeCallbacks)
        publish(state.copy(controlsVisible = true))
        if (generation != revision || container == null) return
        hideControls = Runnable { if (generation == revision && container != null) publish(state.copy(controlsVisible = false)) }.also { handler.postDelayed(it, 3500) }
    }

    private fun publish(value: AndroidWebFullscreenState) {
        state = value
        controls?.view?.visibility = if (value.controlsVisible) View.VISIBLE else View.GONE
        controls?.update(value)
    }

    /** 返回是否退出全屏；恢复进入前的方向、栏显示和栏行为。 */
    fun hide(): Boolean {
        val overlay = container ?: return false
        container = null; revision++
        val hiddenGeneration = revision
        poll?.let(handler::removeCallbacks); poll = null
        hideControls?.let(handler::removeCallbacks); hideControls = null
        backCallback?.remove(); backCallback = null
        overlay.removeAllViews(); controls = null
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        if (!activity.isDestroyed) {
            activity.requestedOrientation = previousOrientation
            val decor = activity.window.decorView
            val window = WindowCompat.getInsetsController(activity.window, decor)
            window.systemBarsBehavior = previousBarsBehavior
            if (previousStatusVisible) window.show(WindowInsetsCompat.Type.statusBars()) else window.hide(WindowInsetsCompat.Type.statusBars())
            if (previousNavigationVisible) window.show(WindowInsetsCompat.Type.navigationBars()) else window.hide(WindowInsetsCompat.Type.navigationBars())
            decor.systemUiVisibility = previousSystemUi
            ViewCompat.requestApplyInsets(decor)
        }
        val result = callback; callback = null
        result?.onCustomViewHidden()
        if (revision == hiddenGeneration && container == null) onVisibilityChanged(false)
        return true
    }

    private fun findVideo(view: View): VideoView? {
        if (view is VideoView) return view
        if (view is ViewGroup) repeat(view.childCount) { findVideo(view.getChildAt(it))?.let { found -> return found } }
        return null
    }
}
