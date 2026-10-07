const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const os = require('node:os');
const childProcess = require('node:child_process');
const devEco = process.env.WEBVIEW_DEVECO_HOME || '/Applications/DevEco-Studio.app/Contents';
const ts = require(path.join(devEco, 'sdk/default/openharmony/ets/build-tools/ets-loader/node_modules/typescript'));
const harArgument = process.argv.indexOf('--har');
let root = path.resolve(__dirname, '../webview-native/src/main/ets');
if (harArgument >= 0) {
  const extracted = fs.mkdtempSync(path.join(os.tmpdir(), 'gy-webview-har-'));
  childProcess.execFileSync('tar', ['-xf', path.resolve(process.argv[harArgument + 1]), '-C', extracted]);
  root = path.join(extracted, 'package/src/main/ets');
  process.on('exit', () => fs.rmSync(extracted, { recursive: true, force: true }));
}
const deferred = () => { let resolve, reject; const promise = new Promise((r, j) => { resolve = r; reject = j; }); return { promise, resolve, reject }; };
let nextJavascript = null;
let permission = null;
let selectedFile = null;
let capture = null;
let fileSize = 16;
let captureHeaderValid = true;
const deletedFiles = [];
let fileOpens = 0;
let failNextLoad = false;
class Port {
  closed = false;
  close() { this.closed = true; }
  onMessageEvent(callback) { this.callback = callback; }
  message(message) { this.callback?.(message); }
}
class Controller {
  loads = []; data = []; refreshes = 0; url = 'https://trusted.test/page'; ports = []; deliveries = []; scripts = []; scriptContext = null;
  loadUrl(url, headers) { if (failNextLoad) { failNextLoad = false; throw new Error('first load rejected'); } this.loads.push({ url, headers }); this.url = url; }
  loadData(...args) { if (failNextLoad) { failNextLoad = false; throw new Error('first load rejected'); } this.data.push(args); }
  getUrl() { return this.url; }
  getUserAgent() { return 'ArkWeb'; }
  setCustomUserAgent(value) { this.userAgent = value; }
  runJavaScript(source) { this.lastScript = source; this.scripts.push(source); const task = nextJavascript; nextJavascript = null; const execute = () => { if (this.scriptContext) vm.runInNewContext(source, this.scriptContext); return 'null'; }; if (task?.deferExecution) return task.promise.then(execute); execute(); return task ? task.promise : Promise.resolve('null'); }
  createWebMessagePorts() { const ports = [new Port(), new Port()]; this.ports.push(ports); return ports; }
  postMessage(name, ports, origin) { this.deliveries.push({ name, ports, origin }); }
  accessBackward() { return true; } accessForward() { return false; }
  stop() {} stopAllMedia() {} closeAllMediaPresentations() {} onInactive() {} onActive() {} refresh() { this.refreshes++; } backward() {} forward() {}
}
const contexts = [];
class BaseView {
  getUIContext() { return { getHostContext: () => ({ cacheDir: "/cache" }), postFrameCallback: callback => contexts.push(callback) }; }
  setProp() { return false; } onDestroy() {} call() {}
}
const windowObject = { Orientation: { UNSPECIFIED: 0, AUTO_ROTATION_LANDSCAPE: 1 }, getLastWindow: async () => ({}) };
const mocks = {
  '@kit.ArkData': { uniformTypeDescriptor: { getUniformDataTypeByFilenameExtension: value => value, getTypeDescriptor: value => ({ mimeTypes: value === '.pdf' ? ['application/pdf'] : value === '.jpg' ? ['image/jpeg'] : [] }) } },
  '@kit.CameraKit': { camera: { CameraPosition: { CAMERA_POSITION_BACK: 1 } }, cameraPicker: { PickerMediaType: { PHOTO: 'photo', VIDEO: 'video' }, pick: (context, types, profile) => { capture.profile = profile; capture.types = types; return capture.promise; } } },
  '@kit.ArkTS': { url: { URL }, util: { generateRandomUUID: () => 'test-capture', TextEncoder: class { encodeInto(value) { return new TextEncoder().encode(value); } } } },
  '@kuikly-open/render': { KuiklyRenderBaseView: BaseView },
  '@ohos.arkui.node': { ComponentContent: class {} },
  '@kit.ArkWeb': { webview: { WebviewController: Controller } },
  '@kit.ArkUI': { window: windowObject, FrameCallback: class {} },
  '@kit.AbilityKit': { abilityAccessCtrl: { createAtManager: () => ({ requestPermissionsFromUser: () => permission.promise }) } },
  '@kit.CoreFileKit': { fileIo: { OpenMode: { CREATE: 1, READ_WRITE: 2, READ_ONLY: 0 }, openSync: path => { fileOpens++; return { fd: path }; }, closeSync() {}, statSync: () => ({ size: fileSize }), readSync: (fd, buffer) => { const bytes = new Uint8Array(buffer); if (captureHeaderValid) { if (String(fd).endsWith('.mp4')) { bytes.set([0x66, 0x74, 0x79, 0x70], 4); } else bytes.set([0xff, 0xd8, 0xff]); } return 12; }, unlinkSync: path => deletedFiles.push(path) }, fileUri: { FileUri: class { constructor(uri) { this.name = uri.split('/').at(-1); } }, getUriFromPath: path => 'file://' + path }, picker: { DocumentSelectOptions: class {}, DocumentViewPicker: class { select() { return selectedFile.promise; } } } },
  './WebViewComponent': { createGYWebView() {} },
};
// 使用组件真实 Window owner，替身仅隔离系统 Window/ArkWeb。
const policySource = process.env.WEBVIEW_WINDOW_POLICY_SOURCE || path.resolve(__dirname,
  '../webview-native/oh_modules/@gycrosskit/system-actions-native/src/main/ets/WindowPolicy.ets');
