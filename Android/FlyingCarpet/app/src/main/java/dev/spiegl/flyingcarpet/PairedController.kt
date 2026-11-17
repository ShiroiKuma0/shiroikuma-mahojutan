package dev.spiegl.flyingcarpet

import android.app.Application
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
// Both run while the app is open, which is already enough to remove the mode, the password
// and the arming step from a transfer; PresenceService runs the same controller with the
// app closed, behind the "Stay reachable" switch. Keys are pairwise (Pairing.kt), so every
// probe, handshake and hotspot here is keyed to the one peer it concerns.

/** How long a scan listens. Long enough for a /21 sweep's chunk delays plus the replies. */
private const val SCAN_WINDOW_MS = 1500L

class PairedController(
    private val context: Context,
    private val viewModel: MainViewModel,
) {
    val pairing = Pairing(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val app: Application get() = context.applicationContext as Application
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
        unattended = pairing.shouldServe && listenerJob?.isActive == true,
    )

    /** The presence keys, one per peer and one per unclaimed code, as the store now stands. */
    private fun presenceRing(): KeyRing = pairing.keyRing().forPresence()

    /** The presence keys a probe goes out under: the peers' only. */
    private fun probeKeys(): List<PairKey> = presenceRing().keys.filter { !it.isPending }

    /**
     * A device has just proved it holds one of our keys — by answering presence, or by
     * completing a handshake. Records it as the peer that key belongs to, and if the key
     * was a code this device showed, the code is spent.
     */
    private fun claim(pairKey: ByteArray, peer: PairedPeer) {
        pairing.claim(pairKey, peer)
    }

    /**
     * Brings presence and the listener up, or takes them down to match the store. Idempotent,
     * so it can simply be called after anything that might have changed the group.
     */
    init {
        // A paired device that hears a knock while idle answers it. Nothing else in the app
        // has a reason to, so this is set once and left.
        viewModel.onIdlePeerContact = { peerOs, peerId ->
            armForIncomingHotspot(peerId, PeerOs.fromLabel(peerOs))
        }
    }

    fun start() {
        stop()
        if (!pairing.shouldServe) return
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
        val paired = PairedListener()
        listener = paired
        listenerJob = scope.launch {
            try {
                // One coroutine per connection, and in the controller's scope rather than
                // under this one: several devices may send at once, and a transfer under
                // way must outlive the listener that accepted it — stop() cancels the
                // listener every time the app leaves the screen.
                paired.listen { socket -> scope.launch { serveOne(socket) } }
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

        val presence = PresenceResponder(context, ::presenceRing, identity())
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
     * A paired device has just told us where it is — or a device has just claimed a code
     * this one showed. Adds it if it is new.
     *
     * That last part is what makes pairing symmetric: the device that *showed* the code only
     * ever answers probes, and this is the moment it learns who scanned it. Completing a
     * presence exchange means holding the key, which is the only credential this feature
     * has, so hearing a device is exactly as good a reason to list it as finding one.
     */
    private fun rememberSeen(peer: DiscoveredPeer) {
        val key = pairing.keyRing().pairKeyBehind(peer.presenceKey) ?: return
        claim(
            key,
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
    suspend fun scan(): List<DiscoveredPeer> = scanFor(null)

    /**
     * Asks for one device, or for all of them. A targeted look goes out under that device's
     * key alone, so it need not wake every other peer.
     */
    private suspend fun scanFor(deviceId: String?): List<DiscoveredPeer> {
        val keys = probeKeys().filter { key ->
            deviceId == null || key.deviceId?.let { base32Encode(it) } == deviceId
        }
        if (keys.isEmpty()) {
            viewModel.outputText(
                if (deviceId == null) "Not paired with anything yet, so there is nothing to look for."
                else "No pairing key is stored for that device; pair it again."
            )
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
        var found = presenceProbe(keys, identity(), endpoint, SCAN_WINDOW_MS, sweep = false)
        if (found.size < keys.size) {
            found = presenceProbe(keys, identity(), endpoint, SCAN_WINDOW_MS, sweep = true)
        }
        viewModel.outputText(
            if (found.isEmpty()) {
                "No paired devices answered. Check the other device has this app open and is " +
                    "on the same Wi-Fi."
            } else {
                "Found ${found.size}: " + found.joinToString(", ") { it.name.ifEmpty { "unnamed" } }
            }
        )
        for (peer in found) {
            val key = pairing.keyRing().pairKeyBehind(peer.presenceKey) ?: continue
            claim(
                key,
                PairedPeer(
                    deviceId = peer.deviceId,
                    name = peer.name,
                    os = peer.os.label,
                    lastIp = peer.ip,
                    lastSeen = System.currentTimeMillis() / 1000,
                    // A peer that has just completed a presence exchange holds the key,
                    // which is the only credential this feature has. Starting it trusted is
                    // the feature; having to switch each device on by hand is not.
                    // Revocable per peer afterwards.
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
    private suspend fun askPeerToHost(peer: PairedPeer, key: ByteArray): Boolean {
        val address = peer.lastIp ?: locate(peer.deviceId)
        if (address != null) {
            viewModel.outputText("Asking ${peer.displayName} to raise its hotspot...")
            if (requestOverLan(peer, address, key)) return true
            viewModel.outputText("Could not reach it over this network; trying Bluetooth.")
        } else {
            viewModel.outputText(
                "${peer.displayName} is not reachable over this network; trying Bluetooth."
            )
        }
        return wakeOverBluetooth()
    }

    /**
     * One authenticated control connection: handshake, ask, hear the answer, hang up.
     *
     * A lane of its own that is never entered in the registry: it moves no files and is gone
     * in a moment, and it must never share state with the hotspot transfer that follows it —
     * which, being a lane, it now cannot.
     */
    private suspend fun requestOverLan(
        peer: PairedPeer,
        address: String,
        key: ByteArray,
    ): Boolean {
        val lane = PairedLane(
            app,
            sending = true,
            peerId = peer.deviceId,
            peerName = peer.displayName,
            log = viewModel::outputText,
        )
        // Read for the offer only — how many files and how large — so the far device can
        // refuse a transfer it would refuse anyway before raising a hotspot for it. The
        // streams stay where they are: the transfer itself runs on the single slot.
        lane.files = viewModel.files
        return try {
            if (!lane.connectToPairedPeer(address)) return false
            lane.mode = Mode.Sending
            lane.psk = derivePairedPsk(key)
            lane.pairedSession = PairedSession(
                key = key,
                sending = true,
                localId = pairing.deviceId,
                localName = pairing.name,
                peerName = peer.displayName,
                request = REQUEST_RAISE_HOTSPOT,
            )
            lane.run()
            // run() only returns normally if it ran a whole transfer, which a request must
            // never do.
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
            lane.close()
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
        val key = pairing.keyFor(peer.deviceId)
        if (key == null) {
            viewModel.outputText("No pairing key is stored for ${peer.displayName}; pair it again.")
            onFinished()
            return
        }
        // A hotspot takes the radio the lanes are running on. Refused here as well as on the
        // screen, because the doorbell and the "Stay reachable" service arrive without one.
        if (lanesBlockHotspot()) {
            viewModel.cleanUpUi()
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
        ringWakeBell(key, sending)
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
            key = key,
            sending = sending,
            overHotspot = true,
            localId = pairing.deviceId,
            localName = pairing.name,
            peerName = peer.displayName,
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
                askPeerToHost(peer, key)
                withContext(Dispatchers.Main) {
                    rearmForHotspot(peer, key, sending = true)
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
    private fun rearmForHotspot(peer: PairedPeer, key: ByteArray, sending: Boolean) {
        viewModel.mode = if (sending) Mode.Sending else Mode.Receiving
        viewModel.connectionMode = ConnectionMode.Hotspot
        viewModel.pairedSession = PairedSession(
            key = key,
            sending = sending,
            overHotspot = true,
            localId = pairing.deviceId,
            localName = pairing.name,
            peerName = peer.displayName,
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
        if (lanesBlockHotspot()) return false
        // The credentials are derived per pair, so a caller that did not say who it is
        // cannot be hosted for — there is no key to derive them from.
        val peer = senderId?.let { pairing.findPeer(it) }
        val key = peer?.let { pairing.keyFor(it.deviceId) }
        if (peer == null || key == null) {
            viewModel.outputText(
                "A device made contact over a hotspot request, but it is not paired with " +
                    "this one. Pair the two first."
            )
            return false
        }
        val os = senderOs ?: PeerOs.fromLabel(peer.os) ?: PeerOs.ANDROID
        val destination = peer.receiveDir?.let(Uri::parse) ?: receiveDirProvider()
        if (destination == null) {
            viewModel.outputText(
                "A paired device wants to send over a hotspot, but no directory has been chosen " +
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
        rearmForHotspot(peer, key, sending = false)
        viewModel.outputText("${peer.displayName} is sending over a hotspot — nothing to do here.")
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
    private fun ringWakeBell(key: ByteArray, sending: Boolean) {
        if (!pairing.bleWake) return
        val advertiser = WakeAdvertiser(context)
        val started = try {
            advertiser.start(derivePresenceKey(key), pairing.deviceIdBytes, sending)
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

    /**
     * Whether lanes are in the way of a hotspot, saying so if they are. The other half of the
     * exclusion is in sendTo() and decide(): no lane starts while the single slot is busy.
     */
    private fun lanesBlockHotspot(): Boolean {
        if (!TransferLanes.anyActive) return false
        viewModel.outputText(
            "A hotspot transfer cannot start while transfers over this network are running " +
                "or queued — it would take the Wi-Fi away from them. Wait for them to finish, " +
                "or cancel them."
        )
        return true
    }

    /** Finds one device now, for when its remembered address did not answer. */
    private suspend fun locate(deviceId: String): String? =
        scanFor(deviceId).find { it.deviceId == deviceId }?.ip

    // ── receiving ─────────────────────────────────────────────────────────────────────────

    /**
     * Whether to take one offer, and where to put it.
     *
     * [lane] is the lane the offer arrived on, or null for the receiving half of a paired
     * hotspot transfer, which runs on the single slot. That half passes [alreadyBusy],
     * sampled *before* it armed the transfer, and that is not a nicety — reading
     * `transferIsRunning` in here refused every paired receive that ever happened (白い熊,
     * 2026-09-11, "The other device is busy with another transfer"): the caller sets the flag
     * and then runs the transfer, and the offer exchange sits inside that transfer, so the
     * guard was always looking at the very transfer it was being asked about.
     *
     * A lane has no such problem. It does not touch the single slot's flag, so "is this
     * device busy" is asked of the registry, here, at the moment the answer goes on the wire.
     */
    private fun decide(offer: PairedOffer, alreadyBusy: Boolean, lane: PairedLane? = null): Verdict {
        if (alreadyBusy) return Verdict.Refuse(Refusal.BUSY)
        val peer = pairing.findPeer(offer.deviceId)
        // Holding the key is necessary but not sufficient: 白い熊 gets the last word on which
        // of their own devices may write here without being asked.
        if (peer == null || !peer.autoAccept) return Verdict.Refuse(Refusal.NOT_ALLOWED)
        if (offer.request == REQUEST_RAISE_HOTSPOT) {
            // Asked to leave this network for a hotspot: not while anything is running on
            // it, and not while the single slot is already spoken for.
            if (TransferLanes.anyActive || TransferLanes.primaryRunning) {
                return Verdict.Refuse(Refusal.BUSY)
            }
        }
        val ceiling = pairing.autoAcceptMaxBytes
        if (ceiling > 0 && offer.totalBytes > ceiling) return Verdict.Refuse(Refusal.TOO_LARGE)
        // This device's own directory first, then whatever the main screen is pointing at. The
        // fallback matters: a peer paired before per-device directories existed has none stored.
        val destination = peer.receiveDir?.let(Uri::parse) ?: receiveDirProvider()
            ?: return Verdict.Refuse(Refusal.NO_DESTINATION)
        if (lane != null && offer.request == REQUEST_TRANSFER) {
            // Last, so that a lane is only ever entered for a transfer that is going to
            // happen. The registry says no when the lanes are full, or when a hotspot or
            // password-mode transfer holds the single slot.
            lane.peerId = offer.deviceId
            lane.peerName = peer.displayName
            if (!TransferLanes.admitIncoming(lane)) return Verdict.Refuse(Refusal.BUSY)
        }
        return Verdict.Accept(destination)
    }

    private suspend fun serveOne(socket: Socket) {
        // The destination is deliberately NOT chosen here: it depends on which device is
        // calling, and that is not known until its offer has been read — inside the transfer,
        // after the handshake has proved it holds the key. decide() picks it then, and enters
        // the lane in the registry at the same moment. The key itself is chosen by the hello,
        // from the ring as it stands when the call arrives.
        val lane = PairedLane(
            app,
            sending = false,
            peerId = null,
            peerName = "",
            log = viewModel::outputText,
        )
        lane.client = socket
        lane.mode = Mode.Receiving
        val session = PairedSession(
            key = null,
            sending = false,
            localId = pairing.deviceId,
            localName = pairing.name,
            ring = { pairing.keyRing() },
            onClaimed = { caller, name ->
                claim(
                    caller.key.key,
                    PairedPeer(
                        deviceId = caller.deviceId,
                        name = name,
                        os = caller.os.label,
                        lastIp = socket.inetAddress?.hostAddress,
                        lastSeen = System.currentTimeMillis() / 1000,
                        autoAccept = true,
                        receiveDir = receiveDirProvider()?.toString(),
                    )
                )
            },
            decide = { offer -> decide(offer, alreadyBusy = false, lane = lane) },
        )
        lane.pairedSession = session
        lane.job = kotlin.coroutines.coroutineContext[Job]
        try {
            lane.run()
            session.offer?.let { onArrival(it) }
        } catch (e: HotspotRequested) {
            // Not a failure: the control connection did its job. Let go of it, then raise the
            // access point the peer is waiting for.
            lane.close()
            val offer = e.offer
            // The OS is not in the offer — it is looked up from the paired store by id, which
            // is where it was recorded when the device was found.
            hostForPairedPeer(offer?.deviceId, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Paired", "Transfer from ${socket.inetAddress} ended: ${e.message}")
            // On screen only once the transfer was taken. A connection that never got that
            // far — a stale key, a refusal — is the other device's news, and it has said so
            // there.
            if (lane.state == PairedLane.State.RUNNING && !lane.cancelled) {
                lane.outputText("Transfer error: ${e.message}")
            }
        } finally {
            lane.close()
            TransferLanes.finish(lane)
        }
    }

    // ── sending ───────────────────────────────────────────────────────────────────────────

    /**
     * Sends to a paired device. The caller has already put the selection into the ViewModel,
     * exactly as the file picker does; this takes it out again and into a lane of its own, so
     * the screen is free for the next selection while this one is still on its way.
     *
     * The lane starts at once unless that device already has a transfer with this one, or
     * four are already running — then it waits its turn, on screen, with its own Cancel.
     *
     * The remembered address is tried first and a scan is the fallback, not the other way
     * round: a unicast packet reaches a phone whose Wi-Fi driver is filtering broadcast,
     * which is the state a phone sitting on a desk is in nearly all of the time. When the
     * address is stale — DHCP moved the peer — the failure is indistinguishable from the
     * device being off, so a failed connect looks again rather than giving up.
     */
    fun sendTo(peer: PairedPeer, onFinished: () -> Unit) {
        val key = pairing.keyFor(peer.deviceId)
        if (key == null) {
            viewModel.outputText("No pairing key is stored for ${peer.displayName}; pair it again.")
            onFinished()
            return
        }
        if (TransferLanes.primaryRunning) {
            viewModel.outputText(
                "A hotspot or password transfer is running, and it has the Wi-Fi to itself. " +
                    "Send to ${peer.displayName} when it has finished."
            )
            onFinished()
            return
        }
        val lane = PairedLane(
            app,
            sending = true,
            peerId = peer.deviceId,
            peerName = peer.displayName,
            log = viewModel::outputText,
            ask = { who, name, identical, moreFiles ->
                viewModel.askAboutExistingFile(who, name, identical, moreFiles)
            },
        )
        // The selection moves into the lane, and the ViewModel gets fresh, empty lists: the
        // next pick must not reach into a transfer that is already running.
        lane.files = viewModel.files
        lane.fileStreams = viewModel.fileStreams
        lane.filePaths = viewModel.filePaths
        viewModel.files = mutableListOf()
        viewModel.fileStreams = mutableListOf()
        viewModel.filePaths = mutableListOf()
        lane.mode = Mode.Sending
        lane.psk = derivePairedPsk(key)
        lane.pairedSession = PairedSession(
            key = key,
            sending = true,
            localId = pairing.deviceId,
            localName = pairing.name,
            peerName = peer.displayName,
        )
        val count = lane.files.size
        val what = if (count == 1) lane.files[0].name ?: "one file" else "$count files"
        if (TransferLanes.enqueue(lane)) {
            lane.outputText("Queued $what. It starts as soon as it can.")
        } else {
            lane.outputText("Sending $what.")
        }
        lane.job = scope.launch {
            try {
                TransferLanes.awaitTurn(lane)
                var connected = peer.lastIp?.let { lane.connectToPairedPeer(it) } == true
                if (!connected) {
                    lane.outputText("Looking for ${peer.displayName} on this network...")
                    val fresh = locate(peer.deviceId)
                    if (fresh != null && fresh != peer.lastIp) {
                        connected = lane.connectToPairedPeer(fresh)
                        if (connected) pairing.noteSeen(peer.deviceId, fresh)
                    }
                }
                if (!connected) {
                    throw Exception(
                        "Could not reach ${peer.displayName}. Open the app on it, or check that both " +
                            "devices are on the same network."
                    )
                }
                lane.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A cancelled lane fails on its closed socket; it has already said why.
                if (!lane.cancelled) lane.outputText("Transfer error: ${e.message}")
            } finally {
                lane.close()
                TransferLanes.finish(lane)
                onFinished()
            }
        }
    }
}
