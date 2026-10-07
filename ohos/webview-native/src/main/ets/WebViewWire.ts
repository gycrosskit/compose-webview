import { url } from '@kit.ArkTS';

/**
 * 中立内容 wire；由 decodeRequest 校验后使用，正文和 URL 可能包含敏感数据。
 * @property type url 或 html 内容类型。
 * @property url 非空顶层 URL。
 * @property additionalHeaders 本次顶层请求头，缺省为空；不适用于全部子资源。
 * @property html 非空 HTML 正文。
 * @property baseUrl HTML 来源/相对链接基准，默认无来源。
 * @property mimeType 默认 text/html，桥仅支持此类型。
 * @property encoding 默认 UTF-8。
 * @property historyUrl 可选历史显示地址，不替代来源授权。
 */
export interface WebViewContent {
  type: string; url?: string; additionalHeaders?: Record<string, string>;
  html?: string; baseUrl?: string | null; mimeType?: string; encoding?: string; historyUrl?: string | null;
}
/**
 * ArkWeb 消费的中立设置；本地文件、新窗口与每实例第三方 Cookie 能力不受支持时拒绝请求。
 * @property javaScriptEnabled 默认 false。
 * @property domStorageEnabled 默认 true。
 * @property allowFileAccess 必须关闭。
 * @property allowContentAccess 必须关闭。
 * @property mixedContentPolicy 默认 NEVER_ALLOW。
 * @property cachePolicy 默认 DEFAULT。
 * @property acceptsThirdPartyCookies 默认 false，不能启用。
 * @property supportMultipleWindows 默认 false，不能启用。
 * @property javaScriptCanOpenWindowsAutomatically 默认 false，不能启用。
 * @property mediaPlaybackRequiresUserGesture 默认 true。
 * @property loadsImagesAutomatically 默认 true。
 * @property blockNetworkImage 默认 false。
 * @property supportZoom 默认 true。
 * @property userAgentSuffix 可选 User-Agent 后缀，不含 CR/LF。
 */
export interface WebViewSettings {
  javaScriptEnabled?: boolean; domStorageEnabled?: boolean; allowFileAccess?: boolean; allowContentAccess?: boolean;
  mixedContentPolicy?: string; cachePolicy?: string; acceptsThirdPartyCookies?: boolean;
  supportMultipleWindows?: boolean; javaScriptCanOpenWindowsAutomatically?: boolean;
  mediaPlaybackRequiresUserGesture?: boolean; loadsImagesAutomatically?: boolean; blockNetworkImage?: boolean;
  supportZoom?: boolean; userAgentSuffix?: string | null;
}
/**
 * 高权限 HTTPS 来源授权；路径、查询和 fragment 不参与匹配。
 * @property urls 精确来源列表，含 scheme/host/有效端口；空列表不授权。
 * @property trustedHostSuffixes 完整域标签后缀，允许子域及 HTTPS 任意有效端口。
 */
export interface TrustOrigins { urls: string[]; trustedHostSuffixes: string[]; }
/**
 * 所有能力缺省关闭，file/media/appBridge 必须配置可信 HTTPS 来源。
 * @property trustedOrigins 高权限来源白名单。
 * @property appBridgeEnabled 可信主文档业务 Bridge，与 pageBridge 互斥。
 * @property pageBridgeEnabled 初始 HTTP/HTTPS 同源主文档 Bridge，与 appBridge 互斥。
 * @property fileChooserEnabled 可信且就绪主文档可以选择文件；URI 不得写日志。
 * @property mediaCaptureEnabled 可信主文档可请求摄像头/麦克风，系统授权仍单独申请。
 */
export interface WebViewSecurity {
  trustedOrigins: TrustOrigins; appBridgeEnabled?: boolean; pageBridgeEnabled?: boolean;
  fileChooserEnabled?: boolean; mediaCaptureEnabled?: boolean;
}
/**
 * 声明式 URL 过滤规则，不用 Contains 替代来源鉴权。
 * @property type contains/exactHost/hostSuffix。
 * @property value contains 非空匹配片段。
 * @property ignoreCase contains 默认 false。
 * @property host exactHost 目标域。
 * @property suffix hostSuffix 目标域及子域。
 */
