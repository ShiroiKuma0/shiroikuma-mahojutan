package dev.spiegl.flyingcarpet

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

// Fork: presence — how a paired device finds the others, and how it says who it is.
//
// The Kotlin twin of core/src/presence.rs; PresenceUnitTest.kt asserts the same bytes.
// Deliberately NOT an extension of Discovery.kt: that record's layout is pinned by a
// known-answer test shared with Apple, it is keyed on the per-transfer password, and it
// answers a different question. New magic, new port, new key.
//
// The cadence was chosen for 白い熊's own network, not a textbook one:
//
//  * **The receiver does not beacon; it answers.** A phone that is merely reachable holds a
//    socket and replies when asked. It broadcasts once, when its address changes. This is
//    what keeps an idle receiver from needing a MulticastLock — and the MulticastLock is the
//    expensive part, because holding one turns off the Wi-Fi chip's multicast filtering and
//    wakes the CPU for every broadcast frame on the network.
//  * **The sender shouts, and only while a device list is on screen.** That puts the cost on
//    the phone whose screen is already lit.
//  * **Broadcast, not just multicast.** On 2026-08-10 a transfer between 白い熊's two phones
//    died on a /21 that dropped multicast between clients (Discovery.kt:18-22). The subnet
//    broadcast address is an ordinary on-link destination needing no group join, and it is
//    what actually arrives there.

const val PRESENCE_PORT = 3291
const val PRESENCE_MULTICAST_ADDR = "239.255.73.68"
val PRESENCE_MAGIC = byteArrayOf(0x46, 0x43, 0x50, 0x52) // "FCPR"
/** Version 2 adds the key id after the device id — see presence.rs. */
const val PRESENCE_VERSION = 2

/** magic(4) version(2) flags(2) os(1) deviceId(16) keyId(4) ip(4) port(2) timestamp(8) nameLen(1) */
const val PRESENCE_HEADER_SIZE = 44
const val PRESENCE_HMAC_SIZE = 32
const val PRESENCE_MIN_SIZE = PRESENCE_HEADER_SIZE + PRESENCE_HMAC_SIZE
const val PRESENCE_MAX_SIZE = PRESENCE_HEADER_SIZE + MAX_NAME_BYTES + PRESENCE_HMAC_SIZE

/** "Answer now." What a sender emits while its device list is open. */
const val FLAG_PROBE = 0x0001
/** "I will accept a transfer without anyone touching me." Advisory; the receiver still decides. */
const val FLAG_UNATTENDED = 0x0002

private const val PRESENCE_TIMESTAMP_WINDOW_SECS = 60L

/**
 * A sweep is capped higher than Discovery.kt's because it runs once per scan rather than on
 * a heartbeat, and because 白い熊's own network is a /21 where it must work.
 */
private const val PRESENCE_MAX_SCAN_HOSTS = 4096
private const val PRESENCE_SCAN_CHUNK = 128
private const val PRESENCE_SCAN_CHUNK_DELAY_MS = 20L

/** Same order as core's `Peer` and presence.rs's `PeerOs`, so the byte means the same thing. */
enum class PeerOs(val value: Int, val label: String) {
    ANDROID(0, "android"),
    IOS(1, "ios"),
    LINUX(2, "linux"),
    MACOS(3, "mac"),
    WINDOWS(4, "windows");

    companion object {
        fun fromByte(b: Int): PeerOs? = entries.find { it.value == b }
        fun fromLabel(label: String): PeerOs? = entries.find { it.label == label }
    }
}

data class LocalIdentity(
    val deviceId: ByteArray,
    val name: String,
    val os: PeerOs,
    val unattended: Boolean,
) {
    // deviceId is a ByteArray, so the generated equals/hashCode would compare by reference
    // and quietly make two identical identities unequal.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LocalIdentity) return false
        return deviceId.contentEquals(other.deviceId) && name == other.name &&
            os == other.os && unattended == other.unattended
    }

    override fun hashCode(): Int =
        deviceId.contentHashCode() * 31 + name.hashCode() * 31 + os.hashCode() * 31 +
            unattended.hashCode()
}

/** A device we have just heard from, and the presence key the exchange was verified under. */
data class DiscoveredPeer(
    val deviceId: String,
    val name: String,
    val os: PeerOs,
    val ip: String,
    val port: Int,
    val unattended: Boolean,
    val presenceKey: ByteArray = ByteArray(0),
)