const policyExports = {};
vm.runInNewContext(ts.transpileModule(fs.readFileSync(policySource, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
}).outputText, { exports: policyExports, require: name => mocks[name] });
mocks['@gycrosskit/system-actions-native'] = policyExports;
const cache = {};
function load(file) {
  if (cache[file]) return cache[file];
  const source = fs.readFileSync(path.join(root, file), 'utf8');
  const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS, experimentalDecorators: true } }).outputText;
  const exports = {};
  vm.runInNewContext(compiled, { exports, require: name => name === './WebViewWire' ? load('WebViewWire.ts') : mocks[name],
    Observed: value => value, ProtectedResourceType: { VIDEO_CAPTURE: 'video', AUDIO_CAPTURE: 'audio' },
    FileSelectorMode: { FileOpenMultipleMode: 1 }, console, Set, Map, Promise,
    WebResourceResponse: class { setResponseCode() {} setReasonMessage() {} setResponseData() {} },
  }, { filename: file });
  cache[file] = exports; return exports;
}
const wire = load('WebViewWire.ts');
function request(overrides = {}) {
  return { content: { type: 'url', url: 'https://trusted.test/page', additionalHeaders: {} },
    settings: { javaScriptEnabled: false }, security: { trustedOrigins: { urls: ['https://trusted.test'], trustedHostSuffixes: [] }, appBridgeEnabled: false, pageBridgeEnabled: false, fileChooserEnabled: false, mediaCaptureEnabled: false },
    scripts: [], blockedResourceRules: [], navigationPolicy: { allowedSchemes: ['https'], blockedRules: [], allowNewWindows: false }, ...overrides };
}
const encoded = value => JSON.stringify(value);
const requestForNavigation = (value, gesture = true) => ({ getRequestUrl: () => value, isMainFrame: () => true, isRequestGesture: () => gesture });
(async () => {
  const standard = request();
  const strictMallRule = { type: 'hostSuffix', suffix: 'jd.com', scheme: 'https', includeRoot: false, rejectUserInfo: true };
  for (const value of ['https://user@shop.jd.com', 'https://@shop.jd.com', 'https://:@shop.jd.com', 'https://shop.jd.com.evil', 'https://shop.jd.com..', 'https://shop..jd.com', 'http://shop.jd.com', 'https://jd.com']) assert.equal(wire.matches(value, strictMallRule), false, value);
  assert.equal(wire.matches('https://shop.jd.com:8443/item', strictMallRule), true);
  assert.equal(wire.matches('https://user@shop.jd.com', { ...strictMallRule, rejectUserInfo: false }), true);

  for (const [raw, canonical] of [
    ['2001:0db8:0000:0:0:0:0:1', '2001:db8::1'], ['::ffff:192.0.2.1', '::ffff:c000:201'],
    ['0:0:0:0:0:0:0:0', '::'], ['1:0:0:2:0:0:3:4', '1::2:0:0:3:4'],
  ]) {
    assert.equal(wire.origin(`https://[${raw}]:8443`), `https://[${canonical}]:8443`);
    const security = { trustedOrigins: { urls: [`https://[${raw}]:8443`], trustedHostSuffixes: [] } };
    assert.equal(wire.trusted(`https://[${canonical}]:8443/path`, security), true);
  }
  for (const host of ['1::2::3', '1:2:3', '::ffff:192.00.2.1', '::ffff:256.0.0.1'])
    assert.equal(wire.origin(`https://[${host}]`), '');
  assert.equal(wire.navigationAllowed('https://trusted.test/next', true, standard), true);
  for (const value of ['javascript:alert(1)', 'file:///etc/passwd', 'data:text/html,evil', 'https://user@trusted.test/', 'https://@trusted.test/', 'https://:@trusted.test/', 'https://trusted.test../', 'https://sub..trusted.test/', 'http://trusted.test/']) {
    assert.equal(wire.navigationAllowed(value, true, standard), false, value);
  }
  assert.equal(wire.trusted('https://trusted.test.evil/', standard.security), false);
  assert.equal(wire.trusted('https://trusted.test:444/', standard.security), false);
  assert.equal(wire.trusted('https://trusted.test./', standard.security), true);
  assert.equal(wire.trusted('https://user@trusted.test/', standard.security), false);
  const suffixSecurity = { trustedOrigins: { urls: [], trustedHostSuffixes: ['trusted.test'] } };
  assert.equal(wire.trusted('https://child.trusted.test:444/', suffixSecurity), true);
  assert.equal(wire.trusted('https://trusted.test.evil/', suffixSecurity), false);
  assert.throws(() => wire.decodeRequest(encoded(request({ security: { ...standard.security, appBridgeEnabled: true } }))));
  assert.throws(() => wire.decodeRequest(encoded(request({ settings: { allowFileAccess: true } }))));
  assert.throws(() => wire.decodeRequest(encoded(request({ content: { type: 'html', html: '<p>x</p>', baseUrl: 'javascript:evil' } }))));
  const filtered = request({ navigationPolicy: { allowedSchemes: ['https'], blockedRules: [{ type: 'hostSuffix', suffix: 'evil.test' }] } });
  assert.equal(wire.navigationAllowed('https://child.evil.test/', true, filtered), false);
  const mallRule = { type: 'hostSuffix', suffix: 'jd.com', scheme: 'https', includeRoot: false };
  for (const value of ['https://shop.jd.com/item', 'https://a.b.jd.com/item']) assert.equal(wire.matches(value, mallRule), true);
  for (const value of ['http://shop.jd.com/item', 'https://jd.com/item', 'https://shop.jd.com.evil/item']) assert.equal(wire.matches(value, mallRule), false);
  const mallRequest = request({ navigationPolicy: { allowedSchemes: ['http', 'https'], blockedRules: [mallRule] } });
  assert.equal(wire.decodeRequest(encoded(mallRequest)).navigationPolicy.blockedRules[0].includeRoot, false);
  assert.equal(wire.navigationAllowed('https://shop.jd.com/item', true, mallRequest), false);
  assert.equal(wire.navigationAllowed('https://jd.com/item', true, mallRequest), true);
  assert.throws(() => wire.decodeRequest(encoded(request({ blockedResourceRules: [{ ...mallRule, includeRoot: 0 }] }))));
  const readOnly = request({ navigationPolicy: { allowedSchemes: ['http', 'https'], allowedOrigins: ['HTTPS://TRUSTED.TEST:443/protocol'], blockedRules: [{ type: 'contains', value: '/mall/' }] } });
  assert.equal(wire.decodeRequest(encoded(readOnly)).settings.javaScriptEnabled, false);
  assert.equal(wire.navigationAllowed('https://trusted.test/next', true, readOnly), true);
  assert.equal(wire.navigationAllowed('https://trusted.test:443/next', true, readOnly), true);
  assert.equal(wire.navigationAllowed('HTTPS://TRUSTED.TEST/next', true, readOnly), true);
  for (const value of ['http://trusted.test/', 'https://trusted.test:444/', 'https://evil.test/', 'https://trusted.test.evil/', 'https://trusted.test/mall/item']) {
    assert.equal(wire.navigationAllowed(value, true, readOnly), false, value);
  }
  const readOnlyHttp = request({ content: { type: 'url', url: 'http://trusted.test/protocol' }, navigationPolicy: { allowedSchemes: ['http', 'https'], allowedOrigins: ['http://trusted.test:80'], blockedRules: [] } });
  assert.equal(wire.decodeRequest(encoded(readOnlyHttp)).content.url, 'http://trusted.test/protocol');
  assert.equal(wire.navigationAllowed('http://trusted.test/next', true, readOnlyHttp), true);
  assert.equal(wire.navigationAllowed('https://trusted.test/', true, readOnlyHttp), false);
  for (const allowedOrigins of [null, 'https://trusted.test', ['javascript:evil'], ['https://user@trusted.test/'], ['https://trusted.test:0/'], ['https://trusted.test:65536/'], [5]]) {
    assert.throws(() => wire.decodeRequest(encoded(request({ navigationPolicy: { allowedSchemes: ['https'], allowedOrigins, blockedRules: [] } }))));
  }
  const guarded = { id: 'contract', source: 'window.executions=(window.executions||0)+1;',
    injectionTime: 'DOCUMENT_START', onlyForTrustedMainFrame: true };
  for (const initial of ['http://legacy.test/captcha', 'http://legacy.test:8080/captcha', 'https://legacy.test:8443/captcha']) {
    const page = request({ content: { type: 'url', url: initial }, settings: { javaScriptEnabled: true },
      security: { trustedOrigins: { urls: [], trustedHostSuffixes: [] }, pageBridgeEnabled: true },
      navigationPolicy: { allowedSchemes: ['http', 'https'], allowedUrls: [initial], blockedRules: [] } });
    assert.equal(wire.decodeRequest(encoded(page)).content.url, initial);
    for (const [target, main, allowed] of [[initial, true, true], [initial, false, false],
      ['http://legacy.test:9090/captcha', true, false], ['https://evil.test/captcha', true, false]]) {
      const window = {}; window.top = main ? window : {};
      vm.runInNewContext(wire.guardedScript(guarded, page), { window, location: new URL(target) });
      assert.equal(window.executions || 0, allowed ? 1 : 0, `${target} main=${main}`);
    }
    for (const target of [initial.replace('/captcha', '/other'), initial + '?extra=1', initial + '#next']) {
      assert.equal(wire.navigationAllowed(target, true, page), false, target);
      assert.equal(wire.navigationAllowed(target, false, page), true, target);
    }
    assert.equal(wire.navigationAllowed(initial, true, { ...page, navigationPolicy: { ...page.navigationPolicy, blockedRules: [{ type: 'contains', value: '/captcha' }] } }), false);
    const mixed = { ...page, security: { ...page.security, appBridgeEnabled: true } };
    assert.throws(() => wire.decodeRequest(encoded(mixed)));
    const window = {}; window.top = window;
    vm.runInNewContext(wire.guardedScript(guarded, mixed), { window, location: new URL(initial) });
    assert.equal(window.executions || 0, 0, 'Invalid mixed Bridge flags must not grant page scope');
  }
  for (const allowedUrls of [null, 'https://trusted.test/page', [1], ['javascript:evil'], ['http://user@legacy.test/captcha'], ['http://legacy.test:0/captcha'], [' https://trusted.test/page']]) {
    assert.throws(() => wire.decodeRequest(encoded(request({ navigationPolicy: { allowedSchemes: ['http', 'https'], allowedUrls, blockedRules: [] } }))));
  }
  assert.throws(() => wire.decodeRequest(encoded(request({ security: { trustedOrigins: { urls: ['http://legacy.test'], trustedHostSuffixes: [] }, appBridgeEnabled: true } }))));
  for (const [target, mainFrame, expected] of [
    ['https://trusted.test/page', true, 1], ['https://trusted.test:444/page', true, 0],
    ['https://trusted.test.evil/page', true, 0], ['http://trusted.test/page', true, 0],
    ['https://trusted.test/page', false, 0],
  ]) {
    const location = new URL(target); const window = {}; window.top = mainFrame ? window : {};
    vm.runInNewContext(wire.guardedScript(guarded, standard), { window, location });
    vm.runInNewContext(wire.guardedScript(guarded, standard), { window, location });
    assert.equal(window.executions || 0, expected, `${target} mainFrame=${mainFrame} executes once`);
  }
  const prototypeIdPage = {}; prototypeIdPage.top = prototypeIdPage;
  const prototypeIdContext = { window: prototypeIdPage, location: new URL('https://trusted.test/page') };
  const prototypeIdScript = wire.guardedScript({ ...guarded, id: '__proto__' }, standard);
  vm.runInNewContext(prototypeIdScript, prototypeIdContext); vm.runInNewContext(prototypeIdScript, prototypeIdContext);
  assert.equal(prototypeIdPage.executions, 1, '__proto__ script id uses own-property per-document deduplication');
  const { GYWebView } = load('GYWebView.ets');
  const updatedPolicyView = new GYWebView();
  updatedPolicyView.setProp('request', encoded(standard));
  updatedPolicyView.onControllerAttached(); updatedPolicyView.onPageVisible('https://trusted.test/page');
  const policyController = updatedPolicyView.controller;
  const policyLoads = policyController.loads.length;
  const currentRequest = updatedPolicyView.request;
  const policyToken = updatedPolicyView.renderToken;
  updatedPolicyView.setProp('request', encoded(request({ navigationPolicy: { ...standard.navigationPolicy, blockedRules: [{ type: 'exactHost', host: 'trusted.test' }] } })));
  assert.equal(updatedPolicyView.controller, policyController);
  assert.equal(updatedPolicyView.renderToken, policyToken);
  assert.equal(updatedPolicyView.request.navigationPolicy.blockedRules.length, 1);
  assert.equal(updatedPolicyView.request, currentRequest);
  assert.equal(policyController.loads.length, policyLoads);
  assert.equal(updatedPolicyView.interceptNavigation(requestForNavigation('https://trusted.test/next')), true);
  updatedPolicyView.onDestroy();

  // 生产 ArkTS + 系统 Controller 替身：初始化与可见业务能力使用不同门禁。
  const scriptRequest = request({ settings: { javaScriptEnabled: true }, security: { ...standard.security, appBridgeEnabled: true },
    scripts: [
      { id: 'start', source: 'window.started=(window.started||0)+1;', injectionTime: 'DOCUMENT_START', onlyForTrustedMainFrame: true },
      { id: 'ready', source: 'window.ready=(window.ready||0)+1;', injectionTime: 'DOM_READY', onlyForTrustedMainFrame: true },
      { id: 'finished', source: 'window.finished=(window.finished||0)+1;', injectionTime: 'DOCUMENT_FINISHED', onlyForTrustedMainFrame: true },
    ] });
  const hidden = new GYWebView(); const hiddenEvents = [];
  hidden.setProp('onEvent', event => hiddenEvents.push(event)); hidden.setProp('visible', false);
  hidden.setProp('request', encoded(scriptRequest)); hidden.onControllerAttached(); hidden.onPageBegin('https://trusted.test/page');
  const documentListeners = {}; const pageListeners = {}; const page = { addEventListener: (name, callback) => pageListeners[name] = callback }; page.top = page;
  const document = { readyState: 'loading', addEventListener: (name, callback) => { (documentListeners[name] ||= []).push(callback); } };
  page.dispatchEvent = event => pageListeners[event.type]?.(event);
  const hiddenContext = { window: page, document, location: new URL('https://trusted.test/page'), TextEncoder, Event: class { constructor(type) { this.type = type; } } };
  hidden.controller.scriptContext = hiddenContext;
  const startScripts = hidden.documentStartScripts();
  assert.equal(startScripts.length, 3, 'DOM_READY is registered at actual document-start');
  startScripts.forEach(item => vm.runInNewContext(item.script, hiddenContext));
  assert.equal(page.started, 1); assert.equal(page.ready, undefined);
  assert.equal(page.GYWebViewBridge.postMessage('hidden-init', '{}'), false, 'hidden-start bootstrap never queues business messages');
  document.readyState = 'interactive'; documentListeners.DOMContentLoaded.forEach(callback => callback());
  assert.equal(page.ready, 1, 'real DOMContentLoaded initializes while hidden');
  document.readyState = 'complete'; hidden.onPageEnd('https://trusted.test/page'); hidden.onPageEnd('https://trusted.test/page');
  hidden.onPageVisible('https://trusted.test/page');
  assert.equal(page.ready, 1); assert.equal(page.finished, 1, 'repeated native phases execute named scripts once per document');
  assert.equal(hidden.controller.deliveries.length, 0, 'hidden initialization cannot open a business channel');
  const initializedController = hidden.controller; const beforeShowScripts = initializedController.scripts.length;
  hidden.setProp('visible', true); await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
  assert.equal(hidden.controller, initializedController); assert.equal(initializedController.refreshes, 0);
  assert.equal(initializedController.deliveries.length, 1); assert.equal(page.ready, 1); assert.equal(page.finished, 1);
  assert.equal(initializedController.scripts.length, beforeShowScripts + 2, 'show synchronizes visibility before bridge bootstrap');
  const delayedHide = deferred(); delayedHide.deferExecution = true; nextJavascript = delayedHide;
  hidden.setProp('visible', false); hidden.setProp('visible', true);
  await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
  const currentVisibilityRevision = page.__GY_WEBVIEW_VISIBILITY_REVISION__;
  assert.equal(page.__GY_WEBVIEW_VISIBLE__, true);
  delayedHide.resolve('null'); await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
  assert.equal(page.__GY_WEBVIEW_VISIBLE__, true, 'late-executing hide cannot overwrite newer show');
  assert.equal(page.__GY_WEBVIEW_VISIBILITY_REVISION__, currentVisibilityRevision);
  hidden.setProp('visible', false);
  const lateScript = deferred(); nextJavascript = lateScript;
  hidden.onPageVisible('https://trusted.test/page');
  hidden.call('stopLoading', null, () => {}); const beforeCancelledFailure = hiddenEvents.length;
  lateScript.reject(new Error('late script failure')); await Promise.resolve(); await Promise.resolve();
  assert.equal(hiddenEvents.length, beforeCancelledFailure, 'cancelled script completion cannot emit a late failure');
  const oldToken = hidden.renderToken; const oldController = hidden.controller;
  hidden.setProp('request', encoded(scriptRequest));
  assert.equal(hidden.isRenderActive(oldToken), false, 'old render cannot enter production page callbacks');
  assert.equal(oldController.ports[0][0].closed, true);
  hidden.onControllerAttached(); hidden.onPageBegin('https://trusted.test/page');
  hidden.onDestroy(); const beforeLateEnd = hidden.controller.scripts.length;
  hidden.onPageEnd('https://trusted.test/page');
  assert.equal(hidden.controller.scripts.length, beforeLateEnd, 'destroyed document cannot initialize');
  const noVisibleCallback = new GYWebView(); noVisibleCallback.setProp('visible', false);
  noVisibleCallback.setProp('request', encoded(scriptRequest)); noVisibleCallback.onControllerAttached();
  noVisibleCallback.onPageBegin('https://trusted.test/page'); noVisibleCallback.onPageEnd('https://trusted.test/page');
  assert.equal(noVisibleCallback.controller.scripts.length, 2, 'page-end supplies DOM_READY fallback when visible callback is absent');
  noVisibleCallback.onDestroy();
  const disabledScripts = new GYWebView(); disabledScripts.setProp('request', encoded(request({ scripts: scriptRequest.scripts })));
  disabledScripts.onControllerAttached(); disabledScripts.onPageEnd('https://trusted.test/page');
  assert.equal(disabledScripts.documentStartScripts().length, 0); assert.equal(disabledScripts.controller.scripts.length, 0); disabledScripts.onDestroy();
  // 系统替身执行 HAR 中的真实实现，首航失败必须重建，保留原请求且只提交一次。
  for (const content of [
    { type: 'url', url: 'https://trusted.test/retry', additionalHeaders: { Authorization: 'test-only' } },
    { type: 'html', html: '<p>retry</p>', baseUrl: 'https://trusted.test/base/', mimeType: 'text/html', encoding: 'UTF-8', historyUrl: 'https://trusted.test/history' },
  ]) {
    const retryView = new GYWebView(); const retryEvents = [];
    retryView.setProp('onEvent', event => retryEvents.push(event));
    retryView.setProp('request', encoded(request({ content })));
    const failedController = retryView.controller; const failedToken = retryView.renderToken;
    failNextLoad = true; retryView.onControllerAttached(failedToken);
    assert.equal(retryEvents.filter(event => event.type === 'loadFailed').length, 1);
    retryView.call('reload', null, () => {});
    assert.notEqual(retryView.controller, failedController, 'failed first load must rebuild Controller');
    assert.equal(failedController.refreshes, 0, 'reload must not refresh an unsubmitted document');
    assert.equal(retryView.isRenderActive(failedToken), false);
    retryView.onControllerAttached(failedToken);
    assert.equal(retryView.controller.loads.length + retryView.controller.data.length, 0, 'old attached callback cannot submit');
    retryView.onControllerAttached(retryView.renderToken);
    retryView.onControllerAttached(retryView.renderToken);
    assert.equal(retryView.controller.loads.length + retryView.controller.data.length, 1, 'current attachment submits once');
    if (content.type === 'url') {
      assert.equal(retryView.controller.loads[0].url, content.url);
      assert.deepEqual(Array.from(retryView.controller.loads[0].headers, header => ({ ...header })), [{ headerKey: 'Authorization', headerValue: 'test-only' }]);
    } else assert.deepEqual(Array.from(retryView.controller.data[0]), ['<p>retry</p>', 'text/html', 'UTF-8', 'https://trusted.test/base/', 'https://trusted.test/history']);
    const submittedController = retryView.controller;
    retryView.onPageBegin('https://trusted.test/retry'); retryView.call('reload', null, () => {});
    assert.equal(retryView.controller, submittedController, 'submitted reload preserves controller/history');
    assert.equal(submittedController.refreshes, 1);
    retryView.setProp('request', encoded(standard));
    retryView.onControllerAttached(failedToken);
    assert.equal(retryView.controller.loads.length, 0, 'new request ignores old attachment');
    retryView.onDestroy(); retryView.onControllerAttached(retryView.renderToken);
    assert.equal(retryView.controller.loads.length, 0, 'destroy ignores attachment');
  }
  const policyView = new GYWebView(); const policyEvents = [];
  policyView.setProp('onEvent', event => policyEvents.push(event)); policyView.setProp('request', encoded(readOnly)); policyView.onControllerAttached();
  assert.equal(policyView.interceptNavigation(requestForNavigation('https://trusted.test/next')), false);
  assert.equal(policyView.interceptNavigation(requestForNavigation('https://trusted.test:444/next')), true);
  assert.equal(policyEvents.at(-1).blocked, true); policyView.onDestroy();
  const view = new GYWebView(); const events = [];
  view.setProp('onEvent', event => events.push(event));
  const html = request({ navigationPolicy: readOnly.navigationPolicy, content: { type: 'html', html: '<p>hello</p>', baseUrl: 'https://trusted.test/relative/', mimeType: 'text/html', encoding: 'UTF-8', historyUrl: 'https://trusted.test/history' } });
  view.setProp('request', encoded(html));
  assert.equal(view.controller.data.length, 0, 'HTML must wait for controller attachment');
  view.onControllerAttached();
  assert.deepEqual(Array.from(view.controller.data[0]), ['<p>hello</p>', 'text/html', 'UTF-8', 'https://trusted.test/relative/', 'https://trusted.test/history']);
  assert.equal(view.interceptNavigation(requestForNavigation('data:text/html,internal', false)), false);
  assert.equal(view.interceptNavigation(requestForNavigation('data:text/html,evil')), true, 'HTML allowance is consumed exactly once');
  const htmlGesture = new GYWebView(); htmlGesture.setProp('request', encoded(html)); htmlGesture.onControllerAttached();
  assert.equal(htmlGesture.interceptNavigation(requestForNavigation('data:text/html,gesture')), true, 'user data navigation never receives the internal allowance');
  assert.equal(htmlGesture.interceptNavigation(requestForNavigation('data:text/html,late', false)), true, 'first rejected main-frame navigation consumes allowance'); htmlGesture.onDestroy();
  const htmlOtherMime = new GYWebView(); htmlOtherMime.setProp('request', encoded(html)); htmlOtherMime.onControllerAttached();
  assert.equal(htmlOtherMime.interceptNavigation(requestForNavigation('data:image/svg+xml,evil', false)), true, 'only internal HTML MIME gets the first-load allowance');
  assert.equal(htmlOtherMime.interceptNavigation(requestForNavigation('data:text/html,late', false)), true); htmlOtherMime.onDestroy();
  let historyResult;
  view.call('goBack', null, result => historyResult = result);
  assert.equal(typeof historyResult, 'object'); assert.equal(historyResult.result, true);
  assert.equal(events.some(event => event.type === 'historyChanged' && event.canGoBack === true), true);
  let popupController = 'unset';
  view.rejectNewWindow({ targetUrl: 'https://trusted.test/popup', isUserTrigger: true, handler: { setWebController: controller => popupController = controller } });
  assert.equal(popupController, null, 'popup must be cancelled synchronously');
  assert.equal(events.at(-1).target, 'NEW_WINDOW'); assert.equal(events.at(-1).blocked, true);
  const bridgeRequest = request({ settings: { javaScriptEnabled: true }, security: { ...standard.security, appBridgeEnabled: true } });
  view.setProp('request', encoded(bridgeRequest));
  assert.equal(view.controller.loads.length, 0, 'new controller must wait for attachment');
  assert.equal(view.isRenderActive(view.renderToken - 1), false, 'old same-origin document cannot authorize new request');
  view.onControllerAttached();
  view.onPageBegin('https://trusted.test/page'); view.onPageVisible('https://trusted.test/page');
  await Promise.resolve(); await Promise.resolve();
  assert.equal(view.controller.deliveries.length, 1);
  assert.equal(view.controller.deliveries[0].origin, 'https://trusted.test');
  const oldPorts = view.controller.ports[0];
  oldPorts[0].message(encoded({ handlerName: 'echo', data: '{}' }));
  const beforeHide = events.filter(event => event.type === 'bridgeMessage').length;
  assert.equal(beforeHide, 1);
  view.setProp('visible', false);
  assert.equal(oldPorts[0].closed, true); assert.equal(oldPorts[1].closed, true);
  oldPorts[0].message(encoded({ handlerName: 'stale', data: '{}' }));
  assert.equal(events.filter(event => event.type === 'bridgeMessage').length, beforeHide);
  view.setProp('visible', true); await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
  assert.equal(view.controller.deliveries.length, 2, 'visible resumes same-document handshake');
  oldPorts[0].message(encoded({ handlerName: 'still-stale', data: '{}' }));
  assert.equal(events.filter(event => event.type === 'bridgeMessage').length, beforeHide);
  const visibilityRace = new GYWebView(); visibilityRace.setProp('visible', false);
  visibilityRace.setProp('request', encoded(bridgeRequest)); visibilityRace.onControllerAttached();
  visibilityRace.onPageEnd('https://trusted.test/page');
  const showSync = deferred(); nextJavascript = showSync;
  visibilityRace.setProp('visible', true); visibilityRace.onPageEnd('https://trusted.test/page');
  await Promise.resolve(); await Promise.resolve();
  assert.equal(visibilityRace.controller.deliveries.length, 0, 'page-end cannot handshake before visibility synchronization');
  showSync.resolve('null'); await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
  assert.equal(visibilityRace.controller.deliveries.length, 1, 'current visibility synchronization opens one channel');
  visibilityRace.setProp('visible', false);
  const staleShowSync = deferred(); nextJavascript = staleShowSync;
  visibilityRace.setProp('visible', true); visibilityRace.setProp('visible', false); visibilityRace.setProp('visible', true);
  await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
  assert.equal(visibilityRace.controller.deliveries.length, 2);
  staleShowSync.resolve('null'); await Promise.resolve(); await Promise.resolve();
  assert.equal(visibilityRace.controller.deliveries.length, 2, 'stale hide/show generation never opens another channel');
  visibilityRace.setProp('visible', false); const replacedShow = deferred(); nextJavascript = replacedShow;
  visibilityRace.setProp('visible', true); const replacedController = visibilityRace.controller;
  visibilityRace.setProp('request', encoded(bridgeRequest)); replacedShow.resolve('null');
  await Promise.resolve(); await Promise.resolve();
  assert.equal(replacedController.deliveries.length, 2, 'replaced controller completion never flushes old messages'); visibilityRace.onDestroy();
  const navigationDuringSync = new GYWebView(); navigationDuringSync.setProp('visible', false);
  navigationDuringSync.setProp('request', encoded(bridgeRequest)); navigationDuringSync.onControllerAttached();
  const navigationSync = deferred(); nextJavascript = navigationSync;
  navigationDuringSync.setProp('visible', true); navigationDuringSync.onPageBegin('https://trusted.test/next');
  navigationSync.resolve('null'); await Promise.resolve(); await Promise.resolve();
  navigationDuringSync.onPageEnd('https://trusted.test/next'); await Promise.resolve(); await Promise.resolve();
  assert.equal(navigationDuringSync.controller.deliveries.length, 1, 'new document resets cancelled visibility wait and handshakes');
  navigationDuringSync.onDestroy();
  const evalTask = deferred(); nextJavascript = evalTask; let late = 0;
  view.call('evaluateJavascript', encoded({ script: '1+1' }), () => late++);
  view.setProp('request', encoded(standard)); evalTask.resolve('2'); await Promise.resolve();
  assert.equal(late, 0, 'request change revokes JS completion');
  const resultBeforeDispose = events.length;
  view.onDestroy(); view.onPageEnd('https://trusted.test/late');
  assert.equal(events.length, resultBeforeDispose, 'dispose suppresses late events');
  const lifetime = new wire.CallbackLifetime(); const generation = lifetime.generation;
  lifetime.invalidate(); assert.equal(lifetime.current(generation), false); lifetime.dispose(); assert.equal(lifetime.current(lifetime.generation), false);

  const mediaView = new GYWebView();
  const mediaPolicy = request({ settings: { javaScriptEnabled: true }, security: { ...standard.security, mediaCaptureEnabled: true, fileChooserEnabled: true } });
  mediaView.setProp('request', encoded(mediaPolicy)); mediaView.onControllerAttached(); mediaView.onPageVisible('https://trusted.test/page');
  const captureEvents = []; mediaView.setProp('onEvent', event => captureEvents.push(event));
  let captureCompletions = 0;
  permission = deferred(); capture = deferred();
  const captureResponses = [];
  mediaView.selectFile({ fileSelector: { isCapture: () => true, getAcceptType: () => ['image/*'] }, result: { handleFileList: files => { captureCompletions++; captureResponses.push(Array.from(files)); } } });
  const captureController = mediaView.controller;
  const captureGeneration = mediaView.lifetime.generation;
  mediaView.setProp('request', encoded({ ...mediaPolicy, navigationPolicy: { ...mediaPolicy.navigationPolicy, blockedRules: [strictMallRule] } }));
  assert.equal(mediaView.controller, captureController); assert.equal(mediaView.lifetime.generation, captureGeneration);
  permission.resolve({ authResults: [0] }); await Promise.resolve(); await Promise.resolve();
  assert.equal(capture.types[0], 'photo');
  capture.resolve({ resultCode: 0, resultUri: capture.profile.saveUri, mediaType: 'photo' });
  await Promise.resolve(); await Promise.resolve();
  assert.equal(captureCompletions, 1); assert.deepEqual(captureResponses, [[capture.profile.saveUri]]);
  assert.equal(captureEvents.some(event => event.type === 'capabilityUnsupported'), false);
  const capturedPath = capture.profile.saveUri.replace('file://', '');
  mediaView.setProp('visible', false); assert.ok(deletedFiles.includes(capturedPath)); mediaView.setProp('visible', true);
  for (const revoke of ['navigate', 'request', 'hide', 'destroy']) {
    const captured = new GYWebView(); captured.setProp('request', encoded(mediaPolicy)); captured.onControllerAttached(); captured.onPageVisible('https://trusted.test/page');
    permission = deferred(); capture = deferred(); const replies = [];
    captured.selectFile({ fileSelector: { isCapture: () => true, getAcceptType: () => ['image/jpeg'] }, result: { handleFileList: files => replies.push(Array.from(files)) } });
    permission.resolve({ authResults: [0] }); await Promise.resolve(); await Promise.resolve();
    const savedUri = capture.profile.saveUri;
    if (revoke === 'navigate') captured.onPageBegin('https://trusted.test/next');
    else if (revoke === 'request') captured.setProp('request', encoded(mediaPolicy));
    else if (revoke === 'hide') captured.setProp('visible', false);
    else captured.onDestroy();
    capture.resolve({ resultCode: 0, resultUri: savedUri, mediaType: 'photo' }); await Promise.resolve(); await Promise.resolve();
    assert.deepEqual(replies, [[]], `${revoke} discards late capture exactly once`);
    assert.ok(deletedFiles.includes(savedUri.replace('file://', '')), `${revoke} cleans output`); captured.onDestroy();
  }
  for (const reason of ['denied', 'cancel', 'wrongUri', 'wrongType', 'badHeader', 'empty', 'oversize']) {
    const captured = new GYWebView(); const capturedEvents = []; captured.setProp('onEvent', event => capturedEvents.push(event));
    captured.setProp('request', encoded(mediaPolicy)); captured.onControllerAttached(); captured.onPageVisible('https://trusted.test/page');
    permission = deferred(); capture = deferred(); const replies = [];
    captured.selectFile({ fileSelector: { isCapture: () => true, getAcceptType: () => ['image/jpeg'] }, result: { handleFileList: files => replies.push(Array.from(files)) } });
    permission.resolve(reason === 'denied' ? { authResults: [-1], dialogShownResults: [false] } : { authResults: [0] });
    await Promise.resolve(); await Promise.resolve();
    if (reason !== 'denied') {
      captureHeaderValid = reason !== 'badHeader'; fileSize = reason === 'empty' ? 0 : reason === 'oversize' ? 50 * 1024 * 1024 + 1 : 16;
      capture.resolve({ resultCode: reason === 'cancel' ? -1 : 0, resultUri: reason === 'wrongUri' ? 'file://foreign' : capture.profile.saveUri, mediaType: reason === 'wrongType' ? 'video' : 'photo' });
      await Promise.resolve(); await Promise.resolve();
    }
    assert.deepEqual(replies, [[]], `${reason} capture must not report success`);
    assert.equal(capturedEvents.some(event => event.type === 'permissionSettingsRequired'), reason === 'denied');
    fileSize = 16; captureHeaderValid = true; captured.onDestroy();
  }
  const captureCount = captureEvents.length;
  mediaView.controller.url = 'https://trusted.test.evil/';
  mediaView.selectFile({ fileSelector: { isCapture: () => true, getAcceptType: () => ['image/*'] }, result: { handleFileList: files => { captureCompletions++; assert.equal(files.length, 0); } } });
  assert.equal(captureEvents.length, captureCount, 'untrusted page cannot trigger capability feedback');
  mediaView.controller.url = 'https://trusted.test/page';
  permission = deferred(); let grants = 0; let denials = 0;
  mediaView.permissionRequest({ getAccessibleResource: () => ['video'], getOrigin: () => 'https://trusted.test', grant: () => grants++, deny: () => denials++ });
  mediaView.setProp('visible', false); permission.resolve({ authResults: [0] }); await Promise.resolve();
  assert.equal(grants, 0); assert.equal(denials, 1, 'hidden media prompt is settled exactly once');
  mediaView.setProp('visible', true); selectedFile = deferred(); const fileResponses = [];
  mediaView.selectFile({ fileSelector: { isCapture: () => false, getAcceptType: () => [], getMode: () => 0 }, result: { handleFileList: files => fileResponses.push(files) } });
  mediaView.onDestroy(); selectedFile.resolve(['file://picked/document']); await Promise.resolve();
  assert.equal(fileResponses.length, 1); assert.equal(fileResponses[0].length, 0, 'disposed picker cannot return a file');

  for (const revoke of ['navigate', 'request', 'hide']) {
    const pickerView = new GYWebView(); pickerView.setProp('request', encoded(mediaPolicy)); pickerView.onControllerAttached(); pickerView.onPageVisible('https://trusted.test/page');
    selectedFile = deferred(); const responses = [];
    pickerView.selectFile({ fileSelector: { isCapture: () => false, getAcceptType: () => [], getMode: () => 0 }, result: { handleFileList: files => responses.push(files) } });
    if (revoke === 'navigate') pickerView.onPageBegin('https://trusted.test/next');
    else if (revoke === 'request') pickerView.setProp('request', encoded(mediaPolicy));
    else pickerView.setProp('visible', false);
    const beforeLateOpens = fileOpens;
    selectedFile.resolve(['file://picked/document']); await Promise.resolve(); await Promise.resolve();
    assert.equal(fileOpens, beforeLateOpens, `${revoke} late picker cannot read URI`);
    assert.equal(responses.length, 1, `${revoke} completes FileSelectorResult once`);
    assert.equal(responses[0].length, 0, `${revoke} discards late file URI`); pickerView.onDestroy();
  }
  // 按当前主文档重新核验权限，不依赖 begin 回调是否已经到达。
  for (const reason of ['unsupported', 'foreignOrigin', 'originChanged', 'partialGrant', 'settingsDenied']) {
    const permissionView = new GYWebView(); const permissionEvents = [];
    permissionView.setProp('onEvent', event => permissionEvents.push(event));
    permissionView.setProp('request', encoded(mediaPolicy)); permissionView.onControllerAttached();
    permissionView.onPageVisible('https://trusted.test/page');
    permission = deferred(); let settledGrants = 0; let settledDenials = 0;
    permissionView.permissionRequest({
      getAccessibleResource: () => reason === 'unsupported' ? ['video', 'unknown'] : ['video', 'audio'],
      getOrigin: () => reason === 'foreignOrigin' ? 'https://foreign.test' : 'https://trusted.test',
      grant: () => settledGrants++, deny: () => settledDenials++,
    });
    if (reason === 'originChanged') permissionView.controller.url = 'https://foreign.test/page';
    permission.resolve(reason === 'partialGrant' ? { authResults: [0] } : reason === 'settingsDenied' ?
      { authResults: [-1, 0], dialogShownResults: [false, false] } : { authResults: [0, 0] });
    await Promise.resolve(); await Promise.resolve();
    assert.equal(settledGrants, 0, `${reason} never grants capture`);
    assert.equal(settledDenials, 1, `${reason} settles once`);
    const settingsEvents = permissionEvents.filter(event => event.type === 'permissionSettingsRequired');
    assert.equal(settingsEvents.length, reason === 'settingsDenied' ? 1 : 0);
    if (settingsEvents.length) assert.deepEqual(Array.from(settingsEvents[0].permissions), ['CAMERA']);
    permissionView.onDestroy(); assert.equal(settledDenials, 1, `${reason} is not settled again at destroy`);
  }
  // 选择器返回前 URL 已切到异源，尚未收到导航回调也不能暴露文件 URI。
  const changedPicker = new GYWebView(); const changedFiles = [];
  changedPicker.setProp('request', encoded(mediaPolicy)); changedPicker.onControllerAttached();
  changedPicker.onPageVisible('https://trusted.test/page'); selectedFile = deferred();
  changedPicker.selectFile({ fileSelector: { isCapture: () => false, getAcceptType: () => [], getMode: () => 0 },
    result: { handleFileList: files => changedFiles.push(Array.from(files)) } });
  changedPicker.controller.url = 'https://foreign.test/page';
  selectedFile.resolve(['file://picked/secret']); await Promise.resolve(); await Promise.resolve();
  assert.deepEqual(changedFiles, [[]]); changedPicker.onDestroy(); assert.equal(changedFiles.length, 1);

  const fullscreenView = new GYWebView(); fullscreenView.setProp('request', encoded(standard)); fullscreenView.onControllerAttached();
  const orientations = []; const layouts = [];
  windowObject.getLastWindow = async () => ({ getPreferredOrientation: () => 0, getWindowProperties: () => ({ isLayoutFullScreen: false }),
    setPreferredOrientation: async value => orientations.push(value), setWindowLayoutFullScreen: async value => layouts.push(value) });
  let exits = 0;
  fullscreenView.enterFullscreen({ exitFullScreen: () => exits++ }, 1600, 900);
  await fullscreenView.windowTask;
  let consumed;
  fullscreenView.call('goBack', null, result => consumed = result.result);
  await fullscreenView.windowTask;
  assert.equal(consumed, true); assert.equal(exits, 1); assert.deepEqual(orientations, [1, 0]); assert.deepEqual(layouts, [true, false]);
  fullscreenView.call('exitFullscreen', null, result => consumed = result.result); assert.equal(consumed, false);
  fullscreenView.onDestroy();

  // ArkWeb 无参退出事件：同 render 确认退出前拒绝新进入，Window 恢复不等待事件。
  const rapid = new GYWebView(); let firstRapidExit = 0; let rejectedRapidExit = 0; let currentRapidExit = 0;
  rapid.enterFullscreen({ exitFullScreen: () => firstRapidExit++ }, 1600, 900); await rapid.windowTask;
  rapid.exitFullscreen();
  rapid.enterFullscreen({ exitFullScreen: () => rejectedRapidExit++ }, 1600, 900);
  assert.equal(firstRapidExit, 1); assert.equal(rejectedRapidExit, 1); assert.equal(rapid.fullscreenHandler, null);
  await rapid.windowTask;
  assert.equal(layouts.at(-1), false, 'Window restores without waiting for native exit confirmation');
  assert.equal(rapid.nativeExitPending, true);
  rapid.nativeFullscreenExited();
  assert.equal(rapid.nativeExitPending, false, 'late A exit acknowledges the gate without touching a later handler');
  const currentRapidHandler = { exitFullScreen: () => currentRapidExit++ };
  rapid.enterFullscreen(currentRapidHandler, 1600, 900); await rapid.windowTask;
  assert.equal(rapid.fullscreenHandler, currentRapidHandler);
  rapid.nativeFullscreenExited(); await rapid.windowTask;
  assert.equal(rapid.fullscreenHandler, null); assert.equal(currentRapidExit, 0, 'native exit must not ask already-exited handler to exit again');
  rapid.onDestroy();

  const sourceChanged = new GYWebView(); sourceChanged.setProp('request', encoded(standard));
  const oldRender = sourceChanged.renderToken;
  sourceChanged.enterFullscreen({ exitFullScreen() {} }, 1600, 900); await sourceChanged.windowTask;
  sourceChanged.exitFullscreen(); sourceChanged.setProp('request', encoded(standard));
  assert.equal(sourceChanged.nativeExitPending, false, 'new Controller/render clears only old-render gate');
  const sourceHandler = { exitFullScreen() {} };
  sourceChanged.enterFullscreen(sourceHandler, 1600, 900); await sourceChanged.windowTask;
  sourceChanged.nativeFullscreenExited(oldRender);
  assert.equal(sourceChanged.fullscreenHandler, sourceHandler, 'old render exit cannot clear new source fullscreen');
  sourceChanged.nativeFullscreenExited(sourceChanged.renderToken); await sourceChanged.windowTask; sourceChanged.onDestroy();

  const synchronouslyExited = new GYWebView();
  synchronouslyExited.enterFullscreen({ exitFullScreen: () => synchronouslyExited.nativeFullscreenExited() }, 1600, 900);
  await synchronouslyExited.windowTask; synchronouslyExited.exitFullscreen(); await synchronouslyExited.windowTask;
  assert.equal(synchronouslyExited.nativeExitPending, false, 'gate is set before synchronously emitted native exit');
  synchronouslyExited.onDestroy();

  const destroyedActive = new GYWebView(); let destroyedHandlerExit = 0;
  destroyedActive.enterFullscreen({ exitFullScreen: () => destroyedHandlerExit++ }, 1600, 900); await destroyedActive.windowTask;
  destroyedActive.onDestroy(); await destroyedActive.windowTask;
  const afterDestroyWindowCalls = layouts.length + orientations.length;
  destroyedActive.nativeFullscreenExited();
  destroyedActive.enterFullscreen({ exitFullScreen: () => destroyedHandlerExit++ }, 1600, 900);
  assert.equal(destroyedHandlerExit, 2); assert.equal(layouts.length + orientations.length, afterDestroyWindowCalls);

  const componentSource = fs.readFileSync(path.join(root, 'WebViewComponent.ets'), 'utf8');
  assert.ok(componentSource.includes('nativeFullscreenExited(this.renderToken)'), 'ArkUI exit events carry render ownership into acknowledgment');

  const barCalls = []; let firstExit = 0; let secondExit = 0;
  const sharedFullscreenWindow = { getPreferredOrientation: () => 0,
    getWindowProperties: () => ({ isLayoutFullScreen: false, isFullScreen: false }),
    setPreferredOrientation: async value => orientations.push(value),
    setWindowSystemBarEnable: async value => barCalls.push(Array.from(value)),
    setWindowLayoutFullScreen: async value => layouts.push(value) };
  windowObject.getLastWindow = async () => sharedFullscreenWindow;
  class HostPolicyView extends GYWebView {
    fullscreenPolicy(width, height) {
      return { enterOrientation: width <= 0 || height <= 0 || width > height ? 1 : undefined,
        unspecifiedExitOrientation: 2, enterSystemBars: [],
        exitSystemBarsWhenFullscreen: [], exitSystemBarsWhenNotFullscreen: ['status', 'navigation'] };
    }
  }
  const firstFull = new HostPolicyView(); const secondFull = new HostPolicyView();
  firstFull.enterFullscreen({ exitFullScreen: () => firstExit++ }, 0, 0); await firstFull.windowTask;
  secondFull.enterFullscreen({ exitFullScreen: () => secondExit++ }, 0, 0); await secondFull.windowTask;
  assert.equal(firstExit, 1, 'new shared owner exits old ArkWeb handler');
  firstFull.onDestroy(); await firstFull.windowTask;
  assert.deepEqual(barCalls, [[], []], 'old destruction cannot restore new view');
  secondFull.exitFullscreen(); await secondFull.windowTask;
  assert.equal(secondExit, 1); assert.deepEqual(barCalls, [[], [], ['status', 'navigation']]);
  assert.equal(orientations.at(-1), 2, 'host UNSPECIFIED fallback is explicit policy');
  secondFull.onDestroy();

  const lateWindow = deferred(); let lateWindowCalls = 0;
  windowObject.getLastWindow = () => lateWindow.promise;
  const destroyedFull = new GYWebView();
  destroyedFull.enterFullscreen({ exitFullScreen() {} }, 1600, 900);
  await Promise.resolve(); destroyedFull.onDestroy();
  lateWindow.resolve({ getPreferredOrientation: () => 0, getWindowProperties: () => ({}),
    setPreferredOrientation: async () => lateWindowCalls++, setWindowLayoutFullScreen: async () => lateWindowCalls++ });
  await destroyedFull.windowTask;
  assert.equal(lateWindowCalls, 0, 'destroy before Window resolution never mutates window');

  // 执行真正的 document-start closure，拒绝来自 iframe 或页面伪造的消息端口。
  const bridgeSource = fs.readFileSync(path.join(root, 'GYWebView.ets'), 'utf8').match(/const BRIDGE_BOOTSTRAP: string = `([\s\S]*?)`;/)[1];
  const listeners = {}; const top = { addEventListener: (name, callback) => listeners[name] = callback }; top.top = top;
  vm.runInNewContext(bridgeSource, { window: top, console, TextEncoder });
  assert.equal(top.GYWebViewBridge.postMessage('early', '{}'), true);
  const sent = []; const transferred = { close() {}, postMessage: message => sent.push(message) };
  listeners.message({ isTrusted: true, source: {}, data: 'GYWebViewChannel', ports: [transferred] }); assert.equal(sent.length, 0);
  listeners.message({ isTrusted: false, source: null, data: 'GYWebViewChannel', ports: [transferred] }); assert.equal(sent.length, 0);
  listeners.message({ isTrusted: true, source: null, data: 'GYWebViewChannel', ports: [transferred] }); assert.equal(sent.length, 1);
  assert.equal(top.GYWebViewBridge.postMessage('ready', '{}'), true); assert.equal(sent.length, 2);
  assert.equal(top.GYWebViewBridge.postMessage('x', 'x'.repeat(65537)), false);
  assert.equal(top.GYWebViewBridge.postMessage('x', '字'.repeat(30000)), false, 'limit counts UTF-8 bytes');
  // 执行真实 bootstrap：保留可见首航队列，隐藏初始/撤销队列不能在恢复时 flush。
  for (const initiallyVisible of [true, false]) {
    const visibilityListeners = {}; const visibilityTop = { __GY_WEBVIEW_VISIBLE__: initiallyVisible,
      addEventListener: (name, callback) => visibilityListeners[name] = callback }; visibilityTop.top = visibilityTop;
    vm.runInNewContext(bridgeSource, { window: visibilityTop, TextEncoder });
    assert.equal(visibilityTop.GYWebViewBridge.postMessage('before-hide', '{}'), initiallyVisible);
    visibilityTop.__GY_WEBVIEW_VISIBLE__ = false; visibilityListeners.GYWebViewVisibility();
    assert.equal(visibilityTop.GYWebViewBridge.postMessage('hidden-init', '{}'), false);
    visibilityTop.__GY_WEBVIEW_VISIBLE__ = true; visibilityListeners.GYWebViewVisibility();
    const delivered = []; const resumedPort = { close() {}, postMessage: message => delivered.push(message) };
    visibilityListeners.message({ isTrusted: true, source: null, data: 'GYWebViewChannel', ports: [resumedPort] });
    assert.equal(delivered.length, 0, 'no pre-hide or hidden message is flushed after show');
    assert.equal(visibilityTop.GYWebViewBridge.postMessage('visible-new', '{}'), true); assert.equal(delivered.length, 1);
  }
  console.log(`PASS (${harArgument >= 0 ? 'actual HAR' : 'source'}): first-load retry URL/HTML, synchronous origin policy, controlled capture, once-only results, hidden initialization, Bridge and lifecycle`);
})().catch(error => { console.error(error); process.exitCode = 1; });
