import { hashPattern, idPattern } from '@alexbeatsz/file-sync-core';

export const prefix = '/mojian-sync/v1';
const reserved = /^(con|prn|aux|nul|com[0-9]|lpt[0-9])(?:\.|$)/i;

// v2 layout. Clients no longer create it, but the server keeps its tombstones forever.
const legacyPath = /^(library\.json|notebooks\/[A-Za-z0-9_-][A-Za-z0-9._-]*\/(pages\/[A-Za-z0-9_-][A-Za-z0-9._-]*\.json|assets\/[A-Za-z0-9_-][A-Za-z0-9._-]*\/[A-Za-z0-9_-][A-Za-z0-9._-]*))$/;
const storableName = part => !!part && part !== '.' && part !== '..' && part.length <= 240 &&
  !/[\\:*?"<>|\x00-\x1f\x7f]/.test(part) && !/[. ]$/.test(part) && !reserved.test(part);

/**
 * Folder library v3: real notebook/section folders, `<title>.mojian` pages, one `.mojian-folder.json` per
 * folder and page assets under `.mojian/assets/<page-id>/`. Paths name both files and folder items.
 */
export function validPath(value) {
  if (typeof value !== 'string' || !value || value.length >= 1000 || /[\r\n]/.test(value) ||
      value.endsWith('.previous') || value.endsWith('.tmp') || value.includes('.tmp-') || value.includes('-sync-conflict-')) return false;
  const parts = value.split('/');
  if (!parts.every(storableName)) return false;
  if (legacyPath.test(value)) return true;
  if (parts[0] === '.mojian') return parts.length <= 4 && (parts.length < 2 || parts[1] === 'assets');
  // Hidden names other than the folder descriptor (.DS_Store, .git, .trash…) never synchronize.
  return parts.every(part => !part.startsWith('.') || part === '.mojian-folder.json');
}

const isLive = entry => entry.hash !== null || entry.directory === true;
const pageFile = name => name.endsWith('.mojian') || name.endsWith('/.mojian-folder.json') || name.startsWith('.mojian/assets/') || legacyPath.test(name);

export function validateManifest(manifest) {
  if (!manifest || typeof manifest.workspaceId !== 'string' || !idPattern.test(manifest.workspaceId) ||
      !Number.isSafeInteger(manifest.revision) || manifest.revision < 0 ||
      !manifest.files || typeof manifest.files !== 'object' || Array.isArray(manifest.files)) {
    throw new Error('Invalid stored manifest');
  }
  // Like Syncthing, only existing items must be unique ignoring case; a tombstone keeps its spelling.
  const live = new Map();
  for (const [name, entry] of Object.entries(manifest.files)) {
    if (!validPath(name) || !entry || !Number.isSafeInteger(entry.version) ||
        entry.version <= 0 || entry.version > manifest.revision ||
        (entry.hash !== null && (typeof entry.hash !== 'string' || !hashPattern.test(entry.hash))) ||
        (entry.directory !== undefined && (entry.directory !== true || entry.hash !== null))) {
      throw new Error('Invalid stored entry');
    }
    if (!isLive(entry)) continue;
    if (live.has(name.toLowerCase()) || (entry.hash !== null && !pageFile(name))) throw new Error('Invalid stored entry');
    live.set(name.toLowerCase(), entry.directory === true);
  }
  for (const name of live.keys()) {
    const parts = name.split('/'); parts.pop();
    while (parts.length) { if (live.get(parts.join('/')) === false) throw new Error('Sync file/directory collision'); parts.pop(); }
  }
}
