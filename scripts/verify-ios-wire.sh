#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${WEBVIEW_IOS_SIMULATOR_RENDER_FRAMEWORK_DIR:?Pass the parent directory of the real simulator OpenKuiklyIOSRender.framework}"
test -f "$WEBVIEW_IOS_SIMULATOR_RENDER_FRAMEWORK_DIR/OpenKuiklyIOSRender.framework/OpenKuiklyIOSRender"
output="$PWD/build/ios-wire-check"
app="$output/WireCheck.app"
mkdir -p "$app"
sdk="$(xcrun --sdk iphonesimulator --show-sdk-path)"
xcrun --sdk iphonesimulator clang -target "$(uname -m)-apple-ios15.0-simulator" -isysroot "$sdk" \
    -fobjc-arc -fmodules -F "$WEBVIEW_IOS_SIMULATOR_RENDER_FRAMEWORK_DIR" -I ios/Sources \
    ios/Tests/BooleanWireCheck.m -framework UIKit -framework WebKit -framework UniformTypeIdentifiers \
    -framework OpenKuiklyIOSRender -framework Foundation -framework CoreFoundation -lc++ -ObjC -o "$app/WireCheck"
cat > "$app/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>io.github.gycrosskit.webview.wire-check</string>
<key>CFBundleExecutable</key><string>WireCheck</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleName</key><string>WireCheck</string>
<key>CFBundleVersion</key><string>1</string>
<key>CFBundleShortVersionString</key><string>1.0</string>
<key>LSRequiresIPhoneOS</key><true/>
</dict></plist>
PLIST
codesign --force --sign - "$app" >/dev/null
xcrun simctl install "${WEBVIEW_SIMULATOR:-booted}" "$app"
xcrun simctl launch --console --terminate-running-process "${WEBVIEW_SIMULATOR:-booted}" io.github.gycrosskit.webview.wire-check | tee "$output/result.log"
rg -q '^PASS: Native Boolean wire' "$output/result.log"
