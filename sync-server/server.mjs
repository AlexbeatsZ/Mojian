import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { createFileSyncServer } from '@alexbeatsz/file-sync-core';
import { prefix, validPath, validateManifest } from './protocol.mjs';

export { prefix, validPath } from './protocol.mjs';

// Mojian owns its wire namespace and allowed notebook paths. Persistence, blobs,
// exact-version commits and durable replay are owned by the pinned shared core.
export function createSyncServer({ root, token, maxBlobBytes = 1024 * 1024 * 1024 }) {
  return createFileSyncServer({
    root, token, maxBlobBytes, routePrefix: prefix, validPath, validateManifest,
    validateToken(value) {
      if (typeof value !== 'string' || Buffer.byteLength(value) < 32 || /[\r\n]/.test(value)) {
        throw new Error('MOJIAN_SYNC_TOKEN must contain at least 32 bytes');
      }
    },
    onError: ({ code }) => console.error('Mojian sync request failed:', code),
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const service = await createSyncServer({
    root: path.resolve(process.env.MOJIAN_SYNC_DATA ?? './data'),
    token: process.env.MOJIAN_SYNC_TOKEN,
  });
  const port = Number(process.env.MOJIAN_SYNC_PORT ?? 18742);
  service.server.listen(port, '127.0.0.1', () => console.log(`Mojian sync listening on 127.0.0.1:${port}${prefix}`));
  for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => service.close().then(() => process.exit(0)));
}
