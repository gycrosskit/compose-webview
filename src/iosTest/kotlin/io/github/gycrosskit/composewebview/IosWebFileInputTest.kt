package io.github.gycrosskit.composewebview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ExportObjCClass
import kotlinx.cinterop.BetaInteropApi
import platform.CoreGraphics.*
import platform.Foundation.*
import platform.UIKit.*
import platform.WebKit.*
import platform.UniformTypeIdentifiers.*
import platform.darwin.*
import platform.posix.exit
import kotlinx.coroutines.*

@OptIn(ExperimentalForeignApi::class)
class IosWebFileInputTest {
    @Test fun registeredUIKitAppVerifiesProductionControllerTypes() {
        // 独立 kexe 不拥有 LaunchServices 类型注册；另一个 app 启动脚本要求专属 PASS 标记。
        if (NSBundle.mainBundle.bundleIdentifier != "io.github.gycrosskit.webview.cmp-check") return
        UIApplicationMain(0, null, null, "WebViewCmpFileCheckDelegate")
    }

    @Test fun acceptedCaptureSurvivesNextChooserCancellationUntilDocumentRevoke() {
        val controller = IosWebFileChooserController()
        var allowed = true
        var delivered: List<*>? = null
        val file = NSURL.fileURLWithPath(NSTemporaryDirectory() + NSUUID().UUIDString + ".jpg")
        try {
            // 文件来自 UIKit 的真实 JPEG 编码，控制器执行与拍摄 delegate 相同的结果入口。
            UIGraphicsBeginImageContextWithOptions(CGSizeMake(2.0, 2.0), true, 1.0)
            UIColor.redColor.setFill()
            UIRectFill(CGRectMake(0.0, 0.0, 2.0, 2.0))
            val data = UIImageJPEGRepresentation(UIGraphicsGetImageFromCurrentImageContext()!!, 0.9)!!
            UIGraphicsEndImageContext()
            assertTrue(data.writeToURL(file, true))
            val size = NSFileManager.defaultManager.attributesOfItemAtPath(file.path!!, null)?.get(NSFileSize)
            assertEquals(data.length.toLong(), (size as? Number)?.toLong(), "actual Foundation file-size value")
            assertEquals(data.length.toLong(), (size as? NSNumber)?.longLongValue, "Foundation NSNumber reads the same size")
            assertTrue(controller.startRequest(false, { allowed }) { delivered = it })
            controller.finishCapturedFile(file)
            assertEquals(listOf(file), delivered)
            assertTrue(NSData.dataWithContentsOfURL(file) != null)
            assertTrue(controller.startRequest(false, { allowed }) { delivered = it })
            controller.cancel()
            assertNull(delivered)
            assertTrue(NSData.dataWithContentsOfURL(file) != null)
            allowed = false
            controller.cancel(revokeDocument = true)
            assertFalse(NSFileManager.defaultManager.fileExistsAtPath(file.path!!))
        } finally {
            controller.cancel(revokeDocument = true)
            NSFileManager.defaultManager.removeItemAtURL(file, null)
        }
    }

