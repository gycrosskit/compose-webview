"""Run workflow trust gates with fixture bytes; no Gradle, SDK or network calls."""
import hashlib
import json
import os
import shutil
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory


ROOT = Path(__file__).resolve().parents[2]


def workflow(name):
    return json.loads(subprocess.check_output([
        'ruby', '-ryaml', '-rjson', '-e',
        'puts JSON.generate(YAML.safe_load(File.read(ARGV.fetch(0))))',
        str(ROOT / '.github/workflows' / name),
    ], text=True))


def step(data, job, name):
    return next(item['run'] for item in data['jobs'][job]['steps'] if item.get('name') == name)


source = workflow('regression.yml')
release = workflow('release-validation.yml')
# New RC enables every added API probe without changing historical release conditions.
rc18 = "github.event.release.tag_name == '0.2.0-rc.18' || inputs.version == '0.2.0-rc.18'"
assert rc18 in release['env']['KUIKLY_COMPOSE_FLAGS']
assert rc18 in release['env']['FULLSCREEN_CONTROLS_FLAGS']
for job in ('release-android', 'release-native'):
    consumers = [item['run'] for item in release['jobs'][job]['steps']
                 if '-p verification-consumer' in item.get('run', '')]
    assert consumers and all('-PremoteOnly' in command for command in consumers)
    assert any('-PverifyNavigation=' in command and rc18 in command for command in consumers)
    assert any('-PverifyPageChannels=' in command and rc18 in command for command in consumers)
for name in ('Build exact remote Git Pod and pinned Renderer', 'Verify remote Pod checkout receipt'):
    item = next(item for item in release['jobs']['release-native']['steps'] if item.get('name') == name)
    assert rc18 in item['if'] and '0.2.0-rc.17' in item['if']
kuikly_link = step(release, 'release-native', 'Link new Kuikly fullscreen APIs with the real Renderer')
assert '$FULLSCREEN_CONTROLS_FLAGS' in kuikly_link and '$KUIKLY_COMPOSE_FLAGS' in kuikly_link
assert '-PsimRenderFrameworkDir=$RUNNER_TEMP/webview-release-render/Products' in kuikly_link
assert 'linkDebugFramework$ios_framework_target' in kuikly_link

