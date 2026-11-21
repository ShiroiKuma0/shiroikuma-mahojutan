// Fork: the Devices panel — the desktop end of paired devices.
//
// One list, one tap. No mode to choose, no password to agree, nothing to arm on the far
// device. Everything this page does is a call into fork_paired.rs; there is no networking
// in the webview.
//
// It reuses customize.js's dialog primitives rather than growing its own, because a second
// set would drift out of step with the theme the first one follows.

import {
  el, icon, pill, switchInput, colour, actionRow, forkInfo, forkAlert, forkMenu,
} from './customize.js';
import { QRCode } from './deps/qrcode.js';

const { core, dialog } = window.__TAURI__;

/** The last scan's answer, so the list can be redrawn without shouting at the network again. */
let lastScan = new Map();

async function status() {
  return await core.invoke('paired_status');
}

/**
 * A device id is 26 base32 characters, which is unreadable and, worse, unmemorable — so
 * nothing anywhere shows one in full. Six is plenty to tell two of 白い熊's devices apart
 * when the names collide.
 */
function shortId(deviceId) {
  return deviceId.slice(0, 6);
}

/** What to call a device: the name set here, then the one it announces, then its id. */
function displayName(peer) {
  return peer.alias || peer.name || `(unnamed · ${shortId(peer.device_id)})`;
}

function whenSeen(seconds) {
  if (!seconds) return 'never seen on a network';
  const ago = Math.floor(Date.now() / 1000) - seconds;
  if (ago < 60) return 'seen just now';
  if (ago < 3600) return `seen ${Math.floor(ago / 60)} min ago`;
  if (ago < 86400) return `seen ${Math.floor(ago / 3600)} h ago`;
  return `seen ${Math.floor(ago / 86400)} days ago`;
}

// ── Pairing ───────────────────────────────────────────────────────────────────────────────
//
// Pairwise, no group (白い熊, 2026-09-11): a code pairs exactly the two devices involved,
// either one can show it, and pairing a third device never touches the first pairing.

/**
 * Shows the code another device scans or types. The QR and the text carry the same key and
 * the same id — the QR as a URI, the text as 78 characters grouped in fives.
 */
function showPairCode(uri, typed) {
  const overlay = el('div', 'fork-overlay');
  const box = el('div', 'fork-info-box');
  box.appendChild(el('div', 'fork-info-title', 'Pair a device'));
  box.appendChild(
    el(
      'div',
      'fork-info-body',
      'On the other device, open Devices ＋ and choose “Scan a code” — or “Type a code” and type what is under the QR. The two are paired the moment it answers; nothing else is needed.',
    ),
  );

  // Light and dark of the code are roles (the UI page, “QR codes”), as on the main page.
  const qrHolder = el('div', 'fork-qr-holder');
  new QRCode(qrHolder, {
    text: uri,
    width: 182,
    height: 182,
    colorLight: colour('role.qr.light'),
    colorDark: colour('role.qr.dark'),
  });
  qrHolder.appendChild(el('div', 'fork-qr-caption', typed));
  box.appendChild(qrHolder);

  const warn = el(
    'div',
    'fork-info-hint',
    'Whoever uses this code first becomes paired with this device. Show it, don’t send it.',
  );
  box.appendChild(warn);

  const row = el('div', 'fork-info-actions');
  const done = pill('Done', { main: true });
  done.onclick = () => {
    overlay.remove();
    refresh();
  };
  row.appendChild(done);
  box.appendChild(row);
  overlay.appendChild(box);
  document.body.appendChild(overlay);
}

/** Pairs with a device from the code typed or pasted off its screen. */
function askForPairCode() {
  const overlay = el('div', 'fork-overlay');
  const box = el('div', 'fork-info-box');
  box.appendChild(el('div', 'fork-info-title', 'Type a code'));
  box.appendChild(
    el(
      'div',
      'fork-info-body',
      'Type or paste the 78 characters shown under the QR code on the other device. Spaces and capitals do not matter.',
    ),
  );
  const input = el('input', 'fork-text-input');
  input.type = 'text';
  input.spellcheck = false;
  input.style.width = '100%';
  input.style.fontFamily = 'monospace';
  box.appendChild(input);

  const row = el('div', 'fork-info-actions');
  const cancel = el('button', 'fork-pill', 'Cancel');
  cancel.type = 'button';
  cancel.onclick = () => overlay.remove();
  const join = el('button', 'fork-pill fork-main', 'Pair');
  join.type = 'button';
  join.onclick = async () => {
    const text = input.value.trim();
    if (!text) return;
    try {
      await core.invoke('paired_add_from_code', { text });
      overlay.remove();
      await refresh();
      // The other device learns about this one when this one introduces itself, which is
      // what a scan is — so it happens now rather than at some later opening of the list.
      await scan();
    } catch (e) {
      forkAlert('Could not pair', String(e));
    }
  };
  row.appendChild(cancel);
  row.appendChild(join);
  box.appendChild(row);
  overlay.appendChild(box);
  document.body.appendChild(overlay);
  input.focus();
}

