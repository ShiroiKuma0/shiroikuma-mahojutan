// Fork: the desktop half of paired devices.
//
// Kept in its own module rather than in main.rs, because main.rs is upstream's file and
// every line added there is a line an upstream rebase can collide with. Only the command
// names in `generate_handler!` and one line in `setup` reach across.
//
// The desktop is the easy end of this feature: nothing freezes it, nothing puts it to
// sleep, and it has no battery. So it simply serves whenever it is running, and the whole
// "stay reachable" question that shapes the Android side does not arise here.

use flying_carpet_core::{
    network,
    noise::{derive_paired_psk, derive_presence_key},
    paired::{self, PairedOffer, Refusal, Verdict},
    pairing::{self, PairedPeer, PairingStore},
    presence::{self, DiscoveredPeer, LocalIdentity, PeerOs, PRESENCE_PORT},
    SendFile, Transfer, WiFiInterface, UI,
};
use std::net::{IpAddr, Ipv4Addr};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tauri::{AppHandle, Emitter, Manager, State};
use tokio::sync::mpsc;

/// How long a scan listens. Long enough for a subnet sweep of a /21 to finish shouting
/// (~320 ms of chunk delays) and for the replies to come back, short enough that the button
/// does not feel broken.
const SCAN_WINDOW: Duration = Duration::from_millis(1500);

/// A UI sink for work that has no window in front of it. Everything is best-effort: unlike
/// the transfer's `GUI`, this must never `.expect()` on an emit, because it outlives the
/// window it is emitting into — a serve loop that panics when someone closes the window
/// would take the receiver down with it.
#[derive(Clone)]
pub struct BackgroundUi {
    app: AppHandle,
}

#[derive(Clone, serde::Serialize)]
struct Message {
    message: String,
}

#[derive(Clone, serde::Serialize)]
struct Value {
    value: u8,
}

#[derive(Clone, serde::Serialize)]
struct DetailsPayload {
    current: String,
    total: String,
}

impl UI for BackgroundUi {
    fn output(&self, msg: &str) {
        println!("[paired] {}", msg);
        let _ = self.app.emit(
            "outputMsg",
            Message {
                message: msg.to_string(),
            },
        );
    }
    fn show_progress_bar(&self) {
        let _ = self.app.emit("showProgressBar", Value { value: 0 });
    }
    fn update_progress_bar(&self, percent: u8) {
        let _ = self.app.emit("updateProgressBar", Value { value: percent });
    }
    fn update_total_progress_bar(&self, percent: u8) {
        let _ = self
            .app
            .emit("updateTotalProgressBar", Value { value: percent });
    }
    fn update_progress_details(&self, current: &str, total: &str) {
        let _ = self.app.emit(
            "updateProgressDetails",
            DetailsPayload {
                current: current.to_string(),
                total: total.to_string(),
            },
        );
    }
    fn enable_ui(&self) {
        let _ = self.app.emit("enableUi", Value { value: 0 });
    }
    fn show_pin(&self, pin: &str) {
        let _ = self.app.emit(
            "showPin",
            Message {
                message: pin.to_string(),
            },
        );
    }
    fn ask_file_conflict(&self, _: &str, _: u64, _: u64, _: bool, _: bool) {
        // Only ever asked on the sending side, and a paired send always has a window.
    }
}

pub struct PairedState {
    pub store: Mutex<PairingStore>,
    serve_cancel: Mutex<Option<Arc<AtomicBool>>>,
}

impl PairedState {
    pub fn load() -> Self {
        let store = pairing::load().unwrap_or_else(|e| {
            println!("[paired] {}", e);
            PairingStore::fresh(pairing::default_device_name())
        });
        PairedState {
            store: Mutex::new(store),
            serve_cancel: Mutex::new(None),
        }
    }

    /// The group key, for the one caller outside this module: start_async, which needs it to
    /// hand to a paired hotspot transfer.
    pub fn group_key(&self) -> Option<[u8; 32]> {
        self.snapshot().group_key_bytes()
    }

    fn snapshot(&self) -> PairingStore {
        self.store.lock().unwrap_or_else(|e| e.into_inner()).clone()
    }

    /// Every mutation goes through here, so nothing can change the store without also
    /// persisting it and telling the page.
    fn update<F: FnOnce(&mut PairingStore)>(&self, app: &AppHandle, f: F) -> Result<(), String> {
        let snapshot = {
            let mut store = self.store.lock().unwrap_or_else(|e| e.into_inner());
            f(&mut store);
            store.clone()
        };
        pairing::save(&snapshot).map_err(|e| e.to_string())?;
        let _ = app.emit("pairedChanged", status_of(&snapshot));
        Ok(())
    }
}

