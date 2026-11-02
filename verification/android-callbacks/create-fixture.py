"""媒体权限回调与权限控制器共用的最小 Android/AndroidX 系统边界。"""
from pathlib import Path
files = {
'Manifest.kt': '''package android
object Manifest { object permission { const val CAMERA="camera"; const val RECORD_AUDIO="microphone" } }
''',
'Activity.kt': '''package android.app
open class Activity
''',
'Uri.kt': '''package android.net
data class Uri(val value:String) { override fun toString()=value }
''',
'Intent.kt': '''package android.content
class Intent
''',
'WebKit.kt': '''package android.webkit
import android.net.Uri
class WebView(var url:String?="https://safe.test/page")
class PermissionRequest(val origin:Uri,val resources:Array<String>) { var grants=0;var denials=0;fun grant(value:Array<String>) {grants++};fun deny(){denials++};companion object {const val RESOURCE_VIDEO_CAPTURE="video";const val RESOURCE_AUDIO_CAPTURE="audio"} }
''',
'Result.kt': '''package androidx.activity.result
class Launcher<I>(val callback:(Any?)->Unit) { var launches=0;fun launch(input:I){launches++};fun unregister(){};fun deliver(value:Any?){callback(value)} }
class Registry {
 val launchers=mutableListOf<Launcher<*>>()
 fun <I,O> register(key:String,contract:androidx.activity.result.contract.Contract<I,O>,callback:(O)->Unit):Launcher<I> { val launcher=Launcher<I> { @Suppress("UNCHECKED_CAST") callback(it as O) };launchers.add(launcher);return launcher }
}
''',
'Contracts.kt': '''package androidx.activity.result.contract
open class Contract<I,O>
''',
'ComponentActivity.kt': '''package androidx.activity
class ComponentActivity:android.app.Activity() {
 val ui=ArrayDeque<()->Unit>();var queueUi=false
 fun runOnUiThread(action:()->Unit) { if(queueUi) ui.add(action) else action() }
 fun drain() { while(ui.isNotEmpty()) ui.removeFirst()() }
}
''',
'ContractBoundary.kt': '''package io.github.gycrosskit.composewebview
class WebViewRequest(val security:Security=Security())
class Security(var mediaCaptureEnabled:Boolean=true,val trustedOrigins:Trust=Trust())
class Trust { fun isTrusted(value:String?)=value?.startsWith("https://safe.test/")==true || value=="https://safe.test" || value=="https://trusted-frame.test" }
enum class WebPermissionPurpose { MEDIA_CAPTURE }
class AndroidWebPermissionController {
 var callback:((Boolean)->Unit)?=null;var granted=false;var pendingKey:Any?=null
 fun isGranted(value:String)=granted
 fun request(key:Any,permissions:Collection<String>,purpose:WebPermissionPurpose,stillAllowed:()->Boolean,onResult:(Boolean)->Unit) { pendingKey=key;callback=onResult }
 fun cancel(key:Any) { if(pendingKey===key) callback=null }
}
''',
}
if __name__ == '__main__':
    root=Path(__file__).resolve().parents[2]/'build/remote-library-review/android-fixture'
    root.mkdir(parents=True,exist_ok=True)
    for name,source in files.items(): (root/name).write_text(source)
