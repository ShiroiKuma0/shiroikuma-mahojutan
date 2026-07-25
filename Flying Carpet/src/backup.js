// 白い熊 魔法絨毯 — the desktop half of the 家族 backup engine.
//
// The same category-ZIP model the Android app and every sister app use (shiroikuma-kojiki's
// KojikiExport, shiroikuma-kxkb's BackupManager): ONE .zip per export, holding a manifest.json plus
// one entry per selected category. Import applies the selected categories the archive actually
// contains, MERGING per key rather than wiping, so restoring a partial backup never destroys what it
// didn't cover and re-importing the same file is idempotent.
//
// Everything settable in this app lives in the one `shiroikuma_ui` localStorage entry, so the
// categories split it along the lines the UI page itself uses — the main window's look, this UI
// page's own look, and the fonts 白い熊 imported.
//
// Rust cannot read localStorage, so the engine lives here and calls the four file-access commands in
// src-tauri/src/main.rs (read / write / list / restart), passing payloads base64-encoded.

const { core } = window.__TAURI__;

export const BACKUP_FORMAT = 'mahojutan-backup';
export const BACKUP_VERSION = 1;

// Both halves of the fork write `shiroikuma-mahojutan_*.zip` — the family name convention keys off
// the repo name, which they share. The values inside differ per platform (an Android colour is an
// ARGB Int, a desktop one is "#rrggbb"), so the manifest records which app wrote the file and an
// import of the other kind is refused rather than silently corrupting settings.
const PLATFORM = 'desktop';

// The family name convention (白い熊, 2026-07-25): `<english-app-name>_<yyyy-MM-dd_HH-mm-ss>.zip` —
// no version, no infix, no suffix, because every sister app's backups share one folder.
export const EXPORT_PREFIX = 'shiroikuma-mahojutan_';

// Device-local settings — the backup folder. Its OWN localStorage entry, so the folder picked on
// THIS machine can never travel inside a backup and overwrite the folder on another one.
const LOCAL_KEY = 'shiroikuma_ui_local';

// The store key the theme engine uses; the fonts map lives inside it under FONT_FILES_KEY.
const STORE_KEY = 'shiroikuma_ui';
const FONT_FILES_KEY = '__fontFiles';

export const CATEGORIES = [
  { id: 'appearance', label: 'Appearance — main window' },
  { id: 'page', label: 'Appearance — this UI page' },
  { id: 'fonts', label: 'Imported fonts' },
];

// ── Device-local settings ─────────────────────────────────────────────────────────────────────

function local() {
  try { return JSON.parse(localStorage.getItem(LOCAL_KEY)) || {}; }
  catch (e) { return {}; }
}

export function exportDir() {
  const dir = local().exportDir;
  return dir && String(dir).trim() ? String(dir).trim() : null;
}

export function setExportDir(path) {
  const data = local();
  data.exportDir = String(path).trim();
  localStorage.setItem(LOCAL_KEY, JSON.stringify(data));
}

// ── File names ────────────────────────────────────────────────────────────────────────────────

function two(n) { return String(n).padStart(2, '0'); }

export function exportFileName(now) {
  const d = now || new Date();
  return `${EXPORT_PREFIX}${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())}` +
    `_${two(d.getHours())}-${two(d.getMinutes())}-${two(d.getSeconds())}.zip`;
}

/** Ours, and only ours: 白い熊 keeps every app's backups in one folder, so filter by the prefix. */
export function isBackupFileName(name) {
  return name.startsWith(EXPORT_PREFIX) && name.endsWith('.zip');
}

/** Our backups in the configured folder, newest first. Never throws — a bad folder is just empty. */
export async function listBackups() {
  const dir = exportDir();
  if (!dir) return [];
  let entries;
  try { entries = await core.invoke('fork_list_dir', { path: dir }); }
  catch (e) { return []; }
  return entries.filter((e) => isBackupFileName(e.name)).sort((a, b) => b.modified - a.modified);
}

