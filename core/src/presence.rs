// Fork: presence — how a paired device finds the others, and how it says who it is.
//
// This is deliberately NOT an extension of discovery.rs. That record's byte layout is
// pinned by a known-answer test shared with Android and Apple, it is keyed on the
// per-transfer password, and it answers one question ("where is the one peer that is
// transferring with me right now?"). This one answers a different question — "which of my
// devices are on this network, and what are they called?" — and it must be able to change
// shape without a three-platform release. New magic, new port, new key.
//
// The cadence is the important design decision, and it was chosen for 白い熊's own
// network rather than for a textbook one.
//
// **The receiver does not beacon; it answers.** A device that is merely reachable holds a
// UDP socket and replies when asked. It broadcasts exactly once, when its IP changes. The
// sender is the one that shouts, and only while a person is looking at a device list. That
// inverts the cost onto the device whose screen is already on, and — with the cached-IP
// connect in pairing.rs — means a standing receiver needs no MulticastLock at all in the
// ordinary case, because a unicast packet to a device's own address is delivered even when
// its Wi-Fi driver is filtering multicast and broadcast.
//
// **Broadcast, not just multicast.** On 2026-08-10 a transfer between 白い熊's two phones
// died on a /21 where the network dropped multicast between clients (Discovery.kt:18-22).
// Multicast is tried, but the subnet broadcast address is what actually works there, and
// unlike multicast it is an ordinary on-link destination needing no group join.
//
// The Kotlin port is Presence.kt and must produce identical bytes; presence_known_answer()
// below is the vector both sides are tested against.

use crate::error::{fc_error, FCError};
use crate::pairing::{clamp_name, MAX_NAME_BYTES};
use crate::utils::{compute_hmac, verify_hmac};
use socket2::{Domain, Protocol, Socket, Type};
use std::collections::HashMap;
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::net::UdpSocket;

/// Adjacent to the transfer port, and deliberately not equal to it. Port 3290 is bound
/// without SO_REUSEADDR on purpose (discovery.rs:325 — a second bind there should be a
/// loud "is another copy running?", not two sockets splitting the packets), and it is
/// shared by transfer-time discovery and the transfer socket itself. A listener that
/// stands for hours cannot live there.
pub const PRESENCE_PORT: u16 = 3291;
pub const PRESENCE_MULTICAST_ADDR: &str = "239.255.73.68";
pub const PRESENCE_MAGIC: [u8; 4] = *b"FCPR";
pub const PRESENCE_VERSION: u16 = 1;

/// Everything before the name: magic(4) version(2) flags(2) os(1) device_id(16) ip(4)
/// port(2) timestamp(8) name_len(1).
pub const PRESENCE_HEADER_SIZE: usize = 40;
pub const PRESENCE_HMAC_SIZE: usize = 32;
pub const PRESENCE_MIN_SIZE: usize = PRESENCE_HEADER_SIZE + PRESENCE_HMAC_SIZE;
pub const PRESENCE_MAX_SIZE: usize = PRESENCE_HEADER_SIZE + MAX_NAME_BYTES + PRESENCE_HMAC_SIZE;

const _: () = assert!(
    4 + 2 + 2 + 1 + 16 + 4 + 2 + 8 + 1 == PRESENCE_HEADER_SIZE,
    "PRESENCE_HEADER_SIZE does not match the sum of the fixed field sizes"
);

/// "Answer now." A probe is what a sender emits while its device list is open; anything
/// hearing one replies unicast to the source and does not itself probe back.
pub const FLAG_PROBE: u16 = 0x0001;
/// "I will accept a transfer without anyone touching me." Advisory — the receiving side
/// decides for itself; this only lets the sender's list say what will happen.
pub const FLAG_UNATTENDED: u16 = 0x0002;

/// Same order as `crate::Peer`, so the two convert without a lookup table.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PeerOs {
    Android = 0,
    Ios = 1,
    Linux = 2,
    MacOs = 3,
    Windows = 4,
}

impl PeerOs {
    pub fn from_byte(b: u8) -> Option<PeerOs> {
        match b {
            0 => Some(PeerOs::Android),
            1 => Some(PeerOs::Ios),
            2 => Some(PeerOs::Linux),
            3 => Some(PeerOs::MacOs),
            4 => Some(PeerOs::Windows),
            _ => None,
        }
    }

