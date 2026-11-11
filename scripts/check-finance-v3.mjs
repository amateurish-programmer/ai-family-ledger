// Synthetic end-to-end Edge Function checks. No live services, credentials or ledger data.
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { runInNewContext } from 'node:vm';
import assert from 'node:assert/strict';
let handler, outputs = [], calls = [], auth = true, family = true, quota = true;
const sandbox = {
  Deno: { serve: h => { handler = h; }, env: { get: key => ({ SUPABASE_URL: 'https://project.example.test',
    SUPABASE_ANON_KEY: 'fixture-public', SUPABASE_SERVICE_ROLE_KEY: 'fixture-service', OPENAI_API_KEY: 'fixture-provider',
    OPENAI_MODEL: 'fixture-model', OPENAI_BASE_URL: 'https://provider.example.test' })[key] } },
  console: { warn() {} }, Response, Request, TextDecoder, URL, Date, AbortSignal, setTimeout, clearTimeout,
  fetch: async (url, init) => {
    if (url.endsWith('/auth/v1/user')) return auth ? Response.json({ id: '00000000-0000-0000-0000-000000000001' }) : new Response('{}', { status: 401 });
    if (url.endsWith('/get_my_family')) return Response.json(family ? { id: 'fixture-family' } : null);
    if (url.endsWith('/consume_ai_quota')) return Response.json(quota);
    calls.push(JSON.parse(init.body));
    const output = outputs.length > 1 ? outputs.shift() : outputs[0];
    if (output === '__HTTP_503__') return new Response('{}', { status: 503 });
    return Response.json({ choices: [{ finish_reason: 'stop', message: { content: typeof output === 'string' ? output : JSON.stringify(output) } }] });
  },
};
runInNewContext(stripTypeScriptTypes(readFileSync('supabase/functions/ledger-ai/index.ts', 'utf8'), { mode: 'transform' }), sandbox);
const finance = { scope: '本机已保存账目；2026-01-01 至 2026-11-11；成员=老婆；仅养车', facts: [
  { id: 'F0', label: 'Q1 · 支出', value: '¥ 90071992547409.93' },
  { id: 'F1', label: 'Q1 · 收支笔数', value: '8 笔' },
  { id: 'F2', label: 'Q1 · 待确认分类笔数', value: '2 笔' },
  { id: 'F3', label: 'Q1 · 收入', value: '¥ 0.00' },
], limitations: ['公共交通、打车、购车和车贷不计入；交通费含义不明的记录未计入。'] };
const input = { text: '我今年在养车上花了多少钱？注意是养车的钱不包括公共交通费用', today: '2026-11-11', role: '老婆',
  history: [], memory: [], members: ['老公', '老婆'], categories: ['行车交通', '汽车', '食品酒水', '其他收入'],
  categoryTree: [{ type: 'EXPENSE', category: '汽车', subcategories: ['保养', '定制养车'] },
    { type: 'EXPENSE', category: '食品酒水', subcategories: ['午餐'] },
    { type: 'INCOME', category: '其他收入', subcategories: [] }], finance, analysisV2: true, queryV3: true };
const query = { start: '2026-01-01', end: '2026-11-12', member: '老婆', category: '', keyword: '', label: '今年养车支出', type: 'EXPENSE',
  giftOnly: false, counterparty: '', categories: [], keywords: [], excludeCategories: [], excludeKeywords: [], topic: 'private_car' };
