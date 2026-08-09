const { core, dialog, os } = window.__TAURI__;
import { QRCode } from './deps/qrcode.js'

let aboutButton;
let canUseBluetooth = false;
let usingBluetooth;
let bluetoothSwitch;
// Set by whichever of the two send buttons started this transfer: "Files to send" or
// "Directory to send". It replaced a "Send Folder" tick box that had to be found and ticked
// before pressing Start (白い熊, 2026-08-08).
let sendingFolder = false;
let peerLabel;
let peerBox;
let outputBox;
let startButton;
let cancelButton;
let progressBar;
let lastFolderButton;
let progressDetails;
let progressTotalDetails;
let totalProgressBar;
let appWindow;
let connectionModeLabel;
let connectionModeBox;

let selectedMode;
let selectedPeer;
let selectedFiles;
let selectedFolder;
// Fork default: Shared Network rather than upstream's Hotspot.
let connectionMode = 'shared_network';

// 'idle' -> 'starting' (user is picking files/password) -> 'running' -> 'cancelling' -> 'idle'.
// Everything that can kick off or stop a transfer (the buttons, Enter, drag and drop) checks
// this first, so clicks that arrive while a transfer is starting or winding down are dropped
// instead of queueing up behind it. The backend enforces the same rule independently.
let transferState = 'idle';

// save UI if user refreshes
window.onunload = () => {
  let uiState = {
    usingBluetooth: usingBluetooth,
    // canUseBluetooth:
    sendingFolder: sendingFolder,
    selectedMode: selectedMode,
    selectedPeer: selectedPeer,
    selectedFiles: selectedFiles,
    selectedFolder: selectedFolder,
    output: outputBox.innerText,
    transferRunning: startButton.style.display === 'none',
    progressBarValue: progressBar.value,
    progressBarVisible: progressBar.style.display !== 'none',
    connectionMode: connectionMode,
    progressDetailsText: progressDetails.innerText,
    progressTotalText: progressTotalDetails.innerText,
    totalProgressValue: totalProgressBar.value,
  };
  let uiJSON = JSON.stringify(uiState);
  sessionStorage.setItem('pageState', uiJSON);
}

