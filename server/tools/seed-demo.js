#!/usr/bin/env node
'use strict';
// Fills a data directory with Chinese demo content for trying out the board.
// Usage: node server/tools/seed-demo.js [dataDir] [--force]
//   dataDir defaults to RH_DATA_DIR (or server/data). Refuses a non-empty workspace unless --force.
//   Set RH_DATA_KEY_FILE as well if the hub runs with at-rest encryption.
const path = require('node:path');
const { resolveDataDir } = require('../lib/config');

const args = process.argv.slice(2);
if (args.includes('--help') || args.includes('-h')) {
  console.log('Usage: node server/tools/seed-demo.js [dataDir] [--force]');
  process.exit(0);
}
const force = args.includes('--force');
const dirArg = args.find(a => !a.startsWith('--'));
const dataDir = dirArg ? path.resolve(dirArg) : resolveDataDir(process.env.RH_DATA_DIR);
if (process.env.RH_DATA_KEY_FILE) require('../lib/crypt').init(process.env.RH_DATA_KEY_FILE);
const { SyncStore } = require('../lib/sync-store');

const PHONE = 'phone-7a3f';
const TABLET = 'tablet-2b81';
const MIN = 60_000, HOUR = 60 * MIN, DAY = 24 * HOUR;

const now = new Date();
const pad = n => String(n).padStart(2, '0');
const shift = days => new Date(now.getFullYear(), now.getMonth(), now.getDate() + days);
const ymd = d => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const slash = d => `${d.getFullYear()}/${d.getMonth() + 1}/${d.getDate()}`;
const md = d => `${d.getMonth() + 1}月${d.getDate()}日`;
// A past moment: N days ago at the given clock time, never later than now.
function past(days, hour, minute) {
  const d = shift(-days);
  d.setHours(hour, minute, 0, 0);
  return Math.min(d.getTime(), now.getTime() - 20 * MIN);
}

