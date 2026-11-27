import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { lifecycleCli } from '@alexbeatsz/file-sync-core';

try {
  const [file, ...args] = process.argv.slice(2);
  if (!file) throw new Error('Usage: node admin.mjs CONFIG status|cleanup|backup [--apply]|backup-list|restore ID --destination NEW_DIR');
  const c = JSON.parse(await readFile(file, 'utf8'));
  const result = await lifecycleCli({ root: path.resolve(c.dataRoot), token: c.token,
    baseUrl: `http://127.0.0.1:${c.port ?? 18742}/mojian-sync/v1/`,
    configFile: path.resolve(file), configuration: { backup: c.backup, history: c.history, garbageCollection: c.garbageCollection } }, args);
  console.log(JSON.stringify(result, null, 2));
} catch (e) { console.error('Mojian lifecycle:', e.message); process.exitCode = 1; }
