#import <UIKit/UIKit.h>
#import <OpenKuiklyIOSRender/KuiklyRenderViewExportProtocol.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * Kuikly 通过同名 Objective-C 类发现组件，底层由系统 WKWebView 渲染。
 *
 * 在主线程按 KuiklyRenderViewExportProtocol 使用：hrv_setPropWithKey:propValue: 接收 request JSON、
 * visible（默认 YES）和 onEvent；输入变化/隐藏会撤销旧文档的脚本、选择器和媒体授权。
 * hrv_callWithMethod:params:callback: 提供 reload/stopLoading/goBack/goForward/exitFullscreen/
 * evaluateJavascript；脚本仅对就绪且获授权的可见主文档执行，旧 generation 的结果不再交付。
 * hrv_removeFromSuperview 负责关闭媒体、清除 WKWebView 代理与监听，组件释放后不可复用。
 * onEvent 和脚本结果可能包含完整 URL、文件 URI 与业务正文；宿主不得直接记录敏感数据。
 */
@interface GYWebView : UIView <KuiklyRenderViewExportProtocol>
@end

NS_ASSUME_NONNULL_END