export interface WebViewUrlRule { type: string; value?: string; ignoreCase?: boolean; host?: string; suffix?: string; scheme?: string | null; includeRoot?: boolean; rejectUserInfo?: boolean; }
/**
 * 主文档脚本声明，输入不可信时应由宿主拒绝而非拼接。
 * @property id 非空稳定脚本标识。
 * @property source 非空可信脚本正文。
 * @property injectionTime DOCUMENT_START/DOM_READY/DOCUMENT_FINISHED。
 * @property onlyForTrustedMainFrame 默认 true；false 仍拒绝 iframe。
 */
export interface WebViewScript { id: string; source: string; injectionTime: string; onlyForTrustedMainFrame: boolean; }
/**
 * 原生同步导航策略，不等待 Kotlin 异步观察事件。
 * @property allowedSchemes 允许的 scheme；ArkWeb 进一步限制为 HTTP/HTTPS。
 * @property allowedOrigins 可选精确主文档来源白名单，空时不限制。
 * @property allowedUrls 可选完整 URL 字符串白名单，只限制主文档；空时不限制，不归一化路径或查询。
 * @property blockedRules 主文档拒绝规则。
 * @property allowNewWindows 默认 false；此平台不支持启用。
 */
export interface NavigationPolicy { allowedSchemes: string[]; allowedOrigins?: string[]; allowedUrls?: string[]; blockedRules: WebViewUrlRule[]; allowNewWindows?: boolean; }
/**
 * 单个原生实例的完整 JSON 输入；变化重建 Controller 并撤销旧异步操作。
 * @property content 本次声明式内容。
 * @property settings 渲染与能力设置。
 * @property security 来源授权与高权限开关。
 * @property scripts 命名业务脚本，默认空。
 * @property blockedResourceRules 子资源拒绝规则。
 * @property navigationPolicy 原生同步导航策略。
 */
export interface WebViewRequest {
  content: WebViewContent; settings: WebViewSettings; security: WebViewSecurity;
  scripts: WebViewScript[]; blockedResourceRules: WebViewUrlRule[]; navigationPolicy: NavigationPolicy;
  pageMessageChannels?: string[];
}

