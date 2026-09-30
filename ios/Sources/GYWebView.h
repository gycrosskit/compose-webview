#import <UIKit/UIKit.h>
#import <OpenKuiklyIOSRender/KuiklyRenderViewExportProtocol.h>

NS_ASSUME_NONNULL_BEGIN

/** Kuikly 通过同名 Objective-C 类发现组件，底层由系统 WKWebView 渲染。 */
@interface GYWebView : UIView <KuiklyRenderViewExportProtocol>
@end

NS_ASSUME_NONNULL_END
