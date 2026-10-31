"""Compile the production permission controller; Android UI/ActivityResult are fixtures, no SDK."""
import os
from pathlib import Path
import runpy
import subprocess
from tempfile import TemporaryDirectory

ROOT = Path(__file__).resolve().parents[2]
fixtures = runpy.run_path(str(Path(__file__).with_name('create-fixture.py')))['files']
fixtures.update({
    'Context.kt': '''package android.content
open class Context { fun getString(value:Int)="resource:$value" }
''',
    'PackageManager.kt': '''package android.content.pm
class PackageManager {
 val policyRevoked=mutableSetOf<String>()
 fun isPermissionRevokedByPolicy(permission:String,packageName:String)=permission in policyRevoked
 companion object { const val PERMISSION_GRANTED=0;const val PERMISSION_DENIED=-1 }
}
''',
    'Looper.kt': '''package android.os
object Looper { fun myLooper()=this;fun getMainLooper()=this }
''',
    'Activity.kt': '''package android.app
open class Activity:android.content.Context() { companion object { const val RESULT_OK=-1 } }
class AlertDialog {
 var positive:(()->Unit)?=null;var cancel:(()->Unit)?=null;var dismiss:(()->Unit)?=null
 fun setOnCancelListener(value:(()->Unit)?) { cancel=value }
 fun setOnDismissListener(value:(()->Unit)?) { dismiss=value }
 fun setCanceledOnTouchOutside(value:Boolean) {};fun show() { latest=this };fun dismiss() { dismiss?.invoke() }
 fun acknowledge() { positive?.invoke();dismiss() }
 fun cancelByUser() { cancel?.invoke();dismiss() }
 class Builder(val activity:androidx.activity.ComponentActivity) {
  private val dialog=AlertDialog()
  fun setTitle(value:String)=this;fun setMessage(value:String)=this
  fun setPositiveButton(value:Int,callback:(Any?,Int)->Unit)=apply { dialog.positive={callback(null,value)} }
  fun setOnCancelListener(callback:()->Unit)=apply { dialog.cancel=callback }
  fun setOnDismissListener(callback:()->Unit)=apply { dialog.dismiss=callback }
  fun create()=dialog
 }
 companion object { var latest:AlertDialog?=null }
}
''',
    'ComponentActivity.kt': '''package androidx.activity
class ComponentActivity:android.app.Activity() {
 val activityResultRegistry=androidx.activity.result.Registry()
 val packageManager=android.content.pm.PackageManager();val packageName="fixture"
 val grants=mutableSetOf<String>();val rationale=mutableSetOf<String>()
 fun shouldShowRequestPermissionRationale(permission:String)=permission in rationale
 fun runOnUiThread(action:()->Unit)=action()
}
''',
    'ContextCompat.kt': '''package androidx.core.content
object ContextCompat {
 fun checkSelfPermission(activity:androidx.activity.ComponentActivity,permission:String)=if(permission in activity.grants) 0 else -1
}
''',
    'Contracts.kt': '''package androidx.activity.result.contract
open class Contract<I,O>
object ActivityResultContracts { class RequestMultiplePermissions:Contract<Array<String>,Map<String,Boolean>>() }
''',
    'ContractBoundary.kt': '''package io.github.gycrosskit.composewebview
enum class WebViewPermission { CAMERA,MICROPHONE }
fun logWebError(message:String,error:Throwable?=null) {}
object R { object string {
 const val web_permission_acknowledge=1;const val web_permission_explanation_title=2
 const val web_permission_name_camera_microphone=3;const val web_permission_name_microphone=4;const val web_permission_name_camera=5
 const val web_permission_scenario_upload_video=6;const val web_permission_scenario_upload_photo=7
 const val web_permission_scenario_av_call=8;const val web_permission_scenario_audio_call=9;const val web_permission_scenario_video_capture=10
 const val web_permission_impact_camera_microphone=11;const val web_permission_impact_microphone=12;const val web_permission_impact_camera=13
} }
''',
})
# Permission regressions need only these shared Android/AndroidX boundary fragments.
names = ('Manifest.kt', 'Result.kt', 'Context.kt', 'PackageManager.kt', 'Looper.kt', 'Activity.kt',
         'ComponentActivity.kt', 'ContextCompat.kt', 'Contracts.kt', 'ContractBoundary.kt', 'Intent.kt', 'Uri.kt')
cache = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle'))) / 'caches/modules-2/files-2.1'
version = '2.2.21-1.0.0'


def jar(group, name):
    matches = list((cache / group / name / version).rglob(f'{name}-{version}.jar'))
    assert len(matches) == 1, (name, matches)
    return str(matches[0])


compiler = [jar('org.jetbrains.kotlin', name) for name in
            ('kotlin-compiler-embeddable', 'kotlin-stdlib', 'kotlin-script-runtime')]
for module in ('org.jetbrains.kotlin/kotlin-reflect', 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm', 'org.jetbrains/annotations'):
    compiler.append(str(next(path for path in (cache / module).rglob('*.jar')
                             if not any(suffix in path.name for suffix in ('-sources', '-javadoc', '-all')))))
with TemporaryDirectory(prefix='webview-permissions-') as directory:
    output = Path(directory)
    for name in names:
        (output / name).write_text(fixtures[name])
    sources = [str(output / name) for name in names]
    sources += [str(ROOT / 'webview-core/src/androidMain/kotlin/io/github/gycrosskit/composewebview/AndroidWebPermissionController.kt'),
                str(Path(__file__).with_name('permission-main.kt'))]
    subprocess.run(['java', '-cp', os.pathsep.join(compiler), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', compiler[1], '-d', str(output / 'classes'), *sources], check=True)
    subprocess.run(['java', '-cp', str(output / 'classes') + os.pathsep + compiler[1],
                    'io.github.gycrosskit.composewebview.Permission_mainKt'], check=True)
