package dev.spiegl.flyingcarpet

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.*
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.location.LocationManagerCompat
import androidx.core.app.ActivityCompat
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.SecureRandom

const val PORT = 3290

// How long to give a Wi-Fi Direct group before falling back to LocalOnlyHotspot. Generous enough
// for a driver that is slow to bring the group up, short enough that the fallback still feels like
// part of starting the transfer rather than a hang.
const val WIFI_DIRECT_TIMEOUT_MS = 8000L
// Some devices announce the group before its network name and passphrase can be read back.
const val WIFI_DIRECT_GROUP_READ_ATTEMPTS = 10

enum class Mode {
    Sending,
    Receiving,
}

enum class Peer {
    Android,
    iOS,
    Linux,
    macOS,
    Windows,
}

enum class ConnectionMode {
    Hotspot,
    SharedNetwork,
}

// v10 is a breaking change: shared network mode and its new protocol are not compatible
// with v9 or earlier. See docs/shared-network-crypto.md in the main repo.
const val MAJOR_VERSION: Long = 10

// What this fork puts on the wire in place of the plain major version, and the floor at which a
// peer counts as running it too. Stock v10 sends 10 and treats anything higher as "the newer peer
// decides compatibility", which it then obeys -- so the fork can announce itself without breaking
// a stock peer, and two forks recognise each other and switch on the file-conflict exchange.
// Mirrors WIRE_VERSION / FORK_WIRE_FLOOR in core/src/lib.rs; the two must stay in step.
const val WIRE_VERSION: Long = 10_001
const val FORK_WIRE_FLOOR: Long = 10_000

/** What to do about a file the receiving device already has, decided on the sending device. */
sealed class FileConflictChoice {
    data object Skip : FileConflictChoice()
    data object Overwrite : FileConflictChoice()
    data class Rename(val newName: String) : FileConflictChoice()
}

/**
 * A conflict answer, plus whether it should stand for every remaining file instead of just this
 * one. A ten-file folder the peer already has asked ten identical questions before this existed
 * (白い熊, 2026-08-14). Mirrors FileConflictAnswer in core/src/lib.rs.
 */
data class FileConflictAnswer(val choice: FileConflictChoice, val applyToAll: Boolean)

/**
 * What "apply to all" remembers: the *rule*, never the answer verbatim.
 *
 * A rename carries a name, and a name cannot be reused -- sending every remaining file as
 * "photo (copy).jpg" would pile them all onto one another at the other end. So a sticky rename
 * stores only the intent, and each file gets its own name from suggestRename(). Mirrors
 * ConflictRule in core/src/lib.rs.
 */
enum class ConflictRule {
    Skip, Overwrite, Rename;

    /** The rule applied to one file: the only place a sticky rename's name is decided. */
    fun apply(relativeName: String): FileConflictChoice = when (this) {
        Skip -> FileConflictChoice.Skip
        Overwrite -> FileConflictChoice.Overwrite
        Rename -> FileConflictChoice.Rename(suggestRename(relativeName))
    }

    val label: String get() = when (this) {
        Skip -> "skip"
        Overwrite -> "overwrite"
        Rename -> "rename"
    }

    companion object {
        /** The rule behind an answer, so that the answer can be repeated for later files. */
        fun of(choice: FileConflictChoice): ConflictRule = when (choice) {
            is FileConflictChoice.Skip -> Skip
            is FileConflictChoice.Overwrite -> Overwrite
            is FileConflictChoice.Rename -> Rename
        }
    }
}
val zero = ByteArray(8) // meant to represent a 64-bit unsigned 0
val one = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1) // meant to represent a 64-bit unsigned 1
const val chunkSize = 5_000_000

// Backstop on the transfer WakeLock, not a budget: cleanUpTransfer() releases it on every exit,
// and this only matters if some path ever fails to reach that. Generous enough that no plausible
// transfer hits it -- 4 GB at the slowest rate we have measured is minutes, not hours.
const val TRANSFER_WAKELOCK_TIMEOUT_MS = 4L * 60L * 60L * 1000L

// How long to wait for the peer's hotspot to show up in a scan before opening the system network
// picker regardless, and how often to look. 20s comfortably covers a desktop `nmcli con up`
// (measured 3-4s to AP-ENABLED) and an Android LocalOnlyHotspot, without stalling a transfer when
// scan results are unavailable. See waitForPeerAp().
const val JOIN_AP_WAIT_MS = 20_000L
const val JOIN_AP_POLL_MS = 1_500L
//fun ByteArray.toHex(): String = joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }

/** One line of transfer log, tagged with a monotonically increasing sequence number. */
data class OutputLine(val seq: Long, val text: String)

class MainViewModel(private val application: Application) : AndroidViewModel(application), BluetoothDelegate {

    lateinit var mode: Mode
    lateinit var peer: Peer
    var peerIP: Inet4Address? = null
    var ssid: String = ""
    var password: String = ""
    // PBKDF2-stretched Noise PSK, derived once per transfer off the main thread (600k
    // iterations); also the source of the discovery HMAC key (deriveDiscoveryKey).
    // Fork: not private, because a paired transfer sets it from Paired.kt — its key comes
    // from the group key rather than from a password, so it cannot be derived in here.
    lateinit var psk: ByteArray
    // Fork default: Shared Network rather than upstream's Hotspot. The Bluetooth switch stays
    // usable there (fork, 白い熊 2026-08-07): BLE carries the transfer password when no hotspot
    // credentials need negotiating.
    var connectionMode: ConnectionMode = ConnectionMode.SharedNetwork
    var files: MutableList<DocumentFile> = mutableListOf()
    var fileStreams: MutableList<InputStream> = mutableListOf()
    var filePaths: MutableList<String> = mutableListOf() // paths relative to root directory peer is sending to
    lateinit var receiveDir: Uri
    // Per-transfer directory listing for the receive tree, replacing DocumentFile.findFile().
    // Rebuilt at the start of each transfer so a folder changed between transfers is seen.
    var safCache: SafDirectoryCache? = null
    lateinit var sendDir: Uri
    var sendFolder: Boolean = false
    private lateinit var server: ServerSocket // TCP listener, used to release port when transfer fails/ends/is cancelled
    lateinit var client: Socket // TCP socket, used to release port when transfer fails/ends/is cancelled
    lateinit var inputStream: InputStream // incoming TCP stream from peer
    lateinit var outputStream: OutputStream // outgoing TCP stream to peer
    var transferCoroutine: Job? = null
    var transferIsRunning = false

    // Fork: set for the length of a paired transfer, null for every other kind. Its presence
    // is what the two hooks below switch on — see Paired.kt.
    var pairedSession: PairedSession? = null

    // Fork: set while the fallback hotspot is waiting on a Location grant, so the permission
    // result resumes the fallback rather than restarting Wi-Fi Direct — which would be
    // refused again for whatever reason sent us to the fallback in the first place.
    var awaitingLocationForFallback = false

    // Fork: called when a Bluetooth peer makes contact while no transfer is running. Returns
    // true if this device armed itself to answer. Set by PairedController.
    /**
     * A paired device has made contact over Bluetooth while nothing was running: its OS, and
     * — when it said so — its device id. The id is what picks the pair key, and without it a
     * hotspot cannot be raised for it, because the credentials are derived per pair.
     */
    var onIdlePeerContact: ((String, String?) -> Boolean)? = null

    /**
     * What this device says it is over Bluetooth. In a paired session the device id rides
     * along after a bar, so the far end can pick the pair key; every other transfer says the
     * bare OS the stock app expects. Stock devices never see the long form — a paired session
     * only ever targets a device running this fork.
     */
    override fun osValue(): String {
        val id = pairedSession?.localId
        return if (id.isNullOrEmpty()) "android" else "android|$id"
    }

    // How many times to ask for the peer's hotspot before giving up, so a first look that lands
    // before the AP is beaconing costs a few seconds rather than a manual retry.
    // Long enough for a removeGroup() to take the interface down on the drivers we have
    // measured, short enough not to read as a hang.
    private val GROUP_TEARDOWN_SETTLE_MS = 1200L
    private val MAX_JOIN_ATTEMPTS = 4
    private var joinAttempts = 0
    var hotspotRunning = false
    lateinit var wifiManager: WifiManager
    lateinit var reservation: WifiManager.LocalOnlyHotspotReservation
    lateinit var requestPermissionLauncher: ActivityResultLauncher<String>
    val bluetooth = Bluetooth(application, this)
    lateinit var barcodeLauncher: ActivityResultLauncher<ScanOptions>
    // Fork: these six carried an Activity's behaviour and were `lateinit`, which made the
    // ViewModel unusable without one — the first cleanUpTransfer() threw. A paired transfer
    // that arrives while the app is closed has no Activity by definition, so each now starts
    // as a no-op and MainActivity replaces it when there is a screen to drive. Nothing about
    // the Activity's own path changes: it assigns all six in onCreate exactly as before.
    var displayQrCode: (String, String) -> Unit = { _, _ -> }
    var cleanUpUi: () -> Unit = { }
    var enableBluetoothUi: (Boolean) -> Unit = { }
    var promptForPassword: () -> Unit = { } // shared network mode: sender asks user for the receiver's password
    // "The other device already has this file" -- asked on the SENDING device, which is where the
    // user who picked the files is. Set by MainActivity; answered through the callback. The third
    // argument is false on the last file, where "apply to all" has nothing left to apply to.
    // Headless, this answers "skip" rather than hanging for ever on a dialog nobody can see.
    // It is only ever reached on the SENDING side, and a send with no screen has no user to
    // ask — so the safe answer is the one that changes nothing on the far device.
    var askFileConflict: (String, Boolean, Boolean, (FileConflictAnswer) -> Unit) -> Unit =
        { _, _, _, answer -> answer(FileConflictAnswer(FileConflictChoice.Skip, false)) }
    // "Apply to all", once ticked, for the rest of THIS transfer. The viewmodel outlives a
    // transfer, so it is cleared at the start of every send loop as well as in cleanUpTransfer().
    var conflictRule: ConflictRule? = null
    // True when the peer announced this fork's wire version, i.e. it understands the conflict
    // exchange. A stock peer is spoken to exactly as upstream does.
    var peerIsFork = false
    var displaySharedNetworkPassword: (String) -> Unit = { } // shared network mode: receiver shows generated password as QR code
    var discoveryManager: DiscoveryManager? = null
    private var discoveryJob: Job? = null // receiver-role background discovery in shared network mode
    private var boundToWifiNetwork = false
    private val handler = Handler(Looper.getMainLooper())
    private var _output = MutableLiveData<OutputLine>()
    val output: LiveData<OutputLine>
        get() = _output

    // The whole transcript is kept here, not in the Activity's saved-state Bundle. Bundles
    // cross Binder, whose transaction buffer is ~1MB for the whole process, so putString()ing
    // a many-file transfer's log risks TransactionTooLargeException on rotation. The ViewModel
    // already outlives configuration changes, so the log rides along whole: no size cap, no
    // serialization, nothing dropped. Both fields are touched only from the main thread, by
    // way of the Dispatchers.Main hop in outputText().
    private val outputLog = StringBuilder()
    private var outputSeq = 0L