    /// The strings `Peer::try_from` already accepts, so a discovered peer feeds straight
    /// into the existing transfer entry points.
    pub fn as_str(&self) -> &'static str {
        match self {
            PeerOs::Android => "android",
            PeerOs::Ios => "ios",
            PeerOs::Linux => "linux",
            PeerOs::MacOs => "mac",
            PeerOs::Windows => "windows",
        }
    }

    pub fn this_device() -> PeerOs {
        if cfg!(target_os = "windows") {
            PeerOs::Windows
        } else if cfg!(target_os = "macos") {
            PeerOs::MacOs
        } else {
            PeerOs::Linux
        }
    }
}

/// Who this device says it is.
#[derive(Clone, Debug)]
pub struct LocalIdentity {
    pub device_id: [u8; 16],
    pub name: String,
    pub os: PeerOs,
    pub unattended: bool,
}

/// A device we have just heard from.
#[derive(Clone, Debug)]
pub struct DiscoveredPeer {
    pub device_id: [u8; 16],
    pub name: String,
    pub os: PeerOs,
    pub ip: Ipv4Addr,
    pub port: u16,
    pub unattended: bool,
}

#[derive(Clone, Debug)]
pub struct PresenceAnnouncement {
    pub flags: u16,
    pub os: PeerOs,
    pub device_id: [u8; 16],
    pub ip_address: [u8; 4],
    pub port: u16,
    pub timestamp: u64,
    pub name: String,
}

impl PresenceAnnouncement {
    pub fn new(identity: &LocalIdentity, ip: Ipv4Addr, probe: bool) -> Self {
        let mut flags = 0u16;
        if probe {
            flags |= FLAG_PROBE;
        }
        if identity.unattended {
            flags |= FLAG_UNATTENDED;
        }
        PresenceAnnouncement {
            flags,
            os: identity.os,
            device_id: identity.device_id,
            ip_address: ip.octets(),
            port: PRESENCE_PORT,
            timestamp: now_secs(),
            name: clamp_name(&identity.name),
        }
    }

    pub fn is_probe(&self) -> bool {
        self.flags & FLAG_PROBE != 0
    }

    pub fn is_unattended(&self) -> bool {
        self.flags & FLAG_UNATTENDED != 0
    }

    pub fn get_ip_address(&self) -> Ipv4Addr {
        Ipv4Addr::from(self.ip_address)
    }

    /// Everything but the trailing HMAC. Big-endian throughout, matching every other
    /// integer this app puts on a wire.
    fn body(&self) -> Vec<u8> {
        let name = clamp_name(&self.name);
        let name_bytes = name.as_bytes();
        let mut buf = Vec::with_capacity(PRESENCE_HEADER_SIZE + name_bytes.len());
        buf.extend_from_slice(&PRESENCE_MAGIC);
        buf.extend_from_slice(&PRESENCE_VERSION.to_be_bytes());
        buf.extend_from_slice(&self.flags.to_be_bytes());
        buf.push(self.os as u8);
        buf.extend_from_slice(&self.device_id);
        buf.extend_from_slice(&self.ip_address);
        buf.extend_from_slice(&self.port.to_be_bytes());
        buf.extend_from_slice(&self.timestamp.to_be_bytes());
        buf.push(name_bytes.len() as u8);
        buf.extend_from_slice(name_bytes);
        buf
    }

    pub fn serialize(&self, key: &[u8; 32]) -> Vec<u8> {
        let mut buf = self.body();
        let mac = compute_hmac(key, &buf);
        buf.extend_from_slice(&mac);
        buf
    }