window.addEventListener('DOMContentLoaded', async () => {
  aboutButton = document.getElementById('aboutButton');
  peerLabel = document.getElementById('peerLabel');
  peerBox = document.getElementById('peerBox');
  outputBox = document.getElementById('outputBox');
  startButton = document.getElementById('startButton');
  cancelButton = document.getElementById('cancelButton');
  progressBar = document.getElementById('progressBar');
  lastFolderButton = document.getElementById('lastFolderButton');
  progressDetails = document.getElementById('progressDetails');
  progressTotalDetails = document.getElementById('progressTotalDetails');
  totalProgressBar = document.getElementById('totalProgressBar');
  bluetoothSwitch = document.getElementById('bluetoothSwitch');
  connectionModeLabel = document.getElementById('connectionModeLabel');
  connectionModeBox = document.getElementById('connectionModeBox');

  appWindow = window.__TAURI__.window.getCurrentWindow();

  // check for bluetooth support
  let error = await core.invoke('check_support');
  if (error != null) {
    output(`Bluetooth initialization failed: ${error}. Disable the Bluetooth switch in Flying Carpet on the other device to run a transfer.`);
    bluetoothSwitch.disabled = true;
    bluetoothSwitch.checked = false;
    usingBluetooth = false;
    canUseBluetooth = false;
  } else {
    output('Bluetooth is supported.');
    bluetoothSwitch.disabled = false;
    // whatever it was last set to, or on for a first run
    bluetoothSwitch.checked = rememberedBluetooth();
    usingBluetooth = bluetoothSwitch.checked;
    canUseBluetooth = true;
  }

  // about button (fork: themeable in-page dialog instead of the native alert)
  aboutButton.onclick = () => {
    window.forkUI.showAbout(aboutMessage);
  }

  // output handler
  await appWindow.listen('outputMsg', (event) => {
    output(event.payload.message);
  });

  // progress bar handlers
  await appWindow.listen('showProgressBar', (_event) => {
    progressBar.style.display = '';
    progressDetails.style.display = '';
    // the second bar and line only earn their space when there is more than one file
    let multi = totalProgressBar.dataset.multiFile === 'true';
    totalProgressBar.style.display = multi ? '' : 'none';
    progressTotalDetails.style.display = multi ? '' : 'none';
  });
  await appWindow.listen('updateProgressBar', (event) => {
    progressBar.value = event.payload.value;
  });
  await appWindow.listen('updateProgressDetails', (event) => {
    progressDetails.innerText = event.payload.current;
    progressTotalDetails.innerText = event.payload.total;
    // "File 1 of 1" needs no second row; anything else does
    let multi = !/^File 1 of 1\b/.test(event.payload.total);
    totalProgressBar.dataset.multiFile = multi ? 'true' : 'false';
    totalProgressBar.style.display = multi ? '' : 'none';
    progressTotalDetails.style.display = multi ? '' : 'none';
  });
  await appWindow.listen('updateTotalProgressBar', (event) => {
    totalProgressBar.value = event.payload.value;
  });

  // enable UI when transfer finishes. ignored mid-cancel: the aborted transfer task emits
  // this as it unwinds, and re-showing the Start button before the cancel has actually
  // finished is what used to let a stray click start a second transfer.
  await appWindow.listen('enableUi', (_event) => {
    if (transferState === 'cancelling') {
      return;
    }
    // clear the readout: leaving the last file's figures on screen reads as if a transfer is
    // still in flight (Android already clears its line on finish).
    progressDetails.innerText = '';
    progressTotalDetails.innerText = '';
    progressDetails.style.display = 'none';
    progressTotalDetails.style.display = 'none';
    progressBar.style.display = 'none';
    totalProgressBar.style.display = 'none';
    progressBar.value = 0;
    totalProgressBar.value = 0;
    enableUi();
  });

  // the receiving device already has a file we are about to send: ask what to do with it.
  // fork-only (the exchange behind it is version-guarded in core), and the question belongs
  // here, on the device whose user picked the files.
  await appWindow.listen('fileConflict', async (event) => {
    let { name, identical } = event.payload;
    let choice = await showConflict(name, identical);
    if (choice === 'rename') {
      let suggestion = suggestRename(name);
      let newName = await showPrompt(`Send \u201c${name}\u201d as:`, suggestion);
      if (newName === null || !newName.trim()) {
        choice = 'skip';
      } else {
        await core.invoke('user_file_conflict', { choice: 'rename', newName: newName.trim() });
        return;
      }
    }
    await core.invoke('user_file_conflict', { choice: choice, newName: null });
  });

  // show bluetooth PIN and allow user to choose whether to pair on windows
  await appWindow.listen('showPin', async (event) => {
    console.log(event);
    let choice = await dialog.ask(`Is this code displayed on the other device?\n\n${event.payload.message}`, { title: 'Confirm Bluetooth PIN', type: 'info' });
    console.log('choice:', choice);
    await core.invoke('user_bluetooth_pair', {
      choice: choice,
    });
    console.log('invoked user_bluetooth_pair');
  });

  // have Enter start/cancel transfer
  document.getElementById('mainContainer').addEventListener("keyup", event => {
    if (event.key !== "Enter") {
      return;
    }
    if (startButton.style.display != 'none' && !startButton.disabled) {
      startButton.click();
    }
    if (cancelButton.style.display != 'none') {
      cancelButton.click();
    }
    event.preventDefault();
  });

  // handle drag and drop
  await appWindow.onDragDropEvent(async event => {
    if (event.payload.type != 'drop') {
      return;
    }
    // ignore drops once a transfer is under way: startTransfer() would refuse anyway, but
    // the selection below would still have overwritten the running transfer's file list
    if (transferState !== 'idle') {
      return;
    }
    if (selectedMode === 'send') {
      selectedFiles = await core.invoke('expand_files', { paths: event.payload.paths });
      startTransfer(true);
    } else if (selectedMode === 'receive') {
      if (event.payload.length !== 1) {
        output('Error: if receiving, must drop only one destination folder.');
        return;
      }
      let is_dir = await core.invoke('is_dir', { path: event.payload[0] });
      if (is_dir) {
        selectedFolder = event.payload[0];
        rememberFolder(selectedFolder);
      } else {
        output('Error: if receiving, must select folder as destination.');
      }
      startTransfer(true);
    } else {
      output('Error: must select whether sending or receiving before dropping files or folder.');
    }
    checkStatus();
  });

  checkStatus();
  await resolveHomeDir();
  refreshLastFolderButton();
  // the default connection mode is set in markup and fires no change event, so the Bluetooth
  // switch has to be brought in line with it once on a fresh load
  applyBluetoothAvailability();

  // rehydrate UI if user refreshed
  let uiState = JSON.parse(sessionStorage.getItem('pageState'));
  if (uiState) {
    usingBluetooth = uiState.usingBluetooth;
    bluetoothSwitch.checked = usingBluetooth;
    sendingFolder = !!uiState.sendingFolder;
    selectedMode = uiState.selectedMode;
    if (selectedMode === 'send') {
      document.getElementById('sendButton').checked = true;
    } else if (selectedMode === 'receive') {
      document.getElementById('receiveButton').checked = true;
    }
    selectedPeer = uiState.selectedPeer;
    ['android', 'ios', 'linux', 'mac', 'windows'].forEach((os) => {
      let button = os + 'Button';
      if (selectedPeer === os) {
        document.getElementById(button).checked = true;
      }
    });
    // restore connection mode
    connectionMode = uiState.connectionMode || 'shared_network';
    if (connectionMode === 'shared_network') {
      document.getElementById('sharedNetworkButton').checked = true;
    } else {
      document.getElementById('hotspotButton').checked = true;
    }
    applyBluetoothAvailability();
    selectedFiles = uiState.selectedFiles;
    selectedFolder = uiState.selectedFolder;
    outputBox.innerText = uiState.output;
    progressBar.style.display = uiState.progressBarVisible ? '' : 'none';
    progressBar.value = uiState.progressBarValue;
    progressDetails.innerText = uiState.progressDetailsText || '';
    progressDetails.style.display = uiState.progressBarVisible ? '' : 'none';
    progressTotalDetails.innerText = uiState.progressTotalText || '';
    totalProgressBar.value = uiState.totalProgressValue || 0;
    let hadTotal = uiState.progressBarVisible && !!uiState.progressTotalText;
    progressTotalDetails.style.display = hadTotal ? '' : 'none';
    totalProgressBar.style.display = hadTotal ? '' : 'none';
    modeChange(selectedMode);
    if (uiState.transferRunning) {
      disableUi();
    }
    checkStatus();
  }
  refreshLastFolderButton();
});

