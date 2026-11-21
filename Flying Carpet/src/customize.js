// 白い熊 魔法絨毯 UI customization — desktop port of the Android fork's Appearance/Settings engine,
// and the desktop home of the black-yellow theme (the global black-yellow-theme skill is the spec).
//
// The fork's baseline look is a high-contrast yellow-on-black theme: yellow text, yellow frames,
// black grounds — everywhere, unless 白い熊 sets a property of one item separately.
//
// COLOURS. No colour is written in this file. Every colour is a role from the one table in
// roles.js; this module resolves each role (the stored value, else the table's default) and
// writes it onto the root element as a custom property, which is all the stylesheets ever name.
//
// SHAPES. An action is an outlined pill and never a filled one — 3 px and bold for the one action
// a block is for, 1.25 px for every other, the delete red lent to frame and label of a
// destructive one. A switch is an outline with a dot. A menu row is a full-width pill with its
// icon inside. Explanations are dim gold, never grey.
//
// Every stored value is optional: an absent key means "inherit the default"; "Reset" clears keys.
// Mirrors Android's Settings semantics: dims (border width / radius) store an explicit 0 as a valid
// value distinct from "unset", texts store "" as "unset", colours/fonts/styles are absent when unset.

import * as Backup from './backup.js';
import { ROLE_KEYS, TOGGLE_KEYS, roleInfo, resolveRole, cssVar } from './roles.js';
import { icon } from './icons.js';

export { icon };

// The directory picker for the Export/Import panel (the dialog plugin is already in the capability set).
const { dialog } = window.__TAURI__;

const BORDER_WIDTH = 1;   // px
const CORNER_RADIUS = 10; // px
const PILL_MAIN = 3;       // px
const PILL_REGULAR = 1.25; // px
const PILL_RADIUS = 60;    // px — more than half of any pill's height

const STORE_KEY = 'shiroikuma_ui';
const FONT_FILES_KEY = '__fontFiles';
// The UI page's own arrangement. Both start with "page.", so they travel in the backup's
// “this UI page” category with the page's colours and fonts.
const SECTION_ORDER_KEY = 'page.sectionOrder';
const SECTIONS_OPEN_KEY = 'page.sectionsOpen';

// ── Settings store (localStorage-backed, Android SharedPreferences equivalent) ─────────────────

const Settings = {
  data: {},

  load() {
    try { this.data = JSON.parse(localStorage.getItem(STORE_KEY)) || {}; }
    catch (e) { this.data = {}; }
  },
  persist() { localStorage.setItem(STORE_KEY, JSON.stringify(this.data)); },

  has(key) { return Object.prototype.hasOwnProperty.call(this.data, key); },

  colorOrNull(key) { return this.has(key) ? this.data[key] : null; },
  setColor(key, value) {
    if (value === null) delete this.data[key]; else this.data[key] = value;
    this.persist();
  },

  text(key) { return this.has(key) ? String(this.data[key]) : ''; },
  textOr(key, def) { return this.text(key) || def; },
  setText(key, value) {
    if (!value) delete this.data[key]; else this.data[key] = value;
    this.persist();
  },

  size(key) { return this.has(key) ? Number(this.data[key]) : 0; },
  setSize(key, value) {
    if (!(value > 0)) delete this.data[key]; else this.data[key] = value;
    this.persist();
  },

  // Border width / corner radius: 0 is a valid stored value (no border / square corners), distinct
  // from "unset" (use the default).
  sizeOrNull(key) { return this.has(key) ? Number(this.data[key]) : null; },
  setDim(key, value) { this.data[key] = value; this.persist(); },

  family(key) { return this.text(key); },
  setFamily(key, value) { this.setText(key, value); },

  style(key) { return this.has(key) ? Number(this.data[key]) : -1; },
  setStyle(key, value) {
    if (value < 0) delete this.data[key]; else this.data[key] = value;
    this.persist();
  },

  // A list of section ids (the order, the open sections); null while never set.
  list(key) {
    const value = this.has(key) ? this.data[key] : null;
    return Array.isArray(value) ? value.map(String) : null;
  },
  setList(key, value) { this.data[key] = value; this.persist(); },

  remove(keys) { keys.forEach((k) => delete this.data[k]); this.persist(); },
  clearAll() { this.data = {}; this.persist(); },

  // ── External fonts: {name: dataUrl}, registered as FontFace "ext:<name>" ──
  fontFiles() { return this.has(FONT_FILES_KEY) ? this.data[FONT_FILES_KEY] : {}; },
  addFontFile(name, dataUrl) {
    const files = this.fontFiles();
    files[name] = dataUrl;
    this.data[FONT_FILES_KEY] = files;
    this.persist();
  },

  familyValues() { return FONT_VALUES.concat(Object.keys(this.fontFiles()).map((n) => 'ext:' + n)); },
  familyLabels() { return FONT_LABELS.concat(Object.keys(this.fontFiles())); },
};

// System font families offered for every text surface.
const FONT_VALUES = [
  '', 'system-ui', 'sans-serif', 'serif', 'monospace', 'cursive', 'fantasy',
  'Arial', 'Helvetica', 'Verdana', 'Georgia', 'Times New Roman', 'Courier New',
  'Noto Sans CJK JP', 'Noto Serif CJK JP',
];
const FONT_LABELS = [
  '(Default)', 'System UI', 'Sans Serif', 'Serif', 'Monospace', 'Cursive', 'Fantasy',
  'Arial', 'Helvetica', 'Verdana', 'Georgia', 'Times New Roman', 'Courier New',
  'Noto Sans CJK JP', 'Noto Serif CJK JP',
];

const STYLE_VALUES = [-1, 0, 1, 2, 3];
const STYLE_LABELS = ['(Default)', 'Normal', 'Bold', 'Italic', 'Bold Italic'];

function registerFonts() {
  const files = Settings.fontFiles();
  for (const name of Object.keys(files)) {
    try {
      const face = new FontFace('ext:' + name, `url(${files[name]})`);
      face.load().then((f) => document.fonts.add(f)).catch(() => {});
    } catch (e) { /* ignore malformed stored fonts */ }
  }
}

// css font-family value for a stored family ('' = inherit → null).
function cssFamily(family) {
  if (!family) return null;
  return `"${family}"`;
}

function styleCss(style) {
  // -1 inherit; 0 normal; 1 bold; 2 italic; 3 bold italic
  if (style < 0) return null;
  return {
    weight: (style === 1 || style === 3) ? 'bold' : 'normal',
    fontStyle: (style === 2 || style === 3) ? 'italic' : 'normal',
  };
}

// ── Roles ─────────────────────────────────────────────────────────────────────────────────────

/** The colour of a role right now, as "#rrggbb": what is stored for it, else its default. */
export function colour(key) {
  return resolveRole(key, (k) => Settings.colorOrNull(k));
}

/** The role as the stylesheets name it. */
function v(key) { return `var(${cssVar(key)})`; }

// ── Catalog — mirrors Android's UiCatalog, with desktop-only additions ────────────────────────
//
// A section of the UI page lists the elements it styles. The older elements are "surfaces" (a
// text with its font and colours) and "colour groups"; what the role table added sits in groups
// of `roles`, each drawn as a swatch row with the icon and the explanation the table gives it.
// The default of every colour lives in the role table, never here.

function labelField(key, label) { return { key, label }; }
function colorField(key, label) { return { key, label }; }
function dimField(key, label, max, def) { return { key, label, max: max || 16, def: def || 0 }; }

// A Send/Receive/OS selector button: label + per-button selected/unselected ground & text, frame
// colour, and border-width / corner-radius sliders — all separate per button.
// Fork: a pill like every other button, and never filled — the selected one is told by its 3 px frame
// and bold label (白い熊, 2026-11-14), so both grounds default to the background.
function toggleSurface(key, title) {
  return {
    key, title,
    labels: [labelField(key + '.text', 'Button text')],
    hasTextColor: false,
    extraColors: [
      colorField(key + '.unselFill', 'Background (unselected)'),
      colorField(key + '.selFill', 'Background (selected)'),
      colorField(key + '.unselText', 'Text (unselected)'),
      colorField(key + '.selText', 'Text (selected)'),
      colorField(key + '.stroke', 'Border colour'),
    ],
    extraDims: [
      dimField(key + '.strokeWidth', 'Border width (unselected)', 16, PILL_REGULAR),
      dimField(key + '.cornerRadius', 'Corner radius', 60, PILL_RADIUS),
    ],
  };
}

function textSurface(key, title, labels, extra) {
  return Object.assign({ key, title, labels: labels || [], hasTextColor: true, extraColors: [], extraDims: [] }, extra || {});
}

