import { KRAny, KuiklyRenderBaseView, KuiklyRenderCallback } from '@kuikly-open/render';
import { ComponentContent } from '@ohos.arkui.node';
import { webview } from '@kit.ArkWeb';
import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { url, util } from '@kit.ArkTS';
import { window } from '@kit.ArkUI';
import { common, Configuration, ConfigurationConstant } from '@kit.AbilityKit';
import { FullscreenWindowLease, FullscreenWindowPolicy, WindowPolicyController } from '@gycrosskit/system-actions-native';
import { abilityAccessCtrl, Permissions } from '@kit.AbilityKit';
import { picker, fileIo, fileUri } from '@kit.CoreFileKit';
import { camera, cameraPicker } from '@kit.CameraKit';
import { uniformTypeDescriptor } from '@kit.ArkData';
import { CallbackLifetime, decodeRequest, guardedScript, matches, navigationAllowed, origin, trusted, WebViewRequest, WebViewSettings } from './WebViewWire';
import { createGYWebView } from './WebViewComponent';

interface EvalParams { script: string; }
interface PageReplyParams { replyId: string; data: string; }
interface PendingPageReply { nonce: string; channel: string; }
interface BridgeMessage { handlerName: string; data: string; }
/**
 * 发往 Kuikly 的中立事件，仅设置与 type 对应的字段，业务正文、URL 和文件地址不得直接记日志。
 * @property type 事件判别名，对应 WebViewWire.decodeEvent。
 * @property url 当前主文档或导航地址，可含敏感查询参数。
 * @property title 原始标题，尚未做业务展示过滤。
 * @property progress 加载进度百分比，0..100。
 * @property canGoBack 是否可消费历史返回。
 * @property canGoForward 是否可消费历史前进。
 * @property handlerName Bridge 协议处理器名。
 * @property data Bridge 原始业务正文。
 * @property isFullscreen 是否处于 H5 全屏。
 * @property kind 中立错误类型。
 * @property message 错误说明，不携带业务解析结果。
 * @property errorCode 原生错误码。
 * @property httpStatus HTTP 状态码。
 * @property isMainFrame 是否主文档导航/错误。
 * @property hasUserGesture 原生确认的用户手势。
 * @property target CURRENT_WINDOW 或 NEW_WINDOW。
 * @property blocked 是否同步拒绝导航。
 * @property permissions 需宿主提供设置入口的 CAMERA/MICROPHONE。
 * @property capability 不支持的中立能力名。
 */
export interface WebEvent {
  type: string; url?: string; title?: string; progress?: number; canGoBack?: boolean; canGoForward?: boolean;
  handlerName?: string; data?: string; isFullscreen?: boolean; kind?: string; message?: string;
  errorCode?: number; httpStatus?: number; isMainFrame?: boolean; hasUserGesture?: boolean;
  target?: string; blocked?: boolean; permissions?: string[]; capability?: string; channel?: string; replyId?: string;
}

// 端口只由原生发送给主文档；不使用注册到所有 iframe 的 JavaScriptProxy。
const BRIDGE_BOOTSTRAP: string = `(function(){
if(window!==window.top||window.GYWebViewBridge)return;
let port=null,queue=[],bytes=0,active=window.__GY_WEBVIEW_VISIBLE__!==false;
const bridge=Object.freeze({postMessage:function(handlerName,data){
 if(!active)return false;
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
 if(!active||!event.isTrusted||event.source!==null||event.data!=='GYWebViewChannel'||!event.ports||event.ports.length!==1)return;
 if(port)port.close();port=event.ports[0];const pending=queue;queue=[];bytes=0;
 pending.forEach(function(raw){try{port.postMessage(raw)}catch(e){}});
});
window.addEventListener('GYWebViewVisibility',function(){active=window.__GY_WEBVIEW_VISIBLE__===true;if(port)port.close();port=null;queue=[];bytes=0});
window.addEventListener('pagehide',function(){if(port)port.close();port=null;queue=[];bytes=0});
})();`;

/**
 * ArkWeb 原生 Kuikly 组件；在所属 UI 线程调用，输入变化重建 Web 节点，销毁后不可复用。
 * 所有异步授权、选择器和 JS 结果在导航、隐藏、输入变化或销毁时撤销。
 */
