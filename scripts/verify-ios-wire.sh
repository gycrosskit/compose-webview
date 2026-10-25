#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${WEBVIEW_IOS_SIMULATOR_RENDER_FRAMEWORK_DIR:?Pass the parent directory of the real simulator OpenKuiklyIOSRender.framework}"
test -f "$WEBVIEW_IOS_SIMULATOR_RENDER_FRAMEWORK_DIR/OpenKuiklyIOSRender.framework/OpenKuiklyIOSRender"
debug_build="${WEBVIEW_DEBUG_BUILD:-0}"
[[ "$debug_build" =~ ^[01]$ ]] || { echo 'WEBVIEW_DEBUG_BUILD must be 0 or 1' >&2; exit 1; }
cleanup_wire_check() {
    local wire_exit=$?
    set +e
    if [[ -n "${fixture_pid:-}" ]]; then
        kill "$fixture_pid" 2>/dev/null
        wait "$fixture_pid" 2>/dev/null
    fi
    if [[ "$wire_exit" != 0 ]]; then
        mkdir -p ci-diagnostics
        shopt -s nullglob
        for report in "$HOME"/Library/Logs/DiagnosticReports/WireCheck-*.ips "$HOME"/Library/Logs/DiagnosticReports/WireCheck-*.crash; do
            cp "$report" "ci-diagnostics/$(basename "$report").txt"
        done
        xcrun simctl spawn "${WEBVIEW_SIMULATOR:-booted}" log show --last 5m --style compact --predicate 'process == "WireCheck"' > "ci-diagnostics/wire-simulator-debug-$debug_build.txt" 2>&1
    fi
    return "$wire_exit"
}
trap cleanup_wire_check EXIT
output="$PWD/build/ios-wire-check"
app="$output/WireCheck.app"
mkdir -p "$app"
rm -f "$output/page-server.port"
python3 ios/Tests/page-channel-server.py "$output/page-server.port" &
fixture_pid=$!
for _ in {1..100}; do
    test -s "$output/page-server.port" && break
    sleep 0.1
done
test -s "$output/page-server.port"
export SIMCTL_CHILD_WEBVIEW_WIRE_PAGE_URL="http://127.0.0.1:$(cat "$output/page-server.port")/page"
mkdir -p "$app/Frameworks"
xcrun swift verification/native-ios/CreateMovie.swift "$app/capture.mov"
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
<key>UIApplicationSceneManifest</key><dict><key>UIApplicationSupportsMultipleScenes</key><false/><key>UISceneConfigurations</key><dict/></dict>
<key>NSAppTransportSecurity</key><dict><key>NSAllowsArbitraryLoadsInWebContent</key><true/></dict>
</dict></plist>
PLIST
codesign --force --sign - "$app/Frameworks/OpenKuiklyIOSRender.framework" >/dev/null
codesign --force --sign - "$app" >/dev/null
xcrun simctl install "${WEBVIEW_SIMULATOR:-booted}" "$app"
xcrun simctl launch --console --terminate-running-process "${WEBVIEW_SIMULATOR:-booted}" io.github.gycrosskit.webview.wire-check 2>&1 | tee "$output/result.log" || {
    wire_failure=("$?" "${PIPESTATUS[@]}")
    printf 'FAIL: WireCheck launch=%s tee=%s\n' "${wire_failure[1]}" "${wire_failure[2]}" >&2
    exit "${wire_failure[0]}"
}
if [[ "${SIMCTL_CHILD_WEBVIEW_PIP_ONLY:-0}" == 1 ]]; then
    grep -q '^PASS: Native PiP media policy' "$output/result.log"
    grep -q '^PASS: Real WKWebView isolated media handler' "$output/result.log"
    exit 0
fi
grep -q '^PASS: Native Boolean wire' "$output/result.log" || {
    wire_exit=$?
    printf 'FAIL: WireCheck Boolean PASS marker missing\n' >&2
    exit "$wire_exit"
}
grep -q "^PASS: Native inspectable policy DEBUG=$debug_build$" "$output/result.log" || {
    wire_exit=$?
    printf 'FAIL: WireCheck DEBUG=%s policy PASS marker missing\n' "$debug_build" >&2
    exit "$wire_exit"
}
