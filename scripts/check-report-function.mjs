// Synthetic fixtures only. No real ledger, credentials, network or model calls.
// Node 24+; run from repository root: node scripts/check-report-function.mjs
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { runInNewContext } from 'node:vm';
import assert from 'node:assert/strict';

let handler;
let output = '';
let finishReason = 'stop';
const diagnostics = [];
const input = { period: '2026 年 8 月', income: '10000.00', expense: '12000.00', balance: '-2000.00',
  categories: [{ name: '食品酒水', amount: '2000.00' }, { name: '居家物业', amount: '10000.00' }],
  members: [{ name: '本人', amount: '12000.00' }],
  trend: Array.from({ length: 6 }, (_, i) => ({ period: `2026-0${i + 3}`, income: '10000.00', expense: '12000.00' })) };
const sandbox = {
  Deno: { serve: h => { handler = h; }, env: { get: name => ({
    SUPABASE_URL: 'https://project.example.test', SUPABASE_ANON_KEY: 'fixture-public',
    SUPABASE_SERVICE_ROLE_KEY: 'fixture-service', OPENAI_API_KEY: 'fixture-provider',
    OPENAI_MODEL: 'fixture', OPENAI_BASE_URL: 'https://provider.example.test',
  })[name] } },
  console: { warn: event => diagnostics.push(event) }, Response, Request, TextDecoder, URL, AbortSignal, setTimeout, clearTimeout,
  fetch: async url => new Response(JSON.stringify(url.endsWith('/auth/v1/user') ? { id: '00000000-0000-0000-0000-000000000001' }
    : url.endsWith('/get_my_family') ? { id: 'fixture-family' }
    : url.endsWith('/consume_ai_quota') ? true
    : { choices: [{ finish_reason: finishReason, message: { content: output } }] }), { status: 200 }),
};
runInNewContext(stripTypeScriptTypes(readFileSync('supabase/functions/ledger-ai/index.ts', 'utf8'), { mode: 'transform' }), sandbox);
let count = 0;
for (const text of [
  '本期记录较少，建议继续记录日常收支。',
  '2026年8月支出集中于居家物业。',
  '2026-08支出集中于居家物业。',
  '8月支出集中于居家物业。',
  '1. 建议核对大额开支。\n2. 建议记录账户来源。',
  '本期支出为12,000.00元，结余为-2000元。',
  '２０２６年８月支出为１２０００元。',
]) {
  assert.equal(sandbox.reportNumbersSupported(text, input), true, text); count++;
}
for (const text of ['支出占80%。', '本期结余增加了3000元。', '支出为1.2万元。', '2025年8月的收支。', '12月的支出。']) {
  assert.equal(sandbox.reportNumbersSupported(text, input), false, text); count++;
}
for (const [generated, fallback] of [
  [JSON.stringify({ report: '2026年8月支出集中于居家物业。' }), false],
  [JSON.stringify({ report: '本期节省3000元。' }), true],
  ['invalid json', true],
]) {
  output = generated;
  const response = await handler(new Request('https://project.example.test/functions/v1/ledger-ai', {
    method: 'POST', headers: { Authorization: 'Bearer fixture-token-123456789012345', 'Content-Type': 'application/json' },
    body: JSON.stringify({ operation: 'report', input }),
  }));
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.result.includes('基础统计摘要'), fallback);
  if (fallback) { assert.ok(body.result.includes('支出：¥ 12000.00')); assert.ok(!body.result.includes('3000')); }
  count++;
}
assert.ok(diagnostics.every(line => line === '{"event":"ai_report_fallback","reason":"output_validation"}'));
count++;
const yearly = { ...input, period: '2026 年', trend: Array.from({ length: 12 }, (_, i) => ({
  period: `2026-${String(i + 1).padStart(2, '0')}`, income: '10000.00', expense: '12000.00',
})) };
sandbox.reportInput(yearly);
assert.equal(sandbox.reportNumbersSupported('2026年全年支出按12月等月份汇总展示。', yearly), true);
count++;
finishReason = 'length';
output = '{"report":"incomplete';
const truncated = await handler(new Request('https://project.example.test/functions/v1/ledger-ai', {
  method: 'POST', headers: { Authorization: 'Bearer fixture-token-123456789012345', 'Content-Type': 'application/json' },
  body: JSON.stringify({ operation: 'report', input: yearly }),
}));
assert.equal(truncated.status, 200);
assert.ok((await truncated.json()).result.includes('基础统计摘要'));
count++;
const daily = { ...input, period: '2024-02-29', trend: [
  { period: '2024-02-29', income: '10000.00', expense: '12000.00' },
] };
const weekly = { ...input, period: '2025-12-29 至 2026-01-04', trend: [
  '2025-12-29', '2025-12-30', '2025-12-31', '2026-01-01', '2026-01-02', '2026-01-03', '2026-01-04',
].map(period => ({ period, income: '10000.00', expense: '12000.00' })) };
for (const [fixture, text, expected] of [
  [daily, '2024-02-29支出为12000元。', true],
  [daily, '2024年2月29日结余为-2000元。', true],
  [daily, '2月29日需核对开支。', true],
  [daily, '29日支出集中。', true],
  [daily, '2024-02-28支出为12000元。', false],
  [daily, '2024年2月30日支出集中。', false],
  [daily, '本期支出为29元。', false],
  [weekly, '2025-12-29至2026-01-04的支出为12000元。', true],
  [weekly, '2025年12月31日与2026年1月1日均有记录。', true],
  [weekly, '12月30日支出集中。', true],
  [weekly, '2025-01-01支出集中。', false],
  [weekly, '2026-01-05支出集中。', false],
  [weekly, '本周支出占80%。', false],
  [weekly, '本周节省3000元。', false],
]) {
  sandbox.reportInput(fixture);
  assert.equal(sandbox.reportNumbersSupported(text, fixture), expected, text); count++;
}
finishReason = 'stop';
for (const fixture of [
  { ...daily, period: '2023-02-29' },
  { ...daily, period: '2026 年 13 月' },
  { ...weekly, period: '2025-12-30 至 2026-01-05' },
  { ...weekly, period: '2025-12-29 至 2026-01-05' },
  { ...daily, trend: [{ period: '2024-02-30', income: '1.00', expense: '0.00' }] },
]) {
  assert.throws(() => sandbox.reportInput(fixture)); count++;
}
for (const fixture of [daily, weekly]) {
  output = JSON.stringify({ report: `${fixture.period}支出为12000元。` });
  const response = await handler(new Request('https://project.example.test/functions/v1/ledger-ai', {
    method: 'POST', headers: { Authorization: 'Bearer fixture-token-123456789012345', 'Content-Type': 'application/json' },
    body: JSON.stringify({ operation: 'report', input: fixture }),
  }));
  assert.equal(response.status, 200);
  assert.equal((await response.json()).result.includes('基础统计摘要'), false); count++;
}
console.log(`Report regression checks: ${count} passed. No live requests.`);
