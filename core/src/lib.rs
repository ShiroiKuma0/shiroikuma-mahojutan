#[cfg_attr(target_os = "linux", path = "linux/network.rs")]
#[cfg_attr(target_os = "windows", path = "windows/network.rs")]
pub mod network;

#[cfg_attr(target_os = "linux", path = "linux/bluetooth.rs")]
#[cfg_attr(target_os = "windows", path = "windows/bluetooth.rs")]
pub mod bluetooth;

pub mod discovery;
pub mod error;
pub mod noise;
pub mod paired;
pub mod pairing;
pub mod presence;
mod receiving;
mod sending;
pub mod utils;

use bluetooth::negotiate_bluetooth;
use discovery::{DiscoveryRole, DiscoveryService};
use error::{fc_error, FCError};
use socket2::{SockRef, TcpKeepalive};
use std::{
    net::SocketAddr,
    path::PathBuf,
    pin::Pin,
    sync::{Arc, Mutex},
    task::{Context, Poll},
    time::Duration,
};
use tokio::{
    io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, ReadBuf},
    net::{TcpListener, TcpStream},
    sync::mpsc,
};
use utils::get_key_and_ssid;

/// The transport the transfer runs over: a plain TCP stream (hotspot mode) or a Noise
/// EncryptedStream (shared network mode, v10+). Both implement AsyncRead + AsyncWrite, so
/// version/mode confirmation and the send/receive code are identical over either. Opaque
/// to callers — `start_transfer` returns it and `clean_up_transfer` consumes it.
pub enum TransferStream {
    Plain(TcpStream),
    Encrypted(Box<noise::EncryptedStream<TcpStream>>),
}

impl AsyncRead for TransferStream {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<std::io::Result<()>> {
        match self.get_mut() {
            TransferStream::Plain(s) => Pin::new(s).poll_read(cx, buf),
            TransferStream::Encrypted(s) => Pin::new(&mut **s).poll_read(cx, buf),
        }
    }
}

impl AsyncWrite for TransferStream {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<std::io::Result<usize>> {
        match self.get_mut() {
            TransferStream::Plain(s) => Pin::new(s).poll_write(cx, buf),
            TransferStream::Encrypted(s) => Pin::new(&mut **s).poll_write(cx, buf),
        }
    }
    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        match self.get_mut() {
            TransferStream::Plain(s) => Pin::new(s).poll_flush(cx),
            TransferStream::Encrypted(s) => Pin::new(&mut **s).poll_flush(cx),
        }
    }
    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        match self.get_mut() {
            TransferStream::Plain(s) => Pin::new(s).poll_shutdown(cx),
            TransferStream::Encrypted(s) => Pin::new(&mut **s).poll_shutdown(cx),
        }
    }
}

const CHUNKSIZE: usize = 1_000_000; // 1 MB
                                    // v10 is a breaking change: shared network mode and its new protocol are not compatible
                                    // with v9 or earlier. See docs/shared-network-crypto.md.
const MAJOR_VERSION: u64 = 10;
// What this fork puts on the wire in place of the plain major version, and the floor at which a
// peer is recognised as running it too. Stock v10 sends 10 and treats anything higher as "newer
// peer decides compatibility", which it then obeys -- so a fork can announce itself without
// breaking a stock peer, and two forks recognise each other and switch on the extras below.
// Only ever compared, never displayed.
const WIRE_VERSION: u64 = 10_001;
const FORK_WIRE_FLOOR: u64 = 10_000;

/// What to do about a file the receiving device already has, decided on the SENDING device.
///
/// Fork-only: the exchange that carries it (receiving.rs / sending.rs) is skipped entirely unless
/// both ends announced a fork wire version, because a stock peer would read the extra fields as
/// the next protocol field and desynchronise.
#[derive(Clone, Debug, PartialEq)]
pub enum FileConflictChoice {
    Skip,
    Overwrite,
    Rename(String),
}

/// A conflict answer, plus whether it should stand for every remaining file instead of just this
/// one. A ten-file folder the peer already has asked ten identical questions before this existed
/// (白い熊, 2026-08-14).
#[derive(Clone, Debug, PartialEq)]
pub struct FileConflictAnswer {
    pub choice: FileConflictChoice,
    pub apply_to_all: bool,
}

/// What "apply to all" remembers: the *rule*, never the answer verbatim.
///
/// A rename carries a name, and a name cannot be reused -- sending every remaining file as
/// "photo (copy).jpg" would pile them all onto one another at the other end. So a sticky rename
/// stores only the intent, and each file gets its own name from `utils::suggest_rename`, which is
/// the "keep both" of every file manager.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum ConflictRule {
    Skip,
    Overwrite,
    Rename,
}

impl ConflictRule {
    /// The rule behind an answer, so that the answer can be repeated for later files.
    pub fn of(choice: &FileConflictChoice) -> Self {
        match choice {
            FileConflictChoice::Skip => ConflictRule::Skip,
            FileConflictChoice::Overwrite => ConflictRule::Overwrite,
            FileConflictChoice::Rename(_) => ConflictRule::Rename,
        }
    }

    /// The rule applied to one file: the only place a sticky rename's name is decided.
    pub fn apply(&self, relative_name: &str) -> FileConflictChoice {
        match self {
            ConflictRule::Skip => FileConflictChoice::Skip,
            ConflictRule::Overwrite => FileConflictChoice::Overwrite,
            ConflictRule::Rename => {
                FileConflictChoice::Rename(utils::suggest_rename(relative_name))
            }
        }
    }

    pub fn label(&self) -> &'static str {
        match self {
            ConflictRule::Skip => "skip",
            ConflictRule::Overwrite => "overwrite",
            ConflictRule::Rename => "rename",
        }
    }
}
// Sanity bound on the peer-supplied file count (companion to the header bounds in
// receiving.rs): no legitimate transfer approaches it, and a corrupt or hostile
// stream shouldn't be able to put us into a near-endless receive loop.
const MAX_FILE_COUNT: u64 = 1_000_000;

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum ConnectionMode {
    Hotspot,
    SharedNetwork,
}

/// Which side supplies the credentials during the Bluetooth exchange — the one rule both BLE
/// roles (peripheral and central) and every platform have to agree on, so it lives here rather
/// than being spelled out at each of its call sites.
///
/// Hotspot mode: whoever hosts the hotspot, because they are the ones who own an SSID and a
/// password to hand over. Shared network mode: there is no hotspot and no SSID that matters, so
/// the rule is the one the manual path already uses — the **receiver** generates the password
/// (it is the side that would otherwise display it) and the sender takes it, over BLE instead of
/// off a QR code. The SSID still travels, derived from the password like everywhere else, and is
/// simply unused at the other end.
pub(crate) fn we_supply_credentials(
    connection_mode: ConnectionMode,
    peer: &Peer,
    mode: &Mode,
) -> bool {
    match connection_mode {
        ConnectionMode::SharedNetwork => matches!(mode, Mode::Receive(_)),
        ConnectionMode::Hotspot => network::is_hosting(peer, mode),
    }
}