    @Test fun invalidOrRevokedCaptureIsCancelledAndRemoved() {
        val controller = IosWebFileChooserController()
        val file = NSURL.fileURLWithPath(NSTemporaryDirectory() + NSUUID().UUIDString + ".jpg")
        var result: List<*>? = listOf(file)
        assertTrue(controller.startRequest(false, { true }) { result = it })
        assertTrue(NSData().writeToURL(file, true))
        controller.finishCapturedFile(file)
        assertNull(result)
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath(file.path!!))
        assertTrue(controller.startRequest(false, { true }) { result = it })
        controller.cancel(revokeDocument = true)
        assertTrue(NSData().writeToURL(file, true))
        controller.finishCapturedFile(file)
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath(file.path!!))
    }

    @Test fun directoryWithMediaExtensionIsNotAnUploadFile() {
        val controller = IosWebFileChooserController()
        val directory = NSURL.fileURLWithPath(NSTemporaryDirectory() + NSUUID().UUIDString + ".jpg")
        assertTrue(NSFileManager.defaultManager.createDirectoryAtURL(directory, true, null, null))
        var result: List<*>? = listOf(directory)
        assertTrue(controller.startRequest(false, { true }) { result = it })
        controller.finishCapturedFile(directory)
        assertNull(result)
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath(directory.path!!))
    }

    @Test fun hintsHaveBoundedTypesAndNeverAuthorizeNativeAccess() {
        assertEquals(listOf("image/jpeg", ".png") to true, parseIosFileInput("{\"accept\":\" image/jpeg, .PNG \",\"capture\":true}"))
        assertEquals(emptyList<String>() to false, parseIosFileInput("{\"accept\":\"\",\"capture\":false}"))
        for (value in listOf(null, 1, "bad", "[]", "{}", "{\"accept\":{},\"capture\":true}",
            "{\"accept\":\"image/jpeg\",\"capture\":{}}", "{\"accept\":\"image/jpeg\",\"capture\":\"true\"}",
            "{\"accept\":\"${"a".repeat(2049)}\",\"capture\":true}",
            "{\"accept\":\"${List(33) { "image/jpeg" }.joinToString(",")}\",\"capture\":true}")) assertNull(parseIosFileInput(value))
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@ExportObjCClass("WebViewCmpFileCheckDelegate")
private class CmpFileCheckDelegate : NSObject, UIApplicationDelegateProtocol {
    @OverrideInit constructor() : super()
    private var appWindow: UIWindow? = null
    override fun application(application: UIApplication, didFinishLaunchingWithOptions: Map<Any?, *>?): Boolean {
        val root = UIViewController()
        appWindow = UIWindow(frame = UIScreen.mainScreen.bounds).apply { rootViewController = root; makeKeyAndVisible() }
        CoroutineScope(Dispatchers.Main).launch {
            assertEquals(UTTypeJPEG.identifier, UTType.typeWithFilenameExtension("jpg")?.identifier)
            assertEquals(UTTypeJPEG.identifier, UTType.typeWithMIMEType("image/jpeg")?.identifier)
            assertTrue(UTTypeJPEG.conformsToType(UTTypeImage))
            assertTrue(UTTypeQuickTimeMovie.conformsToType(UTTypeMovie))
            assertFalse(UTTypeQuickTimeMovie.conformsToType(UTTypeMPEG4Movie))
            val owner = WKWebView(frame = root.view.bounds).also { root.view.addSubview(it) }
            val navigation = CmpHtmlNavigationDelegate()
            owner.navigationDelegate = navigation
            val request = WebViewRequest(WebViewContent.Url("https://safe.example"))
            val controller = IosWebFileChooserController()
            suspend fun waitUntil(phase: String, condition: () -> Boolean) {
                val until = NSDate.dateWithTimeIntervalSinceNow(10.0)
                while (!condition() && NSDate().compare(until) == NSOrderedAscending) delay(10)
                assertTrue(condition(), "UIKit/WebKit callback did not complete: $phase")
            }
            for (accept in listOf("image/*", "image/jpeg", "video/*", "video/quicktime", "video/mp4")) {
                val expectedLoad = navigation.loaded + 1
                owner.loadHTMLString("<input id='upload' type='file' accept='$accept'>", NSURL.URLWithString("https://safe.example"))
                waitUntil("HTML loaded: $accept") { navigation.loaded == expectedLoad }
                var focused = false
                var focusError: NSError? = null
                owner.evaluateJavaScript("document.getElementById('upload').focus();true") { _, error -> focusError = error; focused = true }
                waitUntil("file input focused: $accept") { focused }
                assertNull(focusError)
                var completed = false
                var selected: List<*>? = null
                controller.show(owner, false, { request }, { true }) { selected = it; completed = true }
                waitUntil("native chooser: $accept") { root.presentedViewController is UIDocumentPickerViewController || completed }
                assertFalse(completed, "Real UTType accept hint was unexpectedly rejected: $accept")
                val image = accept.startsWith("image")
                val file = NSURL.fileURLWithPath(NSTemporaryDirectory() + NSUUID().UUIDString + if (image) ".jpg" else ".mov")
                if (image) {
                    UIGraphicsBeginImageContextWithOptions(CGSizeMake(2.0, 2.0), true, 1.0)
                    val data = UIImageJPEGRepresentation(UIGraphicsGetImageFromCurrentImageContext()!!, 0.9)!!
                    UIGraphicsEndImageContext()
                    assertTrue(data.writeToURL(file, true))
                } else {
                    val source = NSBundle.mainBundle.URLForResource("capture", "mov")!!
                    assertTrue(NSFileManager.defaultManager.copyItemAtURL(source, file, null))
                }
                controller.finishCapturedFile(file)
                assertTrue(completed)
                waitUntil("chooser dismissed: $accept") { root.presentedViewController == null }
                if (accept == "video/mp4") {
                    assertNull(selected)
                    assertFalse(NSFileManager.defaultManager.fileExistsAtPath(file.path!!))
                } else {
                    assertEquals(listOf(file), selected)
                    assertTrue(NSData.dataWithContentsOfURL(file) != null)
                    assertTrue(controller.startRequest(false, { true }) {})
                    controller.cancel()
                    assertTrue(NSData.dataWithContentsOfURL(file) != null)
                    controller.cancel(revokeDocument = true)
                    assertFalse(NSFileManager.defaultManager.fileExistsAtPath(file.path!!))
                }
            }
            controller.cancel(revokeDocument = true)
            println("PASS: CMP UIKit App real UTType and production file chooser lifecycle")
            exit(0)
        }
        return true
    }
}

@OptIn(ExperimentalForeignApi::class)
private class CmpHtmlNavigationDelegate : NSObject(), WKNavigationDelegateProtocol {
    var loaded = 0
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) { loaded++ }
}
