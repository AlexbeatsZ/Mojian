import { spawn } from 'node:child_process';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('.', import.meta.url));
const parent = path.join(os.tmpdir(), '.agents');
await mkdir(parent, { recursive: true });
const artifacts = await mkdtemp(path.join(parent, 'mojian-native-sync-build-'));
function run(command, args, env = process.env) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd: root, env, stdio: 'inherit' });
    child.on('error', reject);
    child.on('exit', code => code === 0 ? resolve() : reject(new Error(`${command} exited ${code}`)));
  });
}
try {
  await run('dotnet', ['build', path.join(root, '../mojian/tests/NativeSync.Tests/NativeSync.Tests.csproj'), '--artifacts-path', artifacts, '-v', 'minimal']);
  await run(process.execPath, ['--test', path.join(root, 'test/server.test.mjs')], {
    ...process.env, MOJIAN_NATIVE_SYNC_TEST_DLL: path.join(artifacts, 'bin/NativeSync.Tests/debug/NativeSync.Tests.dll'),
  });
} finally { await rm(artifacts, { recursive: true, force: true }); }