pub trait UI: Clone + Send + 'static {
    fn output(&self, msg: &str);
    fn show_progress_bar(&self);
    fn update_progress_bar(&self, percent: u8);
    // second bar, underneath: the whole transfer rather than the file in flight
    fn update_total_progress_bar(&self, percent: u8);
    // two lines above the bars: the current file, then the transfer as a whole
    fn update_progress_details(&self, current: &str, total: &str);
    fn enable_ui(&self);
    fn show_pin(&self, pin: &str);
    /// The receiving device already has a file by this name. Ask which way to go; the answer
    /// arrives on the channel passed to start_transfer, exactly as the pairing PIN's does.
    /// `more_files` is false on the last file of the transfer, where "apply to all" has nothing
    /// left to apply to and the toggle should not be offered.
    fn ask_file_conflict(
        &self,
        name: &str,
        local_size: u64,
        incoming_size: u64,
        identical: bool,
        more_files: bool,
    );
}

#[derive(Clone)]
pub enum Mode {
    Send(Vec<SendFile>),
    Receive(PathBuf),
}

// A file queued for sending, paired with the relative name the peer will receive it under.
//
// The name is computed once at selection time (utils::expand_selection) instead of being
// derived from a common prefix at send time: every top-level selection is stripped of its
// own parent directory, so a selected folder's name survives on the wire and the receiver
// recreates the folder with the files inside, while individually selected files arrive
// flat. Separators are always "/", whatever the host platform uses. Matches the Apple and
// Android senders; see docs/send-folder-behavior.md.
#[derive(Clone, serde::Deserialize, serde::Serialize)]
pub struct SendFile {
    pub path: PathBuf,
    pub name: String,
}

#[derive(Clone, Copy)]
pub enum Peer {
    Android,
    IOS,
    Linux,
    MacOS,
    Windows,
}

impl TryFrom<&str> for Peer {
    type Error = FCError;

    fn try_from(peer: &str) -> Result<Self, Self::Error> {
        match peer {
            "android" => Ok(Peer::Android),
            "ios" => Ok(Peer::IOS),
            "linux" => Ok(Peer::Linux),
            "mac" => Ok(Peer::MacOS),
            "windows" => Ok(Peer::Windows),
            other => Err(FCError {
                message: format!("Bad peer: {}", other),
            }),
        }
    }
}

pub enum PeerResource {
    WifiClient(String), // used if joining, .0 is ip of gateway/peer/host
    WindowsHotspot(network::WindowsHotspot),
    LinuxHotspot,
}

// first String is the interface's name, second String is a base-10 representation of the u128 representation of the GUID of the interface. GUID is only used on Windows.
#[derive(serde::Deserialize, serde::Serialize)]
pub struct WiFiInterface(pub String, pub String);

// returned by the interface-enumeration functions so the UI can label interfaces with
// their IP (or lack of one); name and guid follow the WiFiInterface conventions above
#[derive(serde::Serialize)]
pub struct InterfaceInfo {
    pub name: String,
    pub guid: String,
    pub ip: Option<String>,
}

/// The running transfer, if any, and whether a cancellation is already in flight. Both live
/// under one lock so starting and cancelling can't race: every transition (start, begin
/// cancel, finish cancel) is decided while holding it. Aborting a task only takes effect at
/// its next await, so a cancel can stay in flight for a while if the transfer is inside a
/// blocking wifi or bluetooth call; `cancelling` is what makes the clicks that arrive during
/// that window no-ops instead of a queued-up second transfer.
#[derive(Default)]
pub struct TransferTask {
    pub handle: Option<tokio::task::JoinHandle<()>>,
    pub cancelling: bool,
}

impl TransferTask {
    /// True while a transfer task exists and hasn't finished on its own.
    pub fn is_running(&self) -> bool {
        self.handle.as_ref().is_some_and(|h| !h.is_finished())
    }
}

// Progress across the whole transfer, so the second bar and second details line can say where we
// are overall and not just within the file in flight. When sending we know every file's size up
// front and can work in bytes; when receiving, sizes arrive one file at a time (the wire protocol
// sends filename + size per file, and adding a grand total would break compatibility with the other
// platforms), so the overall figure is weighted by file count instead.
pub struct Totals {
    pub num_files: u64,
    pub file_index: u64, // 1-based
    pub bytes_done: u64, // completed files only
    pub total_bytes: Option<u64>,
    pub start: std::time::Instant,
    // Recent rate for the whole-transfer line, so it agrees with the per-file line rather than
    // quoting a different (cumulative) number beside it. See utils::RateWindow.
    rate: utils::RateWindow,
}

impl Totals {
    pub fn new(num_files: u64, total_bytes: Option<u64>) -> Self {
        Totals {
            num_files,
            file_index: 0,
            bytes_done: 0,
            total_bytes,
            start: std::time::Instant::now(),
            rate: utils::RateWindow::new(),
        }
    }

    // (percent, text) for the whole transfer, given how far into the current file we are.
    pub fn snapshot(&mut self, current_done: u64, current_size: u64) -> (u8, String) {
        let files = format!("File {} of {}", self.file_index.max(1), self.num_files);
        match self.total_bytes {
            Some(total) if total > 0 => {
                let done = (self.bytes_done + current_done).min(total);
                let percent = (done as f64 / total as f64 * 100.0).round() as u8;
                // The file counter rides on the data line, so the split stays two rows rather
                // than becoming three (see progress_details_parts).
                let recent = self.rate.sample(done);
                let (data, clock) = utils::progress_details_parts_at(
                    done,
                    total,
                    self.start.elapsed().as_secs_f64(),
                    recent,
                );
                (percent, format!("{}  ·  {}\n{}", files, data, clock))
            }
            _ => {
                let within = if current_size > 0 {
                    current_done as f64 / current_size as f64
                } else {
                    0.0
                };
                let completed = self.file_index.saturating_sub(1) as f64;
                let percent =
                    ((completed + within) / self.num_files.max(1) as f64 * 100.0).round() as u8;
                let done = self.bytes_done + current_done;
                (
                    percent.min(100),
                    format!("{}  ·  {} received", files, utils::make_size_readable(done)),
                )
            }
        }
    }
}

pub struct Transfer {
    pub task: Mutex<TransferTask>,
    pub hotspot: Arc<Mutex<Option<PeerResource>>>,
    pub ssid: Arc<Mutex<Option<String>>>,
    pub ble_ui_tx: Mutex<Option<mpsc::Sender<bool>>>, // used by javascript to report user's choice about whether to pair with bluetooth device to windows custom pairing callback.
    // The same arrangement for "the other device already has this file": the frontend asks, the
    // answer comes back here, and the sending half of the transfer is waiting on it.
    pub conflict_tx: Mutex<Option<mpsc::Sender<FileConflictAnswer>>>,
}

impl Transfer {
    pub fn new() -> Self {
        Transfer {
            task: Mutex::new(TransferTask::default()),
            hotspot: Arc::new(Mutex::new(None)),
            ssid: Arc::new(Mutex::new(None)),
            ble_ui_tx: Mutex::new(None),
            conflict_tx: Mutex::new(None),
        }
    }
}

