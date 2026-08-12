// 白い熊 魔法絨毯 UI customization — desktop port of the Android fork's Appearance/Settings engine.
//
// The fork's baseline look is a high-contrast yellow-on-black theme: yellow text, yellow borders,
// black backgrounds — everywhere, unless 白い熊 overrides a specific property. Borders default to a
// 1 px yellow line with a 10 px corner radius; the sliders still start at 0, so a border can be removed.
//
// Every stored value is optional: an absent key means "inherit the fork default"; "Reset" clears keys.
// Mirrors Android's Settings semantics: dims (border width / radius) store an explicit 0 as a valid
// value distinct from "unset", texts store "" as "unset", colors/fonts/styles are absent when unset.

import * as Backup from './backup.js';

// The folder picker for the Export/Import panel (the dialog plugin is already in the capability set).
const { dialog } = window.__TAURI__;

const YELLOW = '#ffff00';
const BLACK = '#000000';
const SWITCH_TRACK_OFF = '#555555';
const BORDER_WIDTH = 1;   // px
const CORNER_RADIUS = 10; // px

const STORE_KEY = 'shiroikuma_ui';
const FONT_FILES_KEY = '__fontFiles';

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

// ── Catalog — mirrors Android's UiCatalog, with desktop-only additions (password box) ─────────

function labelField(key, label) { return { key, label }; }
function colorField(key, label, def) { return { key, label, def: def === undefined ? null : def }; }
function dimField(key, label, max, def) { return { key, label, max: max || 16, def: def || 0 }; }

// A Send/Receive/OS toggle button: label + per-button selected/unselected fill & text, border colour,
// and border-width / corner-radius sliders — all separate per button, in that button's own element.
function toggleSurface(key, title) {
  return {
    key, title,
    labels: [labelField(key + '.text', 'Button text')],
    hasTextColor: false,
    extraColors: [
      colorField(key + '.unselFill', 'Background (unselected)', BLACK),
      colorField(key + '.selFill', 'Background (selected)', YELLOW),
      colorField(key + '.unselText', 'Text (unselected)', YELLOW),
      colorField(key + '.selText', 'Text (selected)', BLACK),
      colorField(key + '.stroke', 'Border colour', YELLOW),
    ],
    extraDims: [
      dimField(key + '.strokeWidth', 'Border width', 16, BORDER_WIDTH),
      dimField(key + '.cornerRadius', 'Corner radius', 60, CORNER_RADIUS),
    ],
  };
}

function textSurface(key, title, labels, extra) {
  return Object.assign({ key, title, labels: labels || [], hasTextColor: true, extraColors: [], extraDims: [] }, extra || {});
}

