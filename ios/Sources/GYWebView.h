#import <UIKit/UIKit.h>
#import <OpenKuiklyIOSRender/KuiklyRenderViewExportProtocol.h>

NS_ASSUME_NONNULL_BEGIN

/// 宿主注入文件能力；实现须在 Main 回调，并先关闭其系统呈现。
/// status: selected/cancelled/denied/blocked/restricted/failed；仅 blocked 可建议设置。
/// release 归原文件 owner，组件在结果拒绝、迟到或文档撤销时调用。
@protocol GYWebFileSelectionProvider <NSObject>
- (void)selectFiles:(NSArray<NSString *> *)acceptTypes multiple:(BOOL)multiple capture:(BOOL)capture presenter:(UIViewController *)presenter completion:(void (^)(NSArray<NSURL *> * _Nullable urls, NSString *status, NSArray<NSString *> *permissions, dispatch_block_t _Nullable release))completion;
- (void)cancelFileSelection;
@end

/**
 * Kuikly 通过同名 Objective-C 类发现组件，底层由系统 WKWebView 渲染。
 *
 * 在主线程按 KuiklyRenderViewExportProtocol 使用：hrv_setPropWithKey:propValue: 接收 request JSON、
 * visible（默认 YES）和 onEvent；输入变化/隐藏会撤销旧文档的脚本、选择器和媒体授权。
 * hrv_callWithMethod:params:callback: 提供 reload/stopLoading/goBack/goForward/exitFullscreen/
 * evaluateJavascript/replyPageMessage；后者 Boolean 表示当前文档单次回执已提交，隐藏后须新文档恢复频道；脚本仅对就绪且获授权的可见主文档执行，旧 generation 的结果不再交付。
 * hrv_removeFromSuperview 负责关闭媒体、清除 WKWebView 代理与监听，组件释放后不可复用。
 * 事件与命令回执的布尔字段保持 JSON Boolean，不能用数字 0/1 代替。
 * onEvent 和脚本结果可能包含完整 URL、文件 URI 与业务正文；宿主不得直接记录敏感数据。
 */
@interface GYWebView : UIView <KuiklyRenderViewExportProtocol>
/// Renderer 创建前注册无 owner 捕获的工厂；每个 View 持有自己的 provider。
+ (void)configureFileSelectionFactory:(id<GYWebFileSelectionProvider> _Nullable (^)(void))factory;
@end

NS_ASSUME_NONNULL_END
