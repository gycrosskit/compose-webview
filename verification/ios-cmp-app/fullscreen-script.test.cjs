const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.resolve(__dirname, '../../src/iosMain/kotlin/io/github/gycrosskit/composewebview/IosWebViewScripts.kt'), 'utf8');
const script = source.match(/IOS_EXIT_FULLSCREEN_SCRIPT = """([\s\S]*?)"""/)[1];
const run = new (Object.getPrototypeOf(async function(){}).constructor)('document', 'window', script);
class Target extends EventTarget {
  constructor(props = {}) { super(); Object.assign(this, props); this.listeners = new Set(); }
  addEventListener(type, fn, capture) { this.listeners.add(type); super.addEventListener(type, fn, capture); }
  removeEventListener(type, fn, capture) { this.listeners.delete(type); super.removeEventListener(type, fn, capture); }
}
(async () => {
  const full = new Target({ fullscreenElement: {}, exitFullscreen: async function() { await Promise.resolve(); this.fullscreenElement = null; } });
  assert.equal(await run(full, {}), true); assert.equal(full.listeners.size, 0);
  const prefixed = new Target({ webkitFullscreenElement: {}, webkitExitFullscreen() { setTimeout(() => { this.webkitFullscreenElement = null; this.dispatchEvent(new Event('webkitfullscreenchange')); }, 30); } });
  assert.equal(await run(prefixed, {}), true, 'void prefixed DOM exit waits for genuine later state'); assert.equal(prefixed.listeners.size, 0);
  const denied = new Target({ fullscreenElement: {}, exitFullscreen: async () => { throw Error('denied'); } });
  assert.equal(await run(denied, {}), false); assert.equal(denied.listeners.size, 0);
  assert.equal(await run(new Target(), {}), false);
  const video = new Target({ webkitDisplayingFullscreen: true, webkitExitFullscreen() { setTimeout(() => { this.webkitDisplayingFullscreen = false; this.dispatchEvent(new Event('webkitendfullscreen')); }, 30); } });
  const doc = new Target(); assert.equal(await run(doc, { __GY_WEBVIEW_FULLSCREEN_VIDEO__: video }), true); assert.equal(doc.listeners.size + video.listeners.size, 0);
  const stubborn = new Target({ webkitDisplayingFullscreen: true, webkitExitFullscreen() {} });
  const timeoutDoc = new Target(); assert.equal(await run(timeoutDoc, { __GY_WEBVIEW_FULLSCREEN_VIDEO__: stubborn }), false); assert.equal(stubborn.listeners.size + timeoutDoc.listeners.size, 0);
  console.log('Production fullscreen source PASS: standard Promise, delayed prefixed DOM, delayed video, API rejection, absent fullscreen, bounded timeout and cleanup. DOM/video are boundary fixtures, not real fullscreen UI.');
})().catch(error => { console.error(error); process.exitCode = 1; });
