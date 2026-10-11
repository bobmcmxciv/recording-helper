'use strict';
// Optional at-rest encryption (AES-256-GCM) for stored bodies and receipts.
// Ciphertext format: "enc1:" + base64(iv[12] | tag[16] | ciphertext).
// Values without the prefix are returned unchanged, so plaintext databases keep working.
const crypto = require('node:crypto');
const fs = require('node:fs');

const PREFIX = 'enc1:';
let key = null;

function init(keyFile) {
  const k = Buffer.from(fs.readFileSync(keyFile, 'utf8').trim(), 'base64');
  if (k.length !== 32) throw new Error(`Data key must be 32 bytes, base64 encoded (${keyFile})`);
  key = k;
}

function enabled() { return key !== null; }

function enc(v) {
  if (v === null || v === undefined || !key) return v ?? null;
  const s = String(v);
  if (s.startsWith(PREFIX)) return s;
  const iv = crypto.randomBytes(12);
  const c = crypto.createCipheriv('aes-256-gcm', key, iv);
  const body = Buffer.concat([c.update(s, 'utf8'), c.final()]);
  return PREFIX + Buffer.concat([iv, c.getAuthTag(), body]).toString('base64');
}

function dec(v) {
  if (typeof v !== 'string' || !v.startsWith(PREFIX)) return v;
  if (!key) throw new Error('Database contains encrypted values but no data key is configured');
  const b = Buffer.from(v.slice(PREFIX.length), 'base64');
  const d = crypto.createDecipheriv('aes-256-gcm', key, b.subarray(0, 12));
  d.setAuthTag(b.subarray(12, 28));
  return Buffer.concat([d.update(b.subarray(28)), d.final()]).toString('utf8');
}

module.exports = { init, enabled, enc, dec };