    /**
     * The transcript so far, paired with the sequence number of its last line. A recreated
     * Activity seeds its fresh TextView with this, then ignores any [output] line at or below
     * that sequence number — LiveData redelivers its most recent value to a newly registered
     * observer, and that line is already in the seed.
     */
    fun outputSnapshot(): Pair<String, Long> = outputLog.toString() to outputSeq

    // The transcript, also written to a file under the app's own external directory:
    //   /sdcard/Android/data/shiroikuma.mahojutan/files/logs/transcript.log
    // EMUI drops Log.i from third-party apps, so `adb logcat` shows nothing of ours and the phone's
    // half of a failed transfer can only be read off the screen -- where a three-second progress
    // ticker pushes it out of view within a minute (白い熊, 2026-08-08). No permission is needed for
    // this directory, and `adb pull` reaches it. Kept to the last ~200 kB.
    private val transcriptFile: java.io.File? by lazy {
        try {
            val dir = java.io.File(application.getExternalFilesDir(null), "logs")
            dir.mkdirs()
            java.io.File(dir, "transcript.log")
        } catch (e: Exception) {
            Log.e("FlyingCarpet", "No transcript file: $e")
            null
        }
    }

    private fun appendToTranscript(line: String) {
        val file = transcriptFile ?: return
        try {
            if (file.length() > 200_000) {
                file.delete()
            }
            file.appendText(line + "\n")
        } catch (e: Exception) {
            // a log that cannot be written must never take the transfer with it
        }
    }

    override fun outputText(msg: String) {
        // Mirror every user-facing line to logcat under one greppable tag, so a transfer's
        // on-screen log can be pulled off the device with `adb logcat -s FlyingCarpet` instead
        // of being retyped by hand. Logged here, off the main-thread hop below, so the logcat
        // timestamps reflect when each line was produced. Trim only for the log line: the
        // leading blank line some messages carry ("\nStarting Transfer") is for on-screen
        // spacing and would otherwise print as an empty logcat entry.
        Log.i("FlyingCarpet", msg.trim())
        appendToTranscript(msg.trim())
        GlobalScope.launch(Dispatchers.Main) {
            outputLog.append(msg).append('\n')
            outputSeq++
            _output.value = OutputLine(outputSeq, msg)
        }
    }

    // The Bluetooth object holds process-wide registrations (a GATT server, a GATT client, a
    // broadcast receiver). If this ViewModel is discarded without releasing them they stay live for
    // the rest of the process and the next one competes with them.
    override fun onCleared() {
        super.onCleared()
        bluetooth.shutdown()
    }

    var qrBitmap: Bitmap? = null

    var progressBarMut = MutableLiveData(0)
    val progressBar: LiveData<Int>
        get() = progressBarMut

    // "123 MB / 1.2 GB · 8.7 MB/s · 2m 05s left", shown above the bar. Empty hides the line.
    var progressDetailsMut = MutableLiveData("")
    val progressDetails: LiveData<String>
        get() = progressDetailsMut

    // The same for the transfer as a whole; empty hides the second line and the second bar, which
    // is what happens whenever there is only one file to move.
    var progressTotalDetailsMut = MutableLiveData("")
    val progressTotalDetails: LiveData<String>
        get() = progressTotalDetailsMut

    var totalProgressBarMut = MutableLiveData(0)
    val totalProgressBar: LiveData<Int>
        get() = totalProgressBarMut

    var totals: Totals? = null

    private var _transferFinished = MutableLiveData(false)
    val transferFinished: LiveData<Boolean>
        get() = _transferFinished
    // this round-trip through postValue is required when screen is rotated during transfer
    // and activity is recreated, so that the new activity's observer catches this LiveData event
    // and calls cleanUpTransfer() on the new activity
    val finishTransfer = { _transferFinished.postValue(true) }

    private fun isHosting(): Boolean {
        // `peer` is a lateinit set from the peer-OS buttons, which only hotspot mode shows -- so in
        // shared network mode it is never assigned and reading it throws. Answer false rather than
        // crash: "am I hosting a hotspot" is meaningfully "no" when there is no hotspot at all, and
        // every caller that matters in shared network mode is already behind a mode check. Without
        // this a single unguarded call anywhere in the transfer path kills the transfer outright.
        if (!this::peer.isInitialized) {
            return false
        }
        return peer == Peer.iOS
                || peer == Peer.macOS
                || peer == Peer.Linux
                || (peer == Peer.Android && mode == Mode.Receiving)
    }
    // Linux is in that list as a fork change (白い熊, 2026-08-11), reversing who hosts against a
    // desktop peer. Upstream has Linux host for us; we host for it.
    //
    // The reason is channel width. Our hotspot is a Wi-Fi Direct group that asks for 5 GHz and
    // gets a wide channel; the desktop's is a NetworkManager AP profile, and NetworkManager 1.46
    // exposes `band` and `channel` but nothing for width, so it comes up 20 MHz on every band --
    // measured at 2437 MHz and again at 5745 MHz after the band fix, both 20 MHz. That ceiling is
    // real: the same pair of devices did 20 MHz hotspot at 25.8 MB/s peak and a 160 MHz shared
    // network at 58.8. Hosting from here is the only way to get a wide channel into a
    // Linux transfer, and it costs nothing -- the credentials still travel over Bluetooth, the
    // peer still joins exactly as we used to, and the wire protocol is untouched.
    //
    // It also removes the system network picker from the desktop case entirely: a host never
    // calls requestNetwork(), so there is no dialog to expire (see waitForPeerAp).
    //
    // Both sides have to agree, and they change together: core/src/linux/network.rs's is_hosting()
    // drops Peer::Android in the same commit. An old desktop build against a new phone would have
    // both sides waiting to join -- which is why these two edits must never be split.

    // Bluetooth works in both connection modes (fork, 白い熊 2026-08-07). In hotspot mode it
    // negotiates the hotspot's SSID and password; in shared network mode there is no hotspot, so
    // it carries the transfer password alone -- the switch is the toggle between "use Bluetooth"
    // and "scan the QR code or type the password".
    fun usingBluetooth(): Boolean {
        return bluetooth.active
    }

    override fun usingSharedNetwork(): Boolean {
        return connectionMode == ConnectionMode.SharedNetwork
    }

    // The GATT client callbacks live in BluetoothReceiver, which holds this delegate and not the
    // Bluetooth object that owns the ticker; these two forward for it.
    override fun bleWait(what: String) = bluetooth.startWaitTicker(what)

    override fun bleWaitDone() = bluetooth.stopWaitTicker()

    // Transcript and logcat only. What the scan hears belongs in the record, not on a screen whose
    // log is a bare TextView -- thirty devices would push the transfer itself out of view.
    override fun logDetail(msg: String) {
        Log.i("FlyingCarpet", msg)
        appendToTranscript(msg)
    }

    // Hold the radio at full power for the length of a transfer.
    //
    // Without a WifiLock the driver is free to drop into power-save and to run its periodic
    // background scans mid-transfer, taking the radio off-channel for hundreds of milliseconds at
    // a time -- and it does this whether or not the screen is on and the app in front. Measured
    // from the desktop peer on 2026-08-11: while this phone was *receiving*, its round trip time
    // swung from a 4.7ms minimum to 541ms. Linux read that as congestion, its delivery-rate
    // estimate collapsed to 6mbps, and `pacing_rate` throttled the sender to 19.5mbps while 2.7MB
    // sat queued and our receive window stayed wide open at 1023KB. 31mbps for a link that does
    // 494mbps in the other direction.
    //
    // (An earlier version of this comment also cited `lastrcv:12316` as "12.3 seconds without a
    // packet back from us". That was a misreading: lastrcv counts time since application *data*
    // arrived, and the receiving side sends none, so it simply tracks elapsed time. It says
    // nothing about ACKs and is not evidence of anything. The RTT and pacing figures above are.)
    //
    // Confirmed by the fix: with the lock held, the same transfer's RTT stayed between 14 and
    // 41ms, pacing_rate rose from 19.5mbps to 191-383mbps, and throughput roughly doubled
    // (11.4 -> 21.4 MB/s over the hotspot).
    //
    // The direction is the giveaway, and the reason this went unnoticed: when we *send* we hold
    // the medium continuously and so stay on-channel by accident. Only the receiving side is idle
    // enough for the driver to wander off, which is exactly the half that has no reason to be.
    //
    // FULL_LOW_LATENCY (API 29, and minSdk is 29 so it always exists) disables power-save and
    // suppresses scanning while we are the foreground app -- both halves of the problem. The
    // partial WakeLock beside it keeps the CPU up so a long transfer survives the screen timing
    // out; its timeout is a backstop against leaking the lock, never a transfer budget.
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private fun acquireTransferLocks() {
        try {
            if (wifiLock == null) {
                wifiLock = wifiManager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "FlyingCarpet:transfer"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            if (wakeLock == null) {
                val powerManager =
                    application.getSystemService(AppCompatActivity.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "flyingcarpet:transfer"
                ).apply {
                    setReferenceCounted(false)
                    acquire(TRANSFER_WAKELOCK_TIMEOUT_MS)
                }
            }
        } catch (e: Exception) {
            // A missing lock costs speed, never correctness -- never the transfer.
            Log.i("FlyingCarpet", "Could not acquire transfer locks: $e")
        }
    }

    // Idempotent, and safe to call when nothing was ever acquired: cleanUpTransfer() runs on
    // success, error and cancellation alike, and can be re-entered.
    fun releaseTransferLocks() {
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.i("FlyingCarpet", "Could not release transfer locks: $e")
        }
        wifiLock = null
        wakeLock = null
    }