    /// Rejects anything that is not a well-formed, authentic, fresh announcement. The
    /// HMAC is checked before the name is turned into a String, so a forged packet can
    /// never get as far as allocating from its own length byte.
    pub fn deserialize(buf: &[u8], key: &[u8; 32]) -> Option<PresenceAnnouncement> {
        if buf.len() < PRESENCE_MIN_SIZE || buf.len() > PRESENCE_MAX_SIZE {
            return None;
        }
        if buf[0..4] != PRESENCE_MAGIC {
            return None;
        }
        let version = u16::from_be_bytes([buf[4], buf[5]]);
        if version != PRESENCE_VERSION {
            return None;
        }
        let name_len = buf[PRESENCE_HEADER_SIZE - 1] as usize;
        let expected_len = PRESENCE_HEADER_SIZE + name_len + PRESENCE_HMAC_SIZE;
        if buf.len() != expected_len {
            return None;
        }
        let (body, mac) = buf.split_at(PRESENCE_HEADER_SIZE + name_len);
        let mut expected = [0u8; PRESENCE_HMAC_SIZE];
        expected.copy_from_slice(mac);
        if !verify_hmac(key, body, &expected) {
            return None;
        }

        let flags = u16::from_be_bytes([buf[6], buf[7]]);
        let os = PeerOs::from_byte(buf[8])?;
        let mut device_id = [0u8; 16];
        device_id.copy_from_slice(&buf[9..25]);
        let mut ip_address = [0u8; 4];
        ip_address.copy_from_slice(&buf[25..29]);
        let port = u16::from_be_bytes([buf[29], buf[30]]);
        let timestamp = u64::from_be_bytes(buf[31..39].try_into().ok()?);
        let name = String::from_utf8(body[PRESENCE_HEADER_SIZE..].to_vec()).ok()?;

        Some(PresenceAnnouncement {
            flags,
            os,
            device_id,
            ip_address,
            port,
            timestamp,
            name,
        })
    }

    /// The same ±60 s window discovery.rs uses. It is a replay bound, not a clock check:
    /// an announcement is not secret and reveals nothing, so the window only has to be
    /// tight enough that a captured packet stops being useful long before a device moves.
    pub fn is_fresh(&self) -> bool {
        let now = now_secs();
        if self.timestamp > now {
            self.timestamp - now <= TIMESTAMP_WINDOW_SECS
        } else {
            now - self.timestamp <= TIMESTAMP_WINDOW_SECS
        }
    }
}

const TIMESTAMP_WINDOW_SECS: u64 = 60;

/// A sweep is capped the way discovery.rs's is, but higher: 白い熊's own network is a /21
/// (2046 hosts) and the whole point is that it must work there. This runs once per probe,
/// not on a heartbeat, so the packet count is bounded by how often a device list is opened.
const MAX_UNICAST_SCAN_HOSTS: u32 = 4096;
/// Sent in chunks so a sweep of a /21 does not hand two thousand datagrams to the stack in
/// one go, which drops most of them on a phone.
const UNICAST_SCAN_CHUNK: usize = 128;
const UNICAST_SCAN_CHUNK_DELAY_MS: u64 = 20;

pub fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

/// The all-hosts address of the local subnet. Unlike multicast this needs no group join
/// and no driver-level opt-in, which is exactly why it is the channel that works on the
/// networks where multicast does not.
pub fn subnet_broadcast_address(local_ip: Ipv4Addr, prefix_len: u8) -> Option<Ipv4Addr> {
    if prefix_len == 0 || prefix_len > 30 {
        return None;
    }
    let mask = !0u32 << (32 - prefix_len);
    Some(Ipv4Addr::from((u32::from(local_ip) & mask) | !mask))
}

/// Every other host address on the subnet, or None when there are too many to sweep.
pub fn unicast_scan_targets(local_ip: Ipv4Addr, prefix_len: u8) -> Option<Vec<Ipv4Addr>> {
    if prefix_len == 0 || prefix_len > 30 {
        return None;
    }
    let ip_u32 = u32::from(local_ip);
    let mask = !0u32 << (32 - prefix_len);
    let network = ip_u32 & mask;
    let broadcast = network | !mask;
    if broadcast - network - 1 > MAX_UNICAST_SCAN_HOSTS {
        return None;
    }
    Some(
        ((network + 1)..broadcast)
            .filter(|&addr| addr != ip_u32)
            .map(Ipv4Addr::from)
            .collect(),
    )
}

