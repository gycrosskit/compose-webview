import { url } from '@kit.ArkTS';

export interface WebViewContent {
  type: string; url?: string; additionalHeaders?: Record<string, string>;
  html?: string; baseUrl?: string | null; mimeType?: string; encoding?: string; historyUrl?: string | null;
}
export interface WebViewSettings {
  javaScriptEnabled?: boolean; domStorageEnabled?: boolean; allowFileAccess?: boolean; allowContentAccess?: boolean;
  mixedContentPolicy?: string; cachePolicy?: string; acceptsThirdPartyCookies?: boolean;
  supportMultipleWindows?: boolean; javaScriptCanOpenWindowsAutomatically?: boolean;
  mediaPlaybackRequiresUserGesture?: boolean; loadsImagesAutomatically?: boolean; blockNetworkImage?: boolean;
  supportZoom?: boolean; userAgentSuffix?: string | null;
}
export interface TrustOrigins { urls: string[]; trustedHostSuffixes: string[]; }
export interface WebViewSecurity {
  trustedOrigins: TrustOrigins; appBridgeEnabled?: boolean; pageBridgeEnabled?: boolean;
  fileChooserEnabled?: boolean; mediaCaptureEnabled?: boolean;
}
export interface WebViewUrlRule { type: string; value?: string; ignoreCase?: boolean; host?: string; suffix?: string; }
export interface WebViewScript { id: string; source: string; injectionTime: string; onlyForTrustedMainFrame: boolean; }
export interface NavigationPolicy { allowedSchemes: string[]; allowedOrigins?: string[]; blockedRules: WebViewUrlRule[]; allowNewWindows?: boolean; }
export interface WebViewRequest {
  content: WebViewContent; settings: WebViewSettings; security: WebViewSecurity;
  scripts: WebViewScript[]; blockedResourceRules: WebViewUrlRule[]; navigationPolicy: NavigationPolicy;
}

export function origin(value: string): string {
  try {
    const parsed = new url.URL(value.trim());
    if (!['https:', 'http:'].includes(parsed.protocol) || !parsed.hostname || parsed.username || parsed.password) return '';
    const host = parsed.hostname.toLowerCase().replace(/\.$/, '');
    const port = parsed.port || (parsed.protocol === 'https:' ? '443' : '80');
    if (!/^\d+$/.test(port) || Number(port) < 1 || Number(port) > 65535) return '';
    return `${parsed.protocol}//${host}:${port}`;
  } catch (_) { return ''; }
}

function validHost(value: string): boolean {
  const host = value.trim().replace(/^\.|\.$/g, '').toLowerCase();
  return host.length > 0 && host.split('.').every(label => label.length <= 63 && /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/.test(label));
}

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

export function matches(value: string, rule: WebViewUrlRule): boolean {
  if (rule.type === 'contains') {
    const needle = rule.value || '';
    return needle.length > 0 && (rule.ignoreCase === true ? value.toLowerCase().includes(needle.toLowerCase()) : value.includes(needle));
  }
  try {
    const host = new url.URL(value).hostname.toLowerCase().replace(/\.$/, '');
    const target = (rule.type === 'exactHost' ? rule.host : rule.suffix || '').toLowerCase().replace(/^\.|\.$/g, '');
    return target.length > 0 && (host === target || (rule.type === 'hostSuffix' && host.endsWith(`.${target}`)));
  } catch (_) { return false; }
}

export function navigationAllowed(value: string, mainFrame: boolean, request: WebViewRequest): boolean {
  try {
    const parsed = new url.URL(value);
    const scheme = parsed.protocol.replace(':', '').toLowerCase();
    // WebView 不替宿主启动外部 Ability；即使宿主把自定义 scheme 列入 policy 也拒绝。
    if (!['http', 'https'].includes(scheme) || !origin(value) || !request.navigationPolicy.allowedSchemes.includes(scheme)) return false;
    if (mainFrame && request.navigationPolicy.allowedOrigins && request.navigationPolicy.allowedOrigins.length > 0 &&
      !request.navigationPolicy.allowedOrigins.some(item => origin(item) === origin(value))) return false;
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

export function decodeRequest(raw: string): WebViewRequest {
  const request = JSON.parse(raw) as WebViewRequest;
  if (!request || !request.content || !request.settings || !request.security || !request.security.trustedOrigins ||
    !Array.isArray(request.security.trustedOrigins.urls) || !Array.isArray(request.security.trustedOrigins.trustedHostSuffixes) ||
    !request.navigationPolicy || !Array.isArray(request.navigationPolicy.allowedSchemes) ||
    !Array.isArray(request.navigationPolicy.blockedRules) || !Array.isArray(request.scripts) || !Array.isArray(request.blockedResourceRules)) {
    throw new Error('Invalid WebViewRequest');
  }
  if (request.navigationPolicy.allowedOrigins !== undefined && !Array.isArray(request.navigationPolicy.allowedOrigins)) throw new Error('Invalid allowed origins');
  request.navigationPolicy.allowedOrigins?.forEach(item => {
    if (typeof item !== 'string' || !origin(item)) throw new Error('Invalid allowed origin');
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
    if (!navigationAllowed(content.url, true, request)) throw new Error('Initial URL blocked');
  } else if (content.type === 'html') {
    if (typeof content.html !== 'string' || !content.html.trim()) throw new Error('Empty HTML');
    if (content.baseUrl !== undefined && content.baseUrl !== null && typeof content.baseUrl !== 'string') throw new Error('Invalid HTML baseUrl');
    if (content.historyUrl !== undefined && content.historyUrl !== null && typeof content.historyUrl !== 'string') throw new Error('Invalid historyUrl');
    if (content.baseUrl && !navigationAllowed(content.baseUrl, true, request)) throw new Error('HTML baseUrl blocked');
    if (content.historyUrl && !navigationAllowed(content.historyUrl, true, request)) throw new Error('HTML historyUrl blocked');
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

/** 原生 document-start 注入仍必须在页面内校验 top 与 HTTPS origin，绝不为 iframe 执行业务脚本。 */
export function guardedScript(script: WebViewScript, request: WebViewRequest): string {
  const exact = JSON.stringify(request.security.trustedOrigins.urls.map(item => origin(item)).filter(item => item.startsWith('https:')));
  const suffixes = JSON.stringify(request.security.trustedOrigins.trustedHostSuffixes.filter(item => /^[a-zA-Z0-9.-]+$/.test(item)));
  const guard = script.onlyForTrustedMainFrame !== false ?
    `location.protocol==='https:'&&(${exact}.includes(location.protocol+'//'+location.hostname.toLowerCase().replace(/\\.$/,'')+':'+(location.port||'443'))||${suffixes}.some(function(s){s=s.toLowerCase().replace(/^\\.|\\.$/g,'');return location.hostname===s||location.hostname.endsWith('.'+s)}))` : 'true';
  const guarded = `if(window===window.top&&(${guard})){${script.source}\n}`;
  return script.injectionTime === 'DOM_READY' ?
    `(function(){if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',function(){${guarded}},{once:true})}else{${guarded}}})();` : guarded;
}

/** 所有异步操作用同一个 generation 校验，在导航、输入变更、隐藏和销毁时撤销。 */
export class CallbackLifetime {
  generation: number = 0;
  disposed: boolean = false;
  invalidate(): void { this.generation++; }
  current(generation: number): boolean { return !this.disposed && generation === this.generation; }
  dispose(): void { this.disposed = true; this.invalidate(); }
}