#[derive(Clone, serde::Serialize)]
pub struct PeerView {
    pub device_id: String,
    pub name: String,
    pub os: String,
    pub last_ip: Option<String>,
    pub last_seen: u64,
    pub auto_accept: bool,
    /// Answered by the last scan, rather than merely remembered from an earlier one.
    pub reachable: bool,
}

#[derive(Clone, serde::Serialize)]
pub struct PairedStatus {
    pub paired: bool,
    pub device_id: String,
    pub name: String,
    pub receive_dir: Option<String>,
    pub peers: Vec<PeerView>,
    pub serving: bool,
    pub port: u16,
}

fn status_of(store: &PairingStore) -> PairedStatus {
    PairedStatus {
        paired: store.group_key.is_some(),
        device_id: store.device_id.clone(),
        name: store.name.clone(),
        receive_dir: store.receive_dir.clone(),
        peers: store
            .peers
            .iter()
            .map(|p| PeerView {
                device_id: p.device_id.clone(),
                name: p.name.clone(),
                os: p.os.clone(),
                last_ip: p.last_ip.clone(),
                last_seen: p.last_seen,
                auto_accept: p.auto_accept,
                reachable: false,
            })
            .collect(),
        serving: store.group_key.is_some(),
        port: PRESENCE_PORT,
    }
}

fn identity_of(store: &PairingStore) -> LocalIdentity {
    LocalIdentity {
        device_id: store.device_id_bytes(),
        name: store.name.clone(),
        os: PeerOs::this_device(),
        // The desktop serves whenever it is running, so it always advertises that a
        // transfer will land without anyone touching it.
        unattended: store.group_key.is_some(),
    }
}

/// The interface presence and paired transfers ride on: the default-route one, which
/// `get_connected_interfaces` already lists first. Ethernet counts — a paired transfer has
/// no reason to insist on Wi-Fi the way the hotspot path does.
fn local_endpoint() -> Result<(WiFiInterface, Ipv4Addr, u8), String> {
    let interfaces = network::get_connected_interfaces().map_err(|e| e.to_string())?;
    let first = interfaces
        .into_iter()
        .next()
        .ok_or_else(|| "No connected network interface.".to_string())?;
    let interface = WiFiInterface(first.name.clone(), first.guid.clone());
    let ip = network::get_local_ip(&interface).map_err(|e| e.to_string())?;
    let prefix = network::get_prefix_length(&interface).map_err(|e| e.to_string())?;
    Ok((interface, ip, prefix))
}

// ---------------------------------------------------------------------------------------
// Commands
// ---------------------------------------------------------------------------------------

#[tauri::command]
pub fn paired_status(state: State<PairedState>) -> PairedStatus {
    status_of(&state.snapshot())
}

/// Starts a group and returns what to show in the QR — and, printed underneath it, what to
/// type on a device with no camera.
#[tauri::command]
pub fn paired_create_group(app: AppHandle, state: State<PairedState>) -> Result<String, String> {
    state.update(&app, |store| {
        store.create_group();
    })?;
    let uri = state
        .snapshot()
        .pair_uri()
        .ok_or_else(|| "Could not build the pairing code.".to_string())?;
    restart_serving(&app);
    Ok(uri)
}

/// The existing group's code, for showing again to a third device. Separate from
/// `paired_create_group` so that re-showing can never accidentally re-key the group and
/// silently strand every device already paired.
#[tauri::command]
pub fn paired_pair_code(state: State<PairedState>) -> Result<String, String> {
    state
        .snapshot()
        .pair_uri()
        .ok_or_else(|| "This device is not paired with anything yet.".to_string())
}

/// Joins a group from a scanned or typed code. The peers themselves are not learned here —
/// they arrive on the first scan, which is also what proves the key is right.
#[tauri::command]
pub fn paired_join(app: AppHandle, state: State<PairedState>, text: String) -> Result<(), String> {
    let (key, _name) = pairing::parse_pair_uri(&text).map_err(|e| e.to_string())?;
    state.update(&app, |store| {
        store.group_key = Some(pairing::base32_encode(&key));
    })?;
    restart_serving(&app);
    Ok(())
}

/// Leaves the group. The peer list goes with it: those entries are meaningless without the
/// key, and leaving them behind would show devices that can no longer be reached.
#[tauri::command]
pub fn paired_leave(app: AppHandle, state: State<PairedState>) -> Result<(), String> {
    state.update(&app, |store| {
        store.group_key = None;
        store.peers.clear();
    })?;
    stop_serving(&app);
    Ok(())
}

