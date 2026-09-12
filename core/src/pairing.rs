// Fork: paired devices — identity, the pair keys, and the list of devices we know.
//
// The stock app has no concept of "who am I" or "who is that": a transfer is negotiated
// from scratch every time, keyed on a password that is generated, shown, and thrown away.
// That is what makes both devices have to be armed in the same minute. This module is the
// missing half: a device carries a name and an id, and **every pair of devices shares its
// own 32-byte key**, from which everything else is derived (noise.rs: derive_presence_key,
// derive_paired_psk, derive_hotspot_password).
//
// Pairwise, not a group (白い熊, 2026-09-11). The first design shared one key between all
// of a person's devices, and pairing a third device by scanning *its* fresh code silently
// replaced the key on the phone that scanned it — stranding the phone it was paired with
// before, with a stale pill left on screen looking reachable. There is no direction and no
// group to keep in step any more: pairing is between exactly two devices, either one can
// show the code, the other scans or types it, and a third pairing adds a key without
// touching the others.
//
// How the two ends learn each other: the shown code carries the shower's id and a fresh
// key. The scanner stores the shower as a peer at once. The shower keeps the key as
// *pending* — a code it has shown and nobody has used yet — and the first device that
// proves it holds that key, by answering presence under it or completing a Noise handshake
// under it, becomes the peer the key belongs to. One code, one pairing.
//
// The keys are secrets and they are device-local. They must never reach the Export/Import
// archive — Backup.kt sweeps up anything living in the shared preferences file, and the
// desktop's backup.js does the same for localStorage, so this store deliberately lives
// somewhere neither of them looks.
//
// The Kotlin port is Pairing.kt. The base32 codec, the key id and the QR payload format
// must match it byte for byte, because that is how a phone and this desktop pair.

use crate::error::{fc_error, FCError};
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;
use std::time::{SystemTime, UNIX_EPOCH};

/// Crockford's alphabet: no I, L, O or U, so nothing in a typed key can be confused with
/// 1, 0, or read as a word. Decoding folds the confusable letters back in anyway, so a
/// key typed with an O instead of a 0 still works.
const BASE32_ALPHABET: &[u8] = b"0123456789ABCDEFGHJKMNPQRSTVWXYZ";

/// What a pairing QR contains. Version 2 is
/// `mahojutan-pair:2:<base32 key>:<base32 device id>:<name>` — the name is last so it may
/// contain colons without an escaping rule. Version 1 carried a group key and is refused:
/// a device still showing one is running the old model and has to be updated.
pub const PAIR_URI_PREFIX: &str = "mahojutan-pair:2:";
const OLD_PAIR_URI_PREFIX: &str = "mahojutan-pair:1:";

/// Codes shown but never used are dropped after this long. A day, not an hour: the PC has
/// no camera, so its code is typed off a phone that may only come by that evening.
pub const PENDING_CODE_TTL_SECS: u64 = 24 * 3600;
/// And there are never more than this many outstanding, so showing the code again and
/// again cannot grow the store without bound.
pub const MAX_PENDING_CODES: usize = 8;

/// A name has to fit the presence record's single length byte, and 48 bytes of UTF-8 is
/// sixteen kanji — comfortably more than a device name wants to be.
pub const MAX_NAME_BYTES: usize = 48;

pub fn base32_encode(data: &[u8]) -> String {
    let mut out = String::with_capacity(data.len().div_ceil(5) * 8);
    let mut buffer: u16 = 0;
    let mut bits: u8 = 0;
    for byte in data {
        buffer = (buffer << 8) | *byte as u16;
        bits += 8;
        while bits >= 5 {
            bits -= 5;
            let index = ((buffer >> bits) & 0x1f) as usize;
            out.push(BASE32_ALPHABET[index] as char);
        }
    }
    if bits > 0 {
        let index = ((buffer << (5 - bits)) & 0x1f) as usize;
        out.push(BASE32_ALPHABET[index] as char);
    }
    out
}