with TemporaryDirectory() as directory:
    root = Path(directory)
    environment = dict(os.environ, GITHUB_EVENT_NAME='workflow_dispatch',
                       GITHUB_SHA='a' * 40, GITHUB_SERVER_URL='https://github.com',
                       GITHUB_REPOSITORY='gycrosskit/compose-webview', GITHUB_RUN_ID='123',
                       RUNNER_TEMP=str(root), VERSION='0.2.0-rc.17')

    def run(script, expected=True, **values):
        result = subprocess.run(['bash', '-euo', 'pipefail', '-c', script], cwd=root,
                                env=dict(environment, **values), capture_output=True,
                                text=True, timeout=10)
        assert (result.returncode == 0) == expected, result.stderr

    guard = step(source, 'changes', 'Reject packaging during cache warmup')
    for package, warm in [('false', 'false'), ('true', 'false'), ('false', 'true'), ('true', 'true')]:
        run(guard, not (package == warm == 'true'), PACKAGE_MAVEN=package, WARM_NATIVE_CACHE=warm)
    scope = step(source, 'changes', 'Record source scope')
    # The scope expression is substituted by Actions before this production shell runs.
    scope = scope.replace('${{ steps.scope.outputs.source }}', '$SOURCE_SCOPE')
    run(scope, PACKAGE_MAVEN='true', SOURCE_SCOPE='true')
    run(scope, False, PACKAGE_MAVEN='true', SOURCE_SCOPE='false')
    run(scope, PACKAGE_MAVEN='false', SOURCE_SCOPE='false')

    (root / 'scripts').mkdir()
    (root / 'scripts/ci-run.py').write_text('''import subprocess, sys
sys.exit(subprocess.run(sys.argv[sys.argv.index('--') + 1:]).returncode)
''')
    (root / 'scripts/package-maven.sh').write_text('''#!/usr/bin/env bash
set -euo pipefail
test "${PACKAGE_FIXTURE_FAIL:-0}" = 0
test "$1" = --init-script
mkdir -p build/maven/com/github/gycrosskit/compose-webview/compose-webview/0.2.0-rc.17
printf '%s' '{"component":{"version":"0.2.0-rc.17"}}' > build/maven/com/github/gycrosskit/compose-webview/compose-webview/0.2.0-rc.17/compose-webview-0.2.0-rc.17.module
printf 'fixture archive bytes' > build/compose-webview-maven.tar.gz
''')
    package = step(source, 'native', 'Package checked Maven archive')
    run(package)
    receipt_path = root / 'build/maven-provenance.json'
    receipt = json.loads(receipt_path.read_text())
    assert receipt['version'] == '0.2.0-rc.17'
    assert receipt['source_sha'] == 'a' * 40
    assert receipt['run_url'] == 'https://github.com/gycrosskit/compose-webview/actions/runs/123'
    assert receipt['archive_sha256'] == hashlib.sha256((root / 'build/compose-webview-maven.tar.gz').read_bytes()).hexdigest()
    receipt_path.unlink()
    run(package, False, PACKAGE_FIXTURE_FAIL='1')
    assert not receipt_path.exists(), 'Failed packaging must not emit a success receipt'

    tools = root / 'tools'
    tools.mkdir()
    git = tools / 'git'
    git.write_text('''#!/usr/bin/env bash
set -euo pipefail
case "$1" in
  ls-remote) printf "%s\\trefs/tags/%s\\n" "$MOCK_TAG_COMMIT" "$VERSION" ;;
  clone) target="${!#}"; mkdir -p "$target/ios"; cp -R "$MOCK_REFERENCE_SOURCE" "$target/ios/Sources" ;;
  -C) printf '%s\\n' "$MOCK_REFERENCE_HEAD" ;;
  *) exit 1 ;;
esac
''')
    git.chmod(0o755)
    reference = root / 'reference-native'
    reference.mkdir()
    for name in ('GYWebView.h', 'GYWebView.m', 'GYWebViewScripts.inc'):
        (reference / name).write_text('native fixture ' + name)
    environment.update(PATH=str(tools) + os.pathsep + environment['PATH'], MOCK_TAG_COMMIT='a' * 40,
                       MOCK_REFERENCE_SOURCE=str(reference), MOCK_REFERENCE_HEAD='a' * 40)
    pod_dir = root / 'webview-release-render'
    installed = pod_dir / 'Pods/GYWebView/ios/Sources'
    pod = step(release, 'release-native', 'Verify remote Pod checkout receipt')
    for tag, head, version, source_change, expected in [
        ('0.2.0-rc.17', 'a' * 40, '0.2.0-rc.17', None, True),
        ('0.2.0-rc.16', 'a' * 40, '0.2.0-rc.17', None, False),
        ('0.2.0-rc.17', 'b' * 40, '0.2.0-rc.17', None, False),
        ('0.2.0-rc.17', 'a' * 40, '0.2.0-rc.16', None, False),
        ('0.2.0-rc.17', 'a' * 40, '0.2.0-rc.17', 'changed', False),
        ('0.2.0-rc.17', 'a' * 40, '0.2.0-rc.17', 'missing', False),
        ('0.2.0-rc.17', 'a' * 40, '0.2.0-rc.17', 'extra', False),
    ]:
        shutil.rmtree(root / 'webview-release-source', ignore_errors=True)
        shutil.rmtree(installed, ignore_errors=True)
        shutil.copytree(reference, installed)
        if source_change == 'changed':
            (installed / 'GYWebView.m').write_text('different native bytes')
        elif source_change == 'missing':
            (installed / 'GYWebViewScripts.inc').unlink()
        elif source_change == 'extra':
            (installed / 'unexpected.h').touch()
        (pod_dir / 'Podfile.lock').write_text(f'''PODS:
  - GYWebView ({version}):
    - OpenKuiklyIOSRender (= 2.28.0)
CHECKOUT OPTIONS:
  GYWebView:
    :tag: {tag}
    :git: https://github.com/gycrosskit/compose-webview.git
''')
        pod_receipt = root / 'ci-diagnostics/release-pod.json'
        pod_receipt.unlink(missing_ok=True)
        run(pod, expected, MOCK_REFERENCE_HEAD=head)
        assert pod_receipt.exists() == expected, 'Wrong remote Pod identity/source must not emit a success receipt'
        if expected:
            receipt = json.loads(pod_receipt.read_text())
            assert receipt['resolved_tag_sha'] == 'a' * 40
            assert receipt['lock_tag'] == '0.2.0-rc.17'
            assert receipt['native_source_sha256'] == {
                path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in reference.iterdir()
            }

print('PASS: packaging requires source checks, rejects warmup/failure, hashes actual fixture bytes; Git/tag-only Pod lock passes; wrong tag, reference HEAD, version, native bytes or file set fail. SDK/network calls were not executed.')
