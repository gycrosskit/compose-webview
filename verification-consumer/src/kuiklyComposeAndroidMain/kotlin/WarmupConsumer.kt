package verification
import android.content.Context
import io.github.gycrosskit.composewebview.AndroidAppWebViewWarmup
// 编译公开 Android 入口；真实调用必须由宿主隐私授权驱动。
fun prepareWebKernel(context: Context) = AndroidAppWebViewWarmup(context).warmUp()