// ── The panel ─────────────────────────────────────────────────────────────────────────────

/**
 * One paired device in the panel, as a group of the list: the pill that sends it files over
 * this network — its icon green while the device answers, dim while it does not — what is
 * known about it beneath, on the label's edge, then what else can be done with it.
 */
function deviceGroup(peer) {
  const group = el('div', 'fork-list-group');
  const name = displayName(peer);

  const row = el('button', 'fork-list-pill');
  row.type = 'button';
  row.title = `Send files to ${name} over this network`;
  row.appendChild(icon('device', peer.reachable ? 'role.list.online' : 'role.list.offline'));
  row.appendChild(el('span', 'fork-list-text', name));
  // It opens the file picker.
  row.appendChild(el('span', 'fork-chevron', '›'));
  row.onclick = () => sendTo(peer);
  group.appendChild(row);

  const state = peer.reachable
    ? `● on this network — ${peer.last_ip}`
    : peer.last_ip
      ? `○ ${whenSeen(peer.last_seen)} at ${peer.last_ip}`
      : `○ ${whenSeen(peer.last_seen)}`;
  group.appendChild(el('div', 'fork-list-sub', `${peer.os || 'not yet heard from'} · ${state}`));
  if (peer.receive_dir) {
    group.appendChild(el('div', 'fork-list-sub', `Receives into ${peer.receive_dir}`));
  }
  if (!peer.auto_accept) {
    group.appendChild(
      el('div', 'fork-list-sub fork-warn', 'Set not to accept transfers from this device without asking.'),
    );
  }

  const actions = el('div', 'fork-list-actions');
  const hotspotSend = pill('Send over hotspot', { mini: true, icon: 'bolt', tint: 'share', opens: true });
  hotspotSend.onclick = () => sendOverHotspot(peer);
  const hotspotReceive = pill('Receive over hotspot', { mini: true, icon: 'receive', tint: 'connect' });
  hotspotReceive.onclick = () => receiveOverHotspot(peer);
  const forget = pill('Forget', { mini: true, icon: 'trash', danger: true });
  forget.onclick = () => confirmForget(peer);
  actions.appendChild(hotspotSend);
  actions.appendChild(hotspotReceive);
  actions.appendChild(forget);
  group.appendChild(actions);

  // On or off, so a switch — the same one the device's menu shows.
  const accept = el('label', 'fork-list-toggle');
  accept.appendChild(icon('check', 'connect'));
  accept.appendChild(el('span', 'fork-list-text', 'Accept without asking'));
  const toggle = switchInput(peer.auto_accept, true);
  toggle.onchange = () => setAutoAccept(peer, toggle.checked);
  accept.appendChild(toggle);
  group.appendChild(accept);

  group.addEventListener('contextmenu', (event) => {
    event.preventDefault();
    deviceMenu(peer, event);
  });
  return group;
}

// ── One device's menu ─────────────────────────────────────────────────────────────────────
//
// Everything that can be done to one paired device, in one place — the desktop twin of
// Android's DeviceMenu, reached by right-clicking a device the way Android holds one: on the
// main page's strip or on a device of the Devices panel, so neither surface can drift into a
// half-menu of its own. Same items, same order — drawn as the theme draws a menu: the name
// as a plain header, every action a full-width pill with its icon, accepting as a switch, and
// a line between the groups. It opens where the pointer is.

