package io.github.gycrosskit.composewebview

/**
 * Bridge、文件选择和音视频采集的统一安全门禁。
 *
 * 任一高权限能力开启时必须至少配置一个有效 HTTPS 来源；平台仍需在实际请求发生时再次校验当前主框架来源。
 *
 * @property trustedOrigins 允许使用高权限能力的 HTTPS 来源。
 * @property appBridgeEnabled 是否安装应用 JSBridge。
 * @property pageBridgeEnabled 是否为初始同源页面安装低权限消息 Bridge；业务层必须限制可处理的命令。
 * @property fileChooserEnabled 是否允许可信页面打开文件选择器。
 * @property mediaCaptureEnabled 是否允许可信页面申请音视频采集。
 */
data class WebViewSecurity(
    val trustedOrigins: WebViewTrustPolicy = WebViewTrustPolicy(),
    val appBridgeEnabled: Boolean = false,
    val pageBridgeEnabled: Boolean = false,
    val fileChooserEnabled: Boolean = false,
    val mediaCaptureEnabled: Boolean = false,
) {
    init {
        require(!(appBridgeEnabled && pageBridgeEnabled)) {
            "应用 Bridge 与页面 Bridge 不能同时开启"
        }
        require(
            !(appBridgeEnabled || fileChooserEnabled || mediaCaptureEnabled) || !trustedOrigins.isEmpty,
        ) { "开启 WebView 高权限能力前必须配置有效 HTTPS 来源" }
    }
}
