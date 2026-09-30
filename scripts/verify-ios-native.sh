#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${WEBVIEW_IOS_RENDER_FRAMEWORK_DIR:?Pass the parent directory of the real iphoneos OpenKuiklyIOSRender.framework}"
test -f "$WEBVIEW_IOS_RENDER_FRAMEWORK_DIR/OpenKuiklyIOSRender.framework/OpenKuiklyIOSRender"
python3 ios/generate-scripts.py --check
mkdir -p build/native-ios
sdk="$(xcrun --sdk iphoneos --show-sdk-path)"
flags=(-target arm64-apple-ios15.0 -isysroot "$sdk" -fobjc-arc -fmodules -F "$WEBVIEW_IOS_RENDER_FRAMEWORK_DIR" -I ios/Sources)
xcrun --sdk iphoneos clang "${flags[@]}" -c ios/Sources/GYWebView.m -o build/native-ios/GYWebView.o
xcrun ar rcs build/native-ios/libGYWebView.a build/native-ios/GYWebView.o
cat > build/native-ios/consumer.m <<'EOF'
#import "GYWebView.h"
int main(void) {
    @autoreleasepool {
        id<KuiklyRenderViewExportProtocol> view = [[GYWebView alloc] initWithFrame:CGRectZero];
        [view hrv_setPropWithKey:@"visible" propValue:@YES];
        [view hrv_callWithMethod:@"goBack" params:nil callback:^(id result) { (void)result; }];
    }
    return 0;
}
EOF
xcrun --sdk iphoneos clang "${flags[@]}" build/native-ios/consumer.m build/native-ios/libGYWebView.a -framework UIKit -framework WebKit -framework UniformTypeIdentifiers -framework OpenKuiklyIOSRender -ObjC -o build/native-ios/consumer
cat > build/native-ios/consumer.swift <<'EOF'
import UIKit
let view: any KuiklyRenderViewExportProtocol = GYWebView(frame: .zero)
view.hrv_setProp(withKey: "visible", propValue: true)
view.hrv_call?(withMethod: "goBack", params: nil) { result in
    _ = (result as? [String: Any])?["result"] as? Bool
}
EOF
xcrun --sdk iphoneos swiftc -sdk "$sdk" -target arm64-apple-ios15.0 -F "$WEBVIEW_IOS_RENDER_FRAMEWORK_DIR" -import-objc-header ios/Sources/GYWebView.h -typecheck build/native-ios/consumer.swift
echo "Native iOS compile, real SDK final link and Swift public API passed"