// The directory picked last time, remembered across restarts so receiving is one tap.
const LAST_FOLDER_KEY = 'shiroikuma_last_receive_folder';

// Home directory, resolved once, so a path inside it can be shown as "~/tmp" rather than spelled
// out in full. Left empty if the path API is unavailable, in which case paths show verbatim.
let homeDir = '';
async function resolveHomeDir() {
  try {
    homeDir = (await window.__TAURI__.path.homeDir()).replace(/\/+$/, '');
  } catch (e) {
    homeDir = '';
  }
}

function prettyPath(p) {
  if (homeDir && (p === homeDir || p.startsWith(homeDir + '/'))) {
    return '~' + p.slice(homeDir.length);
  }
  return p;
}

function refreshLastFolderButton() {
  if (!lastFolderButton) {
    return;
  }
  let last = localStorage.getItem(LAST_FOLDER_KEY);
  let show = selectedMode === 'receive' && !!last && startButton.style.display !== 'none';
  lastFolderButton.style.display = show ? '' : 'none';
  if (last) {
    lastFolderButton.innerText = `Receive in \u201c${prettyPath(last)}\u201d`;
    lastFolderButton.title = last;
  }
}

// Same as picking that directory again: skip the file dialog and start listening.
window.useLastFolder = () => {
  let last = localStorage.getItem(LAST_FOLDER_KEY);
  if (!last) {
    return;
  }
  selectedFolder = last;
  startTransfer(true);
};

function rememberFolder(folder) {
  if (folder) {
    localStorage.setItem(LAST_FOLDER_KEY, folder);
    refreshLastFolderButton();
  }
}

function output(msg) {
  outputBox.innerText += '\n' + msg;
  outputBox.scrollTop = outputBox.scrollHeight;
}

// in-page replacement for window.prompt(), whose title shows the webview origin.
// resolves to the entered string, or null if cancelled.
let showPrompt = (message, prefill) => {
  return new Promise((resolve) => {
    let overlay = document.getElementById('promptOverlay');
    let input = document.getElementById('promptInput');
    let okButton = document.getElementById('promptOk');
    let cancelButton = document.getElementById('promptCancel');
    document.getElementById('promptMessage').innerText = message;
    input.value = prefill || '';
    let finish = (value) => {
      overlay.style.display = 'none';
      okButton.onclick = null;
      cancelButton.onclick = null;
      input.onkeydown = null;
      resolve(value);
    };
    okButton.onclick = () => finish(input.value);
    cancelButton.onclick = () => finish(null);
    input.onkeydown = (event) => {
      event.stopPropagation();
      if (event.key === 'Enter') {
        finish(input.value);
      } else if (event.key === 'Escape') {
        finish(null);
      }
    };
    overlay.style.display = 'flex';
    input.focus();
  });
}

