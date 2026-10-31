import path from 'node:path';
import { readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import { createSyncServer } from './server.mjs';
import { listenDirectTls } from './direct-tls.mjs';

export async function startConfigured(configPath) {
  if (!configPath) throw new Error('A private configuration file is required');
  let configuration;
  try { configuration = JSON.parse(await readFile(configPath, 'utf8')); }
  catch { throw new Error('The private configuration could not be read or parsed'); }
  if (!configuration || !Number.isInteger(configuration.port) || configuration.port < 1 || configuration.port > 65535) {
    throw new Error('Invalid loopback port');
  }
  if (typeof configuration.dataRoot !== 'string' || !configuration.dataRoot) {
    throw new Error('A dedicated data directory is required');
  }
  const service = await createSyncServer({ root: configuration.dataRoot, token: configuration.token });
  let direct;
  try {
    await new Promise((resolve, reject) => {
      service.server.once('error', reject);
      service.server.listen(configuration.port, '127.0.0.1', resolve);
    });
    direct = await listenDirectTls(service.server, configuration.directTls);
  } catch (error) {
    await service.close();
    throw error;
  }
  let closing;
  return {
    ...service, direct,
    close: () => closing ??= (async () => {
      // The TLS listener delegates into the same core. Drain it before releasing
      // the core's writer lock; do not destroy an accepted upload or commit.
      if (direct?.listening) {
        await new Promise((resolve, reject) => direct.close(error => error ? reject(error) : resolve()));
      }
      await service.close();
    })(),
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  if (process.argv.includes('--version')) {
    const metadata = JSON.parse(await readFile(new URL('./package.json', import.meta.url), 'utf8'));
    console.log(`Mojian Sync Server ${metadata.version}`);
  } else {
    const service = await startConfigured(process.argv[2]);
    console.log(`Mojian sync ready on loopback:${service.server.address().port}`);
    for (const signal of ['SIGTERM', 'SIGINT']) process.once(signal, () => {
      void service.close().then(() => process.exit(0));
    });
  }
}