/// Decodes what `base32_encode` produced, tolerantly: case is ignored, and the separators
/// a human would add when typing a long key — spaces and hyphens — are skipped. O/o folds
/// to 0 and I/i/L/l to 1, which are the substitutions someone copying by eye actually
/// makes.
pub fn base32_decode(text: &str) -> Result<Vec<u8>, FCError> {
    let mut out = Vec::with_capacity(text.len() * 5 / 8);
    let mut buffer: u16 = 0;
    let mut bits: u8 = 0;
    for raw in text.chars() {
        let c = match raw {
            ' ' | '-' | '\t' | '\n' | '\r' => continue,
            'o' | 'O' => '0',
            'i' | 'I' | 'l' | 'L' => '1',
            other => other.to_ascii_uppercase(),
        };
        let value = match BASE32_ALPHABET.iter().position(|&a| a as char == c) {
            Some(v) => v as u16,
            None => {
                fc_error(&format!("Invalid character '{}' in pairing key", raw))?;
                unreachable!()
            }
        };
        buffer = (buffer << 5) | value;
        bits += 5;
        if bits >= 8 {
            bits -= 8;
            out.push((buffer >> bits) as u8);
        }
    }
    Ok(out)
}

/// One device we have paired with. `last_ip` is what makes a standing receiver cheap: a
/// sender tries it directly before broadcasting anything, and a unicast packet reaches a
/// device whose Wi-Fi driver is filtering multicast — which is most of them, most of the
/// time, and 白い熊's own network always.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct PairedPeer {
    /// 26 base32 characters — the peer's `device_id`, stored as text so the file is
    /// readable and so serde needs no byte-array helper.
    pub device_id: String,
    /// 52 base32 characters: the key this device and the peer share, and nobody else. Empty
    /// only for a moment during migration from the group model; `load()` fills it.
    #[serde(default)]
    pub key: String,
    pub name: String,
    /// "android" | "linux" | "windows" | "macos" | "ios". Lets `is_hosting` be answered
    /// without asking, so the peer-OS row never appears for a paired device.
    pub os: String,
    pub last_ip: Option<String>,
    /// Unix seconds. 0 means "never seen on a network" — a device paired but only ever
    /// reached over a hotspot.
    #[serde(default)]
    pub last_seen: u64,
    /// Accept a transfer from this peer without asking. Per-peer and revocable: the group
    /// key proves *who*, this decides *whether*.
    #[serde(default = "default_true")]
    pub auto_accept: bool,
}

fn default_true() -> bool {
    true
}

/// A code this device has shown and nobody has used yet.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct PendingCode {
    pub key: String,
    /// Unix seconds, for the expiry.
    pub created: u64,
}

#[derive(Clone, Debug, Serialize, Deserialize, Default)]
pub struct PairingStore {
    /// The group key of the first design. Read for migration only — `load()` turns it into
    /// a pair key on every peer that has none and clears it — and never written again.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub group_key: Option<String>,
    pub device_id: String,
    pub name: String,
    #[serde(default)]
    pub peers: Vec<PairedPeer>,
    /// Codes shown and not yet claimed. The responder accepts an introduction under any of
    /// these; the first device to make one takes the key and the code is spent.
    #[serde(default)]
    pub pending: Vec<PendingCode>,
    /// Serve transfers from paired devices without the app being open. Off by default —
    /// see the plan's §4 on what it costs.
    #[serde(default)]
    pub stay_reachable: bool,
    /// Where an unattended transfer lands. Held here rather than with the theme settings
    /// because the serve loop needs it without a window, and because it is device-local:
    /// a path from one machine means nothing on another.
    #[serde(default)]
    pub receive_dir: Option<String>,
}