// same modal as showPrompt, but with a single-choice dropdown prepopulated with
// `options` instead of a text input. resolves with the selected index, or null on cancel.
let showSelect = (message, options) => {
  return new Promise((resolve) => {
    let overlay = document.getElementById('promptOverlay');
    let input = document.getElementById('promptInput');
    let select = document.getElementById('promptSelect');
    let okButton = document.getElementById('promptOk');
    let cancelButton = document.getElementById('promptCancel');
    document.getElementById('promptMessage').innerText = message;
    input.style.display = 'none';
    select.style.display = '';
    select.innerHTML = '';
    for (let i = 0; i < options.length; i++) {
      let option = document.createElement('option');
      option.value = i;
      option.innerText = options[i];
      select.appendChild(option);
    }
    let finish = (value) => {
      overlay.style.display = 'none';
      input.style.display = '';
      select.style.display = 'none';
      okButton.onclick = null;
      cancelButton.onclick = null;
      select.onkeydown = null;
      resolve(value);
    };
    okButton.onclick = () => finish(parseInt(select.value));
    cancelButton.onclick = () => finish(null);
    select.onkeydown = (event) => {
      event.stopPropagation();
      if (event.key === 'Enter') {
        finish(parseInt(select.value));
      } else if (event.key === 'Escape') {
        finish(null);
      }
    };
    overlay.style.display = 'flex';
    select.focus();
  });
}

// "example.jpg (copy).jpg" reads worse than "example (copy).jpg": keep the extension last.
function suggestRename(name) {
  let dot = name.lastIndexOf('.');
  let slash = name.lastIndexOf('/');
  if (dot > slash + 1) {
    return name.slice(0, dot) + ' (copy)' + name.slice(dot);
  }
  return name + ' (copy)';
}

// Skip / Overwrite / Rename, wearing the fork's dialog dress. Resolves to one of those three
// strings; dismissing it counts as skipping, which is the only harmless default.
let showConflict = (name, identical) => {
  return new Promise((resolve) => {
    let overlay = document.createElement('div');
    overlay.className = 'fork-overlay';
    let box = document.createElement('div');
    box.className = 'fork-info-box';
    let title = document.createElement('div');
    title.className = 'fork-info-title';
    title.innerText = 'The other device already has this file';
    let body = document.createElement('div');
    body.className = 'fork-info-body';
    body.innerText = `\u201c${name}\u201d is already there`
      + (identical ? ', and it is identical to the one being sent.' : ', with different contents.');
    let actions = document.createElement('div');
    actions.className = 'fork-info-actions';
    let finish = (choice) => {
      overlay.remove();
      resolve(choice);
    };
    for (let [label, choice] of [['Skip', 'skip'], ['Rename', 'rename'], ['Overwrite', 'overwrite']]) {
      let button = document.createElement('button');
      button.type = 'button';
      button.className = 'fork-pill';
      button.innerText = label;
      button.onclick = () => finish(choice);
      actions.appendChild(button);
    }
    box.appendChild(title);
    box.appendChild(body);
    box.appendChild(actions);
    overlay.appendChild(box);
    overlay.addEventListener('click', (e) => { if (e.target === overlay) finish('skip'); });
    document.body.appendChild(overlay);
  });
}

function makeQRCode(str, caption) {
  let elem = document.getElementById('qrcode');
  elem.innerHTML = '';
  // fork: yellow quiet zone (4+ modules) so the code doesn't bleed into the black page
  // background; the QR's light modules are yellow too, so the whole block is black-on-yellow.
  // Bootstrap makes everything border-box, so 150 total = 118 QR + 2*16 quiet zone
  elem.style.background = '#ffff00';
  elem.style.padding = '16px';
  elem.style.width = '150px';
  // with a caption the block grows downward instead of squeezing the code
  elem.style.height = caption ? 'auto' : '150px';
  new QRCode(elem, {
    text: str,
    width: 118,
    height: 118,
    colorLight: '#ffff00',
  });
  if (caption) {
    // The password printed under its own QR code, so a sender can scan it or read it off without
    // a dialog to dismiss first. Black on the same yellow as the quiet zone, clear of the modules.
    const cap = document.createElement('div');
    cap.textContent = caption;
    cap.style.cssText = 'margin-top:6px; text-align:center; font-family:monospace; font-weight:bold;'
      + ' color:#000000; font-size:15px; letter-spacing:1px; line-height:1.2;';
    elem.appendChild(cap);
  }
}

