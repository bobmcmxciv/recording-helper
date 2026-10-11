// Due-date helpers shared by the board (window.RHDates) and the tests (require).
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.RHDates = api;
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';
  const RELATIVE = { 今天: 0, 明天: 1, 后天: 2 };
  const DAY = 86400000;

  const startOfDay = d => new Date(d.getFullYear(), d.getMonth(), d.getDate());

  function make(y, m, d) {
    const date = new Date(y, m - 1, d);
    return date.getFullYear() === y && date.getMonth() === m - 1 && date.getDate() === d ? date : null;
  }

  // Returns a local-midnight Date, or null when the text is not a recognised date.
  // Relative words and "M月D日" are resolved against ref (normally the todo's creation time).
  function parseDue(text, ref) {
    if (typeof text !== 'string') return null;
    const s = text.trim();
    const base = ref instanceof Date && !isNaN(ref) ? ref : new Date();
    if (Object.prototype.hasOwnProperty.call(RELATIVE, s)) {
      const d = startOfDay(base);
      d.setDate(d.getDate() + RELATIVE[s]);
      return d;
    }
    let m = /^(\d{4})-(\d{1,2})-(\d{1,2})$/.exec(s) || /^(\d{4})\/(\d{1,2})\/(\d{1,2})$/.exec(s);
    if (m) return make(+m[1], +m[2], +m[3]);
    m = /^(\d{1,2})月(\d{1,2})日$/.exec(s);
    if (m) return make(base.getFullYear(), +m[1], +m[2]);
    return null;
  }

  const dayDiff = (date, now) => Math.round((startOfDay(date) - startOfDay(now)) / DAY);

  const pad = n => String(n).padStart(2, '0');
  const ymd = d => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;

  function label(date, diff, now) {
    if (diff === 0) return '今天';
    if (diff === 1) return '明天';
    if (diff === 2) return '后天';
    const md = `${date.getMonth() + 1}月${date.getDate()}日`;
    const text = date.getFullYear() === now.getFullYear() ? md : `${date.getFullYear()}年${md}`;
    return diff < 0 ? `逾期 ${-diff} 天 · ${text}` : text;
  }

  // state: 'overdue' | 'today' | 'future' | 'text' (unparsed, shown verbatim); null when empty.
  function dueInfo(due, ref, now) {
    if (due === undefined || due === null) return null;
    const raw = String(due).trim();
    if (!raw) return null;
    const today = now instanceof Date && !isNaN(now) ? now : new Date();
    const date = parseDue(raw, ref instanceof Date && !isNaN(ref) ? ref : today);
    if (!date) return { raw, date: null, state: 'text', diff: null, label: raw };
    const diff = dayDiff(date, today);
    return { raw, date, state: diff < 0 ? 'overdue' : diff === 0 ? 'today' : 'future', diff, label: label(date, diff, today) };
  }

  // Relative words typed now are stored as absolute dates so they do not drift.
  function normalizeDue(text, now) {
    const s = String(text || '').trim();
    if (!Object.prototype.hasOwnProperty.call(RELATIVE, s)) return s;
    return ymd(parseDue(s, now instanceof Date ? now : new Date()));
  }

  return { parseDue, dueInfo, dayDiff, normalizeDue, ymd };
});
