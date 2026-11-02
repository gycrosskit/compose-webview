package io.github.gycrosskit.composewebview

import android.net.Uri
import android.webkit.*
import androidx.activity.ComponentActivity

fun main() {
    var cases=0
    // 文件选择的宿主注入与 lease 边界由 GYWebViewStopTest 使用真实 Android API 验证。
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
    check(cases == 4)
    println("PASS $cases production media permission callback cases (system substitutes; no device)")
}
