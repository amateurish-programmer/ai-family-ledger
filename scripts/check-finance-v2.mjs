// Synthetic fixtures; fetch is isolated from the network and no ledger or keys are read.
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { runInNewContext } from 'node:vm';
import assert from 'node:assert/strict';

let handler, outputs = [], requests = [], diagnostics = [], readTimeouts = [], quota = true;
let clockOffset = 0, providerElapsed = [];
class TestDate extends Date { static now() { return Date.now() + clockOffset; } }
const env = { SUPABASE_URL: 'https://project.example.test', SUPABASE_ANON_KEY: 'fixture-public',
  SUPABASE_SERVICE_ROLE_KEY: 'fixture-service', OPENAI_API_KEY: 'fixture-provider',
  OPENAI_MODEL: 'fixture-chat', OPENAI_BASE_URL: 'https://api.deepseek.com' };
const sandbox = {
  Deno: { serve: value => { handler = value; }, env: { get: key => env[key] } },
  console: { warn: value => diagnostics.push(JSON.parse(value)) }, Response, Request, TextDecoder, URL, Date: TestDate,
  AbortSignal: { timeout: timeout => ({ timeout }) },
  setTimeout: (callback, timeout) => { readTimeouts.push(timeout); return setTimeout(callback, timeout); }, clearTimeout,
  fetch: async (url, init) => {
    if (url.endsWith('/auth/v1/user')) return Response.json({ id: '00000000-0000-0000-0000-000000000001' });
    if (url.endsWith('/get_my_family')) return Response.json({ id: 'synthetic-family' });
    if (url.endsWith('/consume_ai_quota')) return Response.json(quota);
    requests.push({ ...JSON.parse(init.body), timeout: init.signal.timeout });
    clockOffset += providerElapsed.shift() ?? 0;
    const output = outputs.length > 1 ? outputs.shift() : outputs[0];
    if (output === '__HTTP_503__') return new Response('{}', { status: 503 });
    return Response.json({ choices: [{ finish_reason: 'stop', message: { content: typeof output === 'string' ? output : JSON.stringify(output) } }] });
  },
};
runInNewContext(stripTypeScriptTypes(readFileSync('supabase/functions/ledger-ai/index.ts', 'utf8'), { mode: 'transform' }), sandbox);
const finance = { scope: '本机已保存收支，查询组：本月与去年同月', facts: [
  { id: 'F0', label: '本月 · 收入', value: '¥ 10000.00' },
  { id: 'F1', label: '本月 · 支出', value: '¥ 8000.00' },
  { id: 'F2', label: '本月 · 笔数', value: '12 笔' },
], limitations: ['本月尚未结束，未必覆盖家庭所有收支。'] };
const legacy = { text: '分析收支', today: '2026-10-17', role: '老公', history: [], members: ['老婆'], categories: ['食品'], finance };
const v2 = { ...legacy, analysisV2: true, memory: ['用户曾表示：希望先建立应急储备。'] };
const analysis = { text: '分析收支', history: [], finance, analysisV2: true, memory: v2.memory };
const answer = { reply: '建议先核对必要开支。', entries: [], query: null, queries: [], factIds: ['F0', 'F1'] };
const query = { start: '2026-10-01', end: '2026-11-01', member: '老婆', category: '', keyword: '', label: '本月老婆收入', type: 'INCOME', giftOnly: false, counterparty: '' };
const invalid = { reply: '无效', entries: [], query: null, extra: true };
// Mirrors FinanceContext.buildQueries ordering: each complete group precedes
// the next group, and comparison facts appear only after all four groups.
const groupedFinance = { scope: '本机已保存人民币收支；4 组独立筛选，日期左闭右开；各组可能重叠，不得相加为家庭总额',
  facts: [], limitations: ['各组可能重叠；差额为后组减 Q1，不自动代表同比；尚未结束的期间不能直接比较。'] };
