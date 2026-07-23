// 白い熊 魔法絨毯 UI customization — desktop port of the Android fork's Appearance/Settings engine.
//
// The fork's baseline look is a high-contrast yellow-on-black theme: yellow text, yellow borders,
// black backgrounds — everywhere, unless 白い熊 overrides a specific property. Borders default to a
// 1 px yellow line with a 10 px corner radius; the sliders still start at 0, so a border can be removed.
//
// Every stored value is optional: an absent key means "inherit the fork default"; "Reset" clears keys.
// Mirrors Android's Settings semantics: dims (border width / radius) store an explicit 0 as a valid
// value distinct from "unset", texts store "" as "unset", colors/fonts/styles are absent when unset.

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
      textSurface('start', '“Select Files” button', [
        labelField('start.filesText', '“Select Files” text'),
        labelField('start.folderText', '“Select Folder” text (receive mode)'),
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
      textSurface('uiButton', '“Customize UI” button', [labelField('uiButton.text', 'Button text')], {
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
    title: '“Send Folder” checkbox',
    surfaces: [
      textSurface('sendFolder', '“Send Folder” checkbox', [labelField('sendFolder.text', 'Label text')], {
        extraColors: [colorField('sendFolder.tint', 'Box tint', YELLOW)],
      }),
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
      { title: 'Progress bar', colors: [colorField('progress.color', 'Bar colour', YELLOW)] },
    ],
  },
  {
    title: 'Window',
    colorGroups: [
      { title: 'Window', colors: [colorField('window.bg', 'Background', BLACK)] },
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
  'uiButton.text': 'Customize UI',
  'bluetooth.text': 'Use Bluetooth',
  'modeInstruction.text': 'Select Mode',
  'peerInstruction.text': 'Select Peer OS',
  'send.text': 'Send',
  'receive.text': 'Receive',
  'androidOs.text': 'Android',
  'iosOs.text': 'iOS',
  'linuxOs.text': 'Linux',
  'macOs.text': 'macOS',
  'windowsOs.text': 'Windows',
  'cancel.text': 'Cancel Transfer',
  'sendFolder.text': 'Send Folder',
  'start.filesText': 'Select Files',
  'start.folderText': 'Select Folder',
  'output.hint': 'Welcome to Flying Carpet!\nOnce other options are selected, drag and drop can be used to start a transfer.',
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
  modeInstruction: '#modeInstruction',
  peerInstruction: '#peerLabel',
  send: 'label[for=sendButton]',
  receive: 'label[for=receiveButton]',
  androidOs: 'label[for=androidButton]',
  iosOs: 'label[for=iosButton]',
  linuxOs: 'label[for=linuxButton]',
  macOs: 'label[for=macButton]',
  windowsOs: 'label[for=windowsButton]',
  start: '#startButton',
  cancel: '#cancelButton',
  sendFolder: 'label[for=sendFolderCheckbox]',
  output: '#outputBox',
  password: '#passwordBox',
};

const TOGGLE_KEYS = ['send', 'receive', 'androidOs', 'iosOs', 'linuxOs', 'macOs', 'windowsOs'];

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
  for (const key of ['title', 'version', 'about', 'bluetooth', 'modeInstruction', 'peerInstruction', 'sendFolder']) {
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
  css += `#startButton { background-color: ${eColor('start.fill', BLACK)} !important;` +
    ` border: ${eDim('start.strokeWidth', BORDER_WIDTH)}px solid ${eColor('start.stroke', YELLOW)} !important;` +
    ` border-radius: ${eDim('start.cornerRadius', CORNER_RADIUS)}px !important; ${textDecls('start', true)} }\n`;

  // Cancel button.
  css += `#cancelButton { background-color: ${eColor('cancel.fill', BLACK)} !important;` +
    ` border: ${eDim('cancel.strokeWidth', BORDER_WIDTH)}px solid ${eColor('cancel.stroke', YELLOW)} !important;` +
    ` border-radius: ${eDim('cancel.cornerRadius', CORNER_RADIUS)}px !important; ${textDecls('cancel', true)} }\n`;

  // Password box.
  css += `#passwordBox { background-color: ${eColor('password.bg', BLACK)} !important;` +
    ` border: ${eDim('password.strokeWidth', BORDER_WIDTH)}px solid ${eColor('password.stroke', YELLOW)} !important;` +
    ` border-radius: ${eDim('password.cornerRadius', CORNER_RADIUS)}px !important; ${textDecls('password', true)} }\n`;
  css += `#passwordBox::placeholder { color: ${eColor('password.color', YELLOW)}; opacity: 0.7; }\n`;

  // Output box.
  css += `#outputBox { background-color: ${eColor('output.bg', BLACK)} !important;` +
    ` border: ${eDim('output.strokeWidth', BORDER_WIDTH)}px solid ${eColor('output.stroke', YELLOW)} !important;` +
    ` border-radius: ${eDim('output.cornerRadius', CORNER_RADIUS)}px !important; padding: 8px;` +
    ` ${textDecls('output', true)} }\n`;

  // Send Folder checkbox tint.
  css += `#sendFolderCheckbox { accent-color: ${eColor('sendFolder.tint', YELLOW)}; }\n`;

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
  css += `#progressBar { accent-color: ${prog}; }\n`;
  css += `#progressBar::-webkit-progress-bar { background-color: ${eColor('window.bg', BLACK)}; border: 1px solid ${prog}; }\n`;
  css += `#progressBar::-webkit-progress-value { background-color: ${prog}; }\n`;

  // Settings page + About dialog chrome (page.* / about*).
  css += `#forkSettingsPage { background-color: ${eColor('page.bg', BLACK)}; }\n`;
  css += `#forkSettingsPage .fork-page-title { ${textDecls('page.title', true)} }\n`;
  css += `#forkSettingsPage .fork-section-title { ${textDecls('page.section', true)} }\n`;
  css += `#forkSettingsPage .fork-element-title { ${textDecls('page.element', true)} }\n`;
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
  for (const key of ['title', 'about', 'uiButton', 'bluetooth', 'modeInstruction', 'peerInstruction',
    'send', 'receive', 'androidOs', 'iosOs', 'linuxOs', 'macOs', 'windowsOs', 'cancel', 'sendFolder']) {
    const el = document.querySelector(TEXT_SELECTORS[key]);
    if (el) el.innerText = Settings.textOr(key + '.text', DEFAULT_TEXTS[key + '.text']);
  }
  // Version label: full versionName, no "Version" prefix, unless overridden.
  const versionEl = document.querySelector(TEXT_SELECTORS.version);
  if (versionEl) versionEl.innerText = Settings.textOr('version.text', appVersion || versionEl.innerText);
  // Start button text depends on the current mode.
  const startEl = document.querySelector(TEXT_SELECTORS.start);
  const receiveChecked = document.getElementById('receiveButton');
  if (startEl) startEl.innerText = startLabel(receiveChecked && receiveChecked.checked ? 'receive' : 'send');
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

function rebuildSettingsPage() {
  const page = document.getElementById('forkSettingsPage');
  if (!page || page.style.display === 'none') return;
  openSettings(); // rebuilds in place
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

  for (const section of SECTIONS) {
    const sec = el('div', 'fork-section');
    sec.appendChild(el('div', 'fork-section-title', section.title));
    if (section.note) sec.appendChild(el('div', 'fork-note', section.note));
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
    const page = document.getElementById('forkSettingsPage');
    if (page && page.style.display !== 'none') page.style.display = 'none';
    const modal = document.getElementById('forkAboutModal');
    if (modal && modal.style.display !== 'none') modal.style.display = 'none';
  });
});
