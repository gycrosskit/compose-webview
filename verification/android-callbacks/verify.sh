#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
python3 verification/android-callbacks/create-fixture.py
python3 - <<'PY'
from pathlib import Path
import os, subprocess
cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
def jar(group,name,version):
    matches=list((cache/group/name/version).rglob(name+'-'+version+'.jar'))
    assert len(matches)==1,(group,name,version,matches)
    return str(matches[0])
compiler=[jar('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.2.21-1.0.0'),jar('org.jetbrains.kotlin','kotlin-stdlib','2.2.21-1.0.0'),jar('org.jetbrains.kotlin','kotlin-script-runtime','2.2.21-1.0.0')]
def support(module):
    return str(next(p for p in (cache/module).rglob('*.jar') if not any(x in p.name for x in ('-sources','-javadoc','-all'))))
compiler+= [support('org.jetbrains.kotlin/kotlin-reflect'),support('org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm'),support('org.jetbrains/annotations')]
output=Path('build/remote-library-review/android-fixture')
sources=list(map(str,output.glob('*.kt')))+['verification/android-callbacks/main.kt']
source_root=os.environ.get('WEBVIEW_CALLBACK_SOURCE_DIR','webview-core/src/androidMain/kotlin/io/github/gycrosskit/composewebview')
for name in ['AndroidWebFileChooserController','AndroidWebMediaPermissionController']:sources.append(source_root+'/'+name+'.kt')
subprocess.run(['java','-cp',os.pathsep.join(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',compiler[1],'-d',str(output/'classes')]+sources,check=True)
subprocess.run(['java','-cp',str(output/'classes')+os.pathsep+compiler[1],'io.github.gycrosskit.composewebview.MainKt'],check=True)
PY
