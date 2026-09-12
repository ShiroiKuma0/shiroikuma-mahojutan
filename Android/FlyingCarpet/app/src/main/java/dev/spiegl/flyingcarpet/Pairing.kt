package dev.spiegl.flyingcarpet

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

// Fork: paired devices — identity, the pair keys, and the list of devices we know.
//
// The Kotlin twin of core/src/pairing.rs. The base32 codec, the pairing URI, the key id and
// the three key derivations must produce byte-identical results to the Rust reference,
// because that is how this phone and the desktop pair; PairingUnitTest.kt holds the same
// known-answer vectors as noise.rs's paired_derivations_known_answer.
//
// Pairwise, not a group (白い熊, 2026-09-11). The first design shared one key between all
// of a person's devices, and pairing a third device by scanning *its* fresh code silently
// replaced the key on the phone that scanned it — stranding the phone it was paired with
// before. Now every pair of devices shares its own key: either one shows a code, the other
// scans or types it, and a third pairing adds a key without touching the others. The shown
// code's key stays *pending* on the device that showed it until a device proves it holds
// it, and that device becomes the peer the key belongs to. One code, one pairing.
//
// Everything here lives in its OWN preferences file, never in `shiroikuma_ui`. That is not
// tidiness: Backup.isAppearanceKey sweeps up every key in `shiroikuma_ui` that is not a
// `page.*` one, so a key stored there would be written into the Export/Import archive — a
// plaintext credential travelling in a backup 白い熊 might move between devices or hand to
// a sister app. It also follows the `mahojutan_local` precedent (Backup.kt), which keeps
// the backup folder out of backups for the same reason.

private const val PAIRING_PREFS = "mahojutan_pairing"
/** The group model's key. Read once for migration, then removed. */
private const val KEY_GROUP = "group_key"
private const val KEY_DEVICE_ID = "device_id"
private const val KEY_NAME = "device_name"
private const val KEY_PEERS = "peers"
private const val KEY_PENDING = "pending_codes"
private const val KEY_STAY_REACHABLE = "stay_reachable"
private const val KEY_AUTO_ACCEPT_CEILING = "auto_accept_max_bytes"
private const val KEY_BLE_WAKE = "ble_wake"
private const val KEY_SHARE_HOTSPOT = "share_over_hotspot"

/**
 * `mahojutan-pair:2:<base32 key>:<base32 device id>:<name>` — the name last, so it may
 * contain colons. Version 1 carried a group key and is refused with a reason.
 */
const val PAIR_URI_PREFIX = "mahojutan-pair:2:"
private const val OLD_PAIR_URI_PREFIX = "mahojutan-pair:1:"

/** Codes shown but never used are dropped after a day, and never more than eight stand. */
private const val PENDING_CODE_TTL_SECS = 24L * 3600L
private const val MAX_PENDING_CODES = 8

/** Bounded by the presence record's single length byte. 48 bytes is sixteen kanji. */
const val MAX_NAME_BYTES = 48

// Domain-separation labels. Byte-identical to noise.rs / pairing.rs.
private val PAIRED_PRESENCE_INFO = "mahojutan presence v1".toByteArray(Charsets.UTF_8)
private val PAIRED_PSK_INFO = "mahojutan paired psk v1".toByteArray(Charsets.UTF_8)
private val PAIRED_HOTSPOT_INFO = "mahojutan hotspot v1".toByteArray(Charsets.UTF_8)
private val KEY_ID_INFO = "mahojutan key id v1".toByteArray(Charsets.UTF_8)

/**
 * Crockford's alphabet: no I, L, O or U, so nothing in a typed key can be misread as 1 or 0
 * or accidentally spell a word. Decoding folds the confusable letters back in, so a key
 * copied by eye with an O for a 0 still works.
 */
private const val BASE32_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

fun base32Encode(data: ByteArray): String {
    val out = StringBuilder((data.size * 8 + 4) / 5)
    var buffer = 0
    var bits = 0
    for (byte in data) {
        buffer = (buffer shl 8) or (byte.toInt() and 0xff)
        bits += 8
        while (bits >= 5) {
            bits -= 5
            out.append(BASE32_ALPHABET[(buffer shr bits) and 0x1f])
        }
    }
    if (bits > 0) out.append(BASE32_ALPHABET[(buffer shl (5 - bits)) and 0x1f])
    return out.toString()
}

