"""Exercise the production wire script's policy gate without compiling or booting an SDK."""
import os
from pathlib import Path
import shutil
import subprocess
from tempfile import TemporaryDirectory


SCRIPT = Path(__file__).resolve().parents[2] / 'scripts/verify-ios-wire.sh'
with TemporaryDirectory() as directory:
    root = Path(directory)
    (root / 'scripts').mkdir()
    shutil.copy2(SCRIPT, root / 'scripts/verify-ios-wire.sh')
    framework = root / 'Products/OpenKuiklyIOSRender.framework'
    framework.mkdir(parents=True)
    (framework / 'OpenKuiklyIOSRender').touch()
    tools = root / 'tools'
    tools.mkdir()
    (tools / 'python3').write_text('''#!/usr/bin/env bash
set -euo pipefail
printf 12345 > "$2"
exec /bin/sleep 60
''')
    (tools / 'xcrun').write_text('''#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$MOCK_CALLS"
if [[ "$1" == simctl && "$2" == launch ]]; then
  printf '%s\n' "$MOCK_WIRE_RESULT"
elif [[ "$1" == --sdk && "$3" == --show-sdk-path ]]; then
  printf /mock/sdk
fi
''')
    (tools / 'codesign').write_text('#!/usr/bin/env bash\nexit 0\n')
    for tool in tools.iterdir():
        tool.chmod(0o755)
    environment = dict(os.environ, PATH=str(tools) + os.pathsep + os.environ['PATH'],
                       WEBVIEW_IOS_SIMULATOR_RENDER_FRAMEWORK_DIR=str(root / 'Products'),
                       MOCK_CALLS=str(root / 'calls'))
    environment.pop('WEBVIEW_DEBUG_BUILD', None)
    for mode, marker, expected in [
        (None, '0', True), ('0', '0', True), ('1', '1', True),
        ('0', None, False), ('1', '0', False), ('2', '0', False),
    ]:
        case = environment.copy()
        if mode is not None:
            case['WEBVIEW_DEBUG_BUILD'] = mode
        case['MOCK_WIRE_RESULT'] = 'PASS: Native Boolean wire fixture'
        if marker is not None:
            case['MOCK_WIRE_RESULT'] += '\nPASS: Native inspectable policy DEBUG=' + marker
        (root / 'calls').unlink(missing_ok=True)
        result = subprocess.run(['bash', str(root / 'scripts/verify-ios-wire.sh')],
                                env=case, capture_output=True, text=True, timeout=10)
        assert (result.returncode == 0) == expected, (mode, marker, result.stderr)
        if mode == '2':
            assert not (root / 'calls').exists(), 'Invalid mode must fail before SDK invocation'
        else:
            assert '-DDEBUG=' + (mode or '0') in (root / 'calls').read_text()
print('PASS: wire policy gate accepts matching Debug/Release, rejects skipped/mismatched policy and invalid mode; SDK calls are fixtures.')
