package io.github.gycrosskit.composewebview

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult

fun main() {
    var cases=0
    for (photo in listOf(false,true)) for (revoke in listOf("hidden","foreign","released")) {
        val activity=ComponentActivity();val permission=AndroidWebPermissionController()
        var attached=true;val owner=WebView();val request=WebViewRequest()
        val chooser=AndroidWebFileChooserController(activity,{request},{attached},permission)
        Build.VERSION.SDK_INT=if(photo)33 else 32
        val results=mutableListOf<Array<Uri>?>()
        chooser.show(owner,ValueCallback {results.add(it)},WebChromeClient.FileChooserParams(if(photo)arrayOf("image/*") else arrayOf("application/pdf")))
        if(revoke=="hidden")attached=false
        if(revoke=="foreign")owner.url="https://foreign.test/"
        if(revoke=="released")chooser.release(owner)
        activity.activityResultRegistry.launchers[if(photo)1 else 0].deliver(if(photo)Uri("content://secret") else ActivityResult(Activity.RESULT_OK,Intent().apply{data=Uri("content://secret")}))
        check(results.size==1 && results.single()==null) {"$photo $revoke delivered old URI"}
        chooser.destroy();cases++
    }
    // 已授权结果排在 UI 队列中；导航撤销不能只依赖下一次平台权限判断。
    for(revoke in listOf("hidden","navigation","replacement")) {
        val activity=ComponentActivity();val permission=AndroidWebPermissionController();val owner=WebView()
        var attached=true;val controller=AndroidWebMediaPermissionController(activity,{WebViewRequest()},{attached},permission)
        val old=PermissionRequest(Uri("https://safe.test"),arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
        controller.request(owner,old);activity.queueUi=true
        permission.callback!!(true)
        controller.release(owner)
        if(revoke=="hidden")attached=false
        if(revoke=="navigation")owner.url="https://foreign.test/"
        if(revoke=="replacement") {
            val next=PermissionRequest(Uri("https://safe.test"),arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
            controller.request(WebView(),next)
        }
        activity.drain();check(old.grants==0 && old.denials==1) {"$revoke stale media granted"}
        controller.destroy();activity.drain();cases++
    }
    // 同时信任的 iframe 和主页面可以不同 origin，不能强制二者相等。
    run {
        val activity=ComponentActivity();val permission=AndroidWebPermissionController().apply{granted=true}
        val owner=WebView();val controller=AndroidWebMediaPermissionController(activity,{WebViewRequest()},{true},permission)
        val frame=PermissionRequest(Uri("https://trusted-frame.test"),arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
        controller.request(owner,frame)
        check(frame.grants==1&&frame.denials==0)
        controller.destroy();cases++
    }
    // 系统选择器旧结果未返回前拒绝新请求，回执到达后允许恢复同一页面。
    run {
        val activity=ComponentActivity();val owner=WebView();val chooser=AndroidWebFileChooserController(activity,{WebViewRequest()},{true},AndroidWebPermissionController())
        Build.VERSION.SDK_INT=32
        val old=mutableListOf<Array<Uri>?>();val next=mutableListOf<Array<Uri>?>()
        chooser.show(owner,ValueCallback{old.add(it)},WebChromeClient.FileChooserParams());chooser.release(owner)
        chooser.show(owner,ValueCallback{next.add(it)},WebChromeClient.FileChooserParams())
        check(old.size==1&&next.size==1&&next.single()==null)
        activity.activityResultRegistry.launchers[0].deliver(ActivityResult(Activity.RESULT_OK,Intent().apply{data=Uri("content://old")}))
        check(old.size==1&&next.size==1)
        chooser.show(owner,ValueCallback{next.add(it)},WebChromeClient.FileChooserParams())
        activity.activityResultRegistry.launchers[0].deliver(ActivityResult(Activity.RESULT_OK,Intent().apply{data=Uri("content://new")}))
        check(next.size==2&&next.last()!!.single().value=="content://new");chooser.destroy();cases++
    }
    // 授权成功后 queued launch 被隐藏撤销，不启动系统选择器。
    run {
        val activity=ComponentActivity();val owner=WebView();val permission=AndroidWebPermissionController()
        var attached=true;val chooser=AndroidWebFileChooserController(activity,{WebViewRequest()},{attached},permission)
        val results=mutableListOf<Array<Uri>?>()
        chooser.show(owner,ValueCallback{results.add(it)},WebChromeClient.FileChooserParams(arrayOf("image/*"),true))
        activity.queueUi=true;permission.callback!!(true);attached=false;chooser.release(owner);activity.drain()
        check(activity.activityResultRegistry.launchers[0].launches==0 && results.size==1 && results.single()==null)
        chooser.destroy();cases++
    }
    // queued launch 尚未打开系统页面；新请求复用同一 owner 也不能启动旧请求。
    for (photo in listOf(false, true)) for (replaceOwner in listOf(false, true)) {
        val activity=ComponentActivity();val owner=WebView()
        val chooser=AndroidWebFileChooserController(activity,{WebViewRequest()},{true},AndroidWebPermissionController())
        Build.VERSION.SDK_INT=if(photo)33 else 32
        val old=mutableListOf<Array<Uri>?>();val next=mutableListOf<Array<Uri>?>()
        val params=WebChromeClient.FileChooserParams(if(photo)arrayOf("image/*") else arrayOf("application/pdf"))
        activity.queueUi=true
        chooser.show(owner,ValueCallback{old.add(it)},params)
        chooser.release(owner)
        chooser.show(if(replaceOwner)WebView() else owner,ValueCallback{next.add(it)},params)
        activity.drain()
        val launcher=activity.activityResultRegistry.launchers[if(photo)1 else 0]
        check(launcher.launches==1 && old.size==1 && old.single()==null && next.isEmpty()) {"$photo $replaceOwner old queued launch disturbed new request"}
        launcher.deliver(if(photo)Uri("content://new") else ActivityResult(Activity.RESULT_OK,Intent().apply{data=Uri("content://new")}))
        check(next.single()!!.single().value=="content://new")
        chooser.destroy();cases++
    }
    println("PASS $cases actual production controller callback cases (system substitutes; no device)")
}
