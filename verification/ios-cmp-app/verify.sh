#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
output="$PWD/build/ios-cmp-check"
app="$output/CmpCheck.app"
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
cp build/bin/iosSimulatorArm64/debugTest/test.kexe "$app/CmpCheck"
xcrun swift verification/ios-cmp-app/CreateMovie.swift "$app/capture.mov"
cat > "$app/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?><plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>io.github.gycrosskit.webview.cmp-check</string>
<key>CFBundleExecutable</key><string>CmpCheck</string><key>CFBundlePackageType</key><string>APPL</string>
<key>NSAppTransportSecurity</key><dict><key>NSAllowsArbitraryLoadsInWebContent</key><true/></dict>
<key>CFBundleName</key><string>CmpCheck</string><key>CFBundleVersion</key><string>1</string>
<key>CFBundleShortVersionString</key><string>1.0</string><key>LSRequiresIPhoneOS</key><true/>
</dict></plist>
PLIST
codesign --force --sign - "$app" >/dev/null
xcrun simctl install "${WEBVIEW_SIMULATOR:-booted}" "$app"
xcrun simctl launch --console --terminate-running-process "${WEBVIEW_SIMULATOR:-booted}" io.github.gycrosskit.webview.cmp-check '--ktest_filter=*registeredUIKitAppVerifiesProductionControllerTypes' | tee "$output/result.log"
rg -q '^PASS: CMP UIKit App real UTType and production file chooser lifecycle' "$output/result.log"