class PresenceAnnouncement(
    val flags: Int,
    val os: PeerOs,
    val deviceId: ByteArray,
    val keyId: ByteArray,
    val ipAddress: ByteArray,
    val port: Int,
    val timestamp: Long,
    val name: String,
) {
    val isProbe: Boolean get() = flags and FLAG_PROBE != 0
    val isUnattended: Boolean get() = flags and FLAG_UNATTENDED != 0

    /** Everything but the trailing HMAC. Big-endian, like every other integer on this wire. */
    private fun body(): ByteArray {
        val nameBytes = clampName(name).toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(PRESENCE_HEADER_SIZE + nameBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.put(PRESENCE_MAGIC)
        buffer.putShort(PRESENCE_VERSION.toShort())
        buffer.putShort(flags.toShort())
        buffer.put(os.value.toByte())
        buffer.put(deviceId)
        buffer.put(keyId)
        buffer.put(ipAddress)
        buffer.putShort(port.toShort())
        buffer.putLong(timestamp)
        buffer.put(nameBytes.size.toByte())
        buffer.put(nameBytes)
        return buffer.array()
    }

    fun serialize(key: ByteArray): ByteArray {
        val body = body()
        return body + computeHmac(key, body)
    }

    /** The ±60 s replay bound Discovery.kt already uses. */
    fun isFresh(): Boolean {
        val now = System.currentTimeMillis() / 1000
        return kotlin.math.abs(now - timestamp) <= PRESENCE_TIMESTAMP_WINDOW_SECS
    }

    companion object {
        fun create(
            identity: LocalIdentity,
            key: PairKey,
            ip: InetAddress,
            probe: Boolean,
        ): PresenceAnnouncement {
            var flags = 0
            if (probe) flags = flags or FLAG_PROBE
            if (identity.unattended) flags = flags or FLAG_UNATTENDED
            return PresenceAnnouncement(
                flags = flags,
                os = identity.os,
                deviceId = identity.deviceId,
                keyId = key.keyId,
                ipAddress = ip.address,
                port = PRESENCE_PORT,
                timestamp = System.currentTimeMillis() / 1000,
                name = identity.name,
            )
        }

        /**
         * Who sent this and under which key, read off the header before anything is checked.
         * Only ever used to *choose* the key to verify with.
         */
        fun peek(data: ByteArray, length: Int): Pair<ByteArray, ByteArray>? {
            if (length < PRESENCE_MIN_SIZE) return null
            for (i in PRESENCE_MAGIC.indices) if (data[i] != PRESENCE_MAGIC[i]) return null
            val version = ((data[4].toInt() and 0xff) shl 8) or (data[5].toInt() and 0xff)
            if (version != PRESENCE_VERSION) return null
            return Pair(data.copyOfRange(9, 25), data.copyOfRange(25, 29))
        }

        /**
         * [deserialize] against whichever of the ring's keys could have signed this. Returns
         * the announcement and the key that verified it.
         */
        fun open(data: ByteArray, length: Int, ring: KeyRing): Pair<PresenceAnnouncement, PairKey>? {
            val (deviceId, keyId) = peek(data, length) ?: return null
            for (candidate in ring.candidates(deviceId, keyId)) {
                val announcement = deserialize(data, length, candidate.key) ?: continue
                return Pair(announcement, candidate)
            }
            return null
        }

        /**
         * Rejects anything that is not well-formed, authentic and fresh. The HMAC is checked
         * before the name is decoded, so a forged packet can never reach an allocation sized
         * by its own length byte.
         */
        fun deserialize(data: ByteArray, length: Int, key: ByteArray): PresenceAnnouncement? {
            if (length < PRESENCE_MIN_SIZE || length > PRESENCE_MAX_SIZE) return null
            for (i in PRESENCE_MAGIC.indices) if (data[i] != PRESENCE_MAGIC[i]) return null
            val buffer = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)
            buffer.position(4)
            val version = buffer.short.toInt() and 0xffff
            if (version != PRESENCE_VERSION) return null
            val nameLen = data[PRESENCE_HEADER_SIZE - 1].toInt() and 0xff
            if (length != PRESENCE_HEADER_SIZE + nameLen + PRESENCE_HMAC_SIZE) return null

            val bodyLength = PRESENCE_HEADER_SIZE + nameLen
            val body = data.copyOfRange(0, bodyLength)
            val mac = data.copyOfRange(bodyLength, length)
            if (!verifyHmac(key, body, mac)) return null

            val flags = buffer.short.toInt() and 0xffff
            val os = PeerOs.fromByte(buffer.get().toInt() and 0xff) ?: return null
            val deviceId = ByteArray(16).also { buffer.get(it) }
            val keyId = ByteArray(4).also { buffer.get(it) }
            val ipAddress = ByteArray(4).also { buffer.get(it) }
            val port = buffer.short.toInt() and 0xffff
            val timestamp = buffer.long
            buffer.get() // the name length, already read above
            val name = try {
                String(data, PRESENCE_HEADER_SIZE, nameLen, Charsets.UTF_8)
            } catch (e: Exception) {
                return null
            }
            return PresenceAnnouncement(flags, os, deviceId, keyId, ipAddress, port, timestamp, name)
        }
    }
}

