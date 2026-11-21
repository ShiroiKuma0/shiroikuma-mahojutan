// Fork: a transfer between two devices that already know each other.
//
// Everything below the Noise handshake is upstream's, unchanged — the same preamble, the
// same `sending::send_file` and `receiving::receive_file`, the same conflict exchange. What
// changes is only what happens *before*:
//
//   stock shared network        paired
//   -------------------------   -----------------------------------------------
//   generate a password         nothing; the pair key was agreed once, at pairing
//   show it / hand it over BLE  nothing
//   arm both devices            arm neither; the receiver has been listening all along
//   discover by role            connect straight to a known address
//   PBKDF2(password) → PSK      HMAC(pairKey) → PSK, no stretching needed
//
// It deliberately does NOT add a `ConnectionMode` variant. A paired transfer is symmetric
// in exactly the way `ConnectionMode::SharedNetwork` already is — both ends are clients,
// both write their mode and read the peer's — so it reuses that branch of `confirm_mode`
// and adds no arm to any of the three places that match on connection mode. One less thing
// for an upstream rebase to collide with.
//
// Two additions to the wire, both spoken only between paired devices:
//
//   * the **hello**, in the clear between the preamble and the Noise handshake: who is
//     calling and under which key. Keys are pairwise, so the responder has to know which
//     of its keys to run the handshake with *before* the handshake — and the hello is also
//     where "we are not paired" and "we hold different keys" become sentences instead of a
//     bare handshake failure. Every byte of it is recorded into the Noise prologue, so it
//     cannot be tampered with any more than the preamble can.
//   * the **offer**, inside Noise before the file count: what the caller wants to send. It
//     exists because an unattended receiver has to be able to say no, and to name the
//     sender in the notification it posts.

use crate::error::{fc_error, FCError};
use crate::pairing::{clamp_name, key_id, KeyRing, PairKey};
use crate::presence::{PeerOs, PRESENCE_PORT};
use crate::{
    confirm_mode, confirm_version, noise, receiving, sending, ConflictRule, ConnectionMode,
    FileConflictAnswer, Mode, SendFile, Totals, TransferStream, UI,
};
use socket2::{SockRef, TcpKeepalive};
use std::net::{IpAddr, SocketAddr};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::mpsc;

/// Version 2 adds `request`, which is what lets a sender ask a paired device to raise a
/// hotspot instead of taking files down this connection. Bumped rather than sneaked in: the
/// receiver rejects an unknown version loudly, which is a far better failure than a field
/// silently read out of the wrong offset.
pub const OFFER_VERSION: u64 = 2;

/// What the caller wants. The connection is the same either way; only what happens after the
/// offer differs.
pub const REQUEST_TRANSFER: u64 = 0;
/// "Raise your hotspot and listen on it — I will leave this network and join you." The reply
/// is the ordinary accept/refuse, so a device that cannot host says so in the usual way.
pub const REQUEST_RAISE_HOTSPOT: u64 = 1;
/// "What does your Wi-Fi link look like?" Answered with `linkstate::LinkState::to_wire`. Asked
/// by the phone a few seconds into a transfer, because a narrow hop between the access point
/// and this device is something only this device's radio can see (白い熊, 2026-11-21).
pub const REQUEST_LINK_STATUS: u64 = 2;
/// "Reconnect your Wi-Fi." The reply goes out first; the reconnect follows it, since it takes
/// this very connection down.
pub const REQUEST_REASSOCIATE: u64 = 3;
/// "Send me again what you were sending when the transfer was cut." The far end of the
/// narrow-channel dialog's "Restart the transfer", for the direction the phone cannot restart
/// by itself.
pub const REQUEST_RESEND: u64 = 4;

/// The requests that move no files: a question, an answer, and the connection is done.
pub fn is_control(request: u64) -> bool {
    matches!(request, REQUEST_LINK_STATUS | REQUEST_REASSOCIATE | REQUEST_RESEND)
}

/// The hello's own version, separate from the offer's: the two live on opposite sides of
/// the handshake and change for different reasons.
pub const HELLO_VERSION: u64 = 1;
/// The responder's answers to a hello.
pub const HELLO_PROCEED: u64 = 1;
/// "I hold no key for you and no unclaimed code with that id." Pair the two.
pub const HELLO_UNKNOWN: u64 = 2;
/// "I know you, but under a different key." One side re-paired; pair them again.
pub const HELLO_KEY_MISMATCH: u64 = 3;

/// Same bound the filename length uses in receiving.rs, and for the same reason: an
/// unbounded length prefix is a memory-exhaustion lever, so it is refused before anything
/// is allocated from it. The offer is inside Noise by this point, so this guards against a
/// bug rather than an attacker — but a bug that allocates 2^64 bytes is still a crash.
const MAX_OFFER_FIELD_BYTES: u64 = 8192;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);

/// What the sender says about itself and the transfer, before a byte of file data moves.
#[derive(Clone, Debug)]
pub struct PairedOffer {
    /// REQUEST_TRANSFER or REQUEST_RAISE_HOTSPOT.
    pub request: u64,
    pub device_id: [u8; 16],
    pub name: String,
    pub file_count: u64,
    pub total_bytes: u64,
    /// The first file's name, so a notification can say "photo.jpg and 4 more" rather than
    /// "5 files".
    pub first_name: String,
}

