#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
mkdir -p build/early-scripts
python3 - <<'PY'
import os,re,xml.etree.ElementTree as ET
from pathlib import Path
cache=Path(os.environ.get('GRADLE_USER_HOME',str(Path.home()/'.gradle')))/'caches/modules-2/files-2.1'
catalog=Path('gradle/libs.versions.toml').read_text()
kotlin=re.search(r'^kotlin = "([^"]+)"',catalog,re.M).group(1)
ktor=re.search(r'^ktor = "([^"]+)"',catalog,re.M).group(1)
serialization=re.search(r'^serialization = "([^"]+)"',catalog,re.M).group(1)
groups=[('org.jetbrains.kotlin','kotlin-stdlib',kotlin)]+[('io.ktor',name,ktor) for name in ('ktor-http-jvm','ktor-utils-jvm','ktor-io-jvm')]
groups += [('org.jetbrains.kotlinx', 'kotlinx-serialization-json-jvm', serialization)]
jars=[]
seen=set()
def add_runtime(group,artifact,version):
    key=(group,artifact)
    if key in seen: return
    seen.add(key)
    directory=cache/group/artifact/version
    candidates=list(directory.glob(f'*/{artifact}-{version}.jar'))
    if not candidates: raise SystemExit(f'Missing cached runtime {group}/{artifact}/{version}; compile targeted Android first')
    jars.append(str(candidates[0]))
    poms=list(directory.glob('*/*.pom'))
    if not poms: return
    ns={'m':'http://maven.apache.org/POM/4.0.0'}
    for dep in ET.parse(poms[0]).findall('./m:dependencies/m:dependency',ns):
        if dep.findtext('m:scope','compile',ns) not in ('compile','runtime'): continue
        add_runtime(*(dep.findtext('m:'+part,'',ns) for part in ('groupId','artifactId','version')))
for dependency in groups: add_runtime(*dependency)
classes=Path('webview-core/build/tmp/kotlin-classes/debug')
if not classes.exists(): raise SystemExit('Missing production Android classes; compile targeted Android first')
Path('build/early-scripts/classpath').write_text(os.pathsep.join([str(classes)]+jars))
PY
classpath="$(cat build/early-scripts/classpath)"
javac -encoding UTF-8 -cp "$classpath" -d build/early-scripts verification/early-scripts/ExportScript.java
java -cp "build/early-scripts:$classpath" ExportScript > build/early-scripts/production.js
node verification/early-scripts/verify.cjs build/early-scripts/production.js
java -cp "build/early-scripts:$classpath" ExportScript page > build/early-scripts/page.js
node verification/early-scripts/verify.cjs build/early-scripts/page.js page
java -cp "build/early-scripts:$classpath" ExportScript channels > build/early-scripts/channels.js
node verification/early-scripts/page-messages.cjs build/early-scripts/channels.js