function deviceMenu(peer, event) {
  forkMenu({ x: event.clientX, y: event.clientY }, displayName(peer), [
    {
      label: 'Rename…', icon: 'pencil', tint: 'contact', opens: true,
      onClick: () => renameDevice(peer),
    },
    {
      label: 'Receive directory…', icon: 'directory', tint: 'view', opens: true,
      onClick: () => chooseDeviceReceiveDir(peer),
    },
    { divider: true },
    {
      label: 'Accept without asking', icon: 'check', tint: 'connect',
      checked: peer.auto_accept,
      onToggle: (allow) => setAutoAccept(peer, allow),
    },
    { divider: true },
    {
      label: 'Send over hotspot', icon: 'bolt', tint: 'share', opens: true,
      onClick: () => sendOverHotspot(peer),
    },
    {
      label: 'Receive over hotspot', icon: 'receive', tint: 'connect',
      onClick: () => receiveOverHotspot(peer),
    },
    { divider: true },
    {
      label: 'Forget this device', icon: 'trash', danger: true,
      onClick: () => confirmForget(peer),
    },
  ]);
}

/**
 * Writes an alias, not the device's name: the name is refreshed from every announcement, so
 * a rename stored there would be undone by the next scan.
 */
function renameDevice(peer) {
  const overlay = el('div', 'fork-overlay');
  const box = el('div', 'fork-info-box');
  box.appendChild(el('div', 'fork-info-title', `Rename ${displayName(peer)}`));
  box.appendChild(
    el(
      'div',
      'fork-info-body',
      'What this device is called here. The other device keeps its own name; leave this empty to go back to using it.',
    ),
  );
  const input = el('input', 'fork-text-input');
  input.type = 'text';
  input.spellcheck = false;
  input.style.width = '100%';
  input.style.marginTop = '10px';
  input.value = peer.alias || '';
  input.placeholder = peer.name || 'Device name';
  box.appendChild(input);

  const row = el('div', 'fork-info-actions');
  const cancel = el('button', 'fork-pill', 'Cancel');
  cancel.type = 'button';
  cancel.onclick = () => overlay.remove();
  const set = el('button', 'fork-pill fork-main', 'Set');
  set.type = 'button';
  set.onclick = async () => {
    try {
      await core.invoke('paired_set_alias', { deviceId: peer.device_id, alias: input.value });
      overlay.remove();
      await refreshEverywhere();
    } catch (e) {
      forkAlert('Could not rename', String(e));
    }
  };
  input.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') set.click();
    if (event.key === 'Escape') cancel.click();
  });
  row.appendChild(cancel);
  row.appendChild(set);
  box.appendChild(row);
  overlay.appendChild(box);
  document.body.appendChild(overlay);
  input.focus();
  input.select();
}

/** Where files from this one device land, or back to the directory shared by all of them. */
async function chooseDeviceReceiveDir(peer) {
  const shared = (await status()).receive_dir;
  const current = peer.receive_dir
    ? `Files from ${displayName(peer)} go into ${peer.receive_dir}.`
    : shared
      ? `Files from ${displayName(peer)} go into the directory shared by all paired devices: ${shared}.`
      : `No directory is set, so transfers from ${displayName(peer)} are refused.`;
  const pick = async (close) => {
    close();
    const dir = await dialog.open({
      multiple: false,
      directory: true,
      defaultPath: peer.receive_dir || shared || undefined,
    });
    if (!dir) return;
    await core.invoke('paired_set_peer_receive_dir', { deviceId: peer.device_id, dir });
    await refreshEverywhere();
  };
  const actions = [{ label: 'Cancel', onClick: (close) => close() }];
  if (peer.receive_dir) {
    actions.push({
      label: 'Use the shared one',
      onClick: async (close) => {
        close();
        await core.invoke('paired_set_peer_receive_dir', { deviceId: peer.device_id, dir: null });
        await refreshEverywhere();
      },
    });
  }
  actions.push({ label: 'Choose…', onClick: pick });
  forkInfo('Receive directory', current, actions);
}

async function setAutoAccept(peer, allow) {
  await core.invoke('paired_set_auto_accept', {
    deviceId: peer.device_id,
    allow,
  });
  await refreshEverywhere();
}

/**
 * Asks first. The device can be found again by scanning, as long as both ends still share a
 * key — but its directory, its name here and its remembered address do not come back.
 */
function confirmForget(peer) {
  forkInfo(
    `Forget ${displayName(peer)}?`,
    'It disappears from the list, along with the directory and name set for it here. It can be found again by looking, as long as both devices still share the same pairing key — this does not un-pair anything.',
    [
      { label: 'Cancel', onClick: (close) => close() },
      {
        label: 'Forget',
        danger: true,
        onClick: async (close) => {
          close();
          await core.invoke('paired_forget', { deviceId: peer.device_id });
          await refreshEverywhere();
        },
      },
    ],
  );
}

