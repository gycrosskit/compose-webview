import { KRAny, KuiklyRenderBaseView, KuiklyRenderCallback } from '@kuikly-open/render';
import { ComponentContent } from '@ohos.arkui.node';
import { webview } from '@kit.ArkWeb';
import { url, util } from '@kit.ArkTS';
import { window } from '@kit.ArkUI';
import { common } from '@kit.AbilityKit';
import { FullscreenWindowLease, FullscreenWindowPolicy, WindowPolicyController } from '@gycrosskit/system-actions-native';
import { abilityAccessCtrl, Permissions } from '@kit.AbilityKit';
import { picker } from '@kit.CoreFileKit';
import { CallbackLifetime, decodeRequest, guardedScript, matches, navigationAllowed, origin, trusted, WebViewRequest, WebViewSettings } from './WebViewWire';
import { createGYWebView } from './WebViewComponent';

interface EvalParams { script: string; }
interface BridgeMessage { handlerName: string; data: string; }
export interface WebEvent {
  type: string; url?: string; title?: string; progress?: number; canGoBack?: boolean; canGoForward?: boolean;
  handlerName?: string; data?: string; isFullscreen?: boolean; kind?: string; message?: string;
  errorCode?: number; httpStatus?: number; isMainFrame?: boolean; hasUserGesture?: boolean;
  target?: string; blocked?: boolean; permissions?: string[]; capability?: string;
}

// 端口只由原生发送给主文档；不使用注册到所有 iframe 的 JavaScriptProxy。
const BRIDGE_BOOTSTRAP: string = `(function(){
if(window!==window.top||window.GYWebViewBridge)return;
let port=null,queue=[],bytes=0;
const bridge=Object.freeze({postMessage:function(handlerName,data){
 if(typeof handlerName!=='string'||!handlerName||handlerName.length>80)return false;
 let raw,size;try{const payload=typeof data==='string'?data:JSON.stringify(data==null?{}:data);if(typeof payload!=='string')return false;raw=JSON.stringify({handlerName:handlerName,data:payload});size=new TextEncoder().encode(raw).byteLength}catch(e){return false}
 if(size>65536)return false;
 if(port){try{port.postMessage(raw);return true}catch(e){return false}}
 if(queue.length>=32||bytes+size>65536)return false;queue.push(raw);bytes+=size;return true;
}});
Object.defineProperty(window,'GYWebViewBridge',{value:bridge,configurable:false,writable:false});
if(!window.JSAndroidBridge)Object.defineProperty(window,'JSAndroidBridge',{value:Object.freeze({handleJSBridgeMessage:bridge.postMessage}),writable:false});
if(!window.WebViewJavascriptBridge)Object.defineProperty(window,'WebViewJavascriptBridge',{value:Object.freeze({callHandler:bridge.postMessage,registerHandler:function(){}}),writable:false});
window.addEventListener('message',function(event){
 if(!event.isTrusted||event.source!==null||event.data!=='GYWebViewChannel'||!event.ports||event.ports.length!==1)return;
 if(port)port.close();port=event.ports[0];const pending=queue;queue=[];bytes=0;
 pending.forEach(function(raw){try{port.postMessage(raw)}catch(e){}});
});
window.addEventListener('pagehide',function(){if(port)port.close();port=null;queue=[];bytes=0});
})();`;

@Observed
export class GYWebView extends KuiklyRenderBaseView {
  static readonly VIEW_NAME: string = 'GYWebView';
  controller: webview.WebviewController = new webview.WebviewController();
  request: WebViewRequest | null = null;
  visible: boolean = true;
  renderToken: number = 0;
  renderTokens: number[] = [0];
  private documentReady: boolean = false;
  private renderFailed: boolean = false;
  private eventCallback: KuiklyRenderCallback | null = null;
  private attached: boolean = false;
  private lifetime: CallbackLifetime = new CallbackLifetime();
  private pendingHtmlNavigation: boolean = false;
  private loadingRequest: boolean = false;
  private loadSubmitted: boolean = false;
  private ports: webview.WebMessagePort[] = [];
  private permissionRequests: Set<PermissionRequest> = new Set();
  private fileResults: Set<FileSelectorResult> = new Set();
  private fullscreenHandler: FullScreenExitHandler | null = null;
  private fullscreenLease: FullscreenWindowLease | null = null;
  private fullscreenRevision: number = 0;
  // ArkWeb exit 事件没有 handler/id；主动退出确认前不接收同一 render 的下一次全屏。
  private nativeExitPending: boolean = false;
  private windowTask: Promise<void> = Promise.resolve();