impl PairingStore {
    /// The store as a brand-new device would have it: an identity, a name, and no peers.
    pub fn fresh(name: String) -> Self {
        let mut id = [0u8; 16];
        rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut id);
        PairingStore {
            group_key: None,
            device_id: base32_encode(&id),
            name: clamp_name(&name),
            peers: Vec::new(),
            pending: Vec::new(),
            stay_reachable: false,
            receive_dir: None,
        }
    }

    /// Paired with at least one device, or showing a code somebody may still use: either
    /// way this device has a reason to listen.
    pub fn is_paired(&self) -> bool {
        !self.peers.is_empty()
    }

    pub fn should_serve(&self) -> bool {
        !self.peers.is_empty() || !self.pending.is_empty()
    }

    pub fn device_id_bytes(&self) -> [u8; 16] {
        let mut id = [0u8; 16];
        if let Ok(bytes) = base32_decode(&self.device_id) {
            let n = bytes.len().min(16);
            id[..n].copy_from_slice(&bytes[..n]);
        }
        id
    }

    /// Every key this device holds — one per peer, bound to that peer's id, plus the
    /// unbound keys of codes shown and not yet claimed.
    pub fn key_ring(&self) -> KeyRing {
        let mut keys = Vec::with_capacity(self.peers.len() + self.pending.len());
        for peer in &self.peers {
            if let (Some(key), Ok(id)) = (decode_key(&peer.key), decode_id(&peer.device_id)) {
                keys.push(PairKey::bound(key, id));
            }
        }
        for code in &self.pending {
            if let Some(key) = decode_key(&code.key) {
                keys.push(PairKey::pending(key));
            }
        }
        KeyRing { keys }
    }

    /// The keys that name a peer — what a probe goes out under. Pending codes are not
    /// probed with: the device that scanned one does the introducing.
    pub fn peer_keys(&self) -> Vec<PairKey> {
        self.key_ring()
            .keys
            .into_iter()
            .filter(|k| k.device_id.is_some())
            .collect()
    }

    /// The key shared with one peer.
    pub fn key_for(&self, device_id: &str) -> Option<[u8; 32]> {
        self.find_peer(device_id).and_then(|p| decode_key(&p.key))
    }

    /// Makes a fresh code to show. The key is remembered as pending until a device claims
    /// it, and the code says who is showing it, so the scanner can list this device at once.
    pub fn new_code(&mut self) -> String {
        let mut key = [0u8; 32];
        rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut key);
        self.prune_pending();
        self.pending.push(PendingCode {
            key: base32_encode(&key),
            created: now_secs(),
        });
        while self.pending.len() > MAX_PENDING_CODES {
            self.pending.remove(0);
        }
        pair_uri(&key, &self.device_id, &self.name)
    }

    /// Drops codes nobody used in time.
    pub fn prune_pending(&mut self) {
        let now = now_secs();
        self.pending
            .retain(|c| now.saturating_sub(c.created) <= PENDING_CODE_TTL_SECS);
    }

    /// Takes a code scanned or typed off another device: that device becomes a peer under
    /// the code's key, here and now. Its OS is learned on first contact; until then the
    /// entry carries none, which the hotspot route treats as "scan for it again".
    pub fn add_peer_from_code(&mut self, code: &PairCode) {
        let device_id = base32_encode(&code.device_id);
        let key = base32_encode(&code.key);
        match self.peers.iter_mut().find(|p| p.device_id == device_id) {
            Some(existing) => {
                // Re-pairing a device we already know replaces the key and nothing else:
                // the folder and the auto-accept decision are ours, not the code's.
                existing.key = key;
                if let Some(name) = &code.name {
                    existing.name = name.clone();
                }
            }
            None => self.peers.push(PairedPeer {
                device_id,
                key,
                name: code.name.clone().unwrap_or_default(),
                os: String::new(),
                last_ip: None,
                last_seen: 0,
                auto_accept: true,
            }),
        }
    }

    /// A device has just proved it holds one of our keys. Records it as the peer that key
    /// belongs to — and if the key was a pending code, the code is spent.
    ///
    /// The key is the identity here: a peer arriving under a *different* key than the one
    /// stored for it has re-paired, and the new key replaces the old. The user's own
    /// decisions about the peer (auto-accept) are kept, as `upsert_peer` keeps them.
    pub fn claim(&mut self, key: &[u8; 32], peer: PairedPeer) {
        let encoded = base32_encode(key);
        self.pending.retain(|c| c.key != encoded);
        let mut peer = peer;
        peer.key = encoded;
        self.upsert_peer(peer);
    }

    pub fn upsert_peer(&mut self, peer: PairedPeer) {
        match self.peers.iter_mut().find(|p| p.device_id == peer.device_id) {
            // A peer we already know keeps its auto_accept decision: that is the user's,
            // not the announcement's, and an announcement must never be able to grant
            // itself unattended write access.
            Some(existing) => {
                if !peer.name.is_empty() {
                    existing.name = peer.name;
                }
                if !peer.os.is_empty() {
                    existing.os = peer.os;
                }
                if !peer.key.is_empty() {
                    existing.key = peer.key;
                }
                if peer.last_ip.is_some() {
                    existing.last_ip = peer.last_ip;
                }
                if peer.last_seen > existing.last_seen {
                    existing.last_seen = peer.last_seen;
                }
            }
            None => self.peers.push(peer),
        }
    }

    pub fn forget_peer(&mut self, device_id: &str) {
        self.peers.retain(|p| p.device_id != device_id);
    }

    pub fn find_peer(&self, device_id: &str) -> Option<&PairedPeer> {
        self.peers.iter().find(|p| p.device_id == device_id)
    }

    /// Records where a peer was last reached, so the next send can skip discovery.
    pub fn note_seen(&mut self, device_id: &str, ip: &str) {
        if let Some(peer) = self.peers.iter_mut().find(|p| p.device_id == device_id) {
            peer.last_ip = Some(ip.to_string());
            peer.last_seen = now_secs();
        }
    }
}

