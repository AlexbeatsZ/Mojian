import https from 'node:https';
import { readFile } from 'node:fs/promises';

// An optional extra listener for the same server and data root, never a second sync database.
export async function listenDirectTls(backend, configuration) {
  if (!configuration) return null;
  if (!Number.isInteger(configuration.port) || configuration.port < 1 || configuration.port > 65535)
    throw new Error('Invalid additional HTTPS listener port');
  const server = https.createServer({
    key: await readFile(configuration.keyFile), cert: await readFile(configuration.certificateFile), minVersion: 'TLSv1.2',
  }, (request, response) => backend.emit('request', request, response));
  server.requestTimeout = 10 * 60 * 1000;
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(configuration.port, configuration.host ?? '0.0.0.0', resolve);
  });
  return server;
}
