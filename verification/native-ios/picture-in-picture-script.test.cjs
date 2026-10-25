const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.resolve(__dirname, '../../webview-core/src/iosMain/kotlin/io/github/gycrosskit/composewebview/IosWebViewScripts.kt'), 'utf8');
const script = source.match(/IOS_PICTURE_IN_PICTURE_SCRIPT = """([\s\S]*?)"""/)[1];
const run = new Function('document', 'window', 'crypto', 'HTMLVideoElement', script.replace('__GY_MEDIA_DOCUMENT_TOKEN__', '"document-token"'));
class Video { constructor(mode = 'inline') { this.webkitPresentationMode = mode; } }
function frame(id) {
  const listeners = new Map(), messages = [];
  const document = { addEventListener(name, listener) { listeners.set(name, listener); } };
  const window = { webkit: { messageHandlers: { ComposeWebViewMedia: { postMessage(value) { messages.push(value); } } } },
    addEventListener(name, listener) { listeners.set(name, listener); } };
  run(document, window, { getRandomValues(array) { array.fill(id); return array; } }, Video);
  return { document, messages, event(name, target) { listeners.get(name)({ target }); } };
}
const main = frame(1), child = frame(2), video = new Video(), other = new Video();
main.event('webkitpresentationmodechanged', video);
assert.equal(main.messages.length, 0, 'inline/fullscreen is not PiP');
video.webkitPresentationMode = 'picture-in-picture';
main.event('webkitpresentationmodechanged', video);
assert.equal(main.messages.at(-1).active, true);
assert.equal(main.messages.at(-1).token, 'document-token');
main.event('webkitpresentationmodechanged', other);
assert.equal(main.messages.length, 1, 'another inline video cannot clear the PiP owner');
video.webkitPresentationMode = 'inline';
main.event('webkitpresentationmodechanged', video);
assert.equal(main.messages.at(-1).active, false);
child.document.pictureInPictureElement = other;
child.event('enterpictureinpicture', other);
assert.equal(child.messages.at(-1).active, true, 'standard PiP event in a child frame');
assert.notEqual(child.messages.at(-1).frameId, main.messages.at(-1).frameId);
child.document.pictureInPictureElement = null;
child.event('leavepictureinpicture', other);
assert.equal(child.messages.at(-1).active, false);
video.webkitPresentationMode = 'picture-in-picture';
main.event('webkitpresentationmodechanged', video);
main.event('pagehide');
assert.equal(main.messages.at(-1).active, false, 'unloading the document revokes its PiP owner');
console.log('PASS production PiP script: WebKit/standard events, frame ownership, unrelated video, exit and pagehide. DOM fixtures only.');
