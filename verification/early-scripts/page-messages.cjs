const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(process.argv[2], 'utf8');
function document(address = 'http://legacy.test/captcha', subframe = false) {
  const messages = [];
  const window = {webkit: {messageHandlers: {ComposeWebViewPageMessage: {postMessage: value => messages.push(value)}}}};
  window.top = subframe ? {} : window;
  const context = vm.createContext({window, location: new URL(address), URL});
  vm.runInContext(source, context);
  return {window, messages, context};
}
const first = document();
const replies = [];
first.window.earlyChannel.onmessage = event => replies.push(event.data);
first.window.earlyChannel.postMessage('READY');
assert.equal(first.messages[0].token, 'document-A');
assert.equal(first.messages[0].channel, 'earlyChannel');
assert.equal(first.messages[0].data, 'READY');
first.window.earlyChannel.postMessage({invalid: true});
assert.equal(first.messages.length, 1);
assert.equal(first.window.__GY_WEBVIEW_PAGE_MESSAGE_REPLY__('document-A', 'earlyChannel', 'challenge'), true);
assert.deepEqual(replies, ['challenge']);
assert.equal(first.window.__GY_WEBVIEW_PAGE_MESSAGE_REPLY__('document-B', 'earlyChannel', 'old reply'), false);
assert.equal(first.window.__GY_WEBVIEW_PAGE_MESSAGE_REPLY__('document-A', 'other', 'old reply'), false);
const savedPost = first.window.earlyChannel.postMessage;
vm.runInContext(source.replaceAll('document-A', 'document-B'), first.context);
savedPost('old document queued');
assert.equal(first.messages.at(-1).token, 'document-A', 'saved function must keep original nonce');
first.window.earlyChannel.postMessage('new document');
assert.equal(first.messages.at(-1).token, 'document-B');
assert.equal(first.window.__GY_WEBVIEW_PAGE_MESSAGE_REPLY__('document-A', 'earlyChannel', 'old reply'), false);
for (const [address, frame] of [['http://legacy.test/other', false], ['https://legacy.test/captcha', false], ['http://evil.test/captcha', false], ['http://legacy.test/captcha', true]]) {
  assert.equal(document(address, frame).window.earlyChannel, undefined);
}
console.log('PASS: production page channel bootstrap, early string exchange, frame/exact URL, captured nonce and stale reply');

for (const declared of ['https://page.test', 'HTTPS://PAGE.TEST:443?q=a%20b#part', 'http://PAGE.TEST:80/initial.html?q=1#part']) {
  const normalizedSource = source.replace('"http://legacy.test/captcha"', JSON.stringify(declared));
  const current = new URL(declared);
  const page = document();
  delete page.window.earlyChannel;
  delete page.window.__GY_WEBVIEW_PAGE_MESSAGE_REPLY__;
  page.context.location = current;
  vm.runInContext(normalizedSource, page.context);
  assert.ok(page.window.earlyChannel, declared);
  const delivered = [];
  page.window.earlyChannel.onmessage = event => delivered.push(event.data);
  assert.equal(page.window.__GY_WEBVIEW_PAGE_MESSAGE_REPLY__('document-A', 'earlyChannel', 'normalized reply'), true);
  assert.deepEqual(delivered, ['normalized reply']);
  page.context.location = new URL(current.href + '#next');
  assert.equal(page.window.__GY_WEBVIEW_PAGE_MESSAGE_REPLY__('document-A', 'earlyChannel', 'different fragment'), false);
}
console.log('PASS: browser normalization retains bootstrap/reply and distinguishes fragment');
