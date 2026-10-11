'use strict';
const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { SyncStore } = require('../lib/sync-store');
const { createApp, MAX_BODY } = require('../lib/app');

const token = 'test-only-token-at-least-24-characters';
const entry = path.join(__dirname, '..', 'index.js');
const todo = (n, extra = {}) => ({ id: 'todo-abcdefgh' + n, kind: 'todo', baseRev: 0, localVersion: n, body: { id: 'todo-abcdefgh' + n, title: '测试事项', done: false, ...extra } });

let dir, store, server, base;
before(async () => {
  dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rh-app-'));
  store = new SyncStore(dir);
  server = createApp({ token, store, log: null });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  base = `http://127.0.0.1:${server.address().port}`;
});
after(async () => {
  await new Promise(resolve => server.close(resolve));
  store.close();
  fs.rmSync(dir, { recursive: true, force: true });
});

const auth = { 'X-Auth-Token': token, 'Content-Type': 'application/json' };
const post = (body, headers = auth) => fetch(base + '/api/v2/sync?cursor=0', { method: 'POST', headers, body: typeof body === 'string' ? body : JSON.stringify(body) });
function raw(pathname) {
  return new Promise((resolve, reject) => {
    const req = http.request(base + '/', { path: pathname }, res => {
      res.resume();
      res.on('end', () => resolve(res.statusCode));
    });
    req.on('error', reject);
    req.end();
  });
}

function startHub(t, env) {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'rh-http-'));
  const p = spawn(process.execPath, [entry], { env: { ...process.env, RH_DATA_DIR: dataDir, PORT: '0', HOST: '127.0.0.1', ...env }, stdio: ['ignore', 'pipe', 'pipe'] });
  t.after(async () => {
    if (p.exitCode === null) { p.kill(); await new Promise(r => p.once('exit', r)); }
    fs.rmSync(dataDir, { recursive: true, force: true });
  });
  return p;
}

test('hub process enforces auth, serves the board and accepts text-only sync', async t => {
  const p = startHub(t, { RH_TOKEN: token });
  const url = await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(Error('hub start timeout')), 10000);
    p.stdout.once('data', d => { clearTimeout(timeout); resolve(/http:\/\/[^\s]+/.exec(String(d))[0]); });
    p.once('error', reject);
  });
  assert.equal((await fetch(url + '/api/v2/sync')).status, 401);
  assert.equal((await fetch(url + '/api/v2/sync?token=' + token)).status, 401);
  assert.equal((await fetch(url + '/')).status, 200);
  const headers = { 'X-Auth-Token': token, 'Content-Type': 'application/json' };
  const bad = await fetch(url + '/api/v2/sync', { method: 'POST', headers, body: 'not-json' }); assert.equal(bad.status, 400);
  const r = await fetch(url + '/api/v2/sync', { headers }); assert.deepEqual(await r.json(), { items: [], cursor: 0, more: false });
});

test('hub refuses to start without a strong RH_TOKEN', async t => {
  for (const env of [{ RH_TOKEN: '' }, { RH_TOKEN: 'too-short' }]) {
    const p = startHub(t, env);
    let stderr = '';
    p.stderr.on('data', d => { stderr += d; });
    const code = await new Promise(r => p.once('exit', r));
    assert.equal(code, 1);
    assert.match(stderr, /RH_TOKEN/);
  }
});

test('health is public and reports the version', async () => {
  const res = await fetch(base + '/api/v2/health');
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { ok: true, version: '2.0.0' });
});

test('auth: missing, wrong and query-string tokens are rejected', async () => {
  for (const p of ['/api/v2/sync', '/api/v2/stats', '/api/v2/items/todo-abcdefgh1']) {
    assert.equal((await fetch(base + p)).status, 401, p);
    assert.equal((await fetch(base + p + '?token=' + token)).status, 401, p + ' query token');
    assert.equal((await fetch(base + p, { headers: { 'X-Auth-Token': token.replace(/.$/, 'x') } })).status, 401, p + ' wrong token');
    assert.equal((await fetch(base + p, { headers: { 'X-Auth-Token': 'short' } })).status, 401, p + ' short token');
  }
  assert.equal((await fetch(base + '/api/v2/unknown', { headers: auth })).status, 404);
});

test('push and pull keep the wire format', async () => {
  const res = await post({ device: 'phoneA', changes: [todo(1)] });
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.deepEqual(body, { results: [{ item: { id: 'todo-abcdefgh1', kind: 'todo', body: todo(1).body, rev: 1 }, localVersion: 1, conflict: false }] });
  const again = await (await post({ device: 'phoneA', changes: [todo(1)] })).json();
  assert.deepEqual(again, body, 'retry is idempotent');
  const conflict = await (await post({ device: 'phoneB', changes: [todo(1, { done: true })] })).json();
  assert.equal(conflict.results[0].conflict, true);
  assert.equal(conflict.results[0].item.body.done, false);
  const page = await (await fetch(base + '/api/v2/sync?cursor=0', { headers: auth })).json();
  assert.deepEqual(page, { items: [body.results[0].item], cursor: 1, more: false });
  const doubled = await fetch(base + '//api/v2/sync?cursor=0', { headers: auth });
  assert.equal(doubled.status, 200, 'base URL saved with a trailing slash');
  assert.equal((await fetch(base + '/api/v2/sync?cursor=-1', { headers: auth })).status, 400);
  assert.equal((await fetch(base + '/api/v2/sync', { method: 'PUT', headers: auth })).status, 405);
});

