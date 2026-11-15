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
        if [[ -f "${SIMCTL_CHILD_WEBVIEW_WIRE_RESULT_PATH:-}" ]]; then
            cp "$SIMCTL_CHILD_WEBVIEW_WIRE_RESULT_PATH" "ci-diagnostics/wire-app-debug-$debug_build.txt"
        fi
        shopt -s nullglob
        for report in "$HOME"/Library/Logs/DiagnosticReports/WireCheck-*.ips "$HOME"/Library/Logs/DiagnosticReports/WireCheck-*.crash; do
            cp "$report" "ci-diagnostics/$(basename "$report").txt"
        done
        for report in "$HOME"/Library/Logs/DiagnosticReports/com.apple.WebKit.WebContent-*.ips "$HOME"/Library/Logs/DiagnosticReports/WebKit.WebContent-*.ips; do
            if [[ -n "${WEBVIEW_SIMULATOR:-}" && "$WEBVIEW_SIMULATOR" != booted ]] && grep -Fq "$WEBVIEW_SIMULATOR" "$report"; then
                cp "$report" "ci-diagnostics/$(basename "$report").txt"
            fi
        done
        xcrun simctl spawn "${WEBVIEW_SIMULATOR:-booted}" log show --last 2m --style compact --predicate 'process == "WireCheck" OR process CONTAINS "WebKit" OR process == "runningboardd" OR subsystem BEGINSWITH "com.apple.extensionkit"' > "ci-diagnostics/wire-simulator-debug-$debug_build.txt" 2>&1
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
wire_compile=(xcrun --sdk iphonesimulator clang -target "$(uname -m)-apple-ios15.0-simulator" -isysroot "$sdk"
    -fobjc-arc -fmodules -DDEBUG="$debug_build" -F "$WEBVIEW_IOS_SIMULATOR_RENDER_FRAMEWORK_DIR" -I ios/Sources)
if [[ -n "${WEBVIEW_HOST_MEDIA_ROOT:-}" ]]; then
    : "${WEBVIEW_HOST_PROVIDER_SOURCE:?Pass the actual host MediaSdkBridge.swift for the optional consumer integration}"
    test -f "$WEBVIEW_HOST_PROVIDER_SOURCE"
    test -f "$WEBVIEW_HOST_MEDIA_ROOT/build/ios-picker-check/module/GycMedia.swiftmodule"
    test -f "$WEBVIEW_HOST_MEDIA_ROOT/build/ios-picker-check/PickerCheck.app/Frameworks/libGycMedia.dylib"
    module="$output/host-module"
    mkdir -p "$module"
    python3 - "$WEBVIEW_HOST_PROVIDER_SOURCE" "$module" "$PWD/ios/Sources/GYWebView.h" <<'PYHOST'
