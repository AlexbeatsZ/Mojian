#!/usr/bin/env node
// Convert a canonical v2 Mojian library (library.json + notebooks/<id>/pages|assets) into the v3 folder
// library described in docs/design/folder-library.md, then verify the result against the source.
// Usage: node convert.mjs <v2-root> <empty-output-directory>
import { createHash } from 'node:crypto';
import { promises as fs } from 'node:fs';
import path from 'node:path';
import { keysAfter } from './fractional-index.mjs';

const FULL_WIDTH = { '\\': '＼', '/': '／', ':': '：', '*': '＊', '?': '？', '"': '＂', '<': '＜', '>': '＞', '|': '｜' };
const RESERVED = /^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\..*)?$/i;
export const PAGE_EXTENSION = '.mojian';
export const FOLDER_DESCRIPTOR = '.mojian-folder.json';

/** The storable name for a title; every client applies exactly these rules. */
export function storableName(title) {
  let name = [...String(title ?? '')].filter(ch => ch.charCodeAt(0) >= 0x20 && ch !== '\u007f').map(ch => FULL_WIDTH[ch] ?? ch).join('').trim();
  name = name.replace(/^\./, '．').replace(/\.$/, '．');
  if (!name) name = '无标题';
  if (RESERVED.test(name)) name += '_';
  const chars = [...name];
  return chars.length > 120 ? chars.slice(0, 120).join('').trim() : name;
}

/** Explorer-style de-duplication inside one folder, case-insensitive across pages and folders. */
function uniqueName(base, suffix, taken) {
  for (let n = 1; ; n++) {
    const candidate = (n === 1 ? base : `${base} (${n})`) + suffix;
    if (!taken.has(candidate.toLowerCase())) { taken.add(candidate.toLowerCase()); return candidate; }
  }
}

const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const pick = (object, omitted) => Object.fromEntries(Object.entries(object).filter(([key]) => !omitted.includes(key)));
const stable = value => JSON.stringify(value, (key, nested) => nested && typeof nested === 'object' && !Array.isArray(nested)
  ? Object.fromEntries(Object.keys(nested).sort().map(name => [name, nested[name]])) : nested);

async function exists(file) { try { await fs.access(file); return true; } catch { return false; } }

