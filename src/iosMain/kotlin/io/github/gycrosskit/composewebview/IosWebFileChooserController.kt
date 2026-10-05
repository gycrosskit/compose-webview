package io.github.gycrosskit.composewebview

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import platform.Foundation.*
import platform.UIKit.*
import platform.WebKit.WKWebView
import platform.UniformTypeIdentifiers.*
import platform.darwin.NSObject

/** 公共上传拦截从 iOS18.4 才存在，旧系统不能把 DOM 限制当作原生隔离。 */
@OptIn(ExperimentalForeignApi::class)
internal fun iosSupportsControlledFileChooser(): Boolean {
    val parts = UIDevice.currentDevice.systemVersion.split('.').map { it.toIntOrNull() ?: 0 }
    return parts[0] > 18 || (parts[0] == 18 && parts.getOrElse(1) { 0 } >= 4)
}

/** 页面只提供有界的界面提示；恶意 JSON 类型不能把异常带出原生回调。 */
internal fun parseIosFileInput(value: Any?): Pair<List<String>, Boolean>? {
    val raw = value as? String ?: return null
    if (raw.length > 16384) return null
    val input = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
    val accept = input["accept"] as? JsonPrimitive ?: return null
    val capture = input["capture"] as? JsonPrimitive ?: return null
    if (!accept.isString || accept.content.length > 2048 || capture.isString || capture.booleanOrNull == null) return null
    val accepts = accept.content.lowercase().split(',').map(String::trim).filter(String::isNotEmpty)
    if (accepts.size > 32) return null
    return accepts to (capture.booleanOrNull == true)
}

