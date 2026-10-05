#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
WEBVIEW_DEVECO_HOME="${WEBVIEW_DEVECO_HOME:-/Applications/DevEco-Studio.app/Contents}"
export DEVECO_SDK_HOME="${DEVECO_SDK_HOME:-$WEBVIEW_DEVECO_HOME/sdk}"
export PATH="$WEBVIEW_DEVECO_HOME/tools/node/bin:$WEBVIEW_DEVECO_HOME/tools/ohpm/bin:$PATH"
# 候选配套 HAR 尚未上架时显式消费同版本地字节；退出时恢复精确 Registry 声明。
if [[ -n "${WEBVIEW_SYSTEM_ACTIONS_HAR:-}" ]]; then
  test -f "$WEBVIEW_SYSTEM_ACTIONS_HAR"
  snapshot="$(mktemp -d "$PWD/../build/webview-har-config.XXXXXX")"
  cp oh-package.json5 "$snapshot/root.json5"
  cp verification-consumer/oh-package.json5 "$snapshot/consumer.json5"
  webview_ohos_root="$PWD"
  trap 'cp "$snapshot/root.json5" "$webview_ohos_root/oh-package.json5"; cp "$snapshot/consumer.json5" "$webview_ohos_root/verification-consumer/oh-package.json5"; rm -rf "$snapshot"' EXIT
  python3 - "$WEBVIEW_SYSTEM_ACTIONS_HAR" <<'PYLOCAL'
import json, sys
from pathlib import Path
for name in ('oh-package.json5', 'verification-consumer/oh-package.json5'):
    path = Path(name)
    value = json.loads(path.read_text())
    value.setdefault('overrides', {})['@gycrosskit/system-actions-native'] = 'file:' + str(Path(sys.argv[1]).resolve())
    path.write_text(json.dumps(value, indent=2) + '\n')
PYLOCAL
fi
ohpm install --all
node tests/contract.test.cjs
"$WEBVIEW_DEVECO_HOME/tools/hvigor/bin/hvigorw" --mode module -p module=WebViewNative@default -p product=default assembleHar --no-daemon
node tests/contract.test.cjs --har webview-native/build/default/outputs/default/WebViewNative.har
ohpm prepublish webview-native/build/default/outputs/default/WebViewNative.har
cd verification-consumer
ohpm install --all
"$WEBVIEW_DEVECO_HOME/tools/hvigor/bin/hvigorw" --mode module -p module=WebViewConsumer@default -p product=default assembleHar --no-daemon
