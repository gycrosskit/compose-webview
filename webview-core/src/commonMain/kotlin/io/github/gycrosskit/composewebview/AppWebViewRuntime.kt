package io.github.gycrosskit.composewebview

import kotlin.concurrent.Volatile

/** Compose WebView 模块产生的日志级别。 */
enum class AppWebViewLogLevel {
    /** 高频生命周期和性能细节。 */
    DEBUG,
    /** 创建、加载、预热和正常释放节点。 */
    INFO,
    /** 页面失败或可继续执行的释放异常。 */
    WARNING,
    /** 宿主需要重点处理的不可恢复错误。 */
    ERROR,
}

/**
 * 模块日志出口。诊断地址保持原文，Bridge 数据仍不得进入日志。
 */
fun interface AppWebViewLogSink {
    fun log(level: AppWebViewLogLevel, message: String, error: Throwable?)
}

/** Compose WebView 的进程级双平台日志配置入口。 */
object AppWebViewRuntime {
    /** 进程级只持有宿主 Logger，不允许安装页面、ViewModel 或原生 View。 */
    @Volatile
    private var logSink: AppWebViewLogSink = AppWebViewLogSink { _, _, _ -> }

    /** 安装宿主日志出口；重复调用会替换前一个实现。 */
    fun installLogSink(sink: AppWebViewLogSink) {
        logSink = sink
    }

    /** 内核统一从这里输出，避免源码文件直接选择宿主日志框架。 */
    fun log(
        level: AppWebViewLogLevel,
        message: String,
        error: Throwable? = null,
    ) {
        logSink.log(level, message, error)
    }
}