#[tauri::command]
pub fn paired_set_name(
    app: AppHandle,
    state: State<PairedState>,
    name: String,
) -> Result<(), String> {
    state.update(&app, |store| {
        store.name = pairing::clamp_name(&name);
    })
}

#[tauri::command]
pub fn paired_set_receive_dir(
    app: AppHandle,
    state: State<PairedState>,
    dir: Option<String>,
) -> Result<(), String> {
    state.update(&app, |store| {
        store.receive_dir = dir.filter(|d| !d.is_empty());
    })
}

#[tauri::command]
pub fn paired_forget(
    app: AppHandle,
    state: State<PairedState>,
    device_id: String,
) -> Result<(), String> {
    state.update(&app, |store| store.forget_peer(&device_id))
}

#[tauri::command]
pub fn paired_set_auto_accept(
    app: AppHandle,
    state: State<PairedState>,
    device_id: String,
    allow: bool,
) -> Result<(), String> {
    state.update(&app, |store| {
        if let Some(peer) = store.peers.iter_mut().find(|p| p.device_id == device_id) {
            peer.auto_accept = allow;
        }
    })
}

/// Asks who is there. This is the only thing on the desktop that ever shouts, and it only
/// happens while someone is looking at the device list.
///
/// A first pass goes out on multicast and the subnet broadcast address; if nothing answers,
/// a second walks the subnet host by host — which is what rescues 白い熊's own /21, where
/// the network drops multicast between clients.
#[tauri::command]
pub async fn paired_scan(
    app: AppHandle,
    state: State<'_, PairedState>,
) -> Result<Vec<PeerView>, String> {
    let store = state.snapshot();
    let group_key = store
        .group_key_bytes()
        .ok_or_else(|| "This device is not paired with anything yet.".to_string())?;
    let presence_key = derive_presence_key(&group_key);
    let identity = identity_of(&store);
    let (_interface, ip, prefix) = local_endpoint()?;

    let mut found = presence::probe(&presence_key, &identity, ip, prefix, SCAN_WINDOW, false)
        .await
        .map_err(|e| e.to_string())?;
    if found.is_empty() {
        found = presence::probe(&presence_key, &identity, ip, prefix, SCAN_WINDOW, true)
            .await
            .map_err(|e| e.to_string())?;
    }

    remember(&app, &state, &found)?;

    let store = state.snapshot();
    Ok(store
        .peers
        .iter()
        .map(|p| {
            let live = found
                .iter()
                .find(|f| pairing::base32_encode(&f.device_id) == p.device_id);
            PeerView {
                device_id: p.device_id.clone(),
                name: live.map(|f| f.name.clone()).unwrap_or_else(|| p.name.clone()),
                os: p.os.clone(),
                last_ip: live
                    .map(|f| f.ip.to_string())
                    .or_else(|| p.last_ip.clone()),
                last_seen: p.last_seen,
                auto_accept: p.auto_accept,
                reachable: live.is_some(),
            }
        })
        .collect())
}

fn remember(
    app: &AppHandle,
    state: &State<PairedState>,
    found: &[DiscoveredPeer],
) -> Result<(), String> {
    state.update(app, |store| {
        for peer in found {
            store.upsert_peer(PairedPeer {
                device_id: pairing::base32_encode(&peer.device_id),
                name: peer.name.clone(),
                os: peer.os.as_str().to_string(),
                last_ip: Some(peer.ip.to_string()),
                last_seen: pairing::now_secs(),
                // A peer we have never seen before starts trusted: it already proved it
                // holds the group key, which is the only credential this feature has, and
                // an unattended receive the user has to go and switch on for each device
                // is not the feature they asked for. Revocable per peer.
                auto_accept: true,
            });
        }
    })
}

