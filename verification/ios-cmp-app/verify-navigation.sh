#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
output="$PWD/build/ios-cmp-navigation-check"
app="$output/CmpNavigationCheck.app"
mkdir -p "$app"
cp build/bin/iosSimulatorArm64/debugTest/test.kexe "$app/CmpNavigationCheck"
cat > "$app/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?><plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>io.github.gycrosskit.webview.cmp-navigation-check</string>
<key>CFBundleExecutable</key><string>CmpNavigationCheck</string><key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleName</key><string>CmpNavigationCheck</string><key>CFBundleVersion</key><string>1</string>
<key>CFBundleShortVersionString</key><string>1.0</string><key>LSRequiresIPhoneOS</key><true/>
<key>NSAppTransportSecurity</key><dict><key>NSAllowsLocalNetworking</key><true/></dict>
</dict></plist>
PLIST
rm -f "$output/port"
python3 -u - "$output/port" <<'PY' > "$output/http.log" 2>&1 &
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import sys
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        body = b'<html><head><title>Loaded</title></head><body>History</body></html>'
        self.send_response(200)
        self.send_header('Content-Type', 'text/html')
        self.end_headers()
        self.wfile.write(body)
server = HTTPServer(('127.0.0.1', 0), Handler)
Path(sys.argv[1]).write_text(str(server.server_port))
server.serve_forever()
PY
server_pid=$!
trap 'kill "$server_pid" 2>/dev/null || true' EXIT
for attempt in {1..50}; do [[ -s "$output/port" ]] && break; sleep 0.1; done
export SIMCTL_CHILD_WEBVIEW_NAVIGATION_TEST_URL="http://127.0.0.1:$(cat "$output/port")"
codesign --force --sign - "$app" >/dev/null
xcrun simctl install "${WEBVIEW_SIMULATOR:-booted}" "$app"
xcrun simctl launch --console --terminate-running-process "${WEBVIEW_SIMULATOR:-booted}" io.github.gycrosskit.webview.cmp-navigation-check '--ktest_filter=*IosWebViewFrameTest*' | tee "$output/result.log"
rg -q '^PASS: CMP UIKit App genuine iframe rejected with current token, main frame accepted' "$output/result.log"
rg -q '^PASS: CMP UIKit App reentrant cancellation blocks old owner back and forward' "$output/result.log"
