#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
WEBVIEW_DEVECO_HOME="${WEBVIEW_DEVECO_HOME:-/Applications/DevEco-Studio.app/Contents}"
export DEVECO_SDK_HOME="${DEVECO_SDK_HOME:-$WEBVIEW_DEVECO_HOME/sdk}"
export PATH="$WEBVIEW_DEVECO_HOME/tools/node/bin:$WEBVIEW_DEVECO_HOME/tools/ohpm/bin:$PATH"
node tests/contract.test.cjs
ohpm install --all
"$WEBVIEW_DEVECO_HOME/tools/hvigor/bin/hvigorw" --mode module -p module=WebViewNative@default -p product=default assembleHar --no-daemon
node tests/contract.test.cjs --har webview-native/build/default/outputs/default/WebViewNative.har
ohpm prepublish webview-native/build/default/outputs/default/WebViewNative.har
cd verification-consumer
ohpm install --all
"$WEBVIEW_DEVECO_HOME/tools/hvigor/bin/hvigorw" --mode module -p module=WebViewConsumer@default -p product=default assembleHar --no-daemon