/// Why an offer was refused. The sender is told which, so its message can be useful rather
/// than just "refused".
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Refusal {
    /// This device cannot take another transfer right now: every lane is running, or a
    /// hotspot or password-mode transfer has the radio.
    Busy = 2,
    /// The peer completed the handshake, so it holds the group key, but the user has not
    /// granted it unattended acceptance.
    NotAllowed = 3,
    /// Bigger than the unattended ceiling. Not a rejection of the peer.
    TooLarge = 4,
    /// No destination has been chosen on this device yet.
    NoDestination = 5,
}

impl Refusal {
    fn message(&self) -> &'static str {
        match self {
            Refusal::Busy => "The other device is busy with another transfer.",
            Refusal::NotAllowed => {
                "The other device is not set to accept transfers from this one without asking."
            }
            Refusal::TooLarge => {
                "The other device does not accept a transfer this large without asking."
            }
            Refusal::NoDestination => {
                "The other device has not been given a directory to receive into yet."
            }
        }
    }

    fn from_code(code: u64) -> Option<Refusal> {
        match code {
            2 => Some(Refusal::Busy),
            3 => Some(Refusal::NotAllowed),
            4 => Some(Refusal::TooLarge),
            5 => Some(Refusal::NoDestination),
            _ => None,
        }
    }
}

/// What a receiver decides about one offer.
pub enum Verdict {
    Accept(PathBuf),
    Refuse(Refusal),
}

async fn write_bytes<S: AsyncWriteExt + Unpin>(stream: &mut S, data: &[u8]) -> Result<(), FCError> {
    stream.write_u64(data.len() as u64).await?;
    stream.write_all(data).await?;
    Ok(())
}

async fn read_bytes<S: AsyncReadExt + Unpin>(stream: &mut S) -> Result<Vec<u8>, FCError> {
    let len = stream.read_u64().await?;
    if len > MAX_OFFER_FIELD_BYTES {
        fc_error(&format!("Offer field length {} is out of range", len))?;
    }
    let mut buf = vec![0u8; len as usize];
    stream.read_exact(&mut buf).await?;
    Ok(buf)
}

async fn write_offer(
    stream: &mut TransferStream,
    offer: &PairedOffer,
) -> Result<(), FCError> {
    stream.write_u64(OFFER_VERSION).await?;
    stream.write_u64(offer.request).await?;
    write_bytes(stream, &offer.device_id).await?;
    write_bytes(stream, clamp_name(&offer.name).as_bytes()).await?;
    stream.write_u64(offer.file_count).await?;
    stream.write_u64(offer.total_bytes).await?;
    write_bytes(stream, offer.first_name.as_bytes()).await?;
    Ok(())
}

async fn read_offer(stream: &mut TransferStream) -> Result<PairedOffer, FCError> {
    let version = stream.read_u64().await?;
    if version != OFFER_VERSION {
        fc_error(&format!(
            "The other device sent an offer of version {}, which this version does not understand. Update both devices.",
            version
        ))?;
    }
    let request = stream.read_u64().await?;
    let id_bytes = read_bytes(stream).await?;
    if id_bytes.len() != 16 {
        fc_error("The other device sent a malformed identity")?;
    }
    let mut device_id = [0u8; 16];
    device_id.copy_from_slice(&id_bytes);
    let name = String::from_utf8(read_bytes(stream).await?)?;
    let file_count = stream.read_u64().await?;
    let total_bytes = stream.read_u64().await?;
    let first_name = String::from_utf8(read_bytes(stream).await?)?;
    Ok(PairedOffer {
        request,
        device_id,
        name,
        file_count,
        total_bytes,
        first_name,
    })
}

/// Who this end is, for the hello.
#[derive(Clone, Debug)]
pub struct HelloIdentity {
    pub device_id: [u8; 16],
    pub os: PeerOs,
}

/// What the responder learned from a hello it accepted: whom it is talking to and under
/// which key. `key.is_pending()` means the caller is claiming a code this device showed,
/// and the caller becomes a peer once the handshake proves the claim.
#[derive(Clone, Debug)]
pub struct Caller {
    pub device_id: [u8; 16],
    pub os: PeerOs,
    pub key: PairKey,
}

/// The initiator's half: say who we are and which key we mean, and hear whether the other
/// end can go on. Run on the recording stream so it lands in the prologue.
pub async fn send_hello<S: AsyncReadExt + AsyncWriteExt + Unpin>(
    stream: &mut S,
    identity: &HelloIdentity,
    key: &[u8; 32],
    peer_name: &str,
) -> Result<(), FCError> {
    stream.write_u64(HELLO_VERSION).await?;
    write_bytes(stream, &identity.device_id).await?;
    write_bytes(stream, &key_id(key)).await?;
    stream.write_u64(identity.os as u64).await?;
    stream.flush().await?;
    match stream.read_u64().await? {
        HELLO_PROCEED => Ok(()),
        HELLO_UNKNOWN => {
            fc_error(&format!(
                "{} is not paired with this device. Pair the two — show the code on either \
                 one and scan or type it on the other.",
                peer_name
            ))?;
            unreachable!()
        }
        HELLO_KEY_MISMATCH => {
            fc_error(&format!(
                "{} knows this device under a different pairing key — one of the two was \
                 paired again since. Pair them once more, from either side.",
                peer_name
            ))?;
            unreachable!()
        }
        other => {
            fc_error(&format!("{} answered the hello with {}", peer_name, other))?;
            unreachable!()
        }
    }
}

