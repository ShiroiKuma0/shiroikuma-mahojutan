// 白い熊 魔法絨毯 — the role table.
//
// Every colour the desktop app draws is a ROLE: one key, one default, one swatch row on the UI
// page. This file is the only place in the frontend where a colour is written out — the two
// primaries and the colours that carry a meaning — and each of them is written exactly once,
// in the block right below. Everything else is said by reference:
//
//   '@other.key'            follow another role until this one is set on its own
//   ['mix', from, to, t]    a colour derived from two roles, t of the way from the first to
//                           the second (the darker-accent divider, the dim gold, a switch
//                           that is off)
//
// The stylesheets name roles only as `var(--role-…)` (see cssVar below); customize.js writes
// the resolved values onto the root element. A value 白い熊 sets on the UI page is stored in
// the `shiroikuma_ui` store under the role's key, so the keys that existed before the table
// did (`send.stroke`, `page.bg`, `switch.off`, …) are roles like every other and keep working.

// ── The only colour literals ─────────────────────────────────────────────────────────────────

const PRIMARY_ACCENT = '#ffff00';
const PRIMARY_GROUND = '#000000';
// Colours that carry meaning keep their settled defaults.
const MEANING_CONNECT = '#00ff00';
const MEANING_MESSAGE = '#4d94ff';
const MEANING_CONTACT = '#00ffff';
const MEANING_SHARE = '#ff8000';
const MEANING_BLOCK = '#ff00ff';
const MEANING_DELETE = '#ff0000';
const MEANING_WARNING = '#ff5252';

// ── Shorthands for references ────────────────────────────────────────────────────────────────

const ACCENT = '@role.accent';
const GROUND = '@role.ground';
const TEXT = '@role.text';
const DIM_GOLD = '@role.dimGold';
const DIVIDER = '@role.divider';
// An opaque darker yellow, 140/255 of the way from the ground to the accent (#8c8c00 with the
// primaries as they ship): a switch that is off, and everything else that is deliberately dim.
const dimmed = () => ['mix', 'role.ground', 'role.accent', 140 / 255];

/**
 * One role: `key` is both the store key and (through cssVar) the custom property; `label`,
 * `note`, `icon` and `tint` describe its swatch row on the UI page. Roles that predate the
 * table take their row from the page's catalog in customize.js and carry only key + default.
 */
function role(key, def, label, note, icon, tint) {
  return { key, def, label, note, icon, tint };
}