/// Fork: what a hotspot transfer between paired devices needs to know — the key shared
/// with the one peer, this device's own id for the hello, and the peer's name for the
/// sentences.
#[derive(Clone, Debug)]
pub struct PairedHotspot {
    pub key: [u8; 32],
    pub local_id: [u8; 16],
    pub peer_name: String,
}

pub async fn start_transfer<T: UI>(
    mode: String,
    using_bluetooth: bool,
    mut peer: Option<String>,
    mut password: Option<String>,
    interface: WiFiInterface,
    file_list: Option<Vec<SendFile>>,
    receive_dir: Option<String>,
    ui: &T,
    hotspot: Arc<Mutex<Option<PeerResource>>>,
    state_ssid: Arc<Mutex<Option<String>>>,
    ble_ui_rx: mpsc::Receiver<bool>,
    connection_mode: ConnectionMode,
    // The sending side's answers to "the other device already has this file" (fork-only).
    mut conflict_rx: mpsc::Receiver<FileConflictAnswer>,
    // Fork: the pair key and this device's identity, when this is a transfer between
    // paired devices over a hotspot. Everything a hotspot transfer normally has to exchange
    // — the password, the SSID, and therefore the whole QR-or-Bluetooth ceremony — is
    // derived from the key instead. None for every other transfer, which then behaves
    // exactly as it always has.
    paired: Option<PairedHotspot>,
) -> Option<TransferStream> {
    let paired_key = paired.as_ref().map(|p| p.key);
    // get files or receive directory
    // don't panic on bad input: a panic here kills the transfer task without running
    // cleanup, which is how the UI used to get stuck in its in-progress state (#118)
    let mode = if mode == "send" {
        // an empty list is treated as "nothing chosen" rather than started: javascript's
        // truthiness check passes an empty array through, and a zero-file send has nothing
        // to do anyway
        let files = match file_list {
            Some(files) if !files.is_empty() => files,
            _ => {
                ui.output("Error: send mode selected but no files were chosen.");
                return None;
            }
        };
        Mode::Send(files)
    } else if mode == "receive" {
        match receive_dir {
            Some(folder) => Mode::Receive(PathBuf::from(folder)),
            None => {
                ui.output("Error: receive mode selected but no destination folder was chosen.");
                return None;
            }
        }
    } else {
        ui.output(&format!("Error: bad mode: {}", mode));
        return None;
    };

    // if bluetooth, make that connection here first
    // for windows and linux, the central/client api can read and write synchronously, and we always know the ssid before starting hotspot, so we can just do that here before connecting to peer?
    // for servers/peripherals, does it matter? callbacks in both cases?

    // Bluetooth is available in both connection modes (fork, 白い熊 2026-08-07). In hotspot
    // mode it hands over the hotspot's SSID and password; in shared network mode there is no
    // hotspot, so it carries the transfer password alone and the SSID it returns is ignored —
    // the alternative to the receiver displaying a QR code the sender scans or types.
    // The SSID the peer told us over Bluetooth, kept rather than discarded.
    //
    // Everywhere else the hotspot's name is *derived* from the password -- "flyingCarpet_" plus
    // two bytes of the key -- so both ends can compute it without exchanging it. That holds only
    // while every host names its own AP that way, and it stopped being true when Android started
    // hosting for us: a Wi-Fi Direct group owner's network name must begin with "DIRECT-", so the
    // phone's group is "DIRECT-fc-<password>" and no amount of deriving will produce it. We read
    // the real name over BLE and then threw it away, computed "flyingCarpet_4f7d" instead, and
    // NetworkManager quite rightly reported "Wi-Fi ネットワークが見つかりませんでした" (白い熊,
    // 2026-08-11, first transfer after the hosting flip).
    let mut peer_ssid: Option<String> = None;
    // Fork: paired devices need no credential exchange at all. The password is derived from
    // the group key on both sides, and so is the SSID — including an Android host's Wi-Fi
    // Direct group name, which is "DIRECT-fc-" followed by that same password, so the one
    // name that genuinely cannot be derived from a *generated* password can be derived from
    // this one. That is what removes the QR code, the typing and the BLE handshake.
    if let Some(key) = paired_key {
        let derived = noise::derive_hotspot_password(&key);
        if peer.as_deref() == Some("android") && !network::is_hosting(&Peer::Android, &mode) {
            peer_ssid = Some(format!("DIRECT-fc-{}", derived));
        }
        password = Some(derived);
    }
    if using_bluetooth && paired_key.is_none() {
        match negotiate_bluetooth(&mode, ble_ui_rx, ui, connection_mode).await {
            Ok((p, s, pw)) => {
                peer = Some(p);
                if !s.is_empty() {
                    peer_ssid = Some(s);
                }
                if password.is_none() {
                    password = Some(pw);
                }
            }
            Err(e) => {
                ui.output(&format!("Could not establish Bluetooth connection: {}", e));
                println!("Could not establish Bluetooth connection: {}", e);
                return None;
            }
        }
    }

    let password = match password {
        Some(p) => p,
        None => {
            ui.output("Error: no password provided for transfer.");
            return None;
        }
    };
    let (_, ssid) = get_key_and_ssid(&password);

    // Derive the Noise PSK once, up front: the handshake needs it, and in shared network
    // mode the discovery HMAC key is derived from it too (see noise::derive_discovery_key)
    // so that no fast hash of the password ever goes on the air. Same PBKDF2 cost as
    // before — it previously ran inside the handshake — just moved before discovery.
    // Fork: a paired transfer keys the handshake from the group key directly. The hotspot
    // password derived above is a Wi-Fi credential and nothing more — someone who learns it
    // gets onto the access point and no further, because the payload is behind this key,
    // which is 256 bits of randomness and never leaves either device.
    let psk = match paired_key {
        Some(key) => noise::derive_paired_psk(&key),
        None => noise::derive_psk(&password),
    };

    {
        let mut _state_ssid = state_ssid.lock().expect("Couldn't lock state_ssid");
        *_state_ssid = Some(ssid.clone());
    }

    // Establish the raw TCP connection and determine the Noise role. The Noise initiator
    // must be the TCP client (it sends the first handshake message). No handshake yet:
    // version and mode are negotiated in plaintext first (below), then the connection is
    // wrapped in Noise, for BOTH modes.
    let (peer_resource, tcp, noise_role) = match connection_mode {
        ConnectionMode::SharedNetwork => {
            // Shared Network Mode: Use discovery to find peer on existing network. Both
            // sides are labeled WifiClient, so the role comes from send/receive: the sender
            // is the TCP client (initiator), the receiver is the TCP server (responder).
            match start_shared_network_transfer(
                &mode,
                &noise::derive_discovery_key(&psk),
                &interface,
                ui,
            )
            .await
            {
                Ok((resource, tcp)) => {
                    let role = if matches!(mode, Mode::Send(_)) {
                        noise::Role::Initiator
                    } else {
                        noise::Role::Responder
                    };
                    (resource, tcp, role)
                }
                Err(e) => {
                    ui.output(&format!("Error in shared network mode: {}", e));
                    return None;
                }
            }
        }
        ConnectionMode::Hotspot => {
            // Original Hotspot Mode
            let peer = match Peer::try_from(
                peer.expect("Neither UI nor Bluetooth peer present.")
                    .as_str(),
            ) {
                Ok(p) => p,
                Err(e) => {
                    ui.output(&format!("Error parsing peer: {}", e));
                    return None;
                }
            };

            // Hosting, the SSID is ours to choose and the derived name is the one the peer will
            // compute too. Joining, it is the peer's to tell us -- and only the peer knows it,
            // since an Android host's Wi-Fi Direct name is not derivable (see peer_ssid above).
            // Without Bluetooth there is nothing to be told, and the QR code carries the derived
            // name as it always did, so the fallback is the old behaviour exactly.
            let ssid = if network::is_hosting(&peer, &mode) {
                ssid
            } else {
                peer_ssid.unwrap_or(ssid)
            };

            // Correct the SSID held for teardown. It was stored above from the derived name,
            // which is what clean_up_transfer passes to stop_hotspot -- and nmcli deletes the
            // connection profile *by name*, the name being the SSID we joined under. Left at the
            // derived value it would try to delete "flyingCarpet_4f7d" while the profile actually
            // sitting there is "DIRECT-fc-...", leaking one dead profile per transfer.
            {
                let mut _state_ssid = state_ssid.lock().expect("Couldn't lock state_ssid");
                *_state_ssid = Some(ssid.clone());
            }

            // start hotspot or connect to peer's (the Noise handshake below uses the
            // already-derived PSK, not the password itself)
            let peer_resource =
                match network::connect_to_peer(peer, mode.clone(), ssid, password, interface, ui)
                    .await
                {
                    Ok(p) => p,
                    Err(e) => {
                        ui.output(&format!("Error connecting to peer: {}", e));
                        return None;
                    }
                };

            tokio::task::yield_now().await;

            // start tcp connection
            let stream = match start_tcp(&peer_resource, ui).await {
                Ok(s) => s,
                Err(e) => {
                    ui.output(&format!("Error starting TCP connection: {}", e));
                    return None;
                }
            };

            // The hotspot host is the TCP server (responder); the guest that joined and
            // connected is the client (initiator). start_tcp uses exactly this split.
            let role = match peer_resource {
                PeerResource::WifiClient(_) => noise::Role::Initiator,
                _ => noise::Role::Responder,
            };
            (peer_resource, stream, role)
        }
    };

    // Turn off Nagle for the whole transfer. The send loop writes an 8-byte chunk length
    // and then the chunk body, and the length lands on the wire as its own 26-byte segment
    // (8 bytes + a 2-byte Noise frame header + a 16-byte tag). Nagle holds a sub-MSS
    // segment until the peer ACKs what's already in flight, and during a file body the
    // receiver has nothing to send back, so that ACK waits out the peer's delayed-ACK timer
    // -- 200ms on Windows. One stall per chunk, and at CHUNKSIZE=1MB that is 4,500 stalls
    // for a 4.5GB file: measured 2026-07-25 at 38.8mbps where SMB moved the same file
    // between the same two machines at ~600mbps. Nothing about the transfer is
    // latency-sensitive enough to want Nagle's coalescing; every write here is either
    // already large or one the peer is actively waiting on.
    if let Err(e) = tcp.set_nodelay(true) {
        // Not fatal: this costs throughput, not correctness.
        ui.output(&format!(
            "Couldn't disable Nagle on the TCP connection: {}",
            e
        ));
    }

    // Notice a peer that goes away without saying so.
    //
    // Cancelling on the sending device drops its Wi-Fi as part of the teardown, so the FIN
    // frequently never reaches us and the connection is left half-open. Nothing here has a
    // deadline, so this side sat blocked in read for ever: the transfer stayed "running", the
    // progress bar kept its last figures, and the only way out was pressing Cancel here too
    // (白い熊, 2026-08-11 -- the journal shows the transfer ending only at the manual cancel,
    // three minutes after the phone had gone).
    //
    // Keepalive rather than a read timeout, because a stalled transfer is not a dead one: we have
    // measured this very link go quiet for 12 seconds while both ends were perfectly alive, and a
    // timeout long enough to survive that is too long to be useful. Probes are answered by a live
    // peer no matter how badly the transfer is going, so this fires only when nobody is home:
    // 10s idle, then a probe every 5s, three strikes -- about 25s to notice.
    let keepalive = TcpKeepalive::new()
        .with_time(Duration::from_secs(10))
        .with_interval(Duration::from_secs(5))
        .with_retries(3);
    if let Err(e) = SockRef::from(&tcp).set_tcp_keepalive(&keepalive) {
        // Also not fatal: without it we are back to the old behaviour, not worse than it.
        ui.output(&format!("Couldn't enable TCP keepalive: {}", e));
    }

    // The confirm functions only need to know whether we joined the peer's network (guest
    // sends first) or are hosting; capture that before peer_resource moves into the state.
    let is_wifi_client = matches!(peer_resource, PeerResource::WifiClient(..));

    // Store the hotspot in tauri's state NOW, before anything below can fail, so that
    // clean_up_transfer tears it down even when the preamble or handshake errors out
    // (on Windows, stop_hotspot is a no-op unless the PeerResource is in this state).
    // Has to be in its own block or tokio complains that this "mutex guard" is held across an await.
    {
        let mut hotspot_value = hotspot.lock().expect("Couldn't lock hotspot mutex");
        *hotspot_value = Some(peer_resource);
    }

    // Plaintext preamble on the raw TCP stream: version, then send/receive mode. These are
    // not secret (an eavesdropper can already see a transfer is happening) and keeping them
    // outside Noise gives clean version-mismatch reporting — but every preamble byte, sent
    // and received, is recorded and bound into the Noise prologue below, so tampering with
    // them fails the handshake instead of going unnoticed.
    let mut preamble = noise::RecordingStream::new(tcp);

    // make sure the versions are compatible
    let peer_is_fork = match confirm_version(is_wifi_client, &mut preamble).await {
        Ok(is_fork) => is_fork,
        Err(e) => {
            ui.output(&format!("Error confirming version: {}", e));
            let (tcp, _, _) = preamble.into_parts();
            return Some(TransferStream::Plain(tcp));
        }
    };
    if peer_is_fork {
        println!("Peer is running this fork; the file-conflict exchange is available");
    }

    // confirm that one end is sending and the other is receiving
    match confirm_mode(mode.clone(), is_wifi_client, &mut preamble, connection_mode).await {
        Ok(()) => (),
        Err(e) => {
            ui.output(&format!("Error confirming mode: {}", e));
            let (tcp, _, _) = preamble.into_parts();
            return Some(TransferStream::Plain(tcp));
        }
    };

    // Fork: between paired devices the hello goes here, still in the clear and still on
    // the recording stream — who is calling and under which key — so the far end can pick
    // the pair key before the handshake and say so in words if it cannot.
    if let Some(p) = &paired {
        let outcome = match noise_role {
            noise::Role::Initiator => {
                let identity = paired::HelloIdentity {
                    device_id: p.local_id,
                    os: presence::PeerOs::this_device(),
                };
                paired::send_hello(&mut preamble, &identity, &p.key, &p.peer_name)
                    .await
                    .map(|_| ())
            }
            noise::Role::Responder => {
                // The one key, accepted from whoever holds it: on a hotspot the peer was
                // chosen by hand on both ends, so there is nobody else it could be.
                let ring = pairing::KeyRing {
                    keys: vec![pairing::PairKey::pending(p.key)],
                };
                paired::receive_hello(&mut preamble, &ring).await.map(|_| ())
            }
        };
        if let Err(e) = outcome {
            ui.output(&format!("{}", e));
            let (tcp, _, _) = preamble.into_parts();
            return Some(TransferStream::Plain(tcp));
        }
    }

    // Now establish the Noise encrypted transport over the same connection, for both modes,
    // with the preamble transcript bound in as the prologue. Everything after this — file
    // count, metadata, and file data — is confidential and tamper-evident. A wrong password
    // (or a tampered preamble) fails the handshake with a clear message.
    let (tcp, sent, received) = preamble.into_parts();
    let prologue = match noise_role {
        noise::Role::Initiator => noise::build_prologue(&sent, &received),
        noise::Role::Responder => noise::build_prologue(&received, &sent),
    };
    ui.output("Establishing encrypted connection...");
    let mut stream = match noise::handshake(tcp, noise_role, &psk, &prologue).await {
        Ok(enc) => {
            ui.output("Encrypted connection established.");
            TransferStream::Encrypted(Box::new(enc))
        }
        Err(e) => {
            ui.output(&format!("{}", e));
            return None;
        }
    };

    match mode {
        Mode::Send(files) => {
            // tell receiving end how many files we're sending
            match stream.write_u64(files.len() as u64).await {
                Ok(()) => (),
                Err(e) => {
                    ui.output(&format!("Error writing number of files: {}", e));
                    return Some(stream);
                }
            }
            // total bytes are knowable here, so the overall bar can be byte-accurate when sending
            let total_bytes: u64 = files
                .iter()
                .map(|f| std::fs::metadata(&f.path).map(|m| m.len()).unwrap_or(0))
                .sum();
            let mut totals = Totals::new(files.len() as u64, Some(total_bytes));
            // "Apply to all", once ticked, for the rest of THIS transfer -- a local, so it cannot
            // outlive the loop and answer for a later one.
            let mut conflict_rule: Option<ConflictRule> = None;
            // send files. each file already carries the relative name the peer will store
            // it under, resolved at selection time by utils::expand_selection
            for (i, file) in files.iter().enumerate() {
                totals.file_index = (i + 1) as u64;
                ui.output("=========================");
                ui.output(&format!(
                    "Sending file {} of {}. Filename: {}",
                    i + 1,
                    files.len(),
                    file.name
                ));
                match sending::send_file(
                    &file.path,
                    &file.name,
                    &mut stream,
                    &mut totals,
                    ui,
                    peer_is_fork,
                    &mut conflict_rx,
                    &mut conflict_rule,
                    i + 1 < files.len(),
                )
                .await
                {
                    Ok(_) => (),
                    Err(e) => {
                        ui.output(&format!("Error sending file: {}", e));
                        return Some(stream);
                    }
                };
            }
        }
        Mode::Receive(folder) => {
            // find out how many files we're receiving
            let num_files = match stream.read_u64().await {
                Ok(num) => num,
                Err(e) => {
                    ui.output(&format!("Error reading number of files: {}", e));
                    return Some(stream);
                }
            };
            if num_files > MAX_FILE_COUNT {
                ui.output(&format!(
                    "Error: file count {} from peer is out of range",
                    num_files
                ));
                return Some(stream);
            }
            // no grand total on this side: sizes arrive one file at a time
            let mut totals = Totals::new(num_files, None);
            // receive files
            for i in 0..num_files {
                totals.file_index = i + 1;
                ui.output("=========================");
                ui.output(&format!("Receiving file {} of {}.", i + 1, num_files,));
                let last_file = i == num_files - 1;
                match receiving::receive_file(
                    &folder,
                    &mut stream,
                    &mut totals,
                    ui,
                    last_file,
                    peer_is_fork,
                )
                .await
                {
                    Ok(_) => (),
                    Err(e) => {
                        ui.output(&format!("Error receiving file: {}", e));
                        return Some(stream);
                    }
                }
            }
        }
    }

    ui.output("=========================");
    ui.output("Transfer complete");
    Some(stream)
}

