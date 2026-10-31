import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, writeFile, mkdir, mkdtemp, rm } from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import net from 'node:net';
import { once } from 'node:events';
import { pathToFileURL } from 'node:url';
import { execFileSync } from 'node:child_process';
import { unzipSync } from 'fflate';
import { packageServer } from '../scripts/package.mjs';
import { startConfigured } from '../run-configured.mjs';
import { createSyncServer } from '../server.mjs';

const token = 'isolated-package-test-token-not-for-production';
const parent = path.join(os.tmpdir(), '.agents');
async function temporary() {
  await mkdir(parent, { recursive: true });
  return mkdtemp(path.join(parent, 'mojian-server-package-'));
}
async function freePort() {
  const server = net.createServer();
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}
async function extract(archivePath, directory) {
  const entries = unzipSync(new Uint8Array(await readFile(archivePath)));
  for (const [name, bytes] of Object.entries(entries)) {
    assert.ok(!name.startsWith('/') && !name.split('/').includes('..'), name);
    const target = path.join(directory, ...name.split('/'));
    await mkdir(path.dirname(target), { recursive: true });
    await writeFile(target, bytes);
  }
  return Object.keys(entries);
}

test('standalone ZIP runs outside the checkout and preserves old-server receipt replay across restart', async () => {
  const directory = await temporary();
  let service;
  try {
    const packaged = await packageServer({ outputDirectory: directory, allowDirty: true });
    const runtime = path.join(directory, 'runtime');
    const names = await extract(packaged.archivePath, runtime);
    assert.ok(names.includes('node_modules/@alexbeatsz/file-sync-core/package.json'));
    assert.ok(!names.some(name => /(^|\/)(test|tests|artifacts|\.local|fflate)(\/|$)/.test(name)));
    assert.equal(names.filter(name => name.endsWith('package.json') && name.startsWith('node_modules/')).length, 1);
    const info = JSON.parse(await readFile(path.join(runtime, 'BUILD-INFO.json'), 'utf8'));
    assert.equal(info.version, '0.1.0');
    assert.equal(info.core.version, packaged.buildInfo.core.version);
    assert.match(info.sourceRevision, /^[a-f0-9]{40}$/);
    assert.equal(
      execFileSync(process.execPath, [path.join(runtime, 'run-configured.mjs'), '--version'], { cwd: directory, encoding: 'utf8' }).trim(),
      'Mojian Sync Server 0.1.0',
    );
    const fixture = JSON.parse(await readFile(new URL('./fixtures/v1-wire.json', import.meta.url), 'utf8'));
    const data = path.join(directory, 'existing-data');
    await mkdir(data);
    // Captured from the original dc63bcea server with the shared synthetic wire fixture.
    // This includes two pre-existing receipts and a tombstone at revision 2.
    const legacy = await readFile(new URL('./fixtures/legacy-state.json', import.meta.url), 'utf8');
    await writeFile(path.join(data, 'state.json'), legacy);
    await mkdir(path.join(data, 'blobs'));
    await writeFile(path.join(data, 'blobs', fixture.hash), fixture.utf8);
    const configPath = path.join(directory, 'private-config.json');
    await writeFile(configPath, JSON.stringify({ port: await freePort(), dataRoot: data, token }));
    const { startConfigured: startPackaged } = await import(pathToFileURL(path.join(runtime, 'run-configured.mjs')));
    service = await startPackaged(configPath);
    const url = `http://127.0.0.1:${service.server.address().port}/mojian-sync/v1/`;
    const headers = { Authorization: `Bearer ${token}` };
    assert.equal(await readFile(path.join(data, 'state.json'), 'utf8'), legacy, 'startup must preserve original state bytes');
    const commit = () => fetch(`${url}commit`, { method: 'POST', headers, body: JSON.stringify(fixture.commit) });
    assert.deepEqual(await (await commit()).json(), fixture.receipt);
    const state = await readFile(path.join(data, 'state.json'), 'utf8');
    await service.close();
    service = await startPackaged(configPath);
    assert.equal(await readFile(path.join(data, 'state.json'), 'utf8'), state, 'startup must not rewrite existing state');
    assert.deepEqual(await (await commit()).json(), fixture.receipt);
    assert.equal(await (await fetch(`${url}blobs/${fixture.hash}`, { headers })).text(), fixture.utf8);
    assert.equal(await readFile(path.join(data, 'state.json'), 'utf8'), legacy, 'receipt replay must not rewrite or advance state');
    await Promise.all([service.close(), service.close()]);
    service = undefined;
  } finally {
    if (service) await service.close();
    await rm(directory, { recursive: true, force: true });
  }
});

test('failed configured listener startup releases its data-root lock', async () => {
  const directory = await temporary();
  const blocker = net.createServer();
  blocker.listen(0, '127.0.0.1');
  await once(blocker, 'listening');
  let service;
  try {
    const root = path.join(directory, 'data');
    const config = path.join(directory, 'server.json');
    await writeFile(config, JSON.stringify({ port: blocker.address().port, dataRoot: root, token }));
    await assert.rejects(startConfigured(config), { code: 'EADDRINUSE' });
    service = await createSyncServer({ root, token });
  } finally {
    if (service) await service.close();
    await new Promise(resolve => blocker.close(resolve));
    await rm(directory, { recursive: true, force: true });
  }
});