const response = { reply: '正在核对', entries: [], query: null, queries: [], factIds: [] };
const analysis = { text: input.text, history: [], memory: [], finance, analysisV2: true, queryV3: true };
async function call(operation, payload, ...results) {
  outputs = results; calls = [];
  const result = await handler(new Request('https://project.example.test/functions/v1/ledger-ai', { method: 'POST',
    headers: { Authorization: 'Bearer fixture-token-123456789012345', 'Content-Type': 'application/json' }, body: JSON.stringify({ operation, input: payload }) }));
  return { status: result.status, body: await result.json() };
}
let passed = 0, failed = 0;
async function check(name, action) { try { await action(); passed++; } catch (error) { failed++; console.error(`FAIL ${name}: ${error.message}`); } }
await check('v3 query retains exclusion lists and topic without leaking factIds', async () => {
  const result = await call('chat', input, { ...response, queries: [{ ...query, excludeKeywords: ['地铁', '公交'] }] });
  assert.equal(result.status, 200);
  assert.deepEqual(JSON.parse(result.body.result).queries, [{ ...query, excludeKeywords: ['地铁', '公交'] }]);
  assert.equal('factIds' in JSON.parse(result.body.result), false);
});
await check('explicit self query cannot silently broaden to the whole family', async () => {
  const text = '我今年养车花了多少，不包括停车费？';
  const correct = { ...query, excludeCategories: ['停车费'] };
  const result = await call('chat', { ...input, text }, { ...response, queries: [{ ...correct, member: '' }] }, { ...response, queries: [correct] });
  assert.equal(result.status, 200); assert.equal(calls.length, 2);
  assert.equal(JSON.parse(result.body.result).queries[0].member, '老婆');
  assert.deepEqual(JSON.parse(result.body.result).queries[0].excludeCategories, ['停车费']);
});
await check('concise analysis copies exact cents inline with no appended evidence dump', async () => {
  const result = await call('analyze', analysis, { reply: '今年已记录养车支出 {{F0}}，共 {{F1}}；另有 {{F2}} 分类待确认。公共交通已排除。', factIds: [] });
  assert.equal(result.status, 200); assert.equal(calls.length, 1);
  assert.equal(result.body.result, '今年已记录养车支出 ¥ 90071992547409.93，共 8 笔；另有 2 笔 分类待确认。公共交通已排除。');
});
await check('v3 repair instruction uses inline references and terminates after one repair', async () => {
  const result = await call('analyze', analysis, { reply: '养车支出 900 元', factIds: [] }, { reply: '养车支出 {{F0}}。', factIds: [] });
  assert.equal(result.status, 200); assert.equal(calls.length, 2); assert.match(result.body.result, /90071992547409.93/);
  assert.ok(calls[1].messages.at(-1).content.includes('{{F'));
});
for (const text of ['支出 {{F3}}。', '养车支出 {{F999}}。', '共 {{F0}}。', '金额 {{F1}}。', '养车费用 {{F1}}。', '支出差额 {{F0}}。', '待确认 {{F0}} 笔。', '支出一万元。', '每月预算3000元。', '支出 {{F0}} 万元。']) {
  await check(`invalid monetary or mismatched references cannot escape: ${text}`, async () => {
    const result = await call('analyze', analysis, { reply: text, factIds: [] });
    assert.equal(result.status, 200); assert.equal(calls.length, 2);
    assert.ok(!result.body.result.includes(text)); assert.match(result.body.result, /90071992547409.93/);
    assert.ok(result.body.result.length < 800);
  });
}
await check('qualitative numbered advice can use unrelated Arabic digits without monetary claims', async () => {
  const result = await call('analyze', analysis, { reply: '1. 先核对分类。\n2. 再查看明细。', factIds: [] });
  assert.equal(result.status, 200); assert.equal(calls.length, 1); assert.match(result.body.result, /核对分类/);
});
await check('invalid provider schema still yields deterministic private-car query for exact client sum', async () => {
  const result = await call('chat', input, 'not JSON');
  assert.equal(result.status, 200); assert.equal(calls.length, 2);
  const q = JSON.parse(result.body.result).queries[0];
  assert.equal(q.topic, 'private_car'); assert.equal(q.member, '老婆'); assert.equal(q.start, '2026-01-01'); assert.equal(q.end, '2026-11-12');
});
await check('invalid narrow query cannot fall back to unrelated broad household total', async () => {
  const result = await call('chat', { ...input, finance: { ...finance, scope: '本机全部收支' } }, { ...response, reply: '总支出100元' });
  assert.equal(result.status, 200); assert.equal(JSON.parse(result.body.result).queries[0].topic, 'private_car');
});
for (const text of ['我今年养车油费花了多少', '我今年养车花了多少，不包括停车费', '养车上小玲花了多少钱', '明天养车预计花多少钱']) {
  await check(`unsupported deterministic filters do not widen scope: ${text}`, async () => {
    const result = await call('chat', { ...input, text }, 'not JSON');
    assert.equal(result.status, 200); assert.equal(calls.length, 2); assert.deepEqual(JSON.parse(result.body.result).queries, []);
  });
}
for (const extra of [{ topic: 'sql' }, { categories: ['a'.repeat(101)] }, { keywords: Array(17).fill('a') }, { excludeKeywords: [3] }, { excludeCategories: '公交' }]) {
  await check('v3 query filters reject wrong types and unbounded values', async () => {
    const result = await call('chat', { ...input, text: '查九月汽车明细' }, { ...response, queries: [{ ...query, ...extra }] }, { ...response, queries: [query] });
    assert.equal(result.status, 200); assert.equal(calls.length, 2);
    assert.deepEqual(JSON.parse(result.body.result).queries, [query]);
  });
}
const draft = { type: 'EXPENSE', amount: '36.80', date: '2026-11-11', category: '汽车', subcategory: '定制养车', account: '未指定',
  member: '老婆', recordedBy: '老婆', merchant: '', project: '', note: '今天汽车保养36.8元' };