/** 规范化 HTTP/HTTPS origin 为显式有效端口；凭据、无效 URL 或端口返回空字符串。 */
export function origin(value: string): string {
  try {
    const raw = value.trim();
    if (/[\\\u0000-\u0020\u007f]/.test(raw) || raw.split('://')[1]?.split(/[/?#]/)[0].includes('@')) return '';
    const parsed = new url.URL(raw);
    if (!['https:', 'http:'].includes(parsed.protocol) || !parsed.hostname || parsed.username || parsed.password) return '';
    if (parsed.hostname.endsWith('..')) return '';
    const host = parsed.hostname.toLowerCase().replace(/\.$/, '');
    if (!host.startsWith('[') && host.split('.').some(label => label.length === 0)) return '';
    const port = parsed.port || (parsed.protocol === 'https:' ? '443' : '80');
    if (!/^\d+$/.test(port) || Number(port) < 1 || Number(port) > 65535) return '';
    return `${parsed.protocol}//${host}:${port}`;
  } catch (_) { return ''; }
}

function validHost(value: string): boolean {
  const host = value.trim().replace(/^\.|\.$/g, '').toLowerCase();
  return host.length > 0 && host.split('.').every(label => label.length <= 63 && /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/.test(label));
}

/** 按 HTTPS 精确来源或域标签后缀同步检查；不读取网络或系统状态。 */
export function trusted(value: string, security: WebViewSecurity): boolean {
  const target = origin(value);
  if (!target.startsWith('https:')) return false;
  const parsed = new url.URL(value);
  const host = parsed.hostname.toLowerCase().replace(/\.$/, '');
  return security.trustedOrigins.urls.some(item => origin(item) === target) ||
    security.trustedOrigins.trustedHostSuffixes.some(item => {
      const suffix = item.trim().toLowerCase().replace(/^\.|\.$/g, '');
      return validHost(suffix) &&
        (host === suffix || host.endsWith(`.${suffix}`));
    });
}

/** 同步匹配 URL 过滤规则；无效 host URL 返回 false。 */
export function matches(value: string, rule: WebViewUrlRule): boolean {
  if (rule.type === 'contains') {
    const needle = rule.value || '';
    return needle.length > 0 && (rule.ignoreCase === true ? value.toLowerCase().includes(needle.toLowerCase()) : value.includes(needle));
  }
  try {
    const parsed = new url.URL(value);
    if (rule.type === 'hostSuffix' && rule.rejectUserInfo === true && (!value.includes('://') || /[\u0000-\u0020\u007f\\]/.test(value.trim()) || value.split('://').slice(1).join('://').split(/[/?#]/)[0].includes('@'))) return false;
    if (rule.type === 'hostSuffix' && rule.scheme && parsed.protocol.toLowerCase() !== `${rule.scheme}:`) return false;
    const host = parsed.hostname.toLowerCase().replace(/\.$/, '');
    if (!host || host.split('.').some(part => !part)) return false;
    const target = (rule.type === 'exactHost' ? rule.host : rule.suffix || '').toLowerCase().replace(/^\.|\.$/g, '');
    return target.length > 0 && ((host === target && (rule.type !== 'hostSuffix' || rule.includeRoot !== false)) || (rule.type === 'hostSuffix' && host.endsWith(`.${target}`)));
  } catch (_) { return false; }
}

/** 同步检查主帧/子资源导航与 Bridge 来源；不启动外部 Ability。 */
export function navigationAllowed(value: string, mainFrame: boolean, request: WebViewRequest): boolean {
  try {
    const parsed = new url.URL(value);
    const scheme = parsed.protocol.replace(':', '').toLowerCase();
    // WebView 不替宿主启动外部 Ability；即使宿主把自定义 scheme 列入 policy 也拒绝。
    if (!['http', 'https'].includes(scheme) || !origin(value) || !request.navigationPolicy.allowedSchemes.includes(scheme)) return false;
    if (mainFrame && request.navigationPolicy.allowedOrigins && request.navigationPolicy.allowedOrigins.length > 0 &&
      !request.navigationPolicy.allowedOrigins.some(item => origin(item) === origin(value))) return false;
    if (mainFrame && request.navigationPolicy.allowedUrls && request.navigationPolicy.allowedUrls.length > 0 &&
      !request.navigationPolicy.allowedUrls.includes(value)) return false;
    if (mainFrame && request.navigationPolicy.blockedRules.some(rule => matches(value, rule))) return false;
    if (!mainFrame && request.blockedResourceRules.some(rule => matches(value, rule))) return false;
    if (mainFrame && request.security.appBridgeEnabled && !trusted(value, request.security)) return false;
    if (mainFrame && request.security.pageBridgeEnabled) {
      const initial = request.content.type === 'url' ? request.content.url || '' : request.content.baseUrl || '';
      if (!origin(initial) || origin(initial) !== origin(value)) return false;
    }
    return true;
  } catch (_) { return false; }
}

/** 解析并验证完整 JSON 输入，格式或平台不支持的能力抛出 Error；不记录原始正文。 */
export function decodeRequest(raw: string, checkInitialNavigation: boolean = true): WebViewRequest {
  const request = JSON.parse(raw) as WebViewRequest;
  if (!request || !request.content || !request.settings || !request.security || !request.security.trustedOrigins ||
    !Array.isArray(request.security.trustedOrigins.urls) || !Array.isArray(request.security.trustedOrigins.trustedHostSuffixes) ||
    !request.navigationPolicy || !Array.isArray(request.navigationPolicy.allowedSchemes) ||
    !Array.isArray(request.navigationPolicy.blockedRules) || !Array.isArray(request.scripts) || !Array.isArray(request.blockedResourceRules)) {
    throw new Error('Invalid WebViewRequest');
  }
  if (request.pageMessageChannels !== undefined) {
    if (!Array.isArray(request.pageMessageChannels) || request.pageMessageChannels.some(item => typeof item !== 'string')) {
      throw new Error('Invalid page message channels');
    }
    const reserved = ['window', 'self', 'top', 'parent', 'frames', 'document', 'location', 'navigator', 'webkit',
      'globalThis', 'console', 'history', 'performance', 'JSON', 'Object', 'Array', 'Function', 'Promise', 'eval',
      'undefined', 'NaN', 'Infinity', 'onmessage', 'postMessage', 'name', 'constructor', 'prototype',
      'JSAndroidBridge', 'WebViewJavascriptBridge'];
    if (request.pageMessageChannels.length > 16 || new Set(request.pageMessageChannels).size !== request.pageMessageChannels.length ||
      request.pageMessageChannels.some(channel => !/^[a-zA-Z][a-zA-Z0-9_]{0,79}$/.test(channel) ||
        reserved.includes(channel) || channel.startsWith('ComposeWebView') || channel.startsWith('GYWebView'))) {
      throw new Error('Invalid page message channels');
    }
    const initial = request.content.type === 'url' ? request.content.url || '' : request.content.baseUrl || '';
    if (request.pageMessageChannels.length > 0 && !origin(initial)) throw new Error('Page message channels require HTTP(S)');
  }
  if (request.navigationPolicy.allowedOrigins !== undefined && !Array.isArray(request.navigationPolicy.allowedOrigins)) throw new Error('Invalid allowed origins');
  request.navigationPolicy.allowedOrigins?.forEach(item => {
    if (typeof item !== 'string' || !origin(item)) throw new Error('Invalid allowed origin');
  });
  if (request.navigationPolicy.allowedUrls !== undefined && !Array.isArray(request.navigationPolicy.allowedUrls)) throw new Error('Invalid allowed URLs');
  request.navigationPolicy.allowedUrls?.forEach(item => {
    if (typeof item !== 'string' || item !== item.trim() || !origin(item)) throw new Error('Invalid allowed URL');
  });
  const settingValues = request.settings as Record<string, Object>;
  const booleanKeys = ['javaScriptEnabled', 'domStorageEnabled', 'allowFileAccess', 'allowContentAccess', 'acceptsThirdPartyCookies',
    'supportMultipleWindows', 'javaScriptCanOpenWindowsAutomatically', 'mediaPlaybackRequiresUserGesture', 'loadsImagesAutomatically',
    'blockNetworkImage', 'builtInZoomControls', 'displayZoomControls', 'supportZoom', 'useWideViewPort', 'loadWithOverviewMode',
    'followSystemFontScale', 'algorithmicDarkeningAllowed'];
  booleanKeys.forEach(key => { if (settingValues[key] !== undefined && typeof settingValues[key] !== 'boolean') throw new Error('Invalid setting'); });
  const securityFlags = [request.security.appBridgeEnabled, request.security.pageBridgeEnabled, request.security.fileChooserEnabled, request.security.mediaCaptureEnabled];
  securityFlags.forEach(flag => { if (flag !== undefined && typeof flag !== 'boolean') throw new Error('Invalid security flag'); });
  if (request.navigationPolicy.allowNewWindows !== undefined && typeof request.navigationPolicy.allowNewWindows !== 'boolean') throw new Error('Invalid navigation policy');
  if (request.settings.mixedContentPolicy && !['NEVER_ALLOW', 'COMPATIBILITY', 'ALWAYS_ALLOW'].includes(request.settings.mixedContentPolicy)) throw new Error('Invalid mixed content policy');
  if (request.settings.cachePolicy && !['DEFAULT', 'NO_CACHE', 'CACHE_ELSE_NETWORK', 'CACHE_ONLY'].includes(request.settings.cachePolicy)) throw new Error('Invalid cache policy');
  if (request.settings.userAgentSuffix !== undefined && request.settings.userAgentSuffix !== null &&
    (typeof request.settings.userAgentSuffix !== 'string' || /[\r\n]/.test(request.settings.userAgentSuffix))) throw new Error('Invalid User-Agent suffix');
  request.security.trustedOrigins.urls.forEach(item => { if (typeof item !== 'string' || !origin(item).startsWith('https:')) throw new Error('Invalid trusted origin'); });
  request.security.trustedOrigins.trustedHostSuffixes.forEach(item => { if (typeof item !== 'string' || !validHost(item)) throw new Error('Invalid trusted suffix'); });
  request.navigationPolicy.allowedSchemes.forEach(item => { if (typeof item !== 'string' || !item) throw new Error('Invalid scheme'); });
  request.navigationPolicy.blockedRules.concat(request.blockedResourceRules).forEach(rule => {
    if (rule.type === 'hostSuffix' && ((rule.scheme !== undefined && rule.scheme !== null &&
      (typeof rule.scheme !== 'string' || !/^[a-z][a-z0-9+.-]*$/.test(rule.scheme))) ||
      (rule.includeRoot !== undefined && typeof rule.includeRoot !== 'boolean') ||
      (rule.rejectUserInfo !== undefined && typeof rule.rejectUserInfo !== 'boolean'))) throw new Error('Invalid host suffix condition');
    if (!rule || (rule.type === 'contains' ? typeof rule.value !== 'string' || !rule.value || (rule.ignoreCase !== undefined && typeof rule.ignoreCase !== 'boolean') :
      rule.type === 'exactHost' ? typeof rule.host !== 'string' || !validHost(rule.host) :
        rule.type === 'hostSuffix' ? typeof rule.suffix !== 'string' || !validHost(rule.suffix) : true)) throw new Error('Invalid URL rule');
  });
  if (request.content.additionalHeaders) {
    Object.keys(request.content.additionalHeaders).forEach(key => {
      const value = request.content.additionalHeaders?.[key];
      if (!/^[!#$%&'*+.^_`|~0-9a-zA-Z-]+$/.test(key) || typeof value !== 'string' || /[\r\n]/.test(value)) throw new Error('Invalid HTTP header');
    });
  }
  if (request.settings.acceptsThirdPartyCookies === true) throw new Error('Per-view third-party Cookie policy is unsupported');
  if (request.security.appBridgeEnabled && request.security.pageBridgeEnabled) throw new Error('Bridge modes are mutually exclusive');
  if ((request.security.appBridgeEnabled || request.security.pageBridgeEnabled) && request.settings.javaScriptEnabled !== true) {
    throw new Error('Bridge requires JavaScript');
  }
  if (request.settings.allowFileAccess || request.settings.allowContentAccess || request.settings.supportMultipleWindows ||
    request.settings.javaScriptCanOpenWindowsAutomatically || request.navigationPolicy.allowNewWindows) {
    throw new Error('Local URL access and new windows are unsupported');
  }
  const content = request.content;
  if (content.type === 'url') {
    if (typeof content.url !== 'string' || !content.url.trim()) throw new Error('Empty URL');
    if (checkInitialNavigation && !navigationAllowed(content.url, true, request)) throw new Error('Initial URL blocked');
  } else if (content.type === 'html') {
    if (typeof content.html !== 'string' || !content.html.trim()) throw new Error('Empty HTML');
    if (content.baseUrl !== undefined && content.baseUrl !== null && typeof content.baseUrl !== 'string') throw new Error('Invalid HTML baseUrl');
    if (content.historyUrl !== undefined && content.historyUrl !== null && typeof content.historyUrl !== 'string') throw new Error('Invalid historyUrl');
    if (checkInitialNavigation && content.baseUrl && !navigationAllowed(content.baseUrl, true, request)) throw new Error('HTML baseUrl blocked');
    if (checkInitialNavigation && content.historyUrl && !navigationAllowed(content.historyUrl, true, request)) throw new Error('HTML historyUrl blocked');
    if (content.mimeType && content.mimeType !== 'text/html') throw new Error('Only text/html is supported');
  } else throw new Error('Unsupported content type');
  if (request.security.appBridgeEnabled || request.security.fileChooserEnabled || request.security.mediaCaptureEnabled) {
    if (!request.security.trustedOrigins.urls.some(item => origin(item).startsWith('https:')) &&
      !request.security.trustedOrigins.trustedHostSuffixes.some(item => trusted(`https://${item}/`, request.security))) {
      throw new Error('High privilege capability requires trusted HTTPS origins');
    }
  }
  request.scripts.forEach(script => {
    if ((script.onlyForTrustedMainFrame !== undefined && typeof script.onlyForTrustedMainFrame !== 'boolean') || typeof script.id !== 'string' || !/^[a-zA-Z0-9_.-]+$/.test(script.id) || typeof script.source !== 'string' || !script.source.trim() ||
      !['DOCUMENT_START', 'DOM_READY', 'DOCUMENT_FINISHED'].includes(script.injectionTime)) throw new Error('Invalid script');
  });
  return request;
}

/** 自定义脚本沿用手动执行的来源边界；pageBridge 只放行初始同源主文档，不授予高权限。 */
export function guardedScript(script: WebViewScript, request: WebViewRequest): string {
  const exact = JSON.stringify(request.security.trustedOrigins.urls.map(item => origin(item)).filter(item => item.startsWith('https:')));
  const suffixes = JSON.stringify(request.security.trustedOrigins.trustedHostSuffixes.filter(item => /^[a-zA-Z0-9.-]+$/.test(item)));
  const initial = request.content.type === 'url' ? request.content.url || '' : request.content.baseUrl || '';
  const pageOrigin = request.security.pageBridgeEnabled && !request.security.appBridgeEnabled ? origin(initial) : '';
  const trustedGuard = `location.protocol==='https:'&&(${exact}.includes(location.protocol+'//'+location.hostname.toLowerCase().replace(/\\.$/,'')+':'+(location.port||'443'))||${suffixes}.some(function(s){s=s.toLowerCase().replace(/^\\.|\\.$/g,'');return location.hostname===s||location.hostname.endsWith('.'+s)}))`;
  const page = pageOrigin ? new url.URL(pageOrigin) : null;
  const defaultPort = page?.protocol === 'https:' ? '443' : '80';
  const pageGuard = page ? `location.protocol===${JSON.stringify(page.protocol)}&&(location.hostname===${JSON.stringify(page.hostname)}||location.hostname===${JSON.stringify(page.hostname + '.')})&&(location.port||${JSON.stringify(defaultPort)})===${JSON.stringify(page.port || defaultPort)}` : 'false';
  const guard = script.onlyForTrustedMainFrame !== false ? `(${trustedGuard})||(${pageGuard})` : 'true';
  const id = JSON.stringify(script.id);
  const guarded = `if(window===window.top&&(${guard})){window.__GY_WEBVIEW_SCRIPT_IDS__=window.__GY_WEBVIEW_SCRIPT_IDS__||Object.create(null);if(!Object.prototype.hasOwnProperty.call(window.__GY_WEBVIEW_SCRIPT_IDS__,${id})){window.__GY_WEBVIEW_SCRIPT_IDS__[${id}]=true;${script.source}\n}}`;
  return script.injectionTime === 'DOM_READY' ?
    `(function(){if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',function(){${guarded}},{once:true})}else{${guarded}}})();` : guarded;
}

/** 所有异步操作用同一个 generation 校验，在导航、输入变更、隐藏和销毁时撤销。 */
export class CallbackLifetime {
  /** 撤销版本，从 0 开始；调用方在发起异步工作时捕获。 */
  generation: number = 0;
  /** 永久释放标志，初始 false；dispose 后不能复用。 */
  disposed: boolean = false;
  /** 同一 UI 线程撤销所有先前捕获的版本，允许后续新操作。 */
  invalidate(): void { this.generation++; }
  /** 仅在实例存活且版本未变化时允许异步结果交付。 */
  current(generation: number): boolean { return !this.disposed && generation === this.generation; }
  /** 永久释放并推进版本；所有迟到回调均失效。 */
  dispose(): void { this.disposed = true; this.invalidate(); }
}