pub async fn clean_up_transfer<T: UI>(
    stream: Option<TransferStream>,
    hotspot: Arc<Mutex<Option<PeerResource>>>,
    ssid: Arc<Mutex<Option<String>>>,
    ui: &T,
) {
    // shut down tcp stream
    match stream {
        Some(mut s) => {
            if s.shutdown().await.is_err() {
                ui.output("Failed to shut down TCP stream.")
            };
        }
        None => (),
    }
    // shut down hotspot
    shut_down_hotspot(&hotspot, &ssid, ui);
    // make sure hotspot gets dropped
    let mut hotspot_value = hotspot.lock().expect("Couldn't lock hotspot mutex");
    *hotspot_value = None;
    // enable UI
    ui.enable_ui();
}

fn shut_down_hotspot<T: UI>(
    hotspot: &Arc<Mutex<Option<PeerResource>>>,
    ssid: &Arc<Mutex<Option<String>>>,
    _ui: &T,
) {
    let peer_resource = hotspot.lock().expect("Couldn't lock hotspot mutex.");
    let peer_resource = peer_resource.as_ref();
    let ssid = ssid.lock().expect("Couldn't lock SSID mutex.");
    match network::stop_hotspot(peer_resource, ssid.as_deref()) {
        Err(e) => println!("{}", e),
        Ok(msg) => println!("{}", msg),
    };
}

