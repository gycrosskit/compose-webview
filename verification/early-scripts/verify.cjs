const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(process.argv[2], 'utf8');
const pageMode = process.argv[3] === 'page';
const cases = pageMode ? [
  ['http://legacy.test/captcha', true], ['http://legacy.test.:80/other', true],
  ['http://legacy.test:8080/captcha', false], ['https://legacy.test/captcha', false],
  ['http://legacy.test.evil/captcha', false], ['http://evil.test/captcha', false, false, true],
  ['http://legacy.test/captcha', false, true],
] : [
  ['https://trusted.test/', true], ['https://sub.trusted.test:8443/path', true],
  ['https://sub.trusted.test:65535/path', true], ['https://trusted.test.:8443/path', true],
  ['http://sub.trusted.test:8443/path', false], ['https://trusted.test.evil:8443/path', false],
  ['https://evil.test:65535/', false], ['https://evil.test:8443/', false, false, true], ['https://sub.trusted.test:8443/path', false, true],
];
for (const [address, allowed, subframe, poison] of cases) {
  const page = {};
  page.top = subframe ? {} : page;
  const context = { window: page, location: new URL(address), document: { readyState: 'complete' } };
  if (poison) vm.runInNewContext("String.prototype.toLowerCase = function(){return 'trusted.test'}; String.prototype.replace = function(){return 'trusted.test'}; String.prototype.endsWith = function(){return true}", context);
  vm.runInNewContext(source, context);
  assert.equal(page.hits || 0, allowed ? 1 : 0, address + (subframe ? ' subframe' : ''));
  vm.runInNewContext(source, context);
  assert.equal(page.hits || 0, allowed ? 1 : 0, 'every document script runs at most once');
}
console.log('PASS: compiled production early-script wrapper, ' + (pageMode ? 'initial HTTP origin and default port' : 'HTTPS suffix arbitrary ports') + ', lookalike/scheme/subframe rejection');
