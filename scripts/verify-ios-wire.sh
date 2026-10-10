#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${WEBVIEW_IOS_SIMULATOR_RENDER_FRAMEWORK_DIR:?Pass the parent directory of the real simulator OpenKuiklyIOSRender.framework}"
test -f "$WEBVIEW_IOS_SIMULATOR_RENDER_FRAMEWORK_DIR/OpenKuiklyIOSRender.framework/OpenKuiklyIOSRender"
debug_build="${WEBVIEW_DEBUG_BUILD:-0}"
[[ "$debug_build" =~ ^[01]$ ]] || { echo 'WEBVIEW_DEBUG_BUILD must be 0 or 1' >&2; exit 1; }
output="$PWD/build/ios-wire-check"
app="$output/WireCheck.app"
mkdir -p "$app"
rm -f "$output/page-server.port"
python3 ios/Tests/page-channel-server.py "$output/page-server.port" &
fixture_pid=$!
trap 'kill "$fixture_pid" 2>/dev/null || true; wait "$fixture_pid" 2>/dev/null || true' EXIT
for _ in {1..100}; do
    test -s "$output/page-server.port" && break
    sleep 0.1
done
test -s "$output/page-server.port"
export SIMCTL_CHILD_WEBVIEW_WIRE_PAGE_URL="http://127.0.0.1:$(cat "$output/page-server.port")/page"
mkdir -p "$app/Frameworks"
xcrun swift verification/ios-cmp-app/CreateMovie.swift "$app/capture.mov"
cp -R "$WEBVIEW_IOS_SIMULATOR_RENDER_FRAMEWORK_DIR/OpenKuiklyIOSRender.framework" "$app/Frameworks/"
sdk="$(xcrun --sdk iphonesimulator --show-sdk-path)"
xcrun --sdk iphonesimulator clang -target "$(uname -m)-apple-ios15.0-simulator" -isysroot "$sdk" \
    -fobjc-arc -fmodules -DDEBUG="$debug_build" -F "$WEBVIEW_IOS_SIMULATOR_RENDER_FRAMEWORK_DIR" -I ios/Sources \
    ios/Tests/BooleanWireCheck.m -framework UIKit -framework WebKit -framework UniformTypeIdentifiers -framework AVFoundation \
    -framework OpenKuiklyIOSRender -framework Foundation -framework CoreFoundation -lc++ -ObjC \
    -Wl,-rpath,@executable_path/Frameworks -o "$app/WireCheck"
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
<key>NSAppTransportSecurity</key><dict><key>NSAllowsArbitraryLoadsInWebContent</key><true/></dict>
</dict></plist>
PLIST
codesign --force --sign - "$app/Frameworks/OpenKuiklyIOSRender.framework" >/dev/null
codesign --force --sign - "$app" >/dev/null
xcrun simctl install "${WEBVIEW_SIMULATOR:-booted}" "$app"
xcrun simctl launch --console --terminate-running-process "${WEBVIEW_SIMULATOR:-booted}" io.github.gycrosskit.webview.wire-check | tee "$output/result.log"
grep -q '^PASS: Native Boolean wire' "$output/result.log"
grep -q "^PASS: Native inspectable policy DEBUG=$debug_build$" "$output/result.log"