const recordings = [
  {
    id: 'rec-demo-weekly-sync', device: PHONE, at: past(0, 9, 30), status: 'ready',
    title: '周一产品例会：2.1 版本排期',
    text: [
      '张三：今天主要对一下 2.1 版本的排期。先说结论，登录页改版和离线同步这两块必须进 2.1。',
      '李四：离线同步的后端接口已经联调完了，客户端还差冲突提示的界面，估计还要三天。',
      '张三：那上线日期我们今天之内必须定下来，市场那边要排推广。',
      '王五：测试这边至少需要两天回归。登录页改版的验收用例我还没拿到。',
      '李四：用例我可以先出一版初稿，明天给你。',
      '张三：好，用例明天给到王五。另外新版图标的尺寸，设计那边要和我们再确认一次。',
      '王五：上次安卓小屏上图标被裁掉了，这次一定要在 360 宽度上验一下。',
      '张三：记下来。今天就先这样，上线日期定了我发群里。',
    ].join('\n'),
    summary: [
      '2.1 版本范围确定：登录页改版、离线同步。',
      '· 离线同步后端已联调完成，客户端冲突提示界面预计还需 3 天。',
      '· 测试至少需要 2 天回归；登录页验收用例明天出初稿。',
      '· 上线日期今天内确定，并同步给市场。',
      '· 新版图标需要在 360 宽度的小屏上验证，避免被裁切。',
    ].join('\n'),
    polished: '本次例会确认 2.1 版本包含登录页改版与离线同步两项内容。离线同步的后端接口已完成联调，客户端仍需约三天完成冲突提示界面。测试团队需要至少两天进行回归，登录页改版的验收用例将于明天提供初稿。上线日期须在今天之内确定，以便市场团队安排推广。此外，新版图标尺寸需与设计团队再次确认，并在 360 宽度的小屏设备上验证显示效果。',
  },
  {
    id: 'rec-demo-client-visit', device: PHONE, at: past(1, 15, 10), status: 'ready',
    title: '客户回访：华东区经销商续约',
    text: [
      '我：陈经理您好，今天主要想跟您聊一下明年的续约。',
      '陈经理：今年整体合作还是挺顺的，就是三季度有两次到货晚了，下游有意见。',
      '我：这个情况我们内部复盘过了，华东仓的盘点和补货节奏会调整，盘点时间我这周跟您确认。',
      '陈经理：好。续约价格方面，你们能不能先出个报价单？我们这边要走流程。',
      '我：好的，报价单我今天下班前发给您。',
      '陈经理：另外我们下面有三家二级经销商，库存压得比较多，你们也可以去了解一下。',
      '我：明白，下周三前我挨个回访一下。',
    ].join('\n'),
    summary: [
      '客户整体满意，主要问题是三季度两次到货延迟。',
      '· 续约：客户需要报价单走内部流程，已承诺当天发出。',
      '· 华东仓盘点与补货节奏调整，本周内确认盘点时间。',
      '· 下周三前回访客户下属三家二级经销商的库存情况。',
    ].join('\n'),
    polished: '',
  },
  {
    id: 'rec-demo-kitchen-plan', device: TABLET, at: past(2, 19, 40), status: 'ready',
    title: '装修方案沟通（厨房与阳台）',
    text: [
      '设计师小周：厨房台面有两个选择，石英石性价比高，岩板更耐高温，但是贵三千左右。',
      '我：我们做饭比较多，岩板会不会更合适？',
      '设计师小周：都可以，关键看预算。您这两天定下来就行，橱柜要排产。',
      '家人：阳台地砖我想换成防滑的，周末一起去建材市场看看吧。',
      '设计师小周：可以。另外插座位置图麻烦发我一份，我好出水电图。',
      '我：行，插座位置我画好了，晚上发你。',
    ].join('\n'),
    summary: [
      '厨房台面在石英石与岩板之间选择，岩板贵约三千元，需尽快确认以便橱柜排产。',
      '· 阳台地砖改为防滑款，周末去建材市场挑选。',
      '· 插座位置图发给设计师，用于出水电图。',
    ].join('\n'),
    polished: '',
  },
  {
    id: 'rec-demo-budget-review', device: PHONE, at: past(4, 14, 0), status: 'error',
    title: '季度预算初审',
    text: [
      '财务小刘：这一季度的预算初审，市场费用超了百分之十二，主要是线下活动。',
      '我：线下活动的效果数据有没有？',
      '财务小刘：有一部分，我整理一下再发你。',
      '我：好的，其他科目基本没问题的话，就先按这个版本报上去。',
    ].join('\n'),
    summary: '',
    polished: '',
    error: '整理超时，请在手机上重试',
  },
  {
    id: 'rec-demo-reading-club', device: TABLET, at: past(5, 20, 15), status: 'pending',
    title: '读书会：《置身事内》第三章',
    text: [
      '主持人：今天读《置身事内》第三章，讲的是地方政府的财政和债务。',
      '小林：我印象最深的是土地财政那部分，作者把来龙去脉讲得很清楚。',
      '主持人：大家可以想一想，这和我们平时看到的城市建设有什么关系。',
      '小林：下次能不能每人带一个自己城市的例子？',
    ].join('\n'),
    summary: '',
    polished: '',
  },
  {
    id: 'rec-demo-subway-memo', device: PHONE, at: past(1, 8, 20), status: 'silent',
    title: '地铁上的语音备忘',
    text: '',
    summary: '',
    polished: '',
  },
];