/// Binds the well-known presence port for a device that wants to be findable. Uses
/// SO_REUSEADDR, unlike discovery.rs: a presence responder is a long-lived background
/// thing that may well be restarted while the old socket sits in TIME_WAIT, and failing
/// to come back up then would be a silently unreachable device.
fn bind_presence_socket(local_ip: Ipv4Addr) -> Result<UdpSocket, FCError> {
    let socket = Socket::new(Domain::IPV4, Type::DGRAM, Some(Protocol::UDP))
        .map_err(|e| FCError { message: format!("Could not create presence socket: {}", e) })?;
    socket
        .set_reuse_address(true)
        .map_err(|e| FCError { message: format!("Could not set SO_REUSEADDR: {}", e) })?;
    socket.set_broadcast(true).ok();
    let bind_addr = SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, PRESENCE_PORT);
    socket.bind(&bind_addr.into()).map_err(|e| FCError {
        message: format!("Could not bind UDP port {}: {}", PRESENCE_PORT, e),
    })?;

    // Multicast is best-effort everywhere here: broadcast and the unicast sweep carry the
    // protocol on their own, and on the network this was designed for multicast is the one
    // that does not arrive.
    if let Ok(group) = PRESENCE_MULTICAST_ADDR.parse::<Ipv4Addr>() {
        let _ = socket.join_multicast_v4(&group, &local_ip);
        let _ = socket.set_multicast_if_v4(&local_ip);
    }
    let _ = socket.set_multicast_loop_v4(false);
    socket
        .set_nonblocking(true)
        .map_err(|e| FCError { message: format!("Could not set the presence socket nonblocking: {}", e) })?;
    UdpSocket::from_std(socket.into())
        .map_err(|e| FCError { message: format!("Could not adopt the presence socket: {}", e) })
}

fn bind_ephemeral_socket() -> Result<UdpSocket, FCError> {
    let socket = Socket::new(Domain::IPV4, Type::DGRAM, Some(Protocol::UDP))
        .map_err(|e| FCError { message: format!("Could not create probe socket: {}", e) })?;
    socket.set_broadcast(true).ok();
    socket
        .bind(&SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, 0).into())
        .map_err(|e| FCError { message: format!("Could not bind a probe socket: {}", e) })?;
    socket
        .set_nonblocking(true)
        .map_err(|e| FCError { message: format!("Could not set the probe socket nonblocking: {}", e) })?;
    UdpSocket::from_std(socket.into())
        .map_err(|e| FCError { message: format!("Could not adopt the probe socket: {}", e) })
}

/// Sends one announcement everywhere it might be heard: multicast, the subnet broadcast
/// address, and — when the subnet is small enough and `sweep` is set — every host on it.
async fn shout(
    socket: &UdpSocket,
    payload: &[u8],
    local_ip: Ipv4Addr,
    prefix_len: u8,
    sweep: bool,
) {
    if let Ok(group) = PRESENCE_MULTICAST_ADDR.parse::<Ipv4Addr>() {
        let _ = socket
            .send_to(payload, SocketAddr::V4(SocketAddrV4::new(group, PRESENCE_PORT)))
            .await;
    }
    if let Some(broadcast) = subnet_broadcast_address(local_ip, prefix_len) {
        let _ = socket
            .send_to(payload, SocketAddr::V4(SocketAddrV4::new(broadcast, PRESENCE_PORT)))
            .await;
    }
    if !sweep {
        return;
    }
    if let Some(targets) = unicast_scan_targets(local_ip, prefix_len) {
        for chunk in targets.chunks(UNICAST_SCAN_CHUNK) {
            for &target in chunk {
                let _ = socket
                    .send_to(payload, SocketAddr::V4(SocketAddrV4::new(target, PRESENCE_PORT)))
                    .await;
            }
            tokio::time::sleep(Duration::from_millis(UNICAST_SCAN_CHUNK_DELAY_MS)).await;
        }
    }
}

/// The responder half: hold the presence port and answer probes. This is what "stay
/// reachable" runs, and what the app runs while it is open.
pub struct PresenceResponder {
    key: [u8; 32],
    identity: LocalIdentity,
    cancel: Arc<AtomicBool>,
}

impl PresenceResponder {
    pub fn new(key: [u8; 32], identity: LocalIdentity) -> Self {
        PresenceResponder {
            key,
            identity,
            cancel: Arc::new(AtomicBool::new(false)),
        }
    }

