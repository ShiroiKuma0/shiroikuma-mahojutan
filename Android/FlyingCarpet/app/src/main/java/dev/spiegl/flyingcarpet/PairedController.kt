package dev.spiegl.flyingcarpet

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Socket

// Fork: the app-level half of paired devices — what runs, when, and who decides.
//
// Paired.kt is the protocol; this is the orchestration. It owns three things:
//
//   * the presence responder, which holds the UDP port and answers probes,
//   * the listener, which holds the TCP port and serves whoever connects,
//   * the send path, which is one call and needs nothing from the far device.
//
// In this phase both run only while the app is open, which is already enough to remove the
// mode, the password and the arming step from a transfer. Making a phone reachable with the
// app closed is a foreground service and a battery cost, and is deliberately a later,
// opt-in step — see `stayReachable` in Pairing.kt, which is stored but not yet acted on.

/** How long a scan listens. Long enough for a /21 sweep's chunk delays plus the replies. */
private const val SCAN_WINDOW_MS = 1500L

class PairedController(
    private val context: Context,
    private val viewModel: MainViewModel,
) {
    val pairing = Pairing(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var responder: PresenceResponder? = null
    private var responderJob: Job? = null
    private var listener: PairedListener? = null
    private var listenerJob: Job? = null
    private var wakeAdvertiser: WakeAdvertiser? = null
    private var beaconing = false

    /** Where a transfer that arrives unasked should land. Null means "refuse and say why". */
    var receiveDirProvider: () -> Uri? = { null }

    /** Told about every transfer that lands, so the screen can say what arrived. */
    var onArrival: (PairedOffer) -> Unit = {}

    val isPaired: Boolean get() = pairing.isPaired

    private fun identity(): LocalIdentity = LocalIdentity(
        deviceId = pairing.deviceIdBytes,
        name = pairing.name,
        os = PeerOs.ANDROID,
        // Honest rather than aspirational: this device only accepts a transfer nobody
        // touches while it is actually listening, which for now means while the app is open.
        unattended = pairing.isPaired && listenerJob?.isActive == true,
    )

    /**
     * Brings presence and the listener up, or takes them down to match the store. Idempotent,
     * so it can simply be called after anything that might have changed the group.
     */
    init {
        // A paired device that hears a knock while idle answers it. Nothing else in the app
        // has a reason to, so this is set once and left.
        viewModel.onIdlePeerContact = { peerOs ->
            armForIncomingHotspot(null, PeerOs.fromLabel(peerOs))
        }
    }

    fun start() {
        stop()
        val groupKey = pairing.groupKey ?: return
        val endpoint = presenceEndpoint(context)
        if (endpoint == null) {
            // Named rather than vague: mobile data is not a local network, and a phone with
            // Wi-Fi off looks identical to one with a broken feature otherwise.
            viewModel.outputText(
                "Not on a Wi-Fi or wired network, so paired devices cannot be reached. " +
                    "Mobile data does not count — two devices are never on the same local " +
                    "network over it."
            )
            return
        }
        val presenceKey = derivePresenceKey(groupKey)

        val paired = PairedListener()
        listener = paired
        listenerJob = scope.launch {
            try {
                paired.listen { socket -> serveOne(socket, groupKey) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Port already taken is the realistic case — a second copy of the app. Say
                // so rather than be silently unreachable, which is the exact failure this
                // feature exists to remove.
                viewModel.outputText(
                    "Cannot receive from paired devices: ${e.message}. Port $PRESENCE_PORT " +
                        "may be in use."
                )
            }
        }

        // The second half of "a server is running": a paired device has to be reachable when
        // the LAN cannot carry a signal between two clients — hostile corporate Wi-Fi, or no
        // network at all (白い熊, 2026-09-11). Advertising is the cheap direction; the sender
        // does the scanning, and only when a pill is tapped.
        startBleBeacon()

        val presence = PresenceResponder(context, presenceKey, identity())
        responder = presence
        responderJob = scope.launch {
            try {
                presence.run(endpoint) { peer -> rememberSeen(peer) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Paired", "Presence is not running: ${e.message}")
            }
        }
    }

    /**
     * Advertises the fork's GATT service while idle, so a paired sender can find this device
     * with no network at all. The GATT server itself is already up from app start
     * (MainActivity.initializeBluetooth); this only opens the advertising window that used to
     * be opened per transfer.
     */
    private fun startBleBeacon() {
        if (!viewModel.bluetooth.active || viewModel.transferIsRunning) return
        try {
            viewModel.bluetooth.bluetoothReceiver.waitingForConnection = true
            viewModel.bluetooth.advertise()
            beaconing = true
        } catch (e: Exception) {
            Log.i("Paired", "Could not advertise as a paired device: ${e.message}")
        }
    }

    private fun stopBleBeacon() {
        if (!beaconing) return
        beaconing = false
        try {
            viewModel.bluetooth.stopAdvertising()
        } catch (e: Exception) {
            Log.i("Paired", "Could not stop advertising: ${e.message}")
        }
    }

    fun stop() {
        stopBleBeacon()
        wakeAdvertiser?.stop()
        wakeAdvertiser = null
        responder?.cancel()
        listener?.cancel()
        scope.launch {
            responderJob?.cancelAndJoin()
            listenerJob?.cancelAndJoin()
        }
        responder = null
        listener = null
        responderJob = null
        listenerJob = null
    }

    /**
     * A device in the group has just told us where it is. Adds it if it is new.
     *
     * That last part is the whole of what made pairing feel one-way (白い熊, 2026-09-11): the
     * device that *scanned* the QR ran a probe and so learned the other, while the device that
     * *showed* it only ever answered probes — and this used to discard anything it had not
     * already heard of, so it never listed the device that had just joined. Pairing is
     * symmetric by construction: both ends hold the same group key, and completing a presence
     * exchange is the only credential this feature has, so hearing one is exactly as good a
     * reason to list a device as finding one.
     */
    private fun rememberSeen(peer: DiscoveredPeer) {
        pairing.upsertPeer(
            PairedPeer(
                deviceId = peer.deviceId,
                name = peer.name,
                os = peer.os.label,
                lastIp = peer.ip,
                lastSeen = System.currentTimeMillis() / 1000,
                autoAccept = true,
                receiveDir = receiveDirProvider()?.toString(),
            )
        )
    }

    /**
     * Asks who is there. The only thing here that ever shouts, and only while a device list
     * is on screen. A silent first pass is followed by a subnet sweep, which is what rescues
     * 白い熊's own /21 where multicast is dropped between clients.
     */
    suspend fun scan(): List<DiscoveredPeer> {
        val groupKey = pairing.groupKey
        if (groupKey == null) {
            viewModel.outputText("Not paired with anything yet, so there is nothing to look for.")
            return emptyList()
        }
        val endpoint = presenceEndpoint(context)
        if (endpoint == null) {
            viewModel.outputText(
                "Not on a Wi-Fi or wired network, so there is nowhere to look. Mobile data " +
                    "does not count — two devices are never on the same local network over it."
            )
            return emptyList()
        }
        // Says which network is being searched. Without this a scan that finds nothing is
        // indistinguishable from a broken feature — which is exactly how the interface bug
        // presented (白い熊, 2026-09-11): every probe went out on the carrier's interface and
        // the log said nothing at all.
        viewModel.outputText(
            "Looking for paired devices on ${endpoint.nic.name} " +
                "(${endpoint.ip.hostAddress}/${endpoint.prefixLength})..."
        )
        val key = derivePresenceKey(groupKey)
        var found = presenceProbe(key, identity(), endpoint, SCAN_WINDOW_MS, sweep = false)
        if (found.isEmpty()) {
            found = presenceProbe(key, identity(), endpoint, SCAN_WINDOW_MS, sweep = true)
        }
        viewModel.outputText(
            if (found.isEmpty()) {
                "No paired devices answered. Check the other device has this app open and is " +
                    "on the same Wi-Fi, and that both were paired with the same key."
            } else {
                "Found ${found.size}: " + found.joinToString(", ") { it.name.ifEmpty { "unnamed" } }
            }
        )
        for (peer in found) {
            pairing.upsertPeer(
                PairedPeer(
                    deviceId = peer.deviceId,
                    name = peer.name,
                    os = peer.os.label,
                    lastIp = peer.ip,
                    lastSeen = System.currentTimeMillis() / 1000,
                    // A peer that has just completed a presence exchange holds the group
                    // key, which is the only credential this feature has. Starting it
                    // trusted is the feature; having to switch each device on by hand is
                    // not. Revocable per peer afterwards.
                    autoAccept = true,
                    // Seeded from the main screen's "Receive in …" folder, so a device works
                    // the moment it is paired instead of refusing its first transfer for
                    // want of a setting nobody knew to make. upsertPeer keeps whatever is
                    // already stored, so this only ever applies to a new device.
                    receiveDir = receiveDirProvider()?.toString(),
                )
            )
        }
        return found
    }

    // ── the hotspot route ─────────────────────────────────────────────────────────────────

    /**
     * A paired transfer over a hotspot, for when there is no network to share. Both ends
     * derive the same Wi-Fi credential from the group key, so nothing is exchanged — but
     * unlike the network route this still needs a tap on the far device, because until the
     * access point exists there is no channel through which to ask for one.
     *
     * [sending] decides which half this device plays; the peer's OS decides which one raises
     * the access point, through the same `isHosting()` the ordinary hotspot flow uses.
     */
    /**
     * Asks a paired device to raise its hotspot, over whichever channel reaches it.
     *
     * The LAN first because it is instant and costs no radio, then Bluetooth — and Bluetooth
     * is the one that matters (白い熊, 2026-09-11): the hotspot route exists precisely for the
     * networks where the LAN cannot carry a signal between two clients, or where there is no
     * LAN at all. A control channel that only works on a friendly network would be missing
     * exactly the case it was built for.
     *
     * Returns true if the peer was reached and agreed. False is not fatal — the caller joins
     * anyway, because the peer may be raising one for its own reasons.
     */
    private suspend fun askPeerToHost(peer: PairedPeer, groupKey: ByteArray): Boolean {
        val address = peer.lastIp ?: locate(peer.deviceId)
        if (address != null) {
            viewModel.outputText("Asking ${peer.displayName} to raise its hotspot...")
            if (requestOverLan(peer, address, groupKey)) return true
            viewModel.outputText("Could not reach it over this network; trying Bluetooth.")
        } else {
            viewModel.outputText(
                "${peer.displayName} is not reachable over this network; trying Bluetooth."
            )
        }
        return wakeOverBluetooth()
    }

    /** One authenticated control connection: handshake, ask, hear the answer, hang up. */
    private suspend fun requestOverLan(
        peer: PairedPeer,
        address: String,
        groupKey: ByteArray,
    ): Boolean {
        return try {
            if (!viewModel.connectToPairedPeer(address)) return false
            viewModel.mode = Mode.Sending
            viewModel.connectionMode = ConnectionMode.SharedNetwork
            viewModel.psk = derivePairedPsk(groupKey)
            viewModel.pairedSession = PairedSession(
                groupKey = groupKey,
                sending = true,
                localId = pairing.deviceId,
                localName = pairing.name,
                request = REQUEST_RAISE_HOTSPOT,
            )
            viewModel.startTransfer()
            // startTransfer only returns normally if it ran a whole transfer, which a
            // request must never do.
            false
        } catch (e: HotspotRequested) {
            pairing.noteSeen(peer.deviceId, address)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i("Paired", "LAN hotspot request failed: ${e.message}")
            false
        } finally {
            // Only the control connection is torn down here; the hotspot that follows is set
            // up fresh by the caller.
            viewModel.pairedSession = null
            viewModel.closePairedControlConnection()
        }
    }

    /**
     * Makes this device visible to the peer's standing scan, which is all the wake needs to
     * be: the peer's own GATT server does the rest through gotPeer().
     */
    private fun wakeOverBluetooth(): Boolean {
        if (!viewModel.bluetooth.active) {
            viewModel.outputText(
                "Bluetooth is off, so the other device cannot be told. Turn on the Bluetooth " +
                    "switch, or start the transfer on that device instead."
            )
            return false
        }
        viewModel.bluetooth.bluetoothReceiver.waitingForConnection = true
        viewModel.bluetooth.advertise()
        viewModel.bluetooth.scan()
        return true
    }

    fun overHotspot(peer: PairedPeer, sending: Boolean, receiveDir: Uri?, onFinished: () -> Unit) {
        val groupKey = pairing.groupKey
        if (groupKey == null) {
            viewModel.outputText("This device is not paired with anything yet.")
            onFinished()
            return
        }
        val peerOs = PeerOs.fromLabel(peer.os)
        val resolved = when (peerOs) {
            PeerOs.ANDROID -> Peer.Android
            PeerOs.IOS -> Peer.iOS
            PeerOs.LINUX -> Peer.Linux
            PeerOs.MACOS -> Peer.macOS
            PeerOs.WINDOWS -> Peer.Windows
            null -> {
                viewModel.outputText("Don't know what kind of device ${peer.displayName} is; scan for it again.")
                onFinished()
                return
            }
        }
        // Presence is what holds the Wi-Fi in a state the hotspot machinery is about to take
        // over, and its MulticastLock has nothing to listen to once this device leaves the
        // network. Drop it before the access point goes up rather than fighting it.
        stop()
        // Ring the doorbell, if this device has been told to. Best-effort in the strictest
        // sense: it is not waited on, nothing depends on it, and a failure is silent because
        // the tap on the far device is the fallback and is what happens anyway.
        ringWakeBell(sending)
        viewModel.peer = resolved
        viewModel.mode = if (sending) Mode.Sending else Mode.Receiving
        viewModel.connectionMode = ConnectionMode.Hotspot
        // The hotspot route knows its peer up front, so the folder can be chosen here.
        val destination = if (sending) null else {
            peer.receiveDir?.let(Uri::parse) ?: receiveDir
        }
        if (destination != null) viewModel.receiveDir = destination
        // Sampled before arming, for the same reason as serveOne: the receiving half of a
        // paired hotspot transfer asks decide() from inside the transfer this arms.
        val alreadyBusy = viewModel.transferIsRunning
        viewModel.pairedSession = PairedSession(
            groupKey = groupKey,
            sending = sending,
            overHotspot = true,
            localId = pairing.deviceId,
            localName = pairing.name,
            decide = { offer -> decide(offer, alreadyBusy) },
        )
        viewModel.transferIsRunning = true
        viewModel.outputText(
            "Setting up a hotspot with ${peer.displayName}. No password is needed — both " +
                "devices work it out from the pairing."
        )
        if (sending) {
            // Signal first, then join. joinHotspot() polls for the access point for twenty
            // seconds, which comfortably covers the few it takes the peer to raise one — so
            // there is nothing to sequence and no callback to wait on.
            scope.launch {
                askPeerToHost(peer, groupKey)
                withContext(Dispatchers.Main) {
                    rearmForHotspot(peer, groupKey, sending = true)
                    viewModel.connectToPeer()
                    onFinished()
                }
            }
            return
        }
        viewModel.connectToPeer()
        onFinished()
    }

    /**
     * Puts the ViewModel back into the paired-hotspot shape after the control connection has
     * used it for something else. Cheap, and it keeps the two uses from sharing state by
     * accident — the control connection's session must never be the one the transfer runs on.
     */
    private fun rearmForHotspot(peer: PairedPeer, groupKey: ByteArray, sending: Boolean) {
        viewModel.mode = if (sending) Mode.Sending else Mode.Receiving
        viewModel.connectionMode = ConnectionMode.Hotspot
        viewModel.pairedSession = PairedSession(
            groupKey = groupKey,
            sending = sending,
            overHotspot = true,
            localId = pairing.deviceId,
            localName = pairing.name,
            decide = { offer -> decide(offer, false) },
        )
        viewModel.transferIsRunning = true
    }

    /**
     * The far end of a hotspot request: raise one, with nothing tapped here.
     *
     * Reached two ways — a control connection over the LAN, or a paired device making contact
     * over Bluetooth while this one was only advertising. Both arrive at the same place,
     * because which side hosts is decided by `isHosting()` from the peer's OS, not by how the
     * request got here.
     */
    fun hostForPairedPeer(senderId: String?, senderOs: PeerOs?) {
        if (armForIncomingHotspot(senderId, senderOs)) viewModel.connectToPeer()
    }

    /**
     * Arms this device as the far end of a hotspot request, without driving it. The Bluetooth
     * path needs exactly this and no more: `gotPeer()` calls `connectToPeer()` itself a moment
     * later, and doing it here as well would raise two access points.
     */
    fun armForIncomingHotspot(senderId: String?, senderOs: PeerOs?): Boolean {
        val groupKey = pairing.groupKey ?: return false
        val peer = senderId?.let { pairing.findPeer(it) }
        val os = senderOs ?: peer?.os?.let { PeerOs.fromLabel(it) } ?: PeerOs.ANDROID
        val destination = peer?.receiveDir?.let(Uri::parse) ?: receiveDirProvider()
        if (destination == null) {
            viewModel.outputText(
                "A paired device wants to send over a hotspot, but no folder has been chosen " +
                    "to receive into. Pick one and try again."
            )
            return false
        }
        // Presence and a hotspot cannot both have the radio.
        stop()
        viewModel.peer = when (os) {
            PeerOs.ANDROID -> Peer.Android
            PeerOs.IOS -> Peer.iOS
            PeerOs.LINUX -> Peer.Linux
            PeerOs.MACOS -> Peer.macOS
            PeerOs.WINDOWS -> Peer.Windows
        }
        viewModel.receiveDir = destination
        rearmForHotspot(
            peer ?: PairedPeer(senderId.orEmpty(), "", os.label, null, 0, true),
            groupKey,
            sending = false,
        )
        viewModel.outputText(
            "${peer?.displayName ?: "A paired device"} is sending over a hotspot — nothing to " +
                "do here."
        )
        return true
    }

    /**
     * Advertises for a short while that this device is about to raise or join a hotspot, so a
     * paired device that is listening can take the other half without being touched.
     *
     * Deliberately fire-and-forget. Bluetooth is the part of this app with the longest list of
     * ways to fail on these phones, so nothing here is allowed to hold up, or fail, a transfer
     * that would otherwise work with one extra tap.
     */
    private fun ringWakeBell(sending: Boolean) {
        if (!pairing.bleWake) return
        val groupKey = pairing.groupKey ?: return
        val advertiser = WakeAdvertiser(context)
        val started = try {
            advertiser.start(derivePresenceKey(groupKey), pairing.deviceIdBytes, sending)
        } catch (e: Exception) {
            Log.w("Paired", "Could not ring: ${e.message}")
            false
        }
        if (!started) return
        wakeAdvertiser = advertiser
        scope.launch {
            // Long enough for the other device's low-power scan to see it, short enough that
            // a forgotten advertisement is not left running for the rest of the day.
            kotlinx.coroutines.delay(WAKE_ADVERTISE_MS)
            advertiser.stop()
            if (wakeAdvertiser === advertiser) wakeAdvertiser = null
        }
    }

    /** Finds one device now, for when its remembered address did not answer. */
    private suspend fun locate(deviceId: String): String? =
        scan().find { it.deviceId == deviceId }?.ip

    // ── receiving ─────────────────────────────────────────────────────────────────────────

    /**
     * Whether to take one offer, and where to put it.
     *
     * [alreadyBusy] is sampled by the caller *before* it arms the transfer, and that is not a
     * nicety — reading `transferIsRunning` in here refused every paired receive that ever
     * happened (白い熊, 2026-09-11, "The other device is busy with another transfer"). Both
     * callers set the flag and then run the transfer, and the offer exchange sits inside that
     * transfer, so the guard was always looking at the very transfer it was being asked
     * about. The question is "was this device busy when the connection arrived", which can
     * only be answered before we make ourselves busy answering it.
     */
    private fun decide(offer: PairedOffer, alreadyBusy: Boolean): Verdict {
        if (alreadyBusy) return Verdict.Refuse(Refusal.BUSY)
        val peer = pairing.findPeer(offer.deviceId)
        // Holding the group key is necessary but not sufficient: 白い熊 gets the last word on
        // which of their own devices may write here without being asked.
        if (peer == null || !peer.autoAccept) return Verdict.Refuse(Refusal.NOT_ALLOWED)
        val ceiling = pairing.autoAcceptMaxBytes
        if (ceiling > 0 && offer.totalBytes > ceiling) return Verdict.Refuse(Refusal.TOO_LARGE)
        // This device's own folder first, then whatever the main screen is pointing at. The
        // fallback matters: a peer paired before per-device folders existed has none stored.
        val destination = peer.receiveDir?.let(Uri::parse) ?: receiveDirProvider()
            ?: return Verdict.Refuse(Refusal.NO_DESTINATION)
        return Verdict.Accept(destination)
    }

    private suspend fun serveOne(socket: Socket, groupKey: ByteArray) {
        // The destination is deliberately NOT chosen here: it depends on which device is
        // calling, and that is not known until its offer has been read — inside the transfer,
        // after the handshake has proved it holds the group key. decide() picks it then.
        viewModel.client = socket
        viewModel.mode = Mode.Receiving
        viewModel.connectionMode = ConnectionMode.SharedNetwork
        viewModel.psk = derivePairedPsk(groupKey)
        // Sampled before arming, or the guard in decide() sees the flag set two lines below.
        val alreadyBusy = viewModel.transferIsRunning
        val session = PairedSession(
            groupKey = groupKey,
            sending = false,
            localId = pairing.deviceId,
            localName = pairing.name,
            decide = { offer -> decide(offer, alreadyBusy) },
        )
        viewModel.pairedSession = session
        viewModel.transferIsRunning = true
        try {
            viewModel.startTransfer()
            session.offer?.let { onArrival(it) }
            viewModel.cleanUpTransfer()
        } catch (e: HotspotRequested) {
            // Not a failure: the control connection did its job. Let go of it and of the
            // transfer state it borrowed, then raise the access point the peer is waiting for.
            viewModel.cleanUpTransfer()
            val offer = e.offer
            // The OS is not in the offer — it is looked up from the paired store by id, which
            // is where it was recorded when the device was found.
            hostForPairedPeer(offer?.deviceId, null)
        } catch (e: Throwable) {
            viewModel.cleanUpTransfer()
            throw e
        }
    }

    // ── sending ───────────────────────────────────────────────────────────────────────────

    /**
     * Sends to a paired device. The caller has already put the selection into the ViewModel,
     * exactly as the file picker does; everything after that is this one call.
     *
     * The remembered address is tried first and a scan is the fallback, not the other way
     * round: a unicast packet reaches a phone whose Wi-Fi driver is filtering broadcast,
     * which is the state a phone sitting on a desk is in nearly all of the time. When the
     * address is stale — DHCP moved the peer — the failure is indistinguishable from the
     * device being off, so a failed connect looks again rather than giving up.
     */
    fun sendTo(peer: PairedPeer, onFinished: () -> Unit) {
        val groupKey = pairing.groupKey
        if (groupKey == null) {
            viewModel.outputText("This device is not paired with anything yet.")
            onFinished()
            return
        }
        viewModel.transferCoroutine = scope.launch {
            try {
                viewModel.mode = Mode.Sending
                viewModel.connectionMode = ConnectionMode.SharedNetwork
                viewModel.psk = derivePairedPsk(groupKey)
                viewModel.pairedSession = PairedSession(
                    groupKey = groupKey,
                    sending = true,
                    localId = pairing.deviceId,
                    localName = pairing.name,
                )
                viewModel.transferIsRunning = true

                var connected = peer.lastIp?.let { viewModel.connectToPairedPeer(it) } == true
                if (!connected) {
                    viewModel.outputText("Looking for ${peer.displayName} on this network...")
                    val fresh = locate(peer.deviceId)
                    if (fresh != null && fresh != peer.lastIp) {
                        connected = viewModel.connectToPairedPeer(fresh)
                        if (connected) pairing.noteSeen(peer.deviceId, fresh)
                    }
                }
                if (!connected) {
                    throw Exception(
                        "Could not reach ${peer.displayName}. Open the app on it, or check that both " +
                            "devices are on the same network."
                    )
                }
                viewModel.startTransfer()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                viewModel.outputText("Transfer error: ${e.message}\n")
            } finally {
                viewModel.cleanUpTransfer()
                viewModel.finishTransfer()
                onFinished()
            }
        }
    }
}