/// The responder's half: read who is calling, find the key, and say whether to go on. A
/// caller we cannot place is told so and the connection ends there — before the handshake,
/// so the sender's user reads a sentence rather than a handshake failure.
pub async fn receive_hello<S: AsyncReadExt + AsyncWriteExt + Unpin>(
    stream: &mut S,
    ring: &KeyRing,
) -> Result<Caller, FCError> {
    let version = stream.read_u64().await?;
    if version != HELLO_VERSION {
        fc_error(&format!(
            "The other device sent a hello of version {}, which this version does not understand. Update both devices.",
            version
        ))?;
    }
    let id_bytes = read_bytes(stream).await?;
    if id_bytes.len() != 16 {
        fc_error("The other device sent a malformed identity")?;
    }
    let mut device_id = [0u8; 16];
    device_id.copy_from_slice(&id_bytes);
    let key_bytes = read_bytes(stream).await?;
    if key_bytes.len() != 4 {
        fc_error("The other device sent a malformed key id")?;
    }
    let mut wanted = [0u8; 4];
    wanted.copy_from_slice(&key_bytes);
    let os = PeerOs::from_byte(stream.read_u64().await? as u8).unwrap_or(PeerOs::Android);

    let candidates = ring.candidates(&device_id, &wanted);
    // A peer's own key first, then an unclaimed code: a device we know does not get to
    // spend a code it did not need.
    let chosen = candidates
        .iter()
        .find(|k| !k.is_pending())
        .or_else(|| candidates.first())
        .map(|k| (*k).clone());
    match chosen {
        Some(key) => {
            stream.write_u64(HELLO_PROCEED).await?;
            stream.flush().await?;
            Ok(Caller { device_id, os, key })
        }
        None => {
            let known = ring.for_device(&device_id).is_some();
            stream
                .write_u64(if known { HELLO_KEY_MISMATCH } else { HELLO_UNKNOWN })
                .await?;
            stream.flush().await?;
            fc_error(if known {
                "A paired device called under a different key than the one stored for it; it needs pairing again."
            } else {
                "A device that is not paired with this one tried to connect."
            })?;
            unreachable!()
        }
    }
}

/// The bits of a connection that are the same either way. Split out so the sending and
/// receiving halves cannot drift apart in their socket options — a mismatch there is the
/// kind of thing that shows up months later as "it is slow in one direction".
fn prepare_socket<T: UI>(tcp: &TcpStream, ui: &T) {
    if let Err(e) = tcp.set_nodelay(true) {
        ui.output(&format!("Couldn't disable Nagle on the connection: {}", e));
    }
    let keepalive = TcpKeepalive::new()
        .with_time(Duration::from_secs(10))
        .with_interval(Duration::from_secs(5))
        .with_retries(3);
    if let Err(e) = SockRef::from(tcp).set_tcp_keepalive(&keepalive) {
        ui.output(&format!("Couldn't enable TCP keepalive: {}", e));
    }
}

/// Which end of the hello this side speaks.
enum HelloSide<'a> {
    Initiator {
        identity: &'a HelloIdentity,
        key: &'a [u8; 32],
        peer_name: &'a str,
    },
    Responder {
        ring: &'a KeyRing,
    },
}

/// Runs the preamble, the hello and the Noise handshake. Both sides pass
/// `is_wifi_client = true`, which is what shared network mode already does: neither end is
/// hosting anything, so the exchange is symmetric and TCP buffering keeps two simultaneous
/// writes from deadlocking. Returns the caller on the responding side.
async fn handshake<T: UI>(
    tcp: TcpStream,
    mode: &Mode,
    side: HelloSide<'_>,
    ui: &T,
) -> Result<(TransferStream, bool, Option<Caller>), FCError> {
    let mut preamble = noise::RecordingStream::new(tcp);
    let peer_is_fork = confirm_version(true, &mut preamble).await?;
    confirm_mode(
        mode.clone(),
        true,
        &mut preamble,
        ConnectionMode::SharedNetwork,
    )
    .await?;
    let (role, psk, caller) = match side {
        HelloSide::Initiator {
            identity,
            key,
            peer_name,
        } => {
            send_hello(&mut preamble, identity, key, peer_name).await?;
            (noise::Role::Initiator, noise::derive_paired_psk(key), None)
        }
        HelloSide::Responder { ring } => {
            let caller = receive_hello(&mut preamble, ring).await?;
            let psk = noise::derive_paired_psk(&caller.key.key);
            (noise::Role::Responder, psk, Some(caller))
        }
    };
    let (tcp, sent, received) = preamble.into_parts();
    let prologue = match role {
        noise::Role::Initiator => noise::build_prologue(&sent, &received),
        noise::Role::Responder => noise::build_prologue(&received, &sent),
    };
    let encrypted = noise::handshake(tcp, role, &psk, &prologue).await?;
    ui.output("Encrypted connection established.");
    Ok((
        TransferStream::Encrypted(Box::new(encrypted)),
        peer_is_fork,
        caller,
    ))
}