/**
 * The all-hosts address of the local subnet. Needs no group join and no driver opt-in, which
 * is exactly why it works on the networks where multicast does not.
 */
fun presenceBroadcastAddress(localIp: InetAddress, prefixLength: Int): InetAddress? {
    if (prefixLength <= 0 || prefixLength > 30) return null
    val ip = ByteBuffer.wrap(localIp.address).order(ByteOrder.BIG_ENDIAN).int
    val mask = (-1 shl (32 - prefixLength))
    val broadcast = (ip and mask) or mask.inv()
    return InetAddress.getByAddress(
        byteArrayOf(
            (broadcast ushr 24).toByte(),
            (broadcast ushr 16).toByte(),
            (broadcast ushr 8).toByte(),
            broadcast.toByte(),
        )
    )
}

fun presenceScanTargets(localIp: InetAddress, prefixLength: Int): List<InetAddress>? {
    if (prefixLength <= 0 || prefixLength > 30) return null
    val ip = ByteBuffer.wrap(localIp.address).order(ByteOrder.BIG_ENDIAN).int
    val mask = (-1 shl (32 - prefixLength))
    val network = ip and mask
    val broadcast = network or mask.inv()
    val hosts = (broadcast.toLong() and 0xffffffffL) - (network.toLong() and 0xffffffffL) - 1
    if (hosts > PRESENCE_MAX_SCAN_HOSTS) return null
    val targets = ArrayList<InetAddress>(hosts.toInt().coerceAtLeast(0))
    var addr = network + 1
    while (addr != broadcast) {
        if (addr != ip) {
            targets.add(
                InetAddress.getByAddress(
                    byteArrayOf(
                        (addr ushr 24).toByte(),
                        (addr ushr 16).toByte(),
                        (addr ushr 8).toByte(),
                        addr.toByte(),
                    )
                )
            )
        }
        addr++
    }
    return targets
}

/** Where this device is on the network it is actually using, and how wide that network is. */
data class LocalEndpoint(val ip: Inet4Address, val prefixLength: Int, val nic: NetworkInterface)

/**
 * The network a paired device could plausibly be on: Wi-Fi for preference, then Ethernet.
 *
 * This asks ConnectivityManager rather than walking NetworkInterface.getNetworkInterfaces(),
 * and the difference is not cosmetic — it was the bug (白い熊, 2026-09-11: scanning a pairing
 * code joined the group and then never found the other device). That enumeration is in kernel
 * index order, so on a phone with mobile data switched on it hands back `rmnet_data0` long
 * before `wlan0`. Every probe then went out on the carrier's interface, to a broadcast address
 * computed from a carrier-assigned /30, and nothing on the Wi-Fi ever heard a thing. Pairing
 * looked like it had worked — the key was stored — and the device list stayed empty for ever.
 *
 * Cellular is excluded outright rather than merely ranked last: two devices are never on the
 * same local network over it, so a probe there is guaranteed waste. VPNs are excluded for the
 * same reason MainViewModel.getSharedNetworkAndIp excludes them — the tunnel's address is not
 * where the peer is. This is deliberately the same rule that function already applies, so the
 * paired routes and the shared-network transfer can never disagree about which network the
 * app is on.
 */