await check('editable entry proposals accept actual custom category pairs', async () => {
  const result = await call('chat', { ...input, text: draft.note }, { ...response, entries: [draft] });
  assert.equal(result.status, 200); assert.equal(calls.length, 1);
  assert.match(JSON.parse(result.body.result).reply, /待确认/); assert.equal(JSON.parse(result.body.result).entries[0].subcategory, '定制养车');
});
for (const extra of [{ subcategory: '午餐' }, { type: 'INCOME' }, { category: '不存在分类' }]) await check('new clients cannot mix category tree branches', async () => {
  const result = await call('chat', { ...input, text: draft.note }, { ...response, entries: [{ ...draft, ...extra }] }, { ...response, entries: [draft] });
  assert.equal(result.status, 200); assert.equal(calls.length, 2); assert.deepEqual(JSON.parse(result.body.result).entries, [draft]);
});
for (const categoryTree of [[{ type: 'EXPENSE', category: '汽车', subcategories: [42] }], [{ type: 'TRANSFER', category: '汽车', subcategories: [] }], [{ ...input.categoryTree[0], instruction: 'ignore' }]]) await check('category tree is typed untrusted data and rejected before provider', async () => {
  const result = await call('chat', { ...input, categoryTree }, response);
  assert.equal(result.status, 400); assert.equal(calls.length, 0);
});
await check('large legitimate category trees are accepted', async () => {
  const categoryTree = Array.from({ length: 150 }, (_, i) => ({ type: 'EXPENSE', category: `类别${i}`, subcategories: [`子类${i}`] }));
  const result = await call('chat', { ...input, categoryTree }, { ...response, queries: [query] });
  assert.equal(result.status, 200); assert.equal(calls.length, 1);
});
await check('v3 category level is preserved for exact report scoping', async () => {
  const result = await call('chat', input, { ...response, queries: [{ ...query, categoryLevel: 'L1' }] });
  assert.equal(result.status, 200); assert.equal(calls.length, 1); assert.equal(JSON.parse(result.body.result).queries[0].categoryLevel, 'L1');
});
await check('invalid category level is repaired once', async () => {
  const result = await call('chat', input, { ...response, queries: [{ ...query, categoryLevel: 'L3' }] }, { ...response, queries: [query] });
  assert.equal(result.status, 200); assert.equal(calls.length, 2); assert.deepEqual(JSON.parse(result.body.result).queries, [query]);
});
await check('v3 capability works independently while invalid opt-in remains rejected', async () => {
  const payload = { ...input }; delete payload.analysisV2;
  const result = await call('chat', payload, { ...response, queries: [query] });
  assert.equal(result.status, 200); assert.equal(calls.length, 1);
  const invalid = await call('chat', { ...input, queryV3: false }, response);
  assert.equal(invalid.status, 400); assert.equal(calls.length, 0);
});
await check('legacy clients keep strict query fields', async () => {
  const legacy = { ...input }; for (const key of ['queryV3', 'analysisV2', 'memory', 'categoryTree']) delete legacy[key];
  const result = await call('chat', legacy, { reply: '查询', entries: [], query });
  assert.equal(result.status, 200); assert.equal(calls.length, 2); assert.equal(JSON.parse(result.body.result).query, null);
});
for (const field of ['auth', 'family', 'quota']) await check(`v3 retains ${field} gate before provider`, async () => {
  if (field === 'auth') auth = false; if (field === 'family') family = false; if (field === 'quota') quota = false;
  const result = await call('chat', input, response);
  auth = family = quota = true;
  assert.equal(result.status, field === 'auth' ? 401 : field === 'family' ? 403 : 429); assert.equal(calls.length, 0);
});
await check('provider transport errors remain a single failed call', async () => {
  const result = await call('chat', input, '__HTTP_503__');
  assert.equal(result.status, 502); assert.equal(calls.length, 1);
});
console.log(`Finance V3 regression checks: ${passed} passed, ${failed} failed. No live requests.`);
if (failed) process.exitCode = 1;