export function humanSize(bytes) {
  if (bytes >= 1 << 30) return `${(bytes / (1 << 30)).toFixed(2)} GB`;
  if (bytes >= 1 << 20) return `${(bytes / (1 << 20)).toFixed(1)} MB`;
  if (bytes >= 1 << 10) return `${(bytes / (1 << 10)).toFixed(1)} KB`;
  return `${bytes} B`;
}

// ── base64 <-> bytes (the IPC payload encoding; see main.rs) ──────────────────────────────────

function bytesToBase64(bytes) {
  let binary = '';
  const step = 0x8000; // chunked so a multi-MB font never blows the argument limit
  for (let i = 0; i < bytes.length; i += step) {
    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + step));
  }
  return btoa(binary);
}

function base64ToBytes(text) {
  const binary = atob(text);
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
  return out;
}

// ── A minimal stored (uncompressed) ZIP writer / reader ───────────────────────────────────────
//
// Stored entries only: the payloads here are small JSON plus already-compressed font files, so
// deflating them would buy nothing and cost a dependency the app doesn't otherwise need.

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  return table;
})();

function crc32(bytes) {
  let c = 0xFFFFFFFF;
  for (let i = 0; i < bytes.length; i++) c = CRC_TABLE[(c ^ bytes[i]) & 0xFF] ^ (c >>> 8);
  return (c ^ 0xFFFFFFFF) >>> 0;
}

function utf8(text) { return new TextEncoder().encode(text); }
function fromUtf8(bytes) { return new TextDecoder().decode(bytes); }

/** MS-DOS date/time, the only timestamp the ZIP format itself carries. */
function dosStamp(date) {
  const time = (date.getHours() << 11) | (date.getMinutes() << 5) | (date.getSeconds() >> 1);
  const day = ((date.getFullYear() - 1980) << 9) | ((date.getMonth() + 1) << 5) | date.getDate();
  return { time, day };
}

/** entries: [{name, bytes}] → one Uint8Array holding the whole archive. */
function zipWrite(entries) {
  const { time, day } = dosStamp(new Date());
  const locals = [];
  const centrals = [];
  let offset = 0;

  for (const entry of entries) {
    const name = utf8(entry.name);
    const data = entry.bytes;
    const crc = crc32(data);

    const localHeader = new DataView(new ArrayBuffer(30));
    localHeader.setUint32(0, 0x04034B50, true);   // local file header signature
    localHeader.setUint16(4, 20, true);           // version needed
    localHeader.setUint16(6, 0x0800, true);       // flags: UTF-8 names
    localHeader.setUint16(8, 0, true);            // method: stored
    localHeader.setUint16(10, time, true);
    localHeader.setUint16(12, day, true);
    localHeader.setUint32(14, crc, true);
    localHeader.setUint32(18, data.length, true);
    localHeader.setUint32(22, data.length, true);
    localHeader.setUint16(26, name.length, true);
    localHeader.setUint16(28, 0, true);           // extra field length
    locals.push(new Uint8Array(localHeader.buffer), name, data);

    const central = new DataView(new ArrayBuffer(46));
    central.setUint32(0, 0x02014B50, true);       // central directory header signature
    central.setUint16(4, 20, true);               // version made by
    central.setUint16(6, 20, true);               // version needed
    central.setUint16(8, 0x0800, true);
    central.setUint16(10, 0, true);
    central.setUint16(12, time, true);
    central.setUint16(14, day, true);
    central.setUint32(16, crc, true);
    central.setUint32(20, data.length, true);
    central.setUint32(24, data.length, true);
    central.setUint16(28, name.length, true);
    central.setUint16(30, 0, true);               // extra
    central.setUint16(32, 0, true);               // comment
    central.setUint16(34, 0, true);               // disk number start
    central.setUint16(36, 0, true);               // internal attributes
    central.setUint32(38, 0, true);               // external attributes
    central.setUint32(42, offset, true);          // offset of local header
    centrals.push(new Uint8Array(central.buffer), name);

    offset += 30 + name.length + data.length;
  }

  const centralSize = centrals.reduce((n, part) => n + part.length, 0);
  const end = new DataView(new ArrayBuffer(22));
  end.setUint32(0, 0x06054B50, true);             // end of central directory
  end.setUint16(4, 0, true);
  end.setUint16(6, 0, true);
  end.setUint16(8, entries.length, true);
  end.setUint16(10, entries.length, true);
  end.setUint32(12, centralSize, true);
  end.setUint32(16, offset, true);
  end.setUint16(20, 0, true);

  const parts = locals.concat(centrals, [new Uint8Array(end.buffer)]);
  const total = parts.reduce((n, part) => n + part.length, 0);
  const out = new Uint8Array(total);
  let at = 0;
  for (const part of parts) { out.set(part, at); at += part.length; }
  return out;
}

