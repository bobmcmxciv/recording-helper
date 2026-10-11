'use strict';
// Workspace board: pulls items via api/v2/sync (relative URLs, works under a sub-path) and pushes todo edits.
(() => {
  const Dates = window.RHDates;
  const $ = id => document.getElementById(id);

  const TOKEN_KEY = 'rh.token';
  const DEVICE_KEY = 'rh.device';
  const VERSION_KEY = 'rh.localVersion';
  const REFRESH_MS = 60_000;
  const DONE_LIMIT = 20;
  const SPEAKER = /^(.{1,20}?)：(.*)$/;
  const STATUS = {
    pending: ['待整理', 'st-pending'],
    analyzing: ['整理中', 'st-pending'],
    ready: ['已生成', 'st-ready'],
    error: ['失败', 'st-error'],
    silent: ['未识别', 'st-silent'],
  };
  const FILTER_NAMES = { all: '全部', pending: '待整理', ready: '已生成', error: '失败', silent: '未识别' };
  const filterOf = status => (status === 'analyzing' ? 'pending' : status);
  const wide = window.matchMedia('(min-width: 1100px)');
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');

  const state = {
    token: '',
    pendingRemember: null,
    items: new Map(),
    cursor: 0,
    stats: null,
    lastSync: null,
    loaded: false,
    loadError: '',
    busy: false,
    timer: null,
    filter: 'all',
    query: '',
    view: 'todos',
    selected: null,
    tab: 'summary',
    detailKey: '',
    editing: null,
    editRev: 0,
    doneOpen: false,
    expanded: new Set(),
    opener: null,
    model: null,
  };
  const searchCache = new Map();

  // ---------- small helpers ----------
  function h(tag, props, ...children) {
    const el = document.createElement(tag);
    if (props) {
      for (const [k, v] of Object.entries(props)) {
        if (v === undefined || v === null || v === false) continue;
        if (k === 'class') el.className = v;
        else if (k === 'dataset') Object.assign(el.dataset, v);
        else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
        else el.setAttribute(k, v === true ? '' : String(v));
      }
    }
    for (const c of children.flat()) if (c !== null && c !== undefined && c !== false) el.append(c);
    return el;
  }
  const storage = name => { try { return window[name]; } catch { return null; } };
  const readKey = (area, key) => { try { return area ? area.getItem(key) : null; } catch { return null; } };
  const writeKey = (area, key, value) => { try { if (area) area.setItem(key, value); } catch { /* storage unavailable */ } };
  const dropKey = (area, key) => { try { if (area) area.removeItem(key); } catch { /* storage unavailable */ } };
  const pad = n => String(n).padStart(2, '0');
  const fmtDateTime = d => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  const fmtTime = d => `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  const truncate = (s, n) => (s.length > n ? s.slice(0, n) + '…' : s);
  const scrollMode = () => (reducedMotion.matches ? 'auto' : 'smooth');

  function toTime(v) {
    if (typeof v === 'number' && Number.isFinite(v)) return v;
    if (typeof v === 'string' && v) {
      const t = /^\d+$/.test(v) ? Number(v) : Date.parse(v);
      if (Number.isFinite(t)) return t;
    }
    return NaN;
  }
  function recTime(b) {
    const t = toTime(b.occurredAt);
    if (Number.isFinite(t)) return t;
    const c = toTime(b.createdAt);
    return Number.isFinite(c) ? c : 0;
  }
  function relative(ms) {
    if (!Number.isFinite(ms)) return '未知';
    const d = new Date(ms), now = new Date();
    const mins = Math.floor((now - d) / 60000);
    if (mins < 1) return '刚刚';
    if (mins < 60) return `${mins} 分钟前`;
    const days = Dates.dayDiff(d, now);
    if (days === 0) return `今天 ${fmtTime(d)}`;
    if (days === -1) return `昨天 ${fmtTime(d)}`;
    return fmtDateTime(d);
  }
  function shortDevice(id) {
    if (!id) return '';
    const s = String(id);
    return s.length <= 12 ? s : s.slice(0, 8) + '…';
  }
  function randomHex(bytes) {
    const b = new Uint8Array(bytes);
    crypto.getRandomValues(b);
    return Array.from(b, x => x.toString(16).padStart(2, '0')).join('');
  }
  function uuid() {
    if (typeof crypto.randomUUID === 'function') return crypto.randomUUID();
    const x = randomHex(16);
    return `${x.slice(0, 8)}-${x.slice(8, 12)}-4${x.slice(13, 16)}-a${x.slice(17, 20)}-${x.slice(20, 32)}`;
  }
  let deviceCache = '';
  function deviceId() {
    if (deviceCache) return deviceCache;
    const local = storage('localStorage');
    let id = readKey(local, DEVICE_KEY);
    if (!/^web-[a-f0-9]{8,32}$/.test(id || '')) {
      id = 'web-' + randomHex(6);
      writeKey(local, DEVICE_KEY, id);
    }
    return (deviceCache = id);
  }
  let versionFloor = 0;
  function nextVersion() {
    const local = storage('localStorage');
    const last = Math.max(Number(readKey(local, VERSION_KEY)) || 0, versionFloor);
    const v = Math.max(Date.now(), last + 1);
    versionFloor = v;
    writeKey(local, VERSION_KEY, String(v));
    return v;
  }

  // ---------- feedback ----------
  function toast(message, tone) {
    const el = h('div', { class: 'toast', dataset: tone ? { tone } : undefined }, message);
    $('toasts').append(el);
    setTimeout(() => el.remove(), tone === 'error' ? 6000 : 3500);
  }
  function setStatus(kind, text) {
    $('status').dataset.state = kind;
    $('status-text').textContent = text;
  }
  async function copy(text, what) {
    if (!text) { toast(`没有可复制的${what}`); return; }
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      const area = h('textarea', { class: 'sr-only', readonly: true });
      area.value = text;
      document.body.append(area);
      area.select();
      const ok = document.execCommand('copy');
      area.remove();
      if (!ok) { toast('复制失败，请手动选择文本', 'error'); return; }
    }
    toast(`已复制${what}`);
  }

  // ---------- network ----------
  async function api(path, options = {}) {
    let res;
    try {
      res = await fetch(path, {
        method: options.method || 'GET',
        headers: Object.assign({ 'X-Auth-Token': state.token }, options.body ? { 'Content-Type': 'application/json' } : {}),
        body: options.body ? JSON.stringify(options.body) : undefined,
        cache: 'no-store',
        credentials: 'omit',
      });
    } catch {
      throw new Error('无法连接服务器，请检查网络');
    }
    if (res.status === 401) throw Object.assign(new Error('密钥无效'), { auth: true });
    if (!res.ok) {
      let detail = '';
      try { detail = (await res.json()).error || ''; } catch { /* not json */ }
      throw new Error(`服务器返回 ${res.status}${detail ? '：' + detail : ''}`);
    }
    return res.json();
  }
  function merge(item) {
    if (!item || typeof item.id !== 'string' || !item.body) return;
    const cur = state.items.get(item.id);
    if (!cur || item.rev >= cur.rev) state.items.set(item.id, item);
  }

  async function refresh(opts = {}) {
    if (!state.token || state.busy) return false;
    state.busy = true;
    setStatus('busy', '同步中…');
    $('refresh').disabled = true;
    try {
      let cursor = state.cursor, page;
      do {
        page = await api('api/v2/sync?cursor=' + cursor);
        page.items.forEach(merge);
        cursor = page.cursor;
      } while (page.more);
      state.cursor = cursor;
      state.stats = await api('api/v2/stats');
      state.lastSync = new Date();
      state.loaded = true;
      state.loadError = '';
      if (state.pendingRemember !== null) { persistToken(state.token, state.pendingRemember); state.pendingRemember = null; }
      setStatus('ok', '已连接');
      render();
      if (opts.announce) toast('已连接工作空间');
      return true;
    } catch (e) {
      if (e.auth) { authFailed(); return false; }
      setStatus('error', navigator.onLine === false ? '网络已断开' : '连接失败');
      if (opts.manual || !state.loaded) toast(e.message, 'error');
      if (!state.loaded) { state.loadError = e.message; render(); }
      return false;
    } finally {
      state.busy = false;
      $('refresh').disabled = false;
    }
  }

  let queue = Promise.resolve();
  function save(item, patch, okMessage) {
    const run = async () => {
      const body = Object.assign({}, item.body, patch);
      for (const k of Object.keys(body)) if (body[k] === undefined) delete body[k];
      try {
        const res = await api('api/v2/sync', {
          method: 'POST',
          body: { device: deviceId(), changes: [{ id: item.id, kind: item.kind, baseRev: item.rev, localVersion: nextVersion(), body }] },
        });
        const result = res.results && res.results[0];
        if (!result || !result.item) throw new Error('服务器没有返回结果');
        merge(result.item);
        if (result.conflict) {
          render();
          toast('其他设备已修改，已刷新', 'warn');
          await refresh();
          return false;
        }
        render();
        if (okMessage) toast(okMessage);
        return true;
      } catch (e) {
        if (e.auth) authFailed();
        else { toast('保存失败：' + e.message, 'error'); render(); }
        return false;
      }
    };
    const p = queue.then(run, run);
    queue = p.catch(() => {});
    return p;
  }

  // ---------- session ----------
  function persistToken(token, remember) {
    const local = storage('localStorage'), session = storage('sessionStorage');
    dropKey(local, TOKEN_KEY);
    dropKey(session, TOKEN_KEY);
    writeKey(remember ? local : session, TOKEN_KEY, token);
  }
  function forgetToken() {
    dropKey(storage('localStorage'), TOKEN_KEY);
    dropKey(storage('sessionStorage'), TOKEN_KEY);
  }
  function resetData() {
    state.items.clear();
    searchCache.clear();
    state.cursor = 0;
    state.stats = null;
    state.lastSync = null;
    state.loaded = false;
    state.loadError = '';
    state.selected = null;
    state.editing = null;
    state.detailKey = '';
    state.expanded.clear();
  }
  function showWorkspace(on) {
    $('welcome').hidden = on;
    $('workspace').hidden = !on;
    $('connect-form').hidden = on;
    $('session').hidden = !on;
  }
  function startTimer() {
    stopTimer();
    state.timer = setInterval(() => { if (document.visibilityState === 'visible') refresh(); }, REFRESH_MS);
  }
  function stopTimer() {
    if (state.timer) clearInterval(state.timer);
    state.timer = null;
  }
  async function connect(token, remember, auto) {
    state.token = token;
    state.pendingRemember = remember;
    resetData();
    closeSheet();
    showWorkspace(true);
    render();
    startTimer();
    await refresh({ manual: !auto, announce: !auto });
  }
  function disconnect(message) {
    stopTimer();
    forgetToken();
    state.token = '';
    state.pendingRemember = null;
    resetData();
    closeSheet();
    toggleDevices(false);
    showWorkspace(false);
    render();
    if (message) toast(message);
  }
  function authFailed() {
    disconnect();
    setStatus('auth', '密钥无效');
    toast('密钥无效，请重新输入', 'error');
    $('token').focus();
  }

  // ---------- model ----------
  function dueOf(body, now) {
    const created = toTime(body.createdAt);
    return Dates.dueInfo(body.due, Number.isFinite(created) ? new Date(created) : null, now);
  }
  function buildModel(now) {
    const recs = [], todos = [];
    for (const it of state.items.values()) {
      if (it.kind === 'recording') recs.push(it);
      else if (it.kind === 'todo') todos.push(it);
    }
    const gone = new Set(recs.filter(r => r.body.deleted === true).map(r => r.id));
    const liveRecs = recs.filter(r => !gone.has(r.id)).sort((a, b) => recTime(b.body) - recTime(a.body) || b.rev - a.rev);
    const recById = new Map(liveRecs.map(r => [r.id, r]));
    const rows = todos
      .filter(t => t.body.deleted !== true && !(t.body.recordingId && gone.has(t.body.recordingId)))
      .map(item => ({ item, due: dueOf(item.body, now) }));
    const created = r => toTime(r.item.body.createdAt) || 0;
    const open = rows.filter(r => r.item.body.done !== true);
    const today = open.filter(r => r.due && (r.due.state === 'overdue' || r.due.state === 'today'))
      .sort((a, b) => a.due.date - b.due.date || created(b) - created(a));
    const inToday = new Set(today);
    const review = open.filter(r => !inToday.has(r) && r.item.body.reviewed === false).sort((a, b) => created(b) - created(a));
    const other = open.filter(r => !inToday.has(r) && r.item.body.reviewed !== false).sort((a, b) => {
      const da = a.due && a.due.date ? a.due.date.getTime() : Infinity;
      const db = b.due && b.due.date ? b.due.date.getTime() : Infinity;
      return da - db || created(b) - created(a);
    });
    const done = rows.filter(r => r.item.body.done === true).sort((a, b) => b.item.rev - a.item.rev);
    const byRecording = new Map();
    for (const r of rows) {
      const id = r.item.body.recordingId;
      if (!id || !recById.has(id)) continue;
      if (!byRecording.has(id)) byRecording.set(id, []);
      byRecording.get(id).push(r);
    }
    return {
      recs: liveRecs, recById, rows, open, today, review, other, done, byRecording,
      overdue: today.filter(r => r.due.state === 'overdue').length,
      unreviewed: open.filter(r => r.item.body.reviewed === false).length,
    };
  }

  // ---------- rendering ----------
  function render() {
    const now = new Date();
    const m = buildModel(now);
    state.model = m;
    const focusKey = document.activeElement && document.activeElement.dataset ? document.activeElement.dataset.focus : null;
    renderStats(m);
    if (!state.editing) renderTodos(m);
    renderRecordings(m);
    renderDetail(m, false);
    $('last-sync').textContent = state.lastSync ? `上次同步 ${fmtTime(state.lastSync)}` : '尚未同步';
    if (focusKey && (!document.activeElement || document.activeElement === document.body)) {
      const target = document.querySelector(`[data-focus="${CSS.escape(focusKey)}"]`);
      if (target) target.focus({ preventScroll: true });
    }
  }

  function setStat(key, value, hint, tone) {
    $('num-' + key).textContent = String(value);
    $('hint-' + key).textContent = hint || '';
    const tile = $('num-' + key).closest('.stat');
    if (tone) tile.dataset.tone = tone; else delete tile.dataset.tone;
  }
  function renderStats(m) {
    setStat('open', m.open.length, m.done.length ? `已完成 ${m.done.length}` : '');
    setStat('today', m.today.length, m.overdue ? `其中 ${m.overdue} 项已逾期` : m.today.length ? '今天到期' : '今天无到期事项',
      m.overdue ? 'danger' : m.today.length ? 'warn' : '');
    setStat('review', m.unreviewed, m.unreviewed ? 'AI 建议，请核对' : '', m.unreviewed ? 'accent' : '');
    const ready = m.recs.filter(r => r.body.status === 'ready').length;
    setStat('recs', m.recs.length, m.recs.length ? `${ready} 条已生成纪要` : '');
    const devices = (state.stats && state.stats.devices) || [];
    setStat('devices', devices.length, devices.length ? `最近 ${relative(devices[0].lastSeen)}` : '');
    $('stat-devices').title = devices.length
      ? devices.map(d => `${d.device}　最近同步 ${relative(d.lastSeen)}`).join('\n')
      : '还没有设备推送过数据';
    $('todo-count').textContent = String(m.open.length);
    $('sw-todos').textContent = String(m.open.length);
    $('rec-count').textContent = String(m.recs.length);
    $('sw-recs').textContent = String(m.recs.length);
    if (!$('device-pop').hidden) renderDevices();
  }
  function renderDevices() {
    const devices = (state.stats && state.stats.devices) || [];
    const mine = deviceId();
    const rows = devices.map(d => h('div', { class: 'dev' },
      h('span', { class: 'dev-name' }, d.device, d.device === mine ? h('small', null, '此浏览器') : null),
      h('span', { class: 'dev-meta' }, `最近同步 ${relative(d.lastSeen)}`, h('br'), `推送 ${d.pushes} 次`)));
    $('device-pop').replaceChildren(
      h('p', { class: 'pop-title' }, `已同步的设备（${devices.length}）`),
      ...(rows.length ? rows : [h('p', { class: 'pop-note' }, '还没有设备推送过数据。')]),
      h('p', { class: 'pop-note' }, '在此浏览器修改待办时，本浏览器也会登记为一台设备。'));
  }
  function toggleDevices(force) {
    const pop = $('device-pop');
    const open = typeof force === 'boolean' ? force : pop.hidden;
    pop.hidden = !open;
    $('stat-devices').setAttribute('aria-expanded', String(open));
    if (open) renderDevices();
  }

  function skeleton(count, tall) {
    return Array.from({ length: count }, () => h('div', { class: 'skel' + (tall ? ' tall' : ''), 'aria-hidden': 'true' }));
  }
  function empty(title, text) {
    return h('div', { class: 'empty' }, h('p', { class: 'empty-title' }, title), text ? h('p', null, text) : null);
  }
  function loadFailed() {
    return empty('暂时无法加载', state.loadError + '。稍后会自动重试，也可以点击顶部的“刷新”。');
  }
  function dueChip(due) {
    const cls = due.state === 'overdue' ? 'chip chip-overdue' : due.state === 'today' ? 'chip chip-today' : 'chip';
    return h('span', { class: cls, title: due.date ? `期限 ${Dates.ymd(due.date)}` : `期限：${due.raw}` }, due.label);
  }
  function statusChip(status) {
    const [label, cls] = STATUS[status] || [String(status || '未知'), 'st-silent'];
    return h('span', { class: 'chip ' + cls }, label);
  }

  function todoRow(row, compact) {
    const { item, due } = row;
    const b = item.body;
    if (!compact && state.editing === item.id) return editRow(item);
    const done = b.done === true;
    const title = b.title || '（无标题）';
    const check = h('input', {
      type: 'checkbox',
      'aria-label': (done ? '标记为未完成：' : '标记为完成：') + title,
      dataset: { focus: (compact ? 'dchk:' : 'chk:') + item.id },
    });
    check.checked = done;
    check.addEventListener('change', async () => {
      check.disabled = true;
      const ok = await save(item, { done: check.checked, reviewed: true }, check.checked ? '已标记完成' : '已恢复为未完成');
      if (!ok) { check.checked = done; check.disabled = false; }
    });
    const titleEl = compact
      ? h('span', { class: 'todo-title' }, title)
      : h('button', { type: 'button', class: 'todo-title', title: '编辑标题和期限', dataset: { focus: 'title:' + item.id }, onclick: () => startEdit(item) }, title);
    const meta = [];
    if (due) meta.push(dueChip(due));
    if (b.category === 'work') meta.push(h('span', { class: 'chip chip-cat' }, '公事'));
    else if (b.category === 'personal') meta.push(h('span', { class: 'chip chip-cat' }, '私事'));
    if (b.reviewed === false && !done) meta.push(h('span', { class: 'chip chip-ai' }, 'AI 建议'));
    const rec = !compact && b.recordingId ? state.model.recById.get(b.recordingId) : null;
    if (rec) {
      meta.push(h('button', {
        type: 'button', class: 'chip chip-link', title: '查看来源录音：' + (rec.body.title || ''),
        dataset: { focus: 'src:' + item.id }, onclick: () => openDetail(rec.id, 'summary'),
      }, h('span', null, '来源 · ' + truncate(rec.body.title || '未命名录音', 12))));
    }
    let evidence = null;
    if (typeof b.evidence === 'string' && b.evidence.trim()) {
      const text = b.evidence.trim();
      const long = text.length > 60;
      const open = state.expanded.has(item.id);
      const p = h('p', { class: 'evidence' + (long && !open ? ' clamp' : '') }, text);
      evidence = [p];
      if (long) {
        evidence.push(h('button', {
          type: 'button', class: 'btn-link', 'aria-expanded': String(open), dataset: { focus: 'ev:' + item.id },
          onclick: e => {
            const nowOpen = !state.expanded.has(item.id);
            if (nowOpen) state.expanded.add(item.id); else state.expanded.delete(item.id);
            p.classList.toggle('clamp', !nowOpen);
            e.currentTarget.textContent = nowOpen ? '收起' : '展开';
            e.currentTarget.setAttribute('aria-expanded', String(nowOpen));
          },
        }, open ? '收起' : '展开'));
      }
    }
    const side = b.reviewed === false && !done
      ? h('div', { class: 'todo-side' }, h('button', {
        type: 'button', class: 'btn btn-ghost btn-sm', 'aria-label': '确认这条建议：' + title,
        dataset: { focus: (compact ? 'dok:' : 'ok:') + item.id },
        onclick: () => save(item, { reviewed: true }, '已确认'),
      }, '确认'))
      : h('div');
    return h('div', { class: 'todo' + (done ? ' is-done' : ''), role: 'listitem', dataset: { id: item.id } },
      h('label', { class: 'check' }, check),
      h('div', { class: 'todo-main' }, titleEl, meta.length ? h('div', { class: 'todo-meta' }, meta) : null, evidence),
      side);
  }

  function editRow(item) {
    const b = item.body;
    const title = h('input', { class: 'field', name: 'title', maxlength: '500', required: true, 'aria-label': '待办标题', value: b.title || '' });
    const due = h('input', {
      class: 'field', name: 'due', maxlength: '40', 'aria-label': '期限（可选）',
      placeholder: '期限：2026-10-12、10月12日、明天', value: b.due === undefined || b.due === null ? '' : String(b.due),
    });
    const form = h('form', { class: 'todo-edit', 'aria-label': '编辑待办' },
      h('div', { class: 'row' }, title),
      h('div', { class: 'row' }, due),
      h('div', { class: 'actions' },
        h('button', { type: 'button', class: 'btn btn-ghost btn-sm', onclick: () => cancelEdit(item.id) }, '取消'),
        h('button', { type: 'submit', class: 'btn btn-primary btn-sm' }, '保存')));
    form.addEventListener('keydown', e => {
      if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); cancelEdit(item.id); }
    });
    form.addEventListener('submit', async e => {
      e.preventDefault();
      const newTitle = title.value.trim();
      if (!newTitle) { toast('标题不能为空', 'error'); title.focus(); return; }
      const newDue = Dates.normalizeDue(due.value, new Date());
      for (const el of form.elements) el.disabled = true;
      const base = Object.assign({}, item, { rev: state.editRev });
      const ok = await save(base, { title: newTitle, due: newDue || undefined, reviewed: true }, '已保存');
      state.editing = null;
      if (state.model) renderTodos(state.model);
      if (ok) focusLater('title:' + item.id);
    });
    return h('div', { class: 'todo', role: 'listitem', dataset: { id: item.id } }, form);
  }
  function startEdit(item) {
    state.editing = item.id;
    state.editRev = item.rev;
    renderTodos(state.model);
    const input = document.querySelector(`.todo[data-id="${CSS.escape(item.id)}"] input[name="title"]`);
    if (input) { input.focus(); input.select(); }
  }
  function cancelEdit(id) {
    state.editing = null;
    render();
    focusLater('title:' + id);
  }
  function focusLater(key) {
    requestAnimationFrame(() => {
      const el = document.querySelector(`[data-focus="${CSS.escape(key)}"]`);
      if (el) el.focus();
    });
  }

  function section(key, title, rows, extraMarkClass) {
    return h('section', { class: 'sec', id: 'sec-' + key, 'aria-labelledby': 'sec-h-' + key },
      h('h3', { class: 'sec-title', id: 'sec-h-' + key },
        h('span', { class: 'mark mark-' + key + (extraMarkClass ? ' ' + extraMarkClass : ''), 'aria-hidden': 'true' }),
        title, h('span', { class: 'count' }, String(rows.length))),
      h('div', { role: 'list' }, rows.map(r => todoRow(r, false))));
  }
  function renderTodos(m) {
    const list = $('todo-list');
    list.setAttribute('aria-busy', String(!state.loaded));
    if (!state.loaded) { list.replaceChildren(...(state.loadError ? [loadFailed()] : skeleton(4))); return; }
    if (!m.rows.length) {
      list.replaceChildren(empty('还没有待办', '手机整理录音后，建议的待办会出现在这里；也可以在上方直接新建。'));
      return;
    }
    const parts = [];
    if (m.today.length) parts.push(section('today', '今日必办', m.today, m.overdue ? 'has-overdue' : ''));
    if (m.review.length) parts.push(section('review', '待核对（AI 建议）', m.review));
    if (m.other.length) parts.push(section('other', '其他未完成', m.other));
    if (!m.open.length) parts.push(empty('未完成的待办都处理完了', '新的待办会在同步后出现在这里。'));
    if (m.done.length) {
      const shown = m.done.slice(0, DONE_LIMIT);
      const details = h('details', { class: 'sec', id: 'sec-done', open: state.doneOpen },
        h('summary', null, '已完成', h('span', { class: 'count' }, String(m.done.length))),
        h('div', { role: 'list' }, shown.map(r => todoRow(r, false))),
        m.done.length > DONE_LIMIT ? h('p', { class: 'sec-note' }, `仅显示最近 ${DONE_LIMIT} 条`) : null);
      details.addEventListener('toggle', () => { state.doneOpen = details.open; });
      parts.push(details);
    }
    list.replaceChildren(...parts);
  }

  function haystack(r) {
    const hit = searchCache.get(r.id);
    if (hit && hit.rev === r.rev) return hit.text;
    const b = r.body;
    const text = [b.title, b.text, b.summary].filter(x => typeof x === 'string').join('\n').toLowerCase();
    searchCache.set(r.id, { rev: r.rev, text });
    return text;
  }
  function snippet(b) {
    if (typeof b.summary === 'string' && b.summary.trim()) return b.summary;
    if (b.status === 'error') return '整理失败' + (b.error ? '：' + b.error : '');
    if (b.status === 'silent') return '没有识别到有效语音。';
    if (typeof b.text === 'string' && b.text.trim()) return b.text;
    return b.status === 'ready' ? '暂无纪要。' : '等待手机端整理。';
  }
  function renderRecordings(m) {
    const counts = { all: m.recs.length, pending: 0, ready: 0, error: 0, silent: 0 };
    for (const r of m.recs) { const f = filterOf(r.body.status); if (f in counts) counts[f]++; }
    for (const btn of document.querySelectorAll('.filter')) {
      const f = btn.dataset.filter;
      btn.setAttribute('aria-pressed', String(f === state.filter));
      btn.querySelector('span').textContent = String(counts[f] || 0);
    }
    const list = $('rec-list');
    list.setAttribute('aria-busy', String(!state.loaded));
    if (!state.loaded) { list.replaceChildren(...(state.loadError ? [loadFailed()] : skeleton(3, true))); return; }
    if (!m.recs.length) {
      list.replaceChildren(empty('还没有同步的录音', '在手机端开启同步后，转写文字与纪要会出现在这里。音频始终留在手机上。'));
      return;
    }
    const q = state.query.trim().toLowerCase();
    const shown = m.recs.filter(r => (state.filter === 'all' || filterOf(r.body.status) === state.filter) && (!q || haystack(r).includes(q)));
    if (!shown.length) {
      list.replaceChildren(q
        ? empty('没有找到匹配的录音', `没有与“${state.query.trim()}”相关的标题、原文或纪要。`)
        : empty('这里暂时是空的', `没有“${FILTER_NAMES[state.filter]}”状态的录音。`));
      return;
    }
    list.replaceChildren(...shown.map(r => recCard(r, m)));
  }
  function recCard(r, m) {
    const b = r.body;
    const t = recTime(b);
    const linked = m.byRecording.get(r.id) || [];
    const openCount = linked.filter(x => x.item.body.done !== true).length;
    return h('button', {
      type: 'button', class: 'rec-card', 'aria-current': state.selected === r.id ? 'true' : null,
      dataset: { focus: 'rec:' + r.id }, onclick: () => openDetail(r.id, 'summary'),
    },
    h('span', { class: 'rec-top' },
      h('span', { class: 'rec-date' }, t ? fmtDateTime(new Date(t)) : '时间未知'),
      statusChip(b.status),
      b.ownerDevice ? h('span', { class: 'chip chip-device', title: '设备 ' + b.ownerDevice }, shortDevice(b.ownerDevice)) : null),
    h('span', { class: 'rec-title' }, b.title || '未命名录音'),
    h('span', { class: 'rec-summary' }, snippet(b).replace(/\s+/g, ' ')),
    linked.length ? h('span', { class: 'rec-foot' }, `${linked.length} 项待办` + (openCount ? ` · ${openCount} 项未完成` : ' · 均已完成')) : null);
  }

  function transcript(text) {
    const out = [];
    const tones = new Map();
    for (const line of text.split(/\r?\n/)) {
      const s = line.trim();
      if (!s) continue;
      const m = SPEAKER.exec(s);
      if (m) {
        const name = m[1].trim();
        if (!tones.has(name)) tones.set(name, tones.size % 4);
        out.push(h('div', { class: 'turn', dataset: { tone: String(tones.get(name)) } },
          h('span', { class: 'speaker' }, name), h('p', null, m[2])));
      } else {
        out.push(h('p', { class: 'para' }, s));
      }
    }
    return out;
  }
  function renderDetail(m, force) {
    const rec = state.selected ? m.recById.get(state.selected) : null;
    if (state.selected && !rec && state.loaded) {
      state.selected = null;
      closeSheet();
    }
    $('detail-empty').hidden = !!rec;
    $('detail-body').hidden = !rec;
    if (!rec) { state.detailKey = ''; return; }
    const linked = (m.byRecording.get(rec.id) || []).slice()
      .sort((a, b) => Number(a.item.body.done === true) - Number(b.item.body.done === true));
    const key = [rec.id, rec.rev, state.tab, new Date().toDateString(), ...linked.map(r => r.item.id + ':' + r.item.rev)].join('|');
    if (!force && key === state.detailKey) return;
    state.detailKey = key;
    const b = rec.body;
    const t = recTime(b);
    $('detail-meta').replaceChildren(
      h('span', { class: 'when' }, t ? fmtDateTime(new Date(t)) : '时间未知'),
      statusChip(b.status),
      b.ownerDevice ? h('span', { class: 'chip chip-device', title: '设备 ' + b.ownerDevice }, shortDevice(b.ownerDevice)) : null);
    $('detail-title').textContent = b.title || '未命名录音';
    $('tab-todo-count').textContent = String(linked.length);
    for (const tab of document.querySelectorAll('.tabs [role="tab"]')) {
      const on = tab.dataset.tab === state.tab;
      tab.setAttribute('aria-selected', String(on));
      tab.tabIndex = on ? 0 : -1;
      if (on) $('detail-panel').setAttribute('aria-labelledby', tab.id);
    }
    const panel = $('detail-panel');
    let content;
    if (state.tab === 'summary') {
      content = typeof b.summary === 'string' && b.summary.trim()
        ? [h('div', { class: 'prose' }, b.summary.trim())]
        : [b.status === 'error'
          ? empty('整理失败', b.error ? String(b.error) : '可以在手机上重新整理这条录音。')
          : b.status === 'silent'
            ? empty('没有纪要', '这条录音没有识别到有效语音。')
            : empty('还没有纪要', '手机端整理完成并同步后，纪要会显示在这里。')];
    } else if (state.tab === 'todos') {
      content = linked.length
        ? [h('div', { role: 'list' }, linked.map(r => todoRow(r, true)))]
        : [empty('没有关联的待办', '这条录音没有生成待办。')];
    } else if (state.tab === 'text') {
      const turns = typeof b.text === 'string' ? transcript(b.text) : [];
      content = turns.length ? turns : [empty('没有原文', '这条录音还没有转写文字。')];
    } else {
      content = typeof b.polished === 'string' && b.polished.trim()
        ? [h('div', { class: 'prose' }, b.polished.trim())]
        : [empty('无', '这条录音没有润色稿。')];
    }
    panel.replaceChildren(...content);
    if (force) panel.scrollTop = 0;
  }

  // ---------- detail open/close ----------
  function setInert(on) {
    for (const id of ['topbar', 'stats', 'switcher-wrap', 'todo-col', 'rec-col']) {
      const el = $(id);
      if (on) el.setAttribute('inert', ''); else el.removeAttribute('inert');
    }
  }
  function openSheet() {
    const d = $('detail');
    d.classList.add('open');
    d.setAttribute('role', 'dialog');
    d.setAttribute('aria-modal', 'true');
    $('backdrop').hidden = false;
    document.body.classList.add('sheet-open');
    setInert(true);
  }
  function closeSheet() {
    const d = $('detail');
    d.classList.remove('open');
    d.removeAttribute('role');
    d.removeAttribute('aria-modal');
    $('backdrop').hidden = true;
    document.body.classList.remove('sheet-open');
    setInert(false);
  }
  function openDetail(id, tab) {
    if (state.selected !== id) state.opener = document.activeElement;
    state.selected = id;
    state.tab = tab || 'summary';
    render();
    renderDetail(state.model, true);
    if (!wide.matches) openSheet();
    $('detail-title').focus({ preventScroll: true });
  }
  function closeDetail() {
    const id = state.selected;
    state.selected = null;
    closeSheet();
    render();
    const back = (id && document.querySelector(`[data-focus="rec:${CSS.escape(id)}"]`)) || state.opener;
    if (back && document.contains(back)) back.focus({ preventScroll: !wide.matches });
    state.opener = null;
  }
  function selectTab(tab, focus) {
    state.tab = tab;
    renderDetail(state.model, true);
    if (focus) $('tab-' + tab).focus();
  }

  function setView(view) {
    state.view = view;
    $('board').dataset.view = view;
    for (const btn of document.querySelectorAll('.switcher button')) btn.setAttribute('aria-pressed', String(btn.dataset.view === view));
  }
  function goTo(targetId) {
    setView(targetId === 'rec-col' ? 'recordings' : 'todos');
    const el = $(targetId) || $('todo-col');
    el.scrollIntoView({ behavior: scrollMode(), block: 'start' });
  }

  // ---------- events ----------
  $('connect-form').addEventListener('submit', e => {
    e.preventDefault();
    const token = $('token').value.trim();
    if (token.length < 24) { toast('密钥至少 24 个字符，请检查后重试', 'error'); return; }
    $('token').value = '';
    connect(token, $('remember').checked, false);
  });
  $('refresh').addEventListener('click', () => refresh({ manual: true }));
  $('disconnect').addEventListener('click', () => {
    disconnect('已断开，本页数据已清除');
    setStatus('idle', '未连接');
  });
  $('new-todo').addEventListener('submit', async e => {
    e.preventDefault();
    const title = $('new-title').value.trim();
    if (!title) return;
    const due = Dates.normalizeDue($('new-due').value, new Date());
    const id = 'todo-' + uuid();
    const body = { id, title, done: false, reviewed: true, createdAt: Date.now() };
    if (due) body.due = due;
    const submit = e.submitter || $('new-todo').querySelector('button');
    submit.disabled = true;
    const ok = await save({ id, kind: 'todo', rev: 0, body: {} }, body, '已添加待办');
    submit.disabled = false;
    if (ok) {
      $('new-title').value = '';
      $('new-due').value = '';
      $('new-title').focus();
    }
  });
  $('search').addEventListener('input', e => { state.query = e.target.value; render(); });
  $('search').addEventListener('keydown', e => {
    if (e.key === 'Escape' && e.target.value) { e.stopPropagation(); e.target.value = ''; state.query = ''; render(); }
  });
  for (const btn of document.querySelectorAll('.filter')) {
    btn.addEventListener('click', () => { state.filter = btn.dataset.filter; render(); });
  }
  for (const btn of document.querySelectorAll('.switcher button')) {
    btn.addEventListener('click', () => setView(btn.dataset.view));
  }
  for (const btn of document.querySelectorAll('.stat[data-goto]')) {
    btn.addEventListener('click', () => goTo(btn.dataset.goto));
  }
  $('stat-devices').addEventListener('click', () => toggleDevices());
  document.addEventListener('click', e => {
    const pop = $('device-pop');
    if (!pop.hidden && !pop.contains(e.target) && !$('stat-devices').contains(e.target)) toggleDevices(false);
  });
  $('detail-close').addEventListener('click', closeDetail);
  $('backdrop').addEventListener('click', closeDetail);
  $('copy-text').addEventListener('click', () => {
    const rec = state.model && state.model.recById.get(state.selected);
    copy(rec && typeof rec.body.text === 'string' ? rec.body.text.trim() : '', '原文');
  });
  $('copy-summary').addEventListener('click', () => {
    const rec = state.model && state.model.recById.get(state.selected);
    copy(rec && typeof rec.body.summary === 'string' ? rec.body.summary.trim() : '', '纪要');
  });
  const tabs = Array.from(document.querySelectorAll('.tabs [role="tab"]'));
  for (const tab of tabs) {
    tab.addEventListener('click', () => selectTab(tab.dataset.tab, false));
    tab.addEventListener('keydown', e => {
      const i = tabs.indexOf(tab);
      let next = null;
      if (e.key === 'ArrowRight') next = tabs[(i + 1) % tabs.length];
      else if (e.key === 'ArrowLeft') next = tabs[(i - 1 + tabs.length) % tabs.length];
      else if (e.key === 'Home') next = tabs[0];
      else if (e.key === 'End') next = tabs[tabs.length - 1];
      if (next) { e.preventDefault(); selectTab(next.dataset.tab, true); }
    });
  }
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape') {
      if (!$('device-pop').hidden) { toggleDevices(false); $('stat-devices').focus(); return; }
      if (state.selected) { e.preventDefault(); closeDetail(); }
      return;
    }
    if (e.key === '/' && !e.ctrlKey && !e.metaKey && !e.altKey && state.token) {
      const t = e.target;
      if (t && t.closest && t.closest('input, textarea, select, [contenteditable="true"]')) return;
      e.preventDefault();
      if (state.selected && !wide.matches) closeDetail();
      setView('recordings');
      $('search').focus();
    }
  });
  wide.addEventListener('change', () => {
    if (wide.matches) closeSheet();
    else if (state.selected) openSheet();
  });
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible' && state.token && (!state.lastSync || Date.now() - state.lastSync >= REFRESH_MS)) refresh();
  });
  window.addEventListener('online', () => { if (state.token) refresh(); });
  window.addEventListener('offline', () => { if (state.token) setStatus('error', '网络已断开'); });

  // ---------- start ----------
  const local = storage('localStorage'), session = storage('sessionStorage');
  const remembered = readKey(local, TOKEN_KEY);
  const saved = readKey(session, TOKEN_KEY) || remembered;
  $('remember').checked = !!remembered;
  if (saved) connect(saved, !!remembered && saved === remembered, true);
  else { showWorkspace(false); setStatus('idle', '未连接'); }
})();
