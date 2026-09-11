// Fork: paired devices — identity, the group key, and the list of devices we know.
//
// The stock app has no concept of "who am I" or "who is that": a transfer is negotiated
// from scratch every time, keyed on a password that is generated, shown, and thrown away.
// That is what makes both devices have to be armed in the same minute. This module is the
// missing half: a device carries a name and an id, a group of devices shares one 32-byte
// key, and from that key everything else is derived (noise.rs: derive_presence_key,
// derive_paired_psk, derive_hotspot_password).
//
// The group key is a secret and it is device-local. It must never reach the Export/Import
// archive — Backup.kt sweeps up anything living in the shared preferences file, and the
// desktop's backup.js does the same for localStorage, so this store deliberately lives
// somewhere neither of them looks.
//
// The Kotlin port is Pairing.kt. The base32 codec and the QR payload format must match it
// byte for byte, because that is how a phone and this desktop pair.

use crate::error::{fc_error, FCError};
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;
use std::time::{SystemTime, UNIX_EPOCH};

/// Crockford's alphabet: no I, L, O or U, so nothing in a typed key can be confused with
/// 1, 0, or read as a word. Decoding folds the confusable letters back in anyway, so a
/// key typed with an O instead of a 0 still works.
const BASE32_ALPHABET: &[u8] = b"0123456789ABCDEFGHJKMNPQRSTVWXYZ";

/// What a pairing QR contains. Version 1 is `mahojutan-pair:1:<base32 key>:<name>` — the
/// name is last so it may contain colons without an escaping rule.
pub const PAIR_URI_PREFIX: &str = "mahojutan-pair:1:";

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
    /// 32 base32 characters — the peer's `device_id`, stored as text so the file is
    /// readable and so serde needs no byte-array helper.
    pub device_id: String,
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

#[derive(Clone, Debug, Serialize, Deserialize, Default)]
pub struct PairingStore {
    /// 52 base32 characters, or absent when this device has not been paired with anything.
    /// Absent is the shipped state and every paired code path must tolerate it.
    pub group_key: Option<String>,
    pub device_id: String,
    pub name: String,
    #[serde(default)]
    pub peers: Vec<PairedPeer>,
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
    /// The store as a brand-new device would have it: an identity, a name, and no group.
    pub fn fresh(name: String) -> Self {
        let mut id = [0u8; 16];
        rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut id);
        PairingStore {
            group_key: None,
            device_id: base32_encode(&id),
            name: clamp_name(&name),
            peers: Vec::new(),
            stay_reachable: false,
            receive_dir: None,
        }
    }

    pub fn group_key_bytes(&self) -> Option<[u8; 32]> {
        let text = self.group_key.as_ref()?;
        let bytes = base32_decode(text).ok()?;
        if bytes.len() < 32 {
            return None;
        }
        let mut key = [0u8; 32];
        key.copy_from_slice(&bytes[..32]);
        Some(key)
    }

    pub fn device_id_bytes(&self) -> [u8; 16] {
        let mut id = [0u8; 16];
        if let Ok(bytes) = base32_decode(&self.device_id) {
            let n = bytes.len().min(16);
            id[..n].copy_from_slice(&bytes[..n]);
        }
        id
    }

    /// Starts a group. The device that does this is the one whose QR the others scan;
    /// there is no other distinction between members afterwards.
    pub fn create_group(&mut self) -> [u8; 32] {
        let mut key = [0u8; 32];
        rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut key);
        self.group_key = Some(base32_encode(&key));
        key
    }

    /// The string that goes into the QR, and — printed in groups underneath it — the thing
    /// a human types when there is no camera at the other end.
    pub fn pair_uri(&self) -> Option<String> {
        Some(format!(
            "{}{}:{}",
            PAIR_URI_PREFIX,
            self.group_key.as_ref()?,
            self.name
        ))
    }

    pub fn upsert_peer(&mut self, peer: PairedPeer) {
        match self.peers.iter_mut().find(|p| p.device_id == peer.device_id) {
            // A peer we already know keeps its auto_accept decision: that is the user's,
            // not the announcement's, and an announcement must never be able to grant
            // itself unattended write access.
            Some(existing) => {
                existing.name = peer.name;
                existing.os = peer.os;
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
        Ok(store) => Ok(store),
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

/// Writes through a temporary file and renames, so an interrupted write cannot leave a
/// half-written group key behind — the same reason the backup archive is written to
/// `.part` first.
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

/// Parses a scanned or typed pairing payload. Accepts the bare key on its own as well as
/// the full URI, because that is what a human retyping from under a QR code will produce.
pub fn parse_pair_uri(text: &str) -> Result<([u8; 32], Option<String>), FCError> {
    let trimmed = text.trim();
    let (key_part, name) = match trimmed.strip_prefix(PAIR_URI_PREFIX) {
        Some(rest) => match rest.split_once(':') {
            Some((key, name)) => (key, Some(name.to_string())),
            None => (rest, None),
        },
        None => (trimmed, None),
    };
    let bytes = base32_decode(key_part)?;
    if bytes.len() < 32 {
        fc_error("That pairing key is too short — it should be 52 characters.")?;
    }
    let mut key = [0u8; 32];
    key.copy_from_slice(&bytes[..32]);
    Ok((key, name.map(|n| clamp_name(&n)).filter(|n| !n.is_empty())))
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
        let key = store.create_group();
        let uri = store.pair_uri().unwrap();
        let (parsed_key, parsed_name) = parse_pair_uri(&uri).unwrap();
        assert_eq!(parsed_key, key);
        assert_eq!(parsed_name.as_deref(), Some("白い熊二代目"));
    }

    #[test]
    fn a_bare_key_pairs_without_the_uri_wrapper() {
        let key = [0x42u8; 32];
        let (parsed, name) = parse_pair_uri(&base32_encode(&key)).unwrap();
        assert_eq!(parsed, key);
        assert_eq!(name, None);
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
            name: "phone".into(),
            os: "android".into(),
            last_ip: None,
            last_seen: 0,
            auto_accept: false,
        });
        store.upsert_peer(PairedPeer {
            device_id: "AAAA".into(),
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