const SECTIONS = [
  {
    id: 'primary',
    title: 'Primary colours',
    note: 'The two colours everything starts from, and the few derived from them. Whatever has no colour of its own set follows these.',
    colorGroups: [
      { title: 'Primaries', roles: ['role.accent', 'role.ground', 'role.text'] },
      { title: 'Derived', roles: ['role.dimGold', 'role.divider', 'role.focus'] },
    ],
  },
  {
    id: 'main',
    title: 'Main page',
    note: 'The controls you reach for first — the Send, Receive, and Select Files buttons and the Bluetooth switch. Each button’s colours and borders are set right here.',
    surfaces: [
      toggleSurface('send', '“Send” button'),
      toggleSurface('receive', '“Receive” button'),
      toggleSurface('hotspot', '“Hotspot” button'),
      toggleSurface('sharedNetwork', '“Shared Network” button'),
      textSurface('start', 'Send / receive buttons', [
        labelField('start.filesText', '“Files to send” text'),
        labelField('start.dirText', '“Directory to send” text'),
        // The key is stored under this name on 白い熊's machine and is kept as it is.
        labelField('start.folderText', '“Select directory” text (receive mode)'),
      ], {
        extraColors: [
          colorField('start.fill', 'Background'),
          colorField('start.stroke', 'Border colour'),
        ],
        extraDims: [
          dimField('start.strokeWidth', 'Border width', 16, PILL_MAIN),
          dimField('start.cornerRadius', 'Corner radius', 60, PILL_RADIUS),
        ],
      }),
      textSurface('bluetooth', '“Use Bluetooth” label', [labelField('bluetooth.text', 'Label text')]),
      textSurface('bluetoothHint', 'Line under the Bluetooth switch', []),
    ],
    colorGroups: [
      // An outline with a dot, so one colour for each state is all there is to set. Every switch
      // in the app — this one, and the ones in menus and lists — follows these two.
      {
        title: 'Switches',
        colors: [
          colorField('switch.on', 'On'),
          colorField('switch.off', 'Off'),
        ],
      },
    ],
  },
  {
    id: 'titlebar',
    title: 'Title bar',
    surfaces: [
      textSurface('title', 'Title', [labelField('title.text', 'Title text')]),
      textSurface('version', 'Version label', [labelField('version.text', 'Label text')]),
      textSurface('about', '“About” button', [labelField('about.text', 'Button text')]),
      textSurface('devicesButton', '“Devices” button', [labelField('devicesButton.text', 'Button text')]),
      textSurface('uiButton', '“白い熊 魔法絨毯 UI” button', [labelField('uiButton.text', 'Button text')], {
        extraColors: [
          colorField('uiButton.bg', 'Background'),
          colorField('uiButton.stroke', 'Border colour'),
        ],
        extraDims: [
          dimField('uiButton.strokeWidth', 'Border width', 16, PILL_REGULAR),
          dimField('uiButton.cornerRadius', 'Corner radius', 60, PILL_RADIUS),
        ],
      }),
    ],
    colorGroups: [
      { title: 'App logo / picture', colors: [colorField('logo.tint', 'Tint')] },
    ],
  },
  {
    id: 'steps',
    title: 'Step instructions',
    surfaces: [
      textSurface('modeInstruction', 'Step 1 instruction', [labelField('modeInstruction.text', 'Instruction text')]),
      textSurface('connectionInstruction', 'Connection-type instruction', [labelField('connectionInstruction.text', 'Instruction text')]),
      textSurface('peerInstruction', 'Step 2 instruction', [labelField('peerInstruction.text', 'Instruction text')]),
    ],
  },
  {
    id: 'peer',
    title: 'Peer OS buttons',
    surfaces: [
      toggleSurface('androidOs', '“Android” button'),
      toggleSurface('iosOs', '“iOS” button'),
      toggleSurface('linuxOs', '“Linux” button'),
      toggleSurface('macOs', '“macOS” button'),
      toggleSurface('windowsOs', '“Windows” button'),
    ],
  },
  {
    id: 'cancel',
    title: 'Cancel button',
    surfaces: [
      textSurface('cancel', 'Cancel button', [labelField('cancel.text', 'Button text')], {
        extraColors: [
          colorField('cancel.fill', 'Background'),
          colorField('cancel.stroke', 'Border colour'),
        ],
        extraDims: [
          dimField('cancel.strokeWidth', 'Border width', 16, PILL_REGULAR),
          dimField('cancel.cornerRadius', 'Corner radius', 60, PILL_RADIUS),
        ],
      }),
    ],
  },
  {
    id: 'output',
    title: 'Transfer output',
    note: 'The log box at the bottom that shows status messages, and the bars of a transfer.',
    surfaces: [
      textSurface('output', 'Output log text', [labelField('output.hint', 'Welcome text')], {
        extraColors: [
          colorField('output.bg', 'Box background'),
          colorField('output.stroke', 'Box border colour'),
        ],
        extraDims: [
          dimField('output.strokeWidth', 'Box border width', 16, BORDER_WIDTH),
          dimField('output.cornerRadius', 'Box corner radius', 60, CORNER_RADIUS),
        ],
      }),
    ],
    colorGroups: [
      {
        title: 'Progress bar',
        colors: [colorField('progress.color', 'Bar colour')],
        dims: [dimField('progress.height', 'Bar thickness', 60, 15)],
        roles: ['role.progress.ground', 'role.progress.frame', 'role.progress.text'],
      },
    ],
  },
  {
    id: 'lanes',
    title: 'Transfer lanes',
    note: 'The blocks of paired transfers running side by side, one per device. Until set here, their bars follow the progress bar of “Transfer output”.',
    colorGroups: [
      { title: 'Lane', roles: ['role.lane.frame', 'role.lane.ground', 'role.lane.title', 'role.lane.status', 'role.lane.details'] },
      { title: 'Lane bars', roles: ['role.lane.bar', 'role.lane.barGround', 'role.lane.barFrame'] },
    ],
  },
  {
    id: 'devices',
    title: 'Devices and panels',
    note: 'The paired devices on the main page, the Devices panel, and every list the app shows.',
    colorGroups: [
      { title: 'Device pills on the main page', roles: ['role.strip.text', 'role.strip.frame', 'role.strip.ground'] },
      { title: 'Lists', roles: ['role.list.primary', 'role.list.secondary', 'role.list.pill', 'role.list.divider'] },
      { title: 'Device state', roles: ['role.list.online', 'role.list.offline'] },
      { title: 'Panels', roles: ['role.panel.heading', 'role.panel.status'] },
    ],
  },
  {
    id: 'window',
    title: 'Window',
    colorGroups: [
      { title: 'Window', colors: [colorField('window.bg', 'Background')] },
      { title: 'Scrollbars', roles: ['role.scrollbar.thumb', 'role.scrollbar.track'] },
    ],
  },
  {
    id: 'buttons',
    title: 'Buttons',
    note: 'Every button is an outlined pill. These are the colours of the ones that have none of their own; the buttons of the main page are set in their own sections.',
    colorGroups: [
      { title: 'Pills', roles: ['role.button.text', 'role.button.frame', 'role.button.ground'] },
      { title: 'Destructive', roles: ['role.button.danger'] },
    ],
  },
  {
    id: 'dialogs',
    title: 'Dialogs',
    note: 'Questions, confirmations, the pairing dialogs and the panels — every box that opens over the window.',
    colorGroups: [
      { title: 'Box', roles: ['role.dialog.ground', 'role.dialog.frame', 'role.dialog.divider', 'role.dialog.scrim'] },
      { title: 'Text', roles: ['role.dialog.title', 'role.dialog.body', 'role.dialog.note'] },
      { title: 'Buttons', roles: ['role.dialog.button.text', 'role.dialog.button.frame', 'role.dialog.button.ground'] },
    ],
  },
  {
    id: 'password',
    title: 'Password dialog',
    note: 'The box that asks for the password shown on the other device, and for the new name of a file being sent again.',
    surfaces: [
      textSurface('promptMessage', 'Question text', []),
      textSurface('password', 'Password box', [], {
        extraColors: [
          colorField('password.bg', 'Background'),
          colorField('password.stroke', 'Border colour'),
        ],
        extraDims: [
          dimField('password.strokeWidth', 'Border width', 16, BORDER_WIDTH),
          dimField('password.cornerRadius', 'Corner radius', 60, CORNER_RADIUS),
        ],
      }),
      textSurface('promptButton', 'Buttons (Cancel / OK)', [], {
        extraColors: [
          colorField('promptButton.bg', 'Background'),
          colorField('promptButton.stroke', 'Border colour'),
        ],
        extraDims: [
          // OK is the dialog's main action and keeps its 3 px frame.
          dimField('promptButton.strokeWidth', 'Border width (Cancel)', 16, PILL_REGULAR),
          dimField('promptButton.cornerRadius', 'Corner radius', 60, PILL_RADIUS),
        ],
      }),
    ],
    colorGroups: [
      {
        title: 'Dialog',
        colors: [
          colorField('promptBg', 'Background'),
          colorField('promptStroke', 'Border colour'),
        ],
        dims: [
          dimField('promptStrokeWidth', 'Border width', 16, 2),
          dimField('promptCornerRadius', 'Corner radius', 60, 16),
        ],
      },
    ],
  },
  {
    id: 'about',
    title: 'About page',
    note: 'The “About” dialog opened from the button under the title.',
    surfaces: [
      textSurface('aboutTitle', 'Dialog title', []),
      textSurface('aboutBody', 'Body text', []),
    ],
    colorGroups: [
      { title: 'About dialog', colors: [colorField('aboutBg', 'Background')], roles: ['aboutStroke'] },
    ],
  },
  {
    id: 'fields',
    title: 'Fields and pickers',
    note: 'Where something is typed, chosen, dragged or ticked.',
    colorGroups: [
      { title: 'Text fields and pickers', roles: ['role.field.text', 'role.field.ground', 'role.field.frame', 'role.field.placeholder'] },
      { title: 'Sliders and swatches', roles: ['role.slider.track', 'role.slider.thumb', 'role.swatch.frame'] },
      { title: 'Selection marks', roles: ['role.check.mark'] },
      { title: 'Marked text and cursor', roles: ['role.selection.ground', 'role.selection.text', 'role.caret'] },
    ],
  },
  {
    id: 'menus',
    title: 'Menus',
    note: 'The menu a right-click on a paired device opens. Its switch follows “Main page → Switches”, and its icons the section “Icons”.',
    colorGroups: [
      { title: 'Box', roles: ['role.menu.ground', 'role.menu.frame', 'role.menu.divider'] },
      { title: 'Rows', roles: ['role.menu.header', 'role.menu.label', 'role.menu.pill', 'role.menu.danger'] },
    ],
  },
  {
    id: 'icons',
    title: 'Icons',
    note: 'An icon is coloured by what its row does, the same in every menu, list and page, so a row can be found by colour before it is read.',
    colorGroups: [
      {
        title: 'Icon colours',
        roles: ['role.icon.connect', 'role.icon.message', 'role.icon.contact', 'role.icon.share',
          'role.icon.view', 'role.icon.block', 'role.icon.delete'],
      },
    ],
  },
  {
    id: 'qr',
    title: 'QR codes',
    note: 'The code shown for the other device to scan — when a transfer starts and when pairing. A new colour shows on the next code drawn.',
    colorGroups: [
      { title: 'QR code', roles: ['role.qr.light', 'role.qr.dark'] },
    ],
  },
  {
    id: 'warnings',
    title: 'Warnings',
    colorGroups: [
      { title: 'Warning colours', roles: ['role.warning', 'role.delete'] },
    ],
  },
  {
    id: 'page',
    title: 'This settings page',
    note: 'Restyle this customization page itself; changes apply live.',
    surfaces: [
      textSurface('page.title', 'Page title', []),
      textSurface('page.section', 'Section headings', []),
      textSurface('page.element', 'Element headings', []),
      textSurface('page.label', 'Property labels', []),
      textSurface('page.note', 'Notes, hints and explanations', [], { colorLabel: 'Text colour (dim gold)' }),
      textSurface('page.button', 'Buttons (Done / Close)', [], {
        extraColors: [
          colorField('page.button.bg', 'Background'),
          colorField('page.button.stroke', 'Border colour'),
        ],
        extraDims: [
          dimField('page.button.strokeWidth', 'Border width', 16, PILL_REGULAR),
          dimField('page.button.cornerRadius', 'Corner radius', 60, PILL_RADIUS),
        ],
      }),
    ],
    colorGroups: [
      { title: 'Page background', colors: [colorField('page.bg', 'Background')] },
      { title: 'Sections and index', roles: ['page.divider', 'page.fold', 'page.chip.text', 'page.chip.frame', 'page.chip.ground'] },
      { title: 'Rows', roles: ['page.rule', 'page.action.text', 'page.action.frame', 'page.action.ground'] },
    ],
  },
];