pub fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

/// Domain label for the key id. Byte-identical to Pairing.kt.
pub const KEY_ID_INFO: &[u8] = b"mahojutan key id v1";

/// Four bytes that name a key without revealing it: the first four of an HMAC of the key
/// under its own label. They travel in the clear — in the presence record and in the hello
/// before the Noise handshake — so the receiving side can pick the right key out of the
/// handful it holds instead of trying each. A collision between two of one person's keys
/// is a one-in-four-billion event and costs nothing worse than one extra HMAC check.
pub fn key_id(key: &[u8; 32]) -> [u8; 4] {
    let mac = crate::utils::compute_hmac(key, KEY_ID_INFO);
    [mac[0], mac[1], mac[2], mac[3]]
}

/// One key this device holds, and whom it is shared with. `device_id` is None for a code
/// that has been shown but not yet claimed.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PairKey {
    pub key: [u8; 32],
    pub key_id: [u8; 4],
    pub device_id: Option<[u8; 16]>,
}

impl PairKey {
    pub fn bound(key: [u8; 32], device_id: [u8; 16]) -> Self {
        PairKey {
            key,
            key_id: key_id(&key),
            device_id: Some(device_id),
        }
    }

    pub fn pending(key: [u8; 32]) -> Self {
        PairKey {
            key,
            key_id: key_id(&key),
            device_id: None,
        }
    }

    pub fn is_pending(&self) -> bool {
        self.device_id.is_none()
    }
}

/// Everything this device could authenticate a peer with.
#[derive(Clone, Debug, Default)]
pub struct KeyRing {
    pub keys: Vec<PairKey>,
}

impl KeyRing {
    /// The keys that could have produced something from `device_id` under `key_id`: the
    /// key bound to that very device if the id matches, and every unclaimed code with that
    /// id. A key bound to *another* device is never a candidate — a peer's key vouches for
    /// that peer alone.
    pub fn candidates(&self, device_id: &[u8; 16], key_id: &[u8; 4]) -> Vec<&PairKey> {
        self.keys
            .iter()
            .filter(|k| &k.key_id == key_id)
            .filter(|k| match &k.device_id {
                Some(bound) => bound == device_id,
                None => true,
            })
            .collect()
    }

    pub fn for_device(&self, device_id: &[u8; 16]) -> Option<&PairKey> {
        self.keys
            .iter()
            .find(|k| k.device_id.as_ref() == Some(device_id))
    }

    pub fn is_empty(&self) -> bool {
        self.keys.is_empty()
    }
}

/// What a pairing code says: the key, who showed it, and what they call themselves.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PairCode {
    pub key: [u8; 32],
    pub device_id: [u8; 16],
    pub name: Option<String>,
}

fn decode_key(text: &str) -> Option<[u8; 32]> {
    let bytes = base32_decode(text).ok()?;
    if bytes.len() < 32 {
        return None;
    }
    let mut key = [0u8; 32];
    key.copy_from_slice(&bytes[..32]);
    Some(key)
}

fn decode_id(text: &str) -> Result<[u8; 16], FCError> {
    let bytes = base32_decode(text)?;
    if bytes.len() < 16 {
        fc_error("Device id is too short")?;
    }
    let mut id = [0u8; 16];
    id.copy_from_slice(&bytes[..16]);
    Ok(id)
}

/// The string that goes into the QR, and — printed in groups underneath it — the thing a
/// human types when there is no camera at the other end.
pub fn pair_uri(key: &[u8; 32], device_id: &str, name: &str) -> String {
    format!(
        "{}{}:{}:{}",
        PAIR_URI_PREFIX,
        base32_encode(key),
        device_id,
        name
    )
}

/// The typed form: key and id run together, 78 characters, grouped in fives for reading.
pub fn typed_code(uri: &str) -> String {
    let body = uri.strip_prefix(PAIR_URI_PREFIX).unwrap_or(uri);
    let mut parts = body.splitn(3, ':');
    let key = parts.next().unwrap_or("");
    let id = parts.next().unwrap_or("");
    let joined = format!("{}{}", key, id);
    joined
        .as_bytes()
        .chunks(5)
        .map(|c| String::from_utf8_lossy(c).into_owned())
        .collect::<Vec<_>>()
        .join(" ")
}

