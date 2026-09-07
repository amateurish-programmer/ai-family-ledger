// Synthetic protocol checks, never contact a provider or use a real family ledger.
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { runInNewContext } from 'node:vm';
import assert from 'node:assert/strict';
let handler, generated, sent;
let quota = true;
let familyAllowed = true;
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
    if (url.endsWith('/get_my_family')) return Response.json(familyAllowed ? { id: 'fixture-family' } : null);
    if (url.endsWith('/consume_ai_quota')) return Response.json(quota);
    providerCalls++; sent = JSON.parse(init.body);
    return Response.json({ choices: [{ finish_reason: 'stop', message: { content: generated } }] });
  },
};
runInNewContext(stripTypeScriptTypes(readFileSync('supabase/functions/ledger-ai/index.ts', 'utf8'), { mode: 'transform' }), sandbox);
async function call(operation, payload, output) {
  generated = typeof output === 'string' ? output : JSON.stringify(output);
  const response = await handler(new Request('https://project.example.test/functions/v1/ledger-ai', {
    method: 'POST', headers: { Authorization: 'Bearer fixture-token-123456789012345', 'Content-Type': 'application/json' },
    body: JSON.stringify({ operation, input: payload }),
  }));
  return { status: response.status, body: await response.json() };
}

const row = { type:'EXPENSE',amount:'100.00',date:'2026-09-07',category:'人情',subcategory:'随礼',account:'未指定',member:'本人',recordedBy:'本人',merchant:'',project:'',note:'给小王随礼100元' };
let r=await call('chat', {...input,giftFields:true}, {reply:'提案',entries:[{...row,isGift:true,counterparty:'小王'}],query:null});
assert.equal(r.status,200); assert.equal(JSON.parse(r.body.result).entries[0].counterparty,'小王');
r=await call('chat', input, {reply:'提案',entries:[row],query:null}); assert.equal(r.status,200); assert.equal('isGift' in JSON.parse(r.body.result).entries[0],false);
r=await call('chat', input, {reply:'提案',entries:[{...row,isGift:true,counterparty:'小王'}],query:null}); assert.equal(r.status,502);
const items=[{id:'00000000-0000-0000-0000-000000000001',merchant:'',note:'小王结婚随礼'}];
r=await call('gift_history',{items},{items:[{id:items[0].id,isGift:true,counterparty:'小王'}]}); assert.equal(r.status,200);
assert.deepEqual(Object.keys(JSON.parse(sent.messages[1].content).items[0]).sort(),['id','merchant','note']);
assert.ok(sent.messages[0].content.includes('不可信')); assert.ok(sent.messages[0].content.includes('不能计算'));
for (const output of [{items:[{id:'unknown',isGift:true,counterparty:'张三'}]},{items:[{id:items[0].id,isGift:'true',counterparty:'张三'}]},{items:[{id:items[0].id,isGift:true,counterparty:'张三',amount:'99'}]},{items:[]},{items:[{id:items[0].id,isGift:true,counterparty:'张三'},{id:items[0].id,isGift:true,counterparty:'张三'}]}]) {r=await call('gift_history',{items},output);assert.equal(r.status,502);}
r=await call('gift_history',{items},{items:[{id:items[0].id,isGift:true,counterparty:''}]}); assert.equal(r.status,200);
for (const bad of [{items:Array(21).fill(items[0])},{items:[{...items[0],amount:'100'}]},{items:[{...items[0],note:'x'.repeat(2001)}]},{items:[items[0],items[0]]}]) {const n=providerCalls;r=await call('gift_history',bad,{});assert.equal(r.status,400);assert.equal(providerCalls,n);}
quota=false;r=await call('gift_history',{items},{});assert.equal(r.status,429);
quota=true;familyAllowed=false;const callsBeforeDenied=providerCalls;
r=await call('gift_history',{items},{});assert.equal(r.status,403);assert.equal(providerCalls,callsBeforeDenied);
const noAuth=await handler(new Request('https://project.example.test/functions/v1/ledger-ai',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({operation:'gift_history',input:{items}})}));assert.equal(noAuth.status,401);assert.equal(providerCalls,callsBeforeDenied);
console.log('Gift AI contract checks passed. Synthetic provider only.');
