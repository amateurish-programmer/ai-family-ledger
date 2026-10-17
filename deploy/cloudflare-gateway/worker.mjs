// Fixed project only. No secrets, bindings, persistence, or request/body logs.
const UPSTREAM = 'https://xdgeybztysuvvwagqkvb.supabase.co';
const OTA_ROOT = '/storage/v1/object/public/app-updates/';
const MAX_UPLOAD = 6 * 1024 * 1024;
const MAX_JSON = 12 * 1024 * 1024;
const MAX_MANIFEST = 32 * 1024;
const MAX_APK = 50 * 1024 * 1024;
const UUID = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}';
const RPC = new Set(['create_family', 'join_family', 'create_invite', 'get_my_family',
  'update_family_profile', 'get_ledger_manifest', 'put_ledger_entries', 'put_ledger_entry']);
function headers() {
  return new Headers({ 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
}
function failure(status) {
  return Response.json({ error: '云端连接暂时不可用，请稍后重试' }, { status, headers: headers() });
}
function routeFor(url) {
  const path = url.pathname, query = url.search;
  if (path.startsWith('/updates/')) {
    const key = path.slice(9);
    if (query) return null;
    if (key === 'latest.json') return { method: 'GET', path: OTA_ROOT + key, kind: 'manifest' };
    if (/^releases\/[1-9][0-9]*\/ai-family-ledger-v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.apk$/.test(key)) {
      return { method: 'GET', path: OTA_ROOT + key, kind: 'apk' };
    }
    return null;
  }
  let method;
  if (/^\/auth\/v1\/(signup|recover|verify)$/.test(path) && !query) method = 'POST';
  if (path === '/auth/v1/user' && !query) method = 'PUT';
  if (path === '/auth/v1/token' && /^\?grant_type=(password|refresh_token)$/.test(query)) method = 'POST';
  if (path === '/auth/v1/logout' && query === '?scope=local') method = 'POST';
  if (path === '/functions/v1/ledger-ai' && !query) method = 'POST';
  if (path.startsWith('/rest/v1/rpc/') && RPC.has(path.slice(13)) && !query) method = 'POST';
  if (path === '/rest/v1/ledger_entries') {
    const params = url.searchParams, keys = [...params.keys()];
    const exactKeys = keys.length === new Set(keys).size && keys.every(k => ['select', 'family_id', 'id', 'limit'].includes(k));
    const family = new RegExp('^eq\\.' + UUID + '$').test(params.get('family_id') ?? '');
    const id = params.get('id') ?? '';
    const single = new RegExp('^eq\\.' + UUID + '$').test(id) && params.get('limit') === '1';
    const batch = new RegExp('^in\\.\\(' + UUID + '(,' + UUID + '){0,49}\\)$').test(id) && params.get('limit') === '50';
    if (exactKeys && family && params.get('select') === 'id,payload,revision' && (single || batch)) method = 'GET';
  }
  return method ? { method, path: path + query, kind: 'api' } : null;
}
class TooLarge extends Error {}
function lengthOf(source, max, required = false) {
  const raw = source.get('content-length');
  if (raw === null && !required) return null;
  if (raw === null || !/^[0-9]+$/.test(raw) || Number(raw) > max || (required && Number(raw) < 1)) throw new TooLarge();
  return Number(raw);
}
async function readLimited(body, max, signal, expectedLength = null) {
  if (!body) {
    if (expectedLength) throw new Error('truncated body');
    return null;
  }
  const reader = body.getReader(), chunks = [];
  let size = 0;
  const cancel = () => { reader.cancel().catch(() => {}); };
  signal.addEventListener('abort', cancel, { once: true });
  try {
    while (true) {
      if (signal.aborted) throw new Error('cancelled');
      const { value, done } = await reader.read();
      if (signal.aborted) throw new Error('cancelled');
      if (done) break;
      size += value.byteLength;
      if (size > max) { await reader.cancel(); throw new TooLarge(); }
      chunks.push(value);
    }
    if (expectedLength !== null && size !== expectedLength) throw new Error('truncated body');
    const bytes = new Uint8Array(size);
    let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
    return bytes;
  } finally {
    signal.removeEventListener('abort', cancel);
    reader.releaseLock();
  }
}
export default {
  async fetch(request) {
    const url = new URL(request.url);
    if (request.headers.has('origin')) return failure(403);
    const route = routeFor(url);
    if (!route) return failure(404);
    if (request.method !== route.method) return failure(405);
    const controller = new AbortController();
    const abort = () => controller.abort();
    request.signal.addEventListener('abort', abort, { once: true });
    if (request.signal.aborted) abort();
    const timer = setTimeout(abort, route.path === '/functions/v1/ledger-ai' ? 115_000 : 25_000);
    let upstream;
    try {
      let body;
      try {
        const length = lengthOf(request.headers, MAX_UPLOAD);
        body = await readLimited(request.body, MAX_UPLOAD, controller.signal, length);
      } catch (error) {
        if (error instanceof TooLarge) return failure(413);
        throw error;
      }
      const input = new Headers({ Accept: route.kind === 'apk' ? 'application/octet-stream' : 'application/json',
        'Accept-Encoding': 'identity', 'Cache-Control': 'no-cache' });
      if (route.kind === 'api') {
        for (const name of ['Authorization', 'Content-Type', 'apikey']) {
          const value = request.headers.get(name);
          if (value) input.set(name, value);
        }
      }
      // One attempt only. JSON bytes, integer cents, JWT and RPC errors stay exact.
      upstream = await fetch(UPSTREAM + route.path, {
        method: route.method, headers: input, body, redirect: 'manual', cache: 'no-store', signal: controller.signal,
      });
      const media = (upstream.headers.get('content-type') ?? '').split(';')[0].trim().toLowerCase();
      const encoding = upstream.headers.get('content-encoding');
      if ((upstream.status >= 300 && upstream.status < 400) || (encoding && encoding !== 'identity')) throw new Error('invalid upstream');
      const out = headers();
      if (route.kind === 'apk' && upstream.status === 200) {
        const length = lengthOf(upstream.headers, MAX_APK, true);
        if (!['application/vnd.android.package-archive', 'application/octet-stream'].includes(media)) throw new Error('invalid APK media');
        out.set('Content-Type', 'application/vnd.android.package-archive');
        out.set('Content-Length', String(length));
        // Stream APK; Android checks size, SHA-256, package, version and signer.
        return new Response(upstream.body, { status: 200, headers: out });
      }
      if (upstream.status !== 204 && media !== 'application/json') throw new Error('invalid JSON media');
      const limit = route.kind === 'api' ? MAX_JSON : MAX_MANIFEST;
      const length = lengthOf(upstream.headers, limit);
      // Buffer bounded JSON completely so a known truncated reply never reaches a merge.
      const bytes = await readLimited(upstream.body, limit, controller.signal, length);
      out.set('Content-Type', 'application/json; charset=utf-8');
      return new Response(upstream.status === 204 ? null : bytes, { status: upstream.status, headers: out });
    } catch {
      await upstream?.body?.cancel().catch(() => {});
      return failure(502);
    } finally {
      clearTimeout(timer);
      request.signal.removeEventListener('abort', abort);
    }
  },
};