// Default label texts (the value used when "<key>.text" is unset).
const DEFAULT_TEXTS = {
  'title.text': '白い熊 魔法絨毯',
  'about.text': 'About',
  'uiButton.text': '白い熊 魔法絨毯 UI',
  'devicesButton.text': 'Devices ＋',
  'bluetooth.text': 'Use Bluetooth',
  'modeInstruction.text': 'Select File Mode',
  'connectionInstruction.text': 'Select Connection Mode',
  'peerInstruction.text': 'Select Peer OS',
  'send.text': 'Send',
  'receive.text': 'Receive',
  'hotspot.text': 'Hotspot',
  'sharedNetwork.text': 'Shared Network',
  'androidOs.text': 'Android',
  'iosOs.text': 'iOS',
  'linuxOs.text': 'Linux',
  'macOs.text': 'macOS',
  'windowsOs.text': 'Windows',
  'cancel.text': 'Cancel Transfer',
  'start.filesText': 'Files to send',
  'start.dirText': 'Directory to send',
  'start.folderText': 'Select directory',
  'output.hint': 'Welcome to 白い熊 魔法絨毯!\nOnce other options are selected, drag and drop can be used to start a transfer.',
};

// The version label defaults to the app's full version (fetched from Tauri); no "Version" prefix.
let appVersion = '';

// ── Selector map: catalog key → DOM selector for text surfaces ────────────────────────────────

const TEXT_SELECTORS = {
  title: '#programTitle',
  version: '#versionLabel',
  about: '#aboutButton',
  uiButton: '#uiButton',
  devicesButton: '#devicesButton',
  bluetooth: 'label[for=bluetoothSwitch]',
  bluetoothHint: '#bluetoothHint',
  modeInstruction: '#modeInstruction',
  connectionInstruction: '#connectionModeLabel',
  peerInstruction: '#peerLabel',
  send: 'label[for=sendButton]',
  receive: 'label[for=receiveButton]',
  hotspot: 'label[for=hotspotButton]',
  sharedNetwork: 'label[for=sharedNetworkButton]',
  androidOs: 'label[for=androidButton]',
  iosOs: 'label[for=iosButton]',
  linuxOs: 'label[for=linuxButton]',
  macOs: 'label[for=macButton]',
  windowsOs: 'label[for=windowsButton]',
  start: '#startButton, #sendDirButton, #lastDirButton',
  cancel: '#cancelButton',
  output: '#outputBox',
  // the password box lives in the prompt dialog (index.html)
  password: '#promptInput',
  promptMessage: '#promptMessage',
  promptButton: '#promptCancel, #promptOk',
};

// ── Applier ───────────────────────────────────────────────────────────────────────────────────

function eDim(key, def) { const val = Settings.sizeOrNull(key); return val === null ? def : val; }

// The font declarations of a text surface: family, style and size, each only when set.
function fontDecls(key) {
  let out = '';
  const fam = cssFamily(Settings.family(key + '.family'));
  if (fam) out += `font-family: ${fam} !important;`;
  const st = styleCss(Settings.style(key + '.style'));
  if (st) out += `font-weight: ${st.weight} !important; font-style: ${st.fontStyle} !important;`;
  const size = Settings.size(key + '.size');
  if (size > 0) out += `font-size: ${size}px !important;`;
  return out;
}

// A text surface: its colour role "<key>.color", then its font.
function textDecls(key) {
  return `color: ${v(key + '.color')} !important;` + fontDecls(key);
}

// A pill with roles of its own: the three local properties the pill rule in customize.css reads.
function pillDecls(textKey, groundKey, frameKey) {
  return `--pill-text: ${v(textKey)}; --pill-ground: ${v(groundKey)}; --pill-frame: ${v(frameKey)};`;
}

/** Every role as a custom property on the root element — the only place a colour value is written. */
function roleVars() {
  let out = ':root {';
  for (const key of ROLE_KEYS) out += ` ${cssVar(key)}: ${colour(key)};`;
  return out + ' }\n';
}