/// Truncates on a character boundary rather than a byte one, so a name of kanji is cut
/// between glyphs and never mid-sequence — a half-encoded character would make the
/// presence record's name field invalid UTF-8 on the other side.
pub fn clamp_name(name: &str) -> String {
    let trimmed = name.trim();
    if trimmed.len() <= MAX_NAME_BYTES {
        return trimmed.to_string();
    }
    let mut end = MAX_NAME_BYTES;
    while end > 0 && !trimmed.is_char_boundary(end) {
        end -= 1;
    }
    trimmed[..end].to_string()
}

/// `~/.config/shiroikuma-mahojutan/paired.json` — deliberately not beside the theme
/// settings, which the backup archive collects wholesale.
pub fn store_path() -> Result<PathBuf, FCError> {
    let base = match std::env::var_os("XDG_CONFIG_HOME") {
        Some(dir) if !dir.is_empty() => PathBuf::from(dir),
        _ => match std::env::var_os("HOME") {
            Some(home) => PathBuf::from(home).join(".config"),
            None => {
                fc_error("No HOME or XDG_CONFIG_HOME set; cannot locate the pairing store")?;
                unreachable!()
            }
        },
    };
    Ok(base.join("shiroikuma-mahojutan").join("paired.json"))
}

/// Loads the store, creating a fresh identity if there is none. A corrupt file is *not*
/// silently replaced — that would throw away a group key that could still be typed back
/// out of a backup of the file — so it is renamed aside and reported.
pub fn load() -> Result<PairingStore, FCError> {
    let path = store_path()?;
    let text = match fs::read_to_string(&path) {
        Ok(t) => t,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
            let store = PairingStore::fresh(default_device_name());
            save(&store)?;
            return Ok(store);
        }
        Err(e) => return Err(e.into()),
    };
    match serde_json::from_str::<PairingStore>(&text) {
        Ok(mut store) => {
            if migrate_group_key(&mut store) {
                save(&store)?;
            }
            Ok(store)
        }
        Err(e) => {
            let aside = path.with_extension("json.corrupt");
            let _ = fs::rename(&path, &aside);
            fc_error(&format!(
                "Could not read {}: {}. It has been moved to {} and a new identity created; \
                 the old group key is still in that file if it is needed.",
                path.display(),
                e,
                aside.display()
            ))?;
            unreachable!()
        }
    }
}

/// From the group model to pairwise keys: every peer known under the old shared key keeps
/// working, because that key simply becomes the pair key with each of them. Returns
/// whether anything changed.
pub fn migrate_group_key(store: &mut PairingStore) -> bool {
    let Some(group) = store.group_key.take() else {
        return false;
    };
    for peer in store.peers.iter_mut() {
        if peer.key.is_empty() {
            peer.key = group.clone();
        }
    }
    true
}

/// Writes through a temporary file and renames, so an interrupted write cannot leave a
/// half-written key behind — the same reason the backup archive is written to `.part`
/// first.
pub fn save(store: &PairingStore) -> Result<(), FCError> {
    let path = store_path()?;
    if let Some(dir) = path.parent() {
        fs::create_dir_all(dir)?;
    }
    let json = serde_json::to_string_pretty(store)
        .map_err(|e| FCError { message: format!("Could not serialize pairing store: {}", e) })?;
    let temp = path.with_extension("json.part");
    fs::write(&temp, json)?;
    fs::rename(&temp, &path)?;
    Ok(())
}

pub fn default_device_name() -> String {
    std::env::var("HOSTNAME")
        .ok()
        .filter(|h| !h.is_empty())
        .or_else(|| {
            fs::read_to_string("/etc/hostname")
                .ok()
                .map(|h| h.trim().to_string())
                .filter(|h| !h.is_empty())
        })
        .unwrap_or_else(|| "Linux".to_string())
}