// Only one transfer can be in flight at a time, and picking files/entering the password
// happens between the click and the transfer actually starting. This gate covers that whole
// window, so a second click (or Enter, or a drop onto the window) while a file dialog is open
// or a transfer is running does nothing.
async function startTransfer(filesSelected) {
  if (transferState !== 'idle') {
    return;
  }
  transferState = 'starting';
  try {
    await beginTransfer(filesSelected);
  } finally {
    // if we bailed out early (no interface, user dismissed a dialog) we never reached
    // disableUi(), so hand the UI back
    if (transferState === 'starting') {
      transferState = 'idle';
    }
  }
}

async function beginTransfer(filesSelected) {

  // the password is collected after files are chosen (below), so file selection isn't
  // gated on the other device having started and displayed its password yet.
  let password = null;

  // make sure we have a usable interface and prompt for which if more than one.
  // hotspot mode needs a wifi interface; shared network mode works over wired
  // (ethernet) interfaces too, so it uses the broader list. each interface is
  // {name, guid, ip}; label it with its IP (or lack of one) so the user can tell
  // connected interfaces apart, and pass [name, guid] to the backend (a WiFiInterface).
  let wifiInterface;
  let chosen;
  let interfaces = connectionMode === 'shared_network'
    ? await core.invoke('get_network_interfaces')
    : await core.invoke('get_wifi_interfaces');
  let interfaceLabel = (iface) => iface.ip ? `${iface.name} (${iface.ip})` : `${iface.name} (no network)`;
  // console.log('interfaces:', interfaces);
  switch (interfaces.length) {
    case 0:
      if (connectionMode === 'shared_network') {
        output('No connected network interfaces found. Connect to a network (WiFi or Ethernet) and try again.');
      } else {
        output('No WiFi interfaces found. Hotspot mode only works over WiFi.');
      }
      return;
    case 1:
      chosen = interfaces[0];
      output(`Using interface: ${interfaceLabel(chosen)}`);
      break;
    default: {
      let labels = interfaces.map(interfaceLabel);
      let choice = await showSelect('Select which network interface to use:', labels);
      if (choice === null) {
        output('Transfer cancelled.');
        return;
      }
      chosen = interfaces[choice];
      output(`Using interface: ${interfaceLabel(chosen)}`);
    }
  }
  wifiInterface = [chosen.name, chosen.guid];

  // if using shared network mode, check that we have a network connection
  if (connectionMode === 'shared_network') {
    let hasNetwork = await core.invoke('has_network_connection', { interface: wifiInterface });
    if (!hasNetwork) {
      output('No active network connection found. Shared Network mode requires both devices to be on the same local network. Please connect to a network or use Hotspot mode.');
      return;
    }
    output('Network connection detected. Using Shared Network mode.');
  }

  // get files or folder
  if (!filesSelected) {
    if (selectedMode == 'send') {
      if (sendingFolder) {
        let folder = await dialog.open({
          multiple: false,
          directory: true,
        });
        if (!folder) {
          output('User cancelled.');
          return;
        }
        selectedFiles = await core.invoke('expand_files', { paths: [folder] });
        if (!selectedFiles.length) {
          output('Error: the selected folder is empty.');
          return;
        }
      } else {
        await selectFiles();
        if (!selectedFiles) {
          output('User cancelled.');
          return;
        }
        if (!selectedFiles.length) {
          output('Error: no readable files were selected.');
          return;
        }
      }
    } else if (selectedMode == 'receive') {
      await selectFolder();
      if (!selectedFolder) {
        output('User cancelled.');
        return;
      }
    } else {
      output('Must select whether this device is sending or receiving.');
      return;
    }
  }
  
  // shared network sender: files are chosen, now get the password from the receiving device.
  // with Bluetooth on there is nothing to ask for -- the receiver writes the password over BLE.
  if (connectionMode === 'shared_network' && selectedMode === 'send' && !usingBluetooth) {
    let promptMessage = 'Enter the password displayed on the receiving device:';
    while (true) {
      password = await showPrompt(promptMessage);
      if (password === null) {
        output('Transfer cancelled.');
        return;
      }
      password = password.trim();
      if (password.length >= 10) {
        break;
      }
      promptMessage = 'Password must be at least 10 characters. Enter the password displayed on the receiving device:';
    }
  }

  // hotspot joiner: files are chosen, now get the password shown on the hosting device.
  // matches the shared-network prompt above so file selection is never gated on the
  // password (previously read from a box before the file dialog opened).
  if (await needPassword() && connectionMode !== 'shared_network') {
    let promptMessage = 'Enter the password displayed on the other device:';
    while (true) {
      password = await showPrompt(promptMessage);
      if (password === null) {
        output('Transfer cancelled.');
        return;
      }
      password = password.trim();
      if (password.length >= 8) {
        break;
      }
      promptMessage = 'Password must be at least 8 characters. Enter the password displayed on the other device:';
    }
  }

  // if we're generating the password (hosting in hotspot mode, or receiving in shared network mode),
  // and not using bluetooth (which exchanges the password automatically), generate and display it.
  if (!await needPassword() && !usingBluetooth) {
    password = await core.invoke('generate_password');
    if (connectionMode === 'shared_network') {
      // peer OS is unknown in shared network mode: show the password as text for desktop/Apple
      // senders and a QR code for Android senders.
      // the password rides under its own QR code, so there is nothing to dismiss before the
      // sender can scan or read it
      makeQRCode(password, password);
      output(`Password: ${password}`);
      output('Start the transfer on the sending device and scan the QR code, or enter the password shown beneath it.');
    } else if (selectedPeer === 'ios' || selectedPeer === 'android') {
      output('\nStart the transfer on the other device and scan the QR code when prompted.');
      makeQRCode(password);
    } else {
      output(`Password: ${password}`);
      // not awaited: the transfer below must start without waiting for the dialog to be dismissed
      dialog.message(`Start the transfer on the other device and enter this password when prompted:\n\n${password}`, { title: 'Flying Carpet' });
    }
  }

  // disable UI
  disableUi();

  // kick off transfer. the backend returns a message instead of null if it refused to start
  // (a transfer is still running or still cancelling), in which case the UI stays disabled:
  // the transfer that's already going will re-enable it when it ends.
  let refused = await core.invoke('start_async', {
    mode: selectedMode,
    peer: selectedPeer,
    password: password,
    interface: wifiInterface,
    fileList: selectedFiles,
    receiveDir: selectedFolder,
    usingBluetooth: usingBluetooth,
    connectionMode: connectionMode,
    window: appWindow,
  });
  if (refused) {
    output(refused);
  }
}