const groupedCore = [];
function groupedFact(label, value, required = false) {
  const fact = { id: `F${groupedFinance.facts.length}`, label, value };
  groupedFinance.facts.push(fact);
  if (required) groupedCore.push(fact);
  return fact;
}
groupedFact('统计范围', groupedFinance.scope);
for (const [group, month, income, expense, balance] of [
  ['Q1', '06', '1000.00', '200.00', '800.00'], ['Q2', '07', '1200.00', '300.00', '900.00'],
  ['Q3', '08', '900.00', '300.00', '600.00'], ['Q4', '10', '1100.00', '250.00', '850.00'],
]) {
  for (const [label, value] of [
    ['查询标签', `测试 ${month} 月收支`],
    ['筛选范围', `2026-${month}-01 至 2026-${month}-28；成员=全部；分类=全部；关键词=无；类型=全部收支；仅礼金=false；往来人=全部`],
    ['已记录日期', `2026-${month}-01 至 2026-${month}-17`],
    ['期间完整性', group === 'Q4' ? '期间尚未结束，不能直接与完整期间比较；不代表记录完整' : '已到达日历截止日期；仍可能缺记或未同步'],
    ['收支笔数', '8 笔'], ['收入', `¥ ${income}`], ['支出', `¥ ${expense}`], ['结余', `¥ ${balance}`],
  ]) groupedFact(`${group} · ${label}`, value, true);
  for (const kind of ['收入分类', '成员收入', '商家收入', '支出分类', '成员支出', '商家支出']) {
    groupedFact(`${group} · ${kind} 测试项甲`, '¥ 100.00');
    groupedFact(`${group} · ${kind} 测试项乙`, '¥ 50.00');
  }
  groupedFact(`${group} · 礼金收入`, '¥ 0.00'); groupedFact(`${group} · 礼金支出`, '¥ 0.00');
  groupedFact(`${group} · 明细范围`, '仅展示最近 1 笔，共 8 笔；不是完整流水');
  groupedFact(`${group} · 明细 1`, `2026-${month}-17；测试成员；支出 ¥ 50.00；食品；商家=测试商家`);
}
for (const [group, amounts, rates] of [
  ['Q2', ['200.00', '100.00', '100.00'], ['20.00%', '50.00%', '12.50%']],
  ['Q3', ['-100.00', '100.00', '-200.00'], ['-10.00%', '50.00%', '-25.00%']],
  ['Q4', ['100.00', '50.00', '50.00'], ['10.00%', '25.00%', '6.25%']],
]) ['收入', '支出', '结余'].forEach((kind, index) => {
  groupedFact(`${group} − Q1 · ${kind}差额`, `¥ ${amounts[index]}`, true);
  groupedFact(`${group} 相对 Q1 · ${kind}变化率`, rates[index], true);
});
async function call(operation, input, ...generated) {
  outputs = generated; requests = []; diagnostics = []; readTimeouts = []; clockOffset = 0;
  const response = await handler(new Request('https://project.example.test/functions/v1/ledger-ai', {
    method: 'POST', headers: { Authorization: 'Bearer fixture-token-123456789012345', 'Content-Type': 'application/json' },
    body: JSON.stringify({ operation, input }),
  }));
  return { status: response.status, body: await response.json() };
}
let passed = 0, failed = 0;
async function check(name, action) {
  try { await action(); passed++; }
  catch (error) { failed++; console.error(`FAIL ${name}: ${error.message}`); }
}