/// Sends to a paired device in one call: no mode, no password, no interface picker.
///
/// The cached address is tried first and the scan is the fallback, not the other way
/// round — a unicast packet reaches a device whose Wi-Fi driver is filtering broadcast,
/// which is exactly the state a phone sitting on a desk is in.
#[tauri::command]
pub async fn paired_send(
    app: AppHandle,
    device_id: String,
    file_list: Vec<SendFile>,
) -> Result<(), String> {
    if file_list.is_empty() {
        return Err("Nothing selected to send.".to_string());
    }
    let state: State<PairedState> = app.state();
    let transfer: State<Transfer> = app.state();
    let store = state.snapshot();
    let group_key = store
        .group_key_bytes()
        .ok_or_else(|| "This device is not paired with anything yet.".to_string())?;
    let peer = store
        .find_peer(&device_id)
        .cloned()
        .ok_or_else(|| "That device is not in the paired list.".to_string())?;

    // Claim the one transfer slot exactly as start_async does, so a paired send and a
    // classic transfer can never run at once and Cancel keeps working on either.
    let (conflict_tx, mut conflict_rx) = mpsc::channel(1);
    {
        let task = transfer.task.lock().unwrap_or_else(|e| e.into_inner());
        if task.cancelling {
            return Err("Still cancelling the previous transfer. Try again in a moment.".into());
        }
        if task.is_running() {
            return Err("A transfer is already in progress.".into());
        }
    }
    {
        let mut slot = transfer.conflict_tx.lock().unwrap_or_else(|e| e.into_inner());
        *slot = Some(conflict_tx);
    }

    let ui = BackgroundUi { app: app.clone() };
    let psk = derive_paired_psk(&group_key);
    let identity_id = store.device_id_bytes();
    let identity_name = store.name.clone();

    let mut address: Option<IpAddr> = peer
        .last_ip
        .as_deref()
        .and_then(|ip| ip.parse::<IpAddr>().ok());
    if address.is_none() {
        ui.output("No remembered address for that device; searching...");
        address = find_now(&app, &state, &device_id).await?;
    }
    let Some(address) = address else {
        return Err(format!(
            "Could not find {} on this network. Open the app on it, or check both devices are on the same network.",
            peer.name
        ));
    };

    let result = paired::send_to_peer(
        address,
        PRESENCE_PORT,
        &psk,
        identity_id,
        &identity_name,
        &file_list,
        &ui,
        &mut conflict_rx,
    )
    .await;

    match result {
        Ok(()) => {
            let _ = state.update(&app, |store| {
                store.note_seen(&device_id, &address.to_string())
            });
            ui.enable_ui();
            Ok(())
        }
        Err(first) => {
            // A remembered address goes stale every time DHCP moves the peer, and the
            // failure looks identical to the device being off. So a failed direct connect
            // is a reason to look again, not to give up: this is the retry that makes the
            // cached-address shortcut safe to rely on.
            ui.output(&format!("{} Looking for a new address...", first));
            let fresh = find_now(&app, &state, &device_id).await?;
            match fresh {
                Some(new_address) if new_address != address => {
                    let outcome = paired::send_to_peer(
                        new_address,
                        PRESENCE_PORT,
                        &psk,
                        identity_id,
                        &identity_name,
                        &file_list,
                        &ui,
                        &mut conflict_rx,
                    )
                    .await;
                    ui.enable_ui();
                    match outcome {
                        Ok(()) => {
                            let _ = state.update(&app, |store| {
                                store.note_seen(&device_id, &new_address.to_string())
                            });
                            Ok(())
                        }
                        Err(e) => Err(e.to_string()),
                    }
                }
                _ => {
                    ui.enable_ui();
                    Err(first.to_string())
                }
            }
        }
    }
}

/// One targeted scan for a single device, used when the cached address did not work.
async fn find_now(
    app: &AppHandle,
    state: &State<'_, PairedState>,
    device_id: &str,
) -> Result<Option<IpAddr>, String> {
    let store = state.snapshot();
    let Some(group_key) = store.group_key_bytes() else {
        return Ok(None);
    };
    let presence_key = derive_presence_key(&group_key);
    let identity = identity_of(&store);
    let (_interface, ip, prefix) = local_endpoint()?;
    let mut found = presence::probe(&presence_key, &identity, ip, prefix, SCAN_WINDOW, false)
        .await
        .map_err(|e| e.to_string())?;
    if !found.iter().any(|f| pairing::base32_encode(&f.device_id) == device_id) {
        found = presence::probe(&presence_key, &identity, ip, prefix, SCAN_WINDOW, true)
            .await
            .map_err(|e| e.to_string())?;
    }
    remember(app, state, &found)?;
    Ok(found
        .iter()
        .find(|f| pairing::base32_encode(&f.device_id) == device_id)
        .map(|f| IpAddr::V4(f.ip)))
}

// ---------------------------------------------------------------------------------------
// Serving
// ---------------------------------------------------------------------------------------

