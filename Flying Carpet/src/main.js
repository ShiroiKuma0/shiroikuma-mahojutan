const { core, dialog, os } = window.__TAURI__;
import { QRCode } from './deps/qrcode.js'

let aboutButton;
let canUseBluetooth = false;
let usingBluetooth;
let bluetoothSwitch;
let sendFolderCheckbox;
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

let selectedMode;
let selectedPeer;
let selectedFiles;
let selectedFolder;

// save UI if user refreshes
window.onunload = () => {
  let uiState = {
    usingBluetooth: usingBluetooth,
    // canUseBluetooth:
    sendingFolder: sendFolderCheckbox.checked,
    selectedMode: selectedMode,
    selectedPeer: selectedPeer,
    selectedFiles: selectedFiles,
    selectedFolder: selectedFolder,
    output: outputBox.innerText,
    transferRunning: startButton.style.display === 'none',
    passwordBoxValue: passwordBox.value,
    progressBarValue: progressBar.value,
    progressBarVisible: progressBar.style.display !== 'none',
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
  sendFolderCheckbox = document.getElementById('sendFolderCheckbox');

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
    bluetoothSwitch.checked = true;
    usingBluetooth = true;
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

  // enable UI when transfer finishes
  await appWindow.listen('enableUi', (_event) => {
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

  // rehydrate UI if user refreshed
  let uiState = JSON.parse(sessionStorage.getItem('pageState'));
  if (uiState) {
    usingBluetooth = uiState.usingBluetooth;
    bluetoothSwitch.checked = usingBluetooth;
    sendFolderCheckbox.checked = uiState.sendingFolder;
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
    passwordBox.value = uiState.passwordBoxValue;
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

function makeQRCode(str) {
  let elem = document.getElementById('qrcode');
  elem.innerHTML = '';
  // fork: yellow quiet zone (4+ modules) so the code doesn't bleed into the black page
  // background; the QR's light modules are yellow too, so the whole block is black-on-yellow.
  // Bootstrap makes everything border-box, so 150 total = 118 QR + 2*16 quiet zone
  elem.style.background = '#ffff00';
  elem.style.padding = '16px';
  elem.style.width = '150px';
  elem.style.height = '150px';
  new QRCode(elem, {
    text: str,
    width: 118,
    height: 118,
    colorLight: '#ffff00',
  });
}

async function startTransfer(filesSelected) {

  // if we need password, make sure we have it before prompting for files/folder
  let password = null;
  if (await needPassword()) {
    password = document.getElementById('passwordBox').value;
    if (password.length < 8) {
      output('Must enter password from the other device.');
      return;
    }
  }

  // make sure we have a wifi interface and prompt for which if more than one
  let wifiInterface;
  let interfaces = await core.invoke('get_wifi_interfaces');
  // console.log('interfaces:', interfaces);
  switch (interfaces.length) {
    case 0:
      output('No WiFi interfaces found. Flying Carpet only works over WiFi.');
      return;
    case 1:
      wifiInterface = interfaces[0];
      break;
    default:
      let alertString = 'Enter the number for which WiFi interface to use (e.g. "1" or "2"):\n'
      for (let i = 0; i < interfaces.length; i++) {
        alertString += `${i+1}: ${interfaces[i][0]}\n`
      }
      let choice = parseInt(prompt(alertString));
      if (choice && choice > 0 && choice <= interfaces.length) {
        wifiInterface = interfaces[choice - 1];
        output(`Using interface: ${wifiInterface[0]}`);
      } else {
        output('Invalid interface selected. Please enter just the number of the WiFi interface you would like to use, e.g. "1" or "3".');
        return;
      }
  }
  
  // get files or folder
  if (!filesSelected) {
    if (selectedMode == 'send') {
      if (sendFolderCheckbox.checked) {
        let folder = await dialog.open({
          multiple: false,
          directory: true,
        });
        if (!folder) {
          output('User cancelled.');
          return;
        }
        selectedFiles = await core.invoke('expand_files', { paths: [folder] });
      } else {
        await selectFiles();
        if (!selectedFiles) {
          output('User cancelled.');
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
  
  // if we're hosting, generate and display the password
  if (!await needPassword()) {
    if (!usingBluetooth) {
      password = await core.invoke('generate_password');
      if (selectedPeer === 'ios' || selectedPeer === 'android') {
        output('\nStart the transfer on the other device and scan the QR code when prompted.');
        makeQRCode(password);
      } else {
        output(`Password: ${password}`);
        alert(`\nStart the transfer on the other device and enter this password when prompted:\n${password}`);
      }
    }
  }

  // disable UI
  disableUi();

  // kick off transfer
  await core.invoke('start_async', {
    mode: selectedMode,
    peer: selectedPeer,
    password: password,
    interface: wifiInterface,
    fileList: selectedFiles,
    receiveDir: selectedFolder,
    usingBluetooth: usingBluetooth,
    window: appWindow,
  });
}

async function cancelTransfer() {
  output(await core.invoke('cancel_transfer'));
}

let selectFiles = async () => {
  selectedFiles = await dialog.open({
    multiple: true,
    directory: false,
  });
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
  checkStatus();
}

let modeChange = async (button) => {
  // fork: the two labels are customizable
  startButton.innerText = window.forkUI
    ? window.forkUI.startLabel(button)
    : (button === 'receive' ? 'Select directory' : 'Select Files');
  document.getElementById('sendFolderDiv').style.display = button === 'send' ? '' : 'none';
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

let checkStatus = () => {
  showPassword();
  if (usingBluetooth) {
    peerLabel.style.display = 'none';
    peerBox.style.display = 'none';
    startButton.disabled = !selectedMode;
  } else {
    peerLabel.style.display = '';
    peerBox.style.display = '';
    startButton.disabled = !(selectedMode && selectedPeer);
  }
}

let needPassword = async () => {
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

let showPassword = async () => {
  let showPassword = await needPassword();
  if (showPassword) {
    document.getElementById('passwordBox').style.display = '';
  } else {
    document.getElementById('passwordBox').style.display = 'none';
  }
}

let enableUi = async () => {
  // show start button
  startButton.style.display = '';
  // hide cancel button
  cancelButton.style.display = 'none';
  // enable bluetooth switch
  if (canUseBluetooth) {
    document.getElementById('bluetoothSwitch').disabled = false;
  }
  // enable send folder box
  document.getElementById('sendFolderCheckbox').disabled = false;
  // enable radio buttons, file/folder selection buttons
  let radioButtons = ['sendButton', 'receiveButton', 'androidButton', 'iosButton', 'linuxButton', 'macButton', 'windowsButton'];
  for (let i in radioButtons) {
    document.getElementById(radioButtons[i]).disabled = false;
  }
  // enable password box
  document.getElementById('passwordBox').disabled = false;
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
  if (lastFolderButton) {
    lastFolderButton.style.display = 'none';
  }
  // hide start button
  startButton.style.display = 'none';
  // show cancel button
  cancelButton.style.display = '';
  // disable bluetooth switch
  document.getElementById('bluetoothSwitch').disabled = true;
  // disable send folder box
  document.getElementById('sendFolderCheckbox').disabled = true;
  // disable radio buttons, file/folder selection buttons
  let radioButtons = ['sendButton', 'receiveButton', 'androidButton', 'iosButton', 'linuxButton', 'macButton', 'windowsButton'];
  for (let i in radioButtons) {
    document.getElementById(radioButtons[i]).disabled = true;
  }
  // disable password box
  document.getElementById('passwordBox').disabled = true;
}

window.startTransfer = startTransfer;
window.cancelTransfer = cancelTransfer;
window.selectFiles = selectFiles;
window.selectFolder = selectFolder;
window.bluetoothChange = bluetoothChange;
window.modeChange = modeChange;
window.peerChange = peerChange;

const aboutMessage = `https://flyingcarpet.spiegl.dev
Version: 9.0.10
theron@spiegl.dev
Copyright (c) 2025, Theron Spiegl
All rights reserved.

Flying Carpet transfers files between two Android, iOS, Linux, macOS, and Windows devices over ad hoc WiFi. No access point or shared network is required, just two WiFi cards in close range. The only non-working pairings are from one Apple device (macOS or iOS) to another, because Apple no longer allows hotspots to be started programmatically.

INSTRUCTIONS

Turn Bluetooth on or off on both devices. If one side fails to initialize Bluetooth or has it turned off, the other side must disable the "Use Bluetooth" switch in Flying Carpet.

Select Sending on one device and Receiving on the other. If not using Bluetooth, select the operating system of the other device. Click the "Start Transfer" button on each device. On the sending device, select the files or folder to send. On the receiving device, select the folder in which to receive files. (To send a folder, drag it onto the window instead of clicking "Start Transfer".)

If using Bluetooth, confirm the 6-digit PIN on each side. The WiFi connection will be configured automatically. If not using Bluetooth, you will need to scan a QR code or type in a password.

If prompted to join a WiFi network or modify WiFi settings, say Allow. On Windows you may have to grant permission to add a firewall rule. On macOS you may have to grant location permissions, which Apple requires to scan for WiFi networks. Flying Carpet does not read or collect your location, nor any other data.

TROUBLESHOOTING

If using Bluetooth fails, try manually unpairing the devices from one another and starting a new transfer.

If sending from macOS to Linux, you must first initiate pairing from the macOS System Settings > Bluetooth menu. Otherwise, disable Bluetooth on both sides and enter the password manually when prompted.

Flying Carpet may make multiple attempts to join the other device's hotspot.

Licensed under the GPL3: https://www.gnu.org/licenses/gpl-3.0.html#license-text`
