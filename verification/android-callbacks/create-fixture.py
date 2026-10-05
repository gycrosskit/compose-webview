"""仅替换 Android/AndroidX 系统入口，测试编译并驱动两个真实生产控制器。"""
from pathlib import Path
files = {
'Manifest.kt': '''package android
object Manifest { object permission { const val CAMERA="camera"; const val RECORD_AUDIO="microphone" } }
''',
'Activity.kt': '''package android.app
open class Activity { companion object { const val RESULT_OK=-1 } }
''',
'Uri.kt': '''package android.net
data class Uri(val value:String) { override fun toString()=value }
''',
'Intent.kt': '''package android.content
import android.net.Uri
class Intent(val action:String?=null) {
 var data:Uri?=null; var clipData:ClipData?=null; var type:String?=null
 fun addCategory(value:String) {}; fun addFlags(value:Int) {}; fun putExtra(key:String,value:Any?) {}
 companion object { const val ACTION_GET_CONTENT="get";const val CATEGORY_OPENABLE="open";const val FLAG_GRANT_READ_URI_PERMISSION=1;const val FLAG_GRANT_WRITE_URI_PERMISSION=2;const val EXTRA_ALLOW_MULTIPLE="multiple";const val EXTRA_MIME_TYPES="mime" }
}
class ClipData(val values:List<Uri>) { val itemCount get()=values.size; fun getItemAt(index:Int)=Item(values[index]);data class Item(val uri:Uri) }
''',
 'ContentResolver.kt': '''package android.content
class ContentResolver { var mime="application/pdf";var size=16L;fun getType(uri:android.net.Uri)=mime;fun openFileDescriptor(uri:android.net.Uri,mode:String)=Descriptor(size) }
class Descriptor(val statSize:Long):java.io.Closeable { override fun close(){} }
''',
'Build.kt': '''package android.os
object Build { object VERSION { var SDK_INT=32 } }
object Environment { const val DIRECTORY_MOVIES="Movies";const val DIRECTORY_PICTURES="Pictures" }
''',
'MediaStore.kt': '''package android.provider
object MediaStore { const val ACTION_VIDEO_CAPTURE="video";const val ACTION_IMAGE_CAPTURE="photo";const val EXTRA_OUTPUT="output" }
''',
'WebKit.kt': '''package android.webkit
import android.net.Uri
class WebView(var url:String?="https://safe.test/page")
fun interface ValueCallback<T> { fun onReceiveValue(value:T?) }
class WebChromeClient { open class FileChooserParams(val acceptTypes:Array<String> = arrayOf("application/pdf"),val isCaptureEnabled:Boolean=false,val mode:Int=0) { companion object { const val MODE_OPEN_MULTIPLE=1 } } }
class PermissionRequest(val origin:Uri,val resources:Array<String>) { var grants=0;var denials=0;fun grant(value:Array<String>) {grants++};fun deny(){denials++};companion object {const val RESOURCE_VIDEO_CAPTURE="video";const val RESOURCE_AUDIO_CAPTURE="audio"} }
''',
'Result.kt': '''package androidx.activity.result
import android.content.Intent
class ActivityResult(val resultCode:Int,val data:Intent?)
class PickVisualMediaRequest(val mediaType:Any)
class Launcher<I>(val callback:(Any?)->Unit) { var launches=0;fun launch(input:I){launches++};fun unregister(){};fun deliver(value:Any?){callback(value)} }
class Registry {
 val launchers=mutableListOf<Launcher<*>>()
 fun <I,O> register(key:String,contract:androidx.activity.result.contract.Contract<I,O>,callback:(O)->Unit):Launcher<I> { val launcher=Launcher<I> { @Suppress("UNCHECKED_CAST") callback(it as O) };launchers.add(launcher);return launcher }
}
''',
'Contracts.kt': '''package androidx.activity.result.contract
import android.content.Intent
import android.net.Uri
import androidx.activity.result.*
open class Contract<I,O>
object ActivityResultContracts { class StartActivityForResult:Contract<Intent,ActivityResult>();class PickVisualMedia:Contract<PickVisualMediaRequest,Uri?>() { companion object { val ImageOnly=Any() } } }
''',
'ComponentActivity.kt': '''package androidx.activity
import java.io.File
import androidx.activity.result.Registry
class ComponentActivity:android.app.Activity() {
 val activityResultRegistry=Registry();val ui=ArrayDeque<()->Unit>();var queueUi=false
 val contentResolver=android.content.ContentResolver()
 val cacheDir=File("build/remote-library-review/capture");val packageName="fixture"
 fun getExternalFilesDir(value:String):File?=null
 fun runOnUiThread(action:()->Unit) { if(queueUi) ui.add(action) else action() }
 fun drain() { while(ui.isNotEmpty()) ui.removeFirst()() }
}
''',
'FileProvider.kt': '''package androidx.core.content
import java.io.File
import android.net.Uri
object FileProvider { var lastFile:File?=null;fun getUriForFile(context:Any,authority:String,file:File):Uri {lastFile=file;return Uri("content://capture")} }
''',
'ContractBoundary.kt': '''package io.github.gycrosskit.composewebview
class WebViewRequest(val security:Security=Security())
class Security(var fileChooserEnabled:Boolean=true,var mediaCaptureEnabled:Boolean=true,val trustedOrigins:Trust=Trust())
class Trust { fun isTrusted(value:String?)=value?.startsWith("https://safe.test/")==true || value=="https://safe.test" || value=="https://trusted-frame.test" }
enum class WebPermissionPurpose { FILE_CAPTURE, MEDIA_CAPTURE }
class AndroidWebPermissionController {
 var callback:((Boolean)->Unit)?=null;var granted=false;var pendingKey:Any?=null
 fun isGranted(value:String)=granted
 fun request(key:Any,permissions:Collection<String>,purpose:WebPermissionPurpose,stillAllowed:()->Boolean,onResult:(Boolean)->Unit) { pendingKey=key;callback=onResult }
 fun cancel(key:Any) { if(pendingKey===key) callback=null }
}
fun logWebWarning(message:String,error:Throwable?=null){};fun logWebError(message:String,error:Throwable?=null){}
''',
}
root=Path(__file__).resolve().parents[2]/'build/remote-library-review/android-fixture'
root.mkdir(parents=True,exist_ok=True)
for name,source in files.items(): (root/name).write_text(source)
