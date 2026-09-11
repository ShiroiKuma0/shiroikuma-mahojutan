// Fork: the Devices panel — the desktop end of paired devices.
//
// One list, one tap. No mode to choose, no password to agree, nothing to arm on the far
// device. Everything this page does is a call into fork_paired.rs; there is no networking
// in the webview.
//
// It reuses customize.js's dialog primitives rather than growing its own, because a second
// set would drift out of step with the theme the first one follows.

import { el, forkInfo, forkAlert } from './customize.js';
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

function whenSeen(seconds) {
  if (!seconds) return 'never seen on a network';
  const ago = Math.floor(Date.now() / 1000) - seconds;
  if (ago < 60) return 'seen just now';
  if (ago < 3600) return `seen ${Math.floor(ago / 60)} min ago`;
  if (ago < 86400) return `seen ${Math.floor(ago / 3600)} h ago`;
  return `seen ${Math.floor(ago / 86400)} days ago`;
}

/**
 * Groups the pairing key into fives so it can be read across and typed without losing the
 * place. Purely presentational — base32_decode skips the spaces on the way back in.
 */
function grouped(key) {
  return (key.match(/.{1,5}/g) || []).join(' ');
}

// ── Pairing ───────────────────────────────────────────────────────────────────────────────

/**
 * Shows the code another device scans or types. The QR and the text carry exactly the same
 * string, which is the same arrangement the shared-network password already uses: scan it
 * or read it, whichever the other device can manage.
 */
function showPairCode(uri) {
  const key = uri.split(':')[2] || uri;
  const overlay = el('div', 'fork-overlay');
  const box = el('div', 'fork-info-box');
  box.appendChild(el('div', 'fork-info-title', 'Pair a device'));
  box.appendChild(
    el(
      'div',
      'fork-info-body',
      'On the other device, open Devices and choose “Pair with a device”, then scan this code — or type the key underneath it.',
    ),
  );

  const qrHolder = el('div');
  qrHolder.style.cssText =
    'background:#ffff00; padding:16px; width:214px; margin:12px auto 6px auto;';
  new QRCode(qrHolder, { text: uri, width: 182, height: 182, colorLight: '#ffff00' });
  const caption = el('div', null, grouped(key));
  caption.style.cssText =
    'margin-top:8px; text-align:center; font-family:monospace; font-weight:bold;'
    + ' color:#000000; font-size:13px; letter-spacing:1px; line-height:1.5; word-break:break-all;';
  qrHolder.appendChild(caption);
  box.appendChild(qrHolder);

  const warn = el(
    'div',
    'fork-info-hint',
    'Anyone who has this key can send files to your devices. Show it, don’t send it.',
  );
  box.appendChild(warn);

  const row = el('div', 'fork-info-actions');
  const done = el('button', 'fork-pill', 'Done');
  done.type = 'button';
  done.onclick = () => {
    overlay.remove();
    refresh();
  };
  row.appendChild(done);
  box.appendChild(row);
  overlay.appendChild(box);
  document.body.appendChild(overlay);
}

