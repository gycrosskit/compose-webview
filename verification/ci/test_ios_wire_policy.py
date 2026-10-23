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
  mkdir -p "$(dirname "$SIMCTL_CHILD_WEBVIEW_WIRE_RESULT_PATH")"
  printf '%s\n' "$MOCK_WIRE_RESULT" > "$(dirname "$SIMCTL_CHILD_WEBVIEW_WIRE_RESULT_PATH")/wire-old.log"
  if [[ "$MOCK_RESULT_FILE" == current ]]; then
    printf '%s\n' "$MOCK_WIRE_RESULT" > "$SIMCTL_CHILD_WEBVIEW_WIRE_RESULT_PATH"
  fi
  echo launcher-console-only
  echo wire-stderr >&2
  exit "$MOCK_LAUNCH_EXIT"
elif [[ "$1" == simctl && "$2" == get_app_container ]]; then
  printf '%s\n' "$MOCK_APP_DATA"
elif [[ "$1" == simctl && "$2" == spawn ]]; then
  echo wire-simulator-log
  exit 99
elif [[ "$1" == --sdk && "$3" == --show-sdk-path ]]; then
  printf /mock/sdk
fi
''')
    (tools / 'codesign').write_text('#!/usr/bin/env bash\nexit 0\n')
    (tools / 'rg').write_text('#!/usr/bin/env bash\necho "ripgrep is unavailable in the runner fixture" >&2\nexit 127\n')
    for tool in tools.iterdir():
        tool.chmod(0o755)
    reports = root / 'home/Library/Logs/DiagnosticReports'
    reports.mkdir(parents=True)
    (reports / 'WireCheck-fixture.ips').write_text('wire-crash')
    (reports / 'WireCheck-fixture.crash').write_text('wire-legacy-crash')
    (reports / 'Unrelated.ips').write_text('must-not-copy')
    (reports / 'WireCheckOther-fixture.ips').write_text('must-not-copy')
    environment = dict(os.environ, PATH=str(tools) + os.pathsep + os.environ['PATH'],
                       HOME=str(root / 'home'),
                       WEBVIEW_IOS_SIMULATOR_RENDER_FRAMEWORK_DIR=str(root / 'Products'),
                       MOCK_CALLS=str(root / 'calls'))
    environment.update(MOCK_APP_DATA=str(root / 'app-data'), MOCK_RESULT_FILE='current')
    environment.pop('WEBVIEW_DEBUG_BUILD', None)
    for mode, marker, launch_exit, boolean_pass, expected in [
        (None, '0', 0, True, 0), ('0', '0', 0, True, 0), ('1', '1', 0, True, 0),
        ('0', None, 0, True, 1), ('1', '0', 0, True, 1), ('2', '0', 0, True, 1),
        ('0', '0', 7, True, 7), ('0', '0', 0, False, 1),
    ]:
        case = environment.copy()
        if mode is not None:
            case['WEBVIEW_DEBUG_BUILD'] = mode
        case['MOCK_LAUNCH_EXIT'] = str(launch_exit)
        case['MOCK_WIRE_RESULT'] = 'PASS: Native Boolean wire fixture' if boolean_pass else 'START'
        if marker is not None:
            case['MOCK_WIRE_RESULT'] += '\nPASS: Native inspectable policy DEBUG=' + marker
        (root / 'calls').unlink(missing_ok=True)
        shutil.rmtree(root / 'ci-diagnostics', ignore_errors=True)
        result = subprocess.run(['bash', str(root / 'scripts/verify-ios-wire.sh')],
                                env=case, capture_output=True, text=True, timeout=10)
        assert result.returncode == expected, (mode, marker, result.returncode, result.stderr)
        if mode == '2':
            assert not (root / 'calls').exists(), 'Invalid mode must fail before SDK invocation'
        else:
            assert '-DDEBUG=' + (mode or '0') in (root / 'calls').read_text()
            assert 'wire-stderr' in (root / 'build/ios-wire-check/result.log').read_text()
        diagnostics = root / 'ci-diagnostics'
        if expected and mode != '2':
            assert (diagnostics / 'WireCheck-fixture.ips.txt').read_text() == 'wire-crash'
            assert (diagnostics / 'WireCheck-fixture.crash.txt').read_text() == 'wire-legacy-crash'
            assert 'wire-simulator-log' in (diagnostics / f'wire-simulator-debug-{mode or "0"}.txt').read_text()
            assert 'process == "WireCheck"' in (root / 'calls').read_text()
            assert not list(diagnostics.glob('Unrelated*'))
            assert not list(diagnostics.glob('WireCheckOther*'))
        else:
            assert not diagnostics.exists(), 'Successful or rejected-before-launch runs must not collect logs'
        if mode != '2' and expected:
            assert (diagnostics / f'wire-app-debug-{mode or "0"}.txt').read_text().strip() == case['MOCK_WIRE_RESULT']
    case = dict(environment, MOCK_LAUNCH_EXIT='0', MOCK_RESULT_FILE='old-only',
                MOCK_WIRE_RESULT='PASS: Native Boolean wire fixture\nPASS: Native inspectable policy DEBUG=0')
    result = subprocess.run(['bash', str(root / 'scripts/verify-ios-wire.sh')],
                            env=case, capture_output=True, text=True, timeout=10)
    assert result.returncode != 0, 'Missing current result must not reuse a previous UUID result'
print('PASS: Debug/Release policy gates, launch exit 7, missing PASS, stderr capture and WireCheck-only diagnostics preserve original exits even when log collection fails; SDK calls are fixtures.')
