#![cfg_attr(
    all(not(debug_assertions), target_os = "windows"),
    windows_subsystem = "windows"
)]

use flying_carpet_core::{
    bluetooth, clean_up_transfer, network, start_transfer, utils, ConnectionMode,
    FileConflictAnswer, FileConflictChoice, InterfaceInfo, SendFile, Transfer, WiFiInterface, UI,
};
use std::path::PathBuf;
use std::str::FromStr;
use std::sync::Arc;
use std::{fs, sync::Mutex};
use tauri::{Emitter, State, Window};
use tokio::sync::mpsc;

#[derive(Clone, serde::Serialize)]
struct FileConflictPayload {
    name: String,
    local_size: u64,
    incoming_size: u64,
    identical: bool,
    // false on the last file: nothing left for "apply to all" to apply to, so it isn't offered
    more_files: bool,
}

#[derive(Clone, serde::Serialize)]
struct Payload {
    message: String,
}

#[derive(Clone, serde::Serialize)]
struct Details {
    current: String,
    total: String,
}

#[derive(Clone, serde::Serialize)]
struct Progress {
    value: u8,
}

#[derive(Clone)]
struct GUI {
    window: Arc<Mutex<Window>>,
}

impl UI for GUI {
    // The sending side has hit a file the other device already has. Ask, and let
    // user_file_conflict() below carry the answer back to the transfer.
    fn ask_file_conflict(
        &self,
        name: &str,
        local_size: u64,
        incoming_size: u64,
        identical: bool,
        more_files: bool,
    ) {
        let window = self.window.lock().expect("Could not lock window mutex");
        window
            .emit(
                "fileConflict",
                FileConflictPayload {
                    name: name.to_string(),
                    local_size,
                    incoming_size,
                    identical,
                    more_files,
                },
            )
            .expect("Could not emit fileConflict event");
    }

    fn output(&self, msg: &str) {
        self.window
            .lock()
            .expect("Couldn't lock GUI mutex")
            .emit(
                "outputMsg",
                Payload {
                    message: msg.to_string(),
                },
            )
            .expect("could not emit event");
    }
    fn show_progress_bar(&self) {
        self.window
            .lock()
            .expect("Couldn't lock GUI mutex")
            .emit("showProgressBar", Progress { value: 0 })
            .expect("could not emit event");
    }
    fn update_progress_bar(&self, percent: u8) {
        self.window
            .lock()
            .expect("Couldn't lock GUI mutex")
            .emit("updateProgressBar", Progress { value: percent })
            .expect("could not emit event");
    }
    fn update_total_progress_bar(&self, percent: u8) {
        self.window
            .lock()
            .expect("Couldn't lock GUI mutex")
            .emit("updateTotalProgressBar", Progress { value: percent })
            .expect("could not emit event");
    }
    fn update_progress_details(&self, current: &str, total: &str) {
        self.window
            .lock()
            .expect("Couldn't lock GUI mutex")
            .emit(
                "updateProgressDetails",
                Details {
                    current: current.to_string(),
                    total: total.to_string(),
                },
            )
            .expect("could not emit event");
    }
    fn enable_ui(&self) {
        self.window
            .lock()
            .expect("Couldn't lock GUI mutex")
            .emit("enableUi", Progress { value: 0 })
            .expect("could not emit event");
    }
    fn show_pin(&self, pin: &str) {
        println!("showing pin");
        self.window
            .lock()
            .expect("Couldn't lock GUI mutex")
            .emit(
                "showPin",
                Payload {
                    message: pin.to_string(),
                },
            )
            .expect("could not emit event");
    }
}

// Re-enables the frontend when dropped, which happens whether the transfer task
// completes, errors, panics, or is aborted by cancellation. Without this, a panic in
// the transfer task would leave the UI locked in its in-progress state forever.
struct EnableUiGuard {
    gui: GUI,
}