    suspend fun startTransfer() {
        outputText("\nStarting Transfer")
        acquireTransferLocks()
        // Derive the hotspot PSK up front, while `password` is still known to be the one the
        // peer joined with. Deriving it at handshake time instead left a multi-second window —
        // startTCP() blocks waiting for the peer to associate and connect — during which a
        // stray callback could clear the credentials out from under the transfer. In shared
        // network mode the PSK is derived even earlier, before discovery, which keys its
        // announcement HMAC from it. Same PSK either way: this is ordering only, not a wire
        // change.
        if (connectionMode == ConnectionMode.Hotspot) {
            // Name the path rather than letting derivePsk()'s require() fire. Its message says
            // the credentials "were lost", which gives nothing to act on and does not say which
            // of startTransfer()'s three callers ran -- and on 白い熊's Huawei this fired with no
            // "Joining", no "SSID:" and no hotspot line anywhere above it, i.e. from a caller the
            // visible log did not account for (2026-08-10). These three states tell them apart.
            if (password.isEmpty() && pairedSession?.overHotspot != true) {
                val how = when {
                    hotspotRunning -> "after starting our own hotspot"
                    peerIP != null -> "after joining the peer's hotspot"
                    else -> "without ever hosting or joining a hotspot"
                }
                throw Exception(
                    "Hotspot transfer started $how, but no password was ever exchanged over " +
                        "Bluetooth. Cancel and start the transfer again."
                )
            }
            // Fork: a paired hotspot keys the handshake from the group key itself. The
            // derived Wi-Fi password above is a radio credential and nothing more — someone
            // who learns it joins the access point and gets no further, because the payload
            // is behind 256 bits of randomness that never leaves either device. It also
            // skips 600k PBKDF2 iterations, which exist to slow a dictionary attack on a
            // low-entropy password and buy nothing against a random key.
            val paired = pairedSession
            if (paired != null && paired.overHotspot) {
                psk = derivePairedPsk(
                    paired.key ?: throw Exception("No pairing key for that device; pair it again.")
                )
            } else {
                withContext(Dispatchers.IO) { psk = derivePsk(password) }
            }
        }
        startTCP()
        // Plaintext preamble on the raw socket: version, then send/receive mode. Every
        // preamble byte, sent and received, is recorded and bound into the Noise prologue
        // below, so tampering with the preamble fails the handshake.
        val recordingIn = RecordingInputStream(inputStream)
        val recordingOut = RecordingOutputStream(outputStream)
        inputStream = recordingIn
        outputStream = recordingOut
        confirmVersion()
        confirmMode()
        // The Noise initiator is the TCP client, the responder is the TCP server.
        val role = if (connectionMode == ConnectionMode.SharedNetwork) {
            if (mode == Mode.Sending) NoiseRole.INITIATOR else NoiseRole.RESPONDER
        } else {
            if (isHosting()) NoiseRole.RESPONDER else NoiseRole.INITIATOR
        }
        // Fork: between paired devices the hello goes here, still in the clear and still on
        // the recording streams — who is calling and under which key — so the listening
        // side can pick its pair key before the handshake, and say so in words if it cannot.
        pairedSession?.let { exchangePairedHello(it, role) }
        inputStream = recordingIn.inner
        outputStream = recordingOut.inner
        // Establish the Noise encrypted transport over the same connection, for both modes,
        // with the preamble transcript bound in as the prologue. Everything after this —
        // file count, metadata, and file data — is confidential and tamper-evident. A wrong
        // password (or a tampered preamble) fails the handshake with a clear message.
        val prologue = if (role == NoiseRole.INITIATOR) {
            buildPrologue(recordingOut.transcript(), recordingIn.transcript())
        } else {
            buildPrologue(recordingIn.transcript(), recordingOut.transcript())
        }
        outputText("Establishing encrypted connection...")
        withContext(Dispatchers.IO) {
            val transport = noiseHandshake(inputStream, outputStream, role, psk, prologue)
            inputStream = transport.input
            outputStream = transport.output
        }
        outputText("Encrypted connection established.")
        // Fork: paired devices say who they are and what they are sending before any file
        // data, so an unattended receiver can refuse with a reason and can name the sender.
        // After the handshake, so it is encrypted and the peer has already proved it holds
        // the group key.
        pairedSession?.let { exchangePairedOffer(it) }
        // Assigned unconditionally: a cache left over from a previous transfer must not
        // survive into this one, whichever direction it runs in.
        safCache = if (mode == Mode.Receiving && this::receiveDir.isInitialized) {
            SafDirectoryCache(getApplication(), receiveDir)
        } else {
            null
        }
        // send/receive
        if (mode == Mode.Sending) {
            // tell receiving end how many files we're sending
            val numFilesBytes = longToBigEndianBytes(fileStreams.size.toLong())
            withContext(Dispatchers.IO) {
                outputStream.write(numFilesBytes) // write to receiving end
            }

            // sizes are all known here, so the overall bar can be byte-accurate when sending
            totals = Totals(fileStreams.size, files.sumOf { it.length() })
            // No "apply to all" carried in from a previous transfer.
            conflictRule = null
            // send files
            for (i in 0 until fileStreams.size) {
                totals?.fileIndex = i + 1
                outputText("=========================")
                outputText("Sending file ${i + 1} of ${fileStreams.size}. Filename: ${files[i].name}.")
                val path = if (i < filePaths.size) { filePaths[i] } else { "" }
                sendFile(files[i], fileStreams[i], path, i + 1 < fileStreams.size)
            }

        } else if (mode == Mode.Receiving) {
            // find out how many files we're receiving. sanity bound: no legitimate
            // transfer approaches it, and a corrupt or hostile stream shouldn't be
            // able to put us into a near-endless receive loop.
            val numFilesBytes = readNBytes(8, inputStream)
            val numFiles = ByteBuffer.wrap(numFilesBytes).long
            if (numFiles < 0 || numFiles > 1_000_000) {
                throw Exception("File count $numFiles from peer is out of range")
            }

            // no grand total on this side: sizes arrive one file at a time
            totals = Totals(numFiles.toInt(), null)
            // receive files
            for (i in 0 until numFiles) {
                totals?.fileIndex = (i + 1).toInt()
                outputText("=========================")
                outputText("Receiving file ${i + 1} of $numFiles")
                receiveFile(i == numFiles - 1)
            }
        }
        outputText("=========================")
        outputText("Transfer complete\n")
    }

