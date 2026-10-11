'use strict';
// Recording Helper sync hub: text-only sync for one person's devices plus a web board.
// Run behind an HTTPS reverse proxy; it listens on 127.0.0.1 by default.
const { loadConfig } = require('./lib/config');

let config;
try { config = loadConfig(process.env); } catch (e) {
  console.error(`Configuration error: ${e.message}`);
  process.exit(1);
}
if (config.keyFile) require('./lib/crypt').init(config.keyFile);

const { SyncStore } = require('./lib/sync-store');
const { createApp } = require('./lib/app');
const { version } = require('./package.json');

const store = new SyncStore(config.dataDir);
if (!config.keyFile && store.hasEncryptedItems()) {
  console.error('Configuration error: the database contains encrypted items; set RH_DATA_KEY_FILE to the matching key file');
  process.exit(1);
}
const server = createApp({ token: config.token, store });

server.listen(config.port, config.host, () => {
  const { address, port } = server.address();
  const host = address.includes(':') ? `[${address}]` : address;
  console.log(`recording-helper hub ${version} listening on http://${host}:${port} (encryption ${config.keyFile ? 'on' : 'off'})`);
});
server.on('error', e => { console.error(e.message); process.exit(1); });

let stopping = false;
function stop(signal) {
  if (stopping) return;
  stopping = true;
  console.log(`${signal} received, shutting down`);
  const force = setTimeout(() => { server.closeAllConnections(); }, 5000);
  force.unref();
  server.close(() => {
    clearTimeout(force);
    store.close();
    process.exit(0);
  });
}
process.on('SIGINT', () => stop('SIGINT'));
process.on('SIGTERM', () => stop('SIGTERM'));