/** Joins an existing group from a key typed or pasted off the other device's screen. */
function askForPairCode() {
  const overlay = el('div', 'fork-overlay');
  const box = el('div', 'fork-info-box');
  box.appendChild(el('div', 'fork-info-title', 'Join a device'));
  box.appendChild(
    el(
      'div',
      'fork-info-body',
      'Type or paste the key shown under the QR code on the other device. Spaces and capitals do not matter.',
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
  const join = el('button', 'fork-pill', 'Join');
  join.type = 'button';
  join.onclick = async () => {
    const text = input.value.trim();
    if (!text) return;
    try {
      await core.invoke('paired_join', { text });
      overlay.remove();
      await refresh();
      // A key that decodes is not yet a key that matches: only a scan proves that, so the
      // scan happens now rather than leaving the user to wonder whether it worked.
      await scan();
    } catch (e) {
      forkAlert('Could not join', String(e));
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

function peerRow(peer, onSend) {
  const row = el('div', 'fork-setting-row');
  row.style.marginLeft = '0';
  row.style.cursor = peer.reachable || peer.last_ip ? 'pointer' : 'default';

  const title = el('div', 'fork-setting-title', peer.name || `(unnamed · ${shortId(peer.device_id)})`);
  row.appendChild(title);

  const state = peer.reachable
    ? `● on this network — ${peer.last_ip}`
    : peer.last_ip
      ? `○ ${whenSeen(peer.last_seen)} at ${peer.last_ip}`
      : `○ ${whenSeen(peer.last_seen)}`;
  const summary = el('div', 'fork-setting-summary', `${peer.os} · ${state}`);
  if (!peer.reachable) summary.classList.add('fork-note');
  row.appendChild(summary);

  if (!peer.auto_accept) {
    row.appendChild(
      el(
        'div',
        'fork-setting-summary fork-warn',
        'Set not to accept transfers from this device without asking',
      ),
    );
  }

  row.onclick = () => onSend(peer);
  return row;
}

/** Everything that can be done to one device, once it is in the list. */
function peerActions(peer, container) {
  const row = el('div', 'fork-info-actions');
  row.style.marginTop = '0';

  const accept = el(
    'button',
    'fork-mini-btn',
    peer.auto_accept ? 'Ask before accepting' : 'Accept without asking',
  );
  accept.type = 'button';
  accept.onclick = async (event) => {
    event.stopPropagation();
    await core.invoke('paired_set_auto_accept', {
      deviceId: peer.device_id,
      allow: !peer.auto_accept,
    });
    await refresh();
  };

  const forget = el('button', 'fork-mini-btn', 'Forget');
  forget.type = 'button';
  forget.onclick = (event) => {
    event.stopPropagation();
    forkInfo(
      'Forget this device?',
      `${peer.name || shortId(peer.device_id)} will disappear from the list. It can be found again by scanning, as long as both devices still share the same pairing key.`,
      [
        { label: 'Cancel', onClick: (close) => close() },
        {
          label: 'Forget',
          onClick: async (close) => {
            close();
            await core.invoke('paired_forget', { deviceId: peer.device_id });
            await refresh();
          },
        },
      ],
    );
  };

  const hotspotSend = el('button', 'fork-mini-btn', 'Send over hotspot');
  hotspotSend.type = 'button';
  hotspotSend.onclick = (event) => {
    event.stopPropagation();
    sendOverHotspot(peer);
  };

  const hotspotReceive = el('button', 'fork-mini-btn', 'Receive over hotspot');
  hotspotReceive.type = 'button';
  hotspotReceive.onclick = (event) => {
    event.stopPropagation();
    receiveOverHotspot(peer);
  };

  row.appendChild(hotspotSend);
  row.appendChild(hotspotReceive);
  row.appendChild(accept);
  row.appendChild(forget);
  container.appendChild(row);
}

async function scan() {
  const list = document.getElementById('forkDeviceList');
  if (list) list.dataset.scanning = '1';
  const button = document.getElementById('forkScanButton');
  if (button) {
    button.disabled = true;
    button.innerText = 'Looking…';
  }
  try {
    const peers = await core.invoke('paired_scan');
    lastScan = new Map(peers.map((p) => [p.device_id, p]));
  } catch (e) {
    forkAlert('Could not look for devices', String(e));
  } finally {
    if (button) {
      button.disabled = false;
      button.innerText = 'Look again';
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
      pairedHotspot: true,
      window: window.__TAURI__.window.getCurrentWindow(),
    });
    if (refused) forkAlert('Could not start', refused);
  } catch (e) {
    forkAlert('Could not start over hotspot', String(e));
  }
}

async function sendOverHotspot(peer) {
  const picked = await dialog.open({ multiple: true, directory: false });
  if (!picked) return;
  const paths = Array.isArray(picked) ? picked : [picked];
  const fileList = await core.invoke('expand_files', { paths });
  if (!fileList.length) {
    forkAlert('Nothing to send', 'None of what you picked could be read.');
    return;
  }
  await overHotspot(peer, 'send', fileList, null);
}

async function receiveOverHotspot(peer) {
  const state = await status();
  const folder = state.receive_dir
    || (await dialog.open({ multiple: false, directory: true }));
  if (!folder) return;
  await overHotspot(peer, 'receive', null, folder);
}

async function sendTo(peer) {
  const picked = await dialog.open({ multiple: true, directory: false });
  if (!picked) return;
  const paths = Array.isArray(picked) ? picked : [picked];
  const fileList = await core.invoke('expand_files', { paths });
  if (!fileList.length) {
    forkAlert('Nothing to send', 'None of what you picked could be read.');
    return;
  }
  await sendFilesTo(peer, fileList);
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
    forkAlert(`Could not send to ${peer.name || 'that device'}`, String(e));
  }
}

async function chooseReceiveFolder() {
  const folder = await dialog.open({ multiple: false, directory: true });
  if (!folder) return;
  await core.invoke('paired_set_receive_dir', { dir: folder });
  await refresh();
}

async function refresh() {
  const page = document.getElementById('forkDevicesPage');
  if (!page) return;
  const body = page.querySelector('[data-devices-body]');
  if (!body) return;
  body.innerHTML = '';

  const state = await status();

  // Identity.
  const identity = el('div', 'fork-section');
  identity.appendChild(el('div', 'fork-section-title', 'This device'));
  identity.appendChild(el('div', 'fork-section-rule'));

  const nameRow = el('div', 'fork-row');
  nameRow.appendChild(el('span', 'fork-label', 'Name'));
  const nameInput = el('input', 'fork-text-input');
  nameInput.type = 'text';
  nameInput.value = state.name;
  nameInput.onchange = async () => {
    await core.invoke('paired_set_name', { name: nameInput.value });
    await refresh();
  };
  nameRow.appendChild(nameInput);
  identity.appendChild(nameRow);

  const folderRow = el('div', 'fork-setting-row');
  folderRow.style.marginLeft = '0';
  folderRow.appendChild(el('div', 'fork-setting-title', 'Receive into'));
  const folderValue = el(
    'div',
    'fork-setting-summary',
    state.receive_dir || 'No folder set — transfers from paired devices will be refused',
  );
  if (!state.receive_dir) folderValue.classList.add('fork-warn');
  folderRow.appendChild(folderValue);
  folderRow.style.cursor = 'pointer';
  folderRow.onclick = chooseReceiveFolder;
  identity.appendChild(folderRow);

  identity.appendChild(
    el(
      'div',
      'fork-note',
      state.paired
        ? `Listening on port ${state.port}. This device accepts transfers from paired devices for as long as the app is open.`
        : 'Not paired with anything yet.',
    ),
  );
  body.appendChild(identity);

  // The devices.
  const devices = el('div', 'fork-section');
  devices.appendChild(el('div', 'fork-section-title', 'Paired devices'));
  devices.appendChild(el('div', 'fork-section-rule'));

  if (!state.paired) {
    devices.appendChild(
      el(
        'div',
        'fork-info-body',
        'Pairing agrees one key between your devices, once. After that, sending is one click and there is nothing to do on the device receiving.',
      ),
    );
  } else {
    const merged = state.peers.map((p) => {
      const live = lastScan.get(p.device_id);
      return live ? { ...p, ...live } : p;
    });
    if (!merged.length) {
      devices.appendChild(
        el(
          'div',
          'fork-info-body',
          'No devices found yet. Open the app on the other device and press “Look again”.',
        ),
      );
    }
    for (const peer of merged) {
      const holder = el('div');
      holder.appendChild(peerRow(peer, sendTo));
      peerActions(peer, holder);
      devices.appendChild(holder);
    }
  }
  body.appendChild(devices);
}

export function openDevicesPanel() {
  const existing = document.getElementById('forkDevicesPage');
  if (existing) existing.remove();

  const page = el('div');
  page.id = 'forkDevicesPage';
  page.className = 'fork-overlay';
  const box = el('div', 'fork-panel-box');
  page.appendChild(box);

  const header = el('div', 'fork-page-header');
  header.appendChild(el('div', 'fork-page-title', 'Devices'));
  box.appendChild(header);
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
  const close = el('button', 'fork-pill', 'Done');
  close.type = 'button';
  close.onclick = () => page.remove();
  bar.appendChild(close);
  bar.appendChild(el('div', 'fork-pill-spacer'));

  const scanButton = el('button', 'fork-pill', 'Look again');
  scanButton.id = 'forkScanButton';
  scanButton.type = 'button';
  scanButton.onclick = scan;
  bar.appendChild(scanButton);

  const pair = el('button', 'fork-pill', 'Pair a device');
  pair.type = 'button';
  pair.onclick = async () => {
    const state = await status();
    // Two different things wear the same word. Starting a group makes a key for the others
    // to scan; joining one takes a key that already exists. Asking outright is shorter than
    // any label that tries to explain the difference in place.
    forkInfo(
      'Pair a device',
      state.paired
        ? 'This device already belongs to a group. Show its key so another device can join, or leave and join a different group.'
        : 'Show a key for other devices to scan, or type in a key from a device that already has one.',
      [
        { label: 'Cancel', onClick: (c) => c() },
        {
          label: 'Type a key',
          onClick: (c) => {
            c();
            askForPairCode();
          },
        },
        ...(state.paired
          ? [{
              // A stable hotspot SSID is a broadcast identifier that follows the device
              // around — a linkability leak, not a confidentiality one, since the payload
              // stays behind the group key. Changing it means changing the key it derives
              // from, and there is no channel through which to tell the other devices, so
              // they have to be paired again.
              label: 'New key',
              onClick: (c) => {
                c();
                forkInfo(
                  'Start a new key?',
                  'This changes the key every paired device shares, and the hotspot name '
                    + 'derived from it. Every other device stops being able to find or reach '
                    + 'this one until it is paired again — there is no way to tell them, '
                    + 'because the key was the only thing they had in common.',
                  [
                    { label: 'Cancel', onClick: (close) => close() },
                    {
                      label: 'New key',
                      onClick: async (close) => {
                        close();
                        await core.invoke('paired_leave');
                        showPairCode(await core.invoke('paired_create_group'));
                      },
                    },
                  ],
                );
              },
            }]
          : []),
        {
          label: state.paired ? 'Show my key' : 'Show a new key',
          onClick: async (c) => {
            c();
            try {
              // Re-showing must never re-key: every device already paired would be
              // stranded, silently, and the only symptom would be that nothing is ever
              // found again.
              const uri = state.paired
                ? await core.invoke('paired_pair_code')
                : await core.invoke('paired_create_group');
              showPairCode(uri);
            } catch (e) {
              forkAlert('Could not make a pairing key', String(e));
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
  // address. Redraw only if the page is actually open.
  await appWindow.listen('pairedChanged', () => {
    if (document.getElementById('forkDevicesPage')) refresh();
  });
  // An arriving transfer needs no separate channel: the serve loop's own lines already go
  // out as outputMsg, which main.js writes into the log like any other transfer's.
});

window.forkDevices = { openDevicesPanel, sendFilesTo };
