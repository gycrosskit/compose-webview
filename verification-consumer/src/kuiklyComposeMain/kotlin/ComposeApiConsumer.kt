package verification
import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.ui.Modifier
import io.github.gycrosskit.composewebview.WebViewRequest
import io.github.gycrosskit.composewebview.kuikly.AppWebView
@Composable fun WebViewApi(request: WebViewRequest, visible: Boolean) {
 AppWebView(request, visible, Modifier, onEvent = {}, onController = { it?.stopLoading() })
}