import json, pathlib, sys
source = pathlib.Path(sys.argv[1]).read_text()
provider = source[source.index('private final class WebFileSelectionProvider'):]
out = pathlib.Path(sys.argv[2])
(out / 'module.modulemap').write_text('module GYWebView { header ' + json.dumps(sys.argv[3]) + ' export * }\n')
prefix = '''import Foundation
import UIKit
import PhotosUI
import UniformTypeIdentifiers
import GYWebView
@testable import GycMedia
@_cdecl("GYRegisterHostFileProvider") public func registerHostFileProvider() {
    GYWebView.configureFileSelectionFactory { WebFileSelectionProvider() }
}
@_cdecl("GYCompleteHostMediaSelection") public func completeHostMediaSelection(_ pickerPointer: UnsafeMutableRawPointer, _ sourcesPointer: UnsafeMutableRawPointer) {
    let picker = Unmanaged<PHPickerViewController>.fromOpaque(pickerPointer).takeUnretainedValue()
    let urls = Unmanaged<NSArray>.fromOpaque(sourcesPointer).takeUnretainedValue() as! [URL]
    let providers = urls.map { url -> NSItemProvider in
        let provider = NSItemProvider()
        let type = UTType(filenameExtension: url.pathExtension) ?? .data
        provider.registerFileRepresentation(forTypeIdentifier: type.identifier, fileOptions: [], visibility: .all) { completion in completion(url, false, nil); return nil }
        return provider
    }
    MediaClient.shared.finishPhotoFileSelection(picker, providers: providers)
}
'''
(out / 'HostProvider.swift').write_text(prefix + provider)
PYHOST
    "${wire_compile[@]}" -DWEBVIEW_HOST_MEDIA=1 -c ios/Tests/BooleanWireCheck.m -o "$output/WireCheck.o"
    cp "$WEBVIEW_HOST_MEDIA_ROOT/build/ios-picker-check/PickerCheck.app/Frameworks/libGycMedia.dylib" "$app/Frameworks/"
    xcrun --sdk iphonesimulator swiftc -parse-as-library -target "$(uname -m)-apple-ios15.0-simulator" -sdk "$sdk" \
      -I "$module" -I "$WEBVIEW_HOST_MEDIA_ROOT/build/ios-picker-check/module" -F "$WEBVIEW_IOS_SIMULATOR_RENDER_FRAMEWORK_DIR" \
      -L "$app/Frameworks" -lGycMedia -framework UIKit -framework WebKit -framework UniformTypeIdentifiers -framework AVFoundation -framework Security -framework PhotosUI \
      -framework OpenKuiklyIOSRender -lc++ -Xlinker -ObjC -Xlinker -rpath -Xlinker @executable_path/Frameworks \
      "$output/WireCheck.o" "$module/HostProvider.swift" -o "$app/WireCheck"
else
    rm -f "$app/Frameworks/libGycMedia.dylib"
    "${wire_compile[@]}" ios/Tests/BooleanWireCheck.m -framework UIKit -framework WebKit -framework UniformTypeIdentifiers -framework AVFoundation -framework Security \
      -framework OpenKuiklyIOSRender -framework Foundation -framework CoreFoundation -lc++ -ObjC \
      -Wl,-rpath,@executable_path/Frameworks -o "$app/WireCheck"
fi
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
<key>UIApplicationSceneManifest</key><dict>
<key>UISceneConfigurations</key><dict><key>UIWindowSceneSessionRoleApplication</key><array><dict>
<key>UISceneConfigurationName</key><string>WireCheck</string>
<key>UISceneDelegateClassName</key><string>WireCheckScene</string>
</dict></array></dict></dict>
<key>NSAppTransportSecurity</key><dict><key>NSAllowsArbitraryLoadsInWebContent</key><true/></dict>
</dict></plist>
PLIST
codesign --force --sign - "$app/Frameworks/OpenKuiklyIOSRender.framework" >/dev/null
if [[ -f "$app/Frameworks/libGycMedia.dylib" ]]; then codesign --force --sign - "$app/Frameworks/libGycMedia.dylib" >/dev/null; fi
codesign --force --sign - "$app" >/dev/null
xcrun simctl install "${WEBVIEW_SIMULATOR:-booted}" "$app"
wire_data="$(xcrun simctl get_app_container "${WEBVIEW_SIMULATOR:-booted}" io.github.gycrosskit.webview.wire-check data)"
export SIMCTL_CHILD_WEBVIEW_WIRE_RESULT_PATH="$wire_data/tmp/wire-$(uuidgen).log"
xcrun simctl launch --console --terminate-running-process "${WEBVIEW_SIMULATOR:-booted}" io.github.gycrosskit.webview.wire-check 2>&1 | tee "$output/result.log" || {
    wire_failure=("$?" "${PIPESTATUS[@]}")
    printf 'FAIL: WireCheck launch=%s tee=%s\n' "${wire_failure[1]}" "${wire_failure[2]}" >&2
    exit "${wire_failure[0]}"
}
cat "$SIMCTL_CHILD_WEBVIEW_WIRE_RESULT_PATH" | tee -a "$output/result.log"
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
if [[ -n "${WEBVIEW_HOST_MEDIA_ROOT:-}" ]]; then
    grep -q '^PASS: Actual WK DOM accept -> actual App Swift provider -> real MediaClient' "$output/result.log" || {
        printf 'FAIL: Explicit host media integration PASS marker missing\n' >&2
        exit 1
    }
fi
