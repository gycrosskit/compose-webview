package io.github.gycrosskit.composewebview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ExportObjCClass
import kotlinx.cinterop.BetaInteropApi
import platform.CoreGraphics.*
import platform.Foundation.*
import platform.AVFoundation.*
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
            for ((content, fails) in listOf(
                WebViewContent.Html("中文 <script>window.mimeExecuted=true</script>", mimeType = "text/plain") to false,
                WebViewContent.Html("中文 <script>window.mimeExecuted=true</script>") to false,
                WebViewContent.Html("Café <b>literal</b>", mimeType = "text/plain", encoding = "ISO-8859-1") to false,
                WebViewContent.Html("text", encoding = "unknown-fixture-charset") to true,
                WebViewContent.Html("中文", encoding = "US-ASCII") to true,
                WebViewContent.Html("same history", baseUrl = "https://safe.example/page", mimeType = "text/plain", historyUrl = "https://safe.example/page") to false,
                WebViewContent.Html("different history", baseUrl = "https://safe.example/page", historyUrl = "https://safe.example/other") to true,
                WebViewContent.Html("foreign history", baseUrl = "https://safe.example/page", historyUrl = "https://foreign.example/") to true,
            )) {
                val htmlState = AppWebViewState()
                val htmlRequest = WebViewRequest(content, settings = WebViewSettings(javaScriptEnabled = true))
                val htmlScope = CoroutineScope(Dispatchers.Main)
                val coordinator = IosWebViewCoordinator(htmlState, { htmlRequest }, { WebViewCallbacks() }, htmlScope, 0L)
                val html = WKWebView(root.view.bounds, WKWebViewConfiguration())
                root.view.addSubview(html)
                html.navigationDelegate = coordinator
                htmlState.attach(html); coordinator.attach(html, 0L)
                coordinator.loadWhenReady(html, htmlRequest.content)
                waitUntil("MIME/charset") { htmlState.snapshot.error != null || htmlState.snapshot.hasVisibleContent && !htmlState.snapshot.isLoading }
                assertEquals(fails, htmlState.snapshot.error != null, "invalid/lossy charset must fail")
                if (!fails) {
                    var contentType: String? = null; var body: String? = null
                    html.evaluateJavaScript("document.contentType") { value, _ -> contentType = value as? String }
                    html.evaluateJavaScript("document.body.innerText") { value, _ -> body = value as? String }
                    waitUntil("MIME/charset content") { contentType != null && body != null }
                    assertEquals(content.mimeType, contentType)
                    assertTrue(body!!.contains(if (content.mimeType == "text/html") "中文" else content.html))
                    var executed: Boolean? = null
                    html.evaluateJavaScript("window.mimeExecuted === true") { value, _ -> executed = (value as? NSNumber)?.boolValue ?: value as? Boolean }
                    waitUntil("MIME script execution") { executed != null }
                    assertEquals(content.mimeType == "text/html", executed)
                }
                coordinator.release(html); htmlState.detach(html); htmlScope.cancel(); html.removeFromSuperview()
            }
            for (enabled in listOf(false, true)) for (javascript in listOf(false, true)) {
                val zoomState = AppWebViewState()
                val zoomRequest = WebViewRequest(WebViewContent.Html("<meta name='viewport' content='width=device-width,initial-scale=1'><p style='width:1000px'>zoom</p>"), settings = WebViewSettings(supportZoom = enabled, javaScriptEnabled = javascript))
                val zoomScope = CoroutineScope(Dispatchers.Main)
                val coordinator = IosWebViewCoordinator(zoomState, { zoomRequest }, { WebViewCallbacks() }, zoomScope, 0L)
                val zoomConfiguration = platform.WebKit.WKWebViewConfiguration()
                zoomConfiguration.defaultWebpagePreferences.allowsContentJavaScript = javascript
                zoomConfiguration.ignoresViewportScaleLimits = false
                val zoom = WKWebView(root.view.bounds, zoomConfiguration)
                root.view.addSubview(zoom)
                zoom.navigationDelegate = coordinator
                zoomState.attach(zoom)
                coordinator.attach(zoom, 0L)
                coordinator.loadWhenReady(zoom, zoomRequest.content)
                waitUntil("zoom page") { zoomState.snapshot.hasVisibleContent && !zoomState.snapshot.isLoading }
                delay(200)
                var viewport: String? = null
                zoom.evaluateJavaScript("document.querySelector('meta[name=viewport]').content") { value, _ -> viewport = value as? String }
                waitUntil("viewport policy") { viewport != null }
                assertTrue(viewport!!.contains("width=device-width") && viewport!!.contains("initial-scale=1"))
                assertEquals(!enabled, viewport!!.contains("user-scalable=no"))
                assertFalse(zoom.configuration.ignoresViewportScaleLimits)
                if (!enabled) {
                    var changed = false
                    zoom.evaluateJavaScript("document.querySelector('meta[name=viewport]').content='width=device-width;initial-scale=2;viewport-fit=cover;user-scalable=yes';true") { _, _ -> changed = true }
                    waitUntil("dynamic viewport") { changed }
                    viewport = null
                    zoom.evaluateJavaScript("document.querySelector('meta[name=viewport]').content") { value, _ -> viewport = value as? String }
                    waitUntil("updated viewport") { viewport != null }
                    assertTrue(viewport!!.contains("initial-scale=2") && viewport!!.contains("viewport-fit=cover"))
                    assertTrue(viewport!!.contains("user-scalable=no") && !viewport!!.contains("user-scalable=yes"))
                }
                coordinator.release(zoom)
                zoomState.detach(zoom)
                zoomScope.cancel()
                zoom.removeFromSuperview()
            }
            val fixture = NSProcessInfo.processInfo.environment["WEBVIEW_WIRE_PAGE_URL"] as? String
            assertNotNull(fixture)
            for (policy in WebViewMixedContentPolicy.entries) for (scheme in listOf("https", "http")) {
                val mixedState = AppWebViewState()
                val imageUrl = fixture.substringBeforeLast('/') + "/mixed-image?cmp-$policy-$scheme"
                val html = "<img src='$imageUrl' onload='window.mixedResult=1' onerror='window.mixedResult=2'>"
                val mixedRequest = WebViewRequest(WebViewContent.Html(html, baseUrl = if (scheme == "http") fixture else "https://safe.example/"),
                    settings = WebViewSettings(javaScriptEnabled = true, mixedContentPolicy = policy),
                    blockedResourceRules = listOf(WebViewUrlRule.ExactHost("unrelated.example")))
                val mixedScope = CoroutineScope(Dispatchers.Main)
                val coordinator = IosWebViewCoordinator(mixedState, { mixedRequest }, { WebViewCallbacks() }, mixedScope, 0L)
                val configuration = platform.WebKit.WKWebViewConfiguration()
                configuration.defaultWebpagePreferences.allowsContentJavaScript = true
                val mixed = WKWebView(root.view.bounds, configuration)
                root.view.addSubview(mixed)
                mixed.navigationDelegate = coordinator
                mixedState.attach(mixed)
                coordinator.attach(mixed, 0L)
                coordinator.loadWhenReady(mixed, mixedRequest.content)
                var result = 0
                waitUntil("mixed image $policy/$scheme") {
                    mixed.evaluateJavaScript("window.mixedResult || 0") { value, _ -> result = (value as? NSNumber)?.intValue ?: 0 }
                    result != 0
                }
                val blocked = scheme == "https" && policy != WebViewMixedContentPolicy.ALWAYS_ALLOW
                assertEquals(if (blocked) 2 else 1, result, "Mixed content $policy under $scheme")
                coordinator.release(mixed); mixedState.detach(mixed); mixedScope.cancel(); mixed.removeFromSuperview()
            }
            for (blocked in listOf(false, true)) for (dataImage in listOf(false, true)) {
                val imageState = AppWebViewState()
                val source = if (dataImage) "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7" else fixture.substringBeforeLast('/') + "/mixed-image?cmp-images-$blocked"
                val imageRequest = WebViewRequest(WebViewContent.Html("<img src='$source' onload='window.imageResult=1' onerror='window.imageResult=2'>", baseUrl = fixture),
                    settings = WebViewSettings(javaScriptEnabled = true, mixedContentPolicy = WebViewMixedContentPolicy.ALWAYS_ALLOW, blockNetworkImage = blocked))
                val imageScope = CoroutineScope(Dispatchers.Main)
                val coordinator = IosWebViewCoordinator(imageState, { imageRequest }, { WebViewCallbacks() }, imageScope, 0L)
                val configuration = platform.WebKit.WKWebViewConfiguration()
                configuration.defaultWebpagePreferences.allowsContentJavaScript = true
                val image = WKWebView(root.view.bounds, configuration)
                root.view.addSubview(image); image.navigationDelegate = coordinator
                imageState.attach(image); coordinator.attach(image, 0L); coordinator.loadWhenReady(image, imageRequest.content)
                var result = 0
                waitUntil("network image $blocked/data=$dataImage") {
                    image.evaluateJavaScript("window.imageResult || 0") { value, _ -> result = (value as? NSNumber)?.intValue ?: 0 }
                    result != 0
                }
                assertEquals(if (blocked && !dataImage) 2 else 1, result)
                coordinator.release(image); imageState.detach(image); imageScope.cancel(); image.removeFromSuperview()
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
                if (accept == "video/mp4") controller.exportCapturedMovie(file) else controller.finishCapturedFile(file)
                waitUntil("capture completion: $accept") { completed }
                waitUntil("chooser dismissed: $accept") { root.presentedViewController == null }
                if (accept == "video/mp4") {
                    val exported = selected?.singleOrNull() as? NSURL
                    assertNotNull(exported, "MP4-only accept must receive a converted movie")
                    assertEquals("mp4", exported.pathExtension)
                    assertTrue(AVURLAsset(exported, null).tracksWithMediaType(AVMediaTypeVideo).isNotEmpty())
                    assertFalse(NSFileManager.defaultManager.fileExistsAtPath(file.path!!), "conversion must remove its MOV input")
                    controller.cancel(revokeDocument = true)
                    assertFalse(NSFileManager.defaultManager.fileExistsAtPath(exported.path!!))
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
            val cancelledMovie = NSURL.fileURLWithPath(NSTemporaryDirectory() + NSUUID().UUIDString + ".mov")
            assertTrue(NSFileManager.defaultManager.copyItemAtURL(NSBundle.mainBundle.URLForResource("capture", "mov")!!, cancelledMovie, null))
            var cancellationResults = 0
            assertTrue(controller.startRequest(false, { true }) { assertNull(it); cancellationResults++ })
            controller.exportCapturedMovie(cancelledMovie)
            controller.cancel(revokeDocument = true)
            assertEquals(1, cancellationResults)
            assertFalse(NSFileManager.defaultManager.fileExistsAtPath(cancelledMovie.path!!))
            assertTrue(controller.startRequest(false, { true }) { cancellationResults++ })
            delay(500)
            assertEquals(1, cancellationResults, "late export callback must not complete the next chooser")
            controller.cancel(revokeDocument = true)
            assertEquals(2, cancellationResults)
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