/** Returns null rather than throwing: every caller here is parsing something a human typed. */
fun base32Decode(text: String): ByteArray? {
    val out = java.io.ByteArrayOutputStream(text.length * 5 / 8 + 1)
    var buffer = 0
    var bits = 0
    for (raw in text) {
        val c = when (raw) {
            ' ', '-', '\t', '\n', '\r' -> continue
            'o', 'O' -> '0'
            'i', 'I', 'l', 'L' -> '1'
            else -> raw.uppercaseChar()
        }
        val value = BASE32_ALPHABET.indexOf(c)
        if (value < 0) return null
        buffer = (buffer shl 5) or value
        bits += 5
        if (bits >= 8) {
            bits -= 8
            out.write((buffer shr bits) and 0xff)
        }
    }
    return out.toByteArray()
}

/** Authenticates presence announcements. Byte-identical to noise.rs derive_presence_key. */
fun derivePresenceKey(pairKey: ByteArray): ByteArray = computeHmac(pairKey, PAIRED_PRESENCE_INFO)

/** The Noise PSK for a paired transfer. Byte-identical to noise.rs derive_paired_psk. */
fun derivePairedPsk(pairKey: ByteArray): ByteArray = computeHmac(pairKey, PAIRED_PSK_INFO)

/**
 * Four bytes that name a key without revealing it, so the receiving side can pick the right
 * one out of the handful it holds. Travels in the clear. Byte-identical to pairing.rs key_id.
 */
fun keyId(key: ByteArray): ByteArray = computeHmac(key, KEY_ID_INFO).copyOf(4)

/**
 * One key this device holds, and whom it is shared with — null for an unclaimed code. The
 * id is that of the *pair* key even when [key] is a derived one (see [KeyRing.forPresence]),
 * because the id is what travels on the wire and it always names the pair key.
 */
class PairKey(val key: ByteArray, val deviceId: ByteArray?, val keyId: ByteArray = keyId(key)) {
    val isPending: Boolean get() = deviceId == null

    companion object {
        fun bound(key: ByteArray, deviceId: ByteArray) = PairKey(key, deviceId)
        fun pending(key: ByteArray) = PairKey(key, null)
    }
}

/** Everything this device could authenticate a peer with. */
class KeyRing(val keys: List<PairKey>) {
    /**
     * The keys that could have produced something from [deviceId] under [keyId]: the key
     * bound to that very device, and every unclaimed code with that id. A key bound to
     * another device is never a candidate — a peer's key vouches for that peer alone.
     */
    fun candidates(deviceId: ByteArray, keyId: ByteArray): List<PairKey> =
        keys.filter { it.keyId.contentEquals(keyId) }
            .filter { it.deviceId == null || it.deviceId.contentEquals(deviceId) }

    fun forDevice(deviceId: ByteArray): PairKey? =
        keys.find { it.deviceId != null && it.deviceId.contentEquals(deviceId) }

    /** The same ring with every key replaced by its presence key, ids and bindings kept. */
    fun forPresence(): KeyRing =
        KeyRing(keys.map { PairKey(derivePresenceKey(it.key), it.deviceId, it.keyId) })

    /** The pair key a presence key was derived from, if it is one of ours. */
    fun pairKeyBehind(presenceKey: ByteArray): ByteArray? =
        keys.map { it.key }.find { derivePresenceKey(it).contentEquals(presenceKey) }

    val isEmpty: Boolean get() = keys.isEmpty()
}

/** What a pairing code says: the key, who showed it, and what they call themselves. */
data class PairCode(val key: ByteArray, val deviceId: ByteArray, val name: String?) {
    val deviceIdText: String get() = base32Encode(deviceId)
}

/**
 * The Wi-Fi credential a paired hotspot uses, so neither side has to be told it. Mapped into
 * the same 57-symbol alphabet and length as generatePassword(), because everything
 * downstream — the SSID derivation, the WPA2 passphrase, the Wi-Fi Direct group name
 * `DIRECT-fc-<pw>` — already expects exactly that shape.
 *
 * Rejection sampling rather than a bare `% 57`: 256 is not a multiple of 57, so a plain
 * modulo would make the first 28 symbols of the alphabet nearly twice as likely as the rest.
 * Byte-identical to noise.rs derive_hotspot_password.
 */
