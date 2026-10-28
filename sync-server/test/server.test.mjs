import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm, readFile, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { randomUUID, createHash } from 'node:crypto';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { createSyncServer, validPath, prefix } from '../server.mjs';

const token = 'isolated-test-token-never-for-production';
const hash = value => createHash('sha256').update(value).digest('hex');
const temporaryParent = path.join(os.tmpdir(), '.agents');
async function temporary() {
  await mkdir(temporaryParent, { recursive: true });
  return mkdtemp(path.join(temporaryParent, 'mojian-native-sync-'));
}
async function listen(root, options = {}) {
  const service = await createSyncServer({ root, token, ...options });
  service.server.listen(0, '127.0.0.1');
  await once(service.server, 'listening');
  return { ...service, url: `http://127.0.0.1:${service.server.address().port}${prefix}/` };
}
async function request(url, endpoint, method = 'GET', body, authenticated = true) {
  return fetch(url + endpoint, { method, headers: authenticated ? { Authorization: `Bearer ${token}` } : {},
    body: body === undefined ? undefined : typeof body === 'string' ? body : JSON.stringify(body) });
}
async function put(url, content) {
  const digest = hash(content);
  assert.equal((await request(url, `blobs/${digest}`, 'PUT', content)).status, 200);
  return digest;
}
const operation = (workspaceId, changes) => ({ workspaceId, deviceId: randomUUID(), operationId: randomUUID(), changes });
async function fixture(run) {
  const root = await temporary();
  let service = await listen(root);
  try { await run(service, root, async () => { await service.close(); service = await listen(root); return service; }); }
  finally { await service.close(); await rm(root, { recursive: true, force: true }); }
}

test('path rules are cross-platform safe', () => {
  for (const name of ['library.json', 'notebooks/book/pages/page.json', 'notebooks/book/assets/page/image.png']) assert.equal(validPath(name), true, name);
  for (const name of ['../library.json', 'notebooks/../pages/a.json', 'notebooks/book/pages/CON.json', 'notebooks/book/assets/page/hi.',
    'settings.json', 'notebooks/book/pages/page-sync-conflict-a.json', 'notebooks/book/assets/page/a.previous', 'notebooks/book/pages/page.json\n', null]) assert.equal(validPath(name), false, name);
});

test('authentication, immutable blobs, size and malformed commits', async () => fixture(async service => {
  assert.equal((await request(service.url, 'manifest', 'GET', undefined, false)).status, 401);
  assert.equal((await request(service.url, 'commit', 'POST', 'null')).status, 400);
  assert.equal((await request(service.url, 'commit', 'POST', '{')).status, 400);
  const digest = await put(service.url, 'first');
  assert.equal(await (await request(service.url, `blobs/${digest}`)).text(), 'first');
  assert.equal((await request(service.url, `blobs/${digest}`, 'PUT', 'wrong')).status, 422);
  assert.equal(await (await request(service.url, `blobs/${digest}`)).text(), 'first');
}));

test('oversized blob is rejected without publishing', async () => {
  const root = await temporary();
  const service = await listen(root, { maxBlobBytes: 4 });
  try {
    assert.equal((await request(service.url, `blobs/${hash('12345')}`, 'PUT', '12345')).status, 413);
    assert.equal((await request(service.url, `blobs/${hash('12345')}`)).status, 404);
  } finally { await service.close(); await rm(root, { recursive: true, force: true }); }
});