    pub fn cancel(&self) {
        self.cancel.store(true, Ordering::SeqCst);
    }

    pub fn cancel_handle(&self) -> Arc<AtomicBool> {
        self.cancel.clone()
    }

    /// Runs until cancelled. Announces once on entry — that single burst is what tells the
    /// other devices about a new address after the network changed — and thereafter only
    /// answers. Each authenticated peer heard from is reported through `on_peer` so the
    /// caller can keep its cached addresses warm without any extra traffic.
    pub async fn run<F>(
        &self,
        local_ip: Ipv4Addr,
        prefix_len: u8,
        mut on_peer: F,
    ) -> Result<(), FCError>
    where
        F: FnMut(DiscoveredPeer),
    {
        let socket = bind_presence_socket(local_ip)?;
        let hello =
            PresenceAnnouncement::new(&self.identity, local_ip, false).serialize(&self.key);
        shout(&socket, &hello, local_ip, prefix_len, false).await;

        let mut buf = [0u8; PRESENCE_MAX_SIZE];
        loop {
            if self.cancel.load(Ordering::SeqCst) {
                return Ok(());
            }
            let received =
                match tokio::time::timeout(Duration::from_millis(250), socket.recv_from(&mut buf))
                    .await
                {
                    Ok(Ok(r)) => r,
                    // An ICMP unreachable from a swept address surfaces here on some
                    // platforms. Never fatal for a listener.
                    Ok(Err(_)) => continue,
                    Err(_) => continue,
                };
            let (len, src) = received;
            let Some(announcement) = PresenceAnnouncement::deserialize(&buf[..len], &self.key)
            else {
                continue;
            };
            if announcement.device_id == self.identity.device_id {
                continue; // our own broadcast, come back to us
            }
            if !announcement.is_fresh() {
                continue;
            }
            let source_ip = match src {
                SocketAddr::V4(v4) => *v4.ip(),
                _ => announcement.get_ip_address(),
            };
            on_peer(DiscoveredPeer {
                device_id: announcement.device_id,
                name: announcement.name.clone(),
                os: announcement.os,
                // The source address beats the announced one when they disagree: the
                // announcement is already authenticated, and a peer behind a VPN announces
                // a tun address its packets do not come from.
                ip: source_ip,
                port: announcement.port,
                unattended: announcement.is_unattended(),
            });

            if announcement.is_probe() {
                let reply =
                    PresenceAnnouncement::new(&self.identity, local_ip, false).serialize(&self.key);
                let _ = socket.send_to(&reply, src).await;
            }
        }
    }
}

/// The prober half: ask who is there, and collect the answers. Runs from an ephemeral
/// port, so it works on a device that is also running a `PresenceResponder`.
///
/// `sweep` decides whether to fall back to walking the subnet — worth it when the list
/// came back empty, wasteful when a broadcast already answered.
pub async fn probe(
    key: &[u8; 32],
    identity: &LocalIdentity,
    local_ip: Ipv4Addr,
    prefix_len: u8,
    listen_for: Duration,
    sweep: bool,
) -> Result<Vec<DiscoveredPeer>, FCError> {
    let socket = bind_ephemeral_socket()?;
    if let Ok(group) = PRESENCE_MULTICAST_ADDR.parse::<Ipv4Addr>() {
        let _ = socket.set_multicast_loop_v4(false);
        let _ = socket.join_multicast_v4(group, local_ip);
    }
    let payload = PresenceAnnouncement::new(identity, local_ip, true).serialize(key);

    let mut found: HashMap<[u8; 16], DiscoveredPeer> = HashMap::new();
    let deadline = tokio::time::Instant::now() + listen_for;

    // Shout and listen at once: a sweep of a /21 takes ~320 ms of chunk delays on its own,
    // and the first replies arrive long before it finishes.
    let shouting = shout(&socket, &payload, local_ip, prefix_len, sweep);
    tokio::pin!(shouting);
    let mut still_shouting = true;
    let mut buf = [0u8; PRESENCE_MAX_SIZE];

    loop {
        let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
        if remaining.is_zero() {
            break;
        }
        tokio::select! {
            _ = &mut shouting, if still_shouting => {
                still_shouting = false;
            }
            result = tokio::time::timeout(remaining, socket.recv_from(&mut buf)) => {
                match result {
                    Ok(Ok((len, src))) => {
                        let Some(announcement) =
                            PresenceAnnouncement::deserialize(&buf[..len], key) else { continue };
                        if announcement.device_id == identity.device_id || !announcement.is_fresh() {
                            continue;
                        }
                        let ip = match src {
                            SocketAddr::V4(v4) => *v4.ip(),
                            _ => announcement.get_ip_address(),
                        };
                        found.insert(
                            announcement.device_id,
                            DiscoveredPeer {
                                device_id: announcement.device_id,
                                name: announcement.name.clone(),
                                os: announcement.os,
                                ip,
                                port: announcement.port,
                                unattended: announcement.is_unattended(),
                            },
                        );
                    }
                    Ok(Err(_)) => continue,
                    Err(_) => break,
                }
            }
        }
    }

    Ok(found.into_values().collect())
}