fun deriveHotspotPassword(pairKey: ByteArray): String {
    val alphabet = "23456789abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ"
    val length = 10
    val rejectAt = 228 // 4 * 57
    val password = StringBuilder(length)
    var counter = 0
    while (password.length < length) {
        val block = computeHmac(pairKey, PAIRED_HOTSPOT_INFO + byteArrayOf(counter.toByte()))
        for (b in block) {
            val v = b.toInt() and 0xff
            if (v >= rejectAt) continue
            password.append(alphabet[v % alphabet.length])
            if (password.length == length) break
        }
        counter = (counter + 1) and 0xff
    }
    return password.toString()
}

/**
 * Truncates on a character boundary, never a byte one: half an encoded kanji would make the
 * presence record's name field invalid UTF-8 on the far side, and the device would simply
 * never appear.
 */
fun clampName(name: String): String {
    val trimmed = name.trim()
    if (trimmed.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES) return trimmed
    var end = trimmed.length
    while (end > 0 && trimmed.substring(0, end).toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) {
        end--
    }
    return trimmed.substring(0, end)
}

/**
 * One device we have paired with. [lastIp] is what makes an idle receiver cheap: a sender
 * tries it directly before broadcasting anything, and a unicast packet reaches a phone whose
 * Wi-Fi driver is filtering multicast and broadcast — which is the state a phone on a desk
 * is in nearly all the time.
 */
data class PairedPeer(
    val deviceId: String,
    val name: String,
    val os: String,
    val lastIp: String?,
    val lastSeen: Long,
    val autoAccept: Boolean,
    /**
     * The key this device and the peer share, base32, and nobody else's. Empty only for a
     * moment during migration from the group model.
     */
    val key: String = "",
    /**
     * Where files from *this* device land. Seeded when the device is paired from whatever
     * the main screen's "Receive in …" button is pointing at, so a newly paired device works
     * immediately; changeable per device afterwards, because "photos from the other phone"
     * and "documents from the desktop" rarely want the same folder.
     */
    val receiveDir: String? = null,
    /**
     * A name chosen here, which wins over the one the device announces. Needed because
     * [name] is refreshed from every announcement, so a rename stored there would be undone
     * by the next scan — and because what 白い熊 calls a device is not the device's business.
     */
    val alias: String? = null,
) {
    /** What to show: the local name if there is one, then the announced one, then the id. */
    val displayName: String
        get() = alias?.takeIf { it.isNotEmpty() }
            ?: name.takeIf { it.isNotEmpty() }
            ?: deviceId.take(6)

    val keyBytes: ByteArray?
        get() = base32Decode(key)?.takeIf { it.size >= 32 }?.copyOf(32)

    fun toJson(): JSONObject = JSONObject().apply {
        put("device_id", deviceId)
        put("key", key)
        put("name", name)
        put("os", os)
        put("last_ip", lastIp ?: JSONObject.NULL)
        put("last_seen", lastSeen)
        put("auto_accept", autoAccept)
        put("receive_dir", receiveDir ?: JSONObject.NULL)
        put("alias", alias ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): PairedPeer? {
            val id = o.optString("device_id").takeIf { it.isNotEmpty() } ?: return null
            return PairedPeer(
                deviceId = id,
                key = o.optString("key"),
                name = o.optString("name"),
                os = o.optString("os", ""),
                lastIp = o.optString("last_ip").takeIf { it.isNotEmpty() && it != "null" },
                lastSeen = o.optLong("last_seen", 0),
                autoAccept = o.optBoolean("auto_accept", true),
                receiveDir = o.optString("receive_dir")
                    .takeIf { it.isNotEmpty() && it != "null" },
                alias = o.optString("alias").takeIf { it.isNotEmpty() && it != "null" },
            )
        }
    }
}

/**
 * The store. Reads and writes go straight through to preferences rather than being cached,
 * because a foreground service, the Activity and a transfer coroutine all touch this and a
 * stale in-memory copy in any one of them would show devices that are not there.
 */