test('strict CAS, atomic batches, durable receipts, id reuse and tombstones', async () => fixture(async (service, root, restart) => {
  const initial = await (await request(service.url, 'manifest')).json();
  const digest = await put(service.url, 'one');
  const commit = operation(initial.workspaceId, [{ path: 'library.json', expectedVersion: 0, hash: digest }]);
  const accepted = await (await request(service.url, 'commit', 'POST', commit)).json();
  assert.equal(accepted.revision, 1);
  service = await restart();
  assert.deepEqual(await (await request(service.url, 'commit', 'POST', commit)).json(), accepted);
  assert.equal((await request(service.url, 'commit', 'POST', { ...commit, changes: [{ ...commit.changes[0], hash: null }] })).status, 409);
  assert.equal((await request(service.url, 'commit', 'POST', operation(initial.workspaceId, commit.changes))).status, 409);
  assert.equal((await request(service.url, 'commit', 'POST', operation('wrong-workspace', commit.changes))).status, 409);
  const invalidBatch = operation(initial.workspaceId, [{ path: 'library.json', expectedVersion: 0, hash: null }, { path: 'notebooks/book/pages/ok.json', expectedVersion: 0, hash: digest }]);
  assert.equal((await request(service.url, 'commit', 'POST', invalidBatch)).status, 409);
  assert.equal((await (await request(service.url, 'manifest')).json()).files['notebooks/book/pages/ok.json'], undefined);
  const missing = operation(initial.workspaceId, [{ path: 'notebooks/book/pages/ok.json', expectedVersion: 0, hash: hash('not uploaded') }]);
  assert.equal((await request(service.url, 'commit', 'POST', missing)).status, 422);
  const deletion = operation(initial.workspaceId, [{ path: 'library.json', expectedVersion: 1, hash: null }]);
  assert.equal((await (await request(service.url, 'commit', 'POST', deletion)).json()).files['library.json'].hash, null);
  assert.deepEqual(await (await request(service.url, 'commit', 'POST', commit)).json(), accepted);
  const manifest = await (await request(service.url, 'manifest')).json();
  assert.deepEqual(manifest.files['library.json'], { hash: null, version: 2 });
  const stored = JSON.parse(await readFile(path.join(root, 'state.json'), 'utf8'));
  assert.equal(Object.keys(stored.receipts).length, 2);
}));

test('reordered batch retries retain original receipts without rebasing tombstones', async () => fixture(async (service, root, restart) => {
  const { workspaceId } = await (await request(service.url, 'manifest')).json();
  const digest = await put(service.url, 'immutable batch');
  const first = 'notebooks/book/pages/first.json';
  const second = 'notebooks/book/pages/second.json';
  const commit = operation(workspaceId, [first, second].map(name => ({ path: name, expectedVersion: 0, hash: digest })));
  const receipt = await (await request(service.url, 'commit', 'POST', commit)).json();
  assert.equal(receipt.revision, 1);
  assert.equal(Object.keys(receipt.files).length, 2);
  const deletion = operation(workspaceId, [{ path: first, expectedVersion: 1, hash: null }]);
  assert.equal((await request(service.url, 'commit', 'POST', deletion)).status, 200);
  for (const [expectedVersion, proposedHash] of [[0, digest], [1, digest], [1, null]]) {
    const stale = operation(workspaceId, [{ path: first, expectedVersion, hash: proposedHash }]);
    const response = await request(service.url, 'commit', 'POST', stale);
    assert.equal(response.status, 409);
    assert.equal((await response.json()).error, 'version_conflict');
  }
  const reordered = { ...commit, changes: [...commit.changes].reverse() };
  assert.deepEqual(await (await request(service.url, 'commit', 'POST', reordered)).json(), receipt);
  service = await restart();
  assert.deepEqual(await (await request(service.url, 'commit', 'POST', reordered)).json(), receipt);
  const manifest = await (await request(service.url, 'manifest')).json();
  assert.equal(manifest.revision, 2);
  assert.deepEqual(manifest.files[first], { hash: null, version: 2 });
  assert.deepEqual(manifest.files[second], { hash: digest, version: 1 });
}));

test('concurrent CAS accepts one writer; case aliases cannot cross Windows boundaries', async () => fixture(async service => {
  const { workspaceId } = await (await request(service.url, 'manifest')).json();
  const digest = await put(service.url, 'first');
  const changes = [{ path: 'notebooks/book/pages/page.json', hash: digest, expectedVersion: 0 }];
  const results = await Promise.all([request(service.url, 'commit', 'POST', operation(workspaceId, changes)), request(service.url, 'commit', 'POST', operation(workspaceId, changes))]);
  assert.deepEqual(results.map(response => response.status).sort(), [200, 409]);
  assert.equal((await request(service.url, 'commit', 'POST', operation(workspaceId, [{ ...changes[0], path: 'notebooks/BOOK/pages/page.json' }]))).status, 400);
}));