  settings(): WebViewSettings { return this.request?.settings || {}; }

  setProp(key: string, value: KRAny | KuiklyRenderCallback): boolean {
    if (this.lifetime.disposed) return true;
    if (key === 'onEvent') { this.eventCallback = value as KuiklyRenderCallback; return true; }
    if (key === 'visible') {
      this.visible = value === true;
      this.revokePending();
      if (this.attached) {
        try {
          if (this.visible) { this.controller.onActive(); this.installBridge(); }
          else { this.exitFullscreen(); this.controller.stopAllMedia(); this.controller.onInactive(); }
        } catch (_) { this.fail('LOAD_EXCEPTION', 'ArkWeb visibility update failed'); }
      }
      return true;
    }
    if (key !== 'request') return super.setProp(key, value);
    this.revokePending();
    this.exitFullscreen();
    if (this.attached) { try { this.controller.stop(); this.controller.stopAllMedia(); } catch (_) {} }
    this.attached = false;
    this.renderFailed = false;
    this.documentReady = false;
    this.controller = new webview.WebviewController();
    this.request = null;
    this.pendingHtmlNavigation = false;
    this.loadingRequest = false;
    this.loadSubmitted = false;
    try {
      if (typeof value !== 'string') throw new Error('request must be a JSON string');
      this.request = decodeRequest(value as string);
    } catch (_) { this.fail('LOAD_EXCEPTION', 'Invalid or unsupported WebViewRequest'); }
    // 新请求拥有独立 Controller / Web 节点。旧文档的同源迟到事件也不能再次授予 Bridge。
    this.renderToken++;
    this.nativeExitPending = false;
    this.renderTokens = [this.renderToken];
    return true;
  }

  createArkUIView(): ComponentContent<KuiklyRenderBaseView> {
    return new ComponentContent<KuiklyRenderBaseView>(this.getUIContext() as UIContext,
      wrapBuilder<[KuiklyRenderBaseView]>(createGYWebView), this);
  }

  isRenderActive(token: number): boolean { return !this.lifetime.disposed && !this.renderFailed && token === this.renderToken; }

  onControllerAttached(token: number = this.renderToken): void {
    if (!this.isRenderActive(token) || this.attached) return;
    this.attached = true;
    this.loadRequest();
    if (!this.visible) { try { this.controller.onInactive(); } catch (_) {} }
  }

  private loadRequest(): void {
    const request = this.request;
    if (!this.attached || !request || this.lifetime.disposed) return;
    try {
      this.revokePending();
      const suffix = request.settings.userAgentSuffix;
      if (suffix) this.controller.setCustomUserAgent(`${this.controller.getUserAgent()} ${suffix}`);
      const content = request.content;
      this.loadingRequest = true;
      if (content.type === 'html') {
        // 仅这一次、附着后的 loadData 可消费内部 data 导航。baseUrl 和 historyUrl 均原样交给 ArkWeb。
        this.pendingHtmlNavigation = true;
        this.controller.loadData(content.html || '', content.mimeType || 'text/html', content.encoding || 'UTF-8',
          content.baseUrl || '', content.historyUrl || '');
      } else {
        const headers: webview.WebHeader[] = [];
        const values = content.additionalHeaders || {};
        Object.keys(values).forEach(key => headers.push({ headerKey: key, headerValue: values[key] }));
        this.controller.loadUrl(content.url || '', headers);
      }
      this.loadSubmitted = true;
    } catch (_) { this.loadingRequest = false; this.pendingHtmlNavigation = false; this.fail('LOAD_EXCEPTION', 'ArkWeb load failed'); }
  }

