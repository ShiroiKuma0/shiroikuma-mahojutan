package dev.spiegl.flyingcarpet

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.*
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
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
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.security.MessageDigest

const val PORT = 3290

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

const val MAJOR_VERSION: Long = 9
val zero = ByteArray(8) // meant to represent a 64-bit unsigned 0
val one = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1) // meant to represent a 64-bit unsigned 1
const val chunkSize = 5_000_000
//fun ByteArray.toHex(): String = joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }

class MainViewModel(private val application: Application) : AndroidViewModel(application), BluetoothDelegate {

    lateinit var mode: Mode
    lateinit var peer: Peer
    var peerIP: Inet4Address? = null
    var ssid: String = ""
    var password: String = ""
    lateinit var key: ByteArray
    var files: MutableList<DocumentFile> = mutableListOf()
    var fileStreams: MutableList<InputStream> = mutableListOf()
    var filePaths: MutableList<String> = mutableListOf() // paths relative to root directory peer is sending to
    lateinit var receiveDir: Uri
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
    private val handler = Handler(Looper.getMainLooper())
    private var _output = MutableLiveData<String>()
    val output: LiveData<String>
        get() = _output
    override fun outputText(msg: String) {
        // Mirror the on-screen log to logcat. The sending side of a Bluetooth handshake says
        // everything interesting through outputText, and with it going only to a LiveData the
        // sender's half of a stalled handshake could only be read off a photograph of the screen.
        Log.i("FlyingCarpet", msg)
        GlobalScope.launch(Dispatchers.Main) {
            _output.value = msg
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
        return peer == Peer.iOS
                || peer == Peer.macOS
                || (peer == Peer.Android && mode == Mode.Receiving)
    }

    suspend fun startTransfer() {
        outputText("\nStarting Transfer")
        startTCP()
        confirmVersion()
        confirmMode()
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
            // find out how many files we're receiving
            val numFilesBytes = readNBytes(8, inputStream)
            val numFiles = ByteBuffer.wrap(numFilesBytes).long

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
        totals = null
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
        ssid = ""
        password = ""
        // if we're hosting, startHotspot() will write the wifi details over bluetooth or display the QR code
        // if we're joining and using bluetooth, we read peer's wifi characteristic here, then bluetoothReceiver's gattCallback's onCharacteristicRead will call gotSsid()
        // if we're joining and not using bluetooth, barcodeLauncher will call joinHotspot()
        // but who will call connectToPeer? file/folder pickers in MainActivity if not using bluetooth, or after we write OS if bluetooth
        if (isHosting()) {
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

    // hotspot stuff
    private val localOnlyHotspotCallback = object : WifiManager.LocalOnlyHotspotCallback() {
        override fun onFailed(reason: Int) {
            super.onFailed(reason)
            outputText("Hotspot failed: $reason")
            hotspotRunning = false
        }

        override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation?) {
            super.onStarted(res)

            // set flag so we know not to start this twice
            hotspotRunning = true

            // check for cancellation
            if (!transferIsRunning) {
                // Say so. This is the one branch that gave the hotspot back without a word, and it
                // is the branch a stale transferFinished latch used to steer us into: the log ended
                // at "Started hotspot" and nothing ever followed.
                outputText("Hotspot came up after the transfer was cancelled — releasing it")
                res?.close()
                hotspotRunning = false
                return
            }

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

            // ensure no quotes around the ssid, not sure why this is necessary
            ssid = ssid.replace("\"", "")

            // set key
            val hasher = MessageDigest.getInstance("SHA-256")
            hasher.update(password.encodeToByteArray())
            key = hasher.digest()

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
                } catch (e: Exception) {
                    outputText("Transfer error: ${e.message}\n")
                }
                finishTransfer()
            }

        }

        override fun onStopped() {
            super.onStopped()
            outputText("Hotspot stopped")
            hotspotRunning = false
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
        if (mode == Mode.Sending) {
            connectToPeer()
        } else {
            bluetooth.bluetoothReceiver.write(OS_CHARACTERISTIC_UUID, "android".toByteArray())
        }
    }

    override fun gotSsid(ssid: String) {
        this.ssid = if (ssid == NO_SSID) { "" } else ssid
    }

    override fun gotPassword(password: String) {
        this.password = password
        val (ssid, key) = getSsidAndKey(password)
        if (this.ssid == "") {
            this.ssid = ssid
        }
        this.key = key
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
        if (isHosting()) {
            outputText("Listening on port $PORT for the other device...")
        } else {
            outputText("Connecting to the other device at ${peerIP?.hostAddress}...")
        }
        withContext(Dispatchers.IO) {
            if (isHosting()) {
                server = ServerSocket(PORT)
                client = server.accept()
            } else {
                client = Socket(peerIP, 3290)
            }
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
            if (isHosting()) {
                // wait for peer's version
                val peerVersionBytes = readNBytes(8, inputStream)
                peerVersion = ByteBuffer.wrap(peerVersionBytes).long
                // send our version
                outputStream.write(longToBigEndianBytes(MAJOR_VERSION))
            } else {
                // send our version
                outputStream.write(longToBigEndianBytes(MAJOR_VERSION))
                // wait for peer's version
                val peerVersionBytes = readNBytes(8, inputStream)
                peerVersion = ByteBuffer.wrap(peerVersionBytes).long
            }
            if (peerVersion < MAJOR_VERSION) {
                // peer makes decision
                val isCompatibleBytes = readNBytes(8, inputStream)
                if (ByteBuffer.wrap(isCompatibleBytes).long != 1L) {
                    throw Exception("Peer's version of Flying Carpet is not compatible. Please find links to download the newest version at https://flyingcarpet.spiegl.dev.")
                }
            } else {
                // we make decision
                // compatible with version 8. if transferring with higher version, that version will decide compatibility.
                if (peerVersion < 8) {
                    throw Exception("Peer's version of Flying Carpet is not compatible. Please find links to download the newest version at https://flyingcarpet.spiegl.dev.")
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
            if (isHosting()) {
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
                bytesRead += br
            } catch (e: SocketException) {
                throw Exception("Peer connection closed")
            }
        }
        return b
    }

    fun findNewFilename(destinationDir: DocumentFile, filename: String): String {
        var newFileName = filename.split("/").last()
        var fileHandle = destinationDir.findFile(filename)
        var i = 1
        while (fileHandle != null) {
            newFileName = "($i) $filename"
            fileHandle = destinationDir.findFile(newFileName)
            i++
        }
        return newFileName
    }

    fun getOutputStreamForFile(destinationDir: DocumentFile, filename: String): OutputStream {
        val newFile =
            destinationDir.createFile("*/*", filename) ?: throw Exception("Could not create file URI")
        return getApplication<Application>().contentResolver.openOutputStream(newFile.uri)
            ?: throw Exception("Could not open output stream to new file")
    }

    // used when we join a hotspot
    inner class NetworkCallback : ConnectivityManager.NetworkCallback() {
        lateinit var connectivityManager: ConnectivityManager
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            connectivityManager.bindProcessToNetwork(network)
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
        enableBluetoothUi(false)
        cleanUpTransfer()
    }
}

