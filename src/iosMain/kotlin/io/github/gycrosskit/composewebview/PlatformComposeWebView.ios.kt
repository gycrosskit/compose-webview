package io.github.gycrosskit.composewebview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.Foundation.NSURLErrorDomain
import platform.UIKit.UIColor
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKFrameInfo
import platform.WebKit.WKAudiovisualMediaTypeAll
import platform.WebKit.WKAudiovisualMediaTypeNone
import platform.WebKit.WKContentRuleList
import platform.WebKit.WKContentRuleListStore
import platform.WebKit.WKMediaCaptureType
import platform.WebKit.WKPermissionDecision
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKSecurityOrigin
import platform.WebKit.WKUIDelegateProtocol
import platform.WebKit.WKOpenPanelParameters
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject
import kotlin.time.TimeSource

/** iOS 在 WKWebView 内绘制原生进度，避免 Compose 与 UIKit 互操作层留下最后一帧。 */
actual val platformWebViewRendersLoadingProgress: Boolean = true

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun PlatformAppWebView(
    request: WebViewRequest,
    modifier: Modifier,
    state: AppWebViewState,
    callbacks: WebViewCallbacks,
    pageEnteredAtMillis: Long,
    visible: Boolean,
) {
    if (request.security.fileChooserEnabled && !iosSupportsControlledFileChooser()) {
        DisposableEffect(request) {
            callbacks.onEvent(WebViewEvent.CapabilityUnsupported(WebViewCapability.FILE_CHOOSER))
            callbacks.onEvent(WebViewEvent.LoadFailed(WebViewLoadError(WebViewErrorKind.LOAD_EXCEPTION, "Controlled file chooser requires iOS 18.4")))
            onDispose {}
        }
        return
    }
    val initialNavigation = request.content.initialOrigin()?.let { WebViewNavigationRequest(it, true, false) }
    if (initialNavigation != null && !request.allowsNavigation(initialNavigation)) {
        DisposableEffect(request) {
            callbacks.onEvent(WebViewEvent.Navigation(initialNavigation, true))
            onDispose {}
        }
        return
    }
    val currentRequest by rememberUpdatedState(request)
    val currentCallbacks by rememberUpdatedState(callbacks)
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    key(request.settings, request.security, request.scripts, request.blockedResourceRules, request.navigationPolicy) {
        val coordinator = remember {
            IosWebViewCoordinator(
                state = state,
                request = { currentRequest },
                callbacks = { currentCallbacks },
                scope = scope,
                pageEnteredAtMillis = pageEnteredAtMillis,
            )
        }
        DisposableEffect(lifecycle, coordinator) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> coordinator.setMediaSuspended(false)
                    Lifecycle.Event.ON_PAUSE -> coordinator.setMediaSuspended(true)
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                coordinator.setMediaSuspended(true)
            }
        }
        UIKitView(
            factory = {
                val userContentController = WKUserContentController()
                val configuration = WKWebViewConfiguration().apply {
                    this.userContentController = userContentController
                    defaultWebpagePreferences.allowsContentJavaScript =
                        currentRequest.settings.javaScriptEnabled
                    preferences.javaScriptCanOpenWindowsAutomatically =
                        currentRequest.settings.javaScriptCanOpenWindowsAutomatically
                    mediaTypesRequiringUserActionForPlayback =
                        if (currentRequest.settings.mediaPlaybackRequiresUserGesture) {
                            WKAudiovisualMediaTypeAll
                        } else {
                            WKAudiovisualMediaTypeNone
                        }
                    allowsInlineMediaPlayback = true
                    allowsPictureInPictureMediaPlayback = true
                    preferences.elementFullscreenEnabled = true
                    applicationNameForUserAgent = currentRequest.settings.userAgentSuffix
                        ?.takeUnless(String::isBlank)
                }
                val creationMark = TimeSource.Monotonic.markNow()
                WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = configuration).apply {
                    opaque = false
                    backgroundColor = UIColor.clearColor
                    scrollView.backgroundColor = UIColor.clearColor
                    navigationDelegate = coordinator
                    UIDelegate = coordinator
                    if (currentRequest.canUseAppBridgeAt(currentRequest.content.initialOrigin())) {
                        userContentController.addScriptMessageHandler(coordinator, APP_BRIDGE_HANDLER)
                    }
                    userContentController.addScriptMessageHandler(coordinator, WEB_EVENT_HANDLER)
                    state.attach(this)
                    coordinator.attach(this, creationMark.elapsedNow().inWholeMilliseconds)
                    coordinator.installContentRules(this)
                }
            },
            modifier = modifier,
            update = { target ->
                coordinator.setVisible(target, visible)
                coordinator.loadWhenReady(target, request.content)
            },
            onRelease = { target ->
                target.stopLoading()
                coordinator.release(target)
                target.navigationDelegate = null
                target.UIDelegate = null
                target.configuration.userContentController
                    .removeScriptMessageHandlerForName(APP_BRIDGE_HANDLER)
                target.configuration.userContentController
                    .removeScriptMessageHandlerForName(WEB_EVENT_HANDLER)
                state.detach(target)
            },
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosWebViewCoordinator(
    private val state: AppWebViewState,
    private val request: () -> WebViewRequest,
    private val callbacks: () -> WebViewCallbacks,
    private val scope: CoroutineScope,
    private val pageEnteredAtMillis: Long,
) : NSObject(), WKNavigationDelegateProtocol, WKScriptMessageHandlerProtocol, WKUIDelegateProtocol {
    private val progress = IosWebViewProgressController(state, callbacks, scope)
    private var fullscreen = false
    private var visibleForNavigation = false
    private var contentRulesReady = request().blockedResourceRules.isEmpty()
    private var performanceTrace: WebViewPerformanceTrace? = null
    private var released = false
    private var navigationGeneration = 0
    private var documentToken = NSUUID().UUIDString
    private val fileChooser = IosWebFileChooserController()
    private var initialHtmlNavigation = false
    private var mediaTarget: WKWebView? = null
    private var mediaSuspended = true
    private var wasPaused = false
    private var resumedAfterPause = false

    private fun emit(event: WebViewEvent) {
        val owner = mediaTarget ?: return
        if (!released && state.isAttached(owner)) callbacks().onEvent(event)
    }

    fun attach(webView: WKWebView, creationDurationMillis: Long) {
        state.javascriptAllowed = { request().canEvaluateJavascriptAt(it.URL?.absoluteString) }
        mediaTarget = webView
        progress.attach(webView)
        performanceTrace = WebViewPerformanceTrace(
            platform = "ios",
            instanceId = webView.hashCode().toString(16),
            pageEnteredAtMillis = pageEnteredAtMillis,
        ).also { it.created(creationDurationMillis) }
        suspendMedia(webView, mediaSuspended, "attach")
        prepareDocumentScripts(webView)
    }

    fun setVisible(webView: WKWebView, visible: Boolean) {
        if (visible == webView.hidden) {
            state.invalidateJavascriptCallbacks()
            navigationGeneration++
            documentToken = NSUUID().UUIDString
            fileChooser.cancel(revokeDocument = true)
            webView.evaluateJavaScript(transportScript(visible), null)
        }
        webView.hidden = !visible
    }

    private fun transportScript(visible: Boolean): String =
        "if((window.__GY_WEBVIEW_TRANSPORT_REVISION__||0)<=$navigationGeneration){window.__GY_WEBVIEW_TRANSPORT_REVISION__=$navigationGeneration;" +
            "window.__GY_WEBVIEW_BRIDGE_TRANSPORT__={postMessage:function(value){if($visible)window.webkit.messageHandlers.JSAndroidBridge.postMessage({token:'$documentToken',value:value});}};" +
            "window.__GY_WEBVIEW_EVENT_TRANSPORT__={postMessage:function(value){window.webkit.messageHandlers.ComposeWebViewEvent.postMessage({token:'$documentToken',value:value});}};}"

    private fun prepareDocumentScripts(webView: WKWebView) {
        documentToken = NSUUID().UUIDString
        val controller = webView.configuration.userContentController
        controller.removeAllUserScripts()
        fun add(source: String, mainFrame: Boolean = true) {
            controller.addUserScript(WKUserScript(source, WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart, mainFrame))
        }
        add(transportScript(!webView.hidden))
        add(IOS_FILE_CHOOSER_GATE_SCRIPT, false)
        val current = request()
        if (current.settings.javaScriptEnabled) {
            add(WEB_VIEW_PERFORMANCE_SCRIPT.replace("window.webkit.messageHandlers.ComposeWebViewEvent", "window.__GY_WEBVIEW_EVENT_TRANSPORT__"))
            add(IOS_WEB_EVENT_SCRIPT.replace("window.webkit.messageHandlers.ComposeWebViewEvent", "window.__GY_WEBVIEW_EVENT_TRANSPORT__"))
            current.earlyScriptSource()?.let { add(it) }
            if (current.canUseAppBridgeAt(current.content.initialOrigin())) add(IOS_BRIDGE_SCRIPT.replace("window.webkit.messageHandlers.JSAndroidBridge", "window.__GY_WEBVIEW_BRIDGE_TRANSPORT__"))
        }
    }

    fun setMediaSuspended(suspended: Boolean) {
        mediaSuspended = suspended
        if (released) return
        if (suspended) {
            wasPaused = true
            resumedAfterPause = false
        } else if (wasPaused) {
            resumedAfterPause = true
            state.retryInitialNetworkFailure()
        }
        mediaTarget?.let { suspendMedia(it, suspended, "lifecycle") }
    }

    private fun suspendMedia(webView: WKWebView, suspended: Boolean, reason: String) {
        val trace = performanceTrace
        trace?.mediaSuspension(suspended, reason, completed = false)
        // stopLoading 只停止资源请求；原生停媒同时覆盖 video/audio、跨域 iframe 和 PiP。
        webView.setAllMediaPlaybackSuspended(suspended) {
            trace?.mediaSuspension(suspended, reason, completed = true)
        }
    }

    fun installContentRules(webView: WKWebView) {
        val rules = request().blockedResourceRules
        if (rules.isEmpty()) return
        val encodedRules = rules.toWebKitContentRuleList()
        val identifier = "compose-webview-resource-rules-${encodedRules.hashCode().toUInt()}"
        WKContentRuleListStore.defaultStore()!!.compileContentRuleListForIdentifier(
            identifier = identifier,
            encodedContentRuleList = encodedRules,
        ) { ruleList: WKContentRuleList?, _: NSError? ->
            scope.launch {
                if (released || !state.isAttached(webView)) return@launch
                if (ruleList != null) {
                    webView.configuration.userContentController.addContentRuleList(ruleList)
                }
                // 编译失败时仍放行页面加载；Android 也不会因单条过滤规则异常阻断主页面。
                contentRulesReady = true
                val current = request()
                state.loadIfChanged(
                    webView,
                    current.content,
                    current.settings.cachePolicy,
                ) {
                    initialHtmlNavigation = current.content is WebViewContent.Html
                    performanceTrace?.load(current.content)
                    progress.show(0)
                    progress.observe(webView, restart = true)
                }
            }
        }
    }

    fun loadWhenReady(webView: WKWebView, content: WebViewContent) {
        if (contentRulesReady) {
            state.loadIfChanged(webView, content, request().settings.cachePolicy) {
                initialHtmlNavigation = content is WebViewContent.Html
                performanceTrace?.load(content)
                progress.show(0)
                progress.observe(webView, restart = true)
            }
        }
    }

    fun release(webView: WKWebView) {
        released = true
        navigationGeneration++
        fileChooser.cancel(revokeDocument = true)
        suspendMedia(webView, true, "release")
        webView.closeAllMediaPresentationsWithCompletionHandler(null)
        mediaTarget = null
        progress.release()
        updateFullscreen(false)
        performanceTrace?.released(webView.URL?.absoluteString)
        performanceTrace = null
    }

    /**
     * WKWebView 默认会提示音视频权限；这里先执行与 Android 相同的能力开关和 HTTPS 来源校验。
     */
    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        requestMediaCapturePermissionForOrigin: WKSecurityOrigin,
        initiatedByFrame: WKFrameInfo,
        type: WKMediaCaptureType,
        decisionHandler: (WKPermissionDecision) -> Unit,
    ) {
        val sourceUrl = initiatedByFrame.request.URL?.absoluteString
        val generation = navigationGeneration
        val stillAllowed = {
            !released && !webView.hidden && generation == navigationGeneration && state.isAttached(webView) &&
                request().security.run { mediaCaptureEnabled && trustedOrigins.isTrusted(sourceUrl) }
        }
        if (!stillAllowed()) {
            decisionHandler(WKPermissionDecision.WKPermissionDecisionDeny)
            return
        }
        IosWebMediaPermissionController.request(type, stillAllowed) { granted, deniedPermissions ->
            val active = stillAllowed()
            decisionHandler(
                if (active && granted) {
                    WKPermissionDecision.WKPermissionDecisionGrant
                } else {
                    WKPermissionDecision.WKPermissionDecisionDeny
                },
            )
            if (active && deniedPermissions.isNotEmpty()) {
                emit(WebViewEvent.PermissionSettingsRequired(deniedPermissions))
            }
        }
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didStartProvisionalNavigation: WKNavigation?) {
        if (released || !state.isAttached(webView)) return
        initialHtmlNavigation = false
        navigationGeneration++
        fileChooser.cancel(revokeDocument = true)
        visibleForNavigation = false
        performanceTrace?.pageStarted(webView.URL?.absoluteString)
        state.pageStarted(webView)
        emit(WebViewEvent.PageStarted(webView.URL?.absoluteString))
        emit(WebViewEvent.ProgressChanged(0))
        progress.show(0)
        progress.observe(webView, restart = false)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didCommitNavigation: WKNavigation?) {
        if (released || !state.isAttached(webView)) return
        state.navigationCommitted(webView)
        request().earlyScriptSource()?.let { webView.evaluateJavaScript(it, null) }
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        if (released || !state.isAttached(webView)) return
        progress.stop()
        progress.hide()
        state.pageFinished(webView)
        val url = webView.URL?.absoluteString
        publishFirstVisible(webView, url)
        performanceTrace?.pageFinished(url)
        emit(WebViewEvent.TitleChanged(webView.title))
        emit(WebViewEvent.ProgressChanged(100))
        emit(WebViewEvent.PageFinished(url))
        if (released || !state.isAttached(webView)) return
        val current = request()
        val fileChooserAllowed = iosSupportsControlledFileChooser() && current.security.fileChooserEnabled &&
            current.security.trustedOrigins.isTrusted(url)
        webView.evaluateJavaScript(
            "window.$FILE_CHOOSER_ALLOWED_FLAG = ${fileChooserAllowed.toString()};",
            null,
        )
        if (current.settings.javaScriptEnabled && current.canUseAppBridgeAt(url)) {
            webView.evaluateJavaScript(IOS_BRIDGE_SCRIPT.replace("window.webkit.messageHandlers.JSAndroidBridge", "window.__GY_WEBVIEW_BRIDGE_TRANSPORT__"), null)
        }
        // didCommit 是正常提前路径；完成回调仍为旧系统或特殊导航提供幂等兜底。
        current.earlyScriptSource()?.let { webView.evaluateJavaScript(it, null) }
        current.finishedScriptsAt(url).forEach { script -> webView.evaluateJavaScript(script.source, null) }
    }

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didFailProvisionalNavigation: WKNavigation?,
        withError: NSError,
    ) = reportFailure(webView, withError)

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didFailNavigation: WKNavigation?,
        withError: NSError,
    ) = reportFailure(webView, withError)

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        if (released || !state.isAttached(webView)) {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
            return
        }
        val url = decidePolicyForNavigationAction.request.URL?.absoluteString.orEmpty()
        val isMainFrame = decidePolicyForNavigationAction.targetFrame?.mainFrame != false
        val target = if (decidePolicyForNavigationAction.targetFrame == null) {
            WebViewNavigationTarget.NEW_WINDOW
        } else {
            WebViewNavigationTarget.CURRENT_WINDOW
        }
        val current = request()
        val navigation = WebViewNavigationRequest(url, isMainFrame, false, target)
        val internalHtmlLoad = initialHtmlNavigation && navigation.isInternalHtmlInitialNavigation() &&
            decidePolicyForNavigationAction.navigationType == platform.WebKit.WKNavigationTypeOther
        if (isMainFrame) initialHtmlNavigation = false
        val blockedByTrust = !internalHtmlLoad && !current.allowsNavigation(navigation)
        val generation = navigationGeneration
        fun decide(hasUserGesture: Boolean) {
            val decision = completeIosWebNavigation(
                navigation = WebViewNavigationRequest(url, isMainFrame, hasUserGesture, target),
                blockedByTrust = blockedByTrust,
                isActive = { !released && state.isAttached(webView) && generation == navigationGeneration },
                route = { value ->
                    callbacks().onNavigationRequest(value).also { decision ->
                        emit(WebViewEvent.Navigation(value, blockedByTrust || decision == WebViewNavigationDecision.BLOCK))
                    }
                },
                loadPopup = { webView.loadRequest(decidePolicyForNavigationAction.request) },
                onBlocked = {
                    progress.stop()
                    progress.hide()
                    state.navigationCancelled(webView)
                },
                onAllowedMainFrame = {
                    navigationGeneration++
                    fileChooser.cancel(revokeDocument = true)
                    prepareDocumentScripts(webView)
                },
            )
            decisionHandler(
                if (decision == WebViewNavigationDecision.ALLOW) WKNavigationActionPolicy.WKNavigationActionPolicyAllow
                else WKNavigationActionPolicy.WKNavigationActionPolicyCancel,
            )
        }
        if (blockedByTrust || internalHtmlLoad || !current.settings.javaScriptEnabled) decide(false)
        else webView.evaluateJavaScript(IOS_HAS_RECENT_USER_GESTURE_SCRIPT) { value, _ ->
            decide((value as? NSNumber)?.boolValue ?: (value as? Boolean ?: false))
        }
    }

    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage,
    ) {
        val owner = didReceiveScriptMessage.webView ?: return
        if (released || !state.isAttached(owner) || owner.configuration.userContentController != userContentController) return
        val envelope = didReceiveScriptMessage.body as? Map<*, *> ?: return
        if (envelope["token"] != documentToken) return
        val payload = envelope["value"] as? String ?: return
        if (didReceiveScriptMessage.name == WEB_EVENT_HANDLER) {
            val raw = payload
            parseWebViewPerformanceMessage(raw)?.let { (metric, duration) ->
                if (didReceiveScriptMessage.frameInfo.mainFrame) {
                    performanceTrace?.performanceMetric(metric, duration)
                    emit(WebViewEvent.PerformanceMetric(metric, duration))
                    if (metric == WebViewPerformanceMetric.FIRST_CONTENT_VISIBLE) {
                        val sourceWebView = didReceiveScriptMessage.webView ?: return
                        publishFirstVisible(
                            sourceWebView,
                            didReceiveScriptMessage.frameInfo.request.URL?.absoluteString,
                        )
                    }
                }
                return
            }
            when (raw) {
                WEB_EVENT_FULLSCREEN_ENTER -> updateFullscreen(true)
                WEB_EVENT_FULLSCREEN_EXIT -> updateFullscreen(false)
            }
            return
        }
        val url = didReceiveScriptMessage.frameInfo.request.URL?.absoluteString
        val current = request()
        if (owner.hidden || didReceiveScriptMessage.name != APP_BRIDGE_HANDLER) return
        if (!current.canReceiveAppBridgeMessage(url, didReceiveScriptMessage.frameInfo.mainFrame)) return
        if (!current.canUseAppBridgeAt(owner.URL?.absoluteString)) return
        val raw = payload
        val message = parseAppWebBridgeMessage(raw) ?: return
        emit(WebViewEvent.BridgeMessage(message))
    }

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        runOpenPanelWithParameters: WKOpenPanelParameters,
        initiatedByFrame: WKFrameInfo,
        completionHandler: (List<*>?) -> Unit,
    ) {
        val generation = navigationGeneration
        val origin = WebViewTrustPolicy(listOfNotNull(webView.URL?.absoluteString))
        val frameOrigin = initiatedByFrame.securityOrigin.let { source ->
            val host = source.host.let { if (it.contains(':') && !it.startsWith('[')) "[$it]" else it }
            "${source.protocol}://$host:${source.port.takeIf { it > 0 } ?: if (source.protocol == "https") 443 else 80}"
        }
        val allowed = {
            iosSupportsControlledFileChooser() && !released && !webView.hidden && state.isAttached(webView) &&
                generation == navigationGeneration && initiatedByFrame.mainFrame &&
                request().security.fileChooserEnabled && request().security.trustedOrigins.isTrusted(webView.URL?.absoluteString) &&
                origin.isTrusted(frameOrigin) && origin.isTrusted(initiatedByFrame.request.URL?.absoluteString) && origin.isTrusted(webView.URL?.absoluteString)
        }
        if (!allowed() || runOpenPanelWithParameters.allowsDirectories) { completionHandler(null); return }
        fileChooser.show(webView, runOpenPanelWithParameters.allowsMultipleSelection, request, allowed, completionHandler)
    }

    private fun updateFullscreen(value: Boolean) {
        if (fullscreen == value) return
        fullscreen = value
        emit(WebViewEvent.FullscreenChanged(value))
    }

    private fun publishFirstVisible(webView: WKWebView, url: String?) {
        if (visibleForNavigation) return
        if (!state.pageCommitted(webView)) return
        visibleForNavigation = true
        performanceTrace?.firstContentVisible(url)
        emit(WebViewEvent.FirstContentVisible(url))
    }

    private fun reportFailure(webView: WKWebView, error: NSError) {
        if (released || !state.isAttached(webView)) return
        progress.stop()
        progress.hide()
        if (error.domain == NSURLErrorDomain && error.code.toInt() == -999) {
            state.navigationCancelled(webView)
            return
        }
        val loadError = WebViewLoadError(
            kind = if (error.domain == NSURLErrorDomain) WebViewErrorKind.NETWORK else WebViewErrorKind.UNKNOWN,
            message = error.localizedDescription,
            url = webView.URL?.absoluteString,
            errorCode = error.code.toInt(),
        )
        state.loadFailed(webView, loadError)
        performanceTrace?.pageFailed(loadError)
        AppWebViewRuntime.log(AppWebViewLogLevel.WARNING, "ios-navigation-failed domain=${error.domain} code=${error.code}")
        emit(WebViewEvent.LoadFailed(loadError))
        // 系统弹窗消失与失败回调可能乱序；仅补偿尚未提交的首个 GET，状态层限制次数及错误码。
        if (resumedAfterPause && !mediaSuspended) state.retryInitialNetworkFailure()
    }

}
