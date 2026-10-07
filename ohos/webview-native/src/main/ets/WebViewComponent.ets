import { KuiklyRenderBaseView } from '@kuikly-open/render';
import { GYWebView } from './GYWebView';
import { webview } from '@kit.ArkWeb';

// 两个原生 Web 分支共享现有属性和事件；只把注入 API 留在各分支，避免重复事件链。
@Extend(Web)
function webViewAttributes(view: GYWebView, token: number) {
  .backgroundColor(Color.Transparent)
  .javaScriptAccess(view.settings().javaScriptEnabled === true)
  .domStorageAccess(view.settings().domStorageEnabled !== false)
  .fileAccess(false)
  .onlineImageAccess(view.settings().blockNetworkImage !== true)
  .imageAccess(view.settings().loadsImagesAutomatically !== false)
  .zoomAccess(view.settings().supportZoom !== false)
  .multiWindowAccess(true)
  .mixedMode(view.settings().mixedContentPolicy === 'ALWAYS_ALLOW' ? MixedMode.All :
    (view.settings().mixedContentPolicy === 'COMPATIBILITY' ? MixedMode.Compatible : MixedMode.None))
  .cacheMode(view.settings().cachePolicy === 'NO_CACHE' ? CacheMode.Online :
    (view.settings().cachePolicy === 'CACHE_ONLY' ? CacheMode.Only :
      (view.settings().cachePolicy === 'CACHE_ELSE_NETWORK' ? CacheMode.None : CacheMode.Default)))
  .mediaPlayGestureAccess(view.settings().mediaPlaybackRequiresUserGesture !== false)

  .onControllerAttached(() => { if (view.isRenderActive(token)) view.onControllerAttached(token); })
  .onWindowNew(event => { if (view.isRenderActive(token)) view.rejectNewWindow(event); else event.handler.setWebController(null!); })
  .onLoadIntercept(event => view.isRenderActive(token) && event ? view.interceptNavigation(event.data) : true)
  .onInterceptRequest(event => view.isRenderActive(token) && event ? view.interceptResource(event.request) : null!)
  .onPageBegin(event => { if (view.isRenderActive(token)) view.onPageBegin(event?.url || ''); })
  .onPageVisible(event => { if (view.isRenderActive(token)) view.onPageVisible(event?.url || ''); })
  .onPageEnd(event => { if (view.isRenderActive(token)) view.onPageEnd(event?.url || ''); })
  .onProgressChange(event => { if (view.isRenderActive(token)) view.emit({ type: 'progressChanged', progress: event?.newProgress || 0 }); })
  .onTitleReceive(event => { if (view.isRenderActive(token)) view.emit({ type: 'titleChanged', title: event?.title || '' }); })
  .onErrorReceive(event => { if (view.isRenderActive(token) && event?.request.isMainFrame()) view.fail('NETWORK', event.error.getErrorInfo(), event.error.getErrorCode()); })
  .onHttpErrorReceive(event => { if (view.isRenderActive(token) && event?.request.isMainFrame()) view.fail('HTTP', 'HTTP load failed', undefined, event.response.getResponseCode()); })
  .onSslErrorEventReceive(event => { event?.handler.handleCancel(); if (view.isRenderActive(token)) view.fail('SSL', 'TLS certificate rejected'); })
  .onRenderExited(() => { if (view.isRenderActive(token)) view.renderExited(); })
  .onPermissionRequest(event => { if (event) { if (view.isRenderActive(token)) view.permissionRequest(event.request); else event.request.deny(); } })
  .onShowFileSelector(event => { if (!event) return true; if (view.isRenderActive(token)) return view.selectFile(event); event.result.handleFileList([]); return true; })
  .onFullScreenEnter(event => { if (view.isRenderActive(token)) view.enterFullscreen(event.handler, event.videoWidth || 0, event.videoHeight || 0); else event.handler.exitFullScreen(); })
  .onFullScreenExit(() => { if (view.isRenderActive(token)) view.nativeFullscreenExited(token); })
  .onNavigationEntryCommitted(() => { if (view.isRenderActive(token)) view.emitHistory(); })
  .visibility(view.visible ? Visibility.Visible : Visibility.Hidden)
  .width('100%').height('100%')
}

/** 创建与 renderToken 绑定的 ArkUI Web 节点；旧节点的事件在调用平台逻辑前拒绝。 */
@Builder
export function createGYWebView(view: KuiklyRenderBaseView) { GYWebViewHost({ renderView: view as GYWebView }) }

@Component
struct GYWebViewHost {
  @ObjectLink renderView: GYWebView;
  build() {
    Stack() {
      ForEach(this.renderView.renderTokens, (token: number) => {
        GYWebViewComponent({ renderView: this.renderView, renderToken: token, controller: this.renderView.controller })
      }, (token: number) => token.toString())
    }.width('100%').height('100%')
  }
}

@Component
struct GYWebViewComponent {
  @ObjectLink renderView: GYWebView;
  renderToken: number = 0;
  controller: webview.WebviewController = new webview.WebviewController();
  private live(): boolean { return this.renderView.isRenderActive(this.renderToken); }
  build() {
    if (this.live()) {
      if (this.renderView.request?.pageMessageChannels?.length) {
        Web({ src: '', controller: this.controller })
          .javaScriptProxy({ object: this.renderView, name: 'GYWebViewPageMessageNative',
            methodList: ['pageMessageBootstrap', 'pageMessagePost', 'pageMessageRevoke'], controller: this.controller })
          .runJavaScriptOnDocumentStart(this.renderView.documentStartScripts())
          .webViewAttributes(this.renderView, this.renderToken)
      } else {
        Web({ src: '', controller: this.controller })
          .javaScriptOnDocumentStart(this.renderView.documentStartScripts())
          .webViewAttributes(this.renderView, this.renderToken)
      }
    }
  }
}
