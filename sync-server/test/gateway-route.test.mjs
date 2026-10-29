import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { routeMojian } from '../gateway-route.mjs';

async function serve(handler) {
  const server = http.createServer(handler);
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  return { server, port: server.address().port, close: () => new Promise(resolve => { server.close(resolve); server.closeAllConnections(); }) };
}

test('Sakura shared ingress preserves authorization and streams large blobs without stealing other routes', async () => {
  const received = [];
  const backend = await serve(async (request, response) => {
    let length = 0;
    for await (const chunk of request) length += chunk.length;
    received.push({ path: request.url, token: request.headers.authorization, length });
    response.writeHead(request.headers.authorization === 'Bearer isolated-gateway-test' ? 200 : 401);
    response.end('backend');
  });
  const gateway = await serve((request, response) => {
    if (routeMojian(request, response, { port: backend.port })) return;
    response.end('existing subscription or Dayweave route');
  });
  try {
    const base = `http://127.0.0.1:${gateway.port}`;
    const blob = `${base}/mojian-sync/v1/blobs/${'a'.repeat(64)}`;
    const body = Buffer.alloc(3 * 1024 * 1024, 17);
    assert.equal((await fetch(blob, { method: 'PUT', headers: { Authorization: 'Bearer isolated-gateway-test' }, body })).status, 200);
    assert.deepEqual(received[0], { path: `/mojian-sync/v1/blobs/${'a'.repeat(64)}`, token: 'Bearer isolated-gateway-test', length: body.length });
    assert.equal((await fetch(`${base}/mojian-sync/v1/manifest`)).status, 401);
    for (const url of ['/mojian-sync-other', '/mojian-sync/v1/manifest?secret=x', '/mojian-sync/v1/unknown'])
      assert.equal((await fetch(`${base}${url}`)).status, 404);
    assert.equal((await fetch(`${base}/mojian-sync/v1/manifest`, { headers: { Origin: 'https://untrusted.example' } })).status, 403);
    const oversized = await new Promise((resolve, reject) => {
      const request = http.request(`${base}/mojian-sync/v1/commit`, { method: 'POST', headers: { 'Content-Length': 4 * 1024 * 1024 + 1 } }, response => {
        response.resume(); response.on('end', () => resolve(response.statusCode));
      });
      request.on('error', reject); request.end();
    });
    assert.equal(oversized, 413);
    assert.equal(await (await fetch(`${base}/private-profile`)).text(), 'existing subscription or Dayweave route');
    assert.equal(await (await fetch(`${base}/dayweave/pair`, { method: 'POST' })).text(), 'existing subscription or Dayweave route');
    assert.equal(received.length, 2);
  } finally { await gateway.close(); await backend.close(); }
});

test('chunked oversized uploads are rejected and unavailable backends return 502', async () => {
  const backend = await serve((request, response) => { request.resume(); request.on('end', () => response.end('ok')); });
  const gateway = await serve((request, response) => routeMojian(request, response, { port: backend.port, maxBlobBytes: 2 }));
  try {
    const result = await new Promise((resolve, reject) => {
      const request = http.request({ hostname: '127.0.0.1', port: gateway.port, method: 'PUT', path: `/mojian-sync/v1/blobs/${'b'.repeat(64)}` }, response => { response.resume(); response.on('end', () => resolve(response.statusCode)); });
      request.on('error', reject);
      request.write('chunk'); request.end();
    });
    assert.equal(result, 413);
    await backend.close();
    assert.equal((await fetch(`http://127.0.0.1:${gateway.port}/mojian-sync/v1/manifest`)).status, 502);
  } finally { await gateway.close(); if (backend.server.listening) await backend.close(); }
});