  interceptNavigation(request: WebResourceRequest): boolean {
    const current = this.request;
    const target = request.getRequestUrl();
    const mainFrame = request.isMainFrame();
    // 首个主帧回调消费附着后 loadData 的内部导航；用户手势或后续 data 均走拒绝规则。
    const internalHtml = mainFrame && this.pendingHtmlNavigation && this.loadingRequest &&
      !request.isRequestGesture() && target.toLowerCase().startsWith('data:text/html');
    if (mainFrame) this.pendingHtmlNavigation = false;
    if (internalHtml) return false;
    const allowed = current !== null && navigationAllowed(target, mainFrame, current);
    this.emit({ type: 'navigation', url: target, isMainFrame: mainFrame, hasUserGesture: request.isRequestGesture(),
      target: 'CURRENT_WINDOW', blocked: !allowed });
    if (mainFrame && allowed) { this.documentReady = false; this.revokePending(); }
    return !allowed;
  }

  rejectNewWindow(event: OnWindowNewEvent): void {
    // SDK 指定 null 表示取消；只启用事件分流，不创建/支持第二个窗口。
    event.handler.setWebController(null!);
    this.emit({ type: 'navigation', url: event.targetUrl, isMainFrame: true, hasUserGesture: event.isUserTrigger,
      target: 'NEW_WINDOW', blocked: true });
  }

  interceptResource(request: WebResourceRequest): WebResourceResponse {
    const current = this.request;
    // SDK 回调类型未写 nullable；ArkWeb 契约用 null 表示沿用内核网络请求。
    if (!current || request.isMainFrame()) return null!;
    const target = request.getRequestUrl();
    if (!current.blockedResourceRules.some(rule => matches(target, rule)) && !/^(file|content|resource):/i.test(target)) return null!;
    const response = new WebResourceResponse();
    response.setResponseCode(403); response.setReasonMessage('Blocked by WebView policy'); response.setResponseData('');
    return response;
  }

  onPageBegin(value: string): void {
    this.documentReady = false;
    this.loadingRequest = false;
    this.pendingHtmlNavigation = false;
    this.revokePending();
    this.emit({ type: 'pageStarted', url: value });
  }

  onPageVisible(value: string): void {
    this.documentReady = true;
    this.emit({ type: 'firstContentVisible', url: value });
    this.runScripts('DOM_READY');
    this.installBridge();
    this.emitHistory();
  }

  onPageEnd(value: string): void {
    this.documentReady = true;
    this.emit({ type: 'pageFinished', url: value });
    this.runScripts('DOCUMENT_FINISHED');
    this.installBridge();
    this.emitHistory();
  }

  documentStartScripts(): ScriptItem[] {
    const request = this.request;
    if (!request || request.settings.javaScriptEnabled !== true) return [];
    const scripts: ScriptItem[] = [];
    if (request.security.appBridgeEnabled || request.security.pageBridgeEnabled) scripts.push({ script: BRIDGE_BOOTSTRAP, scriptRules: ['*'] });
    request.scripts.filter(script => script.injectionTime === 'DOCUMENT_START').forEach(script =>
      scripts.push({ script: guardedScript(script, request), scriptRules: ['*'] }));
    return scripts;
  }

  private runScripts(time: string): void {
    const request = this.request;
    if (!request || !this.visible || request.settings.javaScriptEnabled !== true) return;
    const generation = this.lifetime.generation;
    request.scripts.filter(script => script.injectionTime === time).forEach(script => {
      this.controller.runJavaScript(guardedScript(script, request)).catch(() => {
        if (this.lifetime.current(generation)) this.fail('LOAD_EXCEPTION', 'Script execution failed');
      });
    });
  }

  private bridgeAllowedAt(value: string): boolean {
    const request = this.request;
    if (!request || request.settings.javaScriptEnabled !== true) return false;
    if (request.security.appBridgeEnabled) return trusted(value, request.security);
    const initial = request.content.type === 'url' ? request.content.url || '' : request.content.baseUrl || '';
    return request.security.pageBridgeEnabled === true && !!origin(initial) && origin(initial) === origin(value);
  }