/// Sends to a paired device at a known address. No discovery, no password, no arming on the
/// far side — this is the whole point of the feature.
///
/// `key` is the pair key shared with that one device; completing the handshake under
/// `derive_paired_psk(key)` is what proves both ends hold it, so there is no separate
/// authentication step.
#[allow(clippy::too_many_arguments)]
pub async fn send_to_peer<T: UI>(
    peer_ip: IpAddr,
    port: u16,
    key: &[u8; 32],
    peer_name: &str,
    identity_id: [u8; 16],
    identity_name: &str,
    files: &[SendFile],
    ui: &T,
    conflict_rx: &mut mpsc::Receiver<FileConflictAnswer>,
) -> Result<(), FCError> {
    if files.is_empty() {
        fc_error("Nothing selected to send.")?;
    }
    let addr = SocketAddr::new(peer_ip, port);
    ui.output(&format!("Connecting to {}...", addr));
    let tcp = match tokio::time::timeout(CONNECT_TIMEOUT, TcpStream::connect(addr)).await {
        Ok(Ok(tcp)) => tcp,
        Ok(Err(e)) => {
            fc_error(&format!("Could not reach {}: {}", addr, e))?;
            unreachable!()
        }
        Err(_) => {
            fc_error(&format!(
                "{} did not answer within {} seconds. It may have a new address, or be asleep.",
                addr,
                CONNECT_TIMEOUT.as_secs()
            ))?;
            unreachable!()
        }
    };
    prepare_socket(&tcp, ui);

    let mode = Mode::Send(files.to_vec());
    let identity = HelloIdentity {
        device_id: identity_id,
        os: PeerOs::this_device(),
    };
    let (mut stream, peer_is_fork, _) = handshake(
        tcp,
        &mode,
        HelloSide::Initiator {
            identity: &identity,
            key,
            peer_name,
        },
        ui,
    )
    .await?;

    let total_bytes: u64 = files
        .iter()
        .map(|f| std::fs::metadata(&f.path).map(|m| m.len()).unwrap_or(0))
        .sum();
    let offer = PairedOffer {
        request: REQUEST_TRANSFER,
        device_id: identity_id,
        name: identity_name.to_string(),
        file_count: files.len() as u64,
        total_bytes,
        first_name: files[0].name.clone(),
    };
    write_offer(&mut stream, &offer).await?;

    match stream.read_u64().await? {
        1 => (),
        code => {
            let refusal = Refusal::from_code(code);
            fc_error(
                refusal
                    .map(|r| r.message())
                    .unwrap_or("The other device refused the transfer."),
            )?;
        }
    }
    ui.output("The other device accepted. Sending...");

    stream.write_u64(files.len() as u64).await?;
    let mut totals = Totals::new(files.len() as u64, Some(total_bytes));
    let mut conflict_rule: Option<ConflictRule> = None;
    for (i, file) in files.iter().enumerate() {
        totals.file_index = (i + 1) as u64;
        ui.output("=========================");
        ui.output(&format!(
            "Sending file {} of {}. Filename: {}",
            i + 1,
            files.len(),
            file.name
        ));
        sending::send_file(
            &file.path,
            &file.name,
            &mut stream,
            &mut totals,
            ui,
            peer_is_fork,
            conflict_rx,
            &mut conflict_rule,
            i + 1 < files.len(),
        )
        .await?;
    }
    ui.output("=========================");
    ui.output("Transfer complete");
    Ok(())
}

/// Asks a paired device one control question and returns its answer. The same connection a
/// send opens — preamble, hello, handshake, offer — with a request in the offer instead of
/// files. The desktop never needs to ask a phone anything yet; this is the calling half the
/// tests drive, and the reference for the one in Paired.kt.
#[allow(clippy::too_many_arguments)]
pub async fn request_control<T: UI>(
    peer_ip: IpAddr,
    port: u16,
    key: &[u8; 32],
    peer_name: &str,
    identity_id: [u8; 16],
    identity_name: &str,
    request: u64,
    ui: &T,
) -> Result<String, FCError> {
    let addr = SocketAddr::new(peer_ip, port);
    let tcp = match tokio::time::timeout(CONNECT_TIMEOUT, TcpStream::connect(addr)).await {
        Ok(Ok(tcp)) => tcp,
        _ => {
            fc_error(&format!("Could not reach {}", addr))?;
            unreachable!()
        }
    };
    let identity = HelloIdentity {
        device_id: identity_id,
        os: PeerOs::this_device(),
    };
    let held = Held::new(ui);
    let (mut stream, _, _) = handshake(
        tcp,
        &Mode::Send(Vec::new()),
        HelloSide::Initiator {
            identity: &identity,
            key,
            peer_name,
        },
        &held,
    )
    .await?;
    let offer = PairedOffer {
        request,
        device_id: identity_id,
        name: identity_name.to_string(),
        file_count: 0,
        total_bytes: 0,
        first_name: String::new(),
    };
    write_offer(&mut stream, &offer).await?;
    stream.flush().await?;
    match stream.read_u64().await? {
        1 => Ok(String::from_utf8(read_bytes(&mut stream).await?)?),
        _ => {
            fc_error("The other device does not answer that request.")?;
            unreachable!()
        }
    }
}

/// A voice that holds its lines until it is known whether anybody should hear them.
///
/// A control request arrives every few seconds while a transfer runs, and each one would
/// otherwise write "Encrypted connection established." into the log for a connection that
/// moves nothing. What it is cannot be known before the offer is read, so the lines wait:
/// dropped for a control request, said in order for everything else — including every way
/// the connection can fail before its offer, which is where a stranger's attempt shows up.
#[derive(Clone)]
struct Held<T: UI> {
    inner: T,
    lines: Arc<std::sync::Mutex<Option<Vec<String>>>>,
}

