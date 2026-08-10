package dev.spiegl.flyingcarpet

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.*
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
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
val zero = ByteArray(8) // meant to represent a 64-bit unsigned 0
val one = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1) // meant to represent a 64-bit unsigned 1
const val chunkSize = 5_000_000
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
    private lateinit var psk: ByteArray
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

    // How many times to ask for the peer's hotspot before giving up, so a first look that lands
    // before the AP is beaconing costs a few seconds rather than a manual retry.
    private val MAX_JOIN_ATTEMPTS = 4
    private var joinAttempts = 0
    var hotspotRunning = false
    lateinit var wifiManager: WifiManager
    lateinit var reservation: WifiManager.LocalOnlyHotspotReservation
    lateinit var requestPermissionLauncher: ActivityResultLauncher<String>
    val bluetooth = Bluetooth(application, this)
    lateinit var barcodeLauncher: ActivityResultLauncher<ScanOptions>
    lateinit var displayQrCode: (String, String) -> Unit
    lateinit var cleanUpUi: () -> Unit
    lateinit var enableBluetoothUi: (Boolean) -> Unit
    lateinit var promptForPassword: () -> Unit // shared network mode: sender asks user for the receiver's password
    // "The other device already has this file" -- asked on the SENDING device, which is where the
    // user who picked the files is. Set by MainActivity; answered through the callback.
    lateinit var askFileConflict: (String, Boolean, (FileConflictChoice) -> Unit) -> Unit
    // True when the peer announced this fork's wire version, i.e. it understands the conflict
    // exchange. A stock peer is spoken to exactly as upstream does.
    var peerIsFork = false
    lateinit var displaySharedNetworkPassword: (String) -> Unit // shared network mode: receiver shows generated password as QR code
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
                || (peer == Peer.Android && mode == Mode.Receiving)
    }

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

    suspend fun startTransfer() {
        outputText("\nStarting Transfer")
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
            if (password.isEmpty()) {
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
            withContext(Dispatchers.IO) { psk = derivePsk(password) }
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
        inputStream = recordingIn.inner
        outputStream = recordingOut.inner
        // Establish the Noise encrypted transport over the same connection, for both modes,
        // with the preamble transcript bound in as the prologue. The Noise initiator is the
        // TCP client, the responder is the TCP server. Everything after this — file count,
        // metadata, and file data — is confidential and tamper-evident. A wrong password
        // (or a tampered preamble) fails the handshake with a clear message.
        val role = if (connectionMode == ConnectionMode.SharedNetwork) {
            if (mode == Mode.Sending) NoiseRole.INITIATOR else NoiseRole.RESPONDER
        } else {
            if (isHosting()) NoiseRole.RESPONDER else NoiseRole.INITIATOR
        }
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
            // send files
            for (i in 0 until fileStreams.size) {
                totals?.fileIndex = i + 1
                outputText("=========================")
                outputText("Sending file ${i + 1} of ${fileStreams.size}. Filename: ${files[i].name}.")
                val path = if (i < filePaths.size) { filePaths[i] } else { "" }
                sendFile(files[i], fileStreams[i], path)
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
        safCache = null
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
                if (mode == Mode.Sending) {
                    // we're peripheral, and we're joining, and already know peer's OS, so need to
                    // wait for central to write the hotspot details. so nothing to do here.
                } else {
                    // we're central, so read wifi details
                    bluetooth.bluetoothReceiver.read(SSID_CHARACTERISTIC_UUID)
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
        @Suppress("DEPRECATION")
        val vpnActive = connectivityManager.allNetworks.any { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        if (vpnActive) {
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
            outputText("Hotspot failed: $reason")
            hotspotRunning = false
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
            if (mode == Mode.Sending) {
                // we're peripheral, and hosting, so just need to wait for the central to read from our
                // wifi characteristic. nothing to do here.
            } else {
                // write the wifi details to peer
                bluetooth.bluetoothReceiver.write(SSID_CHARACTERISTIC_UUID, ssid.toByteArray())
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
        removeWifiDirectGroup()
        startLocalOnlyHotspot()
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
        val generated = generatePassword()
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
                    fallBackToLocalOnlyHotspot("group creation refused, reason $reason")
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

    private fun startLocalOnlyHotspot() {
        try {
            if (!hotspotRunning) {
                wifiManager.startLocalOnlyHotspot(localOnlyHotspotCallback, handler)
                outputText("Started hotspot. Waiting for the other device to join...")
            } else {
                Log.e("Flying Carpet", "startHotspot() called when hotspot already running")
            }
        } catch (e: Exception) {
            e.message?.let { outputText(it) }
            cleanUpTransfer()
        }
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
        val callback = NetworkCallback()
        networkCallback = callback
        joinAttempts += 1
        outputText("Joining $ssid — this drops your other WiFi until the transfer is done")
        // The peer's AP was created seconds ago, so it is not in our scan cache and the framework
        // has to run its own scan cycle before it can match the specifier -- that is the pause
        // before the network picker settles. Asking for a scan first gives it fresh results to work
        // from. Deprecated since API 28 and throttled to a few calls a minute, so it is best-effort:
        // it can shorten the wait, never lengthen it.
        try {
            @Suppress("DEPRECATION")
            wifiManager.startScan()
        } catch (e: Exception) {
            Log.i("WiFi", "startScan() before joining was refused: $e")
        }
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

    override fun gotPeer(peerOS: String) {
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
        // By role, not by send/receive. The central is the side holding a GATT client, so it is
        // the side that can write -- and the peer's peripheral half is blocked waiting for exactly
        // that write to learn what we are. Keying this off "sending" was right when the sender was
        // always the peripheral; with the roles negotiated (2026-08-08) a *sending* central skipped
        // the write, the peer never learned our OS, and it sat in its advertising wait until the
        // grace ran out -- while this side had already read the password and gone looking for it on
        // the network it had not reached yet.
        if (bluetooth.weAreCentral) {
            bluetooth.bluetoothReceiver.write(OS_CHARACTERISTIC_UUID, "android".toByteArray())
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
            if (connectionMode == ConnectionMode.SharedNetwork) {
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
            client.sendBufferSize = chunkSize * 2
            client.receiveBufferSize = chunkSize * 2
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
                    throw Exception("The other device is running Flying Carpet version $peerVersion, which is not compatible with this version ($MAJOR_VERSION). Please update both devices to the latest version at https://flyingcarpet.spiegl.dev.")
                }
            } else if (peerVersion > WIRE_VERSION) {
                // peer's version is higher, so they make the decision
                val isCompatibleBytes = readNBytes(8, inputStream)
                if (ByteBuffer.wrap(isCompatibleBytes).long != 1L) {
                    throw Exception("The other device is running Flying Carpet version $peerVersion, which is not compatible with this version ($MAJOR_VERSION). Please update both devices to the latest version at https://flyingcarpet.spiegl.dev.")
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
    suspend fun askAboutExistingFile(name: String, identical: Boolean): FileConflictChoice =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            handler.post {
                askFileConflict(name, identical) { choice ->
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.success(choice))
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
