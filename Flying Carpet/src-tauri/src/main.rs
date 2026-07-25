#![cfg_attr(
    all(not(debug_assertions), target_os = "windows"),
    windows_subsystem = "windows"
)]

use flying_carpet_core::{
    bluetooth, clean_up_transfer, network, start_transfer, utils, Transfer, WiFiInterface, UI,
};
use std::path::PathBuf;
use std::str::FromStr;
use std::sync::Arc;
use std::{fs, sync::Mutex};
use tauri::{Emitter, State, Window};
use tokio::sync::mpsc;

#[derive(Clone, serde::Serialize)]
struct Payload {
    message: String,
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

#[tauri::command]
fn cancel_transfer(window: Window, state: State<Transfer>) -> String {
    let mut message = String::new();

    // cancel file transfer, which should close tcp socket?
    let cancel_handle = &mut state.cancel_handle.lock().unwrap();
    if let Some(handle) = cancel_handle.as_ref() {
        handle.abort();
        while !handle.is_finished() {
            println!("Waiting for transfer to cancel...");
            std::thread::sleep(std::time::Duration::from_millis(100));
        }
        **cancel_handle = None;
        message += "Transfer cancelled"
    } else {
        message += "No transfer to cancel"
    }

    // shut down hotspot
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

    window
        .emit("enableUi", Progress { value: 0 })
        .expect("Couldn't emit to window");
    message
}

#[tauri::command]
fn start_async(
    state: State<Transfer>,
    mode: String,
    peer: Option<String>,
    password: Option<String>,
    interface: WiFiInterface,
    file_list: Option<Vec<String>>,
    receive_dir: Option<String>,
    using_bluetooth: bool,
    window: Window,
) {
    let thread_window = window.clone();
    let gui = GUI {
        window: Arc::new(Mutex::new(thread_window)),
    };

    let transfer_hotspot = state.hotspot.clone();
    let transfer_ssid = state.ssid.clone();

    // used by windows because we have to implement our own UI for PIN confirmation in non-UWP apps.
    // sends the user's choice of whether the bluetooth PINs match to know whether to pair.
    let (ble_ui_tx, ble_ui_rx) = mpsc::channel(1);

    let cancel_handle = tokio::spawn(async move {
        let stream: std::option::Option<tokio::net::TcpStream> = start_transfer(
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
        )
        .await;
        clean_up_transfer(stream, transfer_hotspot, transfer_ssid, &gui).await;
    });
    let mut state_cancel_handle = state.cancel_handle.lock().unwrap();
    *state_cancel_handle = Some(cancel_handle);
    let mut state_ble_ui_tx = state.ble_ui_tx.lock().unwrap();
    *state_ble_ui_tx = Some(ble_ui_tx);
}

#[tokio::main]
async fn main() {
    tauri::async_runtime::set(tokio::runtime::Handle::current());
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_os::init())
        .manage(Transfer::new())
        .invoke_handler(tauri::generate_handler![
            start_async,
            cancel_transfer,
            is_dir,
            expand_files,
            generate_password,
            get_wifi_interfaces,
            check_support,
            user_bluetooth_pair,
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

#[tauri::command]
fn expand_files(paths: Vec<&str>) -> Vec<String> {
    let path_bufs: Vec<PathBuf> = paths
        .iter()
        .filter_map(|p| PathBuf::from_str(p).ok())
        .collect();
    let mut files: Vec<String> = vec![];
    let mut dirs_to_search: Vec<PathBuf> = vec![];
    for path in path_bufs {
        if let Some(metadata) = fs::metadata(&path).ok() {
            if metadata.is_dir() {
                dirs_to_search.push(path.clone());
            }
            if metadata.is_file() {
                files.push(path.to_string_lossy().to_string());
            }
        }
    }
    while dirs_to_search.len() > 0 {
        let (mut temp_files, mut temp_dirs) = utils::expand_dir(
            dirs_to_search
                .pop()
                .expect("Had dirs to search but couldn't pop."),
        );
        files.append(&mut temp_files);
        dirs_to_search.append(&mut temp_dirs);
    }
    files
}

#[tauri::command]
fn generate_password() -> String {
    utils::generate_password()
}

#[tauri::command]
fn get_wifi_interfaces() -> Vec<WiFiInterface> {
    match network::get_wifi_interfaces() {
        Ok(interfaces) => interfaces,
        Err(_e) => vec![], // if there was an error, just return empty list of interfaces and let javascript detect "no wifi card found"
    }
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
    println!("in user_bluetooth_pair");
    let ble_ui_tx = state
        .ble_ui_tx
        .lock()
        .expect("Could not lock ble_ui_tx mutex");
    let ble_ui_tx = ble_ui_tx.as_ref().expect("State ble_ui_tx was None");
    let ble_ui_tx = ble_ui_tx.clone();

    tokio::spawn(async move {
        ble_ui_tx
            .send(choice)
            .await
            .expect("Could not send on ble_ui_tx");
        println!("sent in user_bluetooth_pair");
    });
}