async function cancelTransfer() {
  if (transferState === 'cancelling') {
    return;
  }
  transferState = 'cancelling';
  // the transfer can take a while to come out of a blocking wifi or bluetooth call, so say so
  // and stop taking clicks rather than letting them pile up
  cancelButton.disabled = true;
  cancelButton.innerText = 'Cancelling...';
  try {
    output(await core.invoke('cancel_transfer'));
  } catch (e) {
    output(`Error cancelling transfer: ${e}`);
  } finally {
    cancelButton.disabled = false;
    cancelButton.innerText = 'Cancel Transfer';
    enableUi();
  }
}

// selectedFiles holds {path, name} pairs, where name is the relative path the receiving
// device stores the file under. expand_files resolves that against each selection's own
// parent folder, so a chosen folder is recreated on the other end while chosen files land
// flat. Plain file picks go through it too, so there is only one code path.
let selectFiles = async () => {
  let picked = await dialog.open({
    multiple: true,
    directory: false,
  });
  if (!picked) {
    selectedFiles = null;
    checkStatus();
    return;
  }
  selectedFiles = await core.invoke('expand_files', { paths: picked });
  checkStatus();
}

let selectFolder = async () => {
  selectedFolder = await dialog.open({
    multiple: false,
    directory: true,
  });
  rememberFolder(selectedFolder);
  checkStatus();
}

let bluetoothChange = () => {
  usingBluetooth = bluetoothSwitch.checked;
  // remembered across restarts, so the app never has to be told twice
  localStorage.setItem(BLUETOOTH_KEY, usingBluetooth ? '1' : '0');
  checkStatus();
}

// The switch state, remembered across restarts. Absent means "never touched": Bluetooth on when
// the machine supports it, which is upstream's default.
const BLUETOOTH_KEY = 'shiroikuma_use_bluetooth';
let rememberedBluetooth = () => localStorage.getItem(BLUETOOTH_KEY) !== '0';

// The line under the switch. With Bluetooth off it says who will display the QR code and password
// and who will scan or type it -- before the transfer starts, rather than when the QR code is
// already on screen (白い熊 2026-08-07). Hotspot mode stays deliberately vague about which device
// is which: there it follows from the peer's OS, not from send/receive.
let updateBluetoothHint = () => {
  let hint = document.getElementById('bluetoothHint');
  if (!hint) {
    return;
  }
  if (usingBluetooth) {
    hint.innerText = 'Bluetooth will carry the password: nothing to scan or type.';
  } else if (connectionMode !== 'shared_network') {
    hint.innerText = 'No Bluetooth: one device shows a QR code and password when the transfer starts, and the other scans or types it.';
  } else if (selectedMode === 'send') {
    hint.innerText = 'No Bluetooth: when the transfer starts, scan the QR code shown on the receiving device or type the password under it.';
  } else if (selectedMode === 'receive') {
    hint.innerText = 'No Bluetooth: when the transfer starts, a QR code and password appear here for the sending device to scan or type.';
  } else {
    hint.innerText = 'No Bluetooth: the receiving device shows a QR code and password when the transfer starts, and the sending device scans or types it.';
  }
}

