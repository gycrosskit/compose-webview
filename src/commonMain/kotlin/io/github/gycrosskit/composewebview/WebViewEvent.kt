package io.github.gycrosskit.composewebview

/**
 * JSBridge 的平台无关消息载荷。
 *
 * @property handlerName H5 协议处理器名称。
 * @property data H5 原始消息正文，公共内核不解释其业务语义。
 */
data class WebViewBridgeMessage(
    val handlerName: String,
    val data: String,
)

/** 双端只接受由注入脚本生成的 `handlerName` + 分隔符 + `data` 格式。 */
internal fun parseAppWebBridgeMessage(raw: String): WebViewBridgeMessage? {
    val separator = raw.indexOf('\u001F')
    if (separator <= 0) return null
    return WebViewBridgeMessage(raw.substring(0, separator), raw.substring(separator + 1))
}

/** 网页组件向 UI/业务连接层发布的中立事件。 */
sealed interface WebViewEvent {
    data class PageStarted(val url: String?) : WebViewEvent
    /** 原生内核已经提交首个可绘制主文档，不必等待全部子资源完成。 */
    data class FirstContentVisible(val url: String?) : WebViewEvent
    data class PageFinished(val url: String?) : WebViewEvent
    data class TitleChanged(val title: String?) : WebViewEvent
    data class ProgressChanged(val progress: Int) : WebViewEvent {
        init {
            require(progress in 0..100)
        }
    }
    data class BridgeMessage(val value: WebViewBridgeMessage) : WebViewEvent
    /** 平台网页进入或退出 H5 自定义全屏；宿主可据此同步 Window 与控制层。 */
    data class FullscreenChanged(val isFullscreen: Boolean) : WebViewEvent
    data class LoadFailed(val error: WebViewLoadError) : WebViewEvent

    /** 系统已拒绝网页所需权限；宿主应提供应用设置入口，不再重复触发系统授权框。 */
    data class PermissionSettingsRequired(
        val permissions: Set<WebViewPermission>,
    ) : WebViewEvent {
        init {
            require(permissions.isNotEmpty())
        }
    }

    /** 无需 H5 接入、由原生预置脚本采集的 Navigation Timing / Paint Timing 指标。 */
    data class PerformanceMetric(
        val name: WebViewPerformanceMetric,
        val navigationDurationMillis: Long,
    ) : WebViewEvent {
        init {
            require(navigationDurationMillis >= 0)
        }
    }
}

/** H5 可以申请、且需要在设置提示中向用户解释的系统能力。 */
enum class WebViewPermission {
    CAMERA,
    MICROPHONE,
}

enum class WebViewPerformanceMetric {
    /** 域名解析耗时；命中系统或内核缓存时可能为 0。 */
    DNS_LOOKUP,
    /** 不含 TLS 握手的 TCP 建连耗时；复用现有连接时可能为 0。 */
    TCP_CONNECT,
    /** HTTPS TLS 握手耗时；非 HTTPS 或复用现有连接时为 0。 */
    TLS_HANDSHAKE,
    /** 从浏览器发出主文档请求到收到首字节的耗时，包含服务端与下游处理时间。 */
    REQUEST,
    /** 从收到首字节到主文档响应体接收完成的耗时。 */
    RESPONSE,
    TIME_TO_FIRST_BYTE,
    DOM_CONTENT_LOADED,
    FIRST_CONTENT_VISIBLE,
    FIRST_CONTENTFUL_PAINT,
    LARGEST_CONTENTFUL_PAINT,
}
