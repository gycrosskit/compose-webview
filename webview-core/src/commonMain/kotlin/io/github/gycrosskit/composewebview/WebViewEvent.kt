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
fun parseAppWebBridgeMessage(raw: String): WebViewBridgeMessage? {
    if (raw.length > 65536 || raw.encodeToByteArray().size > 65536) return null
    val separator = raw.indexOf('\u001F')
    if (separator <= 0 || separator > 80) return null
    return WebViewBridgeMessage(raw.substring(0, separator), raw.substring(separator + 1))
}

/** 网页组件向 UI/业务连接层发布的中立事件。 */
sealed interface WebViewEvent {
    /**
     * 主文档/子框架导航观察事件，原生已同步作出决定。
     * @property request 原始导航请求。
     * @property blocked 是否被来源、平台或宿主路由拒绝。
     */
    data class Navigation(val request: WebViewNavigationRequest, val blocked: Boolean) : WebViewEvent
    /**
     * 原生历史状态变化。
     * @property canGoBack 是否可以返回。
     * @property canGoForward 是否可以前进。
     * @property url 当前主文档完整地址，未知时为 null。
     */
    data class HistoryChanged(val canGoBack: Boolean, val canGoForward: Boolean, val url: String?) : WebViewEvent
    /**
     * 主文档开始新导航。
     * @property url 主文档地址，平台可能尚未提供。
     */
    data class PageStarted(val url: String?) : WebViewEvent
    /**
     * 原生内核已经提交首个可绘制主文档，不必等待全部子资源完成。
     * @property url 当前主文档地址，未知为 null。
     */
    data class FirstContentVisible(val url: String?) : WebViewEvent
    /**
     * 主文档加载完成，不表示所有子资源或业务接口完成。
     * @property url 主文档地址，未知为 null。
     */
    data class PageFinished(val url: String?) : WebViewEvent
    /**
     * 原始文档标题变化，展示过滤由 UI 层负责。
     * @property title 标题，尚未提供或无标题时可为 null。
     */
    data class TitleChanged(val title: String?) : WebViewEvent
    /**
     * 原生主文档加载进度。
     * @property progress 百分比，范围 0..100；100 表示本次导航完成。
     */
    data class ProgressChanged(val progress: Int) : WebViewEvent {
        init {
            require(progress in 0..100)
        }
    }
    /**
     * 通过原生来源门禁的 Bridge 消息。
     * @property value 原始处理器名称与业务正文，可能含敏感字段。
     */
    data class BridgeMessage(val value: WebViewBridgeMessage) : WebViewEvent
    /**
     * 网页进入或退出 H5 自定义全屏，宿主可同步 Window 与控制层。
     * @property isFullscreen 是否处于全屏。
     */
    data class FullscreenChanged(val isFullscreen: Boolean) : WebViewEvent
    /**
     * 主文档整页级失败。
     * @property error 中立错误快照。
     */
    data class LoadFailed(val error: WebViewLoadError) : WebViewEvent
    /**
     * 原生无法提供指定能力，宿主可以展示替代入口。
     * @property capability 不支持的中立能力。
     */
    data class CapabilityUnsupported(val capability: WebViewCapability) : WebViewEvent

    /**
     * 系统已禁止再次询问所需权限，宿主可提供应用设置入口。
     * @property permissions 非空权限集合；不代表本次仍有未完成系统授权请求。
     */
    data class PermissionSettingsRequired(
        val permissions: Set<WebViewPermission>,
    ) : WebViewEvent {
        init {
            require(permissions.isNotEmpty())
        }
    }

    /**
     * 原生脚本采集的 Navigation/Paint Timing 指标。
     * @property name 指标名称。
     * @property navigationDurationMillis 导航时间线的非负毫秒值，含累计指标与分段耗时。
     */
    data class PerformanceMetric(
        val name: WebViewPerformanceMetric,
        val navigationDurationMillis: Long,
    ) : WebViewEvent {
        init {
            require(navigationDurationMillis >= 0)
        }
    }
}

/** 平台可显式拒绝并由宿主提供替代入口的能力。 */
enum class WebViewCapability {
    FILE_CHOOSER,
    FILE_CAPTURE,
}

/** H5 可以申请、且需要在设置提示中向用户解释的系统能力。 */
enum class WebViewPermission {
    CAMERA,
    MICROPHONE,
}

/** Navigation/Paint Timing 指标；具体支持与上报次数取决于浏览器内核。 */
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