/** The panel if it is open, and the strip on the main page either way. */
async function refreshEverywhere() {
  if (document.getElementById('forkDevicesPage')) {
    await refresh();
  } else {
    renderPills(await status());
  }
}

async function scan() {
  const list = document.getElementById('forkDeviceList');
  if (list) list.dataset.scanning = '1';
  const button = document.getElementById('forkScanButton');
  // The pill keeps its icon: only its label changes.
  const label = button && button.querySelector('.fork-pill-label');
  if (button) {
    button.disabled = true;
    label.innerText = 'Looking…';
  }
  try {
    const peers = await core.invoke('paired_scan');
    lastScan = new Map(peers.map((p) => [p.device_id, p]));
  } catch (e) {
    forkAlert('Could not look for devices', String(e));
  } finally {
    if (button) {
      button.disabled = false;
      label.innerText = 'Look again';
    }
  }
  await refresh();
}

/**
 * The hotspot route. A paired hotspot transfer needs no password, no QR and no Bluetooth —
 * both ends derive the credentials from the group key — but it still needs a tap on the far
 * device, because until the access point exists there is no channel through which to ask for
 * one. So this is one tap here and one tap there, rather than the two taps plus a password
 * ceremony on both devices that it replaces.
 */
async function hotspotInterface() {
  const interfaces = await core.invoke('get_wifi_interfaces');
  if (!interfaces.length) {
    throw new Error('No Wi-Fi interface found. Hotspot mode only works over Wi-Fi.');
  }
  const chosen = interfaces[0];
  return [chosen.name, chosen.guid];
}

async function overHotspot(peer, mode, fileList, receiveDir) {
  if (!peer.os) {
    // Which side raises the hotspot follows from the peer's OS, and that is only learned
    // on first contact over a network. Until then the pill is a promise, not a route.
    forkAlert(
      'Not yet heard from',
      `${displayName(peer)} has not answered on a network yet, so it is not known what kind of device it is. Open the app on it while both are on the same Wi-Fi and press “Look again” once.`,
    );
    return;
  }
  const page = document.getElementById('forkDevicesPage');
  if (page) page.remove();
  try {
    const refused = await core.invoke('start_async', {
      mode,
      peer: peer.os,
      password: null,
      interface: await hotspotInterface(),
      fileList: fileList || null,
      receiveDir: receiveDir || null,
      usingBluetooth: false,
      connectionMode: 'hotspot',
      pairedHotspot: peer.device_id,
      window: window.__TAURI__.window.getCurrentWindow(),
    });
    if (refused) forkAlert('Could not start', refused);
  } catch (e) {
    forkAlert('Could not start over hotspot', String(e));
  }
}

/**
 * What either half of a pill picks: files, or one folder. Both go through the same
 * expand_files as the main page's buttons do — a folder's files come back named relative to
 * its parent, which is what makes the far device recreate the folder rather than flatten it.
 * Null when there is nothing to send, and the reason has already been said.
 */
async function pickToSend(folder) {
  const picked = await dialog.open(
    folder ? { multiple: false, directory: true } : { multiple: true, directory: false },
  );
  if (!picked) return null;
  const paths = Array.isArray(picked) ? picked : [picked];
  const fileList = await core.invoke('expand_files', { paths });
  if (!fileList.length) {
    forkAlert(
      'Nothing to send',
      folder ? 'That directory is empty.' : 'None of what you picked could be read.',
    );
    return null;
  }
  return fileList;
}

async function sendOverHotspot(peer, folder = false) {
  const fileList = await pickToSend(folder);
  if (fileList) await overHotspot(peer, 'send', fileList, null);
}

async function receiveOverHotspot(peer) {
  const state = await status();
  const folder = peer.receive_dir
    || state.receive_dir
    || (await dialog.open({ multiple: false, directory: true }));
  if (!folder) return;
  await overHotspot(peer, 'receive', null, folder);
}

async function sendTo(peer, folder = false) {
  const fileList = await pickToSend(folder);
  if (fileList) await sendFilesTo(peer, fileList);
}

/**
 * The one-tap path, shared with anything else that already has a file list — a drop onto
 * the window, or a future share equivalent.
 */
export async function sendFilesTo(peer, fileList) {
  const page = document.getElementById('forkDevicesPage');
  if (page) page.remove();
  try {
    await core.invoke('paired_send', { deviceId: peer.device_id, fileList });
  } catch (e) {
    forkAlert(`Could not send to ${displayName(peer)}`, String(e));
  }
}