let modeChange = async (button) => {
  // fork: the two labels are customizable
  startButton.innerText = window.forkUI
    ? window.forkUI.startLabel(button)
    : (button === 'receive' ? 'Select directory' : 'Select Files');
  document.getElementById('sendDirButton').style.display = button === 'send' ? '' : 'none';
  selectedMode = button;
  // after selectedMode is set, not before: refreshing first tested the mode we were leaving, which
  // is why the button turned up in Send mode
  refreshLastFolderButton();
  checkStatus();
}

let peerChange = (button) => {
  selectedPeer = button;
  checkStatus();
}

let connectionModeChange = (mode) => {
  connectionMode = mode;
  applyBluetoothAvailability();
  checkStatus();
}

// Bluetooth is usable in both connection modes (fork, 白い熊 2026-08-07). In hotspot mode it
// negotiates the hotspot's SSID and password; in shared network mode there is no hotspot, so it
// carries the transfer password alone -- the switch is the toggle between "use Bluetooth" and
// "scan the QR code or type the password". Which side generates that password does not change:
// the receiver does, and hands it over the BLE link instead of showing it on screen.
//
// The switch is the user's alone: nothing in the app turns it on or off behind their back, least
// of all a change of connection mode, and the choice outlives a restart. (白い熊 2026-08-07: a
// per-mode default that switched Bluetooth off on selecting Shared Network read, correctly, as
// "the app won't let Bluetooth on with shared WiFi".) Unavailable Bluetooth is the one exception,
// and it disables the switch rather than pretending it is off.
let applyBluetoothAvailability = () => {
  bluetoothSwitch.disabled = !canUseBluetooth;
  if (!canUseBluetooth) {
    bluetoothSwitch.checked = false;
  }
  usingBluetooth = bluetoothSwitch.checked;
  updateBluetoothHint();
}

let checkStatus = () => {
  // every path that can change the mode, the connection mode or the switch comes through here
  updateBluetoothHint();
  // the directory button is the start button's twin: same conditions, same moment
  let sendDirButton = document.getElementById('sendDirButton');
  if (connectionMode === 'shared_network' || usingBluetooth) {
    // Shared network: peer OS not needed (discovery handles it)
    // Bluetooth: peer OS not needed (exchanged over BLE)
    peerLabel.style.display = 'none';
    peerBox.style.display = 'none';
    startButton.disabled = !selectedMode;
  } else {
    peerLabel.style.display = '';
    peerBox.style.display = '';
    startButton.disabled = !(selectedMode && selectedPeer);
  }
  if (sendDirButton) {
    sendDirButton.disabled = startButton.disabled;
  }
}

let needPassword = async () => {
  // Shared network: receiver generates password, sender enters it (consistent with hotspot
  // same-platform convention) -- unless Bluetooth is on, which carries it across for us, so
  // neither side has anything to type or display.
  if (connectionMode === 'shared_network') {
    return !usingBluetooth && selectedMode === 'send';
  }
  if (usingBluetooth) {
    return false;
  }
  // if linux, joining windows, hosting mac/ios/android or linux if receiving.
  // if windows, always hosting unless windows and sending.
  let showPassword;
  console.log('os:', os.type());
  switch (await os.type()) {
    case 'linux':
      showPassword = selectedPeer === 'windows' || (selectedPeer === 'linux' && selectedMode === 'send');
      break;
    case 'windows':
      showPassword = selectedPeer === 'windows' && selectedMode === 'send';
      break;
    default:
      alert('Error in needPassword()');
  }
  return showPassword;
}