async fn start_tcp<T: UI>(peer_resource: &PeerResource, ui: &T) -> Result<TcpStream, FCError> {
    let stream;
    match peer_resource {
        PeerResource::WifiClient(gateway) => {
            let addr = format!("{}:3290", gateway).parse::<SocketAddr>()?;
            stream = TcpStream::connect(addr).await?;
        }
        _ => {
            // linux or windows hotspot
            let addr = "0.0.0.0:3290".parse::<SocketAddr>()?;
            let listener = TcpListener::bind(&addr).await?;
            ui.output("Waiting for connection...");
            let (_stream, _socket_addr) = listener.accept().await?;
            ui.output("Connection accepted");
            stream = _stream;
        }
    }
    Ok(stream)
}

async fn start_shared_network_transfer<T: UI>(
    mode: &Mode,
    discovery_key: &[u8; 32],
    interface: &WiFiInterface,
    ui: &T,
) -> Result<(PeerResource, TcpStream), FCError> {
    // Check for network connection
    if !network::has_network_connection(interface)? {
        fc_error("No network connection on selected interface")?;
    }

    // Both roles need inbound traffic on port 3290: UDP for discovery announcements,
    // and TCP for the receiver's listener. No-op on Linux.
    network::ensure_firewall_rules(ui).await?;

    // Get local IP and prefix length
    let local_ip = network::get_local_ip(interface)?;
    let prefix_len = network::get_prefix_length(interface)?;
    ui.output(&format!("Local IP: {}/{}", local_ip, prefix_len));

    // Determine role for TCP connection
    let role = DiscoveryRole::from(mode);

    // Receiver is TCP server (consistent with hotspot same-platform convention
    // where the receiver hosts). Bind listener *before* discovery so it's ready
    // when the sender connects immediately after discovering us.
    let listener = if role == DiscoveryRole::Receiver {
        let addr = "0.0.0.0:3290".parse::<SocketAddr>()?;
        let listener = TcpListener::bind(&addr).await?;
        ui.output("TCP listener ready on port 3290.");
        Some(listener)
    } else {
        None
    };

    // Create discovery service
    let discovery = DiscoveryService::new(*discovery_key, mode, local_ip, prefix_len);

    let (peer_ip, stream) = match role {
        DiscoveryRole::Receiver => {
            // The sender discovers us and connects, and it stops announcing as soon as
            // it hears us — possibly before we ever hear it. So the TCP connection
            // itself is the receiver's completion signal: discovery runs alongside the
            // listener only to announce our presence and surface diagnostics
            // (receiver-role discovery never resolves with a peer), and must not gate
            // the accept. No timeout on the accept either: the sender may not be
            // started for a long time.
            let listener = listener.unwrap();
            tokio::select! {
                result = discovery.discover_peer(ui) => {
                    // only returns on failure or cancellation
                    result?;
                    fc_error("Discovery ended unexpectedly")?;
                    unreachable!()
                }
                accepted = listener.accept() => {
                    let (stream, addr) = accepted?;
                    ui.output(&format!("TCP connection accepted from {}", addr));
                    (addr.ip().to_string(), stream)
                }
            }
        }
        DiscoveryRole::Sender => {
            let peer_ip = discovery.discover_peer(ui).await?;

            // Sender connects to receiver
            ui.output(&format!("Connecting to peer at {}:3290", peer_ip));

            // Retry for up to 30 seconds (matches the Apple implementation): the
            // receiver may still be finishing discovery when we start connecting.
            const CONNECT_ATTEMPTS: u32 = 15;
            let mut stream = None;
            for attempt in 1..=CONNECT_ATTEMPTS {
                match TcpStream::connect(format!("{}:3290", peer_ip)).await {
                    Ok(s) => {
                        stream = Some(s);
                        break;
                    }
                    Err(e) => {
                        if attempt < CONNECT_ATTEMPTS {
                            ui.output(&format!(
                                "Connection attempt {} failed, retrying...",
                                attempt
                            ));
                            tokio::time::sleep(std::time::Duration::from_secs(2)).await;
                        } else {
                            fc_error(&format!("Failed to connect to peer: {}", e))?;
                        }
                    }
                }
            }

            (peer_ip.to_string(), stream.unwrap())
        }
    };

    ui.output("TCP connection established");
    Ok((PeerResource::WifiClient(peer_ip), stream))
}