// Broad regex matching used to steal a third person's income and erase gift semantics.
for (const text of ['今天老婆收入5450元', '今天爸爸收入100元', '今天红包收入100元', '今天收到小王礼金收入100元',
  '今天没有收入100元', '今天还未收入100元', '今天不收入100元', '今天预期收入100元', '今天假设收入100元',
  '今天将会收入100元', '今天明天收入100元', '今天可能收入100元']) {
  await check(`ambiguous fallback clarifies: ${text}`, async () => {
    const result = await call('chat', { ...legacy, text, giftFields: true }, invalid, invalid);
    assert.equal(result.status, 200);
    assert.deepEqual(JSON.parse(result.body.result).entries, []);
    assert.match(JSON.parse(result.body.result).reply, /确认|补充|明确/);
  });
}
await check('clear self income fallback retains member and gift fields', async () => {
  const result = await call('chat', { ...v2, text: '今天我意外收入1034元', giftFields: true }, invalid, invalid);
  const output = JSON.parse(result.body.result);
  assert.equal(result.status, 200); assert.equal(output.entries[0].member, '老公');
  assert.equal(output.entries[0].recordedBy, '老公'); assert.equal(output.entries[0].amount, '1034');
  assert.equal(output.entries[0].isGift, false); assert.deepEqual(output.queries, []);
});
await check('new chat renders original evidence labels and strips factIds', async () => {
  const result = await call('chat', v2, answer);
  assert.equal(result.status, 200);
  const output = JSON.parse(result.body.result);
  assert.deepEqual(Object.keys(output).sort(), ['entries', 'queries', 'query', 'reply']);
  assert.match(output.reply, /本月 · 收入：¥ 10000.00/);
  assert.match(output.reply, /本月 · 支出：¥ 8000.00/);
  assert.equal(requests.length, 1); assert.equal(requests[0].thinking.type, 'disabled');
  assert.deepEqual(JSON.parse(requests[0].messages[1].content).memory, v2.memory);
});
await check('new chat accepts four separate bounded query plans', async () => {
  const queries = [query, { ...query, start: '2025-10-01', end: '2025-11-01', label: '去年同月' },
    { ...query, type: 'EXPENSE', giftOnly: true, counterparty: '小王', label: '礼金支出' }, { ...query, type: '', label: '全部收支' }];
  const result = await call('chat', v2, { ...answer, queries, factIds: [] });
  assert.equal(result.status, 200); assert.deepEqual(JSON.parse(result.body.result).queries, queries);
});
for (const output of [
  { ...answer, queries: Array(5).fill(query) },
  { ...answer, queries: [{ ...query, type: 'TRANSFER' }] },
  { ...answer, queries: [{ ...query, giftOnly: 'true' }] },
  { ...answer, queries: [{ ...query, end: query.start }] },
  { ...answer, query },
]) await check('invalid new query contract is repaired or safely rejected', async () => {
  const result = await call('chat', v2, output, answer);
  assert.equal(result.status, 200); assert.equal(requests.length, 2);
  assert.deepEqual(JSON.parse(result.body.result).queries, []);
});
for (const text of ['支出 {{F0}}。', '可每月储蓄三千元。', '共十二笔。', '预算为 3000。', '收益百分之十。', '收入一万元。', '支出为八千块。']) {
  await check(`unsupported financial text triggers bounded repair: ${text}`, async () => {
    const result = await call('analyze', analysis, { reply: text, factIds: ['F0'] }, { reply: '请先核对已有记录。', factIds: ['F1'] });
    assert.equal(result.status, 200); assert.equal(requests.length, 2);
    assert.ok(!result.body.result.includes(text)); assert.match(result.body.result, /本月 · 支出：¥ 8000.00/);
    assert.ok(diagnostics.some(event => event.event === 'ai_output_repair' && event.reason === 'financial'));
  });
}
await check('legacy income misused as expense cannot pass placeholder validation', async () => {
  const result = await call('analyze', { text: legacy.text, history: [], finance }, { reply: '支出 {{F0}}。' }, { reply: '收入 {{F0}}。' });
  assert.equal(result.status, 200); assert.equal(requests.length, 2);
  assert.match(result.body.result, /收入 ¥ 10000.00/); assert.ok(!result.body.result.includes('支出 ¥ 10000.00'));
});
await check('legacy financial narrative retains only dates present in source facts', async () => {
  const input = { text: legacy.text, history: [], finance: { ...finance, facts: [{ ...finance.facts[0], label: '2026 年 10 月 · 收入' }] } };
  let result = await call('analyze', input, { reply: '2026年10月收入 {{F0}}。' });
  assert.equal(result.status, 200); assert.equal(requests.length, 1); assert.match(result.body.result, /2026年10月收入/);
  result = await call('analyze', input, { reply: '2025年10月收入 {{F0}}。' }, { reply: '收入 {{F0}}。' });
  assert.equal(result.status, 200); assert.equal(requests.length, 2); assert.ok(!result.body.result.includes('2025'));
});
await check('new analysis repairs invalid JSON once', async () => {
  const result = await call('analyze', analysis, 'not json', { reply: '建议先梳理固定开支。', factIds: ['F1'] });
  assert.equal(result.status, 200); assert.equal(requests.length, 2); assert.match(result.body.result, /本月 · 支出/);
});
for (const factIds of [['F999'], ['F0', 'F0'], Array(17).fill('F0')]) await check('invalid evidence selection falls back with original scope', async () => {
  const result = await call('analyze', analysis, { reply: '建议核对。', factIds });
  assert.equal(result.status, 200); assert.equal(requests.length, 2);
  assert.match(result.body.result, /程序统计/); assert.match(result.body.result, /本月 · 收入：¥ 10000.00/);
  assert.match(result.body.result, /本月尚未结束/);
});
await check('chat financial validation repairs instead of silently swallowing', async () => {
  const result = await call('chat', v2, { ...answer, reply: '支出 {{F0}}。' }, answer);
  assert.equal(result.status, 200); assert.equal(requests.length, 2);
  assert.ok(diagnostics.some(event => event.reason === 'financial'));
});
await check('financial error during repair still returns original statistics', async () => {
  const result = await call('chat', v2, 'invalid json', { ...answer, reply: '收入一万元。' });
  assert.equal(result.status, 200); assert.equal(requests.length, 2);
  assert.match(JSON.parse(result.body.result).reply, /本月 · 收入：¥ 10000.00/);
});
await check('long safe fallback never truncates original evidence inside a line', async () => {
  const facts = Array.from({ length: 8 }, (_, index) => ({ id: `F${index}`, label: '项目'.repeat(50), value: `¥ ${'1'.repeat(496)}` }));
  const limitations = Array(8).fill('数据范围限制'.repeat(83));
  const result = await call('analyze', { ...analysis, finance: { ...finance, facts, limitations } }, 'invalid json');
  assert.equal(result.status, 200); assert.ok(result.body.result.length <= 6000);
  const knownLines = new Set([`${facts[0].label}：${facts[0].value}`, ...limitations]);
  for (const line of result.body.result.split('\n')) {
    if (line.startsWith('项目') || line.startsWith('数据范围限制')) assert.ok(knownLines.has(line), 'source value must not be cut');
  }
});
for (const operation of ['analyze', 'chat']) await check(`${operation} fallback preserves all four query summaries and comparisons`, async () => {
  const input = { ...(operation === 'analyze' ? analysis : v2), finance: groupedFinance };
  const output = operation === 'analyze' ? 'invalid json' : { ...answer, reply: '收入三千元。' };
  const result = await call(operation, input, output);
  assert.equal(result.status, 200); assert.equal(requests.length, 2);
  const text = operation === 'analyze' ? result.body.result : JSON.parse(result.body.result).reply;
  assert.ok(text.length <= 6000);
  for (const fact of groupedCore) assert.ok(text.includes(`${fact.label}：${fact.value}`), `missing ${fact.label}`);
  assert.ok(text.includes(groupedFinance.limitations[0]));
});
await check('evidence automatically includes the scope of each selected query and both comparison groups', async () => {
  const factIds = ['Q2 · 收入', 'Q4 − Q1 · 支出差额'].map(label => groupedFinance.facts.find(fact => fact.label === label).id);
  const result = await call('analyze', { ...analysis, finance: groupedFinance }, { reply: '建议结合期间范围核对收支变化。', factIds });
  assert.equal(result.status, 200); assert.equal(requests.length, 1);
  for (const group of ['Q1', 'Q2', 'Q4']) for (const field of ['查询标签', '筛选范围', '已记录日期', '期间完整性']) {
    const fact = groupedFinance.facts.find(fact => fact.label === `${group} · ${field}`);
    assert.ok(result.body.result.includes(`${fact.label}：${fact.value}`), `missing ${fact.label}`);
  }
  assert.ok(!result.body.result.includes('Q3 · 筛选范围'));
  assert.ok(result.body.result.length <= 6000);
});
await check('oversized required query summaries fail explicitly instead of silently losing later groups', async () => {
  const facts = groupedCore.map((fact, index) => ({ ...fact, id: `F${index}`, value: 'x'.repeat(500) }));
  const result = await call('analyze', { ...analysis, finance: { ...groupedFinance, facts } }, 'invalid json');
  assert.equal(result.status, 502); assert.equal('result' in result.body, false);
});
await check('long query filters and limitations cannot silently exclude the last query', async () => {
  const facts = groupedFinance.facts.map(fact => fact.label.endsWith('筛选范围') ? { ...fact, value: 'x'.repeat(500) } : fact);
  const limitations = Array(8).fill('x'.repeat(500));
  const result = await call('analyze', { ...analysis, finance: { ...groupedFinance, facts, limitations } }, 'invalid json');
  assert.equal(result.status, 502); assert.equal('result' in result.body, false);
});
for (const extra of [{ analysisV2: false }, { memory: Array(9).fill('用户陈述') }, { memory: ['字'.repeat(501)] }, { history: Array(9).fill({ role: 'user', text: '问题' }) }]) {
  await check('invalid capability/history/memory stops before provider', async () => {
    const result = await call('chat', { ...v2, ...extra }, answer);
    assert.equal(result.status, 400); assert.equal(requests.length, 0);
  });
}
await check('memory requires v2 capability', async () => {
  const result = await call('chat', { ...legacy, memory: ['用户陈述'] }, answer);
  assert.equal(result.status, 400); assert.equal(requests.length, 0);
});
await check('new capability without memory and exact limits work', async () => {
  const result = await call('analyze', { ...analysis, memory: Array(8).fill('字'.repeat(500)), history: Array(8).fill({ role: 'user', text: '字'.repeat(1500) }) }, { reply: '建议核对账目。', factIds: [] });
  assert.equal(result.status, 200);
  const noMemory = { ...v2 }; delete noMemory.memory;
  assert.equal((await call('chat', noMemory, answer)).status, 200);
});
await check('analysis uses optional model, thinking and bounded budget', async () => {
  env.OPENAI_ANALYSIS_MODEL = 'fixture-analysis'; env.OPENAI_ANALYSIS_TIMEOUT_MS = '90000';
  const result = await call('analyze', analysis, { reply: '建议核对账目。', factIds: ['F0'] });
  assert.equal(result.status, 200); assert.equal(requests[0].model, 'fixture-analysis');
  assert.equal(requests[0].thinking.type, 'enabled'); assert.ok(requests[0].max_tokens >= 8000);
  assert.ok(requests[0].timeout <= 45000 && requests[0].timeout > 25000);
  assert.ok(Math.max(...readTimeouts) > 25000 && Math.max(...readTimeouts) <= 45000, 'response body uses the remaining analysis budget');
  env.OPENAI_ANALYSIS_THINKING = 'false'; env.OPENAI_ANALYSIS_MAX_TOKENS = '4096';
  await call('analyze', analysis, { reply: '建议核对账目。', factIds: [] });
  assert.equal(requests[0].thinking.type, 'disabled'); assert.equal(requests[0].max_tokens, 4096);
  delete env.OPENAI_ANALYSIS_MODEL; delete env.OPENAI_ANALYSIS_THINKING;
  delete env.OPENAI_ANALYSIS_MAX_TOKENS; delete env.OPENAI_ANALYSIS_TIMEOUT_MS;
});
await check('analysis repair gets only the remaining shared time budget', async () => {
  providerElapsed = [30000];
  const result = await call('analyze', analysis, 'invalid json', { reply: '建议核对账目。', factIds: [] });
  assert.equal(result.status, 200); assert.equal(requests.length, 2);
  assert.ok(requests[0].timeout <= 45000 && requests[1].timeout <= 15000);
});
await check('report enables analysis settings without any new client fields', async () => {
  env.OPENAI_ANALYSIS_MODEL = 'fixture-report';
  const input = { period: '2026 年', income: '100.00', expense: '40.00', balance: '60.00', categories: [], members: [], trend: [] };
  const result = await call('report', input, { report: '建议持续记录真实收支。' });
  delete env.OPENAI_ANALYSIS_MODEL;
  assert.equal(result.status, 200); assert.equal(requests[0].model, 'fixture-report');
  assert.equal(requests[0].thinking.type, 'enabled'); assert.ok(requests[0].timeout <= 45000);
});
await check('legacy and non-DeepSeek providers preserve request compatibility', async () => {
  env.OPENAI_BASE_URL = 'https://provider.example.test';
  let result = await call('chat', legacy, { reply: '建议核对账目。', entries: [], query: null });
  assert.equal(result.status, 200); assert.equal('queries' in JSON.parse(result.body.result), false);
  assert.equal('thinking' in requests[0], false); assert.equal(requests[0].model, 'fixture-chat');
  assert.ok(requests[0].timeout <= 25000);
  result = await call('analyze', analysis, { reply: '建议核对账目。', factIds: [] });
  assert.equal(result.status, 200); assert.equal('thinking' in requests[0], false);
  env.OPENAI_BASE_URL = 'https://api.deepseek.com';
});
await check('provider transport failure is not retried as format failure', async () => {
  const result = await call('analyze', analysis, '__HTTP_503__');
  assert.equal(result.status, 502); assert.equal(requests.length, 1);
});
await check('quota denial prevents any model call', async () => {
  quota = false;
  const result = await call('chat', v2, answer);
  quota = true;
  assert.equal(result.status, 429); assert.equal(requests.length, 0);
});
console.log(`Finance V2 regression checks: ${passed} passed, ${failed} failed. No live requests.`);
if (failed) process.exitCode = 1;