const later = (recId, minutes) => recordings.find(r => r.id === recId).at + minutes * MIN;
const todos = [
  { id: 'todo-demo-release-date', device: PHONE, recordingId: 'rec-demo-weekly-sync', createdAt: later('rec-demo-weekly-sync', 12),
    title: '确定 2.1 版本上线日期并同步给市场', due: ymd(shift(0)), reviewed: false, done: false,
    evidence: '张三：那上线日期我们今天之内必须定下来，市场那边要排推广。' },
  { id: 'todo-demo-test-cases', device: PHONE, recordingId: 'rec-demo-weekly-sync', createdAt: later('rec-demo-weekly-sync', 12),
    title: '整理登录页改版的验收用例初稿', due: '明天', reviewed: false, done: false,
    evidence: '李四：用例我可以先出一版初稿，明天给你。' },
  { id: 'todo-demo-icon-size', device: PHONE, recordingId: 'rec-demo-weekly-sync', createdAt: later('rec-demo-weekly-sync', 12),
    title: '和设计确认新版图标尺寸，并在 360 宽度小屏上验证', reviewed: true, done: false,
    evidence: '王五：上次安卓小屏上图标被裁掉了，这次一定要在 360 宽度上验一下。' },
  { id: 'todo-demo-quotation', device: PHONE, recordingId: 'rec-demo-client-visit', createdAt: later('rec-demo-client-visit', 15),
    title: '给陈经理发送续约报价单', due: '今天', reviewed: false, done: false,
    evidence: '我：好的，报价单我今天下班前发给您。' },
  { id: 'todo-demo-stocktake', device: PHONE, recordingId: 'rec-demo-client-visit', createdAt: later('rec-demo-client-visit', 15),
    title: '和陈经理确认华东仓盘点时间', due: md(shift(3)), reviewed: true, done: false,
    evidence: '我：这个情况我们内部复盘过了，华东仓的盘点和补货节奏会调整，盘点时间我这周跟您确认。' },
  { id: 'todo-demo-dealer-visits', device: PHONE, recordingId: 'rec-demo-client-visit', createdAt: later('rec-demo-client-visit', 15),
    title: '回访三家二级经销商的库存情况', due: '下周三前', reviewed: false, done: false,
    evidence: '陈经理：另外我们下面有三家二级经销商，库存压得比较多，你们也可以去了解一下。' },
  { id: 'todo-demo-countertop', device: TABLET, recordingId: 'rec-demo-kitchen-plan', createdAt: later('rec-demo-kitchen-plan', 8),
    title: '确认橱柜台面选石英石还是岩板', due: md(shift(0)), reviewed: true, done: false,
    evidence: '设计师小周：都可以，关键看预算。您这两天定下来就行，橱柜要排产。' },
  { id: 'todo-demo-floor-tiles', device: TABLET, recordingId: 'rec-demo-kitchen-plan', createdAt: later('rec-demo-kitchen-plan', 8),
    title: '周末去建材市场挑阳台防滑地砖', due: ymd(shift(4)), reviewed: false, done: false,
    evidence: '家人：阳台地砖我想换成防滑的，周末一起去建材市场看看吧。' },
  { id: 'todo-demo-socket-plan', device: TABLET, recordingId: 'rec-demo-kitchen-plan', createdAt: later('rec-demo-kitchen-plan', 8),
    title: '把插座位置图发给设计师', reviewed: true, done: false,
    evidence: '我：行，插座位置我画好了，晚上发你。' },
  { id: 'todo-demo-passport', device: TABLET, createdAt: past(6, 21, 0),
    title: '续办护照（材料：照片、旧护照）', due: slash(shift(-3)), reviewed: true, done: false },
  { id: 'todo-demo-checkup', device: PHONE, createdAt: past(6, 12, 30),
    title: '预约年度体检', reviewed: true, done: true },
  { id: 'todo-demo-medicine', device: PHONE, createdAt: past(3, 18, 5),
    title: '给妈妈买降压药', due: ymd(shift(-1)), reviewed: true, done: true },
];

function recordingBody(r) {
  const body = {
    id: r.id, title: r.title, text: r.text, summary: r.summary, polished: r.polished,
    occurredAt: new Date(r.at).toISOString(), status: r.status, error: r.error || '',
    createdAt: r.at + 2 * MIN, ownerDevice: r.device,
  };
  return body;
}
function todoBody(t) {
  const body = { id: t.id, title: t.title, done: t.done, reviewed: t.reviewed, createdAt: t.createdAt };
  if (t.due) body.due = t.due;
  if (t.evidence) body.evidence = t.evidence;
  if (t.recordingId) body.recordingId = t.recordingId;
  return body;
}

const store = new SyncStore(dataDir);
try {
  if (store.lastRev() > 0 && !force) {
    console.error(`Refusing to seed: ${dataDir} already contains data. Pass --force to add demo items anyway.`);
    process.exitCode = 1;
  } else {
    let version = 1;
    const change = (kind, body) => ({ id: body.id, kind, baseRev: 0, localVersion: version++, body });
    for (const device of [PHONE, TABLET]) {
      const changes = [
        ...recordings.filter(r => r.device === device).map(r => change('recording', recordingBody(r))),
        ...todos.filter(t => t.device === device).map(t => change('todo', todoBody(t))),
      ];
      const res = store.push({ device, changes });
      const conflicts = res.results.filter(r => r.conflict).length;
      if (conflicts) console.log(`${device}: ${conflicts} item(s) already existed and were left unchanged`);
    }
    // Cross-device edits: the phone finishes a tablet todo, the tablet confirms a phone suggestion.
    const edit = (device, id, patch) => {
      const item = store.item(id);
      return store.push({ device, changes: [{ id, kind: item.kind, baseRev: item.rev, localVersion: version++, body: { ...item.body, ...patch } }] });
    };
    edit(PHONE, 'todo-demo-socket-plan', { done: true });
    edit(TABLET, 'todo-demo-icon-size', { reviewed: true });
    const s = store.stats();
    console.log(`Seeded ${dataDir}`);
    console.log(`recordings=${s.recordings} todos=${s.todos} openTodos=${s.openTodos} reviewedPending=${s.reviewedPending} devices=${s.devices.length} lastRev=${s.lastRev}`);
  }
} finally {
  store.close();
}
