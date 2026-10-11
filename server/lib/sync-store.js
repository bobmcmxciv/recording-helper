'use strict';
// Single-owner workspace store: every device holding the token shares all items.
// Text only (recordings and todos); audio, file paths and models are never accepted.
const { DatabaseSync } = require('node:sqlite');
const fs = require('node:fs');
const path = require('node:path');
const { enc, dec } = require('./crypt');

const ID_RE = /^(rec|todo)-[a-zA-Z0-9-]{8,80}$/;
const DEVICE_RE = /^[a-zA-Z0-9_-]{4,80}$/;
const MAX_CHANGES = 50;
const PAGE = 50;
const FIELDS = {
  recording: ['id', 'title', 'text', 'polished', 'summary', 'occurredAt', 'status', 'error', 'createdAt', 'ownerDevice', 'deleted'],
  todo: ['id', 'title', 'due', 'evidence', 'recordingId', 'done', 'reviewed', 'createdAt', 'deleted', 'category'],
};
const STATUSES = ['pending', 'analyzing', 'ready', 'error', 'silent'];

const bad = message => Object.assign(new Error(message), { status: 400 });

function validate(change) {
  if (!change || !ID_RE.test(change.id || '')) throw bad('invalid id');
  if (!['recording', 'todo'].includes(change.kind)) throw bad('invalid kind');
  if (!Number.isSafeInteger(change.baseRev) || change.baseRev < 0) throw bad('invalid revision');
  if (!Number.isSafeInteger(change.localVersion) || change.localVersion < 1) throw bad('invalid local version');
  const body = change.body;
  if (!body || body.id !== change.id || typeof body.title !== 'string' || body.title.length > 500) throw bad('invalid body');
  const allowed = FIELDS[change.kind];
  if (Object.keys(body).some(k => !allowed.includes(k))) throw bad('unsupported field');
  if ('deleted' in body && typeof body.deleted !== 'boolean') throw bad('invalid deleted');
  if (change.kind === 'todo' && typeof body.done !== 'boolean') throw bad('invalid done');
  if (change.kind === 'todo' && body.category !== undefined && !['work', 'personal', ''].includes(body.category)) throw bad('invalid category');
  if (change.kind === 'recording' && (typeof body.text !== 'string' || !STATUSES.includes(body.status))) throw bad('invalid recording');
  for (const key of Object.keys(body)) {
    const value = body[key];
    if (!['string', 'number', 'boolean'].includes(typeof value) || (typeof value === 'number' && !Number.isFinite(value))) throw bad('invalid field type');
  }
  if (JSON.stringify(body).length > 1_000_000) throw bad('item too large');
  return change;
}

class SyncStore {
  constructor(dataDir) {
    fs.mkdirSync(dataDir, { recursive: true });
    this.db = new DatabaseSync(path.join(dataDir, 'local-sync.db'));
    this.db.exec(`PRAGMA journal_mode=WAL;
      PRAGMA busy_timeout=5000;
      CREATE TABLE IF NOT EXISTS items(id TEXT PRIMARY KEY, kind TEXT NOT NULL, body TEXT NOT NULL, rev INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS sequence(id INTEGER PRIMARY KEY CHECK(id=1), value INTEGER NOT NULL);
      INSERT OR IGNORE INTO sequence VALUES(1,0);
      CREATE TABLE IF NOT EXISTS receipts(device TEXT, item TEXT, version INTEGER, response TEXT, PRIMARY KEY(device,item,version));
      CREATE TABLE IF NOT EXISTS devices(device TEXT PRIMARY KEY, last_seen INTEGER NOT NULL, pushes INTEGER NOT NULL DEFAULT 0);`);
    const q = sql => this.db.prepare(sql);
    this.sql = {
      item: q('SELECT * FROM items WHERE id=?'),
      receipt: q('SELECT response FROM receipts WHERE device=? AND item=? AND version=?'),
      nextRev: q('UPDATE sequence SET value=value+1 WHERE id=1 RETURNING value'),
      upsert: q('INSERT INTO items VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET body=excluded.body,rev=excluded.rev'),
      saveReceipt: q('INSERT INTO receipts VALUES(?,?,?,?)'),
      seen: q('INSERT INTO devices VALUES(?,?,1) ON CONFLICT(device) DO UPDATE SET last_seen=excluded.last_seen, pushes=pushes+1'),
      page: q('SELECT * FROM items WHERE rev>? ORDER BY rev LIMIT ?'),
      all: q('SELECT kind, body FROM items'),
      lastRev: q('SELECT value FROM sequence WHERE id=1'),
      devices: q('SELECT device, last_seen AS lastSeen, pushes FROM devices ORDER BY last_seen DESC'),
      encrypted: q("SELECT 1 FROM items WHERE body LIKE 'enc1:%' LIMIT 1"),
    };
  }

