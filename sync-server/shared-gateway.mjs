import { pathToFileURL } from 'node:url';
import { routeMojian } from './gateway-route.mjs';

if (!process.argv[2]) throw new Error('The existing shared gateway module is required');
const { createGateway } = await import(pathToFileURL(process.argv[2]).href);
const gateway = createGateway();
const listeners = gateway.server.listeners('request');
if (listeners.length !== 1) throw new Error('Unexpected shared gateway handler; no service was started');
gateway.server.removeListener('request', listeners[0]);
gateway.server.on('request', (request, response) => {
  if (!routeMojian(request, response)) listeners[0].call(gateway.server, request, response);
});
gateway.server.requestTimeout = 10 * 60 * 1000;
await gateway.listen();
console.log('Shared Sakura HTTPS gateway ready on loopback:18781');
for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => {
  gateway.server.close(() => process.exit(0));
  gateway.server.closeAllConnections();
});