// fork: pub(crate) so paired.rs can run the same preamble over its own connection.
pub(crate) async fn confirm_mode<S: AsyncRead + AsyncWrite + Unpin>(
    mode: Mode,
    is_wifi_client: bool,
    stream: &mut S,
    connection_mode: ConnectionMode,
) -> Result<(), FCError> {
    let our_mode: u64 = match mode {
        Mode::Send(..) => 1,
        Mode::Receive(..) => 0,
    };

    match connection_mode {
        ConnectionMode::SharedNetwork => {
            // Symmetric approach (matches Apple implementation):
            // Both sides send their mode, both sides read peer's mode, both verify opposite
            stream.write_u64(our_mode).await?;
            let peer_mode = stream.read_u64().await?;
            if peer_mode == our_mode {
                let msg = format!(
                    "Both ends of the transfer selected {}",
                    if our_mode == 0 { "receive" } else { "send" }
                );
                fc_error(&msg)?
            }
        }
        ConnectionMode::Hotspot => {
            // Asymmetric approach for backward compatibility with hotspot mode
            if is_wifi_client {
                // tell host what mode we selected and wait for confirmation that they don't match
                stream.write_u64(our_mode).await?;
                // wait to ensure host responds that mode selection was correct
                if stream.read_u64().await? != 1 {
                    let message = format!(
                        "Both ends of the transfer selected {}",
                        if our_mode == 0 { "receive" } else { "send" }
                    );
                    fc_error(&message)?
                }
            } else {
                // hosting: wait for guest to say what mode they selected, compare to our own, and report back
                let peer_mode = stream.read_u64().await?;
                if peer_mode == our_mode {
                    let msg = format!(
                        "Both ends of the transfer selected {}",
                        if our_mode == 0 { "receive" } else { "send" }
                    );
                    // write failure to guest
                    stream.write_u64(0).await?;
                    fc_error(&msg)?
                } else {
                    // write success to guest
                    stream.write_u64(1).await?;
                }
            }
        }
    }
    Ok(())
}

// fork: pub(crate) so paired.rs can run the same preamble over its own connection.
pub(crate) async fn confirm_version<S: AsyncRead + AsyncWrite + Unpin>(
    is_wifi_client: bool,
    stream: &mut S,
) -> Result<bool, FCError> {
    // only really have to worry about version 6 as that's the only one online and in app store. it will do mode confirmation first,
    // and obey hotspot host/guest rule, and it will write 0 or 1 for mode, so we shouldn't deadlock with both ends waiting.
    let peer_version = if is_wifi_client {
        // send version to hotspot host. in shared network mode both sides are wifi
        // clients, so both send first — symmetric, works via TCP buffering.
        stream.write_u64(WIRE_VERSION).await?;
        // receive version of host
        stream.read_u64().await?
    } else {
        // wait for guest to say what version they're using, then send our version
        let _peer_version = stream.read_u64().await?;
        stream.write_u64(WIRE_VERSION).await?;
        _peer_version
    };

    if peer_version < WIRE_VERSION {
        // we make decision
        if utils::is_compatible(peer_version) {
            stream.write_u64(1).await?; // report that versions are compatible
        } else {
            stream.write_u64(0).await?;
            fc_error(&format!("The other device is running 白い熊 魔法絨毯 version {}, which is not compatible with this version ({}). Please update both devices to the latest version at https://github.com/ShiroiKuma0/shiroikuma-mahojutan.", peer_version, MAJOR_VERSION))?;
        }
    } else if peer_version > WIRE_VERSION {
        // peer makes decision
        if stream.read_u64().await? == 0 {
            fc_error(&format!("The other device is running 白い熊 魔法絨毯 version {}, which is not compatible with this version ({}). Please update both devices to the latest version at https://github.com/ShiroiKuma0/shiroikuma-mahojutan.", peer_version, MAJOR_VERSION))?;
        }
    } // otherwise, versions match, implicitly compatible
    // A peer that announced a fork wire version understands the file-conflict exchange; a stock
    // one does not, and must be spoken to exactly as upstream does.
    Ok(peer_version >= FORK_WIRE_FLOOR)
}