const TABLE = [
  // ── Primary colours ──
  role('role.accent', PRIMARY_ACCENT, 'Accent',
    'Frames, marks and lines. Everything that has no colour of its own set follows it.', 'drop', 'view'),
  role('role.ground', PRIMARY_GROUND, 'Background',
    'The ground every surface is drawn on until it is given one of its own.', 'drop', 'view'),
  role('role.text', ACCENT, 'Text',
    'Every label and line of text that has no colour of its own set.', 'text', 'view'),
  role('role.dimGold', ['mix', 'role.ground', 'role.text', 200 / 255], 'Dim gold',
    'Explanations and notes — and the thin rule beside an explained row.', 'info', 'view'),
  role('role.divider', ['mix', 'role.ground', 'role.accent', 0.7], 'Darker accent',
    'The thin lines between sections and between groups.', 'lines', 'view'),
  role('role.focus', ACCENT, 'Keyboard focus ring',
    'The outline round whatever the keyboard is on.', 'button', 'view'),

  // ── Buttons ──
  role('role.button.text', TEXT, 'Button label',
    'The label of a pill that has no colour of its own.', 'text', 'view'),
  role('role.button.frame', ACCENT, 'Button frame',
    'The outline of a pill — 3 px for a main action, 1.25 px for every other.', 'button', 'view'),
  role('role.button.ground', GROUND, 'Button ground',
    'What shows through a pill. A pill is never filled.', 'drop', 'view'),
  role('role.button.danger', '@role.delete', 'Destructive button',
    'Frame and label of a button that deletes or overwrites.', 'trash', 'delete'),

  // ── Dialogs ──
  role('role.dialog.ground', GROUND, 'Dialog ground',
    'The box of every dialog and panel.', 'dialog', 'view'),
  role('role.dialog.frame', ACCENT, 'Dialog frame',
    'The outline of that box.', 'dialog', 'view'),
  role('role.dialog.title', TEXT, 'Dialog title',
    'The bold line a dialog opens with.', 'text', 'view'),
  role('role.dialog.body', TEXT, 'Dialog text',
    'What a dialog says or asks.', 'text', 'view'),
  role('role.dialog.note', DIM_GOLD, 'Dialog notes',
    'Hints and asides inside a dialog.', 'info', 'view'),
  role('role.dialog.divider', DIVIDER, 'Dialog divider',
    'The thin line between the parts of a dialog.', 'lines', 'view'),
  role('role.dialog.scrim', GROUND, 'Shade behind a dialog',
    'Laid over the window at 70 % while a dialog is open.', 'window', 'view'),
  role('role.dialog.button.text', '@role.button.text', 'Dialog button label',
    'The label of a pill in a dialog.', 'text', 'view'),
  role('role.dialog.button.frame', '@role.button.frame', 'Dialog button frame',
    'The outline of a pill in a dialog.', 'button', 'view'),
  role('role.dialog.button.ground', '@role.dialog.ground', 'Dialog button ground',
    'What shows through a pill in a dialog.', 'drop', 'view'),

  // ── Fields and pickers ──
  role('role.field.text', TEXT, 'Field text',
    'What is typed into a field, and the value shown by a picker.', 'text', 'view'),
  role('role.field.ground', GROUND, 'Field ground',
    'The inside of a text field or a picker.', 'drop', 'view'),
  role('role.field.frame', ACCENT, 'Field frame',
    'The outline of a text field or a picker.', 'field', 'view'),
  role('role.field.placeholder', DIM_GOLD, 'Field placeholder',
    'The hint shown while a field is empty.', 'info', 'view'),
  role('role.slider.track', dimmed(), 'Slider track',
    'The line a slider runs on.', 'sliders', 'view'),
  role('role.slider.thumb', ACCENT, 'Slider thumb',
    'The dot that is dragged.', 'sliders', 'view'),
  role('role.swatch.frame', ACCENT, 'Swatch frame',
    'The outline round a colour swatch.', 'drop', 'view'),
  role('role.check.mark', ACCENT, 'Selection marks',
    'The tick boxes of a list where several things can be chosen.', 'check', 'view'),
  role('role.selection.ground', ACCENT, 'Selected text — ground',
    'Behind text that is marked with the pointer.', 'text', 'view'),
  role('role.selection.text', GROUND, 'Selected text — letters',
    'The letters of text that is marked.', 'text', 'view'),
  role('role.caret', ACCENT, 'Text cursor',
    'The blinking line in a field.', 'text', 'view'),

  // ── Menus ──
  role('role.menu.ground', GROUND, 'Menu ground',
    'The box of a right-click menu.', 'menu', 'view'),
  role('role.menu.frame', ACCENT, 'Menu frame',
    'The outline of that box.', 'menu', 'view'),
  role('role.menu.header', TEXT, 'Menu header',
    'The plain line a menu opens with — the name of the device.', 'text', 'view'),
  role('role.menu.label', TEXT, 'Menu labels',
    'The label of every row, and its ›.', 'text', 'view'),
  role('role.menu.pill', ACCENT, 'Menu row frame',
    'The outline of every row that does something.', 'button', 'view'),
  role('role.menu.divider', DIVIDER, 'Menu divider',
    'The line between two groups of rows.', 'lines', 'view'),
  role('role.menu.danger', '@role.delete', 'Destructive menu row',
    'Frame, icon and label of a row that removes something.', 'trash', 'delete'),

  // ── Icons: coloured by what their row does ──
  role('role.icon.connect', MEANING_CONNECT, 'Connects',
    'Rows that reach a device: a network, a hotspot, receiving.', 'wifi', 'connect'),
  role('role.icon.message', MEANING_MESSAGE, 'Writes',
    'Rows where a text is typed.', 'text', 'message'),
  role('role.icon.contact', MEANING_CONTACT, 'A device or its name',
    'Rows about one device: its name, its code.', 'device', 'contact'),
  role('role.icon.share', MEANING_SHARE, 'Sends, exports',
    'Rows that send files or carry settings out and in.', 'export', 'share'),
  role('role.icon.view', ACCENT, 'Shows, opens, sets',
    'Rows that open something or set how it looks.', 'eye', 'view'),
  role('role.icon.block', MEANING_BLOCK, 'Blocks, refuses',
    'Rows that hold something back.', 'block', 'block'),
  role('role.icon.delete', '@role.delete', 'Deletes, resets',
    'Rows that remove something.', 'trash', 'delete'),

  // ── Warnings ──
  role('role.warning', MEANING_WARNING, 'Warning',
    '“No backup directory set” and every other line that says something is missing.', 'warning', 'role.warning'),
  role('role.delete', MEANING_DELETE, 'Delete red',
    'Lent to the frame and label of whatever deletes.', 'trash', 'delete'),

  // ── Devices and panels ──
  role('role.strip.text', TEXT, 'Device pill label',
    'The name on a paired device’s pills on the main page.', 'text', 'view'),
  role('role.strip.frame', ACCENT, 'Device pill frame',
    'The outline of those pills and the line between their halves.', 'button', 'view'),
  role('role.strip.ground', GROUND, 'Device pill ground',
    'What shows through them.', 'drop', 'view'),
  role('role.list.primary', TEXT, 'List — first line',
    'The name on a row of a list: a device, a backup, a choice.', 'text', 'view'),
  role('role.list.secondary', DIM_GOLD, 'List — second line',
    'What stands under the name: an address, a size, a state.', 'info', 'view'),
  role('role.list.pill', ACCENT, 'List row frame',
    'The outline of a row that does something when it is clicked.', 'button', 'view'),
  role('role.list.divider', DIVIDER, 'List divider',
    'The line between two groups of a list.', 'lines', 'view'),
  role('role.list.online', '@role.icon.connect', 'Device on this network',
    'The icon of a paired device that answered the last look.', 'device', 'role.list.online'),
  role('role.list.offline', dimmed(), 'Device not seen',
    'The icon of a paired device that did not answer.', 'device', 'role.list.offline'),
  role('role.panel.heading', TEXT, 'Panel headings',
    'The underlined headings inside the Devices panel.', 'text', 'view'),
  role('role.panel.status', DIM_GOLD, 'Panel status line',
    'The line that says what a panel found — the latest export, the listening port.', 'info', 'view'),

  // ── Transfer lanes ──
  role('role.lane.frame', ACCENT, 'Lane frame',
    'The box round one paired transfer.', 'lanes', 'view'),
  role('role.lane.ground', '@window.bg', 'Lane ground',
    'The inside of that box.', 'drop', 'view'),
  role('role.lane.title', TEXT, 'Lane title',
    'The direction and the device.', 'text', 'view'),
  role('role.lane.status', dimmed(), 'Lane status',
    '“Queued…” and “Connecting…”.', 'clock', 'view'),
  role('role.lane.details', '@progress.color', 'Lane readout',
    'Sent, total, rate and time left.', 'text', 'view'),
  role('role.lane.bar', '@progress.color', 'Lane bar',
    'The filled part of a lane’s progress bar.', 'progress', 'view'),
  role('role.lane.barGround', '@window.bg', 'Lane bar ground',
    'The part not yet filled.', 'progress', 'view'),
  role('role.lane.barFrame', '@progress.color', 'Lane bar frame',
    'The outline of the bar.', 'progress', 'view'),

  // ── Progress (the single transfer's bars; the fill is the older key progress.color) ──
  role('role.progress.ground', '@window.bg', 'Bar ground',
    'The part of a progress bar not yet filled.', 'progress', 'view'),
  role('role.progress.frame', '@progress.color', 'Bar frame',
    'The outline of a progress bar.', 'progress', 'view'),
  role('role.progress.text', '@progress.color', 'Readout',
    'The two lines above the bars: sent, total, rate and time left.', 'text', 'view'),

  // ── QR codes ──
  role('role.qr.light', ACCENT, 'QR code — light',
    'The light modules, the quiet zone round them and the box they sit in.', 'qr', 'view'),
  role('role.qr.dark', GROUND, 'QR code — dark',
    'The dark modules and the code printed underneath.', 'qr', 'view'),

  // ── Window ──
  role('role.scrollbar.thumb', dimmed(), 'Scrollbar',
    'The part of a scrollbar that moves.', 'sliders', 'view'),
  role('role.scrollbar.track', '@window.bg', 'Scrollbar track',
    'What the scrollbar runs in.', 'sliders', 'view'),

  // ── About dialog ──
  role('aboutStroke', '@aboutTitle.color', 'Frame',
    'The outline of the About dialog.', 'dialog', 'view'),

  // ── The UI page itself (page.* keys travel in the backup's “this UI page” category) ──
  role('page.divider', DIVIDER, 'Section divider',
    'The 1 px line between two sections, folded or open.', 'lines', 'view'),
  role('page.fold', '@page.section.color', 'Fold marks',
    'The › of a folded section and the ⌄ of an open one.', 'fold', 'view'),
  role('page.chip.text', TEXT, 'Index chip label',
    'The chips under the page title, one for every section.', 'text', 'view'),
  role('page.chip.frame', ACCENT, 'Index chip frame',
    'The outline of an index chip.', 'chips', 'view'),
  role('page.chip.ground', '@page.bg', 'Index chip ground',
    'What shows through an index chip.', 'drop', 'view'),
  role('page.rule', '@page.note.color', 'Left rule',
    'The thin rule down the left of a row that carries an explanation.', 'lines', 'view'),
  role('page.action.text', TEXT, 'Action pill label',
    'Rows of this page that open or run something, and the small “Default” pills.', 'text', 'view'),
  role('page.action.frame', ACCENT, 'Action pill frame',
    'The outline of those pills.', 'button', 'view'),
  role('page.action.ground', '@page.bg', 'Action pill ground',
    'What shows through them.', 'drop', 'view'),
];

