'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { SyncStore } = require('../lib/sync-store');

function fixture(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rh-sync-'));
  const s = new SyncStore(dir);
  t.after(() => { s.close(); fs.rmSync(dir, { recursive: true, force: true }); });
  return s;
}
const change = (n = 1, rev = 0, done = false) => ({ id: 'todo-abcdefgh' + n, kind: 'todo', baseRev: rev, localVersion: n, body: { id: 'todo-abcdefgh' + n, title: '测试事项', done } });
const recording = (id, extra = {}) => ({ id, kind: 'recording', baseRev: 0, localVersion: 1, body: { id, title: '录音', text: '张三：你好', status: 'ready', ...extra } });

test('idempotent retries, two-device conflict, browser completion and paged pull', t => {
  const s = fixture(t), c = change();
  const first = s.push({ device: 'phoneA', changes: [c] });
  assert.equal(first.results[0].item.rev, 1);
  assert.deepEqual(s.push({ device: 'phoneA', changes: [c] }), first);
  const conflict = s.push({ device: 'phoneB', changes: [{ ...c, body: { ...c.body, done: true } }] });
  assert.equal(conflict.results[0].conflict, true); assert.equal(s.item(c.id).body.done, false);
  const update = { ...c, baseRev: 1, localVersion: 2, body: { ...c.body, done: true } };
  assert.equal(s.push({ device: 'browser', changes: [update] }).results[0].conflict, false);
  assert.equal(s.pull(1).items[0].body.done, true);
  for (let n = 2; n <= 62; n++) s.push({ device: 'phoneA', changes: [change(n)] });
  const page = s.pull(); assert.equal(page.items.length, 50); assert.equal(page.more, true);
  assert.equal(s.pull(page.cursor).items.length, 12);
});

test('invalid batches are atomic and no audio/path payload accepted', t => {
  const s = fixture(t);
  assert.throws(() => s.push({ device: 'phoneA', changes: [change(), { ...change(2), body: { ...change(2).body, audio: 'private' } }] }));
  assert.equal(s.pull().items.length, 0);
  assert.throws(() => s.push({ device: '../secret', changes: [] }));
  assert.throws(() => s.pull(-1));
  assert.throws(() => s.push({ device: 'phoneA', changes: [change(), change()] }));
});

test('wire shape: items are {id, kind, body, rev} and unknown base revision is rejected', t => {
  const s = fixture(t);
  const res = s.push({ device: 'phoneA', changes: [change()] });
  assert.deepEqual(Object.keys(res.results[0]), ['item', 'localVersion', 'conflict']);
  assert.deepEqual(Object.keys(res.results[0].item), ['id', 'kind', 'body', 'rev']);
  assert.deepEqual(Object.keys(s.pull()), ['items', 'cursor', 'more']);
  assert.throws(() => s.push({ device: 'phoneA', changes: [change(9, 5)] }), /unknown base revision/);
  assert.throws(() => s.push({ device: 'phoneA', changes: Array.from({ length: 51 }, (_, i) => change(i + 1)) }), /max 50 changes/);
  assert.throws(() => s.push({ device: 'phoneA', changes: [recording('rec-abcdefgh1', { status: 'done' })] }), /invalid recording/);
});

test('stats count items and track pushing devices', t => {
  const s = fixture(t);
  s.push({ device: 'phoneA', changes: [change(1), change(2, 0, true), recording('rec-abcdefgh1')] }, 1000);
  s.push({ device: 'phoneA', changes: [] }, 2000);
  s.push({ device: 'tabletB', changes: [{ ...change(3), body: { ...change(3).body, reviewed: false } }] }, 3000);
  const stats = s.stats();
  assert.equal(stats.recordings, 1);
  assert.equal(stats.todos, 3);
  assert.equal(stats.openTodos, 2);
  assert.equal(stats.reviewedPending, 1);
  assert.equal(stats.lastRev, 4);
  assert.deepEqual(stats.devices, [{ device: 'tabletB', lastSeen: 3000, pushes: 1 }, { device: 'phoneA', lastSeen: 2000, pushes: 2 }]);
  assert.throws(() => s.push({ device: 'ghost', changes: [{ ...change(4), body: { bad: 1 } }] }));
  assert.equal(s.stats().devices.length, 2, 'rejected batches do not register a device');
});

test('deleted tombstones: boolean accepted, other types rejected, excluded from stats', t => {
  const s = fixture(t);
  const rec = 'rec-abcdefgh1';
  s.push({ device: 'phoneA', changes: [
    recording(rec),
    recording('rec-abcdefgh2'),
    { ...change(1), body: { ...change(1).body, recordingId: rec, reviewed: false } },
    change(2),
    { ...change(3), body: { ...change(3).body, deleted: false } },
  ] });
  assert.throws(() => s.push({ device: 'phoneA', changes: [{ ...change(4), body: { ...change(4).body, deleted: 'yes' } }] }), /invalid deleted/);
  assert.throws(() => s.push({ device: 'phoneA', changes: [{ ...recording('rec-abcdefgh3'), body: { ...recording('rec-abcdefgh3').body, deleted: 1 } }] }), /invalid deleted/);
  assert.equal(s.item('todo-abcdefgh4'), null);
  const before = s.stats();
  assert.deepEqual([before.recordings, before.todos, before.openTodos, before.reviewedPending], [2, 3, 3, 1]);

  const recItem = s.item(rec), todoItem = s.item('todo-abcdefgh2');
  const res = s.push({ device: 'phoneA', changes: [
    { id: rec, kind: 'recording', baseRev: recItem.rev, localVersion: 2, body: { ...recItem.body, deleted: true } },
    { id: todoItem.id, kind: 'todo', baseRev: todoItem.rev, localVersion: 5, body: { ...todoItem.body, deleted: true } },
  ] });
  assert.equal(res.results.every(r => !r.conflict), true);
  assert.equal(s.item(rec).body.deleted, true);
  const after = s.stats();
  assert.equal(after.recordings, 1, 'tombstoned recording excluded');
  assert.equal(after.todos, 1, 'tombstoned todo and todos of a tombstoned recording excluded');
  assert.equal(after.openTodos, 1);
  assert.equal(after.reviewedPending, 0);
  assert.equal(s.pull().items.length, 5, 'tombstones still sync to other devices');
});

test('todo category: work/personal/empty accepted, other values rejected', t => {
  const s = fixture(t);
  const ok = s.push({ device: 'phoneA', changes: [{ ...change(7), body: { ...change(7).body, category: 'work' } }] });
  assert.equal(ok.results[0].item.body.category, 'work');
  assert.throws(() => s.push({ device: 'phoneA', changes: [{ ...change(8), body: { ...change(8).body, category: 'urgent' } }] }));
});
