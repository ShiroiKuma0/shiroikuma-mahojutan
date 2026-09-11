// Fork: a transfer between two devices that already know each other.
//
// Everything below the Noise handshake is upstream's, unchanged — the same preamble, the
// same `sending::send_file` and `receiving::receive_file`, the same conflict exchange. What
// changes is only what happens *before*:
//
//   stock shared network        paired
//   -------------------------   -----------------------------------------------
//   generate a password         nothing; the group key was agreed once, at pairing
//   show it / hand it over BLE  nothing
//   arm both devices            arm neither; the receiver has been listening all along
//   discover by role            connect straight to a known address
//   PBKDF2(password) → PSK      HMAC(groupKey) → PSK, no stretching needed
//
// It deliberately does NOT add a `ConnectionMode` variant. A paired transfer is symmetric
// in exactly the way `ConnectionMode::SharedNetwork` already is — both ends are clients,
// both write their mode and read the peer's — so it reuses that branch of `confirm_mode`
// and adds no arm to any of the three places that match on connection mode. One less thing
// for an upstream rebase to collide with.
//
// The one addition to the wire is the **offer**, sent inside Noise before the file count:
// who is calling, and what they want to send. It exists because an unattended receiver has
// to be able to say no, and to name the sender in the notification it posts. Only paired
// devices ever speak this, so it needs no version guard beyond its own.

use crate::error::{fc_error, FCError};
use crate::pairing::clamp_name;
use crate::presence::PRESENCE_PORT;
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
    /// This device is already transferring. One at a time, exactly as before.
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
                "The other device has not been given a folder to receive into yet."
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

/// Runs the preamble and the Noise handshake. Both sides pass `is_wifi_client = true`,
/// which is what shared network mode already does: neither end is hosting anything, so the
/// exchange is symmetric and TCP buffering keeps two simultaneous writes from deadlocking.
async fn handshake<T: UI>(
    tcp: TcpStream,
    mode: &Mode,
    psk: &[u8; 32],
    role: noise::Role,
    ui: &T,
) -> Result<(TransferStream, bool), FCError> {
    let mut preamble = noise::RecordingStream::new(tcp);
    let peer_is_fork = confirm_version(true, &mut preamble).await?;
    confirm_mode(
        mode.clone(),
        true,
        &mut preamble,
        ConnectionMode::SharedNetwork,
    )
    .await?;
    let (tcp, sent, received) = preamble.into_parts();
    let prologue = match role {
        noise::Role::Initiator => noise::build_prologue(&sent, &received),
        noise::Role::Responder => noise::build_prologue(&received, &sent),
    };
    let encrypted = noise::handshake(tcp, role, psk, &prologue).await?;
    ui.output("Encrypted connection established.");
    Ok((TransferStream::Encrypted(Box::new(encrypted)), peer_is_fork))
}

/// Sends to a paired device at a known address. No discovery, no password, no arming on the
/// far side — this is the whole point of the feature.
///
/// `psk` is `noise::derive_paired_psk(group_key)`; completing the handshake with it is what
/// proves both ends are in the same group, so there is no separate authentication step.
#[allow(clippy::too_many_arguments)]
pub async fn send_to_peer<T: UI>(
    peer_ip: IpAddr,
    port: u16,
    psk: &[u8; 32],
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
    let (mut stream, peer_is_fork) =
        handshake(tcp, &mode, psk, noise::Role::Initiator, ui).await?;

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

/// Handles one accepted connection. Split from the accept loop so a peer that misbehaves
/// during its own session cannot take the listener down with it — `serve` logs the error
/// and goes back to accepting.
async fn serve_one<T: UI, D>(
    tcp: TcpStream,
    psk: &[u8; 32],
    ui: &T,
    decide: &D,
) -> Result<(), FCError>
where
    D: Fn(&PairedOffer) -> Verdict,
{
    prepare_socket(&tcp, ui);
    // The destination is not known until the offer has been read, and Mode::Receive needs
    // one to exist for the preamble. It is replaced by the decision's own path before a
    // single file is written, so this placeholder never reaches the filesystem.
    let mode = Mode::Receive(PathBuf::new());
    let (mut stream, peer_is_fork) =
        handshake(tcp, &mode, psk, noise::Role::Responder, ui).await?;

    let offer = read_offer(&mut stream).await?;
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

/// The standing receiver. Binds the presence TCP port and serves paired devices until
/// cancelled.
///
/// It never returns on its own: like the shared-network receiver, there is no timeout,
/// because the other device may not be started for hours. A failed session is logged and
/// the loop carries on — one peer with a stale group key must not silently take away the
/// ability to receive from every other.
pub async fn serve<T: UI, D>(
    psk: [u8; 32],
    ui: &T,
    cancel: Arc<AtomicBool>,
    decide: D,
) -> Result<(), FCError>
where
    D: Fn(&PairedOffer) -> Verdict + Send + Sync + 'static,
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
        if let Err(e) = serve_one(tcp, &psk, ui, &decide).await {
            // Includes the ordinary case of a stranger connecting: they cannot complete
            // the Noise handshake without the group key, and this is where that surfaces.
            ui.output(&format!("Transfer from {} ended: {}", addr, e));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::noise::derive_paired_psk;
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

    /// The whole point of the feature, end to end: a listener that nobody armed, a sender
    /// that was told nothing but an address, and a file that arrives.
    #[tokio::test]
    async fn a_paired_send_needs_nothing_on_the_receiving_side() {
        let f = fixture("accept", b"one tap");
        let psk = derive_paired_psk(&[0x11u8; 32]);
        let ui = test_ui();
        let cancel = Arc::new(AtomicBool::new(false));

        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let dest = f.dest.clone();
        let server_ui = ui.clone();
        let server = tokio::spawn(async move {
            let (tcp, _) = listener.accept().await.unwrap();
            serve_one(tcp, &psk, &server_ui, &move |_offer: &PairedOffer| {
                Verdict::Accept(dest.clone())
            })
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
            &psk,
            [9u8; 16],
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
        let psk = derive_paired_psk(&[0x22u8; 32]);
        let ui = test_ui();

        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let server_ui = ui.clone();
        let server = tokio::spawn(async move {
            let (tcp, _) = listener.accept().await.unwrap();
            serve_one(tcp, &psk, &server_ui, &|_offer: &PairedOffer| {
                Verdict::Refuse(Refusal::NotAllowed)
            })
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
            &psk,
            [9u8; 16],
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

    /// A device outside the group holds no key, so it cannot get past the handshake — and
    /// crucially it must not leave the listener broken for the devices that can.
    #[tokio::test]
    async fn a_stranger_cannot_complete_the_handshake() {
        let f = fixture("stranger", b"secret");
        let ours = derive_paired_psk(&[0x33u8; 32]);
        let theirs = derive_paired_psk(&[0x44u8; 32]);
        let ui = test_ui();

        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let dest = f.dest.clone();
        let server_ui = ui.clone();
        let server = tokio::spawn(async move {
            let (tcp, _) = listener.accept().await.unwrap();
            serve_one(tcp, &ours, &server_ui, &move |_o: &PairedOffer| {
                Verdict::Accept(dest.clone())
            })
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
            [9u8; 16],
            "Somebody else",
            &files,
            &ui,
            &mut conflict_rx,
        )
        .await;

        assert!(result.is_err(), "a wrong group key must not transfer");
        assert!(server.await.unwrap().is_err());
        assert!(!f.dest.join("hello.txt").exists());
        let _ = std::fs::remove_dir_all(&f._dir);
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
