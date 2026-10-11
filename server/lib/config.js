'use strict';
const path = require('node:path');

const SERVER_DIR = path.join(__dirname, '..');

// Relative data paths are resolved against the server directory.
function resolveDataDir(value) {
  return path.resolve(SERVER_DIR, value || 'data');
}

function loadConfig(env = process.env) {
  const token = env.RH_TOKEN || '';
  if (token.length < 24) throw Error('RH_TOKEN must be set to a random secret of at least 24 characters');
  if (/[\s\x00-\x1f\x7f]/.test(token)) throw Error('RH_TOKEN must not contain whitespace or control characters');
  const port = env.PORT === undefined || env.PORT === '' ? 8788 : Number(env.PORT);
  if (!Number.isInteger(port) || port < 0 || port > 65535) throw Error('PORT must be an integer between 0 and 65535');
  return {
    token,
    port,
    host: env.HOST || '127.0.0.1',
    dataDir: resolveDataDir(env.RH_DATA_DIR),
    keyFile: env.RH_DATA_KEY_FILE || '',
  };
}

module.exports = { loadConfig, resolveDataDir, SERVER_DIR };
