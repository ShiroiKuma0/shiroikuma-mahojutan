// Fork: parallel paired transfers — one block per lane.
//
// A paired transfer over the shared network no longer takes the window. Each runs in a lane
// of its own (fork_lanes.rs), and each lane gets a block here: which way it is going and
// with whom, its own bars and readout, and its own Cancel. The main screen stays free the
// whole time, so another selection can go to another device while these run.
//
// Hotspot and password-mode transfers are not lanes. They keep the bars at the bottom of
// the window and the one Cancel button, exactly as before, and never run beside a lane.
//
// Nothing here decides anything: the backend opens, queues, starts and ends lanes and this
// page draws what it is told.

import { el, icon, pill } from './customize.js';

const { core } = window.__TAURI__;

/** Lane id → the pieces of its block that change. */
const lanes = new Map();

function stack() {
  return document.getElementById('laneStack');
}

function setStatus(lane, text) {
  lane.status.innerText = text;
  lane.status.style.display = text ? '' : 'none';
}

function openLane(info) {
  if (lanes.has(info.id)) return;
  const block = el('div', 'fork-lane');
  block.dataset.lane = String(info.id);

  const head = el('div', 'fork-lane-head');
  // Which way it goes is the lane's icon, coloured by what the lane does: sending out is the
  // orange of sharing, taking in the green of connecting.
  const sending = info.direction === 'send';
  const title = el('span', 'fork-lane-title');
  title.appendChild(icon(sending ? 'send' : 'receive', sending ? 'share' : 'connect'));
  title.appendChild(el('span', 'fork-lane-name', info.peer_name || 'Paired device'));
  const status = el('span', 'fork-lane-status');
  const cancel = pill('Cancel', { className: 'fork-lane-cancel', danger: true });
  cancel.onclick = async () => {
    // Said once and then left alone: a running lane stops at its next await, and a second
    // click has nothing more to ask for.
    cancel.disabled = true;
    cancel.innerText = 'Cancelling…';
    try {
      await core.invoke('lane_cancel', { lane: info.id });
    } catch (e) {
      console.warn('lane_cancel failed', e);
    }
  };
  head.appendChild(title);
  head.appendChild(status);
  head.appendChild(cancel);
  block.appendChild(head);

  const details = el('div', 'fork-lane-details');
  const bar = document.createElement('progress');
  bar.className = 'fork-lane-bar';
  bar.max = 100;
  bar.value = 0;
  // The whole-transfer pair only earns its space when there is more than one file.
  const totalDetails = el('div', 'fork-lane-details');
  const totalBar = document.createElement('progress');
  totalBar.className = 'fork-lane-bar';
  totalBar.max = 100;
  totalBar.value = 0;
  for (const piece of [details, bar, totalDetails, totalBar]) {
    piece.style.display = 'none';
    block.appendChild(piece);
  }

  const lane = { block, status, cancel, details, bar, totalDetails, totalBar };
  lanes.set(info.id, lane);
  setStatus(
    lane,
    info.queued ? 'Queued — it starts when the transfer before it ends.' : 'Connecting…',
  );
  stack().appendChild(block);
  stack().hidden = false;
}

function startLane(id) {
  const lane = lanes.get(id);
  if (lane) setStatus(lane, 'Connecting…');
}

function closeLane(id) {
  const lane = lanes.get(id);
  if (!lane) return;
  lane.block.remove();
  lanes.delete(id);
  stack().hidden = lanes.size === 0;
}

function showBars(lane) {
  setStatus(lane, '');
  lane.details.style.display = '';
  lane.bar.style.display = '';
}

window.addEventListener('DOMContentLoaded', async () => {
  const appWindow = window.__TAURI__.window.getCurrentWindow();

  await appWindow.listen('laneOpened', (event) => openLane(event.payload));
  await appWindow.listen('laneStarted', (event) => startLane(event.payload.lane));
  await appWindow.listen('laneEnded', (event) => closeLane(event.payload.lane));

  await appWindow.listen('laneProgress', (event) => {
    const lane = lanes.get(event.payload.lane);
    if (!lane) return;
    showBars(lane);
    lane.bar.value = event.payload.value;
  });
  await appWindow.listen('laneTotalProgress', (event) => {
    const lane = lanes.get(event.payload.lane);
    if (lane) lane.totalBar.value = event.payload.value;
  });
  await appWindow.listen('laneDetails', (event) => {
    const lane = lanes.get(event.payload.lane);
    if (!lane) return;
    showBars(lane);
    lane.details.innerText = event.payload.current;
    lane.totalDetails.innerText = event.payload.total;
    // "File 1 of 1" needs no second row; anything else does. The same test main.js makes.
    const multi = !/^File 1 of 1\b/.test(event.payload.total);
    lane.totalDetails.style.display = multi ? '' : 'none';
    lane.totalBar.style.display = multi ? '' : 'none';
  });

  // The far device already has a file this lane is about to send. Asked through the one
  // queue main.js keeps, so two lanes — or a lane and anything else — never put two
  // dialogs up at once, and the question says which device it is about.
  await appWindow.listen('laneFileConflict', async (event) => {
    const { lane, peer, name, identical, more_files } = event.payload;
    const answer = await window.forkConflict.ask(name, identical, more_files, peer);
    await core.invoke('lane_file_conflict', {
      lane,
      choice: answer.choice,
      newName: answer.newName,
      applyToAll: answer.applyToAll,
    });
  });

  // A reloaded window finds the lanes that were already running. Their bars fill in with
  // the next progress event.
  try {
    for (const info of await core.invoke('lanes_status')) openLane(info);
  } catch (e) {
    console.warn('lanes status unavailable at start', e);
  }
});

window.forkLanes = {
  /** True while any paired transfer is running or queued. */
  active: () => lanes.size > 0,
};