// What the stylesheet cannot say on its own: widths, radii and fonts 白い熊 set per element, and
// which roles each separately styled element reads. Colours appear only as var(--role-…).
function buildThemeCss() {
  let css = roleVars();

  // Simple text surfaces.
  for (const key of ['title', 'version', 'bluetooth', 'bluetoothHint',
    'modeInstruction', 'connectionInstruction', 'peerInstruction']) {
    css += `${TEXT_SELECTORS[key]} { ${textDecls(key)} }\n`;
  }
  // "About" was a bare word you could click; every action in the theme is a pill. Its frame
  // follows its text colour.
  css += `#aboutButton { ${pillDecls('about.color', 'window.bg', 'about.color')} ${fontDecls('about')} }\n`;

  // Selector buttons: unselected base + selected state, per button.
  for (const key of TOGGLE_KEYS) {
    const sel = TEXT_SELECTORS[key];
    const width = eDim(key + '.strokeWidth', PILL_REGULAR);
    css += `${sel} { background-color: ${v(key + '.unselFill')};` +
      ` color: ${v(key + '.unselText')};` +
      ` border: ${width}px solid ${v(key + '.stroke')};` +
      ` border-radius: ${eDim(key + '.cornerRadius', PILL_RADIUS)}px;` +
      ` ${fontDecls(key)} }\n`;
    css += `.fork-seg-input:checked + ${sel} { background-color: ${v(key + '.selFill')};` +
      ` color: ${v(key + '.selText')};` +
      // The rest of the 3 px frame drawn inside the border, so selecting does not move anything.
      ` box-shadow: inset 0 0 0 ${Math.max(0, PILL_MAIN - width)}px ${v(key + '.stroke')};` +
      ` font-weight: bold; }\n`;
  }

  // Files to send / Directory to send / Receive in …
  // The start button is the one thing the page is for, so it is the main pill; the other two are
  // regular pills in the same colours.
  css += `#startButton, #sendDirButton, #lastDirButton { ${pillDecls('start.color', 'start.fill', 'start.stroke')}` +
    ` border-width: ${PILL_REGULAR}px;` +
    ` border-radius: ${eDim('start.cornerRadius', PILL_RADIUS)}px; ${fontDecls('start')} }\n`;
  css += `#startButton { border-width: ${eDim('start.strokeWidth', PILL_MAIN)}px; font-weight: bold; }\n`;

  // The two buttons under the logo. The Devices button is styled from the UI button's box
  // keys, because the two read as one pair of controls; each keeps its own text settings.
  css += `#uiButton, #devicesButton { border-width: ${eDim('uiButton.strokeWidth', PILL_REGULAR)}px;` +
    ` border-radius: ${eDim('uiButton.cornerRadius', PILL_RADIUS)}px; }\n`;
  css += `#uiButton { ${pillDecls('uiButton.color', 'uiButton.bg', 'uiButton.stroke')} ${fontDecls('uiButton')} }\n`;
  css += `#devicesButton { ${pillDecls('devicesButton.color', 'uiButton.bg', 'uiButton.stroke')} ${fontDecls('devicesButton')} }\n`;

  // Cancel button.
  css += `#cancelButton { ${pillDecls('cancel.color', 'cancel.fill', 'cancel.stroke')}` +
    ` border-width: ${eDim('cancel.strokeWidth', PILL_REGULAR)}px;` +
    ` border-radius: ${eDim('cancel.cornerRadius', PILL_RADIUS)}px; ${fontDecls('cancel')} }\n`;

  // Password dialog: the card, the question, the box that takes the password, and the
  // Cancel / OK pills — Cancel regular, OK the main one.
  css += `#promptCard { border-width: ${eDim('promptStrokeWidth', 2)}px;` +
    ` border-radius: ${eDim('promptCornerRadius', 16)}px; }\n`;
  css += `#promptMessage { ${textDecls('promptMessage')} }\n`;
  css += `#promptInput { border-width: ${eDim('password.strokeWidth', BORDER_WIDTH)}px;` +
    ` border-radius: ${eDim('password.cornerRadius', CORNER_RADIUS)}px; ${textDecls('password')} }\n`;
  css += `#promptCard #promptCancel, #promptCard #promptOk { ${pillDecls('promptButton.color', 'promptButton.bg', 'promptButton.stroke')}` +
    ` border-width: ${eDim('promptButton.strokeWidth', PILL_REGULAR)}px;` +
    ` border-radius: ${eDim('promptButton.cornerRadius', PILL_RADIUS)}px; ${fontDecls('promptButton')} }\n`;
  css += `#promptCard #promptOk { border-width: ${PILL_MAIN}px; font-weight: bold; }\n`;

  // Output box.
  css += `#outputBox { border: ${eDim('output.strokeWidth', BORDER_WIDTH)}px solid ${v('output.stroke')};` +
    ` border-radius: ${eDim('output.cornerRadius', CORNER_RADIUS)}px; ${textDecls('output')} }\n`;

  // Progress bars: one thickness for the single transfer's two and for every lane's.
  const barHeight = eDim('progress.height', 15);
  css += `#progressBar, #totalProgressBar, .fork-lane-bar { height: ${barHeight}px; min-height: ${barHeight}px; }\n`;

  // The UI page and the About dialog (page.* / about*).
  css += `#forkSettingsPage .fork-page-title { ${textDecls('page.title')} }\n`;
  css += `#forkSettingsPage .fork-section-title { ${textDecls('page.section')} }\n`;
  css += `#forkSettingsPage .fork-element-title { ${textDecls('page.element')} }\n`;
  css += `#forkSettingsPage .fork-label { ${textDecls('page.label')} }\n`;
  css += `#forkSettingsPage .fork-note, #forkSettingsPage .fork-row-note, #forkSettingsPage .fork-value-note,` +
    ` #forkSettingsPage .fork-action-summary:not(.fork-warn) { ${textDecls('page.note')} }\n`;
  css += `#forkSettingsPage .fork-btn, #forkAboutModal .fork-btn { ${pillDecls('page.button.color', 'page.button.bg', 'page.button.stroke')}` +
    ` border-width: ${eDim('page.button.strokeWidth', PILL_REGULAR)}px;` +
    ` border-radius: ${eDim('page.button.cornerRadius', PILL_RADIUS)}px; ${fontDecls('page.button')} }\n`;
  css += `#forkAboutModal .fork-about-title { ${textDecls('aboutTitle')} }\n`;
  css += `#forkAboutModal .fork-about-body { ${textDecls('aboutBody')} }\n`;

  return css;
}

// Set label texts on the main page (only surfaces whose element carries its own text).
function applyTexts() {
  for (const key of ['title', 'about', 'uiButton', 'devicesButton', 'bluetooth', 'modeInstruction', 'connectionInstruction',
    'peerInstruction', 'send', 'receive', 'hotspot', 'sharedNetwork', 'androidOs', 'iosOs', 'linuxOs',
    'macOs', 'windowsOs', 'cancel']) {
    const target = document.querySelector(TEXT_SELECTORS[key]);
    if (target) target.innerText = Settings.textOr(key + '.text', DEFAULT_TEXTS[key + '.text']);
  }
  // Version label: full versionName, no "Version" prefix, unless overridden.
  const versionEl = document.querySelector(TEXT_SELECTORS.version);
  if (versionEl) versionEl.innerText = Settings.textOr('version.text', appVersion || versionEl.innerText);
  // Start button text depends on the current mode; the directory button says the same thing
  // whichever mode it is in, since it is only shown while sending.
  const startEl = document.getElementById('startButton');
  const receiveChecked = document.getElementById('receiveButton');
  if (startEl) startEl.innerText = startLabel(receiveChecked && receiveChecked.checked ? 'receive' : 'send');
  const sendDirEl = document.getElementById('sendDirButton');
  if (sendDirEl) sendDirEl.innerText = Settings.textOr('start.dirText', DEFAULT_TEXTS['start.dirText']);
  // Welcome text: swap only while the box still shows a known default/hint, never over transfer logs.
  const out = document.getElementById('outputBox');
  if (out) {
    const hint = Settings.textOr('output.hint', DEFAULT_TEXTS['output.hint']);
    const known = [DEFAULT_TEXTS['output.hint'], lastAppliedHint].filter(Boolean);
    if (known.some((k) => out.innerText.trim() === k.trim())) out.innerText = hint;
    lastAppliedHint = hint;
  }
}
let lastAppliedHint = null;

function startLabel(mode) {
  return mode === 'receive'
    ? Settings.textOr('start.folderText', DEFAULT_TEXTS['start.folderText'])
    : Settings.textOr('start.filesText', DEFAULT_TEXTS['start.filesText']);
}

// Luminance-preserving logo colorize (Android's ColorMatrix port): each pixel keeps its brightness
// but takes the tint's hue, so the carpet's "FC" detail stays visible. Never applied to QR codes —
// only to the idle logo <img data-logo>.
function applyLogoTint() {
  const img = document.querySelector('#qrcode img[data-logo]');
  if (!img) return;
  const tint = colour('logo.tint');
  const src = img.getAttribute('data-original') || img.getAttribute('src');
  const base = new Image();
  base.onload = () => {
    const canvas = document.createElement('canvas');
    canvas.width = base.naturalWidth;
    canvas.height = base.naturalHeight;
    const ctx = canvas.getContext('2d');
    ctx.drawImage(base, 0, 0);
    const image = ctx.getImageData(0, 0, canvas.width, canvas.height);
    const d = image.data;
    const r = parseInt(tint.slice(1, 3), 16) / 255;
    const g = parseInt(tint.slice(3, 5), 16) / 255;
    const b = parseInt(tint.slice(5, 7), 16) / 255;
    for (let i = 0; i < d.length; i += 4) {
      const lum = 0.299 * d[i] + 0.587 * d[i + 1] + 0.114 * d[i + 2];
      d[i] = Math.round(lum * r);
      d[i + 1] = Math.round(lum * g);
      d[i + 2] = Math.round(lum * b);
    }
    ctx.putImageData(image, 0, 0);
    img.setAttribute('data-original', src);
    img.src = canvas.toDataURL();
  };
  base.src = src;
}

function applyAll() {
  let styleEl = document.getElementById('forkTheme');
  if (!styleEl) {
    styleEl = document.createElement('style');
    styleEl.id = 'forkTheme';
    document.head.appendChild(styleEl);
  }
  styleEl.textContent = buildThemeCss();
  // The first-paint ground written in customize.css is replaced by the role as soon as there is one.
  document.documentElement.style.backgroundColor = v('window.bg');
  applyTexts();
  applyLogoTint();
}

// ── Building blocks ───────────────────────────────────────────────────────────────────────────

export function el(tag, className, text) {
  const e = document.createElement(tag);
  if (className) e.className = className;
  if (text !== undefined) e.innerText = text;
  return e;
}

/**
 * A pill button. `main` is the one action its block is for (3 px, bold); `danger` lends the
 * delete red to frame and label; `opens` adds the › of something that opens; `icon` + `tint`
 * put a role-coloured icon before the label.
 */
export function pill(label, options = {}) {
  const button = el('button', 'fork-pill');
  button.type = 'button';
  if (options.main) button.classList.add('fork-main');
  if (options.danger) button.classList.add('fork-danger');
  if (options.mini) button.classList.add('fork-mini');
  if (options.className) button.className += ' ' + options.className;
  if (options.icon) button.appendChild(icon(options.icon, options.danger ? 'delete' : options.tint));
  button.appendChild(el('span', 'fork-pill-label', label));
  if (options.opens) button.appendChild(el('span', 'fork-chevron', '›'));
  return button;
}

/** The theme's switch: an outlined pill with a dot. `small` is the size used in menus and lists. */
export function switchInput(checked, small) {
  const input = el('input', small ? 'fork-switch fork-switch-small' : 'fork-switch');
  input.type = 'checkbox';
  input.setAttribute('role', 'switch');
  input.checked = !!checked;
  return input;
}

// ── Dialogs ───────────────────────────────────────────────────────────────────────────────────
//
// One box for every dialog: the dialog ground, an outline, pills right-aligned at the foot. An
// overlay may carry `_dismiss`, which is what Escape runs on it — a dialog somebody is waiting
// on answers "cancelled" there instead of vanishing without a word.

/**
 * A dialog with a title, a body and pills. Each action decides what to close. The rightmost
 * action is the one the dialog is for, unless an action says `main` itself; `danger` makes it
 * the red of something that deletes.
 */