  item(id) {
    const row = this.sql.item.get(id);
    return row ? { ...row, body: JSON.parse(dec(row.body)) } : null;
  }

  push(payload, now = Date.now()) {
    if (!payload || !DEVICE_RE.test(payload.device || '')) throw bad('invalid device');
    if (!Array.isArray(payload.changes) || payload.changes.length > MAX_CHANGES) throw bad('max 50 changes');
    payload.changes.forEach(validate);
    if (new Set(payload.changes.map(c => c.id)).size !== payload.changes.length) throw bad('duplicate id in batch');
    this.db.exec('BEGIN IMMEDIATE');
    try {
      this.sql.seen.run(payload.device, now);
      const results = payload.changes.map(c => {
        const saved = this.sql.receipt.get(payload.device, c.id, c.localVersion);
        if (saved) return JSON.parse(dec(saved.response));
        let item = this.item(c.id);
        const conflict = !!item && (item.rev !== c.baseRev || item.kind !== c.kind);
        if (!conflict) {
          if (!item && c.baseRev !== 0) throw bad('unknown base revision');
          const rev = this.sql.nextRev.get().value;
          this.sql.upsert.run(c.id, c.kind, enc(JSON.stringify(c.body)), rev);
          item = this.item(c.id);
        }
        const result = { item, localVersion: c.localVersion, conflict };
        this.sql.saveReceipt.run(payload.device, c.id, c.localVersion, enc(JSON.stringify(result)));
        return result;
      });
      this.db.exec('COMMIT');
      return { results };
    } catch (e) { this.db.exec('ROLLBACK'); throw e; }
  }

  pull(cursor = 0) {
    if (!Number.isSafeInteger(cursor) || cursor < 0) throw bad('invalid cursor');
    const rows = this.sql.page.all(cursor, PAGE + 1);
    const items = rows.slice(0, PAGE).map(r => ({ ...r, body: JSON.parse(dec(r.body)) }));
    return { items, cursor: items.length ? items.at(-1).rev : cursor, more: rows.length > PAGE };
  }

  // Tombstoned items (deleted:true) and todos of tombstoned recordings are not counted.
  stats() {
    const recordings = [], todos = [];
    for (const row of this.sql.all.all()) (row.kind === 'recording' ? recordings : todos).push(JSON.parse(dec(row.body)));
    const gone = new Set(recordings.filter(r => r.deleted === true).map(r => r.id));
    const live = todos.filter(t => t.deleted !== true && !(t.recordingId && gone.has(t.recordingId)));
    const open = live.filter(t => t.done !== true);
    return {
      recordings: recordings.length - gone.size,
      todos: live.length,
      openTodos: open.length,
      reviewedPending: open.filter(t => t.reviewed === false).length,
      devices: this.sql.devices.all().map(d => ({ device: d.device, lastSeen: d.lastSeen, pushes: d.pushes })),
      lastRev: this.lastRev(),
    };
  }

  lastRev() { return this.sql.lastRev.get().value; }

  hasEncryptedItems() { return !!this.sql.encrypted.get(); }

  close() { this.db.close(); }
}

module.exports = { SyncStore, validate, ID_RE };