  private installBridge(): void {
    if (!this.attached || !this.documentReady || !this.visible || this.lifetime.disposed || this.ports.length > 0) return;
    const currentUrl = this.currentUrl();
    if (!this.bridgeAllowedAt(currentUrl)) return;
    const generation = this.lifetime.generation;
    this.controller.runJavaScript(BRIDGE_BOOTSTRAP).then(() => {
      if (!this.lifetime.current(generation) || !this.visible || !this.bridgeAllowedAt(this.currentUrl()) ||
        origin(currentUrl) !== origin(this.currentUrl()) || this.ports.length > 0) return;
      try {
        this.ports = this.controller.createWebMessagePorts();
        this.ports[0].onMessageEvent((message: webview.WebMessage) => {
          if (!this.lifetime.current(generation) || !this.visible || !this.bridgeAllowedAt(this.currentUrl())) return;
          if (typeof message !== 'string' || message.length > 65536 || new util.TextEncoder().encodeInto(message).length > 65536) return;
          try {
            const data = JSON.parse(message) as BridgeMessage;
            if (typeof data.handlerName !== 'string' || !data.handlerName || data.handlerName.length > 80 || typeof data.data !== 'string') return;
            this.emit({ type: 'bridgeMessage', handlerName: data.handlerName, data: data.data });
          } catch (_) {}
        });
        // postMessage 的 NWeb 公开契约只投递主文档；target origin 使用真实当前文档，永不使用 '*'。
        this.controller.postMessage('GYWebViewChannel', [this.ports[1]], new url.URL(currentUrl).origin);
      } catch (_) { this.closePorts(); this.fail('LOAD_EXCEPTION', 'Main-document Bridge channel failed'); }
    }).catch(() => { if (this.lifetime.current(generation)) this.fail('LOAD_EXCEPTION', 'Bridge bootstrap failed'); });
  }

  permissionRequest(request: PermissionRequest): void {
    try {
      const current = this.request;
      const resources = request.getAccessibleResource();
      const value = request.getOrigin();
      if (!current || !this.visible || !current.security.mediaCaptureEnabled || !trusted(value, current.security) ||
        origin(value) !== origin(this.currentUrl()) || resources.length === 0 ||
        resources.some(resource => resource !== ProtectedResourceType.VIDEO_CAPTURE && resource !== ProtectedResourceType.AUDIO_CAPTURE)) {
        request.deny(); return;
      }
      const permissions: Permissions[] = resources.map(resource => resource === ProtectedResourceType.VIDEO_CAPTURE ? 'ohos.permission.CAMERA' : 'ohos.permission.MICROPHONE');
      const context = (this.getUIContext() as UIContext).getHostContext() as common.UIAbilityContext;
      const generation = this.lifetime.generation;
      this.permissionRequests.add(request);
      abilityAccessCtrl.createAtManager().requestPermissionsFromUser(context, permissions).then(result => {
        if (!this.permissionRequests.delete(request)) return;
        if (!this.lifetime.current(generation) || !this.visible || this.request !== current ||
          !trusted(this.currentUrl(), current.security) || origin(value) !== origin(this.currentUrl())) { request.deny(); return; }
        if (result.authResults.length === permissions.length && result.authResults.every(state => state === 0)) request.grant(resources);
        else {
          request.deny();
          const denied = permissions.filter((_permission, index) => result.authResults[index] === -1 && result.dialogShownResults?.[index] === false);
          if (denied.length > 0) this.emit({ type: 'permissionSettingsRequired', permissions: denied.map(permission => permission === 'ohos.permission.CAMERA' ? 'CAMERA' : 'MICROPHONE') });
        }
      }).catch(() => { if (this.permissionRequests.delete(request)) request.deny(); });
    } catch (_) {
      this.permissionRequests.delete(request);
      try { request.deny(); } catch (_) {}
    }
  }

