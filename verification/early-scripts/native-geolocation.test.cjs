const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const file = process.env.NATIVE_GEOLOCATION_SCRIPT_FILE;
const source = file ? fs.readFileSync(file, 'utf8') : fs.readFileSync(
  'webview-core/src/commonMain/kotlin/io/github/gycrosskit/composewebview/WebViewNativeGeolocation.kt', 'utf8'
).match(/val WEB_VIEW_NATIVE_GEOLOCATION_SCRIPT = """([\s\S]*?)"""\.trimIndent\(\)/)[1];

function page({readyState = 'loading', secure = true, locked = false, channel = true, subframe = false} = {}) {
  let now = 0, nextTimer = 0;
  const sent = [], timers = new Map(), listeners = {};
  const original = {getCurrentPosition() {}, watchPosition() {}, clearWatch() {}};
  const geo = Object.create(original);
  if (locked) Object.defineProperty(geo, 'watchPosition', {value: original.watchPosition, configurable: false});
  const navigator = {};
  Object.defineProperty(navigator, 'geolocation', {get: () => geo});
  const window = {isSecureContext: secure, addEventListener: (event, fn) => { listeners[event] = fn; }};
  window.top = subframe ? {} : window;
  if (channel) window.NativeGeolocation = {postMessage: value => sent.push(JSON.parse(value))};
  const context = {window, navigator, document: {readyState},
    setTimeout(fn, delay) { const id = ++nextTimer; timers.set(id, {fn, at: now + delay}); return id; },
    clearTimeout(id) { timers.delete(id); }};
  vm.runInNewContext(source, context);
  function tick(milliseconds = 0) {
    const end = now + milliseconds;
    let count = 0;
    while (true) {
      const due = [...timers].filter(([, value]) => value.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
      if (!due) break;
      assert.ok(++count < 100, 'timers must not spin');
      timers.delete(due[0]); now = due[1].at; due[1].fn();
    }
    now = end;
  }
  return {geo, original, window, sent, tick, listeners,
    reply(value) { window.NativeGeolocation.onmessage({data: JSON.stringify(value)}); }};
}
function position(id, timestamp = 123456789) {
  return {id, position: {timestamp, coords: {latitude: 31, longitude: 121, accuracy: 3500,
    altitude: null, altitudeAccuracy: null, heading: null, speed: null}}};
}

test('forwards options and preserves approximate accuracy and cached timestamp', () => {
  const p = page(), results = [];
  assert.equal(p.geo.getCurrentPosition(value => results.push(value), assert.fail,
    {enableHighAccuracy: true, maximumAge: 0, timeout: 12000}), undefined);
  assert.deepEqual(p.sent[0], {id: '1', action: 'get', options: {enableHighAccuracy: true, maximumAge: 0, timeout: 12000}});
  p.reply(position('1')); assert.equal(results.length, 0); p.tick();
  assert.equal(results[0].coords.accuracy, 3500); assert.equal(results[0].timestamp, 123456789);
  p.reply(position('1')); p.tick(); assert.equal(results.length, 1);
});
test('normalizes defaults, clamps unsigned options and rejects invalid callbacks', () => {
  const p = page();
  p.geo.getCurrentPosition(() => {});
  assert.deepEqual(p.sent[0].options, {enableHighAccuracy: false, maximumAge: 0, timeout: null});
  p.geo.getCurrentPosition(() => {}, null, {maximumAge: Infinity, timeout: -1});
  assert.deepEqual(p.sent[1].options, {enableHighAccuracy: false, maximumAge: 4294967295, timeout: 0});
  assert.throws(() => p.geo.getCurrentPosition(null), /callbacks/);
  assert.throws(() => p.geo.watchPosition(() => {}, true), /callbacks/);
});
test('maps all error codes and invalid positions without leaking late replies', () => {
  const p = page(), errors = [];
  for (const code of [1, 2, 3]) {
    p.geo.getCurrentPosition(assert.fail, value => errors.push(value));
    p.reply({id: String(code), error: {code, message: 'native failure'}});
  }
  p.geo.getCurrentPosition(assert.fail, value => errors.push(value));
  const invalid = position('4'); invalid.position.coords.latitude = 91; p.reply(invalid);
  p.tick(); assert.deepEqual(errors.map(value => value.code), [1, 2, 3, 2]);
  assert.equal(errors[0].PERMISSION_DENIED, 1);
});
test('watch uses serial requests, suppresses unchanged snapshots and cancels in-flight work', () => {
  const p = page(), results = [];
  const watch = p.geo.watchPosition(value => results.push(value), assert.fail);
  p.reply(position('1')); p.tick(); assert.equal(results.length, 1);
  p.tick(1000); assert.equal(p.sent[1].id, '2');
  p.reply(position('2')); p.tick(); assert.equal(results.length, 1);
  p.tick(1000); assert.equal(p.sent[2].id, '3');
  p.geo.clearWatch(watch); assert.deepEqual(p.sent[3], {id: '3', action: 'cancel'});
  p.reply(position('3', 123456790)); p.tick(5000); assert.equal(results.length, 1); assert.equal(p.sent.length, 4);
});
test('clearWatch suppresses a queued reply and denial stops further sampling', () => {
  const p = page(), results = [];
  const id = p.geo.watchPosition(value => results.push(value), value => results.push(value));
  p.reply(position('1')); p.geo.clearWatch(id); p.tick(); assert.equal(results.length, 0);
  p.geo.watchPosition(assert.fail, value => results.push(value));
  p.reply({id: '2', error: {code: 1, message: 'denied'}}); p.tick(5000);
  assert.equal(results.length, 1); assert.equal(p.sent.filter(value => value.action === 'get').length, 2);
});
test('navigation cancels owned requests and suppresses already queued callbacks', () => {
  const p = page(), results = [];
  p.geo.getCurrentPosition(value => results.push(value)); p.reply(position('1'));
  p.geo.watchPosition(value => results.push(value));
  p.listeners.pagehide(); p.reply(position('2')); p.tick(5000);
  assert.equal(results.length, 0); assert.ok(p.sent.some(value => value.action === 'cancel' && value.id === '2'));
});
test('late injection and insecure context preserve browser methods and report unsupported', () => {
  for (const [configuration, reason] of [[{readyState: 'complete'}, 'DOCUMENT_START_UNAVAILABLE'],
    [{secure: false}, 'INSECURE_CONTEXT'], [{locked: true}, 'METHODS_NOT_REPLACEABLE']]) {
    const p = page(configuration);
    assert.equal(p.geo.getCurrentPosition, p.original.getCurrentPosition);
    assert.equal(p.geo.watchPosition, p.original.watchPosition);
    assert.deepEqual(p.sent, [{id: '0', action: 'unsupported', reason}]);
  }
});
test('missing transport and subframes never replace browser methods', () => {
  for (const configuration of [{channel: false}, {subframe: true}]) {
    const p = page(configuration);
    assert.equal(p.geo.getCurrentPosition, p.original.getCurrentPosition); assert.equal(p.sent.length, 0);
  }
});
