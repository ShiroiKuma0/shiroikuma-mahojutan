// 白い熊 魔法絨毯 — the icons of rows, drawn here on a 24-unit grid so that no font and no
// image asset is needed. Each is a line drawing in `currentColor`; its colour is the role of
// what its row DOES (connect, message, contact, share, view, block, delete — the table in
// roles.js), given through a class the stylesheet maps to that role, never written here.

import { cssVar } from './roles.js';

const PATHS = {
  // what a row sets
  drop: 'M12 3l5.5 7.5a6.6 6.6 0 1 1-11 0z',
  text: 'M5 6h14M12 6v13',
  font: 'M6 19l6-14 6 14M8.5 14h7',
  style: 'M10 5h7M7 19h7M14 5l-4 14',
  size: 'M12 4v16M8 8l4-4 4 4M8 16l4 4 4-4',
  lines: 'M4 7h16M4 12h16M4 17h16',
  corner: 'M5 19v-8a6 6 0 0 1 6-6h8',
  sliders: 'M4 7h9M17 7h3M4 17h3M11 17h9M15 5v4M9 15v4',
  field: 'M3 8h18v8H3zM7 11v2',
  button: 'M7 8h10a4 4 0 0 1 0 8H7a4 4 0 0 1 0-8z',
  switch: 'M8 7h8a5 5 0 0 1 0 10H8A5 5 0 0 1 8 7zM16 10.5a1.5 1.5 0 1 0 0 3a1.5 1.5 0 0 0 0-3z',
  progress: 'M3 10h18v4H3zM13 10v4',
  window: 'M3 5h18v14H3zM3 9h18',
  dialog: 'M4 5h16v11H9l-5 4z',
  menu: 'M4 6h.01M8 6h12M4 12h.01M8 12h12M4 18h.01M8 18h12',
  lanes: 'M4 7h16M4 12h10M4 17h13',
  chips: 'M4 5h7v5H4zM13 5h7v5h-7zM4 14h7v5H4zM13 14h7v5h-7z',
  fold: 'M9 6l6 6-6 6',
  qr: 'M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h2v2h-2zM18 18h2v2h-2zM18 14h2M14 18h2',
  // what a row does
  export: 'M12 15V4M8 8l4-4 4 4M5 14v5h14v-5',
  import: 'M12 4v11M8 11l4 4 4-4M5 14v5h14v-5',
  directory: 'M3 7a1 1 0 0 1 1-1h5l2 2h9a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z',
  file: 'M7 3h7l4 4v14H7zM14 3v4h4',
  archive: 'M4 5h16v4H4zM5 9v10h14V9M10 13h4',
  wifi: 'M2.5 9a14 14 0 0 1 19 0M5.5 12.5a9.5 9.5 0 0 1 13 0M8.8 16a5 5 0 0 1 6.4 0M12 19.5h.01',
  network: 'M12 3v6M5 21v-6h14v6M12 9v6M3 21h4M10 21h4M17 21h4',
  bolt: 'M13 3L5 13h6l-1 8 8-10h-6z',
  device: 'M8 3h8a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1zM11 18h2',
  tag: 'M4 12V5h7l9 9-7 7zM8 8.5h.01',
  pencil: 'M4 20l1-4L16 5l3 3L8 19zM14 7l3 3',
  keyboard: 'M3 7h18v10H3zM7 11h.01M11 11h.01M15 11h.01M8 14h8',
  search: 'M11 4a7 7 0 1 0 0 14a7 7 0 0 0 0-14zM16 16l4.5 4.5',
  send: 'M4 12h14M13 6l6 6-6 6',
  receive: 'M20 12H6M11 6l-6 6 6 6',
  check: 'M5 12.5l4.5 4.5L19 7.5',
  block: 'M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18zM5.6 5.6l12.8 12.8',
  eye: 'M2 12s3.5-6.5 10-6.5S22 12 22 12s-3.5 6.5-10 6.5S2 12 2 12zM12 9.5a2.5 2.5 0 1 0 0 5a2.5 2.5 0 0 0 0-5z',
  info: 'M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18zM12 11v6M12 7.5h.01',
  warning: 'M12 4l9 16H3zM12 10v5M12 17.5h.01',
  clock: 'M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18zM12 7v5l3 2',
  reset: 'M4 12a8 8 0 1 0 2.5-5.8M4 4v5h5',
  order: 'M7 4v16M4 7l3-3 3 3M17 20V4M14 17l3 3 3-3',
  trash: 'M5 7h14M10 7V4h4v3M7 7l1 13h8l1-13',
  restart: 'M20 12a8 8 0 1 1-2.5-5.8M20 4v5h-5',
};

/** The seven things a row can do, each with a role of its own (roles.js, “Icons”). */
const TINTS = ['connect', 'message', 'contact', 'share', 'view', 'block', 'delete'];

const SVG = 'http://www.w3.org/2000/svg';

/**
 * An icon as an inline SVG. `tint` is one of the seven row roles above — or the key of any
 * other role, for the few icons that show a role of their own (a device that is on the
 * network, the warning). Left out, the icon takes the colour of the text round it.
 */
export function icon(name, tint) {
  const svg = document.createElementNS(SVG, 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('aria-hidden', 'true');
  svg.classList.add('fork-icon');
  if (TINTS.includes(tint)) svg.classList.add(`fork-tint-${tint}`);
  else if (tint) svg.style.color = `var(${cssVar(tint)})`;
  const path = document.createElementNS(SVG, 'path');
  path.setAttribute('d', PATHS[name] || PATHS.info);
  svg.appendChild(path);
  return svg;
}