  selectFile(event: OnShowFileSelectorEvent): boolean {
    this.fileResults.add(event.result);
    const complete = (files: string[]): void => {
      // 先移出 pending，再调用系统结果；同步抛错或迟到 Promise 都不能二次结算。
      if (!this.fileResults.delete(event.result)) return;
      try { event.result.handleFileList(files); } catch (_) {}
    };
    try {
      const current = this.request;
      const value = this.currentUrl();
      // SDK 未提供真实来源 frame；仅允许就绪的可信 HTTPS 当前主文档。
      if (!current || !this.visible || !this.documentReady || this.lifetime.disposed ||
        !current.security.fileChooserEnabled || !trusted(value, current.security)) {
        complete([]); return true;
      }
      if (event.fileSelector.isCapture()) {
        complete([]);
        this.emit({ type: 'capabilityUnsupported', capability: 'FILE_CAPTURE' });
        return true;
      }
      const context = (this.getUIContext() as UIContext).getHostContext() as common.UIAbilityContext;
      const generation = this.lifetime.generation;
      const options = new picker.DocumentSelectOptions();
      options.maxSelectNumber = event.fileSelector.getMode() === FileSelectorMode.FileOpenMultipleMode ? 10 : 1;
      new picker.DocumentViewPicker(context).select(options).then(values => {
        const valid = this.lifetime.current(generation) && this.visible && this.request === current && this.documentReady &&
          origin(value) === origin(this.currentUrl()) && trusted(this.currentUrl(), current.security);
        complete(valid ? values : []);
      }).catch(() => complete([]));
      return true;
    } catch (_) {
      complete([]);
      return true;
    }
  }

  /** 默认保持原生 View 的 layout/orientation 语义；应用可覆写方向与系统栏目标。 */
  fullscreenPolicy(width: number, height: number): FullscreenWindowPolicy {
    return {
      enterOrientation: width > height && width > 0 ? window.Orientation.AUTO_ROTATION_LANDSCAPE : undefined,
      enterLayoutFullscreen: true
    };
  }

  enterFullscreen(handler: FullScreenExitHandler, width: number, height: number): void {
    if (!this.visible || this.lifetime.disposed || this.fullscreenHandler || this.nativeExitPending) { handler.exitFullScreen(); return; }
    this.fullscreenHandler = handler;
    const revision = ++this.fullscreenRevision;
    const lease = WindowPolicyController.shared.createFullscreenLease(async () => {
      const context = (this.getUIContext() as UIContext).getHostContext();
      if (!context) throw new Error('Window unavailable');
      return await window.getLastWindow(context);
    }, this.fullscreenPolicy(width, height), () => {
      // 新 View 接管时仅退出旧 ArkWeb handler；旧 lease 不恢复新 owner 的窗口。
      if (this.fullscreenLease === lease) this.exitFullscreen();
    });
    this.fullscreenLease = lease;
    this.emit({ type: 'fullscreenChanged', isFullscreen: true });
    this.windowTask = lease.enter().then(entered => {
      if (!entered && this.fullscreenRevision === revision && this.fullscreenHandler === handler) this.exitFullscreen();
    }).catch(() => {
      if (this.fullscreenRevision === revision && this.fullscreenHandler === handler) {
        this.exitFullscreen();
        this.fail('LOAD_EXCEPTION', 'Fullscreen window update failed');
      }
    });
  }

  exitFullscreen(): boolean {
    const consumed = this.fullscreenHandler !== null || this.fullscreenLease !== null;
    const handler = this.fullscreenHandler;
    if (handler) {
      // 在调用 handler 前设门禁，兼容其同步触发原生退出确认。
      this.nativeExitPending = true;
      try { handler.exitFullScreen(); } catch (_) {}
    }
    this.leaveFullscreen();
    return consumed;
  }

  nativeFullscreenExited(token: number = this.renderToken): void {
    if (!this.isRenderActive(token)) return;
    if (this.nativeExitPending) {
      this.nativeExitPending = false;
      return;
    }
    // 系统/网页主动退出当前全屏，尚未由本实例退出时才释放 active lease。
    this.leaveFullscreen();
  }