@Observed
export class GYWebView extends KuiklyRenderBaseView {
  /** Kuikly 原生注册名，三端保持一致。 */
  static readonly VIEW_NAME: string = 'GYWebView';
  /** 仅属于当前 renderToken 的 ArkWeb Controller；不能跨页面持有。 */
  controller: webview.WebviewController = new webview.WebviewController();
  /** 当前已验证请求；无效输入或销毁时为 null。 */
  request: WebViewRequest | null = null;
  /** 初始 true，隐藏撤销异步操作并停媒退出全屏。 */
  visible: boolean = true;
  /** 当前 Web 节点身份，输入变化后递增。 */
  renderToken: number = 0;
  /** 当前渲染节点列表，仅含当前身份，用于 ArkUI 替换旧 Controller。 */
  renderTokens: number[] = [0];
  fontScale: number = 1;
  darkTheme: boolean = false;
  private environmentContext: common.ApplicationContext | null = null;
  private environmentId: number | null = null;
  private documentReady: boolean = false;
  private pageMessageSecret: string = '';
  private pageMessageNonce: string = '';
  private pageMessageActive: boolean = false;
  private pageMessageStarted: boolean = false;
  private pageMessageIntercepted: boolean = false;
  private pageMessageReplies: Map<string, PendingPageReply> = new Map();
  private bridgeVisibilityPending: boolean = false;
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
  private captureFiles: Set<string> = new Set();
  private fullscreenHandler: FullScreenExitHandler | null = null;
  private fullscreenLease: FullscreenWindowLease | null = null;
  private fullscreenRevision: number = 0;
  // ArkWeb exit 事件没有 handler/id；主动退出确认前不接收同一 render 的下一次全屏。
  private nativeExitPending: boolean = false;
  private windowTask: Promise<void> = Promise.resolve();

  /** 返回当前声明配置；未设置输入时使用空对象的默认值。 */
  settings(): WebViewSettings { return this.request?.settings || {}; }

  textZoomPercent(): number {
    const settings = this.settings();
    if (settings.followSystemFontScale === false) return 100;
    return Math.min(settings.maximumTextZoomPercent || 200,
      Math.max(settings.minimumTextZoomPercent || 100, Math.round(this.fontScale * 100)));
  }

  private updateConfiguration(config: Configuration): void {
    if (this.lifetime.disposed) return;
    if (config.fontSizeScale !== undefined) this.fontScale = Number.isFinite(config.fontSizeScale) && config.fontSizeScale > 0 ? config.fontSizeScale : 1;
    if (config.colorMode !== undefined) this.darkTheme = config.colorMode === ConfigurationConstant.ColorMode.COLOR_MODE_DARK;
  }

  private observeConfiguration(): void {
    if (this.environmentContext) return;
    const ui = this.getUIContext();
    if (!ui) return;
    const context = ui.getHostContext() as common.UIAbilityContext;
    if (!context) return;
    this.updateConfiguration(context.config);
    const application = context.getApplicationContext();
    try {
      const id = application.on('environment', {
        onConfigurationUpdated: (config: Configuration) => this.updateConfiguration(config),
        onMemoryLevel: () => {},
      });
      if (this.lifetime.disposed) {
        try { application.off('environment', id).catch(() => {}); } catch (_) {}
        return;
      }
      this.environmentId = id;
      this.environmentContext = application;
    } catch (_) { /* Environment observation must not prevent document loading. */ }
  }

