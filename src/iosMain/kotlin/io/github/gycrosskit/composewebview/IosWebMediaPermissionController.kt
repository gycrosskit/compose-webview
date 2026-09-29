package io.github.gycrosskit.composewebview

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVAuthorizationStatusRestricted
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.WebKit.WKMediaCaptureType
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/** 主动桥接 AVFoundation；首次拒绝只结束本次请求，后续主动使用才映射为设置页恢复事件。 */
@OptIn(ExperimentalForeignApi::class)
internal object IosWebMediaPermissionController {
    fun request(
        type: WKMediaCaptureType,
        stillAllowed: () -> Boolean,
        onResult: (Boolean, Set<WebViewPermission>) -> Unit,
    ) {
        val permissions = type.capturePermissions()
        if (permissions.isEmpty()) {
            onResult(false, emptySet())
            return
        }
        val denied = linkedSetOf<WebViewPermission>()
        var deniedDuringCurrentRequest = false

        fun requestAt(index: Int) {
            // 系统弹窗返回时页面可能已经离开，不能再发起下一个系统权限请求。
            if (!stillAllowed()) {
                onResult(false, emptySet())
                return
            }
            if (index >= permissions.size) {
                onResult(!deniedDuringCurrentRequest && denied.isEmpty(), denied)
                return
            }
            val (mediaType, permission) = permissions[index]
            when (AVCaptureDevice.authorizationStatusForMediaType(mediaType)) {
                AVAuthorizationStatusAuthorized -> requestAt(index + 1)
                AVAuthorizationStatusDenied -> {
                    denied += permission
                    requestAt(index + 1)
                }
                AVAuthorizationStatusRestricted -> onResult(false, emptySet())
                AVAuthorizationStatusNotDetermined -> {
                    AVCaptureDevice.requestAccessForMediaType(mediaType) { granted ->
                        dispatch_async(dispatch_get_main_queue()) {
                            // 本次系统弹窗刚被拒绝时不立即追弹设置引导；下次请求会从 Denied 分支恢复。
                            if (!granted) deniedDuringCurrentRequest = true
                            requestAt(index + 1)
                        }
                    }
                }
                else -> onResult(false, emptySet())
            }
        }

        requestAt(0)
    }
}

private fun WKMediaCaptureType.capturePermissions(): List<Pair<String?, WebViewPermission>> = when (this) {
    WKMediaCaptureType.WKMediaCaptureTypeCamera -> listOf(AVMediaTypeVideo to WebViewPermission.CAMERA)
    WKMediaCaptureType.WKMediaCaptureTypeMicrophone ->
        listOf(AVMediaTypeAudio to WebViewPermission.MICROPHONE)
    WKMediaCaptureType.WKMediaCaptureTypeCameraAndMicrophone -> listOf(
        AVMediaTypeVideo to WebViewPermission.CAMERA,
        AVMediaTypeAudio to WebViewPermission.MICROPHONE,
    )
    // Metadata 编译需要兜住平台枚举的未来值；未知类型不申请权限。
    else -> emptyList()
}