/// Decides one offer, entirely from what is already stored. Deliberately synchronous and
/// deliberately unable to ask: phase one has no "somebody is knocking" dialog, and an offer
/// that cannot be auto-accepted is refused with a reason the sender can act on rather than
/// left hanging on a prompt nobody is in front of.
fn decide(app: &AppHandle, offer: &PairedOffer) -> Verdict {
    let transfer: State<Transfer> = app.state();
    {
        let task = transfer.task.lock().unwrap_or_else(|e| e.into_inner());
        if task.is_running() || task.cancelling {
            return Verdict::Refuse(Refusal::Busy);
        }
    }
    let state: State<PairedState> = app.state();
    let store = state.snapshot();
    let id = pairing::base32_encode(&offer.device_id);
    match store.find_peer(&id) {
        Some(peer) if peer.auto_accept => (),
        // Holding the group key is necessary but not sufficient: the user gets the last
        // word on which of their own devices may write here unattended.
        _ => return Verdict::Refuse(Refusal::NotAllowed),
    }
    match store.receive_dir.as_deref() {
        Some(dir) if !dir.is_empty() => Verdict::Accept(PathBuf::from(dir)),
        _ => Verdict::Refuse(Refusal::NoDestination),
    }
}

/// Brings the serve loop up, or takes it down, to match the store. Idempotent, so it can
/// simply be called after anything that might have changed the group.
pub fn restart_serving(app: &AppHandle) {
    stop_serving(app);
    let state: State<PairedState> = app.state();
    let store = state.snapshot();
    let Some(group_key) = store.group_key_bytes() else {
        return;
    };

    let cancel = Arc::new(AtomicBool::new(false));
    {
        let mut slot = state.serve_cancel.lock().unwrap_or_else(|e| e.into_inner());
        *slot = Some(cancel.clone());
    }

    let psk = derive_paired_psk(&group_key);
    let presence_key = derive_presence_key(&group_key);
    let identity = identity_of(&store);

    // The transfer half.
    {
        let app = app.clone();
        let cancel = cancel.clone();
        tokio::spawn(async move {
            let ui = BackgroundUi { app: app.clone() };
            let decider_app = app.clone();
            let result = paired::serve(psk, &ui, cancel, move |offer: &PairedOffer| {
                decide(&decider_app, offer)
            })
            .await;
            if let Err(e) = result {
                // Port already taken is the realistic case — a second copy of the app, or
                // the phone's own listener on a shared host. Say so instead of being
                // silently unreachable, which is the failure mode this whole feature is
                // supposed to remove.
                ui.output(&format!(
                    "Cannot receive from paired devices: {}. Port {} may be in use by another program.",
                    e, PRESENCE_PORT
                ));
            }
        });
    }

    // The presence half: hold the port and answer probes. It also learns addresses for
    // free — every probe it answers came from a device that has just told us where it is.
    {
        let app = app.clone();
        let cancel = cancel.clone();
        tokio::spawn(async move {
            let ui = BackgroundUi { app: app.clone() };
            let (_interface, ip, prefix) = match local_endpoint() {
                Ok(endpoint) => endpoint,
                Err(e) => {
                    println!("[paired] not announcing: {}", e);
                    return;
                }
            };
            let responder = presence::PresenceResponder::new(presence_key, identity);
            let watcher = cancel.clone();
            let responder_cancel = responder.cancel_handle();
            tokio::spawn(async move {
                while !watcher.load(Ordering::SeqCst) {
                    tokio::time::sleep(Duration::from_millis(250)).await;
                }
                responder_cancel.store(true, Ordering::SeqCst);
            });
            let heard = app.clone();
            if let Err(e) = responder
                .run(ip, prefix, move |peer: DiscoveredPeer| {
                    let state: State<PairedState> = heard.state();
                    // Adds a device it has not heard of before, rather than only refreshing
                    // one it has. That omission is what made pairing look one-way: the device
                    // that showed the QR only ever *answers* probes, so it never learned about
                    // the device that had just joined it. Completing a presence exchange means
                    // holding the group key, which is the only credential here — so hearing a
                    // device is exactly as good a reason to list it as finding one.
                    let _ = state.update(&heard, |store| {
                        store.upsert_peer(PairedPeer {
                            device_id: pairing::base32_encode(&peer.device_id),
                            name: peer.name.clone(),
                            os: peer.os.as_str().to_string(),
                            last_ip: Some(peer.ip.to_string()),
                            last_seen: pairing::now_secs(),
                            auto_accept: true,
                        });
                    });
                })
                .await
            {
                ui.output(&format!("Presence is not running: {}", e));
            }
        });
    }
}

pub fn stop_serving(app: &AppHandle) {
    let state: State<PairedState> = app.state();
    let mut slot = state.serve_cancel.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(cancel) = slot.take() {
        cancel.store(true, Ordering::SeqCst);
    }
}
