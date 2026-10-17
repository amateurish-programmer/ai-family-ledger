import { test, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import worker from './worker.mjs';

const originalFetch = globalThis.fetch;
afterEach(() => { globalThis.fetch = originalFetch; });
const base = 'https://family-ledger-gateway.pages.dev';
const upstream = 'https://xdgeybztysuvvwagqkvb.supabase.co';
const json = (body = '{}', status = 200) => new Response(body, { status, headers: { 'Content-Type': 'application/json' } });

test('auth forwards to the original project with public key and raw session bytes', async () => {
  const body = '{"email":"fixture@example.invalid","password":"fixture-only"}';
  const reply = '{"access_token":"fixture","expires_at":9007199254740993}';
  let calls = 0;
  globalThis.fetch = async (url, init) => {
    calls++;
    assert.equal(url, upstream + '/auth/v1/token?grant_type=password');
    assert.equal(new TextDecoder().decode(init.body), body);
    assert.equal(init.headers.get('apikey'), 'sb_publishable_fixture');
    assert.equal(init.headers.get('cookie'), null);
    assert.equal(init.redirect, 'manual');
    return json(reply);
  };
  const response = await worker.fetch(new Request(base + '/auth/v1/token?grant_type=password', {
    method: 'POST', body, headers: { apikey: 'sb_publishable_fixture', Cookie: 'private=never', 'Content-Type': 'application/json' },
  }));
  assert.equal(response.status, 200);
  assert.equal(await response.text(), reply);
  assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.equal(calls, 1);
});

test('sync RPC preserves integer cents and bearer and never retries a failed write', async () => {
  const body = '{"p_payload":{"amountMinor":9007199254740993}}';
  let calls = 0;
  globalThis.fetch = async (url, init) => {
    calls++;
    assert.equal(url, upstream + '/rest/v1/rpc/put_ledger_entry');
    assert.equal(init.headers.get('authorization'), 'Bearer fixture');
    assert.equal(new TextDecoder().decode(init.body), body);
    return json('{"code":"P0001","message":"fixture"}', 409);
  };
  const response = await worker.fetch(new Request(base + '/rest/v1/rpc/put_ledger_entry', {
    method: 'POST', body, headers: { Authorization: 'Bearer fixture', 'Content-Type': 'application/json' },
  }));
  assert.equal(response.status, 409);
  assert.equal(await response.text(), '{"code":"P0001","message":"fixture"}');
  assert.equal(calls, 1);
});

test('only known Auth, recovery, family, sync and AI operations are exposed', async () => {
  globalThis.fetch = async () => json();
  for (const [method, path] of [
    ['POST', '/auth/v1/signup'], ['POST', '/auth/v1/recover'], ['POST', '/auth/v1/verify'],
    ['PUT', '/auth/v1/user'], ['POST', '/auth/v1/logout?scope=local'],
    ['POST', '/auth/v1/token?grant_type=refresh_token'], ['POST', '/functions/v1/ledger-ai'],
    ...['create_family','join_family','create_invite','get_my_family','update_family_profile',
      'get_ledger_manifest','put_ledger_entries'].map(name => ['POST', '/rest/v1/rpc/' + name]),
  ]) assert.equal((await worker.fetch(new Request(base + path, { method }))).status, 200, path);
});

test('ledger reads keep the exact bounded family and entry filter', async () => {
  const path = '/rest/v1/ledger_entries?select=id,payload,revision&family_id=eq.00000000-0000-0000-0000-000000000001&id=in.(00000000-0000-0000-0000-000000000002)&limit=50';
  globalThis.fetch = async (url) => { assert.equal(url, upstream + path); return json('[]'); };
  assert.equal((await worker.fetch(new Request(base + path))).status, 200);
});

test('arbitrary targets, admin, other buckets, query injection and wrong methods are rejected', async () => {
  globalThis.fetch = async () => { assert.fail('rejected route reached upstream'); };
  for (const path of ['/auth/v1/admin/users','/rest/v1/rpc/unknown','/functions/v1/other',
    '/updates/../private/latest.json','/updates/latest.json?url=https://evil.invalid',
    '/auth/v1/token?grant_type=password&grant_type=refresh_token',
    '/rest/v1/ledger_entries?select=*&family_id=eq.fixture',
    '/storage/v1/object/public/other/latest.json']) {
    assert.equal((await worker.fetch(new Request(base + path))).status, 404, path);
  }
  assert.equal((await worker.fetch(new Request(base + '/auth/v1/signup'))).status, 405);
});

test('manifest and APK use only the original bucket and strip credentials', async () => {
  const paths = ['latest.json', 'releases/24/ai-family-ledger-v1.9.2.apk'];
  for (const path of paths) {
    globalThis.fetch = async (url, init) => {
      assert.equal(url, upstream + '/storage/v1/object/public/app-updates/' + path);
      assert.equal(init.headers.get('authorization'), null);
      assert.equal(init.headers.get('apikey'), null);
      return path.endsWith('.apk') ? new Response(new Uint8Array([1,2,3]), { headers: {
        'Content-Type': 'application/vnd.android.package-archive', 'Content-Length': '3',
      } }) : json('{"versionCode":24}');
    };
    const response = await worker.fetch(new Request(base + '/updates/' + path, {
      headers: { Authorization: 'Bearer private', apikey: 'private' },
    }));
    assert.equal(response.status, 200);
    assert.equal((await response.arrayBuffer()).byteLength, path.endsWith('.apk') ? 3 : 18);
  }
});

test('redirects cannot move credentials to another upstream', async () => {
  globalThis.fetch = async () => new Response(null, { status: 302, headers: { Location: 'https://evil.invalid' } });
  assert.equal((await worker.fetch(new Request(base + '/auth/v1/signup', { method: 'POST' }))).status, 502);
});

test('oversize and truncated JSON fail before a partial response reaches the app', async () => {
  for (const reply of [
    () => json(' '.repeat(12 * 1024 * 1024 + 1)),
    () => new Response('{}', { headers: { 'Content-Type': 'application/json', 'Content-Length': '100' } }),
  ]) {
    globalThis.fetch = async () => reply();
    assert.equal((await worker.fetch(new Request(base + '/rest/v1/rpc/get_my_family', { method: 'POST' }))).status, 502);
  }
});

test('request overflow is rejected and client cancellation aborts the single upstream', async () => {
  globalThis.fetch = async () => { assert.fail('oversized request reached upstream'); };
  assert.equal((await worker.fetch(new Request(base + '/functions/v1/ledger-ai', {
    method: 'POST', body: ' '.repeat(6 * 1024 * 1024 + 1),
  }))).status, 413);
  const controller = new AbortController();
  globalThis.fetch = async (_url, init) => new Promise((_resolve, reject) => {
    init.signal.addEventListener('abort', () => reject(new Error('cancelled')), { once: true });
    controller.abort();
  });
  assert.equal((await worker.fetch(new Request(base + '/auth/v1/signup', {
    method: 'POST', signal: controller.signal,
  }))).status, 502);
});