  leaveFullscreen(): void {
    const lease = this.fullscreenLease;
    const hadHandler = this.fullscreenHandler !== null;
    this.fullscreenHandler = null;
    this.fullscreenLease = null;
    if (!lease && !hadHandler) return;
    const revision = ++this.fullscreenRevision;
    this.windowTask = (lease?.release() ?? Promise.resolve()).then(() => {
      if (this.fullscreenRevision === revision) this.emit({ type: 'fullscreenChanged', isFullscreen: false });
    }).catch(() => {
      if (this.fullscreenRevision === revision) this.fail('LOAD_EXCEPTION', 'Fullscreen window restoration failed');
    });
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    if (this.lifetime.disposed) return null;
    if (!this.attached || !this.request) { callback?.({ result: null }); return null; }
    try {
      if (method === 'evaluateJavascript') {
        const args = JSON.parse(typeof params === 'string' ? params : '{}') as EvalParams;
        if (!this.visible || !this.documentReady || this.request.settings.javaScriptEnabled !== true ||
          !(trusted(this.currentUrl(), this.request.security) || this.bridgeAllowedAt(this.currentUrl())) ||
          typeof args.script !== 'string' || !args.script.trim()) {
          callback?.({ result: null }); return null;
        }
        const generation = this.lifetime.generation;
        this.controller.runJavaScript(args.script).then(result => {
          if (this.lifetime.current(generation)) callback?.({ result: result });
        }).catch(() => { if (this.lifetime.current(generation)) callback?.({ result: null }); });
        return null;
      }
      let result = true;
      if (method === 'reload') {
        if (this.renderFailed || !this.loadSubmitted) this.setProp('request', JSON.stringify(this.request));
        else { this.documentReady = false; this.revokePending(); this.controller.refresh(); }
      }
      else if (method === 'goBack') {
        if (this.exitFullscreen()) result = true;
        else { result = this.controller.accessBackward(); if (result) { this.documentReady = false; this.revokePending(); this.controller.backward(); } }
      }
      else if (method === 'goForward') { result = this.controller.accessForward(); if (result) { this.documentReady = false; this.revokePending(); this.controller.forward(); } }
      else if (method === 'stopLoading') { this.revokePending(); this.controller.stop(); }
      else if (method === 'exitFullscreen') result = this.exitFullscreen();
      else { super.call(method, params, callback); return null; }
      callback?.({ result: result });
      this.emitHistory();
    } catch (_) { callback?.({ result: null }); this.fail('LOAD_EXCEPTION', 'ArkWeb method failed'); }
    return null;
  }

  private currentUrl(): string {
    if (!this.attached || this.lifetime.disposed) return '';
    try { return this.controller.getUrl(); } catch (_) { return ''; }
  }

  emit(event: WebEvent): void { if (!this.lifetime.disposed) this.eventCallback?.(event); }
  fail(kind: string, message: string, errorCode?: number, httpStatus?: number): void {
    this.emit({ type: 'loadFailed', kind: kind, message: message, url: this.attached ? this.currentUrl() : '',
      errorCode: errorCode, httpStatus: httpStatus, isMainFrame: true });
  }
  emitHistory(): void {
    if (!this.attached || this.lifetime.disposed) return;
    try { this.emit({ type: 'historyChanged', canGoBack: this.controller.accessBackward(), canGoForward: this.controller.accessForward(), url: this.currentUrl() }); }
    catch (_) { this.fail('LOAD_EXCEPTION', 'ArkWeb history unavailable'); }
  }
  renderExited(): void {
    this.renderFailed = true; this.documentReady = false; this.revokePending(); this.exitFullscreen();
    this.fail('RENDER_PROCESS', 'Web renderer exited');
  }
  private closePorts(): void { this.ports.forEach(port => { try { port.close(); } catch (_) {} }); this.ports = []; }
  private revokePending(): void {
    this.lifetime.invalidate(); this.closePorts();
    this.permissionRequests.forEach(request => { try { request.deny(); } catch (_) {} }); this.permissionRequests.clear();
    this.fileResults.forEach(result => { try { result.handleFileList([]); } catch (_) {} }); this.fileResults.clear();
  }
  onDestroy(): void {
    this.revokePending(); this.lifetime.dispose(); this.eventCallback = null; this.exitFullscreen();
    this.pendingHtmlNavigation = false; this.request = null;
    if (this.attached) {
      try { this.controller.stopAllMedia(); this.controller.closeAllMediaPresentations(); this.controller.stop(); this.controller.onInactive(); } catch (_) {}
    }
    this.attached = false;
    super.onDestroy();
  }
}