test('second process is refused without replacing state', async () => fixture(async (service, root) => {
  await assert.rejects(createSyncServer({ root, token }), /already in use/);
  assert.equal((await request(service.url, 'manifest')).status, 200);
}));

test('corrupt durable state is refused and preserved for recovery', async () => {
  const root = await temporary();
  try {
    await writeFile(path.join(root, 'state.json'), '{corrupt');
    await assert.rejects(createSyncServer({ root, token }));
    assert.equal(await readFile(path.join(root, 'state.json'), 'utf8'), '{corrupt');
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('shared v1 wire fixture exactly matches commits, receipts, replay and deletion', async () => {
  const fixture = JSON.parse(await readFile(new URL('./fixtures/v1-wire.json', import.meta.url), 'utf8'));
  const root = await temporary();
  let service;
  try {
    await writeFile(path.join(root, 'manifest.json'), JSON.stringify({ workspaceId: fixture.commit.workspaceId, revision: 0, files: {} }));
    service = await listen(root);
    assert.equal(hash(fixture.utf8), fixture.hash);
    await put(service.url, fixture.utf8);
    assert.deepEqual(await (await request(service.url, 'commit', 'POST', fixture.commit)).json(), fixture.receipt);
    assert.deepEqual(await (await request(service.url, 'commit', 'POST', fixture.deletion)).json(), fixture.deletionReceipt);
    assert.deepEqual(await (await request(service.url, 'commit', 'POST', fixture.commit)).json(), fixture.receipt);
  } finally { if (service) await service.close(); await rm(root, { recursive: true, force: true }); }
});

test('restart after actual process termination retains commit and removes stale owner', async () => {
  const root = await temporary();
  const childCode = `import {createSyncServer,prefix} from ${JSON.stringify(new URL('../server.mjs', import.meta.url).href)};const service=await createSyncServer({root:process.env.TEST_ROOT,token:process.env.TEST_TOKEN});service.server.listen(0,'127.0.0.1',()=>process.send({port:service.server.address().port}));`;
  const child = spawn(process.execPath, ['--input-type=module', '-e', childCode], { env: { ...process.env, TEST_ROOT: root, TEST_TOKEN: token }, stdio: ['ignore', 'pipe', 'pipe', 'ipc'] });
  let restarted;
  try {
    const [{ port }] = await once(child, 'message');
    const url = `http://127.0.0.1:${port}${prefix}/`;
    const { workspaceId } = await (await request(url, 'manifest')).json();
    const digest = await put(url, 'before kill');
    const commit = operation(workspaceId, [{ path: 'library.json', expectedVersion: 0, hash: digest }]);
    const receipt = await (await request(url, 'commit', 'POST', commit)).json();
    child.kill('SIGKILL');
    await once(child, 'exit');
    restarted = await listen(root);
    assert.deepEqual(await (await request(restarted.url, 'commit', 'POST', commit)).json(), receipt);
  } finally {
    if (child.exitCode === null && child.signalCode === null) { child.kill('SIGKILL'); await once(child, 'exit'); }
    if (restarted) await restarted.close();
    await rm(root, { recursive: true, force: true });
  }
});

test('Windows client isolated integration against the real protocol', { skip: !process.env.MOJIAN_NATIVE_SYNC_TEST_DLL }, async () => fixture(async service => {
  const child = spawn('dotnet', [process.env.MOJIAN_NATIVE_SYNC_TEST_DLL], { env: { ...process.env, MOJIAN_TEST_SYNC_URL: service.url, MOJIAN_TEST_SYNC_TOKEN: token }, stdio: ['ignore', 'pipe', 'pipe'] });
  let output = '';
  child.stdout.on('data', chunk => { output += chunk; });
  child.stderr.on('data', chunk => { output += chunk; });
  const [code] = await once(child, 'exit');
  assert.equal(code, 0, output);
  console.log(output.trim());
}));