impl<T: UI> Held<T> {
    fn new(inner: &T) -> Self {
        Held {
            inner: inner.clone(),
            lines: Arc::new(std::sync::Mutex::new(Some(Vec::new()))),
        }
    }

    /// Stops holding. `say` decides whether what was held is said or dropped.
    fn release(&self, say: bool) {
        let held = self.lines.lock().unwrap_or_else(|e| e.into_inner()).take();
        if say {
            for line in held.unwrap_or_default() {
                self.inner.output(&line);
            }
        }
    }
}

impl<T: UI> UI for Held<T> {
    fn output(&self, msg: &str) {
        let mut lines = self.lines.lock().unwrap_or_else(|e| e.into_inner());
        match lines.as_mut() {
            Some(held) => held.push(msg.to_string()),
            None => {
                drop(lines);
                self.inner.output(msg)
            }
        }
    }
    fn show_progress_bar(&self) {
        self.inner.show_progress_bar()
    }
    fn update_progress_bar(&self, percent: u8) {
        self.inner.update_progress_bar(percent)
    }
    fn update_total_progress_bar(&self, percent: u8) {
        self.inner.update_total_progress_bar(percent)
    }
    fn update_progress_details(&self, current: &str, total: &str) {
        self.inner.update_progress_details(current, total)
    }
    fn enable_ui(&self) {
        self.inner.enable_ui()
    }
    fn show_pin(&self, pin: &str) {
        self.inner.show_pin(pin)
    }
    fn ask_file_conflict(
        &self,
        name: &str,
        local_size: u64,
        incoming_size: u64,
        identical: bool,
        more_files: bool,
    ) {
        self.inner
            .ask_file_conflict(name, local_size, incoming_size, identical, more_files)
    }
}

/// `serve_one_with_control` for a caller that answers no control requests: they are refused.
pub async fn serve_one<T: UI, D, C>(
    tcp: TcpStream,
    ring: &KeyRing,
    ui: &T,
    decide: &D,
    claimed: &C,
) -> Result<(), FCError>
where
    D: Fn(&PairedOffer) -> Verdict,
    C: Fn(&Caller, &str),
{
    serve_one_with_control(tcp, ring, ui, decide, claimed, &|_: &PairedOffer, _: IpAddr| None).await
}

/// Handles one accepted connection, start to finish. Public because the accept loop no
/// longer runs it: each connection is a lane of its own (see lanes.rs), spawned by whoever
/// called `serve`, so a long transfer from one device does not hold the door shut against
/// every other. A peer that misbehaves during its own session ends that session and
/// nothing else.
///
/// `control` answers the requests that move no files (`is_control`). It is handed the offer
/// and this end's address on the connection — which is what says which interface the caller
/// is asking about — and returns the reply text, or None to refuse. Anything it has to do
/// *after* the reply has gone out, it arranges for itself.
pub async fn serve_one_with_control<T: UI, D, C, K>(
    tcp: TcpStream,
    ring: &KeyRing,
    ui: &T,
    decide: &D,
    claimed: &C,
    control: &K,
) -> Result<(), FCError>
where
    D: Fn(&PairedOffer) -> Verdict,
    C: Fn(&Caller, &str),
    K: Fn(&PairedOffer, IpAddr) -> Option<String>,
{
    let outer = ui;
    let held = Held::new(outer);
    let ui = &held;
    let local_ip = tcp.local_addr().map(|a| a.ip());
    prepare_socket(&tcp, ui);
    // The destination is not known until the offer has been read, and Mode::Receive needs
    // one to exist for the preamble. It is replaced by the decision's own path before a
    // single file is written, so this placeholder never reaches the filesystem.
    let mode = Mode::Receive(PathBuf::new());
    let opened = async {
        let (mut stream, peer_is_fork, caller) =
            handshake(tcp, &mode, HelloSide::Responder { ring }, ui).await?;
        let offer = read_offer(&mut stream).await?;
        Ok::<_, FCError>((stream, peer_is_fork, caller, offer))
    }
    .await;
    let (mut stream, peer_is_fork, caller, offer) = match opened {
        Ok(opened) => opened,
        Err(e) => {
            held.release(true);
            return Err(e);
        }
    };
    held.release(!is_control(offer.request));
    if is_control(offer.request) {
        // Asked under a key the handshake has just proved, like everything else here; a
        // device still claiming a code has no business asking, so it is not recorded.
        let reply = match local_ip {
            Ok(ip) => control(&offer, ip),
            Err(_) => None,
        };
        match reply {
            Some(text) => {
                stream.write_u64(1).await?;
                write_bytes(&mut stream, text.as_bytes()).await?;
            }
            None => stream.write_u64(Refusal::NotAllowed as u64).await?,
        }
        stream.flush().await?;
        return Ok(());
    }
    // The handshake has proved the caller holds the key it named. If that key was a code
    // this device showed, the caller is the device it was shown to: record it now, with
    // the name the offer carries, before anything is decided about the offer itself.
    if let Some(caller) = caller {
        claimed(&caller, &offer.name);
    }
    if offer.request == REQUEST_RAISE_HOTSPOT {
        // Never expected here: `is_hosting` already gives the phone the hosting role against
        // a Linux peer, so nothing should ask this one to raise an access point. Refused in
        // the ordinary way rather than ignored, so the caller hears a sentence.
        stream.write_u64(Refusal::NotAllowed as u64).await?;
        ui.output(&format!(
            "{} asked this device to raise a hotspot; only the phone hosts in this pairing.",
            offer.name
        ));
        return Ok(());
    }
    if offer.request != REQUEST_TRANSFER {
        // A request from a newer build than this one. Refused in words rather than taken
        // for a transfer of no files.
        stream.write_u64(Refusal::NotAllowed as u64).await?;
        return Ok(());
    }
    let folder = match decide(&offer) {
        Verdict::Accept(folder) => {
            stream.write_u64(1).await?;
            folder
        }
        Verdict::Refuse(reason) => {
            stream.write_u64(reason as u64).await?;
            ui.output(&format!(
                "Refused a transfer from {}: {}",
                offer.name,
                reason.message()
            ));
            return Ok(());
        }
    };

    ui.output("=========================");
    ui.output(&format!(
        "{} is sending {} file{}.",
        if offer.name.is_empty() {
            "A paired device".to_string()
        } else {
            offer.name.clone()
        },
        offer.file_count,
        if offer.file_count == 1 { "" } else { "s" }
    ));

    let num_files = stream.read_u64().await?;
    if num_files > crate::MAX_FILE_COUNT {
        fc_error(&format!(
            "Error: file count {} from peer is out of range",
            num_files
        ))?;
    }
    let mut totals = Totals::new(num_files, Some(offer.total_bytes));
    for i in 0..num_files {
        totals.file_index = i + 1;
        ui.output("=========================");
        ui.output(&format!("Receiving file {} of {}.", i + 1, num_files));
        receiving::receive_file(
            &folder,
            &mut stream,
            &mut totals,
            ui,
            i == num_files - 1,
            peer_is_fork,
        )
        .await?;
    }
    ui.output("=========================");
    ui.output("Transfer complete");
    Ok(())
}