/// Parses a scanned or typed pairing code. Accepts the full URI and the typed form — the
/// key and the id run together, with whatever spaces a person put in — because that is
/// what someone retyping from under a QR code will produce.
pub fn parse_pair_uri(text: &str) -> Result<PairCode, FCError> {
    let trimmed = text.trim();
    if trimmed.starts_with(OLD_PAIR_URI_PREFIX) {
        fc_error(
            "That code is from an older version of this app, which paired devices as a \
             group. Update the app on that device and show the code again.",
        )?;
    }
    let (key_part, id_part, name) = match trimmed.strip_prefix(PAIR_URI_PREFIX) {
        Some(rest) => {
            let mut parts = rest.splitn(3, ':');
            let key = parts.next().unwrap_or("");
            let id = parts.next().unwrap_or("");
            let name = parts.next().map(|n| n.to_string());
            (key.to_string(), id.to_string(), name)
        }
        None => {
            // The typed form: 52 characters of key, then 26 of id, separators ignored.
            let compact: String = trimmed
                .chars()
                .filter(|c| !matches!(c, ' ' | '-' | '\t' | '\n' | '\r'))
                .collect();
            if compact.len() < 78 {
                fc_error(
                    "That pairing code is too short — it should be 78 characters. Check it \
                     against the one shown under the QR code on the other device.",
                )?;
            }
            (compact[..52].to_string(), compact[52..].to_string(), None)
        }
    };
    let key_bytes = base32_decode(&key_part)?;
    if key_bytes.len() < 32 {
        fc_error("That pairing code is too short — it should be 78 characters.")?;
    }
    let mut key = [0u8; 32];
    key.copy_from_slice(&key_bytes[..32]);
    let device_id = decode_id(&id_part)?;
    Ok(PairCode {
        key,
        device_id,
        name: name.map(|n| clamp_name(&n)).filter(|n| !n.is_empty()),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn base32_round_trips() {
        let data: Vec<u8> = (0u8..=255).collect();
        let encoded = base32_encode(&data);
        let decoded = base32_decode(&encoded).unwrap();
        assert_eq!(decoded[..data.len()], data[..]);
    }

    #[test]
    fn base32_tolerates_typed_input() {
        let key = [0x9au8; 32];
        let encoded = base32_encode(&key);
        let typed = format!(
            "{}-{}",
            &encoded[..26].to_lowercase(),
            &encoded[26..].to_lowercase()
        );
        assert_eq!(&base32_decode(&typed).unwrap()[..32], &key[..]);
    }

    // O and 0 are not the same character but they are the same keystroke to someone
    // copying by eye, and a key that fails for that reason is indistinguishable from a
    // broken feature.
    #[test]
    fn base32_folds_confusable_letters() {
        let encoded = base32_encode(&[0u8; 32]);
        assert!(encoded.starts_with('0'));
        let mistyped = encoded.replacen('0', "O", 1);
        assert_eq!(base32_decode(&mistyped).unwrap(), base32_decode(&encoded).unwrap());
    }

    #[test]
    fn pair_uri_round_trips_with_a_japanese_name() {
        let mut store = PairingStore::fresh("白い熊二代目".to_string());
        let uri = store.new_code();
        let code = parse_pair_uri(&uri).unwrap();
        assert_eq!(code.device_id, store.device_id_bytes());
        assert_eq!(code.name.as_deref(), Some("白い熊二代目"));
        assert_eq!(store.pending.len(), 1);
        assert_eq!(base32_encode(&code.key), store.pending[0].key);
    }

    #[test]
    fn the_typed_form_pairs_without_the_uri_wrapper() {
        let mut store = PairingStore::fresh("desk".to_string());
        let uri = store.new_code();
        let typed = typed_code(&uri);
        assert_eq!(typed.replace(' ', "").len(), 78);
        let code = parse_pair_uri(&typed.to_lowercase()).unwrap();
        assert_eq!(code.device_id, store.device_id_bytes());
        assert_eq!(base32_encode(&code.key), store.pending[0].key);
        assert_eq!(code.name, None);
    }

    #[test]
    fn an_old_group_code_is_refused_with_a_reason() {
        let old = format!("{}{}:phone", OLD_PAIR_URI_PREFIX, base32_encode(&[1u8; 32]));
        let message = parse_pair_uri(&old).unwrap_err().message;
        assert!(message.contains("older version"), "{}", message);
    }

    /// The whole pairing, both directions, on the store alone: A shows, B scans, B is
    /// listed on A the moment it proves the key, and the code is spent.
    #[test]
    fn a_code_pairs_exactly_one_device_and_is_then_spent() {
        let mut a = PairingStore::fresh("A".to_string());
        let mut b = PairingStore::fresh("B".to_string());
        let code = parse_pair_uri(&a.new_code()).unwrap();
        b.add_peer_from_code(&code);
        assert_eq!(b.peers.len(), 1);
        assert_eq!(b.peers[0].device_id, a.device_id);
        assert_eq!(b.key_for(&a.device_id), Some(code.key));

        // B introduces itself under the key; A's ring accepts it as pending...
        let ring = a.key_ring();
        let found = ring.candidates(&b.device_id_bytes(), &key_id(&code.key));
        assert_eq!(found.len(), 1);
        assert!(found[0].is_pending());
        // ...and claiming binds it to B and spends the code.
        a.claim(
            &code.key,
            PairedPeer {
                device_id: b.device_id.clone(),
                key: String::new(),
                name: "B".into(),
                os: "android".into(),
                last_ip: Some("10.0.0.2".into()),
                last_seen: 5,
                auto_accept: true,
            },
        );
        assert!(a.pending.is_empty());
        assert_eq!(a.key_for(&b.device_id), Some(code.key));
        // A third device cannot use the same code.
        let ring = a.key_ring();
        assert!(ring.candidates(&[9u8; 16], &key_id(&code.key)).is_empty());
        // But B, now bound, still can.
        assert_eq!(ring.candidates(&b.device_id_bytes(), &key_id(&code.key)).len(), 1);
    }

    /// Pairing a third device must not touch the first pairing — the bug that started all
    /// of this.
    #[test]
    fn a_second_pairing_leaves_the_first_alone() {
        let mut a = PairingStore::fresh("A".to_string());
        let mut b = PairingStore::fresh("B".to_string());
        let mut c = PairingStore::fresh("C".to_string());
        let ab = parse_pair_uri(&b.new_code()).unwrap();
        a.add_peer_from_code(&ab);
        let ca = parse_pair_uri(&c.new_code()).unwrap();
        a.add_peer_from_code(&ca);
        assert_eq!(a.peers.len(), 2);
        assert_eq!(a.key_for(&b.device_id), Some(ab.key));
        assert_eq!(a.key_for(&c.device_id), Some(ca.key));
        assert_ne!(ab.key, ca.key);
    }

    #[test]
    fn the_group_key_migrates_onto_every_peer() {
        let mut store = PairingStore::fresh("desk".to_string());
        store.group_key = Some(base32_encode(&[7u8; 32]));
        store.peers.push(PairedPeer {
            device_id: base32_encode(&[1u8; 16]),
            key: String::new(),
            name: "phone".into(),
            os: "android".into(),
            last_ip: None,
            last_seen: 0,
            auto_accept: true,
        });
        assert!(migrate_group_key(&mut store));
        assert!(store.group_key.is_none());
        assert_eq!(store.key_for(&base32_encode(&[1u8; 16])), Some([7u8; 32]));
        assert!(!migrate_group_key(&mut store));
    }

    #[test]
    fn key_id_is_stable_and_short() {
        let id = key_id(&[0xABu8; 32]);
        assert_eq!(id, key_id(&[0xABu8; 32]));
        assert_ne!(id, key_id(&[0xACu8; 32]));
    }

    #[test]
    fn clamp_name_cuts_between_glyphs() {
        let long = "白".repeat(40); // 120 bytes
        let clamped = clamp_name(&long);
        assert!(clamped.len() <= MAX_NAME_BYTES);
        assert_eq!(clamped.chars().count(), MAX_NAME_BYTES / 3);
    }

    #[test]
    fn upsert_keeps_the_users_auto_accept_decision() {
        let mut store = PairingStore::fresh("desk".to_string());
        store.upsert_peer(PairedPeer {
            device_id: "AAAA".into(),
            key: String::new(),
            name: "phone".into(),
            os: "android".into(),
            last_ip: None,
            last_seen: 0,
            auto_accept: false,
        });
        store.upsert_peer(PairedPeer {
            device_id: "AAAA".into(),
            key: String::new(),
            name: "phone renamed".into(),
            os: "android".into(),
            last_ip: Some("192.168.128.7".into()),
            last_seen: 100,
            auto_accept: true,
        });
        let peer = store.find_peer("AAAA").unwrap();
        assert_eq!(peer.name, "phone renamed");
        assert_eq!(peer.last_ip.as_deref(), Some("192.168.128.7"));
        assert!(!peer.auto_accept, "an announcement must not grant itself auto-accept");
    }
}
