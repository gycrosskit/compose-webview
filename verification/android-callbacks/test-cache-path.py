"""Run the real Bash CLI with isolated Gradle caches and a Java process fixture."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from tempfile import TemporaryDirectory


SOURCE = Path(__file__).resolve().parent
VERSION = '2.2.21-1.0.0'
COMPILER = ('kotlin-compiler-embeddable', 'kotlin-stdlib', 'kotlin-script-runtime')


def seed(home, version=VERSION):
    cache = home / 'caches/modules-2/files-2.1'
    for group, module, revision in [
        *(('org.jetbrains.kotlin', module, version) for module in COMPILER),
        ('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
        ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
        ('org.jetbrains', 'annotations', '13.0'),
    ]:
        jar = cache / group / module / revision / 'fixture' / f'{module}-{revision}.jar'
        jar.parent.mkdir(parents=True)
        jar.touch()


with TemporaryDirectory() as directory:
    root = Path(directory)
    scripts = root / 'repository/verification/android-callbacks'
    scripts.mkdir(parents=True)
    for file in ('verify.sh', 'create-fixture.py', 'main.kt'):
        shutil.copy2(SOURCE / file, scripts / file)
    tools = root / 'tools'
    tools.mkdir()
    (tools / 'python3').symlink_to(sys.executable)
    java = tools / 'java'
    java.write_text('''#!%s
import json, os, sys
from pathlib import Path
with Path(os.environ['MOCK_JAVA_CALLS']).open('a') as stream:
    stream.write(json.dumps(sys.argv[1:]) + '\\n')
''' % sys.executable)
    java.chmod(0o755)
    scenarios = [('override', None), ('fallback', None), ('empty-override', None),
                 ('wrong-version', None), *(('duplicate', module) for module in COMPILER)]
    for index, (mode, module) in enumerate(scenarios):
        case = root / str(index)
        home = case / 'home'
        gradle = case / 'gradle home'
        calls = case / 'java-calls.jsonl'
        case.mkdir()
        environment = dict(os.environ, PATH=str(tools) + os.pathsep + os.environ['PATH'],
                           HOME=str(home), GRADLE_USER_HOME=str(gradle), MOCK_JAVA_CALLS=str(calls))
        expected = mode in ('override', 'fallback')
        if mode == 'fallback':
            environment.pop('GRADLE_USER_HOME')
            gradle = home / '.gradle'
        if mode == 'empty-override':
            seed(home / '.gradle')
        else:
            seed(gradle, '2.2.21' if mode == 'wrong-version' else VERSION)
        if mode == 'duplicate':
            duplicate = gradle / 'caches/modules-2/files-2.1/org.jetbrains.kotlin' / module / VERSION / 'second' / f'{module}-{VERSION}.jar'
            duplicate.parent.mkdir()
            duplicate.touch()
        result = subprocess.run(['bash', str(scripts / 'verify.sh')], cwd=case, env=environment,
                                capture_output=True, text=True, timeout=10)
        assert (result.returncode == 0) == expected, (mode, module, result.stderr)
        if expected:
            invocations = [json.loads(line) for line in calls.read_text().splitlines()]
            assert len(invocations) == 2
            classpath = invocations[0][1].split(os.pathsep)
            assert all(Path(jar).is_relative_to(gradle) for jar in classpath)
            assert [Path(jar).name for jar in classpath[:3]] == [f'{name}-{VERSION}.jar' for name in COMPILER]
            assert 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler' in invocations[0]
            assert invocations[1][-1] == 'io.github.gycrosskit.composewebview.MainKt'
            assert (scripts.parents[1] / 'build/remote-library-review/android-fixture/Manifest.kt').exists()
        else:
            assert not calls.exists(), 'Invalid compiler cache must fail before Java invocation'

print('PASS: 7 real Bash CLI cache-path/version/unique-JAR checks; Java is a fixture, no SDK/compiler executed')