/// The standing receiver. Binds the presence TCP port and hands every connection to
/// `on_connection` until cancelled.
///
/// It never returns on its own: like the shared-network receiver, there is no timeout,
/// because the other device may not be started for hours.
///
/// It only accepts. `on_connection` is expected to spawn — typically `serve_one` on a task
/// of its own — and return at once, so that a transfer in progress never stops the next
/// device from getting through. This loop used to await each session in turn, which is
/// what made a second sender wait on a connect that was accepted by nobody.
pub async fn serve<T: UI, H>(
    ui: &T,
    cancel: Arc<AtomicBool>,
    on_connection: H,
) -> Result<(), FCError>
where
    H: Fn(TcpStream, SocketAddr),
{
    let listener = TcpListener::bind(SocketAddr::from(([0, 0, 0, 0], PRESENCE_PORT))).await?;
    ui.output(&format!(
        "Listening for paired devices on port {}.",
        PRESENCE_PORT
    ));
    loop {
        if cancel.load(Ordering::SeqCst) {
            return Ok(());
        }
        let accepted =
            match tokio::time::timeout(Duration::from_millis(250), listener.accept()).await {
                Ok(Ok(pair)) => pair,
                Ok(Err(e)) => {
                    // A connection that died between the SYN and the accept, typically.
                    // Never a reason to stop listening.
                    println!("[Paired] accept error: {}", e);
                    continue;
                }
                Err(_) => continue,
            };
        let (tcp, addr) = accepted;
        on_connection(tcp, addr);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    #[derive(Clone)]
    struct TestUi(Arc<Mutex<Vec<String>>>);

    impl UI for TestUi {
        fn output(&self, msg: &str) {
            self.0.lock().unwrap().push(msg.to_string());
        }
        fn show_progress_bar(&self) {}
        fn update_progress_bar(&self, _: u8) {}
        fn update_total_progress_bar(&self, _: u8) {}
        fn update_progress_details(&self, _: &str, _: &str) {}
        fn enable_ui(&self) {}
        fn show_pin(&self, _: &str) {}
        fn ask_file_conflict(&self, _: &str, _: u64, _: u64, _: bool, _: bool) {}
    }

    fn test_ui() -> TestUi {
        TestUi(Arc::new(Mutex::new(Vec::new())))
    }

    struct Fixture {
        _dir: std::path::PathBuf,
        source: std::path::PathBuf,
        dest: std::path::PathBuf,
    }

    fn fixture(tag: &str, contents: &[u8]) -> Fixture {
        let dir = std::env::temp_dir().join(format!("mahojutan-paired-test-{}", tag));
        let _ = std::fs::remove_dir_all(&dir);
        let source = dir.join("src");
        let dest = dir.join("dest");
        std::fs::create_dir_all(&source).unwrap();
        std::fs::create_dir_all(&dest).unwrap();
        std::fs::write(source.join("hello.txt"), contents).unwrap();
        Fixture {
            _dir: dir,
            source,
            dest,
        }
    }

    const SENDER_ID: [u8; 16] = [9u8; 16];

    fn ring_with(keys: Vec<PairKey>) -> KeyRing {
        KeyRing { keys }
    }

    /// The whole point of the feature, end to end: a listener that nobody armed, a sender
    /// that was told nothing but an address, and a file that arrives.
    #[tokio::test]
    async fn a_paired_send_needs_nothing_on_the_receiving_side() {
        let f = fixture("accept", b"one tap");
        let key = [0x11u8; 32];
        let ui = test_ui();
        let cancel = Arc::new(AtomicBool::new(false));

        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let dest = f.dest.clone();
        let server_ui = ui.clone();
        let server = tokio::spawn(async move {
            let (tcp, _) = listener.accept().await.unwrap();
            let ring = ring_with(vec![PairKey::bound(key, SENDER_ID)]);
            serve_one(
                tcp,
                &ring,
                &server_ui,
                &move |_offer: &PairedOffer| Verdict::Accept(dest.clone()),
                &|_: &Caller, _: &str| {},
            )
            .await
        });

        let (_tx, mut conflict_rx) = mpsc::channel(1);
        let files = vec![SendFile {
            path: f.source.join("hello.txt"),
            name: "hello.txt".to_string(),
        }];
        send_to_peer(
            addr.ip(),
            addr.port(),
            &key,
            "phone",
            SENDER_ID,
            "Tuxedo",
            &files,
            &ui,
            &mut conflict_rx,
        )
        .await
        .unwrap();

        server.await.unwrap().unwrap();
        assert_eq!(
            std::fs::read(f.dest.join("hello.txt")).unwrap(),
            b"one tap".to_vec()
        );
        let _ = std::fs::remove_dir_all(&f._dir);
        drop(cancel);
    }

    /// A refusal must reach the *sender* as a sentence about the other device, not as a
    /// connection error — the sender's user is the only one looking at a screen.
    #[tokio::test]
    async fn a_refusal_is_reported_to_the_sender_with_its_reason() {
        let f = fixture("refuse", b"nope");
        let key = [0x22u8; 32];
        let ui = test_ui();

        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let server_ui = ui.clone();
        let server = tokio::spawn(async move {
            let (tcp, _) = listener.accept().await.unwrap();
            let ring = ring_with(vec![PairKey::bound(key, SENDER_ID)]);
            serve_one(
                tcp,
                &ring,
                &server_ui,
                &|_offer: &PairedOffer| Verdict::Refuse(Refusal::NotAllowed),
                &|_: &Caller, _: &str| {},
            )
            .await
        });

        let (_tx, mut conflict_rx) = mpsc::channel(1);
        let files = vec![SendFile {
            path: f.source.join("hello.txt"),
            name: "hello.txt".to_string(),
        }];
        let result = send_to_peer(
            addr.ip(),
            addr.port(),
            &key,
            "phone",
            SENDER_ID,
            "Tuxedo",
            &files,
            &ui,
            &mut conflict_rx,
        )
        .await;

        server.await.unwrap().unwrap();
        let message = result.unwrap_err().message;
        assert!(
            message.contains("not set to accept"),
            "unhelpful refusal message: {}",
            message
        );
        assert!(!f.dest.join("hello.txt").exists());
        let _ = std::fs::remove_dir_all(&f._dir);
    }

    /// A device we are not paired with holds no key of ours, so it is turned away at the
    /// hello with a sentence — and crucially it must not leave the listener broken for the
    /// devices that are paired.
    #[tokio::test]
    async fn a_stranger_is_told_to_pair() {
        let f = fixture("stranger", b"secret");
        let ours = [0x33u8; 32];
        let theirs = [0x44u8; 32];
        let ui = test_ui();

        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let dest = f.dest.clone();
        let server_ui = ui.clone();
        let server = tokio::spawn(async move {
            let (tcp, _) = listener.accept().await.unwrap();
            let ring = ring_with(vec![PairKey::bound(ours, [1u8; 16])]);
            serve_one(
                tcp,
                &ring,
                &server_ui,
                &move |_o: &PairedOffer| Verdict::Accept(dest.clone()),
                &|_: &Caller, _: &str| {},
            )
            .await
        });

        let (_tx, mut conflict_rx) = mpsc::channel(1);
        let files = vec![SendFile {
            path: f.source.join("hello.txt"),
            name: "hello.txt".to_string(),
        }];
        let result = send_to_peer(
            addr.ip(),
            addr.port(),
            &theirs,
            "desk",
            SENDER_ID,
            "Somebody else",
            &files,
            &ui,
            &mut conflict_rx,
        )
        .await;

        let message = result.unwrap_err().message;
        assert!(message.contains("not paired"), "{}", message);
        assert!(server.await.unwrap().is_err());
        assert!(!f.dest.join("hello.txt").exists());
        let _ = std::fs::remove_dir_all(&f._dir);
    }

    /// A known device calling under the wrong key is a re-pairing gone stale, and the
    /// sender is told exactly that.
    #[tokio::test]
    async fn a_stale_key_is_named_as_such() {
        let f = fixture("stale", b"secret");
        let stored = [0x55u8; 32];
        let stale = [0x66u8; 32];
        let ui = test_ui();

        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let server_ui = ui.clone();
        let server = tokio::spawn(async move {
            let (tcp, _) = listener.accept().await.unwrap();
            let ring = ring_with(vec![PairKey::bound(stored, SENDER_ID)]);
            serve_one(
                tcp,
                &ring,
                &server_ui,
                &|_o: &PairedOffer| Verdict::Refuse(Refusal::Busy),
                &|_: &Caller, _: &str| {},
            )
            .await
        });

        let (_tx, mut conflict_rx) = mpsc::channel(1);
        let files = vec![SendFile {
            path: f.source.join("hello.txt"),
            name: "hello.txt".to_string(),
        }];
        let result = send_to_peer(
            addr.ip(),
            addr.port(),
            &stale,
            "desk",
            SENDER_ID,
            "phone",
            &files,
            &ui,
            &mut conflict_rx,
        )
        .await;
        let message = result.unwrap_err().message;
        assert!(message.contains("different pairing key"), "{}", message);
        assert!(server.await.unwrap().is_err());
        let _ = std::fs::remove_dir_all(&f._dir);
    }

    /// A code this device showed is claimed by the first device to complete a handshake
    /// under it — and the responder is told whom it was, with the name off the offer.
    #[tokio::test]
    async fn a_pending_code_is_claimed_by_the_caller() {
        let f = fixture("claim", b"hi");
        let code = [0x77u8; 32];
        let ui = test_ui();

        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let dest = f.dest.clone();
        let server_ui = ui.clone();
        let claimed = Arc::new(Mutex::new(None));
        let sink = claimed.clone();
        let server = tokio::spawn(async move {
            let (tcp, _) = listener.accept().await.unwrap();
            let ring = ring_with(vec![PairKey::pending(code)]);
            serve_one(
                tcp,
                &ring,
                &server_ui,
                &move |_o: &PairedOffer| Verdict::Accept(dest.clone()),
                &move |caller: &Caller, name: &str| {
                    *sink.lock().unwrap() = Some((caller.clone(), name.to_string()));
                },
            )
            .await
        });

        let (_tx, mut conflict_rx) = mpsc::channel(1);
        let files = vec![SendFile {
            path: f.source.join("hello.txt"),
            name: "hello.txt".to_string(),
        }];
        send_to_peer(
            addr.ip(),
            addr.port(),
            &code,
            "desk",
            SENDER_ID,
            "Tuxedo",
            &files,
            &ui,
            &mut conflict_rx,
        )
        .await
        .unwrap();
        server.await.unwrap().unwrap();
        let (caller, name) = claimed.lock().unwrap().clone().unwrap();
        assert_eq!(caller.device_id, SENDER_ID);
        assert!(caller.key.is_pending());
        assert_eq!(caller.key.key, code);
        assert_eq!(name, "Tuxedo");
        let _ = std::fs::remove_dir_all(&f._dir);
    }

    /// A control request is answered and moves nothing — and says nothing: the log of a
    /// device that is asked every few seconds must not fill with handshakes.
    #[tokio::test]
    async fn a_control_request_is_answered_quietly() {
        let key = [0x88u8; 32];
        let ui = test_ui();
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let server_ui = ui.clone();
        let server = tokio::spawn(async move {
            let (tcp, _) = listener.accept().await.unwrap();
            let ring = ring_with(vec![PairKey::bound(key, SENDER_ID)]);
            serve_one_with_control(
                tcp,
                &ring,
                &server_ui,
                &|_o: &PairedOffer| -> Verdict { panic!("a control request must not be decided as a transfer") },
                &|_: &Caller, _: &str| {},
                &|offer: &PairedOffer, ip: IpAddr| {
                    assert_eq!(offer.request, REQUEST_LINK_STATUS);
                    Some(format!("wifi=1\nasked_on={}\n", ip))
                },
            )
            .await
        });
        let answer = request_control(
            addr.ip(),
            addr.port(),
            &key,
            "desk",
            SENDER_ID,
            "phone",
            REQUEST_LINK_STATUS,
            &ui,
        )
        .await
        .unwrap();
        server.await.unwrap().unwrap();
        assert_eq!(answer, "wifi=1\nasked_on=127.0.0.1\n");
        assert!(ui.0.lock().unwrap().is_empty(), "{:?}", ui.0.lock().unwrap());
    }

    /// The plain `serve_one` answers none, and says so instead of taking it for a transfer.
    #[tokio::test]
    async fn a_control_request_nobody_answers_is_refused() {
        let key = [0x99u8; 32];
        let ui = test_ui();
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let server_ui = ui.clone();
        let server = tokio::spawn(async move {
            let (tcp, _) = listener.accept().await.unwrap();
            let ring = ring_with(vec![PairKey::bound(key, SENDER_ID)]);
            serve_one(
                tcp,
                &ring,
                &server_ui,
                &|_o: &PairedOffer| -> Verdict { panic!("not a transfer") },
                &|_: &Caller, _: &str| {},
            )
            .await
        });
        let result = request_control(
            addr.ip(), addr.port(), &key, "desk", SENDER_ID, "phone", REQUEST_REASSOCIATE, &ui,
        )
        .await;
        server.await.unwrap().unwrap();
        assert!(result.is_err());
    }

    #[test]
    fn every_refusal_code_survives_the_round_trip() {
        for reason in [
            Refusal::Busy,
            Refusal::NotAllowed,
            Refusal::TooLarge,
            Refusal::NoDestination,
        ] {
            assert_eq!(Refusal::from_code(reason as u64), Some(reason));
        }
        // 1 is "accepted" and must never decode as a refusal, or an accepted transfer
        // would be reported as a refused one.
        assert_eq!(Refusal::from_code(1), None);
    }
}