let enableUi = async () => {
  transferState = 'idle';
  // show start button, and the directory button with it if we are sending
  startButton.style.display = '';
  document.getElementById('sendDirButton').style.display = selectedMode === 'send' ? '' : 'none';
  // hide cancel button
  cancelButton.style.display = 'none';
  // enable bluetooth switch (stays disabled in shared network mode)
  applyBluetoothAvailability();
  // enable send folder box
  // enable radio buttons, file/folder selection buttons
  let radioButtons = ['sendButton', 'receiveButton', 'androidButton', 'iosButton', 'linuxButton', 'macButton', 'windowsButton', 'hotspotButton', 'sharedNetworkButton'];
  for (let i in radioButtons) {
    document.getElementById(radioButtons[i]).disabled = false;
  }
  // replace logo (fork: data-logo marks it tintable — QR codes are never tinted)
  let qrElem = document.getElementById('qrcode');
  qrElem.style.background = 'transparent';
  qrElem.style.padding = '0';
  qrElem.style.border = 'none';
  // last, once the start button is back: the check looks at whether that button is showing, so
  // refreshing earlier left the receive-in button hidden until a mode was tapped again
  refreshLastFolderButton();
  qrElem.style.width = '150px';
  qrElem.style.height = '150px';
  qrElem.innerHTML = '<img src="assets/icon1024.png" data-logo style="width: 150px; height: 150px;">'
  window.forkUI?.applyLogoTint();
}

let disableUi = async () => {
  transferState = 'running';
  if (lastFolderButton) {
    lastFolderButton.style.display = 'none';
  }
  // hide both send buttons
  startButton.style.display = 'none';
  document.getElementById('sendDirButton').style.display = 'none';
  // show cancel button
  cancelButton.style.display = '';
  // disable bluetooth switch
  document.getElementById('bluetoothSwitch').disabled = true;
  // disable send folder box
  // disable radio buttons, file/folder selection buttons
  let radioButtons = ['sendButton', 'receiveButton', 'androidButton', 'iosButton', 'linuxButton', 'macButton', 'windowsButton', 'hotspotButton', 'sharedNetworkButton'];
  for (let i in radioButtons) {
    document.getElementById(radioButtons[i]).disabled = true;
  }
}

// The two send buttons. Each says what it will send and starts the transfer in one press.
window.startFiles = () => {
  sendingFolder = false;
  startTransfer(false);
};
window.startDirectory = () => {
  sendingFolder = true;
  startTransfer(false);
};

window.startTransfer = startTransfer;
window.cancelTransfer = cancelTransfer;
window.selectFiles = selectFiles;
window.selectFolder = selectFolder;
window.bluetoothChange = bluetoothChange;
window.modeChange = modeChange;
window.peerChange = peerChange;
window.connectionModeChange = connectionModeChange;

const aboutMessage = `https://flyingcarpet.spiegl.dev
Version: 10.0.1
theron@spiegl.dev
Copyright (c) 2026, Theron Spiegl
All rights reserved.

Flying Carpet transfers files between two Android, iOS, Linux, macOS, and Windows devices over ad hoc WiFi. In Hotspot mode, no access point or shared network is required, just two WiFi cards in close range. Hotspot mode does not work from one Apple device (macOS or iOS) to another, because Apple no longer allows hotspots to be started programmatically: use Shared Network mode for those transfers.

In Shared Network mode, both devices must be connected to the same network. No hotspot is created: the devices find each other on the network automatically. The receiving device generates the password either way, and the "Use Bluetooth" switch decides how the sending device gets it: with the switch off the receiver displays the password and its QR code, to be typed or scanned on the sender; with the switch on it is handed over Bluetooth and there is nothing to type or scan.

INSTRUCTIONS

Turn Bluetooth on or off on both devices. If one side fails to initialize Bluetooth or has it turned off, the other side must disable the "Use Bluetooth" switch in Flying Carpet.

Select Sending on one device and Receiving on the other. If not using Bluetooth, select the operating system of the other device. Click the "Start Transfer" button on each device. On the sending device, select the files or folder to send. On the receiving device, select the folder in which to receive files. (To send a folder, check "Send Folder" before clicking "Start Transfer", or drag the folder onto the window. A folder you send is recreated inside the destination folder on the receiving device, with its contents inside.)

If using Bluetooth, confirm the 6-digit PIN on each side. The WiFi connection will be configured automatically. If not using Bluetooth, you will need to scan a QR code or type in a password.

If prompted to join a WiFi network or modify WiFi settings, say Allow. On Windows you may have to grant permission to add a firewall rule. On macOS you may have to grant location permissions, which Apple requires to scan for WiFi networks. Flying Carpet does not read or collect your location, nor any other data.

TROUBLESHOOTING

Disable any VPN on both devices.

If using Bluetooth fails, try manually unpairing the devices from one another and starting a new transfer.

If sending from macOS to Linux, you must first initiate pairing from the macOS System Settings > Bluetooth menu. Otherwise, disable Bluetooth on both sides and enter the password manually when prompted.

Flying Carpet may make multiple attempts to join the other device's hotspot.

Licensed under the GPL3: https://www.gnu.org/licenses/gpl-3.0.html#license-text`