impl Drop for EnableUiGuard {
    fn drop(&mut self) {
        // Don't panic inside drop (a double panic aborts the process): tolerate a
        // poisoned mutex and ignore emit errors.
        let window = match self.gui.window.lock() {
            Ok(window) => window,
            Err(poisoned) => poisoned.into_inner(),
        };
        let _ = window.emit("enableUi", Progress { value: 0 });
    }
}

// Async on purpose: a sync command body runs inline on the main thread, so the old version
// blocked the GTK event loop while it waited for the transfer to wind down. The window froze,
// X11 queued the user's clicks, and they landed after the UI had been re-enabled — on the
// Start button, which sits exactly where Cancel was. As an async command this runs on the
// async runtime instead, so the window keeps repainting and every click is handled live.
#[tauri::command]
async fn cancel_transfer(window: Window, state: State<'_, Transfer>) -> Result<String, String> {
    // Take the task out and claim the cancellation under the lock, so a second click gets a
    // fast "already cancelling" instead of stacking up behind this one.
    let handle = {
        let mut task = state.task.lock().unwrap_or_else(|e| e.into_inner());
        if task.cancelling {
            return Ok("Already cancelling transfer.".to_string());
        }
        match task.handle.take() {
            Some(handle) => {
                task.cancelling = true;
                Some(handle)
            }
            None => None,
        }
    };

    let message = match handle {
        Some(handle) => {
            handle.abort();
            // an abort only lands at the task's next await, so this can take a while if the
            // transfer is inside a blocking wifi or bluetooth call. awaiting it (rather than
            // polling is_finished in a sleep loop) keeps this off the main thread the whole
            // time.
            println!("Waiting for transfer to cancel...");
            let _ = handle.await;
            "Transfer cancelled"
        }
        None => "No transfer to cancel",
    };

    // shut down hotspot. blocking, but we're on the async runtime, not the main thread.
    {
        let hotspot = state
            .hotspot
            .lock()
            .expect("Couldn't lock state hotspot mutex.");
        let hotspot = &*hotspot;
        let ssid = state.ssid.lock().expect("Couldn't lock state ssid mutex.");
        let ssid = &*ssid;
        match network::stop_hotspot(hotspot.as_ref(), ssid.as_deref()) {
            Err(e) => println!("Error stopping hotspot: {}", e),
            Ok(msg) => println!("{}", msg),
        };
    }

    // release the cancellation before re-enabling the UI, so the start the user is now free
    // to click isn't refused by start_async.
    state
        .task
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .cancelling = false;

    // don't panic on a failed emit: this is the cleanup path, and the frontend re-enables
    // itself when the invoke resolves anyway.
    let _ = window.emit("enableUi", Progress { value: 0 });
    Ok(message.to_string())
}

// Returns None if the transfer was started, or a message explaining why it wasn't. The
// frontend guards against starting twice as well, but this is the only place that knows
// whether the previous task is really gone, so it gets the last word.
#[tauri::command]
fn start_async(
    state: State<Transfer>,
    mode: String,
    peer: Option<String>,
    password: Option<String>,
    interface: WiFiInterface,
    file_list: Option<Vec<SendFile>>,
    receive_dir: Option<String>,
    using_bluetooth: bool,
    connection_mode: Option<String>,
    window: Window,
) -> Option<String> {
    let thread_window = window.clone();
    let gui = GUI {
        window: Arc::new(Mutex::new(thread_window)),
    };

    let transfer_hotspot = state.hotspot.clone();
    let transfer_ssid = state.ssid.clone();

    // used by windows because we have to implement our own UI for PIN confirmation in non-UWP apps.
    // sends the user's choice of whether the bluetooth PINs match to know whether to pair.
    let (ble_ui_tx, ble_ui_rx) = mpsc::channel(1);
    // Answers to "the other device already has this file", one question at a time.
    let (conflict_tx, conflict_rx) = mpsc::channel(1);

    // Parse connection mode
    let conn_mode = match connection_mode.as_deref() {
        Some("shared_network") => ConnectionMode::SharedNetwork,
        _ => ConnectionMode::Hotspot,
    };

    // hold the lock across the check and the spawn so two starts can't both pass the check
    let mut task = state.task.lock().unwrap_or_else(|e| e.into_inner());
    if task.cancelling {
        return Some("Still cancelling the previous transfer. Try again in a moment.".to_string());
    }
    if task.is_running() {
        return Some("A transfer is already in progress.".to_string());
    }

    let handle = tokio::spawn(async move {
        let _enable_ui_guard = EnableUiGuard { gui: gui.clone() };
        let stream: Option<flying_carpet_core::TransferStream> = start_transfer(
            mode,
            using_bluetooth,
            peer,
            password,
            interface,
            file_list,
            receive_dir,
            &gui,
            transfer_hotspot.clone(),
            transfer_ssid.clone(),
            ble_ui_rx,
            conn_mode,
            conflict_rx,
        )
        .await;
        clean_up_transfer(stream, transfer_hotspot, transfer_ssid, &gui).await;
    });
    task.handle = Some(handle);
    // only after the start is committed: overwriting this on a refused start would cut the
    // running transfer off from the pairing dialog's answer
    let mut state_ble_ui_tx = state.ble_ui_tx.lock().unwrap();
    *state_ble_ui_tx = Some(ble_ui_tx);
    let mut state_conflict_tx = state.conflict_tx.lock().unwrap();
    *state_conflict_tx = Some(conflict_tx);
    None
}

