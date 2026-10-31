import assert from 'node:assert/strict';
import http from 'node:http';
import { createRequire } from 'node:module';
import { after, before, test } from 'node:test';
import vm from 'node:vm';

const { createProxy, injectInto, INJECT } = createRequire(import.meta.url)('../console-proxy.js');
const SCRIPT = INJECT.replace(/^<script>/, '').replace(/<\/script>$/, '');

const COMPLETE_PAGE = `
  const pedals = { accelerator: false, brake: false };
  let pedalControl = { accelerator: false, brake: false };
  let lastFrame = 0;
  var ticks = 0;
  function physicsTick() { ticks++; }
  function refresh() {}
`;

// Runs a page script and then the injected script in one global scope, as a browser would.
function loadPage(pageSource) {
  const handlers = {};
  const appended = [];
  const intervals = [];
  const sandbox = {
    addEventListener: (name, handler) => { handlers[name] = handler; },
    requestAnimationFrame: () => {},
    setInterval: (fn, ms) => intervals.push({ fn, ms }),
    console: { error: () => {} },
    document: {
      createElement: () => ({ style: {} }),
      body: { appendChild: (node) => appended.push(node) },
    },
  };
  sandbox.window = sandbox;
  const context = vm.createContext(sandbox);
  vm.runInContext(pageSource, context);
  const before = sandbox.physicsTick;
  vm.runInContext(SCRIPT, context);
  handlers.load();
  return { sandbox, context, before, appended, intervals };
}

test('patches the page when all names exist, including lexical ones', () => {
  const page = loadPage(COMPLETE_PAGE);
  assert.deepEqual(page.appended, []);
  assert.notEqual(page.sandbox.physicsTick, page.before);
  assert.equal(page.intervals.length, 1);
  assert.equal(page.intervals[0].ms, 200);
});

test('patched tick runs the original only while a pedal is held', () => {
  const page = loadPage(COMPLETE_PAGE);
  page.sandbox.physicsTick(100);
  assert.equal(vm.runInContext('ticks', page.context), 0);
  assert.equal(vm.runInContext('lastFrame', page.context), 100);
  vm.runInContext('pedalControl.brake = true', page.context);
  page.sandbox.physicsTick(200);
  assert.equal(vm.runInContext('ticks', page.context), 1);
});

for (const [name, source] of [
  ['physicsTick', COMPLETE_PAGE.replace('function physicsTick() { ticks++; }', '')],
  ['pedals', COMPLETE_PAGE.replace('const pedals = { accelerator: false, brake: false };', '')],
  ['pedalControl', COMPLETE_PAGE.replace('let pedalControl = { accelerator: false, brake: false };', '')],
  ['refresh', COMPLETE_PAGE.replace('function refresh() {}', '')],
]) {
  test(`shows a banner and leaves the page alone without ${name}`, () => {
    assert.notEqual(source, COMPLETE_PAGE);
    const page = loadPage(source);
    assert.equal(page.appended.length, 1);
    assert.match(page.appended[0].textContent, new RegExp(`missing: ${name}\\.`));
    assert.equal(page.sandbox.physicsTick, page.before);
    assert.deepEqual(page.intervals, []);
  });
}

test('injects before </body>, and at the end when the tag is absent', () => {
  assert.equal(injectInto('<body>x</body></html>'), `<body>x${INJECT}</body></html>`);
  assert.equal(injectInto('<body>x'), `<body>x${INJECT}`);
});

const listen = (server) =>
  new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(server.address().port)));

let upstream;
let proxy;
let base;
const seen = {};

before(async () => {
  upstream = http.createServer((req, res) => {
    seen[req.url] = req.headers;
    if (req.url === '/') {
      res.writeHead(200, { 'content-type': 'text/html', 'content-length': 13 });
      res.end('<body></body>');
    } else if (req.url === '/vehicle/controls') {
      req.resume();
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(`{"method":"${req.method}"}`);
    } else {
      res.writeHead(404);
      res.end();
    }
  });
  const upstreamPort = await listen(upstream);
  proxy = createProxy({ host: '127.0.0.1', port: upstreamPort });
  base = `http://127.0.0.1:${await listen(proxy)}`;
});

after(() => {
  proxy.closeAllConnections();
  upstream.closeAllConnections();
  proxy.close();
  upstream.close();
});

test('the page comes through injected, with compression refused', async () => {
  const response = await fetch(`${base}/`, { headers: { 'accept-encoding': 'gzip' } });
  const body = await response.text();
  assert.ok(body.includes('console-proxy-banner'));
  assert.ok(body.endsWith('</body>'));
  assert.equal(seen['/']['accept-encoding'], undefined);
});

test('API calls pass through unchanged', async () => {
  const response = await fetch(`${base}/vehicle/controls`, { method: 'PUT', body: '{}' });
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { method: 'PUT' });
  assert.equal((await fetch(`${base}/missing`)).status, 404);
});

test('an unreachable console gives 502', async () => {
  const dead = createProxy({ host: '127.0.0.1', port: 1 });
  const port = await listen(dead);
  const response = await fetch(`http://127.0.0.1:${port}/`);
  assert.equal(response.status, 502);
  dead.closeAllConnections();
  dead.close();
});