// ── Roles that predate the table: the keys the UI page has always stored ─────────────────────

function legacy(key, def) { TABLE.push(role(key, def)); }

// The selector buttons — never filled, the selected one told by its frame and weight.
export const TOGGLE_KEYS = ['send', 'receive', 'hotspot', 'sharedNetwork',
  'androidOs', 'iosOs', 'linuxOs', 'macOs', 'windowsOs'];
for (const key of TOGGLE_KEYS) {
  legacy(`${key}.unselFill`, GROUND);
  legacy(`${key}.selFill`, GROUND);
  legacy(`${key}.unselText`, TEXT);
  legacy(`${key}.selText`, TEXT);
  legacy(`${key}.stroke`, ACCENT);
}

// "<surface>.color" — the text colour of each text surface.
const TEXT_SURFACES = {
  title: TEXT, version: TEXT, about: TEXT, uiButton: TEXT, devicesButton: TEXT,
  bluetooth: TEXT, bluetoothHint: DIM_GOLD,
  modeInstruction: TEXT, connectionInstruction: TEXT, peerInstruction: TEXT,
  start: TEXT, cancel: '@role.delete', output: TEXT,
  promptMessage: '@role.dialog.body',
  password: '@role.field.text',
  promptButton: '@role.dialog.button.text',
  aboutTitle: '@role.dialog.title',
  aboutBody: '@role.dialog.body',
  'page.title': TEXT, 'page.section': TEXT, 'page.element': TEXT, 'page.label': TEXT,
  'page.note': DIM_GOLD, 'page.button': TEXT,
};
for (const key of Object.keys(TEXT_SURFACES)) legacy(`${key}.color`, TEXT_SURFACES[key]);