async function chooseReceiveDir() {
  const dir = await dialog.open({ multiple: false, directory: true });
  if (!dir) return;
  await core.invoke('paired_set_receive_dir', { dir });
  await refresh();
}

/**
 * Which refresh is the latest. Opening the panel, a scan finishing and a `pairedChanged`
 * from the backend all redraw, and a scan fires the event while it is still running — so two
 * refreshes routinely overlap. Each used to clear the body and then, after its await, append;
 * both appended, and the panel showed everything twice (白い熊, 2026-09-11). Now the body is
 * built off-screen and only the newest call is allowed to put it in place.
 */
let refreshGeneration = 0;

async function refresh() {
  const page = document.getElementById('forkDevicesPage');
  if (!page) return;
  const generation = ++refreshGeneration;

  const state = await status();
  renderPills(state);
  if (generation !== refreshGeneration) return;
  const body = page.querySelector('[data-devices-body]');
  if (!body) return;
  body.replaceChildren(...buildPanelBody(state));
}

/**
 * The panel's two parts, as detached nodes: this device, then the paired ones — a list with
 * one icon column and one label edge, a line between every two devices.
 */
function buildPanelBody(state) {
  // Identity.
  const identity = el('div');
  identity.appendChild(el('div', 'fork-panel-heading', 'This device'));

  // A control row: plain.
  const nameRow = el('label', 'fork-panel-row');
  nameRow.appendChild(icon('tag', 'contact'));
  nameRow.appendChild(el('span', null, 'Name'));
  const nameInput = el('input', 'fork-text-input');
  nameInput.type = 'text';
  nameInput.value = state.name;
  nameInput.onchange = async () => {
    await core.invoke('paired_set_name', { name: nameInput.value });
    await refresh();
  };
  nameRow.appendChild(nameInput);
  identity.appendChild(nameRow);

  // It opens the directory picker, so it is a pill; where files go stands beneath it.
  const dirRow = actionRow(
    'Receive into',
    state.receive_dir || 'No directory set — transfers from paired devices will be refused.',
    { icon: 'directory', tint: 'view', opens: true, onClick: chooseReceiveDir },
  );
  if (!state.receive_dir) dirRow.summary.classList.add('fork-warn');
  identity.appendChild(dirRow);

  identity.appendChild(
    el(
      'div',
      'fork-panel-note',
      state.serving
        ? `Listening on port ${state.port}. This device accepts transfers from paired devices for as long as the app is open.`
        : 'Not paired with anything yet.',
    ),
  );

  // The devices.
  const devices = el('div');
  devices.appendChild(el('div', 'fork-hairline'));
  devices.appendChild(el('div', 'fork-panel-heading', 'Paired devices'));

  if (!state.paired) {
    devices.appendChild(
      el(
        'div',
        'fork-info-body',
        'Pair two devices once — show a code on either one, scan or type it on the other. After that, sending is one click and there is nothing to do on the device receiving.',
      ),
    );
  } else {
    const merged = state.peers.map((p) => {
      const live = lastScan.get(p.device_id);
      return live ? { ...p, ...live } : p;
    });
    const list = el('div', 'fork-list');
    merged.forEach((peer, i) => {
      if (i > 0) list.appendChild(el('div', 'fork-list-divider'));
      list.appendChild(deviceGroup(peer));
    });
    devices.appendChild(list);
  }
  return [identity, devices];
}

// ── The strip on the main page ────────────────────────────────────────────────────────────

function half(className, onClick, title) {
  const button = el('button', `fork-device-pill ${className}`);
  button.type = 'button';
  button.title = title;
  button.onclick = onClick;
  return button;
}

/**
 * One row of a device column: a pill with two targets. The name half sends files, exactly
 * the one click it always was; the other half sends one directory. It is the strip's version
 * of the main page's "Files to send | Directory to send" twins — a choice made by where the
 * click lands rather than by a mode set beforehand or a question asked afterwards.
 */
function splitPill(name, route, onFiles, onDirectory) {
  const row = el('div', 'fork-split-pill');
  const files = half('fork-pill-files', onFiles, `Send files to ${name} ${route}`);
  const directory = half('fork-pill-directory', onDirectory, `Send a directory to ${name} ${route}`);
  directory.appendChild(icon('directory', 'share'));
  row.appendChild(files);
  row.appendChild(directory);
  return files;
}

