#!/usr/bin/env python3
"""从现有 Kotlin 平台脚本生成 Objective-C 常量，避免手工维护两份协议。"""
import json
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[1]
ios = root / 'src/iosMain/kotlin/io/github/gycrosskit/composewebview/IosWebViewScripts.kt'
common = root / 'webview-core/src/commonMain/kotlin/io/github/gycrosskit/composewebview/WebViewEarlyScripts.kt'
page_messages = root / 'webview-core/src/commonMain/kotlin/io/github/gycrosskit/composewebview/WebViewPageMessages.kt'
constants = {
    'APP_BRIDGE_HANDLER': 'JSAndroidBridge',
    'WEB_EVENT_HANDLER': 'ComposeWebViewEvent',
    'FILE_CHOOSER_ALLOWED_FLAG': '__COMPOSE_WEBVIEW_FILE_CHOOSER_ALLOWED__',
    'WEB_EVENT_FULLSCREEN_ENTER': 'fullscreen:1',
    'WEB_EVENT_FULLSCREEN_EXIT': 'fullscreen:0',
}
values = []
for path, names in [(ios, ['IOS_DISABLE_ZOOM_SCRIPT', 'IOS_BRIDGE_SCRIPT', 'IOS_FILE_CHOOSER_GATE_SCRIPT', 'IOS_FILE_INPUT_SCRIPT', 'IOS_WEB_EVENT_SCRIPT', 'IOS_EXIT_FULLSCREEN_SCRIPT']), (common, ['WEB_VIEW_PERFORMANCE_SCRIPT']), (page_messages, ['WEB_VIEW_PAGE_MESSAGE_SCRIPT'])]:
    text = path.read_text()
    for name in names:
        source = re.search(r'\b' + name + r' = """(.*?)"""', text, re.S).group(1)
        for key, value in constants.items():
            source = source.replace('$' + key, value)
        values.append(f'static NSString *const {name} = @{json.dumps(source, ensure_ascii=False)};')
output = '// 由 ios/generate-scripts.py 生成，源脚本保持 Kotlin 的唯一实现。\n' + '\n'.join(values) + '\n'
destination = root / 'ios/Sources/GYWebViewScripts.inc'
if '--check' in sys.argv:
    assert destination.read_text() == output, 'Run python3 ios/generate-scripts.py to update native scripts'
else:
    destination.write_text(output)
