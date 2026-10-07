import { KuiklyRenderBaseView } from '@kuikly-open/render';
import { GYWebView } from './GYWebView';
import { webview } from '@kit.ArkWeb';

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
    Web({ src: '', controller: this.controller })
      .backgroundColor(Color.Transparent)
      .javaScriptAccess(this.renderView.settings().javaScriptEnabled === true)
      .domStorageAccess(this.renderView.settings().domStorageEnabled !== false)
      .fileAccess(false)
      .onlineImageAccess(this.renderView.settings().blockNetworkImage !== true)
      .imageAccess(this.renderView.settings().loadsImagesAutomatically !== false)
      .zoomAccess(this.renderView.settings().supportZoom !== false)
      .multiWindowAccess(true)
      .mixedMode(this.renderView.settings().mixedContentPolicy === 'ALWAYS_ALLOW' ? MixedMode.All :
        (this.renderView.settings().mixedContentPolicy === 'COMPATIBILITY' ? MixedMode.Compatible : MixedMode.None))
      .cacheMode(this.renderView.settings().cachePolicy === 'NO_CACHE' ? CacheMode.Online :
        (this.renderView.settings().cachePolicy === 'CACHE_ONLY' ? CacheMode.Only :
          (this.renderView.settings().cachePolicy === 'CACHE_ELSE_NETWORK' ? CacheMode.None : CacheMode.Default)))
      .mediaPlayGestureAccess(this.renderView.settings().mediaPlaybackRequiresUserGesture !== false)
      .javaScriptOnDocumentStart(this.renderView.documentStartScripts())
      .onControllerAttached(() => { if (this.live()) this.renderView.onControllerAttached(this.renderToken); })
      .onWindowNew(event => { if (this.live()) this.renderView.rejectNewWindow(event); else event.handler.setWebController(null!); })
      .onLoadIntercept(event => this.live() && event ? this.renderView.interceptNavigation(event.data) : true)
      .onInterceptRequest(event => this.live() && event ? this.renderView.interceptResource(event.request) : null!)
      .onPageBegin(event => { if (this.live()) this.renderView.onPageBegin(event?.url || ''); })
      .onPageVisible(event => { if (this.live()) this.renderView.onPageVisible(event?.url || ''); })
      .onPageEnd(event => { if (this.live()) this.renderView.onPageEnd(event?.url || ''); })
      .onProgressChange(event => { if (this.live()) this.renderView.emit({ type: 'progressChanged', progress: event?.newProgress || 0 }); })
      .onTitleReceive(event => { if (this.live()) this.renderView.emit({ type: 'titleChanged', title: event?.title || '' }); })
      .onErrorReceive(event => { if (this.live() && event?.request.isMainFrame()) this.renderView.fail('NETWORK', event.error.getErrorInfo(), event.error.getErrorCode()); })
      .onHttpErrorReceive(event => { if (this.live() && event?.request.isMainFrame()) this.renderView.fail('HTTP', 'HTTP load failed', undefined, event.response.getResponseCode()); })
      .onSslErrorEventReceive(event => { event?.handler.handleCancel(); if (this.live()) this.renderView.fail('SSL', 'TLS certificate rejected'); })
      .onRenderExited(() => { if (this.live()) this.renderView.renderExited(); })
      .onPermissionRequest(event => { if (event) { if (this.live()) this.renderView.permissionRequest(event.request); else event.request.deny(); } })
      .onShowFileSelector(event => { if (!event) return true; if (this.live()) return this.renderView.selectFile(event); event.result.handleFileList([]); return true; })
      .onFullScreenEnter(event => { if (this.live()) this.renderView.enterFullscreen(event.handler, event.videoWidth || 0, event.videoHeight || 0); else event.handler.exitFullScreen(); })
      .onFullScreenExit(() => { if (this.live()) this.renderView.nativeFullscreenExited(this.renderToken); })
      .onNavigationEntryCommitted(() => { if (this.live()) this.renderView.emitHistory(); })
      .visibility(this.renderView.visible ? Visibility.Visible : Visibility.Hidden)
      .width('100%').height('100%')
  }
}