  setProp(key: string, value: KRAny | KuiklyRenderCallback): boolean {
    if (this.lifetime.disposed) return true;
    if (key === 'onEvent') { this.eventCallback = value as KuiklyRenderCallback; return true; }
    if (key === 'visible') {
      const changed = this.visible !== (value === true);
      this.visible = value === true;
      const controllerOwner = this.controller;
      const ownerToken = this.renderToken;
      this.revokePending(!this.visible);
      if (controllerOwner !== this.controller || !this.isRenderActive(ownerToken)) return true;
      if (this.attached) {
        try {
          if (this.visible) this.controller.onActive();
          else { this.exitFullscreen(); this.controller.stopAllMedia(); this.controller.onInactive(); }
          if (this.settings().javaScriptEnabled === true) {
            const controller = this.controller;
            const generation = this.lifetime.generation;
            this.bridgeVisibilityPending = true;
            // JS 状态只丢弃隐藏队列；授权仍由 native visible/origin/generation 决定。
            const script = this.bridgeVisibilityScript(changed);
            controller.runJavaScript(script).then(() => {
              if (!this.lifetime.current(generation) || controller !== this.controller) return;
              this.bridgeVisibilityPending = false;
              if (this.visible) this.installBridge();
            }).catch(() => {
              if (this.lifetime.current(generation) && controller === this.controller) this.fail('LOAD_EXCEPTION', 'Bridge visibility update failed');
            });
          }
        } catch (_) { this.fail('LOAD_EXCEPTION', 'ArkWeb visibility update failed'); }
      }
      return true;
    }
    if (key !== 'request') return super.setProp(key, value);
    if (this.request && !this.renderFailed && typeof value === 'string') {
      try {
        const next = decodeRequest(value as string, false);
        if (JSON.stringify(this.request.navigationPolicy) !== JSON.stringify(next.navigationPolicy) &&
          JSON.stringify(this.request.content) === JSON.stringify(next.content) &&
          JSON.stringify(this.request.settings) === JSON.stringify(next.settings) &&
          JSON.stringify(this.request.security) === JSON.stringify(next.security) &&
          JSON.stringify(this.request.scripts) === JSON.stringify(next.scripts) &&
          JSON.stringify(this.request.pageMessageChannels) === JSON.stringify(next.pageMessageChannels) &&
          JSON.stringify(this.request.blockedResourceRules) === JSON.stringify(next.blockedResourceRules)) { this.request.navigationPolicy = next.navigationPolicy; return true; }
      } catch (_) {}
    }
    const controllerOwner = this.controller;
    const ownerToken = this.renderToken;
    this.revokePending();
    if (controllerOwner !== this.controller || ownerToken !== this.renderToken || this.lifetime.disposed) return true;
    this.exitFullscreen();
    if (controllerOwner !== this.controller || ownerToken !== this.renderToken || this.lifetime.disposed) return true;
    if (this.attached) { try { this.controller.stop(); this.controller.stopAllMedia(); } catch (_) {} }
    this.attached = false;
    this.renderFailed = false;
    this.documentReady = false;
    this.bridgeVisibilityPending = false;
    this.controller = new webview.WebviewController();
    this.request = null;
    this.pendingHtmlNavigation = false;
    this.loadingRequest = false;
    this.loadSubmitted = false;
    this.renderToken++;
    this.nativeExitPending = false;
    this.renderTokens = [this.renderToken];
    try {
      if (typeof value !== 'string') throw new Error('request must be a JSON string');
      this.request = decodeRequest(value as string);
      this.pageMessageStarted = false; this.pageMessageIntercepted = false;
      if (this.request.pageMessageChannels?.length && this.request.settings.javaScriptEnabled === true) {
        const bytes = cryptoFramework.createRandom().generateRandomSync(32).data;
        this.pageMessageSecret = Array.from(bytes).map(byte => byte.toString(16).padStart(2, '0')).join('');
        this.pageMessageActive = this.visible;
      }
    } catch (_) {
      this.request = null; this.pageMessageSecret = '';
      this.fail('LOAD_EXCEPTION', 'Invalid or unsupported WebViewRequest');
    }
    return true;
  }

  createArkUIView(): ComponentContent<KuiklyRenderBaseView> {
    return new ComponentContent<KuiklyRenderBaseView>(this.getUIContext() as UIContext,
      wrapBuilder<[KuiklyRenderBaseView]>(createGYWebView), this);
  }

  /** ArkUI 回调入口核验节点身份、销毁状态及渲染进程状态。 */
  isRenderActive(token: number): boolean { return !this.lifetime.disposed && !this.renderFailed && token === this.renderToken; }

  /** 当前节点首次附着后提交内容，重复或旧节点附着安全忽略。 */
  onControllerAttached(token: number = this.renderToken): void {
    if (!this.isRenderActive(token) || this.attached) return;
    this.attached = true;
    this.observeConfiguration();
    this.loadRequest();
    if (!this.visible) { try { this.controller.onInactive(); } catch (_) {} }
  }

