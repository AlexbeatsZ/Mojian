import http from 'node:http';

const prefix = '/mojian-sync/v1/';
const hopHeaders = new Set(['connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization', 'te', 'trailer', 'transfer-encoding', 'upgrade']);

function forwardedHeaders(headers) {
  const blocked = new Set([...hopHeaders, ...String(headers.connection ?? '').toLowerCase().split(',').map(value => value.trim())]);
  return Object.fromEntries(Object.entries(headers).filter(([name]) => !blocked.has(name.toLowerCase())));
}

export function routeMojian(request, response, { port = 18742, maxBlobBytes = 1024 * 1024 * 1024 } = {}) {
  if (!request.url?.startsWith('/mojian-sync')) return false;
  const reject = status => {
    response.writeHead(status, { 'Cache-Control': 'no-store', Connection: 'close' }).end();
    request.resume();
  };
  const manifest = request.url === `${prefix}manifest` && request.method === 'GET';
  const commit = request.url === `${prefix}commit` && request.method === 'POST';
  const blob = /^\/mojian-sync\/v1\/blobs\/[a-f0-9]{64}$/.test(request.url) && ['GET', 'PUT'].includes(request.method);
  if (!manifest && !commit && !blob) { reject(404); return true; }
  if (request.headers.origin) { reject(403); return true; }
  const limit = commit ? 4 * 1024 * 1024 : request.method === 'PUT' ? maxBlobBytes : 0;
  if (Number(request.headers['content-length'] ?? 0) > limit) { reject(413); return true; }
  const upstream = http.request({
    hostname: '127.0.0.1', port, method: request.method, path: request.url,
    headers: { ...forwardedHeaders(request.headers), host: `127.0.0.1:${port}` }, timeout: 10 * 60 * 1000,
  }, reply => {
    if (response.destroyed) { reply.destroy(); return; }
    response.writeHead(reply.statusCode ?? 502, forwardedHeaders(reply.headers));
    reply.on('error', () => response.destroy());
    reply.pipe(response);
  });
  let bytes = 0;
  request.on('data', chunk => {
    bytes += chunk.length;
    if (bytes > limit) {
      request.unpipe(upstream);
      upstream.destroy();
      if (!response.headersSent) reject(413);
      else response.destroy();
    }
  });
  upstream.on('timeout', () => upstream.destroy());
  upstream.on('error', () => {
    if (response.destroyed || response.writableEnded) return;
    if (!response.headersSent) response.writeHead(502, { 'Cache-Control': 'no-store' }).end();
    else response.destroy();
  });
  request.on('aborted', () => upstream.destroy());
  response.on('close', () => upstream.destroy());
  request.pipe(upstream);
  return true;
}