class Pairing(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PAIRING_PREFS, Context.MODE_PRIVATE
    )

    init {
        // A device has an identity from the first time it is asked for one, whether or not
        // it ever pairs: the id is what a peer remembers it by, and generating it lazily at
        // pairing time would mean a device that re-pairs looks like a different one.
        if (prefs.getString(KEY_DEVICE_ID, null).isNullOrEmpty()) {
            val id = ByteArray(16)
            SecureRandom().nextBytes(id)
            prefs.edit()
                .putString(KEY_DEVICE_ID, base32Encode(id))
                .putString(KEY_NAME, clampName(defaultDeviceName()))
                .apply()
        }
        migrateGroupKey()
    }

    /**
     * From the group model to pairwise keys: every peer known under the old shared key keeps
     * working, because that key simply becomes the pair key with each of them.
     */
    private fun migrateGroupKey() {
        val group = prefs.getString(KEY_GROUP, null) ?: return
        savePeers(peers().map { if (it.key.isEmpty()) it.copy(key = group) else it })
        prefs.edit().remove(KEY_GROUP).apply()
    }

    val deviceId: String get() = prefs.getString(KEY_DEVICE_ID, "") ?: ""

    val deviceIdBytes: ByteArray
        get() = (base32Decode(deviceId) ?: ByteArray(16)).copyOf(16)

    var name: String
        get() = prefs.getString(KEY_NAME, "")?.takeIf { it.isNotEmpty() } ?: defaultDeviceName()
        set(value) {
            prefs.edit().putString(KEY_NAME, clampName(value)).apply()
        }

    var stayReachable: Boolean
        get() = prefs.getBoolean(KEY_STAY_REACHABLE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_STAY_REACHABLE, value).apply()
        }

    /**
     * Refuse an unattended transfer bigger than this, in bytes; 0 means no ceiling, which is
     * the default and is the honest one. The peer is already authenticated by a 256-bit key
     * only 白い熊's own devices hold, so this is a guard against a device filling this one's
     * storage by accident, not against an intruder — and a ceiling that exists by default
     * would only ever refuse transfers that were meant.
     */
    var autoAcceptMaxBytes: Long
        get() = prefs.getLong(KEY_AUTO_ACCEPT_CEILING, 0L)
        set(value) {
            prefs.edit().putLong(KEY_AUTO_ACCEPT_CEILING, maxOf(0L, value)).apply()
        }

    /**
     * Let a paired device wake this one over Bluetooth to raise or join a hotspot. Off by
     * default and only meaningful while [stayReachable] is on — see BleWake.kt for why this
     * is the last thing in the feature and the first thing to turn off.
     */
    var bleWake: Boolean
        get() = prefs.getBoolean(KEY_BLE_WAKE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_BLE_WAKE, value).apply()
        }

    /**
     * Which route the share sheet's device pills will take: false for this network, true for
     * a hotspot. Remembered, because somebody who has flipped it once has a reason that will
     * still be true the next time they share something.
     */
    var shareOverHotspot: Boolean
        get() = prefs.getBoolean(KEY_SHARE_HOTSPOT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SHARE_HOTSPOT, value).apply()
        }

    /** Paired with at least one device. */
    val isPaired: Boolean get() = peers().isNotEmpty()

    /** Paired, or showing a code somebody may still use: either way a reason to listen. */
    val shouldServe: Boolean get() = peers().isNotEmpty() || pendingCodes().isNotEmpty()

    // ── codes ─────────────────────────────────────────────────────────────────────────────

    private fun pendingCodes(): List<Pair<String, Long>> {
        val raw = prefs.getString(KEY_PENDING, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map {
                val o = array.getJSONObject(it)
                Pair(o.getString("key"), o.optLong("created", 0))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun savePending(codes: List<Pair<String, Long>>) {
        val array = JSONArray()
        codes.forEach { (key, created) ->
            array.put(JSONObject().put("key", key).put("created", created))
        }
        prefs.edit().putString(KEY_PENDING, array.toString()).apply()
    }

    /**
     * Makes a fresh code to show. The key is remembered as pending until a device claims it,
     * and the code says who is showing it, so the scanner can list this device at once.
     */
    fun newCode(): String {
        val key = ByteArray(32)
        SecureRandom().nextBytes(key)
        val now = System.currentTimeMillis() / 1000
        val kept = pendingCodes().filter { now - it.second <= PENDING_CODE_TTL_SECS }
        savePending((kept + Pair(base32Encode(key), now)).takeLast(MAX_PENDING_CODES))
        return pairUri(key, deviceId, name)
    }

    /**
     * Every key this device holds — one per peer, bound to that peer's id, plus the unbound
     * keys of codes shown and not yet claimed.
     */
    fun keyRing(): KeyRing {
        val keys = ArrayList<PairKey>()
        for (peer in peers()) {
            val key = peer.keyBytes ?: continue
            val id = base32Decode(peer.deviceId)?.takeIf { it.size >= 16 }?.copyOf(16) ?: continue
            keys.add(PairKey.bound(key, id))
        }
        for ((text, _) in pendingCodes()) {
            val key = base32Decode(text)?.takeIf { it.size >= 32 }?.copyOf(32) ?: continue
            keys.add(PairKey.pending(key))
        }
        return KeyRing(keys)
    }

    /** The keys that name a peer — what a probe goes out under. */
    fun peerKeys(): List<PairKey> = keyRing().keys.filter { !it.isPending }

    /** The key shared with one peer. */
    fun keyFor(deviceId: String): ByteArray? = findPeer(deviceId)?.keyBytes

    /**
     * Takes a code scanned or typed off another device: that device becomes a peer under the
     * code's key, here and now. Its OS is learned on first contact.
     */
    fun addPeerFromCode(code: PairCode) {
        val id = code.deviceIdText
        val existing = findPeer(id)
        if (existing != null) {
            // Re-pairing a device we already know replaces the key and nothing else: the
            // folder, the alias and the auto-accept decision are ours, not the code's.
            savePeers(
                peers().map {
                    if (it.deviceId == id) {
                        it.copy(key = base32Encode(code.key), name = code.name ?: it.name)
                    } else {
                        it
                    }
                }
            )
        } else {
            savePeers(
                peers() + PairedPeer(
                    deviceId = id,
                    key = base32Encode(code.key),
                    name = code.name.orEmpty(),
                    os = "",
                    lastIp = null,
                    lastSeen = 0,
                    autoAccept = true,
                )
            )
        }
    }

    /**
     * A device has just proved it holds one of our keys. Records it as the peer that key
     * belongs to — and if the key was a pending code, the code is spent. A peer arriving
     * under a different key than the one stored for it has re-paired, and the new key
     * replaces the old; the user's own decisions about it are kept, as [upsertPeer] keeps them.
     */
    fun claim(key: ByteArray, peer: PairedPeer) {
        val encoded = base32Encode(key)
        savePending(pendingCodes().filter { it.first != encoded })
        upsertPeer(peer.copy(key = encoded))
    }

    fun peers(): List<PairedPeer> {
        val raw = prefs.getString(KEY_PEERS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { PairedPeer.fromJson(array.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun findPeer(deviceId: String): PairedPeer? = peers().find { it.deviceId == deviceId }

    private fun savePeers(peers: List<PairedPeer>) {
        val array = JSONArray()
        peers.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_PEERS, array.toString()).apply()
    }

    /**
     * Adds or refreshes a peer. A peer we already know keeps its [PairedPeer.autoAccept]
     * decision: that belongs to 白い熊, not to the announcement, and an announcement that
     * could grant itself unattended write access would make the setting worthless.
     */
    fun upsertPeer(peer: PairedPeer) {
        val current = peers().toMutableList()
        val index = current.indexOfFirst { it.deviceId == peer.deviceId }
        if (index < 0) {
            current.add(peer)
        } else {
            val existing = current[index]
            current[index] = existing.copy(
                name = peer.name.ifEmpty { existing.name },
                os = peer.os.ifEmpty { existing.os },
                key = peer.key.ifEmpty { existing.key },
                lastIp = peer.lastIp ?: existing.lastIp,
                lastSeen = maxOf(peer.lastSeen, existing.lastSeen),
                // Kept, like autoAccept: the folder is 白い熊's choice and an announcement
                // must never be able to move where files from that device land. The alias is
                // kept for the same reason — it exists precisely to survive this.
                receiveDir = existing.receiveDir ?: peer.receiveDir,
                alias = existing.alias,
            )
        }
        savePeers(current)
    }

    fun forgetPeer(deviceId: String) {
        savePeers(peers().filter { it.deviceId != deviceId })
    }

    fun setAutoAccept(deviceId: String, allow: Boolean) {
        savePeers(peers().map { if (it.deviceId == deviceId) it.copy(autoAccept = allow) else it })
    }

    fun setAlias(deviceId: String, alias: String?) {
        savePeers(
            peers().map {
                if (it.deviceId == deviceId) {
                    it.copy(alias = alias?.trim()?.takeIf(String::isNotEmpty)?.let(::clampName))
                } else {
                    it
                }
            }
        )
    }

    /**
     * Rewrites the stored order. The JSON array's order *is* the order — everything that
     * lists peers reads it back in the order it was written — so reordering is a rewrite and
     * needs no index field that could drift out of step with the list.
     *
     * Ids that are not currently stored are ignored, and stored peers the caller forgot to
     * mention keep their places at the end, so a list built from a stale view of the world
     * can never silently drop a device.
     */
    fun reorder(deviceIds: List<String>) {
        val current = peers()
        val byId = current.associateBy { it.deviceId }
        val moved = deviceIds.mapNotNull { byId[it] }
        val untouched = current.filter { peer -> deviceIds.none { it == peer.deviceId } }
        savePeers(moved + untouched)
    }

    fun setReceiveDir(deviceId: String, dir: String?) {
        savePeers(
            peers().map {
                if (it.deviceId == deviceId) it.copy(receiveDir = dir?.takeIf(String::isNotEmpty))
                else it
            }
        )
    }

    /** Records where a peer was last reached, so the next send can skip discovery entirely. */
    fun noteSeen(deviceId: String, ip: String) {
        savePeers(
            peers().map {
                if (it.deviceId == deviceId) {
                    it.copy(lastIp = ip, lastSeen = System.currentTimeMillis() / 1000)
                } else {
                    it
                }
            }
        )
    }
}

/**
 * Android has no user-visible device name an ordinary app may read — Settings.Global
 * DEVICE_NAME is not readable without a permission that is not granted to us — so the
 * model is the honest default, and 白い熊 renames it once.
 */
fun defaultDeviceName(): String {
    val model = Build.MODEL?.trim().orEmpty()
    return clampName(if (model.isNotEmpty()) model else "Android")
}

/** The string that goes into the QR. Byte-identical to pairing.rs pair_uri. */
fun pairUri(key: ByteArray, deviceId: String, name: String): String =
    "$PAIR_URI_PREFIX${base32Encode(key)}:$deviceId:$name"

/** The typed form: key and id run together, 78 characters, grouped in fives for reading. */
fun typedCode(uri: String): String {
    val body = uri.removePrefix(PAIR_URI_PREFIX)
    val parts = body.split(':', limit = 3)
    val joined = parts.getOrElse(0) { "" } + parts.getOrElse(1) { "" }
    return joined.chunked(5).joinToString(" ")
}

/** Whether a scanned string is a code from the group model, which cannot be used. */
fun isOldPairUri(text: String): Boolean = text.trim().startsWith(OLD_PAIR_URI_PREFIX)

/**
 * Parses a scanned or typed pairing code: the full URI, or the typed form — key and id run
 * together with whatever spaces a person put in. Returns null if the text is not one.
 */
fun parsePairUri(text: String): PairCode? {
    val trimmed = text.trim()
    if (trimmed.startsWith(OLD_PAIR_URI_PREFIX)) return null
    val keyPart: String
    val idPart: String
    var name: String? = null
    if (trimmed.startsWith(PAIR_URI_PREFIX)) {
        val parts = trimmed.substring(PAIR_URI_PREFIX.length).split(':', limit = 3)
        keyPart = parts.getOrElse(0) { "" }
        idPart = parts.getOrElse(1) { "" }
        name = parts.getOrNull(2)?.let(::clampName)?.takeIf { it.isNotEmpty() }
    } else {
        val compact = trimmed.filter { it !in " -\t\n\r" }
        if (compact.length < 78) return null
        keyPart = compact.substring(0, 52)
        idPart = compact.substring(52)
    }
    val key = base32Decode(keyPart)?.takeIf { it.size >= 32 }?.copyOf(32) ?: return null
    val id = base32Decode(idPart)?.takeIf { it.size >= 16 }?.copyOf(16) ?: return null
    return PairCode(key, id, name)
}