test('invalid batches are rejected as a whole', async () => {
  const tooMany = await post({ device: 'phoneA', changes: Array.from({ length: 51 }, (_, i) => todo(100 + i)) });
  assert.equal(tooMany.status, 400);
  assert.deepEqual(await tooMany.json(), { error: 'max 50 changes' });
  const mixed = await post({ device: 'phoneA', changes: [todo(200), todo(201, { audio: 'x' })] });
  assert.equal(mixed.status, 400);
  assert.equal((await fetch(base + '/api/v2/items/todo-abcdefgh200', { headers: auth })).status, 404);
  const unknownBase = await post({ device: 'phoneA', changes: [{ ...todo(202), baseRev: 7 }] });
  assert.equal(unknownBase.status, 400);
  assert.equal((await post({ device: 'x', changes: [] })).status, 400);
});

test('bodies over 10 MB get 413, declared or streamed', async () => {
  const declared = await post('x'.repeat(MAX_BODY + 1));
  assert.equal(declared.status, 413);
  const chunk = new Uint8Array(1024 * 1024).fill(32);
  let sent = 0;
  const stream = new ReadableStream({
    pull(controller) {
      if (sent > MAX_BODY + chunk.length) return controller.close();
      sent += chunk.length;
      controller.enqueue(chunk);
    },
  });
  const streamed = await fetch(base + '/api/v2/sync', { method: 'POST', headers: auth, body: stream, duplex: 'half' });
  assert.equal(streamed.status, 413);
  assert.equal((await fetch(base + '/api/v2/health')).status, 200, 'server still healthy');
});

test('stats report counts and devices', async () => {
  await post({ device: 'statsPhone', changes: [todo(300, { reviewed: false }), todo(301, { done: true })] });
  await post({ device: 'statsPhone', changes: [] });
  const res = await fetch(base + '/api/v2/stats', { headers: auth });
  assert.equal(res.status, 200);
  const stats = await res.json();
  assert.deepEqual(Object.keys(stats), ['recordings', 'todos', 'openTodos', 'reviewedPending', 'devices', 'lastRev']);
  assert.equal(stats.recordings, 0);
  assert.equal(stats.todos, 3);
  assert.equal(stats.openTodos, 2);
  assert.equal(stats.reviewedPending, 1);
  assert.equal(stats.lastRev, 3);
  const phone = stats.devices.find(d => d.device === 'statsPhone');
  assert.equal(phone.pushes, 2);
  assert.ok(Math.abs(phone.lastSeen - Date.now()) < 60_000);
  assert.ok(stats.devices.some(d => d.device === 'phoneA'));
});

test('single item endpoint returns 200 or 404', async () => {
  const ok = await fetch(base + '/api/v2/items/todo-abcdefgh1', { headers: auth });
  assert.equal(ok.status, 200);
  assert.deepEqual(Object.keys(await ok.json()), ['id', 'kind', 'body', 'rev']);
  assert.equal((await fetch(base + '/api/v2/items/todo-abcdefgh999', { headers: auth })).status, 404);
  assert.equal((await fetch(base + '/api/v2/items/not-an-id', { headers: auth })).status, 404);
});

test('static board: whitelisted files, types, caching and security headers', async () => {
  const index = await fetch(base + '/');
  assert.equal(index.status, 200);
  assert.match(index.headers.get('content-type'), /^text\/html/);
  assert.equal(index.headers.get('cache-control'), 'no-cache');
  assert.equal(index.headers.get('x-content-type-options'), 'nosniff');
  assert.equal(index.headers.get('referrer-policy'), 'no-referrer');
  const csp = index.headers.get('content-security-policy');
  assert.match(csp, /default-src 'none'/);
  assert.match(csp, /script-src 'self'(;|$)/);
  assert.match(csp, /connect-src 'self'/);
  assert.doesNotMatch(csp, /unsafe-inline|unsafe-eval|https?:/);
  const html = await index.text();
  assert.match(html, /录音助手 · 工作空间/);
  assert.doesNotMatch(html, /<script>|<style|\son\w+=|https?:\/\//, 'no inline script/style, handlers or external URLs');

  const css = await fetch(base + '/app.css');
  assert.equal(css.status, 200);
  assert.match(css.headers.get('content-type'), /^text\/css/);
  const js = await fetch(base + '/app.js');
  assert.equal(js.status, 200);
  assert.match(js.headers.get('content-type'), /^text\/javascript/);
  assert.match(await js.text(), /'api\/v2\/sync/, 'board uses relative API URLs');
  assert.equal((await fetch(base + '/dates.js')).status, 200);
  assert.equal((await fetch(base + '/index.html')).status, 200);
  const etag = js.headers.get('etag');
  assert.equal((await fetch(base + '/app.js', { headers: { 'If-None-Match': etag } })).status, 304);
  const head = await fetch(base + '/app.css', { method: 'HEAD' });
  assert.equal(head.status, 200);
  assert.equal((await fetch(base + '/', { method: 'POST' })).status, 405);

  assert.equal((await fetch(base + '/nope.js')).status, 404);
  assert.equal((await fetch(base + '/package.json')).status, 404);
  for (const p of ['/../package.json', '/%2e%2e/package.json', '/..%2fpackage.json', '/web/../index.js', '/lib/app.js', '//etc/passwd', '/..\\package.json']) {
    assert.equal(await raw(p), 404, p);
  }
});
