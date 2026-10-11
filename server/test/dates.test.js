'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { parseDue, dueInfo, normalizeDue, ymd } = require('../web/dates.js');

const ref = new Date(2026, 9, 11, 15, 30);
const day = d => (d ? ymd(d) : null);

test('parses supported due formats', () => {
  assert.equal(day(parseDue('2026-10-12', ref)), '2026-10-12');
  assert.equal(day(parseDue('2026/1/5', ref)), '2026-01-05');
  assert.equal(day(parseDue('2026/10/09', ref)), '2026-10-09');
  assert.equal(day(parseDue('10月12日', ref)), '2026-10-12');
  assert.equal(day(parseDue('1月3日', new Date(2027, 0, 1))), '2027-01-03');
  assert.equal(day(parseDue('今天', ref)), '2026-10-11');
  assert.equal(day(parseDue(' 明天 ', ref)), '2026-10-12');
  assert.equal(day(parseDue('后天', new Date(2026, 11, 31))), '2027-01-02');
});

test('anything else is not a date', () => {
  for (const s of ['', '下周三前', '2026-02-30', '2026-10-12 15:00', '13月1日', '10/12', 'toString', '大后天', '明天上午']) {
    assert.equal(parseDue(s, ref), null, s);
  }
  assert.equal(parseDue(undefined, ref), null);
  assert.equal(parseDue(20261012, ref), null);
});

test('due state and labels relative to today', () => {
  const createdYesterday = new Date(2026, 9, 10, 9, 0);
  assert.equal(dueInfo('今天', createdYesterday, ref).state, 'overdue');
  assert.equal(dueInfo('今天', createdYesterday, ref).label, '逾期 1 天 · 10月10日');
  assert.equal(dueInfo('明天', createdYesterday, ref).state, 'today');
  assert.equal(dueInfo('2026-10-11', null, ref).label, '今天');
  assert.equal(dueInfo('2026/10/12', null, ref).label, '明天');
  assert.equal(dueInfo('10月20日', null, ref).state, 'future');
  assert.equal(dueInfo('10月20日', null, ref).label, '10月20日');
  assert.equal(dueInfo('2027-01-03', null, ref).label, '2027年1月3日');
  const text = dueInfo('下周三前', null, ref);
  assert.equal(text.state, 'text');
  assert.equal(text.label, '下周三前');
  assert.equal(dueInfo('', null, ref), null);
  assert.equal(dueInfo(undefined, null, ref), null);
});

test('relative words are stored as absolute dates', () => {
  assert.equal(normalizeDue('明天', ref), '2026-10-12');
  assert.equal(normalizeDue(' 今天 ', ref), '2026-10-11');
  assert.equal(normalizeDue('10月12日', ref), '10月12日');
  assert.equal(normalizeDue('', ref), '');
});
