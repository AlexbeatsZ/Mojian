import { readFile, readdir, mkdir, writeFile, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { execFileSync } from 'node:child_process';
import { zipSync } from 'fflate';

const source = fileURLToPath(new URL('../', import.meta.url));
const repository = path.resolve(source, '..');
const runtimeFiles = [
  'server.mjs', 'protocol.mjs', 'run-configured.mjs', 'direct-tls.mjs',
  'gateway-route.mjs', 'shared-gateway.mjs', 'package.json', 'server.example.json',
  'README.md', 'PROTOCOL.md', 'LICENSE',
];

function git(...args) {
  return execFileSync('git', ['-C', repository, ...args], { encoding: 'utf8' }).trim();
}

async function coreDirectory() {
  let directory = path.dirname(fileURLToPath(import.meta.resolve('@alexbeatsz/file-sync-core')));
  for (let depth = 0; depth < 5; depth++) {
    try {
      const metadata = JSON.parse(await readFile(path.join(directory, 'package.json'), 'utf8'));
      if (metadata.name === '@alexbeatsz/file-sync-core') return { directory, metadata };
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
    }
    directory = path.dirname(directory);
  }
  throw new Error('Installed file-sync-core package root was not found');
}

async function addDirectory(entries, directory, prefix) {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    if (entry.name === 'node_modules' || entry.name.startsWith('.')) continue;
    const fullPath = path.join(directory, entry.name);
    const name = `${prefix}/${entry.name}`;
    if (entry.isDirectory()) await addDirectory(entries, fullPath, name);
    else if (entry.isFile()) entries[name] = new Uint8Array(await readFile(fullPath));
    else throw new Error('Server runtime package may not contain symbolic links');
  }
}

export async function packageServer({ outputDirectory = path.join(source, 'artifacts'), allowDirty = false } = {}) {
  const metadata = JSON.parse(await readFile(path.join(source, 'package.json'), 'utf8'));
  const lock = JSON.parse(await readFile(path.join(source, 'package-lock.json'), 'utf8'));
  const dependency = lock.packages['node_modules/@alexbeatsz/file-sync-core'];
  if (!dependency?.resolved || !dependency.integrity || dependency.link) {
    throw new Error('file-sync-core must be an installed, locked, integrity-checked release dependency');
  }
  const dirty = git('status', '--porcelain', '--', 'sync-server');
  if (dirty && !allowDirty) throw new Error('Commit server source before building a release (tests may use --allow-dirty)');
  const core = await coreDirectory();
  if (core.metadata.version !== dependency.version) throw new Error('Installed core does not match package-lock.json');
  const buildInfo = {
    name: metadata.name,
    version: metadata.version,
    sourceRevision: git('rev-parse', 'HEAD'),
    sourceDirty: Boolean(dirty),
    node: metadata.engines.node,
    core: {
      name: core.metadata.name, version: core.metadata.version,
      resolved: dependency.resolved, integrity: dependency.integrity,
    },
  };
  const entries = {};
  for (const name of runtimeFiles) entries[name] = new Uint8Array(await readFile(path.join(source, name)));
  await addDirectory(entries, core.directory, 'node_modules/@alexbeatsz/file-sync-core');
  entries['BUILD-INFO.json'] = new TextEncoder().encode(`${JSON.stringify(buildInfo, null, 2)}\n`);
  const archive = zipSync(entries, { level: 6 });
  await mkdir(outputDirectory, { recursive: true });
  const archivePath = path.join(outputDirectory, `Mojian-Sync-Server-${metadata.version}.zip`);
  const temporary = `${archivePath}.tmp`;
  try {
    await writeFile(temporary, archive);
    // Only the single artifact owned by this build is replaced; no data/config directory is touched.
    const { rename } = await import('node:fs/promises');
    await rename(temporary, archivePath);
  } finally {
    await rm(temporary, { force: true });
  }
  return { archivePath, bytes: archive.length, buildInfo };
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  console.log(JSON.stringify(await packageServer({ allowDirty: process.argv.includes('--allow-dirty') }), null, 2));
}