legacy('start.fill', GROUND);
legacy('start.stroke', ACCENT);
legacy('uiButton.bg', GROUND);
legacy('uiButton.stroke', ACCENT);
legacy('cancel.fill', GROUND);
// Cancelling throws a running transfer away, so it wears the delete red (白い熊, 2026-11-21).
legacy('cancel.stroke', '@role.delete');
legacy('output.bg', GROUND);
legacy('output.stroke', ACCENT);
legacy('password.bg', '@role.field.ground');
legacy('password.stroke', '@role.field.frame');
legacy('promptButton.bg', '@role.dialog.button.ground');
legacy('promptButton.stroke', '@role.dialog.button.frame');
legacy('promptBg', '@role.dialog.ground');
legacy('promptStroke', '@role.dialog.frame');
legacy('aboutBg', '@role.dialog.ground');
legacy('page.button.bg', GROUND);
legacy('page.button.stroke', ACCENT);
legacy('page.bg', GROUND);
legacy('window.bg', GROUND);
legacy('logo.tint', ACCENT);
legacy('switch.on', ACCENT);
legacy('switch.off', dimmed());
legacy('progress.color', ACCENT);

// ── Lookup and resolution ────────────────────────────────────────────────────────────────────

const BY_KEY = new Map(TABLE.map((r) => [r.key, r]));

/** Every colour key the app has, in table order. */
export const ROLE_KEYS = TABLE.map((r) => r.key);

export function roleInfo(key) { return BY_KEY.get(key) || null; }

export function isHex(value) {
  return typeof value === 'string' && /^#[0-9a-fA-F]{6}$/.test(value);
}

function channels(hex) {
  return [1, 3, 5].map((at) => parseInt(hex.slice(at, at + 2), 16));
}

/** The colour t of the way from one hex colour to another. */
export function mixHex(from, to, t) {
  const a = channels(from);
  const b = channels(to);
  return '#' + a.map((c, i) => Math.round(c + (b[i] - c) * t).toString(16).padStart(2, '0')).join('');
}

/**
 * The colour of a role as "#rrggbb": the value stored for it (`stored(key)` returns it, or
 * null), else its default — followed through references and mixes. A key the table does not
 * know, or a loop of references, falls back to the accent so nothing is ever left unpainted.
 */
export function resolveRole(key, stored, depth = 0) {
  const own = stored(key);
  if (isHex(own)) return own.toLowerCase();
  const info = BY_KEY.get(key);
  if (!info || depth > 12) return PRIMARY_ACCENT;
  return resolveDefault(info.def, stored, depth + 1);
}

function resolveDefault(def, stored, depth) {
  if (Array.isArray(def)) {
    return mixHex(resolveRole(def[1], stored, depth), resolveRole(def[2], stored, depth), def[3]);
  }
  if (def.charAt(0) === '@') return resolveRole(def.slice(1), stored, depth);
  return def;
}

/** The custom property a role is written to: "role.dialog.ground" → "--role-dialog-ground". */
export function cssVar(key) {
  return '--role-' + key.replace(/^role\./, '').replace(/\./g, '-');
}
