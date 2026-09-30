package io.github.gycrosskit.webview.consumer

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** 验证 APK 会走 D8 和 Manifest 合并；运行验收由宿主另做。 */
class ConsumerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = consumerWire() })
    }
}