fun presenceEndpoint(context: Context): LocalEndpoint? {
    val manager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
    var wired: LocalEndpoint? = null
    @Suppress("DEPRECATION")
    for (network in manager.allNetworks) {
        val capabilities = manager.getNetworkCapabilities(network) ?: continue
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
        val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isEthernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!isWifi && !isEthernet) continue
        val link = manager.getLinkProperties(network) ?: continue
        val nic = link.interfaceName?.let {
            try {
                NetworkInterface.getByName(it)
            } catch (e: Exception) {
                null
            }
        } ?: continue
        for (linkAddress in link.linkAddresses) {
            val ip = linkAddress.address
            if (ip is Inet4Address && !ip.isLoopbackAddress && !ip.isLinkLocalAddress) {
                val endpoint = LocalEndpoint(ip, linkAddress.prefixLength, nic)
                if (isWifi) return endpoint
                if (wired == null) wired = endpoint
            }
        }
    }
    return wired
}

/**
 * Sends the announcements everywhere they might be heard — one per key, because a peer can
 * only read the one signed under its own, and all of them per address so a sweep costs one
 * pass. [sweep] walks the subnet host by host and is the fallback that rescues a network
 * dropping both multicast and broadcast.
 */
private suspend fun shout(
    socket: DatagramSocket,
    payloads: List<ByteArray>,
    endpoint: LocalEndpoint,
    sweep: Boolean,
) {
    fun sendTo(address: InetAddress) {
        for (payload in payloads) {
            try {
                socket.send(DatagramPacket(payload, payload.size, address, PRESENCE_PORT))
            } catch (e: Exception) {
                // An unreachable host on a sweep is the normal case, not an error.
            }
        }
    }
    sendTo(InetAddress.getByName(PRESENCE_MULTICAST_ADDR))
    presenceBroadcastAddress(endpoint.ip, endpoint.prefixLength)?.let { sendTo(it) }
    if (!sweep) return
    val targets = presenceScanTargets(endpoint.ip, endpoint.prefixLength) ?: return
    // Chunked: two thousand datagrams handed to the stack at once are mostly dropped on a
    // phone, which is what made the equivalent sweep in Discovery.kt need the same treatment.
    for (chunk in targets.chunked(PRESENCE_SCAN_CHUNK)) {
        chunk.forEach(::sendTo)
        delay(PRESENCE_SCAN_CHUNK_DELAY_MS)
    }
}

/** One announcement per key, signed under it. */
private fun payloadsFor(
    keys: List<PairKey>,
    identity: LocalIdentity,
    ip: InetAddress,
    probe: Boolean,
): List<ByteArray> = keys.map { PresenceAnnouncement.create(identity, it, ip, probe).serialize(it.key) }

/**
 * Asks who is there and collects the answers, from an ephemeral port so it works on a device
 * that is also running a [PresenceResponder]. [keys] are presence keys, one per peer.
 */
suspend fun presenceProbe(
    keys: List<PairKey>,
    identity: LocalIdentity,
    endpoint: LocalEndpoint,
    listenForMs: Long,
    sweep: Boolean,
): List<DiscoveredPeer> = withContext(Dispatchers.IO) {
    val found = LinkedHashMap<String, DiscoveredPeer>()
    if (keys.isEmpty()) return@withContext emptyList()
    val ring = KeyRing(keys)
    val socket = DatagramSocket()
    try {
        socket.broadcast = true
        socket.soTimeout = 200
        val payloads = payloadsFor(keys, identity, endpoint.ip, probe = true)
        coroutineScope {
            // Shout on another coroutine and listen here at the same time: a /21 sweep
            // spends ~320 ms in chunk delays alone, and the first replies land long before
            // it has finished going out.
            val shouting = launch { shout(socket, payloads, endpoint, sweep) }
            val deadline = System.currentTimeMillis() + listenForMs
            val buffer = ByteArray(PRESENCE_MAX_SIZE)
            while (System.currentTimeMillis() < deadline && isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    break
                }
                val (announcement, key) =
                    PresenceAnnouncement.open(packet.data, packet.length, ring) ?: continue
                if (announcement.deviceId.contentEquals(identity.deviceId)) continue
                if (!announcement.isFresh()) continue
                val id = base32Encode(announcement.deviceId)
                found[id] = DiscoveredPeer(
                    deviceId = id,
                    name = announcement.name,
                    os = announcement.os,
                    // The source address beats the announced one when they disagree: the
                    // packet is already authenticated, and a peer behind a VPN announces a
                    // tunnel address its packets do not come from.
                    ip = packet.address.hostAddress ?: InetAddress.getByAddress(
                        announcement.ipAddress
                    ).hostAddress.orEmpty(),
                    port = announcement.port,
                    unattended = announcement.isUnattended,
                    presenceKey = key.key,
                )
            }
            shouting.cancel()
        }
    } finally {
        socket.close()
    }
    found.values.toList()
}