/** Reads a stored ZIP via its central directory → {name: Uint8Array}. */
function zipRead(bytes) {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let eocd = -1;
  for (let i = bytes.length - 22; i >= 0 && i >= bytes.length - 22 - 0xFFFF; i--) {
    if (view.getUint32(i, true) === 0x06054B50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('not a .zip file');

  const count = view.getUint16(eocd + 10, true);
  let at = view.getUint32(eocd + 16, true);
  const files = {};
  for (let i = 0; i < count; i++) {
    if (view.getUint32(at, true) !== 0x02014B50) throw new Error('damaged central directory');
    const method = view.getUint16(at + 10, true);
    const size = view.getUint32(at + 24, true);
    const nameLen = view.getUint16(at + 28, true);
    const extraLen = view.getUint16(at + 30, true);
    const commentLen = view.getUint16(at + 32, true);
    const localAt = view.getUint32(at + 42, true);
    const name = fromUtf8(bytes.subarray(at + 46, at + 46 + nameLen));
    if (method !== 0) throw new Error(`unsupported compression in "${name}"`);
    const localNameLen = view.getUint16(localAt + 26, true);
    const localExtraLen = view.getUint16(localAt + 28, true);
    const dataAt = localAt + 30 + localNameLen + localExtraLen;
    files[name] = bytes.subarray(dataAt, dataAt + size);
    at += 46 + nameLen + extraLen + commentLen;
  }
  return files;
}

// ── Categories ────────────────────────────────────────────────────────────────────────────────

// The UI page styles itself through "page.*" keys; everything else styles the main window. The
// font map belongs to the FONTS category, so it is in neither prefs dump.
function isPageKey(key) { return key.startsWith('page.'); }
function isAppearanceKey(key) { return !isPageKey(key) && key !== FONT_FILES_KEY; }

function readStore() {
  try { return JSON.parse(localStorage.getItem(STORE_KEY)) || {}; }
  catch (e) { return {}; }
}

/**
 * JSON keeps string-vs-number apart on its own, so — unlike the Android side, whose
 * SharedPreferences need explicit type tags — the desktop dump stores the values as they are.
 */
function dumpPrefs(keep) {
  const store = readStore();
  const prefs = {};
  let n = 0;
  for (const key of Object.keys(store)) {
    if (!keep(key)) continue;
    prefs[key] = store[key];
    n++;
  }
  return { json: { prefs }, count: n };
}

/** data:font/ttf;base64,AAAA… → {mime, bytes} */
function splitDataUrl(url) {
  const comma = url.indexOf(',');
  if (comma < 0) throw new Error('malformed font data');
  const head = url.slice(5, comma);                 // strip "data:"
  const mime = head.replace(/;base64$/, '') || 'application/octet-stream';
  return { mime, bytes: base64ToBytes(url.slice(comma + 1)) };
}

function fontEntries() {
  const store = readStore();
  const fonts = store[FONT_FILES_KEY] || {};
  const index = [];
  const entries = [];
  for (const name of Object.keys(fonts)) {
    let split;
    try { split = splitDataUrl(String(fonts[name])); }
    catch (e) { continue; }
    const file = `fonts/${name}`;
    index.push({ name, file, mime: split.mime });
    entries.push({ name: file, bytes: split.bytes });
  }
  return { index, entries };
}

// ── Export ────────────────────────────────────────────────────────────────────────────────────

/**
 * Writes one .zip holding [cats] into the configured folder. Returns {path, bytes, lines}; throws
 * with a readable message on anything the UI should show as "Export failed…".
 */
export async function runExport(cats, appVersion) {
  const dir = exportDir();
  if (!dir) throw new Error('no backup folder set');

  const entries = [];
  const lines = [];
  const included = [];

  for (const cat of CATEGORIES) {
    if (!cats.has(cat.id)) continue;
    if (cat.id === 'appearance' || cat.id === 'page') {
      const keep = cat.id === 'page' ? isPageKey : isAppearanceKey;
      const dump = dumpPrefs(keep);
      entries.push({ name: `${cat.id}.json`, bytes: utf8(JSON.stringify(dump.json)) });
      lines.push(`${cat.label}: ${dump.count}`);
    } else {
      const fonts = fontEntries();
      entries.push(...fonts.entries);
      entries.push({ name: 'fonts.json', bytes: utf8(JSON.stringify({ files: fonts.index })) });
      lines.push(`${cat.label}: ${fonts.index.length}`);
    }
    included.push(cat.id);
  }

  entries.push({
    name: 'manifest.json',
    bytes: utf8(JSON.stringify({
      format: BACKUP_FORMAT,
      version: BACKUP_VERSION,
      platform: PLATFORM,
      app: 'shiroikuma-mahojutan',
      appVersion: appVersion || '',
      createdTs: Date.now(),
      categories: included,
    }, null, 2)),
  });

  const name = exportFileName();
  const path = `${dir.replace(/\/+$/, '')}/${name}`;
  const zip = zipWrite(entries);
  const written = await core.invoke('fork_write_file', { path, base64: bytesToBase64(zip) });
  return { path, name, bytes: written, lines };
}

// ── Import ────────────────────────────────────────────────────────────────────────────────────

/** Applies the selected [cats] that the archive at [path] contains. Returns {lines}. */
export async function runImport(path, cats) {
  const raw = await core.invoke('fork_read_file', { path });
  const files = zipRead(base64ToBytes(raw));

  let manifest = null;
  if (files['manifest.json']) {
    try { manifest = JSON.parse(fromUtf8(files['manifest.json'])); } catch (e) { manifest = null; }
  }
  const platform = manifest && manifest.platform ? manifest.platform : '';
  if (platform && platform !== PLATFORM) {
    throw new Error(`this backup was written by the ${platform} app, not the desktop one`);
  }

  const store = readStore();
  const lines = [];

  for (const cat of CATEGORIES) {
    if (!cats.has(cat.id)) continue;
    if (cat.id === 'appearance' || cat.id === 'page') {
      const payload = files[`${cat.id}.json`];
      if (!payload) continue;
      const prefs = (JSON.parse(fromUtf8(payload)) || {}).prefs || {};
      let n = 0;
      for (const key of Object.keys(prefs)) { store[key] = prefs[key]; n++; }   // merge, never wipe
      lines.push(`${cat.label}: ${n}`);
    } else {
      const index = files['fonts.json']
        ? (JSON.parse(fromUtf8(files['fonts.json'])) || {}).files || []
        : [];
      if (!index.length) continue;
      const fonts = store[FONT_FILES_KEY] || {};
      let n = 0;
      for (const item of index) {
        const bytes = files[item.file];
        if (!bytes) continue;
        fonts[item.name] = `data:${item.mime || 'application/octet-stream'};base64,${bytesToBase64(bytes)}`;
        n++;
      }
      store[FONT_FILES_KEY] = fonts;
      lines.push(`${cat.label}: ${n}`);
    }
  }

  localStorage.setItem(STORE_KEY, JSON.stringify(store));
  return { lines };
}

/** "Restart now" on the import-finished dialog. */
export async function restartApp() {
  await core.invoke('fork_restart');
}
