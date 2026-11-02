const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../webview-core/src/iosMain/kotlin/io/github/gycrosskit/composewebview/IosWebViewScripts.kt'), 'utf8');
const extract = name => source.match(new RegExp(name + ' = """([\\s\\S]*?)"""'))[1];
const flag = '__COMPOSE_WEBVIEW_FILE_CHOOSER_ALLOWED__';

function fixture(showPicker = true) {
  const listeners = {}, windowListeners = {}, window = {};
  let now = 1000, opens = 0;
  const document = { activeElement: { tagName: 'BODY' }, addEventListener: (name, action) => { listeners[name] = action; } };
  window.addEventListener = (name, action) => { windowListeners[name] = action; };
  class Input {
    constructor(accept = '', capture = false) { Object.assign(this, { tagName: 'INPUT', type: 'file', accept, capture, disabled: false }); }
    hasAttribute(name) { return name === 'capture' && this.capture; }
    click() {
      if (this.fail) throw this.fail;
      if (this.disabled) return;
      if (this.type === 'file') listeners.click({ target: this, preventDefault() {}, stopImmediatePropagation() {} });
      opens++;
    }
    showPicker() { if (this.fail) throw this.fail; opens++; }
  }
  if (!showPicker) delete Input.prototype.showPicker;
  const context = vm.createContext({ window, document, HTMLInputElement: Input, Date: { now: () => now } });
  vm.runInContext(extract('IOS_FILE_CHOOSER_GATE_SCRIPT').replaceAll('$FILE_CHOOSER_ALLOWED_FLAG', flag), context);
  return { Input, document, window, listeners, windowListeners, opens: () => opens,
    allow: () => { window[flag] = true; }, advance: value => { now += value; },
    consume: () => JSON.parse(vm.runInContext(extract('IOS_FILE_INPUT_SCRIPT'), context)) };
}

test('hidden programmatic file click reports the triggering input without focus and consumes it once', () => {
  const f = fixture(); f.allow();
  new f.Input('.png,image/png', true).click();
  assert.equal(f.document.activeElement.tagName, 'BODY');
  assert.equal(f.opens(), 1);
  assert.deepEqual(f.consume(), { accept: '.png,image/png', capture: true });
  assert.deepEqual(f.consume(), {});
});

test('direct clicks and showPicker preserve photo/video hints rather than an older focused input', () => {
  const f = fixture(); f.allow();
  f.document.activeElement = new f.Input('image/jpeg', true);
  const video = new f.Input('.mp4,video/mp4', true);
  video.showPicker();
  assert.deepEqual(f.consume(), { accept: '.mp4,video/mp4', capture: true });
  const mixed = new f.Input('image/*,video/*');
  f.listeners.click({ target: mixed });
  assert.deepEqual(f.consume(), { accept: 'image/*,video/*', capture: false });
  assert.deepEqual(f.consume(), {});
  f.listeners.click({ target: f.document.activeElement, detail: 0 });
  assert.deepEqual(f.consume(), { accept: 'image/jpeg', capture: true });
  assert.deepEqual(f.consume(), {});
});

test('disabled chooser still blocks all activation paths and preserves non-file controls', () => {
  const f = fixture();
  const input = new f.Input('video/*', true);
  input.click(); input.showPicker();
  let prevented = 0;
  f.listeners.click({ target: input, preventDefault() { prevented++; }, stopImmediatePropagation() { prevented++; } });
  assert.equal(prevented, 2);
  assert.equal(f.opens(), 0);
  assert.deepEqual(f.consume(), {});
  input.type = 'button'; input.click();
  assert.equal(f.opens(), 1);
});

test('old, failed and departed activations cannot replay capture metadata', () => {
  const f = fixture(); f.allow();
  const input = new f.Input('image/*', true);
  input.click(); f.advance(1500);
  assert.deepEqual(f.consume(), {});
  input.click(); f.windowListeners.pagehide();
  assert.deepEqual(f.consume(), {});
  const failure = Error('NotAllowedError'); input.fail = failure;
  assert.throws(() => input.showPicker(), error => error === failure);
  assert.deepEqual(f.consume(), {});
});

test('older WebKit without showPicker retains click support and skips disabled inputs', () => {
  const f = fixture(false); f.allow();
  const input = new f.Input('image/jpeg', true); input.disabled = true; input.click();
  assert.deepEqual(f.consume(), {});
  input.disabled = false; input.click();
  assert.deepEqual(f.consume(), { accept: 'image/jpeg', capture: true });
});