/** Announces once and returns: the burst a device sends when its own address has changed. */
suspend fun presenceAnnounceOnce(
    keys: List<PairKey>,
    identity: LocalIdentity,
    endpoint: LocalEndpoint,
) = withContext(Dispatchers.IO) {
    if (keys.isEmpty()) return@withContext
    val socket = DatagramSocket()
    try {
        socket.broadcast = true
        shout(socket, payloadsFor(keys, identity, endpoint.ip, probe = false), endpoint, sweep = false)
    } catch (e: Exception) {
        Log.w("Presence", "Could not announce: ${e.message}")
    } finally {
        socket.close()
    }
}

/**
 * Holds the presence port and answers probes. This is what runs while the app is open, and
 * what the "Stay reachable" service runs when it is not.
 *
 * A MulticastLock is held only while this is running, and it is the reason "stay reachable"
 * is opt-in: without it Android's Wi-Fi driver filters multicast and broadcast out before
 * they reach us, and with it the chip wakes the CPU for every such frame on the network.
 * The cached-address path in Paired.kt is what lets a transfer still arrive when this is
 * not running at all — a unicast packet to the phone's own address is delivered regardless.
 */
class PresenceResponder(
    private val context: Context,
    /**
     * The presence keys, asked for afresh on every packet: a code shown while this is
     * already running has to be answerable at once, and one just claimed has to stop being
     * so. Packets are rare, so the read costs nothing.
     */
    private val keys: () -> KeyRing,
    private val identity: LocalIdentity,
) {
    @Volatile
    private var cancelled = false
    private var multicastLock: WifiManager.MulticastLock? = null

    fun cancel() {
        cancelled = true
    }

    suspend fun run(endpoint: LocalEndpoint, onPeer: (DiscoveredPeer) -> Unit) =
        withContext(Dispatchers.IO) {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("MahojutanPresence").apply {
                setReferenceCounted(false)
                acquire()
            }
            var socket: MulticastSocket? = null
            try {
                socket = MulticastSocket(PRESENCE_PORT).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 250
                }
                try {
                    socket.joinGroup(
                        InetSocketAddress(
                            InetAddress.getByName(PRESENCE_MULTICAST_ADDR), PRESENCE_PORT
                        ),
                        endpoint.nic,
                    )
                } catch (e: Exception) {
                    // Best effort: broadcast and the sender's sweep carry this on their own,
                    // and on the network this was designed for multicast is the one that
                    // does not arrive anyway.
                    Log.w("Presence", "Could not join the multicast group: ${e.message}")
                }

                // One burst on entry. This is the whole beacon: it exists so that a device
                // whose address has just changed is not silently unreachable at every peer
                // that cached the old one. Under the peers' keys only; a pending code is
                // for the device that scanned it to speak first.
                presenceAnnounceOnce(keys().keys.filter { !it.isPending }, identity, endpoint)

                val buffer = ByteArray(PRESENCE_MAX_SIZE)
                while (!cancelled && coroutineContext.isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    } catch (e: Exception) {
                        if (cancelled) break else continue
                    }
                    val (announcement, key) =
                        PresenceAnnouncement.open(packet.data, packet.length, keys())
                            ?: continue
                    if (announcement.deviceId.contentEquals(identity.deviceId)) continue
                    if (!announcement.isFresh()) continue
                    onPeer(
                        DiscoveredPeer(
                            deviceId = base32Encode(announcement.deviceId),
                            name = announcement.name,
                            os = announcement.os,
                            ip = packet.address.hostAddress.orEmpty(),
                            port = announcement.port,
                            unattended = announcement.isUnattended,
                            presenceKey = key.key,
                        )
                    )
                    if (announcement.isProbe) {
                        // Answered under the key the probe came in on — the only one the
                        // prober can read, and, for a pending code, the one now theirs.
                        val reply = PresenceAnnouncement
                            .create(identity, key, endpoint.ip, probe = false)
                            .serialize(key.key)
                        try {
                            socket.send(
                                DatagramPacket(reply, reply.size, packet.address, packet.port)
                            )
                        } catch (e: Exception) {
                            Log.w("Presence", "Could not answer a probe: ${e.message}")
                        }
                    }
                }
            } finally {
                socket?.close()
                multicastLock?.let { if (it.isHeld) it.release() }
                multicastLock = null
            }
        }
}