/** A column is being dragged; the strip must not be redrawn out from under it. */
let dragging = null;

/**
 * Press a column and move it, and it is dragged: the desktop's version of Android's
 * hold-to-move. Reorders on the fly as the pointer crosses another column, so the order is
 * visible while choosing it, and is saved when the button is let go. Pointer events rather
 * than HTML5 drag-and-drop, because the window's native drop handler — the one that takes
 * files dropped onto the app — is exactly the machinery HTML5 drags go through, and the two
 * were not written to share it.
 *
 * A press that never moves is a click and is left alone; one that does is not allowed to
 * become a click afterwards, or letting go of a dragged pill would open a file picker.
 */
function draggable(column, strip) {
  let pressed = null;
  let ghost = null;
  let moved = false;

  column.addEventListener('pointerdown', (event) => {
    if (event.button !== 0 || dragging) return;
    pressed = { x: event.clientX, y: event.clientY, id: event.pointerId };
    moved = false;
  });

  column.addEventListener('pointermove', (event) => {
    if (!pressed || event.pointerId !== pressed.id) return;
    if (!dragging) {
      // A few pixels of slack, so a slightly unsteady click stays a click.
      if (Math.hypot(event.clientX - pressed.x, event.clientY - pressed.y) < 6) return;
      dragging = column;
      column.setPointerCapture(event.pointerId);
      const rect = column.getBoundingClientRect();
      pressed.dx = pressed.x - rect.left;
      pressed.dy = pressed.y - rect.top;
      ghost = column.cloneNode(true);
      ghost.className = 'fork-device-column fork-drag-ghost';
      ghost.style.width = `${rect.width}px`;
      document.body.appendChild(ghost);
      column.classList.add('fork-dragging');
    }
    event.preventDefault();
    ghost.style.transform =
      `translate(${event.clientX - pressed.dx}px, ${event.clientY - pressed.dy}px)`;
    // The ghost lets pointer events through, and the dragged column is found only once it
    // has already taken the slot the pointer is over — which is what stops the swapping.
    const under = document.elementFromPoint(event.clientX, event.clientY);
    const target = under && under.closest('.fork-device-column');
    if (!target || target === column || target.parentElement !== strip) return;
    const columns = [...strip.children];
    const from = columns.indexOf(column);
    const to = columns.indexOf(target);
    strip.insertBefore(column, from < to ? target.nextSibling : target);
    moved = true;
  });

  const release = async (event) => {
    if (!pressed || event.pointerId !== pressed.id) return;
    pressed = null;
    if (dragging !== column) return;
    dragging = null;
    if (ghost) ghost.remove();
    ghost = null;
    column.classList.remove('fork-dragging');
    if (column.hasPointerCapture(event.pointerId)) column.releasePointerCapture(event.pointerId);
    swallowNextClick();
    if (moved) {
      const order = [...strip.children].map((c) => c.dataset.deviceId);
      try {
        await core.invoke('paired_reorder', { order });
      } catch (e) {
        forkAlert('Could not save the order', String(e));
      }
    }
    // Whatever changed under the drag — a scan finishing, say — was held back; draw it now.
    try {
      renderPills(await status());
    } catch (e) {
      console.warn('paired status unavailable after a drag', e);
    }
  };
  column.addEventListener('pointerup', release);
  column.addEventListener('pointercancel', release);
}

/** The click a browser makes out of the pointer-up that ended a drag. */
function swallowNextClick() {
  const swallow = (event) => {
    event.stopPropagation();
    event.preventDefault();
    window.removeEventListener('click', swallow, true);
  };
  window.addEventListener('click', swallow, true);
  // No click comes when the button was released off the pills; then nothing must be eaten.
  setTimeout(() => window.removeEventListener('click', swallow, true), 0);
}

/**
 * The paired devices as one click each, on the main page — the desktop end of the Android
 * strip, and the same shape: a column per device, the network route on top and the hotspot
 * route underneath, each a split pill of files | folder. Drawn from the store alone, so it
 * is there the moment the window is and does not wait for a scan; a device that turns out
 * not to be on the network says so when it is clicked, as the panel's row does. Columns are
 * dragged to reorder, and the store's order is the order.
 */