/// Announces once, to everything, and returns. This is the IP-change burst: a device that
/// has just been given a new address says so unprompted, because every cached address its
/// peers hold for it is now wrong and nothing else would tell them.
pub async fn announce_once(
    key: &[u8; 32],
    identity: &LocalIdentity,
    local_ip: Ipv4Addr,
    prefix_len: u8,
) -> Result<(), FCError> {
    let socket = bind_ephemeral_socket()?;
    let payload = PresenceAnnouncement::new(identity, local_ip, false).serialize(key);
    shout(&socket, &payload, local_ip, prefix_len, false).await;
    Ok(())
}

pub fn parse_device_id(text: &str) -> Result<[u8; 16], FCError> {
    let bytes = crate::pairing::base32_decode(text)?;
    if bytes.len() < 16 {
        fc_error("Device id is too short")?;
    }
    let mut id = [0u8; 16];
    id.copy_from_slice(&bytes[..16]);
    Ok(id)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn identity() -> LocalIdentity {
        LocalIdentity {
            device_id: [7u8; 16],
            name: "白い熊二代目".to_string(),
            os: PeerOs::Android,
            unattended: true,
        }
    }

    #[test]
    fn round_trips() {
        let key = [0x5au8; 32];
        let announcement =
            PresenceAnnouncement::new(&identity(), Ipv4Addr::new(192, 168, 128, 7), true);
        let bytes = announcement.serialize(&key);
        let parsed = PresenceAnnouncement::deserialize(&bytes, &key).unwrap();
        assert_eq!(parsed.device_id, [7u8; 16]);
        assert_eq!(parsed.name, "白い熊二代目");
        assert_eq!(parsed.os, PeerOs::Android);
        assert_eq!(parsed.get_ip_address(), Ipv4Addr::new(192, 168, 128, 7));
        assert!(parsed.is_probe());
        assert!(parsed.is_unattended());
    }

    #[test]
    fn a_wrong_key_is_rejected() {
        let announcement =
            PresenceAnnouncement::new(&identity(), Ipv4Addr::new(10, 0, 0, 1), false);
        let bytes = announcement.serialize(&[0x01u8; 32]);
        assert!(PresenceAnnouncement::deserialize(&bytes, &[0x02u8; 32]).is_none());
    }

    #[test]
    fn a_tampered_name_is_rejected() {
        let key = [0x33u8; 32];
        let mut bytes =
            PresenceAnnouncement::new(&identity(), Ipv4Addr::new(10, 0, 0, 1), false).serialize(&key);
        let last_name_byte = PRESENCE_HEADER_SIZE;
        bytes[last_name_byte] ^= 0xff;
        assert!(PresenceAnnouncement::deserialize(&bytes, &key).is_none());
    }

    // A forged length byte must be rejected on the length check, before anything is
    // allocated or indexed from it.
    #[test]
    fn a_lying_length_byte_is_rejected() {
        let key = [0x44u8; 32];
        let mut bytes =
            PresenceAnnouncement::new(&identity(), Ipv4Addr::new(10, 0, 0, 1), false).serialize(&key);
        bytes[PRESENCE_HEADER_SIZE - 1] = 200;
        assert!(PresenceAnnouncement::deserialize(&bytes, &key).is_none());
    }

    #[test]
    fn truncated_and_oversized_packets_are_rejected() {
        let key = [0x55u8; 32];
        let bytes =
            PresenceAnnouncement::new(&identity(), Ipv4Addr::new(10, 0, 0, 1), false).serialize(&key);
        assert!(PresenceAnnouncement::deserialize(&bytes[..PRESENCE_MIN_SIZE - 1], &key).is_none());
        let mut oversized = bytes.clone();
        oversized.resize(PRESENCE_MAX_SIZE + 1, 0);
        assert!(PresenceAnnouncement::deserialize(&oversized, &key).is_none());
    }

    #[test]
    fn an_empty_name_is_legal() {
        let key = [0x66u8; 32];
        let mut id = identity();
        id.name = String::new();
        let bytes = PresenceAnnouncement::new(&id, Ipv4Addr::new(10, 0, 0, 1), false).serialize(&key);
        assert_eq!(bytes.len(), PRESENCE_MIN_SIZE);
        assert_eq!(PresenceAnnouncement::deserialize(&bytes, &key).unwrap().name, "");
    }

    #[test]
    fn subnet_broadcast_is_the_all_ones_host() {
        assert_eq!(
            subnet_broadcast_address(Ipv4Addr::new(192, 168, 128, 7), 21),
            Some(Ipv4Addr::new(192, 168, 135, 255))
        );
        assert_eq!(
            subnet_broadcast_address(Ipv4Addr::new(192, 168, 1, 5), 24),
            Some(Ipv4Addr::new(192, 168, 1, 255))
        );
        assert_eq!(subnet_broadcast_address(Ipv4Addr::new(10, 0, 0, 1), 31), None);
    }

    // 白い熊's own network. A /21 must be sweepable or the fallback that rescues a
    // multicast-blocking network is not there when it is needed.
    #[test]
    fn a_slash_21_is_still_sweepable() {
        let targets = unicast_scan_targets(Ipv4Addr::new(192, 168, 128, 7), 21).unwrap();
        assert_eq!(targets.len(), 2045); // 2046 hosts, minus ourselves
        assert!(!targets.contains(&Ipv4Addr::new(192, 168, 128, 7)));
        assert!(unicast_scan_targets(Ipv4Addr::new(10, 0, 0, 1), 8).is_none());
    }

    /// The cross-platform contract. Presence.kt must produce exactly these bytes for this
    /// key and these fields; if this vector and its Kotlin twin disagree, a phone and this
    /// desktop cannot see each other.
    #[test]
    fn presence_known_answer() {
        let key = [0xABu8; 32];
        let announcement = PresenceAnnouncement {
            flags: FLAG_PROBE | FLAG_UNATTENDED,
            os: PeerOs::Linux,
            device_id: [
                0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd,
                0xee, 0xff,
            ],
            ip_address: [192, 168, 128, 7],
            port: PRESENCE_PORT,
            timestamp: 1_700_000_000,
            name: "白い熊".to_string(),
        };
        let bytes = announcement.serialize(&key);
        let hex: String = bytes.iter().map(|b| format!("{:02x}", b)).collect();
        assert_eq!(hex, PRESENCE_KAT_HEX);
        // And it must survive its own round trip, so the vector cannot be "right" while
        // the parser is wrong.
        let parsed = PresenceAnnouncement::deserialize(&bytes, &key).unwrap();
        assert_eq!(parsed.name, "白い熊");
        assert_eq!(parsed.os, PeerOs::Linux);
    }

    const PRESENCE_KAT_HEX: &str = concat!(
        "46435052",                         // "FCPR"
        "0001",                             // version 1
        "0003",                             // FLAG_PROBE | FLAG_UNATTENDED
        "02",                               // PeerOs::Linux
        "00112233445566778899aabbccddeeff", // device id
        "c0a88007",                         // 192.168.128.7
        "0cdb",                             // port 3291
        "000000006553f100",                 // timestamp 1700000000
        "09",                               // name length, in BYTES not characters
        "e799bde38184e7868a",               // 白い熊
        // HMAC-SHA256 of everything above, keyed by 32 bytes of 0xAB
        "986b7e88c6db87d391d6cc85b7557f04902daaf5a216cd72a3b8be8b3c721e34",
    );
}