/// The frontend's answer to a file the receiving device already has: "skip", "overwrite" or
/// "rename" with the new name, and whether it stands for every remaining file. Mirrors
/// user_bluetooth_pair.
#[tauri::command]
fn user_file_conflict(
    choice: String,
    new_name: Option<String>,
    apply_to_all: bool,
    state: State<Transfer>,
) {
    let answer = FileConflictAnswer {
        choice: match choice.as_str() {
            "overwrite" => FileConflictChoice::Overwrite,
            "rename" => FileConflictChoice::Rename(new_name.unwrap_or_default()),
            _ => FileConflictChoice::Skip,
        },
        apply_to_all,
    };
    println!("file conflict: user chose {:?}", answer);
    let conflict_tx = state.conflict_tx.lock().expect("Could not lock conflict_tx");
    let conflict_tx = match conflict_tx.as_ref() {
        Some(tx) => tx.clone(),
        None => return,
    };
    tokio::spawn(async move {
        // The receiver lives in the transfer task; if the transfer ended while the dialog was up,
        // answering it is a no-op rather than a panic.
        let _ = conflict_tx.send(answer).await;
    });
}

#[tokio::main]
async fn main() {
    // remove hotspot connections left in NetworkManager by previous runs that were
    // killed before cleanup could run (#51)
    #[cfg(target_os = "linux")]
    match network::cleanup_stale_connections() {
        Ok(deleted) => {
            for name in deleted {
                println!("Removed stale NetworkManager connection: {}", name);
            }
        }
        Err(e) => println!("Could not clean up stale NetworkManager connections: {}", e),
    }

    tauri::async_runtime::set(tokio::runtime::Handle::current());
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_os::init())
        .manage(Transfer::new())
        .setup(|_app| {
            // Tauri's default window icon is the first PNG listed in bundle.icon, i.e. the
            // 32x32 one, and that's all it publishes as _NET_WM_ICON. Window managers scale
            // that up for the alt-tab switcher (96px on Cinnamon) and it looks it. Publish
            // the 128x128 instead. This is what the panel and switcher fall back to when the
            // window can't be matched to an installed .desktop file, which is the case when
            // running from source or from the AppImage.
            #[cfg(target_os = "linux")]
            {
                use tauri::Manager;
                let icon = tauri::image::Image::from_bytes(include_bytes!("../icons/128x128.png"))?;
                if let Some(window) = _app.get_webview_window("main") {
                    window.set_icon(icon)?;
                }
            }
            Ok(())
        })
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { .. } = event {
                // best-effort cleanup when the app is closed mid-transfer (#51):
                // abort the transfer task and tear down the hotspot so its
                // NetworkManager connection doesn't linger
                use tauri::Manager;
                let state: State<Transfer> = window.state();
                if let Ok(mut task) = state.task.lock() {
                    if let Some(handle) = task.handle.take() {
                        handle.abort();
                    }
                }
                let (hotspot, ssid) = (state.hotspot.clone(), state.ssid.clone());
                if let (Ok(hotspot), Ok(ssid)) = (hotspot.lock(), ssid.lock()) {
                    match network::stop_hotspot(hotspot.as_ref(), ssid.as_deref()) {
                        Err(e) => println!("Error stopping hotspot: {}", e),
                        Ok(msg) => println!("{}", msg),
                    }
                };
            }
        })
        .invoke_handler(tauri::generate_handler![
            start_async,
            cancel_transfer,
            is_dir,
            expand_files,
            generate_password,
            get_wifi_interfaces,
            get_network_interfaces,
            check_support,
            user_bluetooth_pair,
            user_file_conflict,
            has_network_connection,
            fork_read_file,
            fork_write_file,
            fork_list_dir,
            fork_restart,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

// for javascript, None/null means no error and Some(String) means error message
#[tauri::command]
async fn check_support() -> Option<String> {
    bluetooth::check_support()
        .await
        .map_err(|e| e.to_string())
        .err()
}

#[tauri::command]
fn is_dir(path: &str) -> bool {
    match fs::metadata(path) {
        Ok(m) => m.is_dir(),
        Err(_) => false,
    }
}

// Expand whatever the user picked or dropped into the files to send. Each file comes back
// paired with the relative name the peer will store it under, so a selected folder is
// recreated on the receiving end instead of having its contents dumped loose into the
// destination. All the path logic lives in the core (and is unit-tested there).
#[tauri::command]
fn expand_files(paths: Vec<&str>) -> Vec<SendFile> {
    let roots: Vec<PathBuf> = paths
        .iter()
        .filter_map(|p| PathBuf::from_str(p).ok())
        .collect();
    utils::expand_selection(roots)
}

#[tauri::command]
fn generate_password() -> String {
    utils::generate_password()
}

#[tauri::command]
fn get_wifi_interfaces() -> Vec<InterfaceInfo> {
    match network::get_wifi_interfaces() {
        Ok(interfaces) => interfaces,
        Err(_e) => vec![], // if there was an error, just return empty list of interfaces and let javascript detect "no wifi card found"
    }
}

// shared network mode works over wired connections too, so it uses this broader list
// (WiFi + Ethernet interfaces with an IPv4 address) instead of get_wifi_interfaces
#[tauri::command]
fn get_network_interfaces() -> Vec<InterfaceInfo> {
    match network::get_connected_interfaces() {
        Ok(interfaces) => interfaces,
        Err(_e) => vec![], // if there was an error, just return empty list of interfaces and let javascript report it
    }
}

#[tauri::command]
fn has_network_connection(interface: WiFiInterface) -> bool {
    network::has_network_connection(&interface).unwrap_or(false)
}

// ── Fork: Export / Import file access ─────────────────────────────────────────────────────────
//
// The desktop app's settings live in the webview's localStorage, which Rust cannot read — so the
// backup engine itself (backup.js) stays in the frontend, and Rust supplies only the four things a
// webview cannot do: read a file, write a file, list a folder, and restart the app.
//
// Payloads cross the IPC bridge base64-encoded. Handing a `Vec<u8>` to Tauri would serialize it as
// a JSON array of numbers — five-ish bytes on the wire per byte of ZIP — while base64 costs 1.33×
// and rides in a plain JSON string. The encoder/decoder below is 30 lines, so no new crate is
// pulled in for it.

const B64_ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

fn b64_encode(data: &[u8]) -> String {
    let mut out = String::with_capacity((data.len() + 2) / 3 * 4);
    for chunk in data.chunks(3) {
        let b = [chunk[0], *chunk.get(1).unwrap_or(&0), *chunk.get(2).unwrap_or(&0)];
        let n = ((b[0] as u32) << 16) | ((b[1] as u32) << 8) | b[2] as u32;
        out.push(B64_ALPHABET[(n >> 18) as usize & 63] as char);
        out.push(B64_ALPHABET[(n >> 12) as usize & 63] as char);
        out.push(if chunk.len() > 1 { B64_ALPHABET[(n >> 6) as usize & 63] as char } else { '=' });
        out.push(if chunk.len() > 2 { B64_ALPHABET[n as usize & 63] as char } else { '=' });
    }
    out
}

fn b64_decode(text: &str) -> Result<Vec<u8>, String> {
    let mut out = Vec::with_capacity(text.len() / 4 * 3);
    let mut acc: u32 = 0;
    let mut bits = 0u32;
    for c in text.bytes() {
        if c == b'=' || c.is_ascii_whitespace() {
            continue;
        }
        let v = match c {
            b'A'..=b'Z' => c - b'A',
            b'a'..=b'z' => c - b'a' + 26,
            b'0'..=b'9' => c - b'0' + 52,
            b'+' => 62,
            b'/' => 63,
            _ => return Err(format!("invalid base64 character: {}", c as char)),
        };
        acc = (acc << 6) | v as u32;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((acc >> bits) as u8);
        }
    }
    Ok(out)
}

#[derive(serde::Serialize)]
struct ForkDirEntry {
    name: String,
    size: u64,
    // Milliseconds since the epoch, so JavaScript can build a Date from it directly.
    modified: u64,
}

#[tauri::command]
fn fork_read_file(path: String) -> Result<String, String> {
    fs::read(&path).map(|b| b64_encode(&b)).map_err(|e| e.to_string())
}

/// Writes `base64` to `path`, creating the parent directory if it isn't there yet.
#[tauri::command]
fn fork_write_file(path: String, base64: String) -> Result<u64, String> {
    let bytes = b64_decode(&base64)?;
    let target = PathBuf::from(&path);
    if let Some(parent) = target.parent() {
        fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    fs::write(&target, &bytes).map_err(|e| e.to_string())?;
    Ok(bytes.len() as u64)
}

/// Files (not sub-directories) in `path`, so the frontend can find the newest backup in the folder.
#[tauri::command]
fn fork_list_dir(path: String) -> Result<Vec<ForkDirEntry>, String> {
    let mut entries = vec![];
    for entry in fs::read_dir(&path).map_err(|e| e.to_string())? {
        let entry = match entry {
            Ok(e) => e,
            Err(_) => continue,
        };
        let metadata = match entry.metadata() {
            Ok(m) if m.is_file() => m,
            _ => continue,
        };
        let modified = metadata
            .modified()
            .ok()
            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0);
        entries.push(ForkDirEntry {
            name: entry.file_name().to_string_lossy().to_string(),
            size: metadata.len(),
            modified,
        });
    }
    Ok(entries)
}

/// "Restart now" on the import-finished dialog — the desktop twin of the Android app's restart.
#[tauri::command]
fn fork_restart(app: tauri::AppHandle) {
    app.restart();
}

#[tauri::command]
fn user_bluetooth_pair(choice: bool, state: State<Transfer>) {
    // The answer itself, not just that one arrived: "pairing failed" looks identical whether the
    // peer refused or the dialog here was dismissed, and this is the only place that knows which.
    println!("in user_bluetooth_pair: user said {}", choice);
    let ble_ui_tx = state
        .ble_ui_tx
        .lock()
        .expect("Could not lock ble_ui_tx mutex");
    let ble_ui_tx = ble_ui_tx.as_ref().expect("State ble_ui_tx was None");
    let ble_ui_tx = ble_ui_tx.clone();

    tokio::spawn(async move {
        // the receiver lives in the transfer task, so it's gone if the transfer ended or was
        // cancelled while the PIN dialog was still up. answering it then is a no-op, not a
        // panic.
        match ble_ui_tx.send(choice).await {
            Ok(()) => println!("sent in user_bluetooth_pair"),
            Err(_) => println!("no transfer waiting for a pairing choice, ignoring"),
        }
    });
}
