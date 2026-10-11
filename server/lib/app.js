'use strict';
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { ID_RE } = require('./sync-store');
const { version } = require('../package.json');

const MAX_BODY = 10 * 1024 * 1024;
const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.webmanifest': 'application/manifest+json',
};
const SECURITY_HEADERS = {
  'X-Content-Type-Options': 'nosniff',
  'Referrer-Policy': 'no-referrer',
  'X-Frame-Options': 'DENY',
  'Cross-Origin-Opener-Policy': 'same-origin',
  'Cross-Origin-Resource-Policy': 'same-origin',
  'Permissions-Policy': 'camera=(), microphone=(), geolocation=()',
  'Content-Security-Policy': [
    "default-src 'none'", "script-src 'self'", "style-src 'self'", "img-src 'self' data:",
    "connect-src 'self'", "manifest-src 'self'", "base-uri 'none'", "form-action 'self'", "frame-ancestors 'none'",
  ].join('; '),
};

// Whitelist of static files, loaded once: only plain names directly inside webDir.
function loadStatic(webDir) {
  const files = new Map();
  for (const name of fs.readdirSync(webDir)) {
    const ext = path.extname(name);
    if (!/^[a-z0-9][a-z0-9.-]*$/.test(name) || !TYPES[ext]) continue;
    const full = path.join(webDir, name);
    if (!fs.statSync(full).isFile()) continue;
    const body = fs.readFileSync(full);
    const etag = '"' + crypto.createHash('sha256').update(body).digest('base64url').slice(0, 27) + '"';
    files.set('/' + name, { body, etag, type: TYPES[ext] });
  }
  if (files.has('/index.html')) files.set('/', files.get('/index.html'));
  return files;
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const tooLarge = Object.assign(new Error('body too large'), { status: 413 });
    if (Number(req.headers['content-length']) > MAX_BODY) return reject(tooLarge);
    const chunks = [];
    let size = 0, overflow = false;
    req.on('data', chunk => {
      if (overflow) return;
      size += chunk.length;
      if (size <= MAX_BODY) return void chunks.push(chunk);
      // Keep draining so the client can read the 413; drop the connection if it never ends.
      overflow = true;
      chunks.length = 0;
      setTimeout(() => { if (!req.complete) req.destroy(); }, 10_000).unref();
      reject(tooLarge);
    });
    req.on('end', () => { if (!overflow) resolve(Buffer.concat(chunks)); });
    req.on('error', reject);
  });
}

function createApp({ token, store, webDir = path.join(__dirname, '..', 'web'), log = console.log }) {
  const files = loadStatic(webDir);
  const digest = s => crypto.createHash('sha256').update(s, 'utf8').digest();
  const expected = digest(token);
  const authorized = req => {
    const given = req.headers['x-auth-token'];
    return typeof given === 'string' && crypto.timingSafeEqual(digest(given), expected);
  };
  const json = (res, code, body) => {
    res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify(body));
  };
  const fail = (res, e) => {
    if (e.status) return json(res, e.status, { error: e.message });
    if (e instanceof SyntaxError) return json(res, 400, { error: e.message });
    console.error(e);
    return json(res, 500, { error: 'internal error' });
  };

  function serveStatic(req, res, pathname) {
    if (req.method !== 'GET' && req.method !== 'HEAD') {
      res.writeHead(405, { Allow: 'GET, HEAD', 'Content-Type': 'text/plain; charset=utf-8' });
      return res.end('Method not allowed');
    }
    const file = files.get(pathname);
    if (!file) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' });
      return res.end('Not found');
    }
    const headers = { 'Content-Type': file.type, 'Cache-Control': 'no-cache', ETag: file.etag };
    if (req.headers['if-none-match'] === file.etag) { res.writeHead(304, headers); return res.end(); }
    res.writeHead(200, { ...headers, 'Content-Length': file.body.length });
    res.end(req.method === 'HEAD' ? undefined : file.body);
  }

  async function handleApi(req, res, url) {
    const p = url.pathname;
    if (p === '/api/v2/health') {
      if (req.method !== 'GET') return json(res, 405, { error: 'method not allowed' });
      return json(res, 200, { ok: true, version });
    }
    const itemMatch = /^\/api\/v2\/items\/([^/]+)$/.exec(p);
    if (p !== '/api/v2/sync' && p !== '/api/v2/stats' && !itemMatch) return json(res, 404, { error: 'not found' });
    if (!authorized(req)) return json(res, 401, { error: 'invalid token' });
    if (p === '/api/v2/sync') {
      if (req.method === 'GET') return json(res, 200, store.pull(Number(url.searchParams.get('cursor') || 0)));
      if (req.method !== 'POST') return json(res, 405, { error: 'method not allowed' });
      const raw = await readBody(req);
      return json(res, 200, store.push(JSON.parse(raw.toString('utf8'))));
    }
    if (req.method !== 'GET') return json(res, 405, { error: 'method not allowed' });
    if (p === '/api/v2/stats') return json(res, 200, store.stats());
    const item = ID_RE.test(itemMatch[1]) ? store.item(itemMatch[1]) : null;
    return item ? json(res, 200, item) : json(res, 404, { error: 'not found' });
  }

  const server = http.createServer(async (req, res) => {
    const started = process.hrtime.bigint();
    let pathname = '-';
    for (const [k, v] of Object.entries(SECURITY_HEADERS)) res.setHeader(k, v);
    // Path only: query strings are never logged.
    if (log) res.on('finish', () => log(`${new Date().toISOString()} ${req.method} ${pathname} ${res.statusCode} ${Number(process.hrtime.bigint() - started) / 1e6 | 0}ms`));
    let url;
    // Collapse duplicate leading slashes so a base URL saved with a trailing slash still works.
    try { url = new URL(req.url.replace(/^\/{2,}/, '/'), 'http://hub.invalid'); } catch { return json(res, 400, { error: 'bad request' }); }
    pathname = url.pathname;
    try {
      if (pathname.startsWith('/api/')) return await handleApi(req, res, url);
      return serveStatic(req, res, pathname);
    } catch (e) {
      if (!res.headersSent) return fail(res, e);
      res.destroy();
    }
  });
  return server;
}

module.exports = { createApp, MAX_BODY };
