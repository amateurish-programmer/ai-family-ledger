// Synthetic protocol checks, never contact a provider or use a real family ledger.
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { runInNewContext } from 'node:vm';
import assert from 'node:assert/strict';
let handler, generated, generatedQueue, sent;
let quota = true;
let providerCalls = 0;
const finance = { scope: '本机全部人民币收支', facts: [
  { id: 'F0', label: '选定范围合计 · 收入', value: '¥ 10000.00' },
  { id: 'F1', label: '选定范围合计 · 支出', value: '¥ 8000.00' },
  { id: 'F2', label: '选定范围合计 · 结余', value: '¥ 2000.00' },
], limitations: ['本机记录不等于完整家庭资产。'] };
const input = { text: '分析家庭财务，怎样安排储蓄？', today: '2026-09-06', role: '老公', history: [], members: ['老婆'], categories: ['食品'], finance };
const sandbox = {
  Deno: { serve: h => { handler = h; }, env: { get: name => ({
    SUPABASE_URL: 'https://project.example.test', SUPABASE_ANON_KEY: 'fixture-public', SUPABASE_SERVICE_ROLE_KEY: 'fixture-service',
    OPENAI_API_KEY: 'fixture-provider', OPENAI_MODEL: 'fixture', OPENAI_BASE_URL: 'https://provider.example.test',
  })[name] } },
  console: { warn: () => {} }, Response, Request, TextDecoder, URL, AbortSignal, setTimeout, clearTimeout,
  fetch: async (url, init) => {
    if (url.endsWith('/auth/v1/user')) return Response.json({ id: '00000000-0000-0000-0000-000000000001' });
    if (url.endsWith('/get_my_family')) return Response.json({ id: 'fixture-family' });
    if (url.endsWith('/consume_ai_quota')) return Response.json(quota);
    providerCalls++; sent = JSON.parse(init.body);
    const content = generatedQueue?.length ? generatedQueue.shift() : generated;
    if (content === '__HTTP_503__') return new Response('{"error":"fixture"}', { status: 503 });
    return Response.json({ choices: [{ finish_reason: 'stop', message: { content } }] });
  },
};
runInNewContext(stripTypeScriptTypes(readFileSync('supabase/functions/ledger-ai/index.ts', 'utf8'), { mode: 'transform' }), sandbox);
async function call(operation, payload, output) {
  const outputs = Array.isArray(output) ? output : [output];
  generatedQueue = outputs.map(item => typeof item === 'string' ? item : JSON.stringify(item));
  generated = generatedQueue[0];
  const response = await handler(new Request('https://project.example.test/functions/v1/ledger-ai', {
    method: 'POST', headers: { Authorization: 'Bearer fixture-token-123456789012345', 'Content-Type': 'application/json' },
    body: JSON.stringify({ operation, input: payload }),
  }));
  return { status: response.status, body: await response.json() };
}
let checks = 0;
let result = await call('chat', input, { reply: '收入 {{F0}}，支出 {{F1}}。建议结合必要开支建立储蓄安排；目前应急资金有多少？', entries: [], query: null });
assert.equal(result.status, 200);
assert.ok(JSON.parse(result.body.result).reply.includes('收入 ¥ 10000.00'));
assert.equal(providerCalls, 1);
assert.equal(JSON.parse(sent.messages[1].content).finance.facts[1].value, '¥ 8000.00');
assert.ok(sent.messages[0].content.includes('财务问答优先直接分析')); checks++;
for (const reply of ['支出 {{F999}}。', '建议每月存 3000 元。', '收益达到 10%。', '收入 {{F0}}'] ) {
  const result = await call('analyze', { text: input.text, history: [], finance }, { reply });
  assert.equal(result.status, 200);
  assert.equal(result.body.result.includes('未通过数据引用校验'), reply !== '收入 {{F0}}'); checks++;
}
result = await call('chat', { ...input, history: [{ role: 'assistant', text: '应先梳理固定开支。' }, { role: 'user', text: '然后呢？' }] },
  { reply: '可以进一步区分必要支出与可调整支出。', entries: [], query: null });
assert.equal(result.status, 200);
assert.equal(JSON.parse(sent.messages[1].content).history[0].text, '应先梳理固定开支。'); checks++;
const legacy = { ...input }; delete legacy.finance;
result = await call('chat', legacy, { reply: '查询', entries: [], query: { start: '2026-09-01', end: '2026-10-01', member: '', category: '', keyword: '' } });
assert.equal(result.status, 200); assert.ok(JSON.parse(result.body.result).query); checks++;
const before = providerCalls;
result = await call('analyze', { text: 'x', history: [], finance: { ...finance, facts: [finance.facts[0], finance.facts[0]] } }, { reply: '无效' });
assert.equal(result.status, 400); assert.equal(providerCalls, before); checks++;
quota = false;
result = await call('chat', input, { reply: '不应调用', entries: [], query: null });
assert.equal(result.status, 429); assert.equal(providerCalls, before); checks++;
quota = true;
result = await call('analyze', { text: 'x', history: [], finance }, 'invalid json');
assert.equal(result.status, 200); assert.ok(result.body.result.includes('程序统计')); checks++;
const draft = { type: 'EXPENSE', amount: '36.80', date: '2026-09-06', category: '食品', subcategory: '', account: '未指定', member: '老婆', recordedBy: '老公', merchant: '', project: '', note: '午饭36.8' };
result = await call('chat', { ...input, text: '老婆午饭36.8，记一下' }, { reply: '已保存', entries: [draft], query: null });
assert.equal(result.status, 200); assert.ok(JSON.parse(result.body.result).reply.includes('待确认')); checks++;
const callsBeforeRepair = providerCalls;
const salaryDraft = { ...draft, type: 'INCOME', amount: '5450', category: '职业收入', subcategory: '工资', member: '老婆', note: '今天老婆工资入账5450' };
result = await call('chat', { ...input, text: '今天老婆工资入账5450' }, [
  { reply: '格式错误', entries: [salaryDraft], query: null, extra: true },
  { reply: '已重新整理', entries: [salaryDraft], query: null },
]);
assert.equal(result.status, 200); assert.ok(JSON.parse(result.body.result).reply.includes('待确认'));
assert.equal(providerCalls, callsBeforeRepair + 2);
assert.ok(sent.messages[0].content.includes('工资入账'));
assert.ok(sent.messages[2].content.includes('上一次回答未通过 JSON 格式校验')); checks++;
const callsBeforeExplicitIncomeFallback = providerCalls;
result = await call('chat', { ...input, text: '今天意外收入1034元', giftFields: true }, [
  { reply: '格式错误', entries: [], query: null, extra: true },
  { reply: '仍然错误', entries: [], query: null, extra: true },
]);
assert.equal(result.status, 200);
const explicitIncome = JSON.parse(result.body.result).entries[0];
assert.equal(explicitIncome.type, 'INCOME');
assert.equal(explicitIncome.amount, '1034');
assert.equal(explicitIncome.date, input.today);
assert.equal(explicitIncome.category, '其他收入');
assert.equal(explicitIncome.isGift, false);
assert.equal(explicitIncome.counterparty, '');
assert.equal(providerCalls, callsBeforeExplicitIncomeFallback + 2);
assert.ok(sent.messages[0].content.includes('意外收入')); checks++;
const callsBeforeUpstreamFailure = providerCalls;
result = await call('chat', input, '__HTTP_503__');
assert.equal(result.status, 502); assert.equal(providerCalls, callsBeforeUpstreamFailure + 1); checks++;
console.log(`Finance chat regression checks: ${checks} passed. No live requests.`);