// TODO:
// don't write ssid over bluetooth till hotspot has started, so that peer (especially iOS) doesn't start trying too early.
// test closing about window with x on linux: panic?
// https://github.com/hbldh/bleak/issues/367#issuecomment-784375835
// linux name is null on android when pairing - manufacturer info?
// windows cancellation is still slow in one spot: start_wifi_direct blocks on an untimed
//   std mpsc recv() waiting for the WiFiDirect callback, so an abort can't land until the
//   runtime answers. no subprocess involved, so run_command_async doesn't help; needs a
//   tokio channel or spawn_blocking.
// show qr code after refresh

// TESTS:
// test multiple transfers back to back, windows central unpaired but ios peripheral still paired, already paired but switched mode
// test switching os...
// fix tests
// test pulling wifi card, quitting program, etc.

// MYSTERIES
// "Corrupt JPEG data: 298 extraneous bytes before marker 0xbb" in debug output on windows
// how did windows read OS "windows" from itself when acting as central but not peripheral? windows previously wrote "windows" to the OS characteristic of android, which stored it? doesn't look like it from the android code.
// linux sending to linux: last file sent but then hung, didn't exit transfer. receiving end said "didn't receive confirmation".
// is the problem that the device we see advertising isn't the device we're already paired to? but then the device we're paired to presumably offers the services already.

// LATER MAYBE:
// code signing for windows?
// faster?
// cli version?
// move expand_files into utils and make tauri's version a wrapper for CLI version
// hosted network stuff on windows?
// send folder mode?
// recreate directory structure if all submitted files are in same dir. taken for granted in gui? only problem for cli? not if dropping appends... only allow when using send-folder?
// remove file selection box and replace start button with Choose Files/Choose Folder? gets in the way of drag and drop... so no?
// optional password length?
// move password length constant into rust, fetch in javascript

#[cfg(test)]
mod transfer_tests {
    use super::*;
    use crate::noise::{handshake, Role};

    // A UI that answers the file-conflict question the moment it is asked, the way the frontend
    // does: emit -> user -> channel.
    #[derive(Clone)]
    struct AnsweringUi {
        answer: FileConflictChoice,
        apply_to_all: bool,
        tx: mpsc::Sender<FileConflictAnswer>,
        // How many times the question was actually put. "Apply to all" is only worth anything if
        // this stops climbing.
        asked: Arc<Mutex<u32>>,
    }
    impl UI for AnsweringUi {
        fn ask_file_conflict(&self, _n: &str, _l: u64, _i: u64, _same: bool, _more: bool) {
            *self.asked.lock().expect("lock") += 1;
            let _ = self.tx.try_send(FileConflictAnswer {
                choice: self.answer.clone(),
                apply_to_all: self.apply_to_all,
            });
        }
        fn output(&self, _msg: &str) {}
        fn show_progress_bar(&self) {}
        fn update_progress_bar(&self, _percent: u8) {}
        fn update_total_progress_bar(&self, _percent: u8) {}
        fn update_progress_details(&self, _current: &str, _total: &str) {}
        fn enable_ui(&self) {}
        fn show_pin(&self, _pin: &str) {}
    }

    #[derive(Clone)]
    struct TestUi;
    impl UI for TestUi {
        fn ask_file_conflict(&self, _n: &str, _l: u64, _i: u64, _same: bool, _more: bool) {}
        fn output(&self, _msg: &str) {}
        fn show_progress_bar(&self) {}
        fn update_progress_bar(&self, _percent: u8) {}
        fn update_total_progress_bar(&self, _percent: u8) {}
        fn update_progress_details(&self, _current: &str, _total: &str) {}
        fn enable_ui(&self) {}
        fn show_pin(&self, _pin: &str) {}
    }


    /// The fork's file-conflict exchange, both halves, over a real duplex: the receiver already
    /// holds a file by that name, the sender is asked, and each of the three answers lands the
    /// way it should -- skipped, replaced, or written under a new name. This is a protocol test:
    /// it is the thing that would desynchronise a transfer if the two halves ever disagreed.
    #[tokio::test]
    async fn file_conflict_choices_are_obeyed() {
        for (answer, expect_original, expect_renamed) in [
            (FileConflictChoice::Skip, "old", None),
            (FileConflictChoice::Overwrite, "new", None),
            (
                FileConflictChoice::Rename("photo (copy).bin".to_string()),
                "old",
                Some("photo (copy).bin"),
            ),
        ] {
            let base = std::env::temp_dir().join(format!(
                "fc_conflict_{}_{:?}_{:?}",
                std::process::id(),
                std::thread::current().id(),
                std::mem::discriminant(&answer),
            ));
            let send_dir = base.join("send");
            let recv_dir = base.join("recv");
            let _ = std::fs::remove_dir_all(&base);
            std::fs::create_dir_all(&send_dir).unwrap();
            std::fs::create_dir_all(&recv_dir).unwrap();
            let src = send_dir.join("photo.bin");
            std::fs::write(&src, b"new").unwrap();
            // the receiving side already has a *different* file by that name
            std::fs::write(recv_dir.join("photo.bin"), b"old").unwrap();

            let (client, server) = tokio::io::duplex(64 * 1024);
            let (conflict_tx, mut conflict_rx) = mpsc::channel(1);
            // Answer when asked, never before: the sender deliberately drops anything already in
            // the channel when it puts a question, so that a late answer to a previous file
            // cannot be mistaken for this one's.
            let answering_ui = AnsweringUi {
                answer: answer.clone(),
                apply_to_all: false,
                tx: conflict_tx,
                asked: Arc::new(Mutex::new(0)),
            };

            let src2 = src.clone();
            let sender = tokio::spawn(async move {
                let mut stream = client;
                let mut totals = Totals::new(1, Some(3));
                sending::send_file(
                    &src2,
                    "photo.bin",
                    &mut stream,
                    &mut totals,
                    &answering_ui,
                    true,
                    &mut conflict_rx,
                    &mut None,
                    false,
                )
                .await
                .unwrap();
            });

            let recv_dir2 = recv_dir.clone();
            let receiver = tokio::spawn(async move {
                let mut stream = server;
                let mut totals = Totals::new(1, None);
                receiving::receive_file(&recv_dir2, &mut stream, &mut totals, &TestUi, true, true)
                    .await
                    .unwrap();
            });

            // Ten seconds is forever for three bytes; a hang here means the two halves of the
            // exchange disagree, which is exactly what this test is for.
            tokio::time::timeout(std::time::Duration::from_secs(10), sender)
                .await
                .expect("sender deadlocked")
                .unwrap();
            tokio::time::timeout(std::time::Duration::from_secs(10), receiver)
                .await
                .expect("receiver deadlocked")
                .unwrap();

            assert_eq!(
                std::fs::read_to_string(recv_dir.join("photo.bin")).unwrap(),
                expect_original,
                "original file after {:?}",
                answer
            );
            if let Some(renamed) = expect_renamed {
                assert_eq!(
                    std::fs::read_to_string(recv_dir.join(renamed)).unwrap(),
                    "new",
                    "renamed copy after {:?}",
                    answer
                );
            }
            let _ = std::fs::remove_dir_all(&base);
        }
    }

