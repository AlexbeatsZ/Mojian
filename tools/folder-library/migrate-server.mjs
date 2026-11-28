#!/usr/bin/env node
// Migrate the live server library from v2 to the v3 folder library in one atomic commit.
// The server manifest is authoritative: a local v2 replica is reused only where its bytes match the manifest.
// Usage: node migrate-server.mjs --config <sync-config.json> --address <url> --work <empty dir> [--replica <v2 root>] [--apply]
// Without --apply it builds, converts and verifies, then prints what the commit would contain.
import { createHash, randomUUID, X509Certificate } from 'node:crypto';
import { promises as fs } from 'node:fs';
import https from 'node:https';
import path from 'node:path';
import { convert } from './convert.mjs';

const args = Object.fromEntries(process.argv.slice(2).reduce((pairs, value, index, all) =>
  value.startsWith('--') ? [...pairs, [value.slice(2), all[index + 1]?.startsWith('--') || all[index + 1] === undefined ? true : all[index + 1]]] : pairs, []));
const config = JSON.parse(await fs.readFile(args.config, 'utf8'));
const address = args.address ?? config.addresses[0];
const pin = config.certificatePins?.[address];
const work = path.resolve(args.work);
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const legacy = /^(library\.json|notebooks\/[A-Za-z0-9_-][A-Za-z0-9._-]*\/(pages\/[A-Za-z0-9_-][A-Za-z0-9._-]*\.json|assets\/[A-Za-z0-9_-][A-Za-z0-9._-]*\/[A-Za-z0-9_-][A-Za-z0-9._-]*))$/;

function request(resource, { method = 'GET', body, type } = {}) {
  const url = new URL(resource, address);
  return new Promise((resolve, reject) => {
    const req = https.request(url, {
      method, headers: { Authorization: `Bearer ${config.token}`, ...(type ? { 'Content-Type': type } : {}) },
      // Self-signed LAN/Tailnet addresses are trusted only through the configured SHA-256 certificate pin.
      ...(pin ? { rejectUnauthorized: false, checkServerIdentity: () => undefined } : {}),
    }, response => {
      if (pin) {
        const certificate = response.socket.getPeerX509Certificate?.() ?? new X509Certificate(response.socket.getPeerCertificate(true).raw);
        if (sha256(certificate.raw) !== pin) { response.destroy(); reject(new Error('certificate pin mismatch')); return; }
      }
      const chunks = [];
      response.on('data', chunk => chunks.push(chunk));
      response.on('end', () => {
        const bytes = Buffer.concat(chunks);
        if (response.statusCode >= 300) reject(Object.assign(new Error(`${method} ${resource}: HTTP ${response.statusCode} ${bytes.toString('utf8').slice(0, 200)}`), { status: response.statusCode }));
        else resolve(bytes);
      });
    });
    req.on('error', reject);
    req.end(body);
  });
}

async function walk(root, relative = '') {
  const items = [];
  for (const entry of await fs.readdir(path.join(root, relative), { withFileTypes: true })) {
    const name = relative ? `${relative}/${entry.name}` : entry.name;
    if (entry.isDirectory()) items.push({ name, directory: true }, ...await walk(root, name));
    else items.push({ name, directory: false });
  }
  return items;
}

await fs.mkdir(work, { recursive: true });
if ((await fs.readdir(work)).length) throw new Error(`work directory is not empty: ${work}`);
const manifest = JSON.parse((await request('manifest')).toString('utf8'));
const live = Object.entries(manifest.files).filter(([, entry]) => entry.hash !== null || entry.directory);
const foreign = live.filter(([name]) => !legacy.test(name));
if (foreign.length) throw new Error(`server already holds non-v2 items, refusing: ${foreign.slice(0, 5).map(([name]) => name).join(', ')}`);
console.log(JSON.stringify({ workspaceId: manifest.workspaceId, revision: manifest.revision, liveV2: live.length }));

// Rebuild the exact server v2 tree, reusing replica bytes whose digest matches.
const v2 = path.join(work, 'v2');
let reused = 0, downloaded = 0;
for (const [name, entry] of live) {
  const target = path.join(v2, name);
  await fs.mkdir(path.dirname(target), { recursive: true });
  const local = args.replica ? await fs.readFile(path.join(args.replica, name)).catch(() => null) : null;
  if (local && sha256(local) === entry.hash) { await fs.writeFile(target, local); reused++; continue; }
  const bytes = await request(`blobs/${entry.hash}`);
  if (sha256(bytes) !== entry.hash) throw new Error(`downloaded bytes differ for ${name}`);
  await fs.writeFile(target, bytes); downloaded++;
}
console.log(JSON.stringify({ reused, downloaded }));

const v3 = path.join(work, 'v3');
const report = await convert(v2, v3);
await fs.writeFile(path.join(work, 'report.json'), JSON.stringify(report, null, 2));
console.log(JSON.stringify({ pages: report.pages.length, folders: report.folders.length, assets: report.assets.length,
  missingPages: report.missingPages.length, unfiled: report.orphanPages.length }));
if (report.missingPages.length) throw new Error('library lists pages without content; refusing to migrate');

const known = new Set(live.map(([, entry]) => entry.hash));
const changes = [];
const uploads = new Map();
for (const item of await walk(v3)) {
  const expectedVersion = manifest.files[item.name]?.version ?? 0;
  if (item.directory) { changes.push({ path: item.name, expectedVersion, hash: null, directory: true }); continue; }
  const bytes = await fs.readFile(path.join(v3, item.name));
  const hash = sha256(bytes);
  if (!known.has(hash)) uploads.set(hash, bytes);
  changes.push({ path: item.name, expectedVersion, hash });
}
for (const [name, entry] of live) changes.push({ path: name, expectedVersion: entry.version, hash: null });
console.log(JSON.stringify({ changes: changes.length, directories: changes.filter(c => c.directory).length,
  files: changes.filter(c => c.hash).length, tombstones: live.length, newBlobs: uploads.size }));
if (changes.length > 20000) throw new Error('commit exceeds 20000 changes');
if (!args.apply) { console.log('dry run: nothing uploaded or committed'); process.exit(0); }

for (const [hash, bytes] of uploads) await request(`blobs/${hash}`, { method: 'PUT', body: bytes });
const operation = { workspaceId: manifest.workspaceId, deviceId: 'folder-library-migration-v3', operationId: randomUUID(), changes };
await fs.writeFile(path.join(work, 'operation.json'), JSON.stringify(operation));
const receipt = JSON.parse((await request('commit', { method: 'POST', body: JSON.stringify(operation), type: 'application/json' })).toString('utf8'));
console.log(JSON.stringify({ committedRevision: receipt.revision, files: Object.keys(receipt.files).length }));
