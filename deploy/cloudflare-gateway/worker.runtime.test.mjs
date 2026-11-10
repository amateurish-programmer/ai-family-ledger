import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
const { Miniflare, Response: RuntimeResponse } = createRequire(import.meta.url)('miniflare');
let runtime, upstream;
before(async () => {
  runtime = new Miniflare({ modules: true,
    scriptPath: fileURLToPath(new URL('./worker.mjs', import.meta.url)),
    compatibilityDate: '2026-05-15', compatibilityFlags: ['enable_request_signal'],
    outboundService: request => upstream(request),
  });
  await runtime.ready;
});
after(async () => { await runtime?.dispose(); });
function byteStream(size) {
  let sent = 0;
  return new ReadableStream({ pull(controller) {
    if (sent === size) { controller.close(); return; }
    const chunk = new Uint8Array(Math.min(size - sent, 65536)).fill(65);
    sent += chunk.length; controller.enqueue(chunk);
  } });
}
async function digest(body) {
  const hash = createHash('sha256'); let size = 0;
  for await (const chunk of body) { size += chunk.length; hash.update(chunk); }
  return { size, sha256: hash.digest('hex') };
}
test('workerd preserves original RPC JSON integers and authorization', async () => {
  const payload = '{"amountMinor":9007199254740993}';
  upstream = async request => {
    assert.equal(request.url, 'https://xdgeybztysuvvwagqkvb.supabase.co/rest/v1/rpc/put_ledger_entry');
    assert.equal(request.headers.get('authorization'), 'Bearer fixture');
    assert.equal(request.headers.get('apikey'), 'sb_publishable_fixture');
    assert.equal(await request.text(), payload);
    return new RuntimeResponse(payload, { headers: { 'Content-Type': 'application/json' } });
  };
  const response = await runtime.dispatchFetch('https://gateway.example/rest/v1/rpc/put_ledger_entry', {
    method: 'POST', body: payload, headers: { Authorization: 'Bearer fixture', apikey: 'sb_publishable_fixture' },
  });
  assert.equal(response.status, 200);
  assert.equal(await response.text(), payload);
});
test('workerd forwards member avatars through the authenticated, uncached RPC routes', async () => {
  const photo = Buffer.alloc(65536, 0xff).toString('base64');
  const family = '95000000-0000-4000-8000-000000000001';
  const member = { user_id: '94000000-0000-4000-8000-000000000001', role: '成员', avatar: 'photo', photo };
  for (const [name, body, reply] of [
    ['set_my_member_avatar', { p_family: family, p_role: member.role, p_avatar: member.avatar, p_photo: photo }, member],
    ['get_family_member_avatars', { p_family: family }, [member]],
  ]) {
    const route = '/rest/v1/rpc/' + name;
    upstream = async request => {
      assert.equal(request.url, 'https://xdgeybztysuvvwagqkvb.supabase.co' + route);
      assert.equal(request.headers.get('authorization'), 'Bearer fixture');
      assert.equal(request.headers.get('apikey'), 'sb_publishable_fixture');
      assert.equal(await request.text(), JSON.stringify(body));
      return RuntimeResponse.json(reply);
    };
    const response = await runtime.dispatchFetch('https://gateway.example' + route, { method: 'POST',
      body: JSON.stringify(body), headers: { Authorization: 'Bearer fixture', apikey: 'sb_publishable_fixture' } });
    assert.equal(response.status, 200);
    assert.equal(response.headers.get('cache-control'), 'no-store');
    assert.deepEqual(await response.json(), reply);
  }
});
test('workerd rejects unknown-length responses over the Android limit', async () => {
  upstream = async () => new RuntimeResponse(byteStream(24 * 1024 * 1024), { headers: { 'Content-Type': 'application/json' } });
  const response = await runtime.dispatchFetch('https://gateway.example/rest/v1/rpc/get_my_family', { method: 'POST' });
  assert.equal(response.status, 502);
  assert.equal((await response.json()).error, '云端连接暂时不可用，请稍后重试');
});
test('workerd streams a complete realistic APK and strips all account headers', async () => {
  const size = 18045361;
  upstream = async request => {
    assert.equal(request.url, 'https://xdgeybztysuvvwagqkvb.supabase.co/storage/v1/object/public/app-updates/releases/23/ai-family-ledger-v1.9.1.apk');
    assert.equal(request.headers.get('authorization'), null);
    assert.equal(request.headers.get('apikey'), null);
    return new RuntimeResponse(byteStream(size), { headers: {
      'Content-Type': 'application/vnd.android.package-archive', 'Content-Length': String(size),
    } });
  };
  const response = await runtime.dispatchFetch('https://gateway.example/updates/releases/23/ai-family-ledger-v1.9.1.apk', {
    headers: { Authorization: 'Bearer private', apikey: 'private' },
  });
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('content-length'), String(size));
  assert.deepEqual(await digest(response.body), await digest(byteStream(size)));
});