  private loadRequest(): void {
    const request = this.request;
    if (!this.attached || !request || this.lifetime.disposed) return;
    try {
      this.revokePending(false);
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

  /** 原生同步拦截导航；仅一次无手势内部 HTML 首航可消费例外。 */
  interceptNavigation(request: WebResourceRequest): boolean {
    const current = this.request;
    const controllerOwner = this.controller;
    const ownerToken = this.renderToken;
    const target = request.getRequestUrl();
    const mainFrame = request.isMainFrame();
    // 首个主帧回调消费附着后 loadData 的内部导航；用户手势或后续 data 均走拒绝规则。
    const internalHtml = mainFrame && this.pendingHtmlNavigation && this.loadingRequest &&
      !request.isRequestGesture() && target.split(/[;,]/)[0].toLowerCase() ===
        'data:' + (current?.content.mimeType || 'text/html').toLowerCase();
    if (mainFrame) this.pendingHtmlNavigation = false;
    if (internalHtml) return false;
    const allowed = current !== null && navigationAllowed(target, mainFrame, current);
    this.emit({ type: 'navigation', url: target, isMainFrame: mainFrame, hasUserGesture: request.isRequestGesture(),
      target: 'CURRENT_WINDOW', blocked: !allowed });
    if (controllerOwner !== this.controller || !this.isRenderActive(ownerToken)) return true;
    if (mainFrame && allowed) {
      const initial = !this.pageMessageIntercepted && this.isInitialPageMessageUrl(target);
      this.pageMessageIntercepted = true; this.documentReady = false; this.revokePending(!initial);
      if (controllerOwner !== this.controller || !this.isRenderActive(ownerToken)) return true;
    }
    return !allowed;
  }

  /** 取消真实第二窗口；明确允许的目标沿用当前 Controller，并复核路由回调可能改变的 owner。 */
  rejectNewWindow(event: OnWindowNewEvent): void {
    const controllerOwner = this.controller;
    const ownerToken = this.renderToken;
    const current = this.request;
    event.handler.setWebController(null!);
    const allowed = this.visible && this.isRenderActive(ownerToken) && current !== null &&
      current.navigationPolicy.allowNewWindows === true &&
      (event.isUserTrigger || current.settings.javaScriptCanOpenWindowsAutomatically === true) && navigationAllowed(event.targetUrl, true, current);
    this.emit({ type: 'navigation', url: event.targetUrl, isMainFrame: true, hasUserGesture: event.isUserTrigger,
      target: 'NEW_WINDOW', blocked: !allowed });
    if (!allowed || !this.visible || this.request !== current || controllerOwner !== this.controller || !this.isRenderActive(ownerToken)) return;
    this.documentReady = false;
    this.revokePending();
    if (!this.visible || this.request !== current || controllerOwner !== this.controller || !this.isRenderActive(ownerToken)) return;
    try { controllerOwner.loadUrl(event.targetUrl); }
    catch (_) { this.fail('LOAD_EXCEPTION', 'ArkWeb popup navigation failed'); }
  }

  /** 拒绝命中规则及本地资源；null 表示继续内核网络请求。 */
  interceptResource(request: WebResourceRequest): WebResourceResponse {
    const current = this.request;
    // SDK 回调类型未写 nullable；ArkWeb 契约用 null 表示沿用内核网络请求。
    if (!current || request.isMainFrame()) return null!;
    const target = request.getRequestUrl();
    const localBlocked = /^(content|resource):/i.test(target) ||
      (/^file:/i.test(target) && current.settings.allowFileAccess !== true);
    if (!current.blockedResourceRules.some(rule => matches(target, rule)) && !localBlocked) return null!;
    const response = new WebResourceResponse();
    response.setResponseCode(403); response.setReasonMessage('Blocked by WebView policy'); response.setResponseData('');
    return response;
  }

  /** 开始新文档并撤销上一文档能力请求/端口。 */
  onPageBegin(value: string): void {
    this.documentReady = false;
    this.loadingRequest = false;
    this.pendingHtmlNavigation = false;
    const controllerOwner = this.controller; const ownerToken = this.renderToken;
    const initial = !this.pageMessageStarted && this.isInitialPageMessageUrl(value);
    this.pageMessageStarted = true;
    this.revokePending(!initial);
    if (controllerOwner === this.controller && this.isRenderActive(ownerToken)) this.emit({ type: 'pageStarted', url: value });
  }

  /** 当前文档可绘制时执行 DOM_READY 脚本并尝试主文档端口握手。 */
  onPageVisible(value: string): void {
    const controllerOwner = this.controller; const ownerToken = this.renderToken;
    this.documentReady = true;
    this.emit({ type: 'firstContentVisible', url: value });
    if (controllerOwner !== this.controller || !this.isRenderActive(ownerToken)) return;
    this.runScripts('DOM_READY');
    this.installBridge();
    this.emitHistory();
  }

  /** 当前文档完成时执行完成脚本并更新历史。 */
  onPageEnd(value: string): void {
    const controllerOwner = this.controller; const ownerToken = this.renderToken;
    this.documentReady = true;
    this.emit({ type: 'pageFinished', url: value });
    if (controllerOwner !== this.controller || !this.isRenderActive(ownerToken)) return;
    this.runScripts('DOM_READY');
    this.runScripts('DOCUMENT_FINISHED');
    this.installBridge();
    this.emitHistory();
  }

  /** 返回原生首航注入脚本；JS 关闭为空，可信限定由页面内主文档门禁再次检查。 */
  documentStartScripts(): ScriptItem[] {
    const request = this.request;
    if (!request || request.settings.javaScriptEnabled !== true) return [];
    const scripts: ScriptItem[] = [];
    const pageScript = this.pageMessageScript();
    if (pageScript) scripts.push({ script: pageScript, scriptRules: ['*'] });
    if (request.security.appBridgeEnabled || request.security.pageBridgeEnabled) scripts.push({
      script: `window.__GY_WEBVIEW_VISIBILITY_REVISION__=${this.lifetime.generation};window.__GY_WEBVIEW_VISIBLE__=${this.visible};${BRIDGE_BOOTSTRAP}`,
      scriptRules: ['*']
    });
    request.scripts.filter(script => script.injectionTime !== 'DOCUMENT_FINISHED').forEach(script =>
      scripts.push({ script: guardedScript(script, request), scriptRules: ['*'] }));
    return scripts;
  }

  private initialPageMessageUrl(): string {
    return this.request?.content.type === 'url' ? this.request.content.url || '' : this.request?.content.baseUrl || '';
  }

  private isInitialPageMessageUrl(value: string): boolean {
    const actualOrigin = origin(value);
    if (!actualOrigin || actualOrigin !== origin(this.initialPageMessageUrl())) return false;
    try {
      const actual = new url.URL(value); const initial = new url.URL(this.initialPageMessageUrl());
      const suffix = (address: string): string => {
        const tail = address.substring(address.indexOf('://') + 3).replace(/^[^/?#]*/, '');
        return tail.startsWith('/') ? tail : '/' + tail;
      };
      return actual.hostname.toLowerCase() === initial.hostname.toLowerCase() &&
        suffix(value) === suffix(this.initialPageMessageUrl());
    }
    catch (_) { return false; }
  }

  private pageMessageAllowed(): boolean {
    return this.pageMessageActive && this.visible && this.attached && this.isRenderActive(this.renderToken) &&
      this.request?.settings.javaScriptEnabled === true && !!this.pageMessageSecret &&
      navigationAllowed(this.currentUrl() || this.initialPageMessageUrl(), true, this.request) &&
      (!this.currentUrl() || this.isInitialPageMessageUrl(this.currentUrl()));
  }

  /** URL 只辅助初始文档检查；消息授权依赖私有主文档 capability，不能把 URL 当 frame 认证。 */
  pageMessageBootstrap(key: string, nonce: string): boolean {
    if (key !== this.pageMessageSecret || !this.pageMessageAllowed() || this.pageMessageNonce || !/^[a-f0-9]{64}$/.test(nonce)) return false;
    try {
      if (!this.isInitialPageMessageUrl(this.controller.getLastJavascriptProxyCallingFrameUrl())) return false;
    } catch (_) { return false; }
    // 首次门禁在同步宿主回调前消费；同一 owner 的后续同 URL 文档不能再次引导。
    this.pageMessageNonce = nonce;
    return true;
  }

  pageMessagePost(key: string, nonce: string, channel: string, data: string): void {
    if (key !== this.pageMessageSecret || nonce !== this.pageMessageNonce || !nonce || !this.pageMessageAllowed() ||
      !this.request?.pageMessageChannels?.includes(channel) || !this.validPageMessageData(data) || this.pageMessageReplies.size >= 128) return;
    const replyId = util.generateRandomUUID(true);
    this.pageMessageReplies.set(replyId, { nonce: nonce, channel: channel });
    this.emit({ type: 'pageMessage', channel: channel, data: data, replyId: replyId });
  }

  pageMessageRevoke(key: string, nonce: string): void {
    if (key === this.pageMessageSecret && nonce === this.pageMessageNonce && !!nonce) this.revokePending();
  }

  private validPageMessageData(data: string): boolean {
    return typeof data === 'string' && data.length <= 65536 && new util.TextEncoder().encodeInto(data).length <= 65536;
  }

  private pageMessageScript(): string {
    if (!this.pageMessageActive || !this.pageMessageSecret || !this.request?.pageMessageChannels?.length) return '';
    return `(function(key,channels,url){
try{const parsed=new URL(url);const tail=url.substring(url.indexOf('://')+3).replace(/^[^/?#]*/,'');url=parsed.protocol+'//'+parsed.host+(tail.startsWith('/')?tail:'/'+tail);}catch(_){return;}
if(window!==window.top||location.href!==url)return;
const proxy=window.GYWebViewPageMessageNative;
if(!proxy)return;
const listen=window.addEventListener.bind(window);
const bootstrap=proxy.pageMessageBootstrap.bind(proxy),post=proxy.pageMessagePost.bind(proxy),revoke=proxy.pageMessageRevoke.bind(proxy);
const bytes=new Uint8Array(32);crypto.getRandomValues(bytes);const nonce=Array.from(bytes).map(b=>b.toString(16).padStart(2,'0')).join('');
let active=bootstrap(key,nonce);const entries=Object.create(null);
channels.forEach(function(channel){const entry={onmessage:null,postMessage:function(data){
if(active&&window===window.top&&location.href===url&&typeof data==='string')post(key,nonce,channel,data);
}};entries[channel]=entry;Object.defineProperty(window,channel,{value:entry,configurable:true});});
window.__GY_WEBVIEW_PAGE_MESSAGE_REPLY__=function(expected,channel,data){
if(!active||nonce!==expected||window!==window.top||location.href!==url||typeof data!=='string')return false;
const entry=entries[channel];if(!entry||typeof entry.onmessage!=='function')return false;entry.onmessage({data:data});return true;
};
listen('pagehide',function(){active=false;revoke(key,nonce)});
})(${JSON.stringify(this.pageMessageSecret)},${JSON.stringify(this.request.pageMessageChannels)},${JSON.stringify(this.initialPageMessageUrl())});`;
  }

  private replyPageMessage(params: KRAny, callback: KuiklyRenderCallback | null): void {
    let args: PageReplyParams;
    try { args = JSON.parse(typeof params === 'string' ? params : '{}') as PageReplyParams; }
    catch (_) { callback?.({ result: false }); return; }
    if (!args || typeof args.replyId !== 'string' || !this.pageMessageAllowed() || !this.validPageMessageData(args.data)) { callback?.({ result: false }); return; }
    const pending = this.pageMessageReplies.get(args.replyId);
    const nonce = pending?.nonce;
    this.pageMessageReplies.delete(args.replyId);
    if (!nonce || nonce !== this.pageMessageNonce) { callback?.({ result: false }); return; }
    const script = `(function(){const reply=window.__GY_WEBVIEW_PAGE_MESSAGE_REPLY__;return typeof reply==='function'&&reply(${JSON.stringify(nonce)},${JSON.stringify(pending?.channel)},${JSON.stringify(args.data)});})();`;
    // Boolean 与 A/i 一致，只表示已向当前 native Controller 提交，不是 H5 delivery/业务 ACK。
    try { this.controller.runJavaScript(script).catch(() => {}); }
    catch (_) { callback?.({ result: false }); return; }
    callback?.({ result: true });
  }

  private runScripts(time: string): void {
    const request = this.request;
    if (!request || !this.attached || !this.isRenderActive(this.renderToken) || request.settings.javaScriptEnabled !== true) return;
    // 初始化不依赖可见性；端口、权限和显式 evaluateJavascript 仍由 visible 撤销。
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

  private bridgeVisibilityScript(changed: boolean): string {
    return `(function(){const revision=${this.lifetime.generation};if((window.__GY_WEBVIEW_VISIBILITY_REVISION__||0)>revision)return;` +
      `const changed=${changed}||window.__GY_WEBVIEW_VISIBLE__!==${this.visible};window.__GY_WEBVIEW_VISIBILITY_REVISION__=revision;window.__GY_WEBVIEW_VISIBLE__=${this.visible};` +
      `if(changed)window.dispatchEvent(new Event('GYWebViewVisibility'));})();`;
  }

  private installBridge(): void {
    if (!this.attached || !this.documentReady || !this.visible || this.bridgeVisibilityPending || !this.isRenderActive(this.renderToken) || this.ports.length > 0) return;
    const currentUrl = this.currentUrl();
    if (!this.bridgeAllowedAt(currentUrl)) return;
    const generation = this.lifetime.generation;
    this.controller.runJavaScript(this.bridgeVisibilityScript(false) + BRIDGE_BOOTSTRAP).then(() => {
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

  /** 主文档来源授权通过后申请系统权限；取消/异源/部分授权均 deny，结果只结算一次。 */
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

  /** 接管原生选择/拍摄；结果只结算一次，拍摄临时文件保留到文档撤销，供内核上传读取。 */
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
      const context = (this.getUIContext() as UIContext).getHostContext() as common.UIAbilityContext;
      const generation = this.lifetime.generation;
      const valid = (): boolean => this.lifetime.current(generation) && this.visible && this.request === current && this.documentReady &&
        origin(value) === origin(this.currentUrl()) && trusted(this.currentUrl(), current.security);
      const accepts = event.fileSelector.getAcceptType().flatMap(type => type.split(',')).map(type => type.trim().toLowerCase()).filter(type => !!type);
      if (event.fileSelector.isCapture()) {
        const video = accepts.length > 0 && accepts.every(type => type === 'video/*' || type === 'video/mp4');
        const mime = video ? 'video/mp4' : 'image/jpeg';
        if (!current.security.mediaCaptureEnabled || (accepts.length > 0 && !accepts.some(type => type === '*/*' || type === mime || type === (video ? 'video/*' : 'image/*')))) {
          complete([]); return true;
        }
        const permissions: Permissions[] = video ? ['ohos.permission.CAMERA', 'ohos.permission.MICROPHONE'] : ['ohos.permission.CAMERA'];
        abilityAccessCtrl.createAtManager().requestPermissionsFromUser(context, permissions).then(async result => {
          if (!valid()) { complete([]); return; }
          if (result.authResults.length !== permissions.length || result.authResults.some(state => state !== 0)) {
            const denied = permissions.filter((_permission, index) => result.authResults[index] === -1 && result.dialogShownResults?.[index] === false);
            if (denied.length > 0) this.emit({ type: 'permissionSettingsRequired', permissions: denied.map(permission => permission === 'ohos.permission.CAMERA' ? 'CAMERA' : 'MICROPHONE') });
            complete([]); return;
          }
          const path = `${context.cacheDir}/gy-webview-${util.generateRandomUUID()}${video ? '.mp4' : '.jpg'}`;
          const file = fileIo.openSync(path, fileIo.OpenMode.CREATE | fileIo.OpenMode.READ_WRITE); fileIo.closeSync(file);
          this.captureFiles.add(path);
          const uri = fileUri.getUriFromPath(path);
          const mediaType = video ? cameraPicker.PickerMediaType.VIDEO : cameraPicker.PickerMediaType.PHOTO;
          try {
            const result = await cameraPicker.pick(context, [mediaType], { cameraPosition: camera.CameraPosition.CAMERA_POSITION_BACK, saveUri: uri, videoDuration: 60 });
            if (!valid()) { this.captureFiles.delete(path); try { fileIo.unlinkSync(path); } catch (_) {} complete([]); return; }
            const size = fileIo.statSync(path).size;
            const output = fileIo.openSync(path, fileIo.OpenMode.READ_ONLY);
            const header = new ArrayBuffer(12);
            let bytesRead = 0;
            try { bytesRead = fileIo.readSync(output.fd, header); } finally { fileIo.closeSync(output); }
            const bytes = new Uint8Array(header);
            const mediaMatches = video ? bytesRead >= 12 && bytes[4] === 0x66 && bytes[5] === 0x74 && bytes[6] === 0x79 && bytes[7] === 0x70 :
              bytesRead >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
            const accepted = valid() && result.resultCode === 0 && result.resultUri === uri && result.mediaType === mediaType && mediaMatches && size > 0 && size <= 50 * 1024 * 1024;
            if (!accepted) { this.captureFiles.delete(path); try { fileIo.unlinkSync(path); } catch (_) {} }
            complete(accepted ? [uri] : []);
          } catch (_) { this.captureFiles.delete(path); try { fileIo.unlinkSync(path); } catch (_) {} complete([]); }
        }).catch(() => complete([]));
        return true;
      }
      const options = new picker.DocumentSelectOptions();
      const maxFiles = event.fileSelector.getMode() === FileSelectorMode.FileOpenMultipleMode ? 10 : 1;
      options.maxSelectNumber = maxFiles;
      new picker.DocumentViewPicker(context).select(options).then(values => {
        if (!valid()) { complete([]); return; }
        // URI 来自系统 picker；逐个打开核验大小，无法读取的结果不交给内核。
        const bounded = values.length <= maxFiles && values.every(uri => {
          const extension = `.${new fileUri.FileUri(uri).name.split('.').pop() || ''}`.toLowerCase();
          let mimeMatches = accepts.length === 0 || accepts.includes('*/*') || accepts.includes(extension);
          if (!mimeMatches) {
            const descriptor = uniformTypeDescriptor.getTypeDescriptor(uniformTypeDescriptor.getUniformDataTypeByFilenameExtension(extension));
            mimeMatches = accepts.some(type => descriptor.mimeTypes.some(mime =>
              type.endsWith('/*') ? mime.startsWith(type.slice(0, -1)) : mime === type));
          }
          const file = fileIo.openSync(uri, fileIo.OpenMode.READ_ONLY);
          try { const size = fileIo.statSync(file.fd).size; return mimeMatches && size > 0 && size <= 50 * 1024 * 1024; }
          finally { fileIo.closeSync(file); }
        });
        complete(valid() && bounded ? values : []);
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

  /** 申请共享 Window owner，width/height 单位像素；冲突或失活立即退出原生 handler。 */
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

  /** 主动退出并恢复所属 Window lease；true 表示消费全屏，无参原生确认前拒绝同节点再次进入。 */
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

  /** 处理原生无参退出确认；旧节点不得撤销新节点全屏。 */
  nativeFullscreenExited(token: number = this.renderToken): void {
    if (!this.isRenderActive(token)) return;
    if (this.nativeExitPending) {
      this.nativeExitPending = false;
      return;
    }
    // 系统/网页主动退出当前全屏，尚未由本实例退出时才释放 active lease。
    this.leaveFullscreen();
  }

  /** 撤销当前 lease 并异步恢复 Window；重复调用安全忽略。 */
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
    if (method === 'replyPageMessage') { this.replyPageMessage(params, callback); return null; }
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
      const controllerOwner = this.controller; const ownerToken = this.renderToken;
      const currentOwner = (): boolean => controllerOwner === this.controller && ownerToken === this.renderToken && !this.lifetime.disposed;
      let result = true;
      if (method === 'reload') {
        if (this.renderFailed || !this.loadSubmitted || this.request.pageMessageChannels?.length) this.setProp('request', JSON.stringify(this.request));
        else { this.documentReady = false; this.revokePending(); if (currentOwner()) controllerOwner.refresh(); else result = false; }
      }
      else if (method === 'goBack') {
        if (this.exitFullscreen()) result = true;
        else { result = this.controller.accessBackward(); if (result) { this.documentReady = false; this.revokePending(); if (currentOwner()) controllerOwner.backward(); else result = false; } }
      }
      else if (method === 'goForward') { result = this.controller.accessForward(); if (result) { this.documentReady = false; this.revokePending(); if (currentOwner()) controllerOwner.forward(); else result = false; } }
      else if (method === 'stopLoading') { this.revokePending(); if (currentOwner()) controllerOwner.stop(); else result = false; }
      else if (method === 'exitFullscreen') result = this.exitFullscreen();
      else { super.call(method, params, callback); return null; }
      callback?.({ result: result });
      if (currentOwner()) this.emitHistory();
    } catch (_) { callback?.({ result: null }); this.fail('LOAD_EXCEPTION', 'ArkWeb method failed'); }
    return null;
  }

  private currentUrl(): string {
    if (!this.attached || this.lifetime.disposed) return '';
    try { return this.controller.getUrl(); } catch (_) { return ''; }
  }

  /** 交付当前实例事件；销毁后静默丢弃，回调在所属 UI 执行链执行。 */
  emit(event: WebEvent): void { if (!this.lifetime.disposed) this.eventCallback?.(event); }
  /** 发布中立主文档错误，errorCode/httpStatus 可缺省。 */
  fail(kind: string, message: string, errorCode?: number, httpStatus?: number): void {
    const controllerOwner = this.controller; const ownerToken = this.renderToken;
    this.revokePending();
    if (controllerOwner !== this.controller || ownerToken !== this.renderToken || this.lifetime.disposed) return;
    this.emit({ type: 'loadFailed', kind: kind, message: message, url: this.attached ? this.currentUrl() : '',
      errorCode: errorCode, httpStatus: httpStatus, isMainFrame: true });
  }
  /** 当前 Controller 存活时发布历史可用性和地址。 */
  emitHistory(): void {
    if (!this.attached || this.lifetime.disposed) return;
    try { this.emit({ type: 'historyChanged', canGoBack: this.controller.accessBackward(), canGoForward: this.controller.accessForward(), url: this.currentUrl() }); }
    catch (_) { this.fail('LOAD_EXCEPTION', 'ArkWeb history unavailable'); }
  }
  /** 标记渲染进程失败，撤销所有能力；reload 会重建 Controller。 */
  renderExited(): void {
    const controllerOwner = this.controller; const ownerToken = this.renderToken;
    this.renderFailed = true; this.documentReady = false; this.revokePending();
    if (controllerOwner !== this.controller || ownerToken !== this.renderToken || this.lifetime.disposed) return;
    this.exitFullscreen();
    this.fail('RENDER_PROCESS', 'Web renderer exited');
  }
  private closePorts(): void { this.ports.forEach(port => { try { port.close(); } catch (_) {} }); this.ports = []; }
  private revokePending(revokePageChannels: boolean = true): void {
    if (revokePageChannels) {
      this.pageMessageActive = false; this.pageMessageSecret = ''; this.pageMessageNonce = '';
      this.pageMessageReplies.clear();
      if (this.attached && this.request?.pageMessageChannels?.length) {
        try { this.controller.deleteJavaScriptRegister('GYWebViewPageMessageNative'); } catch (_) {}
      }
    }
    this.bridgeVisibilityPending = false;
    this.lifetime.invalidate(); this.closePorts();
    this.permissionRequests.forEach(request => { try { request.deny(); } catch (_) {} }); this.permissionRequests.clear();
    this.fileResults.forEach(result => { try { result.handleFileList([]); } catch (_) {} }); this.fileResults.clear();
    this.captureFiles.forEach(path => { try { fileIo.unlinkSync(path); } catch (_) {} }); this.captureFiles.clear();
  }
  onDestroy(): void {
    if (this.environmentContext && this.environmentId !== null) {
      try { this.environmentContext.off('environment', this.environmentId).catch(() => {}); } catch (_) {}
    }
    this.environmentContext = null; this.environmentId = null;
    this.lifetime.dispose(); this.revokePending(); this.eventCallback = null; this.exitFullscreen();
    this.pendingHtmlNavigation = false; this.request = null;
    if (this.attached) {
      try { this.controller.stopAllMedia(); this.controller.closeAllMediaPresentations(); this.controller.stop(); this.controller.onInactive(); } catch (_) {}
    }
    this.attached = false;
    super.onDestroy();
  }
}