    override fun cleanUpTransfer() {
        transferIsRunning = false
        // Fork: a paired session must never outlive its transfer, or the next ordinary
        // hotspot transfer would take the paired branch in startTCP() and try to use a
        // socket that is already closed.
        pairedSession = null
        // First thing, before any of the teardown below can throw: a WifiLock left held pins the
        // radio out of power-save for the rest of the process's life.
        releaseTransferLocks()
        safCache = null
        conflictRule = null
        // Clear the finished latch. LiveData is sticky, so a value left at true is redelivered to
        // every observer that registers afterwards -- and the Activity re-observes on each
        // recreation, which a fold or a permission dialog is enough to cause. That redelivery
        // called this method again in the middle of the *next* transfer, and the hotspot callback,
        // finding transferIsRunning false, closed the AP it had just been handed without a word.
        _transferFinished.postValue(false)
        joinAttempts = 0
        progressDetailsMut.postValue("")
        progressTotalDetailsMut.postValue("")
        totalProgressBarMut.postValue(0)
        // The per-file bar too. Its two neighbours were cleared here and it was not, so a
        // cancelled transfer left the bar frozen at whatever fraction it had reached, under a
        // log that had stopped and above an idle Start button (白い熊, 2026-08-08).
        progressBarMut.postValue(0)
        totals = null
        // cancel shared network discovery if it's running
        discoveryManager?.cancel()
        discoveryManager = null
        discoveryJob?.cancel()
        discoveryJob = null
        // unbind from the WiFi network if we bound to it for a shared network transfer
        if (boundToWifiNetwork) {
            val connectivityManager = application
                .getSystemService(AppCompatActivity.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.bindProcessToNetwork(null)
            boundToWifiNetwork = false
        }
        // cancel transfer
        if (transferCoroutine != null) {
            transferCoroutine!!.cancel()
            transferCoroutine = null
        }
        // close tcp streams
        if (this::inputStream.isInitialized) {
            inputStream.close()
        }
        if (this::outputStream.isInitialized) {
            outputStream.close()
        }
        // close sockets, release port
        if (this::client.isInitialized) {
            client.close()
        }
        if (this::server.isInitialized) {
            server.close()
        }
        // tear down hotspot
        if (this::reservation.isInitialized) {
            reservation.close()
        }
        // and the Wi-Fi Direct group, if that is what raised the AP this time. A group left behind
        // keeps the radio as a group owner and the peer able to associate, which is the P2P
        // equivalent of the stranded LocalOnlyHotspot reservation above.
        removeWifiDirectGroup()
        hotspotRunning = false
        // Give the peer's hotspot back, after the sockets above are closed. This is the joining
        // side's half of the teardown: without it the phone stayed on the peer's AP once the
        // transfer ended and never returned to its own WiFi on its own.
        releaseNetwork()
        // stop bluetooth functions
        bluetooth.stop(application)
        // clean up UI
        cleanUpUi()
    }

    override fun connectToPeer() {
        // The BLE credential exchange deliberately runs over two GATT connections (see
        // Bluetooth.onConnectionStateChange), so this, its final step, can be reached more
        // than once per transfer. Re-entering after the hotspot is up would clear the
        // ssid/password the peer has already used to join — and startHotspot() below then
        // no-ops because hotspotRunning is set, so nothing puts them back. That left the
        // in-flight transfer deriving its Noise PSK from an empty password, failing the
        // handshake with SecretKeySpec's opaque "Empty key" while the peer, already
        // associated and waiting, saw only a dropped socket. This guard is the single point
        // that enforces "start the hotspot once": the first call always arrives with
        // hotspotRunning false (MainActivity clears it when Start is pressed; only the
        // LocalOnlyHotspot onStarted callback sets it), and every later replay is a no-op.
        if (hotspotRunning) {
            Log.i("Flying Carpet", "connectToPeer() replayed after hotspot start; ignoring")
            return
        }
        // The joiner's half of that same guard, and the one that was missing. hotspotRunning
        // catches the replay only for the side that *hosts*; a device joining the peer's hotspot
        // never sets it, and in Hotspot mode the shared-network guard below does not apply
        // either — so the post-bond OS write fell straight through to the two lines that clear
        // ssid and password, wiping the credentials this device had just been given and was
        // already using to associate. The join then finished, its network callback started the
        // transfer, and the password was gone: "Hotspot transfer started after joining the
        // peer's hotspot, but no password was ever exchanged" (白い熊, 2026-08-10, the Huawei
        // joining AndroidShare_9975). exchangeComplete is the symmetric condition — gotPassword()
        // sets it only for a non-empty password, precisely so that the empty-password retry this
        // flag would otherwise suppress still gets through.
        if (bluetooth.bluetoothReceiver.exchangeComplete) {
            Log.i("Flying Carpet", "connectToPeer() replayed after the credential exchange; ignoring")
            return
        }
        // The same replay reaches the shared-network path, which has no hotspot flag to catch it.
        // Left unguarded it would clear the credentials below and generate a second password,
        // leaving the peer holding the first one, so the transfer already under way wins.
        if (connectionMode == ConnectionMode.SharedNetwork && transferCoroutine?.isActive == true) {
            Log.i("Flying Carpet", "connectToPeer() replayed after the shared-network transfer started; ignoring")
            return
        }
        warnIfVpnActive()
        ssid = ""
        password = ""
        // Fork: a hotspot between paired devices needs no credential exchange at all. Both
        // ends derive the same password from the group key, and the SSID follows from the
        // password exactly as it always has — including the Wi-Fi Direct group name, which is
        // "DIRECT-fc-" plus that password. So the QR code, the typing and the whole BLE
        // handshake are simply skipped: this device raises or joins, and the other one has
        // already worked out what to look for.
        val pairedHotspot = pairedSession
        val pairedHotspotKey = pairedHotspot?.takeIf { it.overHotspot }?.key
        if (pairedHotspot != null && pairedHotspotKey != null) {
            password = deriveHotspotPassword(pairedHotspotKey)
            ssid = if (isHosting()) {
                getSsidAndKey(password).first
            } else {
                // Joining a paired peer: an Android host raises a Wi-Fi Direct group, whose
                // name is the one thing a generated password could never produce and a
                // derived one can.
                if (peer == Peer.Android) "DIRECT-fc-$password" else getSsidAndKey(password).first
            }
            if (isHosting()) startHotspot() else joinHotspot()
            return
        }
        if (connectionMode == ConnectionMode.SharedNetwork) {
            // No hotspot: discovery finds the peer on the network both devices are already on,
            // and the only thing to agree on is the password. The receiver generates it either
            // way; the Bluetooth switch decides how the sender gets it -- over the BLE link, or
            // off the QR code the receiver displays.
            if (bluetooth.active) {
                if (mode == Mode.Receiving) {
                    // We are the central. Generate the password and write it to the sending
                    // device; the SSID goes with it, derived from the password as everywhere
                    // else, and neither side does anything with it in this mode.
                    password = generatePassword()
                    val (derivedSsid, _) = getSsidAndKey(password)
                    ssid = derivedSsid
                    outputText("Password: $password")
                    outputText("Sending it to the other device over Bluetooth...")
                    // our half of the exchange is done, as in the hotspot host branch below:
                    // a post-bond reconnection must not replay read-OS -> write-OS from here
                    bluetooth.bluetoothReceiver.exchangeComplete = true
                    // the password follows automatically once this write lands (onCharacteristicWrite)
                    bluetooth.bluetoothReceiver.write(SSID_CHARACTERISTIC_UUID, ssid.toByteArray())
                    launchSharedNetworkTransfer()
                } else if (bluetooth.weAreCentral) {
                    // We are sending AND we are the one who connected, so the receiving device is
                    // the peripheral: its password is there to be read rather than waited for.
                    // This is the arrangement that keeps a Linux peer from ever having to connect
                    // out -- see the role comment in core/src/linux/bluetooth.rs.
                    outputText("Reading the password from the other device...")
                    bluetooth.bluetoothReceiver.read(SSID_CHARACTERISTIC_UUID)
                } else {
                    // We are the peripheral, and the receiving device has the password: wait for
                    // it to be written to us. gotPassword() picks the transfer up from there.
                    outputText("Waiting for the other device to send us the password over Bluetooth...")
                }
                return
            }
            if (mode == Mode.Receiving) {
                password = generatePassword()
                outputText("Password: $password")
                outputText("Enter this password on the sending device, or scan the QR code with it.")
                displaySharedNetworkPassword(password)
                launchSharedNetworkTransfer()
            } else {
                // MainActivity shows a dialog and calls gotSharedNetworkPassword() with the result
                promptForPassword()
            }
            return
        }
        // if we're hosting, startHotspot() will write the wifi details over bluetooth or display the QR code
        // if we're joining and using bluetooth, we read peer's wifi characteristic here, then bluetoothReceiver's gattCallback's onCharacteristicRead will call gotSsid()
        // if we're joining and not using bluetooth, barcodeLauncher will call joinHotspot()
        // but who will call connectToPeer? file/folder pickers in MainActivity if not using bluetooth, or after we write OS if bluetooth
        if (isHosting()) {
            // The BLE exchange has done its job for the host: from here the peer reads/receives
            // the wifi details and joins. Mark it complete so a post-bond GATT reconnection
            // doesn't replay read-OS → write-OS mid-transfer. Scoped to the host path on
            // purpose — the joiner (else branch) is only *starting* its SSID/password reads
            // here, so it still relies on the replay as a retry and must not be gated.
            bluetooth.bluetoothReceiver.exchangeComplete = true
            startHotspot()
        } else { // joining hotspot
            if (bluetooth.active) {
                // Which of us reads and which of us waits is decided by the BLE role, never by
                // send/receive. Upstream could equate the two because the sender always
                // advertised; this fork cannot, because Linux now advertises first in *both*
                // directions so it never has to connect out (core/src/linux/bluetooth.rs, the
                // dual-transport bond that picks classic BT and carries no GATT). Against a Linux
                // peer we are therefore always the central -- and a *sending* phone used to match
                // `mode == Mode.Sending` here, take the do-nothing branch meant for the
                // peripheral, and never issue the read at all: the PC sat with its hotspot
                // credentials published waiting to be read, the phone ticked "Setting up WiFi for
                // the transfer" for ever, and neither side ever said why (白い熊, 2026-08-11,
                // phone -> PC over hotspot). Receiving worked only because it happened to fall in
                // the other branch. The shared-network path above has tested weAreCentral since
                // the roles came apart; this is the same test, for the same reason.
                if (bluetooth.weAreCentral) {
                    // we're central, so read wifi details
                    bluetooth.bluetoothReceiver.read(SSID_CHARACTERISTIC_UUID)
                } else {
                    // we're the peripheral, and we're joining, and already know peer's OS, so need
                    // to wait for central to write the hotspot details. so nothing to do here.
                }
            } else {
                // scan qr code
                val options = ScanOptions()
                options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                options.setPrompt("Start transfer on the other device and scan the QR code displayed.")
                options.setOrientationLocked(false)
                barcodeLauncher.launch(options)
            }
        }
    }

    // shared network mode

    // called with the password the user typed or scanned when sending in shared network mode
    fun gotSharedNetworkPassword(entered: String) {
        password = entered
        launchSharedNetworkTransfer()
    }

    private fun launchSharedNetworkTransfer() {
        transferCoroutine = GlobalScope.launch {
            try {
                // Derive the PSK before discovery starts: the discovery announcement HMAC
                // key comes from it. In the coroutine (not the main thread) because PBKDF2
                // at 600k iterations takes a noticeable fraction of a second.
                psk = derivePsk(password)
                findPeerOnSharedNetwork()
                startTransfer()
            } catch (e: CancellationException) {
                // cancelling the transfer (e.g. the Cancel button) is not an error; don't
                // report it. Rethrow so cancellation propagates instead of being swallowed.
                throw e
            } catch (e: Exception) {
                outputText("Transfer error: ${e.message}\n")
            } finally {
                // runs on success, error, and cancellation alike
                finishTransfer()
            }
        }
    }

    private suspend fun findPeerOnSharedNetwork() {
        val connectivityManager = application
            .getSystemService(AppCompatActivity.CONNECTIVITY_SERVICE) as ConnectivityManager
        val (network, localIp, prefixLength) = getSharedNetworkAndIp(connectivityManager)
            ?: throw Exception(
                "No network connection. Shared Network mode requires both devices to be "
                        + "connected to the same network. Connect to WiFi (or wired Ethernet) "
                        + "or use Hotspot mode."
            )
        // route our traffic over this network even if Android prefers another one, e.g.
        // cellular because the local network has no internet access
        connectivityManager.bindProcessToNetwork(network)
        boundToWifiNetwork = true
        outputText("Local IP: ${localIp.hostAddress}/$prefixLength")

        // Receiver is TCP server (consistent with hotspot same-platform convention where the
        // receiver hosts). Bind the listener *before* discovery so it's ready when the sender
        // connects immediately after discovering us.
        if (mode == Mode.Receiving) {
            withContext(Dispatchers.IO) {
                server = ServerSocket(PORT)
                server.soTimeout = 1_000 // poll interval so the accept loop in startTCP() notices cancellation
            }
            outputText("TCP listener ready on port $PORT.")
        }

        val role = if (mode == Mode.Sending) DiscoveryRole.SENDER else DiscoveryRole.RECEIVER
        val discovery =
            DiscoveryManager(getApplication(), deriveDiscoveryKey(psk), role, localIp, prefixLength, ::outputText)
        discoveryManager = discovery
        if (mode == Mode.Receiving) {
            // The sender discovers us and connects, and it stops announcing as soon as it
            // hears us — possibly before we ever hear it. So the TCP connection (accepted
            // in startTCP()) is the receiver's completion signal: discovery runs in the
            // background only to announce our presence and surface diagnostics
            // (receiver-role discoverPeer() never returns a peer).
            discoveryJob = GlobalScope.launch(Dispatchers.IO) {
                try {
                    discovery.discoverPeer()
                } catch (e: CancellationException) {
                    // expected: startTransfer() cancels this job once the sender's TCP
                    // connection arrives (and cleanUpTransfer() cancels it on teardown).
                    // Not an error, so don't surface it. Rethrow so cancellation propagates.
                    throw e
                } catch (e: Exception) {
                    outputText("Discovery error: ${e.message}")
                }
            }
        } else {
            // discoverPeer() searches until the peer is found or the transfer is cancelled
            val peer = discovery.discoverPeer() ?: throw Exception("Discovery cancelled.")
            discoveryManager = null
            peerIP = peer
        }
    }

    // Checked for both modes, not just shared network: a VPN captures the local traffic
    // either way, and lockdown ("Block connections without VPN") is suspected of breaking
    // hotspot mode too, since a hotspot gives the VPN client no route to its server and
    // lockdown then drops everything, LAN included (#138, unconfirmed). Warn rather than
    // refuse — a split tunnel that excludes the local network is fine — because the symptom
    // otherwise is a silent wait until the connect attempts time out (#124).
    private fun warnIfVpnActive() {
        val connectivityManager = application
            .getSystemService(AppCompatActivity.CONNECTIVITY_SERVICE) as ConnectivityManager
        // Ask whether *our* traffic is tunnelled, not whether a tunnel exists anywhere on the
        // device. The old check scanned every network for TRANSPORT_VPN, so it fired whenever a
        // VPN was up at all -- including when this app is on the VPN's excluded list and its
        // packets go straight out the Wi-Fi interface, which is exactly 白い熊's configuration
        // (uid 10977 sits outside every uidrange routed into tun1, verified 2026-08-11). The
        // warning therefore appeared on every single transfer and told them to turn off a VPN
        // that was already irrelevant, which is worse than saying nothing.
        //
        // activeNetwork is the network *this process* will actually use, so it already accounts
        // for per-app exclusions: if we are excluded it is the Wi-Fi network, and NOT_VPN holds.
        val activeCapabilities = connectivityManager.activeNetwork
            ?.let { connectivityManager.getNetworkCapabilities(it) }
        val weAreTunnelled = activeCapabilities != null
                && !activeCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        if (weAreTunnelled) {
            outputText(
                "A VPN is active on this device. Flying Carpet needs direct access to the "
                    + "local network, so if the transfer doesn't connect, turn the VPN off and try again."
            )
        }
    }

    // Prefer WiFi, but accept Ethernet (e.g. USB-C adapters) so wired devices work too.
    // A VPN network inherits its underlying network's transports, so TRANSPORT_WIFI alone
    // also matches a VPN whose tun address the peer can't reach: require NOT_VPN.
    private fun getSharedNetworkAndIp(connectivityManager: ConnectivityManager): Triple<Network, Inet4Address, Int>? {
        var wired: Triple<Network, Inet4Address, Int>? = null
        @Suppress("DEPRECATION")
        for (network in connectivityManager.allNetworks) {
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: continue
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
            val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val isEthernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!isWifi && !isEthernet) continue
            val linkProperties = connectivityManager.getLinkProperties(network) ?: continue
            for (linkAddress in linkProperties.linkAddresses) {
                val address = linkAddress.address
                if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                    if (isWifi) return Triple(network, address, linkAddress.prefixLength)
                    if (wired == null) wired = Triple(network, address, linkAddress.prefixLength)
                }
            }
        }
        return wired
    }

    // same charset and length as the desktop version's generate_password():
    // 10 chars ≈ 2^58, so a precomputed PBKDF2 table over the whole password space
    // (possible because the PSK salt is a fixed domain string) is infeasible.
    private fun generatePassword(): String {
        val chars = "23456789abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ"
        val random = SecureRandom()
        return (1..10).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    // Connects to peerIP, retrying for up to 30 seconds (matches the desktop and Apple
    // implementations). Both connect paths need this and for overlapping reasons.
    //
    // Shared network: the receiver may still be finishing discovery when we start connecting.
    //
    // Hotspot: joinHotspot() starts the transfer from onLinkPropertiesChanged, and the
    // network's IPv4 route is not necessarily in the kernel the instant those properties
    // arrive — nor is bindProcessToNetwork guaranteed to have taken effect. That path used to
    // get exactly one attempt, so a few milliseconds of skew failed the whole transfer with
    // ENETUNREACH (a local "no route" error, so nothing reached the host) while the host sat
    // waiting for a connection that was never sent. See #130.
    private suspend fun connectToPeerWithRetry(peerDescription: String) {
        // Guarded here rather than at the call sites: onLinkPropertiesChanged launches the
        // transfer whether or not it managed to resolve an address, so this is where a missing
        // one has to produce something the user can act on.
        val target = peerIP
            ?: throw Exception(
                "Never learned the other device's address on this network. "
                        + "Start the transfer again, or use Shared Network mode."
            )
        outputText("Connecting to $peerDescription at ${target.hostAddress}:$PORT...")
        val deadline = System.currentTimeMillis() + 30_000
        var attempt = 0
        while (true) {
            attempt++
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(target, PORT), 5000)
                client = socket
                if (attempt > 1) {
                    outputText("Connected on attempt $attempt.")
                }
                return
            } catch (e: Exception) {
                if (System.currentTimeMillis() >= deadline) {
                    throw Exception("Failed to connect to peer after $attempt attempts: ${e.message}")
                }
                outputText("Connection attempt $attempt failed, retrying...")
                delay(2000)
            }
        }
    }

    // hotspot stuff
    private val localOnlyHotspotCallback = object : WifiManager.LocalOnlyHotspotCallback() {
        override fun onFailed(reason: Int) {
            super.onFailed(reason)
            outputText("The hotspot could not be started: ${localOnlyFailure(reason)}")
            hotspotRunning = false
            // Nothing else is coming. Without this the transfer sat armed for ever on a
            // hotspot that was never going to exist.
            cleanUpTransfer()
        }

        override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation?) {
            super.onStarted(res)

            // check for cancellation. if the transfer finished or was cancelled before this
            // callback arrived, tear the hotspot back down and leave hotspotRunning false so the
            // next transfer can start one. this must come before setting hotspotRunning: a stray
            // start (e.g. a leftover BT connection re-driving connectToPeer after a completed
            // transfer) otherwise stuck the flag true and made the next real startHotspot() log
            // "hotspot already running" and hang.
            if (!transferIsRunning) {
                // Say so. This is the one branch that gave the hotspot back without a word, and it
                // is the branch a stale transferFinished latch used to steer us into: the log ended
                // at "Started hotspot" and nothing ever followed.
                outputText("Hotspot came up after the transfer was cancelled — releasing it")
                res?.close()
                hotspotRunning = false
                return
            }

            // set flag so we know not to start this twice
            hotspotRunning = true

            if (res != null) {
                reservation = res
            } else {
                outputText("Failed to get hotspot reservation")
                cleanUpTransfer()
                return
            }

            // get ssid and password
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                val info = reservation.wifiConfiguration
                info?.let {
                    ssid = it.SSID
                    password = it.preSharedKey
                }
            } else {
                val info = reservation.softApConfiguration
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    info.wifiSsid?.let { ssid = it.toString() }
                } else {
                    info.ssid?.let { ssid = it }
                }
                info.passphrase?.let { password = it }
            }

            hotspotCredentialsReady()
        }

        override fun onStopped() {
            super.onStopped()
            outputText("Hotspot stopped")
            hotspotRunning = false
        }
    }

    // Everything that happens once an access point is up and its ssid/password are known, whether
    // LocalOnlyHotspot or Wi-Fi Direct raised it. Both paths end here so the credential handover
    // and the transfer launch cannot drift apart between them.
    private fun hotspotCredentialsReady() {
        // ensure no quotes around the ssid, not sure why this is necessary
        ssid = ssid.replace("\"", "")

        if (bluetooth.active) {
            // The host's half of the same rule, and the same correction: only the central can
            // push a characteristic to its peer, so "write or wait" is a question about the BLE
            // role and not about send/receive. Keyed on `mode` this was wrong in both directions
            // -- a hosting central that was sending would wait to be read by a peer that has no
            // way to read it, and a hosting peripheral that was receiving would write into a
            // GATT client. Linux never reaches here (it hosts for us, so we join), which is why
            // this half stayed latent while the joining branch in connectToPeer() broke in the
            // field; the iOS/macOS/Android peers that do reach it deserve the fixed version too.
            if (bluetooth.weAreCentral) {
                // write the wifi details to peer
                bluetooth.bluetoothReceiver.write(SSID_CHARACTERISTIC_UUID, ssid.toByteArray())
            } else {
                // we're the peripheral, and hosting, so just need to wait for the central to read
                // from our wifi characteristic. nothing to do here.
            }
        } else {
            // android generates ssid and password for us
            displayQrCode(ssid, password)
        }

        outputText("SSID: $ssid")
        outputText("Password: $password")

        transferCoroutine = GlobalScope.launch {
            try {
                startTransfer()
            } catch (e: CancellationException) {
                // cancelling the transfer is not an error; rethrow so cancellation
                // propagates instead of being swallowed and reported.
                throw e
            } catch (e: Exception) {
                outputText("Transfer error: ${e.message}\n")
            } finally {
                // runs on success, error, and cancellation alike
                finishTransfer()
            }
        }
    }

    // ── Wi-Fi Direct hotspot ────────────────────────────────────────────────────
    // LocalOnlyHotspot cannot be asked for a band. The overload that takes a
    // SoftApConfiguration is @SystemApi behind NETWORK_SETTINGS, so an ordinary app gets whatever
    // the framework picks -- and on 白い熊's Z Fold that is always 2.4 GHz, 20 MHz, 802.11n
    // (every LocalOnlyHotspot session in the phone's own softap history sat on 2412 or 2437 MHz,
    // while the same device reports config_wifiSoftap5ghzSupported: true). That link is the
    // 17 MB/s ceiling: the identical encrypted transfer path did 42 MB/s over a 5 GHz network.
    //
    // A Wi-Fi Direct group owner is the way out that stays within public API. createGroup() with
    // a WifiP2pConfig has been public since API 29 and takes setGroupOperatingBand(), and a P2P
    // group owner still presents as an ordinary WPA2 access point with a network name and a
    // passphrase -- so the peer joins it exactly as it joins a LocalOnlyHotspot, the credentials
    // travel over Bluetooth exactly as before, and the wire protocol is untouched.
    //
    // The band is a *request*: drivers and regulatory domain get the final say, so this can come
    // up on 2.4 GHz anyway, and on some devices createGroup fails outright. Hence the fallback --
    // any failure, and the timeout below for the case where it neither succeeds nor reports,
    // lands us back on LocalOnlyHotspot with nothing lost but a couple of seconds.
    private var p2pManager: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var p2pGroupActive = false
    // Guards the fallback so it can fire only once per transfer: the timeout and an ActionListener
    // failure can both arrive, and two fallbacks would start two hotspots.
    private var wifiDirectSettled = false

    private fun fallBackToLocalOnlyHotspot(why: String) {
        if (wifiDirectSettled) return
        wifiDirectSettled = true
        outputText("Wi-Fi Direct unavailable ($why) — falling back to the standard hotspot.")
        // Fork: the fallback is where a paired hotspot stops being ceremony-free. Android
        // chooses LocalOnlyHotspot's SSID and password itself ("AndroidShare_9975"), so
        // nothing about them can be derived and the other device has no way to work out what
        // to join. Say that plainly here: the QR code hotspotCredentialsReady() falls back to
        // is then the answer, not a bug, and the transfer still completes.
        if (pairedSession?.overHotspot == true) {
            outputText(
                "This hotspot's name and password are chosen by Android, so the other device " +
                    "cannot work them out from the pairing. Scan the QR code on it, or type " +
                    "the password shown below."
            )
        }
        removeWifiDirectGroup()
        // removeGroup() is asynchronous and reports nothing, so the group can still hold the
        // radio when the fallback asks for it — and the fallback then fails with
        // ERROR_NO_CHANNEL, which reads as a different fault entirely. Given that the usual
        // reason for being here at all is BUSY, i.e. a group that already exists, this is
        // exactly the case worth waiting out. A fixed pause rather than a callback because
        // removeGroup's listener is not delivered on every driver — the same reason
        // startWifiDirectGroup carries its own timeout.
        handler.postDelayed({ startLocalOnlyHotspot() }, GROUP_TEARDOWN_SETTLE_MS)
    }

    fun removeWifiDirectGroup() {
        val mgr = p2pManager
        val ch = p2pChannel
        p2pManager = null
        p2pChannel = null
        if (mgr == null || ch == null || !p2pGroupActive) return
        p2pGroupActive = false
        try {
            mgr.removeGroup(ch, null)
        } catch (e: Exception) {
            Log.i("WiFi", "removeGroup() failed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission") // startHotspot() checked the nearby-devices permission
    private fun startWifiDirectGroup() {
        val mgr = application.getSystemService(AppCompatActivity.WIFI_P2P_SERVICE) as? WifiP2pManager
        val ch = mgr?.initialize(application, Looper.getMainLooper(), null)
        if (mgr == null || ch == null) {
            fallBackToLocalOnlyHotspot("this device has no Wi-Fi Direct")
            return
        }
        p2pManager = mgr
        p2pChannel = ch

        // We choose the credentials rather than reading them back, which is what lets the network
        // name carry the DIRECT- prefix the framework requires while the passphrase stays the
        // transfer password everything else is keyed from.
        // Fork: derived rather than generated for a paired hotspot, which is the whole trick
        // — the joiner computes "DIRECT-fc-<password>" for itself and needs to be told
        // nothing. Every other transfer still gets a fresh single-use password.
        val paired = pairedSession
        val pairedKey = paired?.takeIf { it.overHotspot }?.key
        val generated = if (pairedKey != null) {
            deriveHotspotPassword(pairedKey)
        } else {
            generatePassword()
        }
        val netName = "DIRECT-fc-$generated"
        val config = WifiP2pConfig.Builder()
            .setNetworkName(netName)
            .setPassphrase(generated)
            .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
            .build()

        // createGroup's onSuccess only means the request was accepted, so the credentials are
        // taken from the group itself once it exists rather than assumed from the config.
        outputText("Starting a 5 GHz Wi-Fi Direct hotspot...")
        try {
            mgr.createGroup(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = readWifiDirectGroup(mgr, ch, attempt = 0)
                override fun onFailure(reason: Int) =
                    fallBackToLocalOnlyHotspot("group creation refused — ${p2pFailure(reason)}")
            })
        } catch (e: Exception) {
            fallBackToLocalOnlyHotspot("group creation threw: ${e.message}")
            return
        }

        // Neither listener is guaranteed to fire on every driver. Without this the transfer would
        // sit on "Starting a 5 GHz Wi-Fi Direct hotspot..." for ever.
        handler.postDelayed({
            fallBackToLocalOnlyHotspot("it did not come up within ${WIFI_DIRECT_TIMEOUT_MS / 1000}s")
        }, WIFI_DIRECT_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun readWifiDirectGroup(mgr: WifiP2pManager, ch: WifiP2pManager.Channel, attempt: Int) {
        if (wifiDirectSettled) return
        mgr.requestGroupInfo(ch) { group: WifiP2pGroup? ->
            if (wifiDirectSettled) return@requestGroupInfo
            val name = group?.networkName
            val pass = group?.passphrase
            if (group == null || !group.isGroupOwner || name.isNullOrEmpty() || pass.isNullOrEmpty()) {
                // The group is announced before its credentials are readable on some devices.
                if (attempt < WIFI_DIRECT_GROUP_READ_ATTEMPTS) {
                    handler.postDelayed({ readWifiDirectGroup(mgr, ch, attempt + 1) }, 400)
                } else {
                    fallBackToLocalOnlyHotspot("its details never became readable")
                }
                return@requestGroupInfo
            }
            if (!transferIsRunning) {
                outputText("Wi-Fi Direct came up after the transfer was cancelled — releasing it")
                removeWifiDirectGroup()
                return@requestGroupInfo
            }
            wifiDirectSettled = true
            p2pGroupActive = true
            hotspotRunning = true
            ssid = name
            password = pass
            // The band was a request, not an instruction, so say which one we actually got --
            // the whole point of this path is the 5 GHz link, and a silent 2.4 GHz group would
            // look identical to the LocalOnlyHotspot it replaced.
            val freq = try { group.frequency } catch (e: Throwable) { 0 }
            val band = when {
                freq >= 5925 -> "6 GHz"
                freq >= 4900 -> "5 GHz"
                freq > 0 -> "2.4 GHz"
                else -> "an unreported band"
            }
            outputText("Wi-Fi Direct hotspot up on $band${if (freq > 0) " ($freq MHz)" else ""}.")
            hotspotCredentialsReady()
        }
    }

    /**
     * The fallback access point, for when Wi-Fi Direct will not raise a group.
     *
     * It checks its own permission rather than trusting startHotspot()'s, and that is the
     * whole point of this function's existence in this shape (白い熊, 2026-09-11, Android 12):
     * `createGroup` had returned BUSY, which the framework can decide *before* it checks
     * anything — so the transfer arrived here with the permission question still unanswered,
     * `startLocalOnlyHotspot` threw a SecurityException, the catch printed its raw message
     * ("UID 10018 does not have Coarse/Fine Location permission") with no context at all, and
     * the transfer was torn down. Reading that log, nothing said which call had failed or that
     * anything could be done about it.
     *
     * Below API 33 this call wants ACCESS_FINE_LOCATION *and* the master Location switch on;
     * NEARBY_WIFI_DEVICES does not exist there, so the declaration that covers Android 13+ is
     * simply inert on a phone like this one.
     */
    private fun startLocalOnlyHotspot() {
        if (hotspotRunning) {
            Log.e("Flying Carpet", "startLocalOnlyHotspot() called when hotspot already running")
            return
        }
        if (!canStartLocalOnlyHotspot()) return
        try {
            wifiManager.startLocalOnlyHotspot(localOnlyHotspotCallback, handler)
            outputText("Started hotspot. Waiting for the other device to join...")
        } catch (e: SecurityException) {
            // Reached when checkSelfPermission says yes and the framework still says no —
            // which happens: an app-op can be revoked underneath a granted permission, and
            // EMUI's own permission manager does exactly that. Say which call failed and what
            // to do, rather than handing over the framework's own words and nothing else.
            outputText(
                "The fallback hotspot was refused: ${e.message}. Give this app Location " +
                    "permission in Android's settings — the Wi-Fi framework requires it to " +
                    "start a hotspot on this version of Android, and it is not used to work " +
                    "out where you are."
            )
            cleanUpTransfer()
        } catch (e: Exception) {
            outputText("The fallback hotspot could not be started: ${e.message}")
            cleanUpTransfer()
        }
    }

    /**
     * Asks for what the fallback needs, and returns false when the answer has to be waited
     * for — the permission result resumes the transfer through requestPermissionLauncher.
     */
    private fun canStartLocalOnlyHotspot(): Boolean {
        if (Build.VERSION.SDK_INT >= 33) return true
        val granted = ActivityCompat.checkSelfPermission(
            application, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            outputText("The fallback hotspot needs Location permission on this version of Android.")
            // Resumed by the launcher, which knows to come back here rather than restart
            // Wi-Fi Direct — that would only be refused again for the same reason.
            awaitingLocationForFallback = true
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return false
        }
        // The permission is not enough on its own: below API 33 the framework also refuses
        // while the master Location switch is off, and that refusal reads identically.
        val locationManager =
            application.getSystemService(AppCompatActivity.LOCATION_SERVICE) as? LocationManager
        val locationOn = locationManager?.let {
            LocationManagerCompat.isLocationEnabled(it)
        } ?: true
        if (!locationOn) {
            outputText(
                "Android's Location switch is off, and the Wi-Fi framework will not start a " +
                    "hotspot on this version while it is. Turn it on in Quick Settings and " +
                    "start the transfer again. Nothing here uses your position."
            )
            cleanUpTransfer()
            return false
        }
        return true
    }

    /**
     * WifiP2pManager's ActionListener reason codes, in words. A bare "reason 2" is a riddle
     * that has to be looked up in the SDK to be read at all (白い熊 asked exactly that,
     * 2026-09-11), and the four values mean very different things: BUSY is transient and worth
     * retrying, P2P_UNSUPPORTED never will be.
     */
    private fun p2pFailure(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "this device does not support Wi-Fi Direct"
        WifiP2pManager.BUSY ->
            "the Wi-Fi framework is busy; a Wi-Fi Direct group may already exist, here or in " +
                "another app. Turning Wi-Fi off and on again clears a stranded one."
        WifiP2pManager.NO_SERVICE_REQUESTS -> "no service requests were registered"
        WifiP2pManager.ERROR -> "the framework reported an internal error"
        else -> "reason $reason"
    }

    /**
     * WifiManager.LocalOnlyHotspotCallback's reason codes, for the same reason.
     */
    private fun localOnlyFailure(reason: Int): String = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL ->
            "no free Wi-Fi channel — another access point or Wi-Fi Direct group may be using " +
                "the radio"
        WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC ->
            "the framework refused without saying why"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
            "the Wi-Fi hardware is in a mode that cannot also host a hotspot"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED ->
            "tethering is disallowed on this device, possibly by a policy or the carrier"
        else -> "reason $reason"
    }

    /** Entry point for the permission launcher, which cannot see a private function. */
    fun resumeFallbackHotspot() {
        startLocalOnlyHotspot()
    }

    fun startHotspot() {
        val requiredPermission = if (Build.VERSION.SDK_INT < 33) {
            Manifest.permission.ACCESS_FINE_LOCATION
        } else {
            Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (ActivityCompat.checkSelfPermission(
                application, requiredPermission
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(requiredPermission)
        } else {
            try {
                if (hotspotRunning) {
                    Log.e("Flying Carpet", "startHotspot() called when hotspot already running")
                    return
                }
                wifiDirectSettled = false
                startWifiDirectGroup()
            } catch (e: Exception) {
                e.message?.let { outputText(it) }
                cleanUpTransfer()
            }
        }
    }

    // The network request we used to join the peer's hotspot, kept so it can be given back. While a
    // WifiNetworkSpecifier request is registered the framework deliberately holds the device on that
    // network, so leaving one behind stranded the phone on the peer's AP after the transfer instead
    // of letting it return to its normal WiFi.
    private var networkCallback: NetworkCallback? = null

    // Hand the peer's hotspot back. Safe to call when nothing is registered.
    fun releaseNetwork() {
        val callback = networkCallback ?: return
        networkCallback = null
        val connectivityManager =
            application.getSystemService(AppCompatActivity.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.bindProcessToNetwork(null)
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (e: IllegalArgumentException) {
            // Never registered, or already released. Nothing to give back.
            Log.i("WiFi", "Network callback was not registered: $e")
        }
    }

    fun joinHotspot() {
        // Each retry used to build another callback and register another request without releasing
        // the last, so a transfer that retried leaked one request per attempt for the life of the
        // process -- and the platform starts throwing once an app has about a hundred outstanding.
        releaseNetwork()
        joinAttempts += 1
        outputText("Joining $ssid — this drops your other WiFi until the transfer is done")
        awaitingApSince = System.currentTimeMillis()
        waitForPeerAp(0)
    }

    // Don't open the system network picker until the peer's AP is actually on the air.
    //
    // requestNetwork() puts up "Devices to use with 白い熊 魔法絨毯" immediately, and that dialog
    // has its own patience: if the network it is hunting for does not exist yet it eventually
    // gives up and asks whether to keep trying (白い熊, 2026-08-11, screenshot at 19:17). The peer
    // publishes its SSID over Bluetooth *before* it raises the AP -- on the desktop side the
    // credentials are generated at advertise time and `nmcli con up` runs later, and bringing an
    // AP up takes a few seconds -- so we routinely asked the user to pick a network that did not
    // exist. Answering "keep trying" always worked, which is the tell: nothing was wrong except
    // the order.
    //
    // So poll for it first and open the picker once it is visible. If it never shows up we ask
    // anyway rather than stalling: the framework's own matching may still find it, and the old
    // behaviour is the floor, not the ceiling.
    private var awaitingApSince = 0L

    @SuppressLint("MissingPermission") // startHotspot()/joinHotspot() run behind the same gate
    private fun waitForPeerAp(attempt: Int) {
        if (!transferIsRunning) {
            return
        }
        val visible = try {
            @Suppress("DEPRECATION")
            wifiManager.scanResults.any { it.SSID == ssid }
        } catch (e: Exception) {
            // No scan results without location; fall through to the timeout and ask anyway.
            Log.i("WiFi", "Could not read scan results: $e")
            false
        }
        val waited = System.currentTimeMillis() - awaitingApSince
        if (visible || waited >= JOIN_AP_WAIT_MS) {
            if (!visible) {
                outputText("Haven't seen \"$ssid\" on the air yet — asking to join anyway.")
            }
            requestPeerNetwork()
            return
        }
        if (attempt == 0) {
            outputText("Waiting for the other device's hotspot to come on the air...")
        }
        // startScan() is throttled to a handful of calls a minute, so nudge it occasionally and
        // let the system's own scan cycle fill in between. Reading results is not throttled.
        if (attempt % 4 == 0) {
            try {
                @Suppress("DEPRECATION")
                wifiManager.startScan()
            } catch (e: Exception) {
                Log.i("WiFi", "startScan() while waiting for the peer's AP was refused: $e")
            }
        }
        handler.postDelayed({ waitForPeerAp(attempt + 1) }, JOIN_AP_POLL_MS)
    }

    private fun requestPeerNetwork() {
        val callback = NetworkCallback()
        networkCallback = callback
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val connectivityManager =
            application.getSystemService(AppCompatActivity.CONNECTIVITY_SERVICE) as ConnectivityManager
        callback.connectivityManager = connectivityManager
        peerIP = null // we check this in NetworkCallback so that we only start the transfer once per joinHotspot invocation
        connectivityManager.requestNetwork(request, callback)
    }

    override fun gotPeer(peerOSValue: String) {
        // "android|<device id>" from a paired device, or the bare OS from anything else.
        val peerOS = peerOSValue.substringBefore('|')
        val peerId = peerOSValue.substringAfter('|', "").takeIf { it.isNotEmpty() }
        peer = when (peerOS) {
            "android" -> Peer.Android
            "ios" -> Peer.iOS
            "linux" -> Peer.Linux
            "mac" -> Peer.macOS
            "windows" -> Peer.Windows
            else -> {
                outputText("Error: peer sent an unsupported OS.")
                return
            }
        }
        // Fork: contact arriving while nothing is running means a paired device found this
        // one's standing advertisement and wants to send over a hotspot. Arm as the receiving
        // half here; the existing logic below then drives it exactly as a normal transfer
        // would, because by this point it *is* one.
        if (!transferIsRunning) {
            if (onIdlePeerContact?.invoke(peerOS, peerId) != true) {
                outputText(
                    "A device made contact over Bluetooth, but nothing is set up to receive " +
                        "from it here."
                )
                return
            }
        }
        // By role, not by send/receive. The central is the side holding a GATT client, so it is
        // the side that can write -- and the peer's peripheral half is blocked waiting for exactly
        // that write to learn what we are. Keying this off "sending" was right when the sender was
        // always the peripheral; with the roles negotiated (2026-08-08) a *sending* central skipped
        // the write, the peer never learned our OS, and it sat in its advertising wait until the
        // grace ran out -- while this side had already read the password and gone looking for it on
        // the network it had not reached yet.
        if (bluetooth.weAreCentral) {
            bluetooth.bluetoothReceiver.write(OS_CHARACTERISTIC_UUID, osValue().toByteArray())
        } else {
            connectToPeer()
        }
    }

    override fun gotSsid(ssid: String) {
        this.ssid = if (ssid == NO_SSID) { "" } else ssid
    }

    override fun gotPassword(password: String) {
        this.password = password
        if (this.ssid == "") {
            val (ssid, _) = getSsidAndKey(password)
            this.ssid = ssid
        }
        // The joiner's last BLE step, and where its half of the exchange is complete — the
        // counterpart to connectToPeer() setting this on the host path. Without it, a joining
        // device never set the flag at all and so ran the whole transfer with every
        // exchangeComplete guard disarmed: the peer's deliberate teardown (Linux removes its
        // GATT service a second after we read the password, then hangs up) arrived looking like
        // a live failure, and the service-changed rediscovery in Bluetooth.onServiceChanged
        // re-ran into a database with no Flying Carpet service in it and failed the transfer.
        // Both joiner roles land here — central by reading this characteristic, peripheral by
        // having it written to us — so this one assignment covers both. Skip an empty password:
        // that means the peer's hotspot isn't up yet, the exchange is *not* done, and the
        // replay this flag suppresses is the retry we need.
        if (password != "") {
            bluetooth.bluetoothReceiver.exchangeComplete = true
        }
        // In shared network mode there is no hotspot to join: the password was the whole point of
        // the exchange, and the transfer goes straight to discovery on the network we are already
        // on. Guard against the replay for the same reason connectToPeer() does.
        if (connectionMode == ConnectionMode.SharedNetwork) {
            if (transferCoroutine?.isActive == true) {
                Log.i("Flying Carpet", "gotPassword() replayed after the shared-network transfer started; ignoring")
                return
            }
            launchSharedNetworkTransfer()
            return
        }
        joinHotspot()
    }

    override fun getWifiInfo(): Pair<String, String> {
        // TODO: put mutex around this? and when setting it?
        Log.i("Bluetooth", "In getWifiInfo")
        if (ssid == "" || password == "") {
            return Pair("", "")
        }
        return Pair(ssid, password)
    }

    private suspend fun startTCP() {
        // Both branches block with nothing to show for it -- accept() until the peer dials in,
        // connect() until it answers -- so say which one we are in before going quiet.
        //
        // Hotspot mode only. isHosting() reads `peer`, which comes from the peer-OS buttons, and
        // those are hidden in shared network mode -- so asking here threw "lateinit property peer
        // has not been initialized" and ended the transfer the moment discovery had succeeded.
        // Shared network says the same thing for itself further down: "Waiting for TCP connection
        // from sender..." on the receiver, "Connecting to receiver at ..." on the sender.
        if (connectionMode == ConnectionMode.Hotspot) {
            if (isHosting()) {
                outputText("Listening on port $PORT for the other device...")
            } else {
                outputText("Connecting to the other device at ${peerIP?.hostAddress}...")
            }
        }
        withContext(Dispatchers.IO) {
            // Fork: a paired transfer arrives here with `client` already connected — dialled
            // by connectToPairedPeer(), or handed over by the listener that accepted it — so
            // there is nothing to discover and nothing to wait for. Everything below the
            // branches (socket options, streams) still applies and is deliberately shared.
            if (pairedSession?.overHotspot == false) {
                // nothing to do: the socket is open
            } else if (connectionMode == ConnectionMode.SharedNetwork) {
                // receiver is TCP server, sender connects. the server socket was bound
                // before discovery started, in findPeerOnSharedNetwork().
                if (mode == Mode.Receiving) {
                    outputText("Waiting for TCP connection from sender...")
                    // No timeout: the sender may not be started for a long time. Keep
                    // listening until it connects or the transfer is cancelled (the 1s
                    // soTimeout set at bind is just a poll so cancellation is noticed;
                    // cleanUpTransfer() also closes the server socket).
                    while (true) {
                        try {
                            client = server.accept()
                            break
                        } catch (e: SocketTimeoutException) {
                            if (!isActive) throw CancellationException("Transfer cancelled.")
                        }
                    }
                    // the sender is connected: stop announcing
                    discoveryManager?.cancel()
                    discoveryManager = null
                    discoveryJob?.cancel()
                    discoveryJob = null
                    peerIP = client.inetAddress as? Inet4Address
                    outputText("TCP connection accepted")
                } else {
                    connectToPeerWithRetry("receiver")
                    outputText("TCP connection established")
                }
            } else if (isHosting()) {
                server = ServerSocket(PORT)
                client = server.accept()
            } else {
                connectToPeerWithRetry("the other device")
            }
            // The send loop writes an 8-byte chunk length and then the chunk body, so the
            // length reaches the wire as its own small segment. Nagle holds a sub-MSS
            // segment until the peer ACKs what's in flight, and a receiver taking a file
            // body has nothing to send back, so that ACK waits out its delayed-ACK timer
            // (200ms on Windows). That is one stall per chunk: measured on the Rust side
            // 2026-07-25 at 38.8mbps where SMB moved the same file between the same two
            // machines at ~600mbps. Nothing here benefits from Nagle's coalescing.
            client.tcpNoDelay = true
            outputText("Connected")
            // The RECEIVE buffer is deliberately not set; the SEND buffer deliberately is. They
            // look symmetrical and do entirely different jobs.
            //
            // SO_RCVBUF, set by hand, switches off Linux's receive-window autotuning for the life
            // of the socket: the window stops growing with the bandwidth-delay product and freezes
            // at whatever that one request resolved to. On 白い熊's Huawei that was 1023KB, which
            // the desktop peer saw advertised unchanged for an entire transfer -- ample at a 20ms
            // round trip, a hard ceiling of ~3MB/s once latency reached 300ms. Leaving it alone
            // took that direction from 5.77MB/s to 76MB/s (2026-08-12).
            //
            // SO_SNDBUF is not a window, it is how far this app may run ahead of the wire. The
            // send loop reads a 5MB chunk off storage, encrypts it and writes it; with a buffer
            // that size the write returns at once and the next read overlaps the transmission of
            // the last. Autotuned, the buffer settles near the bandwidth-delay product -- about
            // 400KB at the 6ms round trip we measure -- so each write blocks until the wire has
            // drained it and storage and network stop overlapping. Removing this halved sending,
            // 51.5MB/s to 25.6, at a flat rate with 6ms RTT, no loss and the peer's window wide
            // open: nothing pushing back, just a sender no longer able to run ahead (2026-08-12).
            client.sendBufferSize = chunkSize * 2
            //
            // Keepalive, on the other hand, is worth asking for: a peer that vanishes without
            // closing (cancel on the far side drops its Wi-Fi before the FIN gets out) otherwise
            // leaves this socket blocked in read for ever. Java exposes only the on/off switch,
            // whose idle timer is two hours, so the desktop side carries the real timings -- see
            // the keepalive block in core/src/lib.rs.
            client.keepAlive = true
            inputStream = client.getInputStream()
            outputStream = client.getOutputStream()
        }
    }

    private suspend fun confirmVersion() {
        outputText("Checking both devices speak the same version...")
        withContext(Dispatchers.IO) {
            val peerVersion: Long
            if (connectionMode == ConnectionMode.SharedNetwork) {
                // symmetric: both sides send their version, then read the peer's.
                // safe from deadlock because TCP buffers the 8-byte writes.
                outputStream.write(longToBigEndianBytes(WIRE_VERSION))
                peerVersion = ByteBuffer.wrap(readNBytes(8, inputStream)).long
            } else if (isHosting()) {
                // wait for peer's version
                val peerVersionBytes = readNBytes(8, inputStream)
                peerVersion = ByteBuffer.wrap(peerVersionBytes).long
                // send our version
                outputStream.write(longToBigEndianBytes(WIRE_VERSION))
            } else {
                // send our version
                outputStream.write(longToBigEndianBytes(WIRE_VERSION))
                // wait for peer's version
                val peerVersionBytes = readNBytes(8, inputStream)
                peerVersion = ByteBuffer.wrap(peerVersionBytes).long
            }
            if (peerVersion < WIRE_VERSION) {
                // peer's version is lower, so we make the decision and report it to them.
                // v10 is a clean break from earlier versions; if transferring with a higher
                // version, that version decides compatibility.
                if (peerVersion >= 10) {
                    outputStream.write(one)
                } else {
                    outputStream.write(zero)
                    throw Exception("The other device is running 白い熊 魔法絨毯 version $peerVersion, which is not compatible with this version ($MAJOR_VERSION). Please update both devices to the latest version at https://github.com/ShiroiKuma0/shiroikuma-mahojutan.")
                }
            } else if (peerVersion > WIRE_VERSION) {
                // peer's version is higher, so they make the decision
                val isCompatibleBytes = readNBytes(8, inputStream)
                if (ByteBuffer.wrap(isCompatibleBytes).long != 1L) {
                    throw Exception("The other device is running 白い熊 魔法絨毯 version $peerVersion, which is not compatible with this version ($MAJOR_VERSION). Please update both devices to the latest version at https://github.com/ShiroiKuma0/shiroikuma-mahojutan.")
                }
            } // otherwise versions match, implicitly compatible
            // A peer that announced a fork wire version understands the file-conflict exchange.
            peerIsFork = peerVersion >= FORK_WIRE_FLOOR
        }
        if (peerIsFork) {
            outputText("The other device is running this fork; file conflicts will be asked about.")
        }
    }

    /** Ask on this device what to do about a file the other device already has. */
    suspend fun askAboutExistingFile(
        name: String,
        identical: Boolean,
        moreFiles: Boolean,
    ): FileConflictAnswer =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            handler.post {
                askFileConflict(name, identical, moreFiles) { answer ->
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.success(answer))
                    }
                }
            }
        }

    private suspend fun confirmMode() {
        withContext(Dispatchers.IO) {
            val ourMode = if (mode == Mode.Sending) {
                1L
            } else {
                0L
            }
            if (connectionMode == ConnectionMode.SharedNetwork) {
                // symmetric: both sides send their mode, read the peer's, and verify they're opposite
                outputStream.write(if (ourMode == 1L) one else zero)
                val peerMode = ByteBuffer.wrap(readNBytes(8, inputStream)).long
                if (peerMode == ourMode) {
                    throw Exception("Both ends of the transfer selected $mode")
                }
            } else if (isHosting()) {
                // we're hosting, so wait for guest to say what mode they selected, compare to our own, and report back
                val peerModeBytes = readNBytes(8, inputStream)
                val peerMode = ByteBuffer.wrap(peerModeBytes).long
                if (ourMode == peerMode) {
                    outputStream.write(zero)
                    throw Exception("Both ends of the transfer selected $mode")
                } else {
                    // write success to guest
                    outputStream.write(one)
                }
            } else {
                // we're joining, so tell host what mode we selected and wait for confirmation that they don't match
                // if we're in this branch, we're not hosting, so we will have joined a hotspot, so onLinkPropertiesChanged() will have
                // been called, so peerIP should not be null
                if (mode == Mode.Sending) {
                    outputStream.write(one)
                } else {
                    outputStream.write(zero)
                }
                // wait to ensure host responds that mode selection was correct
                val confirmationBytes = readNBytes(8, inputStream)
                val confirmation = ByteBuffer.wrap(confirmationBytes).long
                if (confirmation == 0L) {
                    throw Exception("Both ends of the transfer selected $mode")
                }
            }
        }
    }

    fun readNBytes(n: Int, inputStream: InputStream): ByteArray {
        val b = ByteArray(n)
        var bytesRead = 0
        while (bytesRead < n) {
            try {
                val br = inputStream.read(b, bytesRead, n - bytesRead)
                if (br == -1) {
                    throw Exception("Peer connection closed")
                }
                bytesRead += br
            } catch (e: SocketException) {
                throw Exception("Peer connection closed")
            }
        }
        return b
    }

    fun findNewFilename(destinationDir: DocumentFile, filename: String): String {
        // work with the base name: destinationDir is already the file's parent
        // directory, and a "(n) name" alternative must not contain separators
        val base = filename.split("/").last()
        val dirId = destinationDir.treeDocumentId()
        val cache = safCache
        // Answered from the cached directory listing: this loop used to cost one full
        // directory enumeration per candidate name.
        fun taken(name: String) = if (cache != null) {
            cache.hasChild(dirId, name)
        } else {
            destinationDir.findFile(name) != null
        }
        var newFileName = base
        var i = 1
        while (taken(newFileName)) {
            newFileName = "($i) $base"
            i++
        }
        return newFileName
    }

    // Receive into "<name>.part", and put it under its real name only once the file is whole.
    // Writing straight to the final name left a cancelled transfer's half-file wearing that name,
    // indistinguishable from a complete one (白い熊, 2026-08-11). Mirrors the desktop receiver in
    // core/src/receiving.rs; the difference is only that SAF renames a document rather than a path.
    //
    // Nothing is written to safCache here. The cache answers "is this name taken" for the
    // collision loop, and until the rename lands the answer for the *final* name is still no --
    // files are received one at a time, so promotePartFile() notes it in time for the next one.
    fun createPartFile(
        destinationDir: DocumentFile,
        finalName: String,
    ): Pair<DocumentFile, OutputStream> {
        val part = destinationDir.createFile("*/*", "$finalName.part")
            ?: throw Exception("Could not create .part file URI")
        val stream = getApplication<Application>().contentResolver.openOutputStream(part.uri)
            ?: throw Exception("Could not open output stream to .part file")
        return Pair(part, stream)
    }

    // The counterpart: drop whatever we are replacing, then rename the finished .part onto the
    // real name. `replacing` is set only when the sending device chose Overwrite, and the delete
    // has to come first -- SAF will not rename onto an occupied name, it silently de-duplicates
    // to "name (1)", which would leave both copies behind.
    fun promotePartFile(
        part: DocumentFile,
        finalName: String,
        replacing: Uri?,
        destinationDir: DocumentFile,
    ) {
        replacing?.let { uri ->
            try {
                DocumentsContract.deleteDocument(
                    getApplication<Application>().contentResolver, uri
                )
            } catch (e: Exception) {
                Log.i("FlyingCarpet", "Could not delete the file being replaced: $e")
            }
        }
        if (!part.renameTo(finalName)) {
            throw Exception("Could not rename \"${part.name}\" to \"$finalName\"")
        }
        safCache?.note(
            destinationDir.treeDocumentId(),
            SafDirectoryCache.Entry(part.treeDocumentId(), finalName, part.length(), false),
        )
    }

    fun getOutputStreamForFile(destinationDir: DocumentFile, filename: String): OutputStream {
        val newFile =
            destinationDir.createFile("*/*", filename) ?: throw Exception("Could not create file URI")
        // Record it so the collision loop sees it without re-querying. The name comes from
        // the provider, not from `filename`, because the provider may not honour the
        // requested display name exactly (it de-duplicates and can append an extension).
        //
        // Size is recorded as 0 and not corrected once the file is written, so within a
        // single transfer a second file arriving at an already-received path is written as
        // "(1) name" rather than being skipped as a duplicate. Only reachable by sending two
        // files with the same relative path in one transfer; across transfers the cache is
        // rebuilt from the directory, so the usual skip-if-identical still applies.
        safCache?.let { cache ->
            val created = newFile.treeDocumentId()
            cache.note(
                destinationDir.treeDocumentId(),
                SafDirectoryCache.Entry(created, newFile.name ?: filename, 0, false),
            )
        }
        return getApplication<Application>().contentResolver.openOutputStream(newFile.uri)
            ?: throw Exception("Could not open output stream to new file")
    }

    // used when we join a hotspot
    inner class NetworkCallback : ConnectivityManager.NetworkCallback() {
        lateinit var connectivityManager: ConnectivityManager
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            // The return value matters: on false the process keeps whatever default network
            // it had (often cellular), where the hotspot's address has no route at all and
            // every connect fails immediately. Silently ignoring it made that indistinguishable
            // from the route simply not being ready yet (#130).
            if (!connectivityManager.bindProcessToNetwork(network)) {
                outputText("Warning: couldn't route this app's traffic over the hotspot. If the transfer fails, start it again.")
            }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            connectivityManager.bindProcessToNetwork(null)
            outputText("Disconnected from hotspot")
            _transferFinished.postValue(true)
        }

        override fun onUnavailable() {
            super.onUnavailable()
            connectivityManager.bindProcessToNetwork(null)
            // The peer's hotspot may not have been on the air yet when we first looked -- it is
            // created seconds earlier, and the system picker gives up on one empty scan. That is
            // the "no device found, hit retry" everyone runs into, so do the retry ourselves.
            if (transferIsRunning && joinAttempts < MAX_JOIN_ATTEMPTS) {
                outputText("Hotspot not found yet, looking again")
                joinHotspot()
                return
            }
            outputText("Failed to connect to hotspot")
            _transferFinished.postValue(true)
        }

        // this is our findGateway(), so after we get the gateway/dhcp server ip we're ready to confirm mode and launch transfer
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            super.onLinkPropertiesChanged(network, linkProperties)
            // check if transfer was cancelled before this callback ran
            if (!transferIsRunning) {
                return
            }
            // this was set to null in joinHotspot right before requesting the network that triggers this function.
            // check that it's null so we only start the transfer once per joinHotspot invocation
            if (peerIP == null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    linkProperties.dhcpServerAddress?.let { peerIP = it }
                } else {
                    for (route in linkProperties.routes) {
                        if (route.isDefaultRoute) {
                            peerIP = route.gateway as Inet4Address?
                        }
                    }
                }
                transferCoroutine = GlobalScope.launch {
                    try {
                        startTransfer()
                    } catch (e: Exception) {
                        outputText("Transfer error: ${e.message}\n")
                    }
                    _transferFinished.postValue(true)
                }
            }
        }