export function forkInfo(title, body, actions, options = {}) {
  const overlay = el('div', 'fork-overlay');
  const box = el('div', 'fork-info-box');
  box.appendChild(el('div', 'fork-info-title', title));
  if (body) box.appendChild(el('div', 'fork-info-body', body));
  const row = el('div', 'fork-info-actions');
  const close = () => overlay.remove();
  const explicit = actions.some((a) => a.main !== undefined);
  actions.forEach((action, i) => {
    const main = explicit ? !!action.main : i === actions.length - 1;
    const button = pill(action.label, { main, danger: action.danger });
    button.onclick = () => action.onClick(close);
    row.appendChild(button);
  });
  box.appendChild(row);
  overlay.appendChild(box);
  overlay._dismiss = () => { close(); if (options.onDismiss) options.onDismiss(); };
  document.body.appendChild(overlay);
  return overlay;
}

/** Single-OK dialog that closes only itself — every failure message uses this. */
export function forkAlert(title, body) {
  forkInfo(title, body, [{ label: 'OK', onClick: (close) => close() }]);
}

/**
 * A question with two answers, resolving to true or false. Escape is “no”. `danger` makes the
 * confirming pill red, for a confirmation that deletes or resets.
 */
export function forkConfirm(title, body, options = {}) {
  return new Promise((resolve) => {
    forkInfo(title, body, [
      { label: options.cancel || 'Cancel', onClick: (close) => { close(); resolve(false); } },
      {
        label: options.confirm || 'OK',
        danger: !!options.danger,
        onClick: (close) => { close(); resolve(true); },
      },
    ], { onDismiss: () => resolve(false) });
  });
}

/**
 * A list to choose one thing from: a full-width pill per item, plus a Cancel pill. An item is a
 * string, or {label, sub, icon, tint, family, current, divider}: `sub` stands under the label,
 * `icon` + `tint` give the row its role-coloured icon (a list where no item has one stays
 * without an icon column), `family` draws the label in that font, `current` marks the value held
 * now, and `divider` starts a new group with a line above the row. `onCancel` runs when the
 * list is left without a choice.
 */
export function forkChooser(title, items, onPick, onCancel) {
  const overlay = el('div', 'fork-overlay');
  const box = el('div', 'fork-info-box');
  box.appendChild(el('div', 'fork-info-title', title));
  const list = el('div', 'fork-list');
  const cancelled = () => { overlay.remove(); if (onCancel) onCancel(); };
  items.forEach((raw, i) => {
    const item = typeof raw === 'string' ? { label: raw } : raw;
    if (item.divider) list.appendChild(el('div', 'fork-list-divider'));
    const row = el('button', item.current ? 'fork-list-pill fork-current' : 'fork-list-pill');
    row.type = 'button';
    if (item.icon) row.appendChild(icon(item.icon, item.tint));
    const text = el('span', 'fork-list-text', item.label);
    if (item.family) text.style.fontFamily = `"${item.family}"`;
    row.appendChild(text);
    if (item.current) row.appendChild(el('span', null, '✓'));
    row.onclick = () => { overlay.remove(); onPick(i); };
    list.appendChild(row);
    if (item.sub) {
      const sub = el('div', 'fork-list-sub', item.sub);
      if (!item.icon) sub.style.paddingLeft = '15.25px';
      list.appendChild(sub);
    }
  });
  box.appendChild(list);
  const row = el('div', 'fork-info-actions');
  const cancel = pill('Cancel');
  cancel.onclick = cancelled;
  row.appendChild(cancel);
  box.appendChild(row);
  overlay.appendChild(box);
  overlay._dismiss = cancelled;
  document.body.appendChild(overlay);
  return overlay;
}

/** A chooser as a promise: the index picked, or null when it was left without a choice. */
export function forkChoose(title, items) {
  return new Promise((resolve) => {
    forkChooser(title, items, (index) => resolve(index), () => resolve(null));
  });
}

// ── Menus ─────────────────────────────────────────────────────────────────────────────────────

let openMenu = null;

function closeMenu() {
  if (openMenu) openMenu.remove();
  openMenu = null;
}

/**
 * A popup menu at a point of the window — what a right-click opens.
 *
 * `entries` are, in order: {divider: true} for the line between two groups; a toggle
 * {label, icon, tint, checked, onToggle(checked)}; or an action
 * {label, icon, tint, opens, danger, disabled, onClick}. An action row is an outlined pill
 * across the whole menu with its icon inside at the left, and a › at its right end when it
 * opens something; a toggle row is plain and carries the outlined switch. The header is plain
 * text. The menu is shifted so that all of it is inside the window: it only ever scrolls when
 * it is taller than the window itself.
 */
export function forkMenu(point, header, entries) {
  closeMenu();
  const layer = el('div', 'fork-menu-layer');
  const menu = el('div', 'fork-menu');
  menu.setAttribute('role', 'menu');
  if (header) menu.appendChild(el('div', 'fork-menu-header', header));

  for (const entry of entries) {
    if (entry.divider) {
      menu.appendChild(el('div', 'fork-menu-divider'));
      continue;
    }
    if (entry.onToggle) {
      const row = el('label', 'fork-menu-toggle');
      row.appendChild(icon(entry.icon, entry.tint));
      row.appendChild(el('span', 'fork-menu-text', entry.label));
      const toggle = switchInput(entry.checked, true);
      toggle.onchange = () => {
        const checked = toggle.checked;
        // Long enough to see the dot arrive, then the menu is done.
        setTimeout(closeMenu, 220);
        entry.onToggle(checked);
      };
      row.appendChild(toggle);
      menu.appendChild(row);
      continue;
    }
    const row = el('button', entry.danger ? 'fork-menu-item fork-danger' : 'fork-menu-item');
    row.type = 'button';
    row.setAttribute('role', 'menuitem');
    row.disabled = !!entry.disabled;
    row.appendChild(icon(entry.icon, entry.danger ? 'delete' : entry.tint));
    row.appendChild(el('span', 'fork-menu-text', entry.label));
    if (entry.opens) row.appendChild(el('span', 'fork-chevron', '›'));
    row.onclick = () => { closeMenu(); entry.onClick(); };
    menu.appendChild(row);
  }

  // A press anywhere else closes the menu and is not passed on.
  layer.addEventListener('pointerdown', (event) => { if (event.target === layer) closeMenu(); });
  layer.addEventListener('contextmenu', (event) => { event.preventDefault(); if (event.target === layer) closeMenu(); });
  layer.appendChild(menu);
  document.body.appendChild(layer);
  openMenu = layer;

  // Placed at the pointer, then shifted up and left by whatever would fall outside the window.
  const margin = 8;
  const rect = menu.getBoundingClientRect();
  const left = Math.max(margin, Math.min(point.x, window.innerWidth - rect.width - margin));
  const top = Math.max(margin, Math.min(point.y, window.innerHeight - rect.height - margin));
  menu.style.left = `${left}px`;
  menu.style.top = `${top}px`;
  return layer;
}

// ── About dialog (themeable, replaces the native alert) ───────────────────────────────────────

function showAbout(message) {
  let modal = document.getElementById('forkAboutModal');
  if (!modal) {
    modal = document.createElement('div');
    modal.id = 'forkAboutModal';
    modal.className = 'fork-overlay';
    const box = el('div', 'fork-modal-box');
    box.appendChild(el('div', 'fork-about-title'));
    box.appendChild(el('div', 'fork-about-body'));
    const row = el('div', 'fork-info-actions');
    const close = pill('Close', { className: 'fork-btn' });
    close.id = 'forkAboutClose';
    close.onclick = () => { modal.style.display = 'none'; };
    row.appendChild(close);
    box.appendChild(row);
    modal.appendChild(box);
    document.body.appendChild(modal);
    modal.addEventListener('click', (e) => { if (e.target === modal) modal.style.display = 'none'; });
  }
  modal.querySelector('.fork-about-title').innerText =
    Settings.textOr('title.text', DEFAULT_TEXTS['title.text']);
  modal.querySelector('.fork-about-body').innerText = message;
  modal.style.display = 'flex';
}

// ── The UI page: rows ─────────────────────────────────────────────────────────────────────────
//
// A row that sets something (a swatch, a slider, a text, a value that opens a picker) is plain:
// icon, label, the control. A row that carries an explanation has a bold label, the explanation
// beneath in dim gold and a thin dim-gold rule down its left. A row that runs or opens
// something is an outlined pill as wide as its label, its explanation beneath.

function surfaceKeys(surface) {
  const keys = [];
  for (const l of surface.labels) keys.push(l.key);
  if (surface.hasTextColor) keys.push(surface.key + '.color');
  keys.push(surface.key + '.family', surface.key + '.style', surface.key + '.size');
  for (const c of surface.extraColors) keys.push(c.key);
  for (const d of surface.extraDims) keys.push(d.key);
  return keys;
}

/** Every swatch on the open page: a role that follows another shows the new colour at once. */
let swatchSyncs = [];

function colourChanged() {
  applyAll();
  for (const sync of swatchSyncs) sync();
}

/** The start of a control row: its icon, then its label — with the explanation, when it has one. */
function rowShell(iconName, tint, label, note) {
  const row = el('div', note ? 'fork-row fork-explained' : 'fork-row');
  row.appendChild(icon(iconName, tint));
  if (note) {
    const text = el('div', 'fork-row-text');
    text.appendChild(el('div', 'fork-label fork-row-label', label));
    text.appendChild(el('div', 'fork-row-note', note));
    row.appendChild(text);
  } else {
    row.appendChild(el('span', 'fork-label', label));
  }
  return row;
}

