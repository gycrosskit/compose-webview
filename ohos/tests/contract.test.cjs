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
const deferred = () => { let resolve; const promise = new Promise(r => resolve = r); return { promise, resolve }; };
let nextJavascript = null;
let permission = null;
let selectedFile = null;
let failNextLoad = false;
class Port {
  closed = false;
  close() { this.closed = true; }
  onMessageEvent(callback) { this.callback = callback; }
  message(message) { this.callback?.(message); }
}
class Controller {
  loads = []; data = []; refreshes = 0; url = 'https://trusted.test/page'; ports = []; deliveries = [];
  loadUrl(url, headers) { if (failNextLoad) { failNextLoad = false; throw new Error('first load rejected'); } this.loads.push({ url, headers }); this.url = url; }
  loadData(...args) { if (failNextLoad) { failNextLoad = false; throw new Error('first load rejected'); } this.data.push(args); }
  getUrl() { return this.url; }
  getUserAgent() { return 'ArkWeb'; }
  setCustomUserAgent(value) { this.userAgent = value; }
  runJavaScript(source) { this.lastScript = source; if (nextJavascript) { const task = nextJavascript; nextJavascript = null; return task.promise; } return Promise.resolve('null'); }
  createWebMessagePorts() { const ports = [new Port(), new Port()]; this.ports.push(ports); return ports; }
  postMessage(name, ports, origin) { this.deliveries.push({ name, ports, origin }); }
  accessBackward() { return true; } accessForward() { return false; }
  stop() {} stopAllMedia() {} closeAllMediaPresentations() {} onInactive() {} onActive() {} refresh() { this.refreshes++; } backward() {} forward() {}
}
const contexts = [];
class BaseView {
  getUIContext() { return { getHostContext: () => ({}), postFrameCallback: callback => contexts.push(callback) }; }
  setProp() { return false; } onDestroy() {} call() {}
}
const windowObject = { Orientation: { UNSPECIFIED: 0, AUTO_ROTATION_LANDSCAPE: 1 }, getLastWindow: async () => ({}) };
const mocks = {
  '@kit.ArkTS': { url: { URL }, util: { TextEncoder: class { encodeInto(value) { return new TextEncoder().encode(value); } } } },
  '@kuikly-open/render': { KuiklyRenderBaseView: BaseView },
  '@ohos.arkui.node': { ComponentContent: class {} },
  '@kit.ArkWeb': { webview: { WebviewController: Controller } },
  '@kit.ArkUI': { window: windowObject, FrameCallback: class {} },
  '@kit.AbilityKit': { abilityAccessCtrl: { createAtManager: () => ({ requestPermissionsFromUser: () => permission.promise }) } },
  '@kit.CoreFileKit': { picker: { DocumentSelectOptions: class {}, DocumentViewPicker: class { select() { return selectedFile.promise; } } } },
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
  assert.equal(wire.navigationAllowed('https://trusted.test/next', true, standard), true);
  for (const value of ['javascript:alert(1)', 'file:///etc/passwd', 'data:text/html,evil', 'https://user@trusted.test/', 'http://trusted.test/']) {
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
  const { GYWebView } = load('GYWebView.ets');
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
  view.setProp('visible', true); await Promise.resolve(); await Promise.resolve();
  assert.equal(view.controller.deliveries.length, 2, 'visible resumes same-document handshake');
  oldPorts[0].message(encoded({ handlerName: 'still-stale', data: '{}' }));
  assert.equal(events.filter(event => event.type === 'bridgeMessage').length, beforeHide);
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
  mediaView.selectFile({ fileSelector: { isCapture: () => true }, result: { handleFileList: files => { captureCompletions++; assert.equal(files.length, 0); throw new Error('system callback failed'); } } });
  assert.equal(captureCompletions, 1, 'unsupported capture settles once even if system result throws');
  assert.equal(captureEvents.at(-1).type, 'capabilityUnsupported');
  assert.equal(captureEvents.at(-1).capability, 'FILE_CAPTURE');
  const captureCount = captureEvents.length;
  mediaView.controller.url = 'https://trusted.test.evil/';
  mediaView.selectFile({ fileSelector: { isCapture: () => true }, result: { handleFileList: files => { captureCompletions++; assert.equal(files.length, 0); } } });
  assert.equal(captureEvents.length, captureCount, 'untrusted page cannot trigger capability feedback');
  mediaView.controller.url = 'https://trusted.test/page';
  permission = deferred(); let grants = 0; let denials = 0;
  mediaView.permissionRequest({ getAccessibleResource: () => ['video'], getOrigin: () => 'https://trusted.test', grant: () => grants++, deny: () => denials++ });
  mediaView.setProp('visible', false); permission.resolve({ authResults: [0] }); await Promise.resolve();
  assert.equal(grants, 0); assert.equal(denials, 1, 'hidden media prompt is settled exactly once');
  mediaView.setProp('visible', true); selectedFile = deferred(); const fileResponses = [];
  mediaView.selectFile({ fileSelector: { isCapture: () => false, getMode: () => 0 }, result: { handleFileList: files => fileResponses.push(files) } });
  mediaView.onDestroy(); selectedFile.resolve(['file://picked/document']); await Promise.resolve();
  assert.equal(fileResponses.length, 1); assert.equal(fileResponses[0].length, 0, 'disposed picker cannot return a file');

  for (const revoke of ['navigate', 'request', 'hide']) {
    const pickerView = new GYWebView(); pickerView.setProp('request', encoded(mediaPolicy)); pickerView.onControllerAttached(); pickerView.onPageVisible('https://trusted.test/page');
    selectedFile = deferred(); const responses = [];
    pickerView.selectFile({ fileSelector: { isCapture: () => false, getMode: () => 0 }, result: { handleFileList: files => responses.push(files) } });
    if (revoke === 'navigate') pickerView.onPageBegin('https://trusted.test/next');
    else if (revoke === 'request') pickerView.setProp('request', encoded(mediaPolicy));
    else pickerView.setProp('visible', false);
    selectedFile.resolve(['file://picked/document']); await Promise.resolve(); await Promise.resolve();
    assert.equal(responses.length, 1, `${revoke} completes FileSelectorResult once`);
    assert.equal(responses[0].length, 0, `${revoke} discards late file URI`); pickerView.onDestroy();
  }
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
  console.log(`PASS (${harArgument >= 0 ? 'actual HAR' : 'source'}): first-load retry URL/HTML, synchronous origin policy, typed unsupported capture, once-only results, Bridge and lifecycle`);
})().catch(error => { console.error(error); process.exitCode = 1; });