export async function convert(source, output) {
  if (await exists(output) && (await fs.readdir(output)).length) throw new Error(`output is not empty: ${output}`);
  const library = JSON.parse(await fs.readFile(path.join(source, 'library.json'), 'utf8'));
  if (library.version !== 2 || !Array.isArray(library.notebooks)) throw new Error('source is not a v2 library');
  const report = { pages: [], folders: [], assets: [], orphanPages: [], missingPages: [] };
  const writeJson = async (file, value) => { await fs.mkdir(path.dirname(file), { recursive: true }); await fs.writeFile(file, JSON.stringify(value, null, 2) + '\n'); };
  const listed = new Set();

  const writeFolder = async (directory, item, sortKey, omitted, relative) => {
    await fs.mkdir(directory, { recursive: true });
    await writeJson(path.join(directory, FOLDER_DESCRIPTOR), { version: 3, id: item.id, sortKey, ...pick(item, ['id', 'title', ...omitted]) });
    report.folders.push({ id: item.id, title: item.title, path: relative });
  };

  const writeSection = async (notebook, section, directory, relative, sortKey) => {
    await writeFolder(directory, section, sortKey, ['pages', 'children'], relative);
    const taken = new Set([FOLDER_DESCRIPTOR]);
    const children = section.children ?? [];
    const childKeys = keysAfter(null, children.length);
    const childNames = children.map(child => uniqueName(storableName(child.title), '', taken));
    const pageKeys = keysAfter(null, section.pages.length);
    for (const [index, page] of section.pages.entries()) {
      const name = uniqueName(storableName(page.title), PAGE_EXTENSION, taken);
      const contentFile = path.join(source, 'notebooks', notebook.id, 'pages', `${page.id}.json`);
      listed.add(contentFile);
      if (!await exists(contentFile)) { report.missingPages.push({ notebook: notebook.id, page: page.id, title: page.title }); continue; }
      const content = JSON.parse(await fs.readFile(contentFile, 'utf8'));
      if (content.pageId !== page.id || content.notebookId !== notebook.id) throw new Error(`page identity mismatch: ${contentFile}`);
      const metadata = pick(page, ['id', 'title']);
      const document = { version: 3, id: page.id, sortKey: pageKeys[index], ...metadata, ...pick(content, ['version', 'notebookId', 'pageId']) };
      for (const key of Object.keys(metadata)) if (key in content && key !== 'updatedAt' && stable(content[key]) !== stable(metadata[key]))
        throw new Error(`page ${page.id} has different ${key} in library and page file`);
      if ('updatedAt' in content) document.updatedAt = content.updatedAt;
      await writeJson(path.join(directory, name), document);
      report.pages.push({ id: page.id, notebook: notebook.id, title: page.title, path: `${relative}/${name}`, source: path.relative(source, contentFile) });
    }
    for (const [index, child] of children.entries())
      await writeSection(notebook, child, path.join(directory, childNames[index]), `${relative}/${childNames[index]}`, childKeys[index]);
  };

  const rootTaken = new Set(['.mojian']);
  const notebookKeys = keysAfter(null, library.notebooks.length);
  for (const [index, notebook] of library.notebooks.entries()) {
    const name = uniqueName(storableName(notebook.title), '', rootTaken);
    const directory = path.join(output, name);
    await writeFolder(directory, notebook, notebookKeys[index], ['sections'], name);
    const taken = new Set([FOLDER_DESCRIPTOR]);
    const sectionKeys = keysAfter(null, notebook.sections.length);
    for (const [sectionIndex, section] of notebook.sections.entries()) {
      const sectionName = uniqueName(storableName(section.title), '', taken);
      await writeSection(notebook, section, path.join(directory, sectionName), `${name}/${sectionName}`, sectionKeys[sectionIndex]);
    }
  }

  // Page files the directory never listed (old conflict copies, interrupted deletes) are kept, not dropped:
  // they go to an 未归档页面 section of their notebook so the user can review them.
  for (const notebook of library.notebooks) {
    const pages = path.join(source, 'notebooks', notebook.id, 'pages');
    const orphans = (await fs.readdir(pages).catch(() => [])).filter(file => file.endsWith('.json') && !listed.has(path.join(pages, file))).sort();
    if (!orphans.length) continue;
    const notebookFolder = report.folders.find(folder => folder.id === notebook.id).path;
    const taken = new Set((await fs.readdir(path.join(output, notebookFolder))).map(name => name.toLowerCase()));
    const sectionName = uniqueName('未归档页面', '', taken);
    const directory = path.join(output, notebookFolder, sectionName);
    const lastKey = notebook.sections.length; // after every existing section
    await writeFolder(directory, { id: `section-unfiled-${notebook.id}` }, keysAfter(null, lastKey + 1)[lastKey], [], `${notebookFolder}/${sectionName}`);
    const pageTaken = new Set([FOLDER_DESCRIPTOR]);
    const keys = keysAfter(null, orphans.length);
    for (const [index, file] of orphans.entries()) {
      const content = JSON.parse(await fs.readFile(path.join(pages, file), 'utf8'));
      const conflict = file.match(/\.sync-conflict-(\d{8})-(\d{6})/);
      const id = conflict ? `${content.pageId}-conflict-${conflict[1]}${conflict[2]}` : content.pageId;
      const title = conflict ? `冲突副本 ${conflict[1]} ${content.pageId.slice(-8)}` : `未归档 ${content.pageId.slice(-8)}`;
      const name = uniqueName(storableName(title), PAGE_EXTENSION, pageTaken);
      const document = { version: 3, id, sortKey: keys[index], createdAt: content.updatedAt, appearance: { sheetCount: 1 },
        ...pick(content, ['version', 'notebookId', 'pageId']) };
      await writeJson(path.join(directory, name), document);
      report.orphanPages.push(path.relative(source, path.join(pages, file)));
      report.pages.push({ id, notebook: notebook.id, title, path: `${notebookFolder}/${sectionName}/${name}`, source: path.relative(source, path.join(pages, file)), unfiled: true });
    }
  }

  // Assets keep their owner page id; moving or renaming a page never moves its assets.
  for (const notebook of await fs.readdir(path.join(source, 'notebooks'))) {
    const assets = path.join(source, 'notebooks', notebook, 'assets');
    for (const owner of await fs.readdir(assets).catch(() => [])) {
      for (const file of await fs.readdir(path.join(assets, owner))) {
        if (file.endsWith('.previous') || file.endsWith('.tmp')) continue;
        const bytes = await fs.readFile(path.join(assets, owner, file));
        const target = path.join(output, '.mojian', 'assets', owner, file);
        if (await exists(target)) {
          if (sha256(await fs.readFile(target)) !== sha256(bytes)) throw new Error(`asset collision with different bytes: ${owner}/${file}`);
          continue;
        }
        await fs.mkdir(path.dirname(target), { recursive: true });
        await fs.writeFile(target, bytes);
        report.assets.push({ source: path.relative(source, path.join(assets, owner, file)), path: `.mojian/assets/${owner}/${file}`, sha256: sha256(bytes) });
      }
    }
  }
  await verify(source, output, library, report);
  return report;
}

/** Every listed page's content and metadata and every asset must survive byte-for-byte or field-for-field. */
async function verify(source, output, library, report) {
  const pages = [];
  const walk = sections => { for (const section of sections) { pages.push(...section.pages); walk(section.children ?? []); } };
  for (const notebook of library.notebooks) walk(notebook.sections);
  if (report.pages.filter(page => !page.unfiled).length + report.missingPages.length !== pages.length) throw new Error('page count mismatch');
  for (const entry of report.pages) {
    const migrated = JSON.parse(await fs.readFile(path.join(output, entry.path), 'utf8'));
    const original = JSON.parse(await fs.readFile(path.join(source, entry.source), 'utf8'));
    for (const [key, value] of Object.entries(original)) {
      if (['version', 'notebookId', 'pageId'].includes(key)) continue;
      if (stable(migrated[key]) !== stable(value)) throw new Error(`content field ${key} changed for ${entry.id}`);
    }
    if (migrated.id !== entry.id || path.basename(entry.path, PAGE_EXTENSION) === '') throw new Error(`identity lost for ${entry.id}`);
  }
  const ids = report.pages.map(page => page.id);
  if (new Set(ids).size !== ids.length) throw new Error('duplicate page id after migration');
  for (const asset of report.assets)
    if (sha256(await fs.readFile(path.join(output, asset.path))) !== asset.sha256) throw new Error(`asset changed: ${asset.path}`);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const [source, output] = process.argv.slice(2);
  if (!source || !output) { console.error('usage: convert.mjs <v2-root> <empty-output>'); process.exit(2); }
  const report = await convert(path.resolve(source), path.resolve(output));
  await fs.writeFile(path.join(path.dirname(path.resolve(output)), 'folder-library-report.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify({ pages: report.pages.length, folders: report.folders.length, assets: report.assets.length,
    missingPages: report.missingPages.length, orphanPages: report.orphanPages.length }));
}