//
//        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
//            super.onBlockedStatusChanged(network, blocked)
//            outputText("blocked status changed")
//        }
//
//        override fun onCapabilitiesChanged(
//            network: Network,
//            networkCapabilities: NetworkCapabilities
//        ) {
//            super.onCapabilitiesChanged(network, networkCapabilities)
//            outputText("capabilities changed")
//        }
//
//        override fun onLosing(network: Network, maxMsToLive: Int) {
//            super.onLosing(network, maxMsToLive)
//            outputText("losing")
//        }
    }
    override fun bluetoothFailed() {
        // Once the credential exchange is done, BLE has nothing left to contribute to this
        // transfer — but the peer's BLE teardown is still to come, and from here it is
        // indistinguishable from a failure. Linux removes its GATT service a second after we
        // read the password and then disconnects the link (core/src/linux/bluetooth.rs), which
        // lands as a Service Changed indication, then a disconnect, plus whatever any read or
        // write already in flight returns. Each of those reaches a different one of the ~ten
        // bluetoothFailed() call sites, so gate the teardown itself rather than every caller:
        // after the exchange a BLE failure is a log line, not a reason to kill a transfer that
        // is running over Wi-Fi. Observed 2026-07-25 aborting a Linux->Android transfer between
        // "Joining flyingCarpet_79e9" and the hotspot association.
        if (bluetooth.bluetoothReceiver.exchangeComplete) {
            Log.i("Flying Carpet", "Bluetooth failed after the credential exchange; not failing the transfer")
            return
        }
        // Same idea for the window *between* transfers, which exchangeComplete can't cover
        // because stop() clears it: from stop() until the next scan()/advertise(), no
        // transfer owns the BLE stack, so a late callback — the peer's teardown landing on
        // a leftover client, or the disconnect for a client stop() closed — must not flip
        // the Bluetooth switch off. Observed 2026-07-25 after a completed iOS→Android
        // transfer: the switch turned itself off between two successful transfers.
        if (bluetooth.bluetoothReceiver.tearingDown) {
            Log.i("Flying Carpet", "Bluetooth event between transfers; not failing the transfer")
            return
        }
        enableBluetoothUi(false)
        cleanUpTransfer()
    }
}
