import { readFile } from 'node:fs/promises';
import { createSyncServer } from './server.mjs';

const configPath = process.argv[2];
if (!configPath) throw new Error('A private configuration file is required');
let configuration;
try { configuration = JSON.parse(await readFile(configPath, 'utf8')); }
catch { throw new Error('The private configuration could not be read or parsed'); }
if (!Number.isInteger(configuration.port) || configuration.port < 1 || configuration.port > 65535)
  throw new Error('Invalid loopback port');
if (!configuration.dataRoot) throw new Error('A dedicated data directory is required');
const service = await createSyncServer({ root: configuration.dataRoot, token: configuration.token });
await new Promise((resolve, reject) => {
  service.server.once('error', reject);
  service.server.listen(configuration.port, '127.0.0.1', resolve);
});
console.log(`Mojian sync ready on loopback:${configuration.port}`);
for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => { void service.close().then(() => process.exit(0)); });