/** 同一原生上传请求持有原生 picker；取消先清回执，迟到 delegate 不影响后续请求。 */
@OptIn(ExperimentalForeignApi::class)
internal class IosWebFileChooserController : NSObject(), UIDocumentPickerDelegateProtocol,
    UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {
    private var completion: ((List<*>?) -> Unit)? = null
    private var stillAllowed: () -> Boolean = { false }
    private var documentPicker: UIDocumentPickerViewController? = null
    private var capturePicker: UIImagePickerController? = null
    private var types: List<UTType> = emptyList()
    private val temporaryFiles = mutableListOf<NSURL>()
    private var revision = 0
    private var maxFiles = 1

    fun show(owner: WKWebView, multiple: Boolean, request: () -> WebViewRequest,
             allowed: () -> Boolean, result: (List<*>?) -> Unit) {
        if (!startRequest(multiple, allowed, result)) return
        val current = revision
        owner.evaluateJavaScript(IOS_FILE_INPUT_SCRIPT) { value, error ->
            if (revision != current) return@evaluateJavaScript
            if (error != null || !allowed()) { cancel(); return@evaluateJavaScript }
            val input = parseIosFileInput(value) ?: run { cancel(); return@evaluateJavaScript }
            val accepts = input.first
            types = accepts.mapNotNull {
                when {
                    it == "*/*" -> UTTypeItem
                    it == "image/*" -> UTTypeImage
                    it == "video/*" -> UTTypeMovie
                    it.startsWith('.') -> UTType.typeWithFilenameExtension(it.drop(1))
                    else -> UTType.typeWithMIMEType(it)
                }
            }
            if (types.size != accepts.size) { cancel(); return@evaluateJavaScript }
            if (types.isEmpty()) types = listOf(UTTypeItem)
            if (input.second) {
                val image = types.any { UTTypeJPEG.conformsToType(it) }
                val video = !image && types.any { UTTypeQuickTimeMovie.conformsToType(it) }
                if ((!image && !video) || !request().security.mediaCaptureEnabled ||
                    !UIImagePickerController.isSourceTypeAvailable(UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera) ||
                    NSBundle.mainBundle.objectForInfoDictionaryKey("NSCameraUsageDescription") == null ||
                    (video && NSBundle.mainBundle.objectForInfoDictionaryKey("NSMicrophoneUsageDescription") == null)) {
                    cancel(); return@evaluateJavaScript
                }
                val captureType = if (video) platform.WebKit.WKMediaCaptureType.WKMediaCaptureTypeCameraAndMicrophone else platform.WebKit.WKMediaCaptureType.WKMediaCaptureTypeCamera
                IosWebMediaPermissionController.request(captureType, { current == revision && allowed() }) { granted, _ ->
                    if (current != revision) return@request
                    if (!granted || !allowed()) { cancel(); return@request }
                    val presenter = presenter(owner) ?: run { cancel(); return@request }
                    val picker = UIImagePickerController().apply {
                        sourceType = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera
                        mediaTypes = listOf(if (video) UTTypeMovie.identifier else UTTypeImage.identifier)
                        videoMaximumDuration = 60.0
                        delegate = this@IosWebFileChooserController
                    }
                    capturePicker = picker
                    presenter.presentViewController(picker, true, null)
                }
            } else {
                val presenter = presenter(owner) ?: run { cancel(); return@evaluateJavaScript }
                val picker = UIDocumentPickerViewController(forOpeningContentTypes = types, asCopy = true).apply {
                    allowsMultipleSelection = multiple
                    delegate = this@IosWebFileChooserController
                }
                documentPicker = picker
                presenter.presentViewController(picker, true, null)
            }
        }
    }

    /** 原生选择请求只保留一个回执；开始新请求不能撤销同文档已交给 WebKit 的文件。 */
    internal fun startRequest(multiple: Boolean, allowed: () -> Boolean, result: (List<*>?) -> Unit): Boolean {
        if (completion != null) { result(null); return false }
        completion = result
        maxFiles = if (multiple) 10 else 1
        types = listOf(UTTypeItem)
        stillAllowed = allowed
        revision++
        return true
    }

    private fun presenter(owner: WKWebView): UIViewController? {
        var result = owner.window?.rootViewController
        while (result?.presentedViewController != null) result = result.presentedViewController
        return result
    }

    @ObjCSignatureOverride
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        if (documentPicker == controller) finish(didPickDocumentsAtURLs.filterIsInstance<NSURL>())
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        if (documentPicker == controller) cancel()
    }

    @ObjCSignatureOverride
    override fun imagePickerController(picker: UIImagePickerController, didFinishPickingMediaWithInfo: Map<Any?, *>) {
        if (capturePicker != picker) return
        if (!stillAllowed()) { cancel(); return }
        val video = didFinishPickingMediaWithInfo[UIImagePickerControllerMediaType] == UTTypeMovie.identifier
        val target = NSURL.fileURLWithPath(NSTemporaryDirectory() + NSUUID().UUIDString + if (video) ".mov" else ".jpg")
        val saved = if (video) {
            (didFinishPickingMediaWithInfo[UIImagePickerControllerMediaURL] as? NSURL)?.let {
                // 原始容器也必须是 MOV，不能把系统返回的其他视频改名冒充 QuickTime。
                it.pathExtension?.lowercase() == "mov" && NSFileManager.defaultManager.copyItemAtURL(it, target, null)
            } == true
        } else {
            (didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage)?.let {
                UIImageJPEGRepresentation(it, 0.9)?.writeToURL(target, true)
            } == true
        }
        if (!saved) NSFileManager.defaultManager.removeItemAtURL(target, null)
        finishCapturedFile(if (saved) target else null)
    }

    override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
        if (capturePicker == picker) cancel()
    }

    /** 拍摄文件归当前文档持有；WebKit 的上传读取可能晚于下一次 chooser。 */
    internal fun finishCapturedFile(url: NSURL?) = finish(listOfNotNull(url), captured = true)

    private fun finish(urls: List<NSURL>, captured: Boolean = false) {
        val valid = completion != null && stillAllowed() && urls.isNotEmpty() && urls.size <= maxFiles && urls.all { url ->
            val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(url.path.orEmpty(), null)
            val size = (attrs?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L
            // 自有拍摄输出由 UIKit 编码/复制确定；不再经扩展名注册表反查已知格式。
            val type = if (captured) when (url.pathExtension) {
                "jpg" -> UTTypeJPEG
                "mov" -> UTTypeQuickTimeMovie
                else -> null
            } else UTType.typeWithFilenameExtension(url.pathExtension.orEmpty())
            url.isFileURL() && attrs?.get(NSFileType) == NSFileTypeRegular && size in 1L..50L * 1024L * 1024L && type != null && types.any {
                it.identifier == UTTypeItem.identifier || type.identifier == it.identifier || type.conformsToType(it)
            }
        }
        if (captured) {
            if (valid) temporaryFiles += urls
            else urls.forEach { NSFileManager.defaultManager.removeItemAtURL(it, null) }
        }
        val result = completion
        completion = null
        revision++
        dismiss()
        result?.invoke(if (valid) urls else null)
    }

    fun cancel(revokeDocument: Boolean = false) {
        val result = completion
        completion = null
        revision++
        dismiss()
        if (revokeDocument) clearTemporaryFiles()
        result?.invoke(null)
    }

    private fun dismiss() {
        documentPicker?.delegate = null
        documentPicker?.dismissViewControllerAnimated(false, null)
        documentPicker = null
        capturePicker?.delegate = null
        capturePicker?.dismissViewControllerAnimated(false, null)
        capturePicker = null
    }

    private fun clearTemporaryFiles() {
        temporaryFiles.forEach { NSFileManager.defaultManager.removeItemAtURL(it, null) }
        temporaryFiles.clear()
    }
}