/** The small pill that puts one value back to its default. */
function defaultPill(onClick) {
  const button = pill('Default', { mini: true });
  button.title = 'Back to the default';
  button.onclick = onClick;
  return button;
}

// The icon of a colour row of the older catalog, told from what the label says it colours.
function colourIcon(label) {
  if (/border|frame/i.test(label)) return 'button';
  if (/text/i.test(label)) return 'text';
  return 'drop';
}

// One colour row: the swatch, and a pill that resets this one role to its default.
function colorRow(field) {
  const info = roleInfo(field.key) || {};
  const label = field.label || info.label || field.key;
  const row = rowShell(info.icon || colourIcon(label), info.tint || 'view', label, info.note);
  const input = document.createElement('input');
  input.type = 'color';
  const sync = () => { input.value = colour(field.key); };
  sync();
  swatchSyncs.push(sync);
  input.oninput = () => { Settings.setColor(field.key, input.value); colourChanged(); };
  row.appendChild(input);
  row.appendChild(defaultPill(() => { Settings.setColor(field.key, null); colourChanged(); }));
  return row;
}

// One dimension row: slider 0..max with live value + a reset.
function dimRow(field) {
  const row = rowShell(/radius/i.test(field.label) ? 'corner' : 'lines', 'view', field.label);
  const input = document.createElement('input');
  input.type = 'range';
  input.min = '0';
  input.max = String(field.max);
  input.step = '1';
  const valueLabel = el('span', 'fork-value-note', '');
  const sync = () => {
    const value = eDim(field.key, field.def);
    input.value = String(value);
    valueLabel.innerText = `${value}px${Settings.sizeOrNull(field.key) === null ? ' (default)' : ''}`;
  };
  sync();
  input.oninput = () => { Settings.setDim(field.key, Number(input.value)); sync(); applyAll(); };
  row.appendChild(input);
  row.appendChild(valueLabel);
  row.appendChild(defaultPill(() => { Settings.remove([field.key]); sync(); applyAll(); }));
  return row;
}

function textRow(field) {
  const row = rowShell('text', 'message', field.label);
  const input = document.createElement('input');
  input.type = 'text';
  input.className = 'fork-text-input';
  input.placeholder = DEFAULT_TEXTS[field.key] || '(default)';
  input.value = Settings.text(field.key);
  input.oninput = () => { Settings.setText(field.key, input.value); applyAll(); };
  row.appendChild(input);
  return row;
}

/** A value that opens a picker: the field's dress with the value in it and a ⌄. */
function valueButton(onClick) {
  const button = el('button', 'fork-value');
  button.type = 'button';
  const text = el('span', null, '');
  button.appendChild(text);
  button.appendChild(el('span', null, '⌄'));
  button.onclick = onClick;
  return { button, text };
}

/** Reads a font file 白い熊 picked, stores it, and hands its family name on. */
function addExternalFont(onAdded) {
  const picker = document.createElement('input');
  picker.type = 'file';
  picker.accept = '.ttf,.otf,.woff,.woff2';
  picker.onchange = () => {
    const file = picker.files[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      const name = file.name.replace(/\.[^.]+$/, '');
      try {
        Settings.addFontFile(name, reader.result);
      } catch (e) {
        forkAlert('Could not store the font', 'It is too large for the app’s local storage.');
        return;
      }
      registerFonts();
      onAdded('ext:' + name);
    };
    reader.readAsDataURL(file);
  };
  picker.click();
}

function fontRows(surface) {
  const rows = [];

  // Font family: the value opens a list, each family drawn in its own glyphs, and the list
  // ends — after a divider — with the row that brings in a font file.
  const famRow = rowShell('font', 'view', 'Font');
  const famValue = valueButton(() => {
    const values = Settings.familyValues();
    const labels = Settings.familyLabels();
    const current = Settings.family(surface.key + '.family');
    const items = values.map((value, i) => ({
      label: labels[i], family: value, icon: 'font', tint: 'view', current: value === current,
    }));
    items.push({ label: 'Add external font…', icon: 'import', tint: 'share', divider: true });
    forkChooser('Font', items, (index) => {
      const set = (value) => { Settings.setFamily(surface.key + '.family', value); syncFamily(); applyAll(); };
      if (index === values.length) addExternalFont(set);
      else set(values[index]);
    });
  });
  const syncFamily = () => {
    const value = Settings.family(surface.key + '.family');
    const at = Settings.familyValues().indexOf(value);
    famValue.text.innerText = at >= 0 ? Settings.familyLabels()[at] : value;
    famValue.text.style.fontFamily = value ? `"${value}"` : '';
  };
  syncFamily();
  famRow.appendChild(famValue.button);
  rows.push(famRow);

  // Style: a plain list of choices.
  const styleRow = rowShell('style', 'view', 'Style');
  const styleValue = valueButton(() => {
    const current = Settings.style(surface.key + '.style');
    forkChooser('Style', STYLE_LABELS.map((label, i) => ({ label, current: STYLE_VALUES[i] === current })),
      (index) => { Settings.setStyle(surface.key + '.style', STYLE_VALUES[index]); syncStyle(); applyAll(); });
  });
  const syncStyle = () => {
    styleValue.text.innerText = STYLE_LABELS[STYLE_VALUES.indexOf(Settings.style(surface.key + '.style'))] || STYLE_LABELS[0];
  };
  syncStyle();
  styleRow.appendChild(styleValue.button);
  rows.push(styleRow);

  // Size.
  const sizeRow = rowShell('size', 'view', 'Text size (px, 0 = default)');
  const sizeInput = document.createElement('input');
  sizeInput.type = 'number';
  sizeInput.min = '0';
  sizeInput.max = '96';
  sizeInput.className = 'fork-num-input';
  sizeInput.value = String(Settings.size(surface.key + '.size') || 0);
  sizeInput.oninput = () => { Settings.setSize(surface.key + '.size', Number(sizeInput.value)); applyAll(); };
  sizeRow.appendChild(sizeInput);
  rows.push(sizeRow);

  return rows;
}

/**
 * A row that runs or opens something: the pill, and its explanation beneath. Returns the row;
 * `row.summary` is the explanation, for a row whose line is filled in later.
 */
export function actionRow(label, note, options = {}) {
  const row = el('div', 'fork-action-row');
  const button = pill(label, {
    main: options.main, danger: options.danger, opens: options.opens,
    icon: options.icon, tint: options.tint, className: 'fork-action-pill',
  });
  button.onclick = options.onClick;
  row.appendChild(button);
  const summary = el('div', 'fork-action-summary', note || '');
  row.appendChild(summary);
  row.summary = summary;
  return row;
}

/** “Reset to default” for one element: acts in place, so no ›; it removes, so its icon is red. */
function resetRow(keys) {
  return actionRow('Reset to default', 'Clears everything set for this element.', {
    icon: 'reset', tint: 'delete',
    onClick: () => { Settings.remove(keys); applyAll(); rebuildSettingsPage(); },
  });
}

function buildSurface(surface, container) {
  const box = el('div', 'fork-element');
  box.appendChild(el('div', 'fork-element-title', surface.title));
  const controls = el('div', 'fork-controls');

  for (const label of surface.labels) controls.appendChild(textRow(label));
  if (surface.hasTextColor) {
    controls.appendChild(colorRow(colorField(surface.key + '.color', surface.colorLabel || 'Text colour')));
  }
  for (const row of fontRows(surface)) controls.appendChild(row);
  for (const c of surface.extraColors) controls.appendChild(colorRow(c));
  for (const d of surface.extraDims) controls.appendChild(dimRow(d));
  controls.appendChild(resetRow(surfaceKeys(surface)));

  box.appendChild(controls);
  container.appendChild(box);
}

function buildColorGroup(group, container) {
  const box = el('div', 'fork-element');
  box.appendChild(el('div', 'fork-element-title', group.title));
  const controls = el('div', 'fork-controls');
  const colors = group.colors || [];
  const dims = group.dims || [];
  const roles = group.roles || [];
  for (const c of colors) controls.appendChild(colorRow(c));
  for (const d of dims) controls.appendChild(dimRow(d));
  for (const key of roles) controls.appendChild(colorRow({ key }));
  controls.appendChild(resetRow(colors.map((c) => c.key).concat(dims.map((d) => d.key), roles)));
  box.appendChild(controls);
  container.appendChild(box);
}

// ── Export / Import ───────────────────────────────────────────────────────────────────────────
//
// The desktop twin of the Android app's Export/Import panel: the panel, the pill bar at its foot,
// and an info dialog on the way out. Closing behaviour (白い熊, 2026-07-25): acknowledging a
// **successful** export or import closes the whole chain — info dialog, panel, and the UI page
// beneath them. Failures close only the info dialog, so the panel stays open and the problem can
// be fixed on the spot.

/** The line under the page's Export / Import pill: the directory and its newest backup. */
async function exportSummary() {
  const dir = Backup.exportDir();
  if (!dir) return { text: 'No backup directory set.', warn: true };
  const newest = (await Backup.listBackups())[0];
  if (!newest) return { text: `${dir} — no backup yet.`, warn: true };
  const stamp = new Date(newest.modified).toLocaleString();
  return { text: `${dir} — latest ${stamp}.`, warn: false };
}

async function refreshExportSummary() {
  const view = document.getElementById('forkExportSummary');
  if (!view) return;
  const { text, warn } = await exportSummary();
  view.innerText = text;
  view.classList.toggle('fork-warn', warn);
}

