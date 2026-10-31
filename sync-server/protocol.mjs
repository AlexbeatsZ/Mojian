import { hashPattern, idPattern } from '@alexbeatsz/file-sync-core';

export const prefix = '/mojian-sync/v1';
const reserved = /^(con|prn|aux|nul|com[0-9]|lpt[0-9])(?:\.|$)/i;

export function validPath(value) {
  return typeof value === 'string' && value.length < 500 &&
    (value === 'library.json' || /^notebooks\/[A-Za-z0-9_-][A-Za-z0-9._-]*\/(pages\/[A-Za-z0-9_-][A-Za-z0-9._-]*\.json|assets\/[A-Za-z0-9_-][A-Za-z0-9._-]*\/[A-Za-z0-9_-][A-Za-z0-9._-]*)$/.test(value)) &&
    !value.endsWith('.previous') && !value.endsWith('.tmp') && !value.includes('-sync-conflict-') &&
    value.split('/').every(component => !component.endsWith('.') && !reserved.test(component)) && !/[\r\n]/.test(value);
}

export function validateManifest(manifest) {
  if (!manifest || typeof manifest.workspaceId !== 'string' || !idPattern.test(manifest.workspaceId) ||
      !Number.isSafeInteger(manifest.revision) || manifest.revision < 0 ||
      !manifest.files || typeof manifest.files !== 'object' || Array.isArray(manifest.files)) {
    throw new Error('Invalid stored manifest');
  }
  const paths = new Set();
  for (const [name, entry] of Object.entries(manifest.files)) {
    if (!validPath(name) || paths.has(name.toLowerCase()) || !entry || !Number.isSafeInteger(entry.version) ||
        entry.version <= 0 || entry.version > manifest.revision ||
        (entry.hash !== null && (typeof entry.hash !== 'string' || !hashPattern.test(entry.hash)))) {
      throw new Error('Invalid stored entry');
    }
    paths.add(name.toLowerCase());
  }
}
