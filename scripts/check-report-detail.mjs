// Synthetic handler checks for evidence-based report interpretation; no network or user data.
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { runInNewContext } from 'node:vm';
import assert from 'node:assert/strict';

let handler, outputs, requests, events, authStatus = 200;
const sandbox = {
  Deno: { serve: h => { handler = h; }, env: { get: name => ({
    SUPABASE_URL: 'https://project.example.test', SUPABASE_ANON_KEY: 'fixture-public',
    SUPABASE_SERVICE_ROLE_KEY: 'fixture-service', OPENAI_API_KEY: 'fixture-provider',
    OPENAI_MODEL: 'fixture', OPENAI_BASE_URL: 'https://provider.example.test',
  })[name] } },
  console: { warn: value => events.push(JSON.parse(value)) }, Response, Request, TextDecoder, URL, AbortSignal, setTimeout, clearTimeout,
  fetch: async (url, init) => {
    if (url.endsWith('/auth/v1/user')) return Response.json({ id: '00000000-0000-0000-0000-000000000001' }, { status: authStatus });
    if (url.endsWith('/get_my_family')) return Response.json({ id: 'synthetic-family' });
    if (url.endsWith('/consume_ai_quota')) return Response.json(true);
    requests.push(JSON.parse(init.body));
    const value = outputs.length > 1 ? outputs.shift() : outputs[0];
    if (value === 'HTTP503') return new Response('{}', { status: 503 });
    return Response.json({ choices: [{ finish_reason: value === 'TRUNCATED' ? 'length' : 'stop',
      message: { content: typeof value === 'string' ? value : JSON.stringify(value) } }] });
  },
};
runInNewContext(stripTypeScriptTypes(readFileSync('supabase/functions/ledger-ai/index.ts', 'utf8'), { mode: 'transform' }), sandbox);
const input = { reportV2: true, finance: { scope: '2026 年 10 月；本机已保存人民币收支', facts: [
  { id: 'F0', label: '本期收入', value: '¥ 10000.00' },
  { id: 'F1', label: '本期支出', value: '¥ 8000.03' },
  { id: 'F2', label: '支出分类 食品', value: '¥ 2000.00；占本期支出 25.00%；20 笔' },
  { id: 'F3', label: '本期明细 1', value: '2026-10-17；支出 ¥ 13.00；食品 / 午餐；商家=711便利店；备注=工作日便餐' },
  { id: 'F4', label: '明细覆盖', value: '节选 40 笔，共 120 笔，不能推断全部消费习惯' },
  { id: 'F5', label: '期间完整性', value: '期间尚未结束；当前日期 2026-10-17' },
], limitations: ['本期尚未结束；明细节选不代表全部消费。', '结余不代表账户余额。'] } };
const good = {
  overview: { text: '已记收入能覆盖已记支出，仍应为后续固定账单留出空间。', factIds: ['F0', 'F1'] },
  spending: { text: '午餐明细呈现了工作日便餐场景，不能仅据节选认定长期消费习惯。', factIds: ['F3', 'F2'] },
  advice: { text: '可以提前准备部分工作日午餐，比较便利店与自备餐的实际开销后再安排餐饮预算。', factIds: [] },
};
let count = 0;
async function invoke(generated, value = input) {
  outputs = Array.isArray(generated) ? generated : [generated]; requests = []; events = [];
  const response = await handler(new Request('https://project.example.test/functions/v1/ledger-ai', {
    method: 'POST', headers: { Authorization: 'Bearer fixture-token-123456789012345', 'Content-Type': 'application/json' },
    body: JSON.stringify({ operation: 'report', input: value }),
  }));
  return { status: response.status, body: await response.json() };
}
const result = await invoke(good);
assert.equal(result.status, 200, 'new report protocol must accept deterministic facts and detail evidence');
for (const expected of ['收支概况', '消费明细观察', '收支建议', '711便利店', '13.00', '25.00%', '明细节选',
  '期间完整性：期间尚未结束；当前日期 2026-10-17', good.advice.text]) {
  assert.ok(result.body.result.includes(expected), expected);
}
assert.equal(requests.length, 1);
assert.ok(!result.body.result.includes('未通过'));
assert.ok(requests[0].messages[0].content.includes('消费明细'));
count++;

for (const text of ['建议每月存3000元。', '建议每月存三千元。', '餐饮占80%。', '支出为10000元。', '共十二笔。', '{{F0}}',
  '餐饮占本期支出的一半。', '本期共有两条支出记录。', '期间发生过三次聚餐。', '支出已经过半。']) {
  const invalid = { ...good, spending: { text, factIds: ['F1'] } };
  const repaired = await invoke([invalid, good]);
  assert.equal(repaired.status, 200);
  assert.ok(repaired.body.result.includes(good.spending.text));
  assert.ok(!repaired.body.result.includes(text));
  assert.equal(requests.length, 2);
  assert.ok(events.some(e => e.event === 'ai_output_repaired'));
  assert.ok(requests[1].messages.at(-1).content.includes('overview'));
  count++;
}
for (const invalid of [
  { ...good, spending: { text: '可核对便餐。', factIds: ['F999'] } },
  { ...good, overview: { text: '可核对支出。', factIds: ['F0', 'F0'] } },
  { ...good, extra: 'unexpected' }, 'not json', 'TRUNCATED',
]) {
  const fallback = await invoke(invalid);
  assert.equal(fallback.status, 200);
  assert.ok(fallback.body.result.includes('仅为程序统计'));
  assert.ok(!fallback.body.result.includes('数值或格式校验'));
  assert.ok(!fallback.body.result.includes('F999'));
  assert.equal(requests.length, 2);
  assert.ok(events.every(e => Object.keys(e).sort().join() === 'event,operation,reason'));
  count++;
}
const numbered = await invoke({ ...good, advice: { text: '1. 先核对固定账单。\n2. 再检查可调整的外卖开支。', factIds: [] } });
assert.ok(numbered.body.result.includes('先核对固定账单'));
assert.equal(requests.length, 1); count++;
const oneOff = await invoke({ ...good, spending: { text: '请区分一次性开支和固定账单，再决定可调整的消费。', factIds: ['F3'] } });
assert.ok(oneOff.body.result.includes('一次性开支'));
assert.equal(requests.length, 1); count++;
for (const invalid of [{ ...input, reportV2: false }, { ...input, extra: true }, { reportV2: true },
  { ...input, finance: { ...input.finance, facts: [{ id: 'F0', label: '未知', value: 'a'.repeat(501) }] } }]) {
  assert.equal((await invoke(good, invalid)).status, 400); assert.equal(requests.length, 0); count++;
}
assert.equal((await invoke('HTTP503')).status, 502); assert.equal(requests.length, 1); count++;
authStatus = 401;
assert.equal((await invoke(good)).status, 401); assert.equal(requests.length, 0); count++;
console.log(`Detailed report checks: ${count} passed. No live requests.`);