    /// "Apply to all", over the same real duplex: three files the receiver already has, one
    /// question, and the answer standing for the other two. Two things are asserted that nothing
    /// else would catch -- that the question is put exactly once, and that a sticky *rename* gives
    /// each file its own name instead of stacking all three onto the first one's.
    #[tokio::test]
    async fn apply_to_all_answers_the_rest() {
        for (answer, rule_name) in [
            (FileConflictChoice::Skip, "skip"),
            (FileConflictChoice::Overwrite, "overwrite"),
            // The name in the answer is deliberately not one of the three files': a sticky rename
            // must ignore it and derive a name per file.
            (FileConflictChoice::Rename("ignored.bin".to_string()), "rename"),
        ] {
            let base = std::env::temp_dir().join(format!(
                "fc_apply_all_{}_{:?}_{}",
                std::process::id(),
                std::thread::current().id(),
                rule_name,
            ));
            let send_dir = base.join("send");
            let recv_dir = base.join("recv");
            let _ = std::fs::remove_dir_all(&base);
            std::fs::create_dir_all(&send_dir).unwrap();
            std::fs::create_dir_all(&recv_dir).unwrap();
            let names = ["one.bin", "two.bin", "three.bin"];
            for name in names {
                std::fs::write(send_dir.join(name), b"new").unwrap();
                // every one of them is already there, with different contents
                std::fs::write(recv_dir.join(name), b"old").unwrap();
            }

            let (client, server) = tokio::io::duplex(64 * 1024);
            let (conflict_tx, mut conflict_rx) = mpsc::channel(1);
            let asked = Arc::new(Mutex::new(0));
            let answering_ui = AnsweringUi {
                answer: answer.clone(),
                apply_to_all: true,
                tx: conflict_tx,
                asked: asked.clone(),
            };

            let send_dir2 = send_dir.clone();
            let sender = tokio::spawn(async move {
                let mut stream = client;
                let mut totals = Totals::new(names.len() as u64, Some(9));
                let mut rule = None;
                for (i, name) in names.iter().enumerate() {
                    sending::send_file(
                        &send_dir2.join(name),
                        name,
                        &mut stream,
                        &mut totals,
                        &answering_ui,
                        true,
                        &mut conflict_rx,
                        &mut rule,
                        i + 1 < names.len(),
                    )
                    .await
                    .unwrap();
                }
            });

            let recv_dir2 = recv_dir.clone();
            let receiver = tokio::spawn(async move {
                let mut stream = server;
                let mut totals = Totals::new(names.len() as u64, None);
                for i in 0..names.len() {
                    receiving::receive_file(
                        &recv_dir2,
                        &mut stream,
                        &mut totals,
                        &TestUi,
                        i + 1 == names.len(),
                        true,
                    )
                    .await
                    .unwrap();
                }
            });

            tokio::time::timeout(std::time::Duration::from_secs(10), sender)
                .await
                .expect("sender deadlocked")
                .unwrap();
            tokio::time::timeout(std::time::Duration::from_secs(10), receiver)
                .await
                .expect("receiver deadlocked")
                .unwrap();

            assert_eq!(*asked.lock().unwrap(), 1, "questions put for {}", rule_name);
            for name in names {
                let original = std::fs::read_to_string(recv_dir.join(name)).unwrap();
                match rule_name {
                    // replaced in place
                    "overwrite" => assert_eq!(original, "new", "{} after overwrite all", name),
                    // untouched, and nothing new beside it
                    _ => assert_eq!(original, "old", "{} after {} all", name, rule_name),
                }
                let copy = recv_dir.join(utils::suggest_rename(name));
                if rule_name == "rename" {
                    assert_eq!(
                        std::fs::read_to_string(&copy).unwrap(),
                        "new",
                        "copy of {} after rename all",
                        name
                    );
                } else {
                    assert!(!copy.exists(), "unexpected copy of {} after {} all", name, rule_name);
                }
            }
            let _ = std::fs::remove_dir_all(&base);
        }
    }

    // End-to-end shared-network path: the real send_file/receive_file run over a Noise
    // EncryptedStream (backed by an in-memory duplex), with a >64 KiB file so the transfer
    // spans multiple Noise records. Verifies handshake, encrypted metadata, chunk transfer,
    // and byte-exact file integrity through the whole stack.
    #[tokio::test]
    async fn end_to_end_encrypted_transfer() {
        let base = std::env::temp_dir().join(format!("fc_noise_test_{}", std::process::id()));
        let send_dir = base.join("send");
        let recv_dir = base.join("recv");
        std::fs::create_dir_all(&send_dir).unwrap();
        std::fs::create_dir_all(&recv_dir).unwrap();
        let src = send_dir.join("photo.bin");
        let data: Vec<u8> = (0..200_000u32).map(|i| (i % 253) as u8).collect();
        std::fs::write(&src, &data).unwrap();

        let psk = noise::derive_psk("correct horse battery staple");
        // both sides bind the same preamble transcript, as the real flow does
        let prologue = noise::build_prologue(
            &[0, 0, 0, 0, 0, 0, 0, 10, 0, 0, 0, 0, 0, 0, 0, 1],
            &[0, 0, 0, 0, 0, 0, 0, 10, 0, 0, 0, 0, 0, 0, 0, 0],
        );
        let prologue2 = prologue.clone();

        let (a, b) = tokio::io::duplex(64 * 1024);
        let src2 = src.clone();
        let recv_dir2 = recv_dir.clone();

        let sender = tokio::spawn(async move {
            let mut enc = handshake(a, Role::Initiator, &psk, &prologue)
                .await
                .unwrap();
            enc.write_u64(1).await.unwrap(); // file count, as the orchestrator does
                                             // sent under a folder-relative name, as a "send folder" selection produces,
                                             // so the receiver's directory recreation is covered end to end
            let mut totals = Totals::new(1, None);
            totals.file_index = 1;
            let (_conflict_tx, mut conflict_rx) = mpsc::channel(1);
            sending::send_file(
                &src2,
                "album/photo.bin",
                &mut enc,
                &mut totals,
                &TestUi,
                false,
                &mut conflict_rx,
                &mut None,
                false,
            )
                .await
                .unwrap();
            enc.flush().await.unwrap();
        });
        let receiver = tokio::spawn(async move {
            let mut enc = handshake(b, Role::Responder, &psk, &prologue2)
                .await
                .unwrap();
            let count = enc.read_u64().await.unwrap();
            assert_eq!(count, 1);
            let mut totals = Totals::new(1, None);
            totals.file_index = 1;
            receiving::receive_file(&recv_dir2, &mut enc, &mut totals, &TestUi, true, false)
                .await
                .unwrap();
        });
        sender.await.unwrap();
        receiver.await.unwrap();

        let got = std::fs::read(recv_dir.join("album").join("photo.bin")).unwrap();
        assert_eq!(
            got, data,
            "received file must match sent file byte-for-byte"
        );

        let _ = std::fs::remove_dir_all(&base);
    }
}