const CATEGORY_ICONS = { appearance: 'window', page: 'sliders', fonts: 'font' };

function openExportPanel() {
  const overlay = el('div', 'fork-overlay');
  const box = el('div', 'fork-panel-box');
  overlay.appendChild(box);

  const selected = new Set(Backup.CATEGORIES.map((c) => c.id));

  const closePanel = () => { overlay.remove(); refreshExportSummary(); };
  const closeChain = () => {
    overlay.remove();
    const page = document.getElementById('forkSettingsPage');
    if (page) page.style.display = 'none';
  };

  overlay.addEventListener('click', (e) => { if (e.target === overlay) closePanel(); });
  overlay._dismiss = closePanel;
  document.body.appendChild(overlay);

  async function pickDirectory() {
    const picked = await dialog.open({ multiple: false, directory: true, defaultPath: Backup.exportDir() || undefined });
    if (!picked) return;
    Backup.setExportDir(picked);
    render();
  }

  async function onExport() {
    if (!Backup.exportDir()) { await pickDirectory(); return; }
    if (!selected.size) { forkAlert('Export', 'No categories selected.'); return; }
    try {
      const result = await Backup.runExport(selected, appVersion);
      const body = [
        result.path,
        `${Backup.humanSize(result.bytes)} · ${result.lines.length} categories`,
        ...result.lines.map((line) => `· ${line}`),
      ].join('\n');
      forkInfo('Export finished', body, [
        { label: 'OK', onClick: (close) => { close(); closeChain(); } },
      ]);
    } catch (e) {
      forkAlert('Export failed', String((e && e.message) || e));
    }
  }

  async function onImport() {
    if (!Backup.exportDir()) { await pickDirectory(); return; }
    if (!selected.size) { forkAlert('Import', 'No categories selected.'); return; }
    const backups = await Backup.listBackups();
    if (!backups.length) {
      forkAlert('Import', 'No backup of this app was found in the backup directory.');
      return;
    }
    forkChooser(
      'Choose a backup',
      backups.map((b) => ({ label: b.name, sub: Backup.humanSize(b.size), icon: 'archive', tint: 'share' })),
      async (which) => {
        const dir = Backup.exportDir().replace(/\/+$/, '');
        try {
          const result = await Backup.runImport(`${dir}/${backups[which].name}`, selected);
          // The theme engine caches the store in memory — reload it and repaint immediately.
          Settings.load();
          registerFonts();
          applyAll();
          const body = [
            ...(result.lines.length ? result.lines.map((line) => `· ${line}`)
              : ['Nothing in this backup matched the selected categories.']),
            '',
            'Restart the app for everything to take effect.',
          ].join('\n');
          forkInfo('Import finished', body, [
            { label: 'Later', onClick: (close) => { close(); closeChain(); } },
            { label: 'Restart now', onClick: () => Backup.restartApp() },
          ]);
        } catch (e) {
          forkAlert('Import failed', String((e && e.message) || e));
        }
      },
    );
  }

  async function render() {
    box.innerHTML = '';
    box.appendChild(el('div', 'fork-panel-title', 'Export / Import'));
    box.appendChild(el('div', 'fork-panel-desc',
      'Save everything you have set in this app to one .zip in your backup directory, or restore it ' +
      'from one. Importing merges: categories the file doesn’t carry are left alone.'));

    // Where the backups go: a pill, because it opens the directory picker; the directory itself
    // stands beneath it, in the warning colour while there is none.
    const dir = Backup.exportDir();
    const dirRow = actionRow('Backup directory', dir || 'No backup directory set — press the pill to choose one.', {
      icon: 'directory', tint: 'view', opens: true, onClick: pickDirectory,
    });
    if (!dir) dirRow.summary.classList.add('fork-warn');
    box.appendChild(dirRow);

    const status = el('div', 'fork-status-line', 'Reading the backup directory…');
    box.appendChild(status);
    if (!dir) {
      status.innerText = 'No backup directory set.';
      status.classList.add('fork-warn');
    } else {
      const newest = (await Backup.listBackups())[0];
      if (newest) {
        status.innerText = `Latest export: ${new Date(newest.modified).toLocaleString()} ` +
          `(${Backup.humanSize(newest.size)}).`;
      } else {
        status.innerText = 'No backup in this directory yet.';
        status.classList.add('fork-warn');
      }
    }

    box.appendChild(el('div', 'fork-hairline'));

    // What to carry: several can be chosen, so these stay selection marks, not switches.
    const allRow = el('label', 'fork-check-row');
    const allBox = el('input', 'fork-check');
    allBox.type = 'checkbox';
    allBox.checked = selected.size === Backup.CATEGORIES.length;
    allBox.onchange = () => {
      if (allBox.checked) Backup.CATEGORIES.forEach((c) => selected.add(c.id));
      else selected.clear();
      render();
    };
    allRow.appendChild(allBox);
    allRow.appendChild(icon('check', 'view'));
    const allLabel = el('span', null, 'Select all');
    allLabel.style.fontWeight = 'bold';
    allRow.appendChild(allLabel);
    box.appendChild(allRow);

    for (const cat of Backup.CATEGORIES) {
      const row = el('label', 'fork-check-row');
      const check = el('input', 'fork-check');
      check.type = 'checkbox';
      check.checked = selected.has(cat.id);
      check.onchange = () => { if (check.checked) selected.add(cat.id); else selected.delete(cat.id); };
      row.appendChild(check);
      row.appendChild(icon(CATEGORY_ICONS[cat.id] || 'file', 'view'));
      row.appendChild(el('span', null, cat.label));
      box.appendChild(row);
    }

    box.appendChild(el('div', 'fork-hairline'));

    // The button bar: Cancel alone on the left, Import + Export grouped on the right — Export,
    // the one thing the panel is for, the main pill.
    const bar = el('div', 'fork-pill-bar');
    const cancel = pill('Cancel');
    cancel.onclick = closePanel;
    bar.appendChild(cancel);
    bar.appendChild(el('div', 'fork-pill-spacer'));
    const importBtn = pill('Import', { icon: 'import', tint: 'share' });
    importBtn.onclick = onImport;
    bar.appendChild(importBtn);
    const exportBtn = pill('Export', { main: true, icon: 'export', tint: 'share' });
    exportBtn.onclick = onExport;
    bar.appendChild(exportBtn);
    box.appendChild(bar);
  }

  render();
}

// ── The UI page: sections, folding, the chip index ────────────────────────────────────────────
//
// Every top-level section folds on a click on its heading — › folded, ⌄ open — and every one is
// folded until 白い熊 opens it. Right under the page title sits the index: one small chip per
// section. A click on a chip unfolds its section and scrolls to it; a chip that is held or
// dragged is moved, the others making room under the pointer as it goes, and the sections take
// the same order when it is let go. Order and fold state are stored with the page's own
// settings, so they survive a restart and travel in the backup.

/** Every section of the page, in its default order: {id, title, note, build(body)}. */
function pageSections() {
  const sections = [{
    id: 'export',
    title: 'Export / Import',
    note: 'Carry everything you set in this app to another device, or put it back.',
    build(body) {
      // The section's one panel, so its pill is the main one: thick, bold, › because it opens.
      const row = actionRow('Export / Import…', 'Reading the backup directory…', {
        main: true, opens: true, icon: 'export', tint: 'share', onClick: openExportPanel,
      });
      row.classList.add('fork-level-row');
      row.summary.id = 'forkExportSummary';
      body.appendChild(row);
      refreshExportSummary();
    },
  }];

  for (const section of SECTIONS) {
    sections.push({
      id: section.id,
      title: section.title,
      note: section.note,
      build(body) {
        for (const surface of (section.surfaces || [])) buildSurface(surface, body);
        for (const group of (section.colorGroups || [])) buildColorGroup(group, body);
      },
    });
  }

  sections.push({
    id: 'maintenance',
    title: 'Maintenance',
    note: 'Put things back the way they came.',
    build(body) {
      const order = actionRow('Reset section order',
        'Puts the sections of this page, and the chips of its index, back in their original order. Nothing else changes.', {
          icon: 'order', tint: 'view',
          onClick: () => { Settings.remove([SECTION_ORDER_KEY]); rebuildSettingsPage(); },
        });
      const colours = actionRow('Reset all colours',
        'Every colour on this page goes back to its default. Texts, fonts, sizes and borders stay as they are.', {
          icon: 'drop', danger: true,
          onClick: async () => {
            const yes = await forkConfirm('Reset all colours?',
              'Every colour set on this page goes back to its default. Texts, fonts, sizes and borders stay as they are.',
              { confirm: 'Reset', danger: true });
            if (!yes) return;
            Settings.remove(ROLE_KEYS);
            applyAll();
            rebuildSettingsPage();
          },
        });
      const everything = actionRow('Reset all to defaults',
        'Everything set on this page goes back to the fork defaults — colours, texts, fonts and borders, the order and folding of the sections, and the fonts you imported.', {
          icon: 'reset', danger: true,
          onClick: async () => {
            const yes = await forkConfirm('Reset everything?',
              'Every customization goes back to the fork defaults, and the fonts you imported are removed.',
              { confirm: 'Reset', danger: true });
            if (!yes) return;
            Settings.clearAll();
            applyAll();
            rebuildSettingsPage();
          },
        });
      for (const row of [order, colours, everything]) {
        row.classList.add('fork-level-row');
        body.appendChild(row);
      }
    },
  });

  return sections;
}

