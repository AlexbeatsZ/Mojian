// Isolated protocol acceptance fixture. Mutations stay in the supplied temporary service root.
import { createSyncServer } from '../../sync-server/server.mjs';
import { createHash, randomUUID } from 'node:crypto';
import { once } from 'node:events';
import { createInterface } from 'node:readline';
const token = 'isolated-readonly-mac-fixture-token';
const service = await createSyncServer({ root: process.argv[2], token });
service.server.listen(0, '127.0.0.1');
await once(service.server, 'listening');
const endpoint = `http://127.0.0.1:${service.server.address().port}/mojian-sync/v1/`;
const requests = [];
service.server.on('request', request => requests.push([request.method, request.url]));
const DIRECTORY = Symbol('directory');
const request = (path, method = 'GET', body) => fetch(endpoint + path, {
  method, headers: { Authorization: `Bearer ${token}` }, body,
});
async function update(changes) {
  const manifest = await (await request('manifest')).json();
  const operations = [];
  for (const [path, content] of Object.entries(changes)) {
    const directory = content === DIRECTORY;
    const hash = content === null || directory ? null : createHash('sha256').update(content).digest('hex');
    if (hash) {
      const response = await request('blobs/' + hash, 'PUT', content);
      if (!response.ok) throw new Error(`Fixture upload ${response.status}`);
    }
    operations.push({ path, hash, ...(directory ? { directory: true } : {}), expectedVersion: manifest.files[path]?.version ?? 0 });
  }
  const response = await request('commit', 'POST', JSON.stringify({
    workspaceId: manifest.workspaceId, deviceId: 'readonly-fixture-device', operationId: randomUUID(), changes: operations,
  }));
  if (!response.ok) throw new Error(`Fixture commit ${response.status}`);
  requests.length = 0;
  return manifest.workspaceId;
}
const workspaceId = await update({ '测试本': DIRECTORY, '测试本/.mojian-folder.json': 'first-index', '测试本/分区': DIRECTORY,
  '测试本/分区/页面.mojian': 'first-page' });
console.log(JSON.stringify({ endpoint, token, workspaceId, enabled: true, allowInitialReplacement: true }));
const lines = createInterface({ input: process.stdin });
for await (const line of lines) {
  if (line === 'update') {
    await update({ '测试本/.mojian-folder.json': 'second-index', '测试本/分区/页面.mojian': null, '测试本/分区': null });
    console.log('updated');
  } else if (line === 'requests') console.log(JSON.stringify(requests));
  else if (line === 'close') break;
}
await service.close();