const SECTIONS = [
  {
    title: 'Main page',
    note: 'The controls you reach for first — the Send, Receive, and Select Files buttons and the Bluetooth toggle. Each button’s colours and borders are set right here.',
    surfaces: [
      toggleSurface('send', '“Send” button'),
      toggleSurface('receive', '“Receive” button'),
      toggleSurface('hotspot', '“Hotspot” button'),
      toggleSurface('sharedNetwork', '“Shared Network” button'),
      textSurface('start', 'Send / receive buttons', [
        labelField('start.filesText', '“Files to send” text'),
        labelField('start.dirText', '“Directory to send” text'),
        labelField('start.folderText', '“Select directory” text (receive mode)'),
      ], {
        extraColors: [
          colorField('start.fill', 'Background', BLACK),
          colorField('start.stroke', 'Border colour', YELLOW),
        ],
        extraDims: [
          dimField('start.strokeWidth', 'Border width', 16, BORDER_WIDTH),
          dimField('start.cornerRadius', 'Corner radius', 60, CORNER_RADIUS),
        ],
      }),
      textSurface('bluetooth', '“Use Bluetooth” label', [labelField('bluetooth.text', 'Label text')]),
      textSurface('bluetoothHint', 'Line under the Bluetooth switch', []),
    ],
    colorGroups: [
      {
        title: 'Bluetooth switch',
        colors: [
          colorField('bt.thumbOn', 'Thumb (on)', BLACK),
          colorField('bt.thumbOff', 'Thumb (off)', YELLOW),
          colorField('bt.trackOn', 'Track (on)', YELLOW),
          colorField('bt.trackOff', 'Track (off)', SWITCH_TRACK_OFF),
        ],
      },
    ],
  },
  {
    title: 'Title bar',
    surfaces: [
      textSurface('title', 'Title', [labelField('title.text', 'Title text')]),
      textSurface('version', 'Version label', [labelField('version.text', 'Label text')]),
      textSurface('about', '“About” link', [labelField('about.text', 'Link text')]),
      textSurface('uiButton', '“白い熊 魔法絨毯 UI” button', [labelField('uiButton.text', 'Button text')], {
        extraColors: [
          colorField('uiButton.bg', 'Background', BLACK),
          colorField('uiButton.stroke', 'Border colour', YELLOW),
        ],
        extraDims: [
          dimField('uiButton.strokeWidth', 'Border width', 16, BORDER_WIDTH),
          dimField('uiButton.cornerRadius', 'Corner radius', 60, CORNER_RADIUS),
        ],
      }),
    ],
    colorGroups: [
      { title: 'App logo / picture', colors: [colorField('logo.tint', 'Tint', YELLOW)] },
    ],
  },
  {
    title: 'Step instructions',
    surfaces: [
      textSurface('modeInstruction', 'Step 1 instruction', [labelField('modeInstruction.text', 'Instruction text')]),
      textSurface('connectionInstruction', 'Connection-type instruction', [labelField('connectionInstruction.text', 'Instruction text')]),
      textSurface('peerInstruction', 'Step 2 instruction', [labelField('peerInstruction.text', 'Instruction text')]),
    ],
  },
  {
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
    title: 'Cancel button',
    surfaces: [
      textSurface('cancel', 'Cancel button', [labelField('cancel.text', 'Button text')], {
        extraColors: [
          colorField('cancel.fill', 'Background', BLACK),
          colorField('cancel.stroke', 'Border colour', YELLOW),
        ],
        extraDims: [
          dimField('cancel.strokeWidth', 'Border width', 16, BORDER_WIDTH),
          dimField('cancel.cornerRadius', 'Corner radius', 60, CORNER_RADIUS),
        ],
      }),
    ],
  },
  {
    title: 'Transfer output',
    note: 'The log box at the bottom that shows status messages.',
    surfaces: [
      textSurface('output', 'Output log text', [labelField('output.hint', 'Welcome text')], {
        extraColors: [
          colorField('output.bg', 'Box background', BLACK),
          colorField('output.stroke', 'Box border colour', YELLOW),
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
        colors: [colorField('progress.color', 'Bar colour', YELLOW)],
        dims: [dimField('progress.height', 'Bar thickness', 60, 15)],
      },
    ],
  },
  {
    title: 'Window',
    colorGroups: [
      { title: 'Window', colors: [colorField('window.bg', 'Background', BLACK)] },
    ],
  },
  {
    title: 'Password dialog',
    note: 'The box that asks for the password shown on the other device — and, when more than one network interface is connected, which one to use.',
    surfaces: [
      textSurface('promptMessage', 'Question text', []),
      textSurface('password', 'Password box', [], {
        extraColors: [
          colorField('password.bg', 'Background', BLACK),
          colorField('password.stroke', 'Border colour', YELLOW),
        ],
        extraDims: [
          dimField('password.strokeWidth', 'Border width', 16, BORDER_WIDTH),
          dimField('password.cornerRadius', 'Corner radius', 60, CORNER_RADIUS),
        ],
      }),
      textSurface('promptButton', 'Buttons (Cancel / OK)', [], {
        extraColors: [
          colorField('promptButton.bg', 'Background', BLACK),
          colorField('promptButton.stroke', 'Border colour', YELLOW),
        ],
        extraDims: [
          dimField('promptButton.strokeWidth', 'Border width', 16, 2),
          dimField('promptButton.cornerRadius', 'Corner radius', 60, 999),
        ],
      }),
    ],
    colorGroups: [
      {
        title: 'Dialog',
        colors: [
          colorField('promptBg', 'Background', BLACK),
          colorField('promptStroke', 'Border colour', YELLOW),
        ],
        dims: [
          dimField('promptStrokeWidth', 'Border width', 16, 2),
          dimField('promptCornerRadius', 'Corner radius', 60, 16),
        ],
      },
    ],
  },
  {
    title: 'About page',
    note: 'The “About” dialog opened from the link under the title.',
    surfaces: [
      textSurface('aboutTitle', 'Dialog title', []),
      textSurface('aboutBody', 'Body text', []),
    ],
    colorGroups: [
      { title: 'About dialog', colors: [colorField('aboutBg', 'Background', BLACK)] },
    ],
  },
  {
    title: 'This settings page',
    note: 'Restyle this customization page itself; changes apply live.',
    pageStyle: true,
    surfaces: [
      textSurface('page.title', 'Page title', []),
      textSurface('page.section', 'Section headings', []),
      textSurface('page.element', 'Element headings', []),
      textSurface('page.label', 'Property labels', []),
      textSurface('page.note', 'Notes & hints', []),
      textSurface('page.button', 'Buttons (Done / Reset)', [], {
        extraColors: [
          colorField('page.button.bg', 'Background', BLACK),
          colorField('page.button.stroke', 'Border colour', YELLOW),
        ],
        extraDims: [
          dimField('page.button.strokeWidth', 'Border width', 16, BORDER_WIDTH),
          dimField('page.button.cornerRadius', 'Corner radius', 60, CORNER_RADIUS),
        ],
      }),
    ],
    colorGroups: [
      { title: 'Page background', colors: [colorField('page.bg', 'Background', BLACK)] },
    ],
  },
];

// Default label texts (the value used when "<key>.text" is unset).
const DEFAULT_TEXTS = {
  'title.text': '白い熊 魔法絨毯',
  'about.text': 'About',
  'uiButton.text': '白い熊 魔法絨毯 UI',
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
  start: '#startButton, #sendDirButton, #lastFolderButton',
  cancel: '#cancelButton',
  output: '#outputBox',
  // the password box lives in the prompt dialog (index.html), which doubles as the
  // network-interface chooser -- so the dropdown wears the same dress as the text field
  password: '#promptInput, #promptSelect',
  promptMessage: '#promptMessage',
  promptButton: '#promptCancel, #promptOk',
};

const TOGGLE_KEYS = ['send', 'receive', 'hotspot', 'sharedNetwork', 'androidOs', 'iosOs', 'linuxOs', 'macOs', 'windowsOs'];

// ── Applier ───────────────────────────────────────────────────────────────────────────────────

function eColor(key, def) { return Settings.colorOrNull(key) || def; }
function eDim(key, def) { const v = Settings.sizeOrNull(key); return v === null ? def : v; }

function encColor(c) { return c.replace('#', '%23'); }

// Emits the font/text-styling declarations for a text surface (color unless hasTextColor=false).
function textDecls(key, hasTextColor) {
  let out = '';
  if (hasTextColor) out += `color: ${eColor(key + '.color', YELLOW)} !important;`;
  const fam = cssFamily(Settings.family(key + '.family'));
  if (fam) out += `font-family: ${fam} !important;`;
  const st = styleCss(Settings.style(key + '.style'));
  if (st) out += `font-weight: ${st.weight} !important; font-style: ${st.fontStyle} !important;`;
  const size = Settings.size(key + '.size');
  if (size > 0) out += `font-size: ${size}px !important;`;
  return out;
}

function buildThemeCss() {
  let css = '';

  // Window background.
  css += `body { background-color: ${eColor('window.bg', BLACK)} !important; }\n`;

  // Simple text surfaces.
  for (const key of ['title', 'version', 'about', 'bluetooth', 'bluetoothHint', 'modeInstruction',
    'connectionInstruction', 'peerInstruction']) {
    css += `${TEXT_SELECTORS[key]} { ${textDecls(key, true)} }\n`;
  }
  css += `#aboutButton { cursor: pointer; }\n`;

  // Toggle buttons: unselected base + selected state, per button.
  for (const key of TOGGLE_KEYS) {
    const sel = TEXT_SELECTORS[key];
    css += `${sel} { background-color: ${eColor(key + '.unselFill', BLACK)} !important;` +
      ` color: ${eColor(key + '.unselText', YELLOW)} !important;` +
      ` border: ${eDim(key + '.strokeWidth', BORDER_WIDTH)}px solid ${eColor(key + '.stroke', YELLOW)} !important;` +
      ` border-radius: ${eDim(key + '.cornerRadius', CORNER_RADIUS)}px !important;` +
      ` ${textDecls(key, false)} margin-right: 6px; }\n`;
    css += `.btn-check:checked + ${sel} { background-color: ${eColor(key + '.selFill', YELLOW)} !important;` +
      ` color: ${eColor(key + '.selText', BLACK)} !important; }\n`;
  }
  // The btn-group rounds only its outer corners; give every segment its own radius like Android.
  css += `#modeBox, #peerBox { gap: 6px; }\n`;

  // Select Files / Start button.
  // the remembered-directory button beside it is styled identically, so the pair reads as one control
  css += `#startButton, #sendDirButton, #lastFolderButton { background-color: ${eColor('start.fill', BLACK)} !important;` +
    ` border: ${eDim('start.strokeWidth', BORDER_WIDTH)}px solid ${eColor('start.stroke', YELLOW)} !important;` +
    ` border-radius: ${eDim('start.cornerRadius', CORNER_RADIUS)}px !important; ${textDecls('start', true)} }\n`;

  // Cancel button.
  css += `#cancelButton { background-color: ${eColor('cancel.fill', BLACK)} !important;` +
    ` border: ${eDim('cancel.strokeWidth', BORDER_WIDTH)}px solid ${eColor('cancel.stroke', YELLOW)} !important;` +
    ` border-radius: ${eDim('cancel.cornerRadius', CORNER_RADIUS)}px !important; ${textDecls('cancel', true)} }\n`;

  // Password dialog: the card, the question, the box that takes the password (or picks the
  // network interface), and the Cancel/OK pills. Bootstrap paints .form-control white on focus
  // and .form-select's arrow dark, so both are overridden here rather than left to inherit.
  const promptSel = TEXT_SELECTORS.password;
  css += `#promptCard { background-color: ${eColor('promptBg', BLACK)} !important;` +
    ` border: ${eDim('promptStrokeWidth', 2)}px solid ${eColor('promptStroke', YELLOW)} !important;` +
    ` border-radius: ${eDim('promptCornerRadius', 16)}px !important; }\n`;
  css += `#promptMessage { ${textDecls('promptMessage', true)} }\n`;
  css += `${promptSel} { background-color: ${eColor('password.bg', BLACK)} !important;` +
    ` border: ${eDim('password.strokeWidth', BORDER_WIDTH)}px solid ${eColor('password.stroke', YELLOW)} !important;` +
    ` border-radius: ${eDim('password.cornerRadius', CORNER_RADIUS)}px !important;` +
    ` box-shadow: none !important; ${textDecls('password', true)} }\n`;
  css += `#promptInput::placeholder { color: ${eColor('password.color', YELLOW)}; opacity: 0.7; }\n`;
  css += `#promptSelect { background-image: url("data:image/svg+xml,%3csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16'%3e%3cpath fill='none' stroke='${encColor(eColor('password.color', YELLOW))}' stroke-linecap='round' stroke-linejoin='round' stroke-width='2' d='m2 5 6 6 6-6'/%3e%3c/svg%3e") !important; }\n`;
  css += `#promptSelect option { background-color: ${eColor('password.bg', BLACK)}; color: ${eColor('password.color', YELLOW)}; }\n`;
  css += `${TEXT_SELECTORS.promptButton} { background-color: ${eColor('promptButton.bg', BLACK)} !important;` +
    ` border: ${eDim('promptButton.strokeWidth', 2)}px solid ${eColor('promptButton.stroke', YELLOW)} !important;` +
    ` border-radius: ${eDim('promptButton.cornerRadius', 999)}px !important; ${textDecls('promptButton', true)} }\n`;

  // Output box.
  css += `#outputBox { background-color: ${eColor('output.bg', BLACK)} !important;` +
    ` border: ${eDim('output.strokeWidth', BORDER_WIDTH)}px solid ${eColor('output.stroke', YELLOW)} !important;` +
    ` border-radius: ${eDim('output.cornerRadius', CORNER_RADIUS)}px !important; padding: 8px;` +
    ` ${textDecls('output', true)} }\n`;


  // Bluetooth switch: track via background-color, thumb via an inline SVG circle.
  const thumbOff = encColor(eColor('bt.thumbOff', YELLOW));
  const thumbOn = encColor(eColor('bt.thumbOn', BLACK));
  const svg = (fill) =>
    `url("data:image/svg+xml,%3csvg xmlns='http://www.w3.org/2000/svg' viewBox='-4 -4 8 8'%3e%3ccircle r='3' fill='${fill}'/%3e%3c/svg%3e")`;
  // Override Bootstrap's --bs-form-switch-bg variable itself (it has :focus/:checked variants that
  // would otherwise fight a plain background-image override) and normalize the control's appearance.
  css += `#bluetoothSwitch { appearance: none !important; -webkit-appearance: none !important;` +
    ` background-color: ${eColor('bt.trackOff', SWITCH_TRACK_OFF)} !important;` +
    ` border-color: ${eColor('bt.trackOff', SWITCH_TRACK_OFF)} !important;` +
    ` --bs-form-switch-bg: ${svg(thumbOff)} !important;` +
    ` background-image: var(--bs-form-switch-bg) !important;` +
    ` background-repeat: no-repeat !important; background-size: contain !important;` +
    ` box-shadow: none !important; outline: none !important; }\n`;
  css += `#bluetoothSwitch:checked { background-color: ${eColor('bt.trackOn', YELLOW)} !important;` +
    ` border-color: ${eColor('bt.trackOn', YELLOW)} !important;` +
    ` --bs-form-switch-bg: ${svg(thumbOn)} !important; }\n`;

  // Progress bar.
  const prog = eColor('progress.color', YELLOW);
  const barHeight = eDim('progress.height', 15);
  css += `#progressBar, #totalProgressBar { height: ${barHeight}px; min-height: ${barHeight}px; }\n`;
  css += `#progressBar { accent-color: ${prog}; }\n`;
  css += `#progressBar::-webkit-progress-bar { background-color: ${eColor('window.bg', BLACK)}; border: 1px solid ${prog}; }\n`;
  css += `#progressBar::-webkit-progress-value { background-color: ${prog}; }\n`;
  // the sent/total, rate and ETA line above the bar follows the bar's colour
  css += `#progressDetails, #progressTotalDetails { color: ${prog}; }\n`;
  css += `#totalProgressBar { accent-color: ${prog}; }\n`;
  css += `#totalProgressBar::-webkit-progress-bar { background-color: ${eColor('window.bg', BLACK)}; border: 1px solid ${prog}; }\n`;
  css += `#totalProgressBar::-webkit-progress-value { background-color: ${prog}; }\n`;

  // Settings page + About dialog chrome (page.* / about*).
  const accent = eColor('page.section.color', YELLOW);
  css += `#forkSettingsPage { background-color: ${eColor('page.bg', BLACK)}; }\n`;
  css += `#forkSettingsPage .fork-page-title { ${textDecls('page.title', true)} }\n`;
  // The headings' underlines and the between-section hairlines follow the heading colour, so a
  // repainted page repaints its rules too.
  css += `#forkSettingsPage .fork-section-rule { background-color: ${accent}; }\n`;
  css += `#forkSettingsPage .fork-section-title { ${textDecls('page.section', true)}` +
    ` border-bottom-color: ${accent}; }\n`;
  css += `#forkSettingsPage .fork-element-title { ${textDecls('page.element', true)}` +
    ` border-bottom-color: ${eColor('page.element.color', YELLOW)}; }\n`;
  css += `#forkSettingsPage .fork-setting-title { ${textDecls('page.label', true)} }\n`;
  css += `#forkSettingsPage .fork-setting-summary:not(.fork-warn) { ${textDecls('page.note', true)} }\n`;
  css += `#forkSettingsPage label, #forkSettingsPage .fork-label { ${textDecls('page.label', true)} }\n`;
  css += `#forkSettingsPage .fork-note { ${textDecls('page.note', true)} }\n`;
  css += `#forkSettingsPage .fork-btn, #forkAboutModal .fork-btn { ${textDecls('page.button', true)}` +
    ` background-color: ${eColor('page.button.bg', BLACK)};` +
    ` border: ${eDim('page.button.strokeWidth', BORDER_WIDTH)}px solid ${eColor('page.button.stroke', YELLOW)};` +
    ` border-radius: ${eDim('page.button.cornerRadius', CORNER_RADIUS)}px; padding: 4px 16px; }\n`;
  css += `#forkAboutModal .fork-modal-box { background-color: ${eColor('aboutBg', BLACK)};` +
    ` border: 1px solid ${eColor('aboutTitle.color', YELLOW)}; }\n`;
  css += `#forkAboutModal .fork-about-title { ${textDecls('aboutTitle', true)} }\n`;
  css += `#forkAboutModal .fork-about-body { ${textDecls('aboutBody', true)} }\n`;

  return css;
}

// Set label texts on the main page (only surfaces whose element carries its own text).
function applyTexts() {
  for (const key of ['title', 'about', 'uiButton', 'bluetooth', 'modeInstruction', 'connectionInstruction',
    'peerInstruction', 'send', 'receive', 'hotspot', 'sharedNetwork', 'androidOs', 'iosOs', 'linuxOs',
    'macOs', 'windowsOs', 'cancel']) {
    const el = document.querySelector(TEXT_SELECTORS[key]);
    if (el) el.innerText = Settings.textOr(key + '.text', DEFAULT_TEXTS[key + '.text']);
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
  const tint = eColor('logo.tint', YELLOW);
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
  applyTexts();
  applyLogoTint();
}

// ── About dialog (themeable, replaces the native alert) ───────────────────────────────────────

function showAbout(message) {
  let modal = document.getElementById('forkAboutModal');
  if (!modal) {
    modal = document.createElement('div');
    modal.id = 'forkAboutModal';
    modal.className = 'fork-overlay';
    modal.innerHTML = `
      <div class="fork-modal-box">
        <div class="fork-about-title"></div>
        <div class="fork-about-body"></div>
        <button type="button" class="fork-btn" id="forkAboutClose">Close</button>
      </div>`;
    document.body.appendChild(modal);
    modal.addEventListener('click', (e) => { if (e.target === modal) modal.style.display = 'none'; });
    modal.querySelector('#forkAboutClose').onclick = () => { modal.style.display = 'none'; };
  }
  modal.querySelector('.fork-about-title').innerText =
    Settings.textOr('title.text', DEFAULT_TEXTS['title.text']);
  modal.querySelector('.fork-about-body').innerText = message;
  modal.style.display = 'flex';
}

// ── Settings page ─────────────────────────────────────────────────────────────────────────────

function surfaceKeys(surface) {
  const keys = [];
  for (const l of surface.labels) keys.push(l.key);
  if (surface.hasTextColor) keys.push(surface.key + '.color');
  keys.push(surface.key + '.family', surface.key + '.style', surface.key + '.size');
  for (const c of surface.extraColors) keys.push(c.key);
  for (const d of surface.extraDims) keys.push(d.key);
  return keys;
}

function el(tag, className, text) {
  const e = document.createElement(tag);
  if (className) e.className = className;
  if (text !== undefined) e.innerText = text;
  return e;
}

// One colour control row: swatch picker + a "default" reset. Rebuilds its swatch from settings.
function colorRow(field, refresh) {
  const row = el('div', 'fork-row');
  row.appendChild(el('span', 'fork-label', field.label));
  const input = document.createElement('input');
  input.type = 'color';
  const sync = () => { input.value = Settings.colorOrNull(field.key) || field.def || YELLOW; };
  sync();
  input.oninput = () => { Settings.setColor(field.key, input.value); applyAll(); };
  row.appendChild(input);
  const reset = el('button', 'fork-mini-btn', 'default');
  reset.type = 'button';
  reset.onclick = () => { Settings.setColor(field.key, null); sync(); applyAll(); if (refresh) refresh(); };
  row.appendChild(reset);
  return row;
}

// One dimension control row: slider 0..max with live value + a "default" reset.
function dimRow(field) {
  const row = el('div', 'fork-row');
  row.appendChild(el('span', 'fork-label', field.label));
  const input = document.createElement('input');
  input.type = 'range';
  input.min = '0';
  input.max = String(field.max);
  input.step = '1';
  const valueLabel = el('span', 'fork-label', '');
  const sync = () => {
    const v = eDim(field.key, field.def);
    input.value = String(v);
    valueLabel.innerText = `${v}px${Settings.sizeOrNull(field.key) === null ? ' (default)' : ''}`;
  };
  sync();
  input.oninput = () => { Settings.setDim(field.key, Number(input.value)); sync(); applyAll(); };
  row.appendChild(input);
  row.appendChild(valueLabel);
  const reset = el('button', 'fork-mini-btn', 'default');
  reset.type = 'button';
  reset.onclick = () => { Settings.remove([field.key]); sync(); applyAll(); };
  row.appendChild(reset);
  return row;
}

function textRow(field) {
  const row = el('div', 'fork-row');
  row.appendChild(el('span', 'fork-label', field.label));
  const input = document.createElement('input');
  input.type = 'text';
  input.className = 'fork-text-input';
  input.placeholder = DEFAULT_TEXTS[field.key] || '(default)';
  input.value = Settings.text(field.key);
  input.oninput = () => { Settings.setText(field.key, input.value); applyAll(); };
  row.appendChild(input);
  return row;
}

function fontRows(surface) {
  const rows = [];

  // Font family, each option rendered in its own glyphs; ends with "Add external font…".
  const famRow = el('div', 'fork-row');
  famRow.appendChild(el('span', 'fork-label', 'Font'));
  const famSelect = document.createElement('select');
  famSelect.className = 'fork-select';
  const rebuild = () => {
    famSelect.innerHTML = '';
    const values = Settings.familyValues();
    const labels = Settings.familyLabels();
    for (let i = 0; i < values.length; i++) {
      const opt = document.createElement('option');
      opt.value = values[i];
      opt.innerText = labels[i];
      if (values[i]) opt.style.fontFamily = `"${values[i]}"`;
      famSelect.appendChild(opt);
    }
    const add = document.createElement('option');
    add.value = '__add__';
    add.innerText = 'Add external font…';
    famSelect.appendChild(add);
    famSelect.value = Settings.family(surface.key + '.family');
  };
  rebuild();
  famSelect.onchange = () => {
    if (famSelect.value === '__add__') {
      const picker = document.createElement('input');
      picker.type = 'file';
      picker.accept = '.ttf,.otf,.woff,.woff2';
      picker.onchange = () => {
        const file = picker.files[0];
        if (!file) { rebuild(); return; }
        const reader = new FileReader();
        reader.onload = () => {
          const name = file.name.replace(/\.[^.]+$/, '');
          try {
            Settings.addFontFile(name, reader.result);
          } catch (e) {
            alert('Could not store the font (too large for local storage).');
            rebuild();
            return;
          }
          registerFonts();
          rebuild();
          famSelect.value = 'ext:' + name;
          Settings.setFamily(surface.key + '.family', famSelect.value);
          applyAll();
        };
        reader.readAsDataURL(file);
      };
      picker.click();
      return;
    }
    Settings.setFamily(surface.key + '.family', famSelect.value);
    applyAll();
  };
  famRow.appendChild(famSelect);
  rows.push(famRow);

  // Style.
  const styleRow = el('div', 'fork-row');
  styleRow.appendChild(el('span', 'fork-label', 'Style'));
  const styleSelect = document.createElement('select');
  styleSelect.className = 'fork-select';
  for (let i = 0; i < STYLE_VALUES.length; i++) {
    const opt = document.createElement('option');
    opt.value = String(STYLE_VALUES[i]);
    opt.innerText = STYLE_LABELS[i];
    styleSelect.appendChild(opt);
  }
  styleSelect.value = String(Settings.style(surface.key + '.style'));
  styleSelect.onchange = () => { Settings.setStyle(surface.key + '.style', Number(styleSelect.value)); applyAll(); };
  styleRow.appendChild(styleSelect);
  rows.push(styleRow);

  // Size.
  const sizeRow = el('div', 'fork-row');
  sizeRow.appendChild(el('span', 'fork-label', 'Text size (px, 0 = default)'));
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

function buildSurface(surface, container) {
  const box = el('div', 'fork-element');
  box.appendChild(el('div', 'fork-element-title', surface.title));
  const controls = el('div', 'fork-controls');

  for (const label of surface.labels) controls.appendChild(textRow(label));
  if (surface.hasTextColor) {
    controls.appendChild(colorRow(colorField(surface.key + '.color', surface.colorLabel || 'Text colour', YELLOW)));
  }
  for (const row of fontRows(surface)) controls.appendChild(row);
  for (const c of surface.extraColors) controls.appendChild(colorRow(c));
  for (const d of surface.extraDims) controls.appendChild(dimRow(d));

  const reset = el('button', 'fork-mini-btn fork-group-reset', 'Reset to default');
  reset.type = 'button';
  reset.onclick = () => { Settings.remove(surfaceKeys(surface)); applyAll(); rebuildSettingsPage(); };
  controls.appendChild(reset);

  box.appendChild(controls);
  container.appendChild(box);
}

function buildColorGroup(group, container) {
  const box = el('div', 'fork-element');
  box.appendChild(el('div', 'fork-element-title', group.title));
  const controls = el('div', 'fork-controls');
  for (const c of group.colors) controls.appendChild(colorRow(c));
  for (const d of (group.dims || [])) controls.appendChild(dimRow(d));
  const reset = el('button', 'fork-mini-btn fork-group-reset', 'Reset to default');
  reset.type = 'button';
  reset.onclick = () => {
    Settings.remove(group.colors.map((c) => c.key).concat((group.dims || []).map((d) => d.key)));
    applyAll();
    rebuildSettingsPage();
  };
  controls.appendChild(reset);
  box.appendChild(controls);
  container.appendChild(box);
}

// ── Export / Import ───────────────────────────────────────────────────────────────────────────
//
// The desktop twin of the Android app's Export/Import panel, and the same shapes: the Kōjiki sheet
// for the panel, the ArcaneChat button bar at its foot, and a black/yellow-bordered info dialog on
// the way out. Closing behaviour (白い熊, 2026-07-25): acknowledging a **successful** export or
// import closes the whole chain — info dialog, panel, and the UI page beneath them. Failures close
// only the info dialog, so the panel stays open and the problem can be fixed on the spot.

/** A yellow-bordered black dialog with right-aligned pills. Each action decides what to close. */
function forkInfo(title, body, actions) {
  const overlay = el('div', 'fork-overlay');
  const box = el('div', 'fork-info-box');
  box.appendChild(el('div', 'fork-info-title', title));
  box.appendChild(el('div', 'fork-info-body', body));
  const row = el('div', 'fork-info-actions');
  const close = () => overlay.remove();
  for (const action of actions) {
    const btn = el('button', 'fork-pill', action.label);
    btn.type = 'button';
    btn.onclick = () => action.onClick(close);
    row.appendChild(btn);
  }
  box.appendChild(row);
  overlay.appendChild(box);
  document.body.appendChild(overlay);
  return overlay;
}

/** Single-OK dialog that closes only itself — every failure message uses this. */
function forkAlert(title, body) {
  forkInfo(title, body, [{ label: 'OK', onClick: (close) => close() }]);
}

/** A black/yellow chooser: one clickable row per item, plus a Cancel pill. */
function forkChooser(title, items, onPick) {
  const overlay = el('div', 'fork-overlay');
  const box = el('div', 'fork-info-box');
  box.appendChild(el('div', 'fork-info-title', title));
  box.appendChild(el('div', 'fork-hairline'));
  items.forEach((label, i) => {
    const row = el('div', 'fork-setting-row');
    row.style.marginLeft = '0';
    row.appendChild(el('div', 'fork-info-body', label));
    row.onclick = () => { overlay.remove(); onPick(i); };
    box.appendChild(row);
  });
  box.appendChild(el('div', 'fork-hairline'));
  const row = el('div', 'fork-info-actions');
  const cancel = el('button', 'fork-pill', 'Cancel');
  cancel.type = 'button';
  cancel.onclick = () => overlay.remove();
  row.appendChild(cancel);
  box.appendChild(row);
  overlay.appendChild(box);
  document.body.appendChild(overlay);
}

/** The folder line shown on the UI page: the folder plus its newest backup, red while unset. */
async function exportSummary() {
  const dir = Backup.exportDir();
  if (!dir) return { text: 'No backup folder set', warn: true };
  const newest = (await Backup.listBackups())[0];
  if (!newest) return { text: `${dir} — no backup yet`, warn: true };
  const stamp = new Date(newest.modified).toLocaleString();
  return { text: `${dir} — latest ${stamp}`, warn: false };
}

async function refreshExportSummary() {
  const view = document.getElementById('forkExportSummary');
  if (!view) return;
  const { text, warn } = await exportSummary();
  view.innerText = text;
  view.classList.toggle('fork-warn', warn);
}

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
  document.body.appendChild(overlay);

  async function pickFolder() {
    const picked = await dialog.open({ multiple: false, directory: true, defaultPath: Backup.exportDir() || undefined });
    if (!picked) return;
    Backup.setExportDir(picked);
    render();
  }

  async function onExport() {
    if (!Backup.exportDir()) { await pickFolder(); return; }
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
    if (!Backup.exportDir()) { await pickFolder(); return; }
    if (!selected.size) { forkAlert('Import', 'No categories selected.'); return; }
    const backups = await Backup.listBackups();
    if (!backups.length) {
      forkAlert('Import', 'No backup of this app was found in the backup folder.');
      return;
    }
    forkChooser(
      'Choose a backup',
      backups.map((b) => `${b.name}\n${Backup.humanSize(b.size)}`),
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
      'Save everything you have set in this app to one .zip in your backup folder, or restore it ' +
      'from one. Importing merges: categories the file doesn’t carry are left alone.'));

    const dir = Backup.exportDir();
    const dirBox = el('div', `fork-dirbox${dir ? '' : ' unset'}`);
    dirBox.appendChild(el('div', 'fork-dirbox-label', 'Backup folder'));
    const dirValue = el('div', `fork-dirbox-value${dir ? '' : ' fork-warn'}`,
      dir || 'No backup folder set — click to choose one');
    dirBox.appendChild(dirValue);
    dirBox.onclick = pickFolder;
    box.appendChild(dirBox);

    const status = el('div', 'fork-status-line', 'Reading the backup folder…');
    box.appendChild(status);
    if (!dir) {
      status.innerText = 'No backup folder set.';
      status.classList.add('fork-warn');
    } else {
      const newest = (await Backup.listBackups())[0];
      if (newest) {
        status.innerText = `Latest export: ${new Date(newest.modified).toLocaleString()} ` +
          `(${Backup.humanSize(newest.size)})`;
      } else {
        status.innerText = 'No backup in this folder yet.';
        status.classList.add('fork-warn');
      }
    }

    box.appendChild(el('div', 'fork-hairline'));

    const allRow = el('div', 'fork-check-row');
    const allBox = document.createElement('input');
    allBox.type = 'checkbox';
    allBox.checked = selected.size === Backup.CATEGORIES.length;
    allBox.onchange = () => {
      if (allBox.checked) Backup.CATEGORIES.forEach((c) => selected.add(c.id));
      else selected.clear();
      render();
    };
    allRow.appendChild(allBox);
    const allLabel = el('span', null, 'Select all');
    allLabel.style.fontWeight = 'bold';
    allRow.appendChild(allLabel);
    box.appendChild(allRow);

    for (const cat of Backup.CATEGORIES) {
      const row = el('div', 'fork-check-row');
      const check = document.createElement('input');
      check.type = 'checkbox';
      check.checked = selected.has(cat.id);
      check.onchange = () => { if (check.checked) selected.add(cat.id); else selected.delete(cat.id); };
      row.appendChild(check);
      row.appendChild(el('span', null, cat.label));
      box.appendChild(row);
    }

    box.appendChild(el('div', 'fork-hairline'));

    // The ArcaneChat button bar: Cancel alone on the left, Import + Export grouped on the right.
    const bar = el('div', 'fork-pill-bar');
    const cancel = el('button', 'fork-pill', 'Cancel');
    cancel.type = 'button';
    cancel.onclick = closePanel;
    bar.appendChild(cancel);
    bar.appendChild(el('div', 'fork-pill-spacer'));
    const importBtn = el('button', 'fork-pill', 'Import');
    importBtn.type = 'button';
    importBtn.onclick = onImport;
    bar.appendChild(importBtn);
    const exportBtn = el('button', 'fork-pill', 'Export');
    exportBtn.type = 'button';
    exportBtn.onclick = onExport;
    bar.appendChild(exportBtn);
    box.appendChild(bar);
  }

  render();
}

// ── Settings page ─────────────────────────────────────────────────────────────────────────────

function rebuildSettingsPage() {
  const page = document.getElementById('forkSettingsPage');
  if (!page || page.style.display === 'none') return;
  openSettings(); // rebuilds in place
}

/** A top-level section: the thin full-width spacer, then the text-width underlined heading. */
function sectionEl(title, note) {
  const sec = el('div', 'fork-section');
  sec.appendChild(el('div', 'fork-section-rule'));
  sec.appendChild(el('div', 'fork-section-title', title));
  if (note) sec.appendChild(el('div', 'fork-note', note));
  return sec;
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

  const header = el('div', 'fork-page-header');
  header.appendChild(el('div', 'fork-page-title',
    `${Settings.textOr('title.text', DEFAULT_TEXTS['title.text'])} UI${appVersion ? ' — ' + appVersion : ''}`));
  const doneBtn = el('button', 'fork-btn', 'Done');
  doneBtn.type = 'button';
  doneBtn.onclick = () => { page.style.display = 'none'; applyAll(); };
  const resetBtn = el('button', 'fork-btn', 'Reset all to defaults');
  resetBtn.type = 'button';
  resetBtn.onclick = () => {
    if (confirm('Reset every customization to the fork defaults?')) {
      Settings.clearAll();
      applyAll();
      openSettings();
    }
  };
  const buttons = el('div', 'fork-page-buttons');
  buttons.appendChild(doneBtn);
  buttons.appendChild(resetBtn);
  header.appendChild(buttons);
  page.appendChild(header);

  // First section on the page, matching the Kōjiki UI page: the backup folder and the panel that
  // carries every setting in this app out to a .zip and back.
  const exportSection = sectionEl(
    'Export / Import',
    'Carry everything you set in this app to another device, or put it back.',
  );
  const row = el('div', 'fork-setting-row');
  const rowText = el('div', null);
  rowText.style.flex = '1 1 auto';
  rowText.appendChild(el('div', 'fork-setting-title', 'Export / Import…'));
  const summary = el('div', 'fork-setting-summary', 'Reading the backup folder…');
  summary.id = 'forkExportSummary';
  rowText.appendChild(summary);
  row.appendChild(rowText);
  row.onclick = openExportPanel;
  exportSection.appendChild(row);
  page.appendChild(exportSection);
  refreshExportSummary();

  for (const section of SECTIONS) {
    const sec = sectionEl(section.title, section.note);
    for (const surface of (section.surfaces || [])) buildSurface(surface, sec);
    for (const group of (section.colorGroups || [])) buildColorGroup(group, sec);
    page.appendChild(sec);
  }

  page.scrollTop = 0;
}

// ── Boot ──────────────────────────────────────────────────────────────────────────────────────

window.forkUI = { showAbout, startLabel, applyLogoTint, openSettings, applyAll };

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
    // Innermost first: an open panel or dialog swallows Escape before the page does.
    const overlays = document.querySelectorAll('body > .fork-overlay');
    const topmost = overlays[overlays.length - 1];
    if (topmost && topmost.id !== 'forkAboutModal') { topmost.remove(); refreshExportSummary(); return; }
    const modal = document.getElementById('forkAboutModal');
    if (modal && modal.style.display !== 'none') { modal.style.display = 'none'; return; }
    const page = document.getElementById('forkSettingsPage');
    if (page && page.style.display !== 'none') page.style.display = 'none';
  });
});