function renderPills(state) {
  const strip = document.getElementById('devicePills');
  if (!strip || dragging) return;
  const peers = state.paired ? state.peers : [];
  strip.hidden = peers.length === 0;
  const columns = peers.map((peer) => {
    const name = displayName(peer);
    const column = el('div', 'fork-device-column');
    column.dataset.deviceId = peer.device_id;
    const network = splitPill(
      name,
      'over this network',
      () => sendTo(peer, false),
      () => sendTo(peer, true),
    );
    // Both routes reach the device, so both marks are the green of connecting.
    network.appendChild(icon('wifi', 'connect'));
    network.appendChild(document.createTextNode(name));
    const hotspot = splitPill(
      name,
      'over a hotspot',
      () => sendOverHotspot(peer, false),
      () => sendOverHotspot(peer, true),
    );
    hotspot.appendChild(icon('bolt', 'connect'));
    hotspot.appendChild(document.createTextNode(name));
    column.appendChild(network.parentElement);
    column.appendChild(hotspot.parentElement);
    // Right-click is the desktop's hold: the same menu Android opens on a long press.
    column.addEventListener('contextmenu', (event) => {
      event.preventDefault();
      if (!dragging) deviceMenu(peer, event);
    });
    draggable(column, strip);
    return column;
  });
  strip.replaceChildren(...columns);
}

export function openDevicesPanel() {
  const existing = document.getElementById('forkDevicesPage');
  if (existing) existing.remove();

  const page = el('div');
  page.id = 'forkDevicesPage';
  page.className = 'fork-overlay';
  const box = el('div', 'fork-panel-box');
  page.appendChild(box);

  box.appendChild(el('div', 'fork-panel-title', 'Devices'));
  box.appendChild(
    el(
      'div',
      'fork-panel-desc',
      'Devices you have paired with. Click one to send it files over this network — it does '
        + 'not have to be armed, and nobody has to accept anything over there. The hotspot '
        + 'buttons work with no network at all, but need one tap on the other device too.',
    ),
  );

  const bodyHolder = el('div');
  bodyHolder.dataset.devicesBody = '1';
  box.appendChild(bodyHolder);

  const bar = el('div', 'fork-pill-bar');
  // Pairing is what the panel is for, so that is its one main pill; Done is the way out.
  const close = pill('Done');
  close.onclick = () => page.remove();
  bar.appendChild(close);
  bar.appendChild(el('div', 'fork-pill-spacer'));

  const scanButton = pill('Look again', { icon: 'search', tint: 'connect' });
  scanButton.id = 'forkScanButton';
  scanButton.onclick = scan;
  bar.appendChild(scanButton);

  const pair = pill('Pair a device', { main: true, icon: 'device', tint: 'contact', opens: true });
  pair.onclick = () => {
    // Either direction works; this PC has no camera, so its own code is the one that gets
    // scanned, and a phone's code is typed.
    forkInfo(
      'Pair a device',
      'Show a code for the other device to scan, or type in the code shown on it. Either way pairs just those two devices and changes nothing else.',
      [
        { label: 'Cancel', onClick: (c) => c() },
        {
          label: 'Type a code',
          onClick: (c) => {
            c();
            askForPairCode();
          },
        },
        {
          label: 'Show my code',
          onClick: async (c) => {
            c();
            try {
              const uri = await core.invoke('paired_show_code');
              const typed = await core.invoke('paired_typed_code', { uri });
              showPairCode(uri, typed);
            } catch (e) {
              forkAlert('Could not make a pairing code', String(e));
            }
          },
        },
      ],
    );
  };
  bar.appendChild(pair);
  box.appendChild(bar);

  document.body.appendChild(page);
  refresh().then(scan);
}

// ── Events from the backend ───────────────────────────────────────────────────────────────

window.addEventListener('DOMContentLoaded', async () => {
  const appWindow = window.__TAURI__.window.getCurrentWindow();
  // The store changed under us — a scan learned a device, or the serve loop noted an
  // address. The strip on the main page follows every change; the panel only if it is open.
  await appWindow.listen('pairedChanged', (event) => {
    if (document.getElementById('forkDevicesPage')) {
      refresh();
    } else if (event.payload) {
      renderPills(event.payload);
    }
  });
  try {
    renderPills(await status());
  } catch (e) {
    console.warn('paired status unavailable at start', e);
  }
  // An arriving transfer needs no separate channel: the serve loop's own lines already go
  // out as outputMsg, which main.js writes into the log like any other transfer's.
});

window.forkDevices = { openDevicesPanel, sendFilesTo };