/** The stored order, with sections it does not name yet put where they are by default. */
function sectionOrder(sections) {
  const defaults = sections.map((s) => s.id);
  const stored = (Settings.list(SECTION_ORDER_KEY) || []).filter((id) => defaults.includes(id));
  const order = stored.filter((id, i) => stored.indexOf(id) === i);
  defaults.forEach((id, at) => {
    if (order.includes(id)) return;
    // After the nearest default predecessor that is already placed, else at the front.
    let after = -1;
    for (let i = at - 1; i >= 0 && after < 0; i--) after = order.indexOf(defaults[i]);
    order.splice(after + 1, 0, id);
  });
  return order;
}

function openSections() { return Settings.list(SECTIONS_OPEN_KEY) || []; }

function setSectionOpen(id, open) {
  const now = openSections().filter((other) => other !== id);
  if (open) now.push(id);
  Settings.setList(SECTIONS_OPEN_KEY, now);
}

/**
 * One section: the thin line, the heading row with its fold mark, and the rows — built only when
 * the section is first opened, so a page of folded sections costs nothing.
 */
function buildSection(def, open) {
  const section = el('div', 'fork-section');
  section.dataset.section = def.id;
  section.appendChild(el('div', 'fork-section-rule'));

  const head = el('div', 'fork-section-head');
  head.setAttribute('role', 'button');
  head.tabIndex = 0;
  head.appendChild(el('div', 'fork-section-title', def.title));
  const fold = el('span', 'fork-fold', '›');
  head.appendChild(fold);
  section.appendChild(head);

  const body = el('div', 'fork-section-body');
  section.appendChild(body);
  let built = false;

  const show = (now) => {
    if (now && !built) {
      built = true;
      if (def.note) body.appendChild(el('div', 'fork-note', def.note));
      def.build(body);
    }
    body.hidden = !now;
    fold.innerText = now ? '⌄' : '›';
    head.setAttribute('aria-expanded', now ? 'true' : 'false');
  };
  show(open);

  const toggle = () => {
    const now = body.hidden;
    show(now);
    setSectionOpen(def.id, now);
  };
  head.onclick = toggle;
  head.addEventListener('keydown', (event) => {
    if (event.key !== 'Enter' && event.key !== ' ') return;
    event.preventDefault();
    toggle();
  });
  // What a chip does: unfold, without ever folding.
  section.unfold = () => { show(true); setSectionOpen(def.id, true); };
  return section;
}

/**
 * One chip of the index. A click jumps. Held for a moment, or dragged a few pixels, it is
 * lifted: a copy rides under the pointer while the chip itself takes, live, the slot of
 * whichever chip the pointer is over. Pointer events rather than HTML5 drag-and-drop, for the
 * reason the device strip gives: the window's own drop handler takes files through that.
 */
function buildChip(def, index, onJump, onDrop) {
  const chip = el('button', 'fork-chip', def.title);
  chip.type = 'button';
  chip.dataset.section = def.id;

  let pressed = null;
  let ghost = null;
  let lifted = false;
  let holdTimer = null;
  let swallowClick = false;
  // The chip this one last changed places with. The pointer is still over it right after the
  // move, and has to leave it before the two can trade again — otherwise they would flap.
  let lastTarget = null;

  const lift = () => {
    if (lifted || !pressed) return;
    lifted = true;
    try { chip.setPointerCapture(pressed.id); } catch (e) { /* the pointer is already gone */ }
    const rect = chip.getBoundingClientRect();
    pressed.dx = pressed.x - rect.left;
    pressed.dy = pressed.y - rect.top;
    ghost = chip.cloneNode(true);
    ghost.classList.add('fork-drag-ghost');
    ghost.style.width = `${rect.width}px`;
    ghost.style.transform = `translate(${rect.left}px, ${rect.top}px)`;
    document.body.appendChild(ghost);
    chip.classList.add('fork-dragging');
  };

  chip.addEventListener('pointerdown', (event) => {
    if (event.button !== 0) return;
    pressed = { x: event.clientX, y: event.clientY, id: event.pointerId };
    lifted = false;
    lastTarget = null;
    holdTimer = setTimeout(lift, 350);
  });

  chip.addEventListener('pointermove', (event) => {
    if (!pressed || event.pointerId !== pressed.id) return;
    if (!lifted) {
      // A few pixels of slack, so a slightly unsteady click stays a click.
      if (Math.hypot(event.clientX - pressed.x, event.clientY - pressed.y) < 6) return;
      clearTimeout(holdTimer);
      lift();
    }
    event.preventDefault();
    ghost.style.transform =
      `translate(${event.clientX - pressed.dx}px, ${event.clientY - pressed.dy}px)`;
    // The copy lets hit-testing through, so this finds the chip lying under the pointer.
    const under = document.elementFromPoint(event.clientX, event.clientY);
    const target = under && under.closest('.fork-chip');
    if (!target || target === chip || target.parentElement !== index) {
      lastTarget = null;
      return;
    }
    if (target === lastTarget) return;
    lastTarget = target;
    const chips = [...index.children];
    index.insertBefore(chip, chips.indexOf(chip) < chips.indexOf(target) ? target.nextSibling : target);
  });

  const release = (event) => {
    if (!pressed || event.pointerId !== pressed.id) return;
    clearTimeout(holdTimer);
    pressed = null;
    if (!lifted) return; // a plain click: the click handler jumps
    lifted = false;
    if (ghost) ghost.remove();
    ghost = null;
    chip.classList.remove('fork-dragging');
    if (chip.hasPointerCapture(event.pointerId)) chip.releasePointerCapture(event.pointerId);
    // Letting go of a lifted chip must not also jump to its section.
    swallowClick = true;
    setTimeout(() => { swallowClick = false; }, 0);
    onDrop([...index.children].map((c) => c.dataset.section));
  };
  chip.addEventListener('pointerup', release);
  chip.addEventListener('pointercancel', release);

  chip.addEventListener('click', () => {
    if (swallowClick) { swallowClick = false; return; }
    onJump(def.id);
  });
  return chip;
}

function rebuildSettingsPage() {
  const page = document.getElementById('forkSettingsPage');
  if (!page || page.style.display === 'none') return;
  const at = page.scrollTop;
  openSettings(); // rebuilds in place
  page.scrollTop = at;
}

function openSettings() {
  let page = document.getElementById('forkSettingsPage');
  if (!page) {
    page = document.createElement('div');
    page.id = 'forkSettingsPage';
    document.body.appendChild(page);
  }
  page.innerHTML = '';
  page.style.display = 'block';
  swatchSyncs = [];

  // The page title, and the way out. Everything that resets lives in Maintenance.
  const header = el('div', 'fork-page-header');
  header.appendChild(el('div', 'fork-page-title',
    `${Settings.textOr('title.text', DEFAULT_TEXTS['title.text'])} UI${appVersion ? ' — ' + appVersion : ''}`));
  const doneBtn = pill('Done', { className: 'fork-btn' });
  doneBtn.onclick = () => { page.style.display = 'none'; applyAll(); };
  const buttons = el('div', 'fork-page-buttons');
  buttons.appendChild(doneBtn);
  header.appendChild(buttons);
  page.appendChild(header);

  const sections = pageSections();
  const byId = new Map(sections.map((s) => [s.id, s]));
  const order = sectionOrder(sections);
  const open = openSections();

  const index = el('div', 'fork-chip-index');
  const holder = el('div', 'fork-sections');
  const built = new Map();

  const jump = (id) => {
    const section = built.get(id);
    if (!section) return;
    section.unfold();
    section.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };
  // The chips already stand in the new order; the sections follow it, each keeping its rows
  // and its fold state because it is moved, not rebuilt.
  const drop = (ids) => {
    Settings.setList(SECTION_ORDER_KEY, ids);
    for (const id of ids) holder.appendChild(built.get(id));
  };

  for (const id of order) {
    const def = byId.get(id);
    index.appendChild(buildChip(def, index, jump, drop));
    const section = buildSection(def, open.includes(id));
    built.set(id, section);
    holder.appendChild(section);
  }
  page.appendChild(index);
  page.appendChild(holder);

  page.scrollTop = 0;
}

// ── Boot ──────────────────────────────────────────────────────────────────────────────────────

window.forkUI = {
  showAbout, startLabel, applyLogoTint, openSettings, applyAll,
  // For main.js, which is loaded before this module and reaches it through the window.
  colour,
  alert: forkAlert,
  confirm: forkConfirm,
  choose: forkChoose,
  icon,
  pill,
  switchInput,
};

window.addEventListener('DOMContentLoaded', async () => {
  Settings.load();
  registerFonts();
  applyAll();
  try {
    appVersion = await window.__TAURI__.app.getVersion();
  } catch (e) { appVersion = ''; }
  applyTexts();
  document.addEventListener('keyup', (e) => {
    if (e.key !== 'Escape') return;
    // Innermost first: a menu, then an open panel or dialog, swallow Escape before the page does.
    if (openMenu) { closeMenu(); return; }
    const overlays = document.querySelectorAll('body > .fork-overlay');
    const topmost = overlays[overlays.length - 1];
    if (topmost && topmost.id !== 'forkAboutModal') {
      if (topmost._dismiss) topmost._dismiss(); else topmost.remove();
      refreshExportSummary();
      return;
    }
    const modal = document.getElementById('forkAboutModal');
    if (modal && modal.style.display !== 'none') { modal.style.display = 'none'; return; }
    const page = document.getElementById('forkSettingsPage');
    if (page && page.style.display !== 'none') page.style.display = 'none';
  });
});
