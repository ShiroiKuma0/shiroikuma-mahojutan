package dev.spiegl.flyingcarpet

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.net.wifi.WifiManager
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.spiegl.flyingcarpet.R.id
import dev.spiegl.flyingcarpet.R.layout

// Where the Bluetooth switch state is kept between runs.
private const val USE_BLUETOOTH_KEY = "use.bluetooth"

class MainActivity : AppCompatActivity() {
    private lateinit var viewModel: MainViewModel
    private lateinit var outputBox: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressDetails: TextView
    private lateinit var lastFolderButton: Button
    private lateinit var progressTotalDetails: TextView
    private lateinit var totalProgressBar: ProgressBar
    private lateinit var bluetoothRequestPermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var filePicker: ActivityResultLauncher<Array<String>>
    private lateinit var folderPicker: ActivityResultLauncher<Uri?>
    private lateinit var localNetworkPermissionLauncher: ActivityResultLauncher<String>
    // true when the local network prompt was raised by the start button rather than at
    // launch, so only that case tells the user to press Start again
    private var localNetworkPromptedFromStart = false
    private lateinit var peerGroup: MaterialButtonToggleGroup
    private lateinit var peerInstruction: TextView
    private lateinit var connectionGroup: MaterialButtonToggleGroup
    private lateinit var bluetoothSwitch: SwitchCompat
    private lateinit var bluetoothIcon: ImageView
    private var bluetoothAvailable = false
    // true when Bluetooth initialization failed only because runtime permissions are
    // missing — recoverable by granting them, unlike missing hardware support. keeps the
    // switch usable so tapping it can re-request permissions (#101)
    private var bluetoothPermissionsMissing = false
    // The switch state each connection mode was last used with lives in the ViewModel
    // (bluetoothCheckedInHotspot / bluetoothCheckedInShared), so a rotation doesn't reset the
    // choice back to the mode's starting default. True while the app is setting the switch
    // itself: the listener fires for programmatic changes too, and those must not be recorded
    // as the user having chosen anything.
    private var settingBluetoothSwitch = false
    // The embedded QR preview inside the shared-network password dialog, while that dialog is up.
    // Held so the camera can be released when the activity goes to the background and picked up
    // again on return, and so the permission callback can start it after the fact.
    private var scannerView: DecoratedBarcodeView? = null
    private var scannerHint: TextView? = null
    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>
    private val settings: Settings by lazy { Settings(this) }

    // The Bluetooth switch state, remembered across restarts (alongside receive.lastDir in the
    // fork's own settings store). Unset means "never touched": on when the radio is available,
    // which is upstream's default.
    private fun useBluetoothRemembered(): Boolean = settings.text(USE_BLUETOOTH_KEY) != "0"

    private fun getFilePicker(): ActivityResultLauncher<Array<String>> {
        return registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            viewModel.files = mutableListOf()
            viewModel.fileStreams = mutableListOf()
            viewModel.filePaths = mutableListOf()
            if (uris.isEmpty()) {
                viewModel.outputText("No files selected.")
                viewModel.cleanUpTransfer()
                return@registerForActivityResult
            }
            for (uri in uris) {
                val file = DocumentFile.fromSingleUri(applicationContext, uri)
                if (file != null) {
                    viewModel.files.add(file)
                } else {
                    viewModel.outputText("Could not open file")
                    viewModel.cleanUpTransfer()
                    return@registerForActivityResult
                }
                val stream = applicationContext.contentResolver.openInputStream(uri)
                if (stream != null) {
                    viewModel.fileStreams.add(stream)
                } else {
                    viewModel.outputText("Could not open file stream")
                    viewModel.cleanUpTransfer()
                    return@registerForActivityResult
                }
            }

            beginTransferWithSelection()
        }
    }

    // The device's master Location switch, which is a different thing from holding the location
    // permission: granting the app ACCESS_FINE_LOCATION does not turn this on, and it is this that
    // older Androids gate scan results on. Unreadable means "on" -- never block a transfer over a
    // question we could not ask.
    private fun locationServicesOn(): Boolean = try {
        (getSystemService(LOCATION_SERVICE) as android.location.LocationManager).isLocationEnabled
    } catch (e: Exception) {
        true
    }

    // Android used to tie BLE scan results to the master Location toggle: with it off, startScan()
    // succeeds, onScanFailed never fires, and not one result is ever delivered. The app looks like
    // it is searching and finding nothing, with every permission granted -- indistinguishable from
    // "the other device is not there". So check before scanning, and offer the setting.
    private fun locationEnabledForScanning(): Boolean {
        // From Android 12 our BLUETOOTH_SCAN is declared neverForLocation, which takes scan results
        // out from under the Location toggle entirely -- so there is nothing to test and nothing to
        // ask 白い熊 for, and refusing to scan here would block a transfer that would have worked.
        // Below 31 the flag does not exist and the toggle still decides whether a single result is
        // ever delivered.
        //
        // The disavowal is honoured by the framework, but it is honoured by *this* framework: if
        // some vendor build ignored it we would be back to scanning into silence, and the dialog
        // that used to explain that is now switched off here. So when Location happens to be off,
        // leave one line in the log saying so. It costs nothing when the scan works, and when it
        // does not it is the difference between a mystery and a one-line answer.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!locationServicesOn()) {
                viewModel.outputText(
                    "Location is off — this version of Android does not need it for Bluetooth " +
                            "scanning, so that is fine. If the other device is never found, turn " +
                            "Location on and try again."
                )
            }
            return true
        }
        if (locationServicesOn()) {
            return true
        }
        viewModel.outputText(
            "Location is switched off. Android returns no Bluetooth scan results at all while it is, " +
                    "so the other device can never be found — even though every permission is granted."
        )
        // ForkDialog, not AlertDialog: the fork's own black/yellow chrome, like every other dialog
        // in the app. A stock Material dialog here would be the one white box in the whole UI.
        ForkDialog.info(
            this,
            "Location is off",
            "Android only returns Bluetooth scan results when Location is on, so 白い熊 魔法絨毯 " +
                    "cannot find the sending device until you turn it on.\n\n" +
                    "Turn Location on, then start receiving again.",
            listOf(
                "Cancel" to { d: android.app.Dialog -> d.dismiss() },
                "Open settings" to { d: android.app.Dialog ->
                    d.dismiss()
                    startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                },
            ),
            cancelable = true,
        )
        viewModel.cleanUpTransfer()
        return false
    }

    // What the file picker does once files are chosen: hand off to Bluetooth if it is on, otherwise
    // fall through to the manual (QR / password) path. Shared files take the same route.
    private fun beginTransferWithSelection() {
        if (viewModel.mode == Mode.Receiving && viewModel.bluetooth.active
            && !locationEnabledForScanning()) {
            return
        }
        if (viewModel.bluetooth.active) {
            if (viewModel.bluetooth.bluetoothGattServer.getService(SERVICE_UUID) == null) {
                viewModel.bluetooth.bluetoothGattServer.addService(viewModel.bluetooth.service)
            }
            // Both roles, always. Whoever makes contact first decides who connects, and this
            // phone is the side that can insist on the LE transport (connectGatt is passed
            // TRANSPORT_LE), so it should be free to take the connecting role in either
            // direction -- a Linux peer cannot, and the classic-bearer trap that cost 白い熊 a
            // whole evening on 2026-08-08 is exactly what happens when it has to.
            viewModel.bluetooth.bluetoothReceiver.waitingForConnection = true
            if (viewModel.mode == Mode.Sending) {
                viewModel.bluetooth.advertise()
            }
            viewModel.bluetooth.scan()
        } else {
            viewModel.connectToPeer()
        }
    }

    // ── Share target ──────────────────────────────────────────────────────────
    // Files shared from a file manager (or anywhere else) arrive here. We preselect them for
    // sending and start looking for the receiving device, so sharing is one action rather than
    // opening the app and picking the same files again.
    private fun handleShareIntent(intent: Intent?): Boolean {
        val action = intent?.action ?: return false
        val uris: List<Uri> = when (action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                }
                listOfNotNull(uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                list ?: emptyList()
            }
            else -> return false
        }
        // Consume it either way, so a rotation or a return to the app does not re-fire the share.
        intent.action = null
        if (uris.isEmpty()) {
            viewModel.outputText("Nothing shared: no files in that share.")
            return false
        }

        viewModel.files = mutableListOf()
        viewModel.fileStreams = mutableListOf()
        viewModel.filePaths = mutableListOf()
        for (uri in uris) {
            val file = DocumentFile.fromSingleUri(applicationContext, uri)
            val stream = try {
                contentResolver.openInputStream(uri)
            } catch (e: Exception) {
                null
            }
            if (file == null || stream == null) {
                viewModel.outputText("Could not open a shared file, ignoring it.")
                continue
            }
            viewModel.files.add(file)
            viewModel.fileStreams.add(stream)
        }
        if (viewModel.files.isEmpty()) {
            viewModel.outputText("Could not open any of the shared files.")
            return false
        }

        findViewById<MaterialButtonToggleGroup>(id.modeGroup).check(id.sendButton)
        viewModel.mode = Mode.Sending
        viewModel.outputText(
            "Sharing ${viewModel.files.size} file${if (viewModel.files.size == 1) "" else "s"} from another app."
        )
        return true
    }

    // Run the shared selection once the activity is set up: permissions are requested during
    // onCreate, and Bluetooth is initialized from their result, so starting inline would race both.
    private fun startSharedTransferWhenReady() {
        window.decorView.post {
            if (!checkForBluetoothPermissions()) {
                // Permissions are still being asked for. The files stay selected; the transfer is
                // one tap on the start button rather than a silent failure.
                viewModel.outputText("Grant the permissions, then press the button to send.")
                return@post
            }
            toggleUI(false)
            viewModel.transferIsRunning = true
            beginTransferWithSelection()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (handleShareIntent(intent)) {
            startSharedTransferWhenReady()
        }
    }

    private fun getFolderPicker(): ActivityResultLauncher<Uri?> {
        return registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                if (viewModel.mode == Mode.Sending) {
                    viewModel.files = mutableListOf()
                    viewModel.fileStreams = mutableListOf()
                    viewModel.filePaths = mutableListOf()
                    val dir = DocumentFile.fromTreeUri(applicationContext, it) ?: run {
                        viewModel.outputText("Could not get DocumentFile from selected directory.")
                        viewModel.cleanUpTransfer()
                        return@registerForActivityResult
                    }
                    // seed with the folder's own name so the receiving device recreates the
                    // folder and puts the files inside it
                    val filesAndPaths = getFilesInDir(dir, dir.name ?: "")
                    for (fileAndPath in filesAndPaths) {
                        val file = fileAndPath.first
                        val path = fileAndPath.second
                        viewModel.files.add(file)
                        viewModel.filePaths.add(path)
                        val stream = applicationContext.contentResolver.openInputStream(file.uri)
                        if (stream != null) {
                            viewModel.fileStreams.add(stream)
                        } else {
                            viewModel.outputText("Could not open file stream")
                            viewModel.cleanUpTransfer()
                            return@registerForActivityResult
                        }
                    }
                    viewModel.sendDir = it
                } else {
                    viewModel.receiveDir = it
                    rememberReceiveDir(it)
                }
                // If using bluetooth, start the process of exchanging OS and wifi information --
                // through the shared function, not a copy of its body. This block used to repeat
                // beginTransferWithSelection() minus its Location check, and picking the
                // destination folder is how a receive normally starts, so this was the one path
                // that reached scan() unguarded: with Location off, startScan() returned success,
                // onScanFailed never fired, not one result was ever delivered, and the app sat on
                // "Scanning for Bluetooth peripherals..." indefinitely with nothing to say -- while
                // the sender two feet away was advertising perfectly well.
                beginTransferWithSelection()
            } ?: run {
                viewModel.outputText("No folder selected.")
                viewModel.cleanUpTransfer()
                return@registerForActivityResult
            }
        }
    }

    private fun getRequestPermissionLauncher(): ActivityResultLauncher<String> {
        return registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                // Permission is granted. Continue the action or workflow in your app.
                viewModel.outputText("Permission granted.")
                // start hotspot here
                viewModel.startHotspot()
            } else {
                val permission = if (Build.VERSION.SDK_INT < 33) {
                    "fine location"
                } else {
                    "nearby device"
                }
                viewModel.outputText(
                    "The Android WifiManager requires $permission permission to start hotspot. "
                            + "This data is not collected. "
                            + "Start transfer again if you would like to grant permission."
                )
                viewModel.cleanUpTransfer()
            }
        }
    }

    // Literal 37 rather than a VERSION_CODES constant: the codename for a just-released API
    // level is the part most likely to be wrong, and SDK_INT comparisons don't need it. Below
    // 37 the permission does not exist and local network access is implicit via INTERNET.
    private fun needsLocalNetworkPermission(): Boolean =
        Build.VERSION.SDK_INT >= 37 && ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_LOCAL_NETWORK
        ) != PackageManager.PERMISSION_GRANTED

    // ACCESS_LOCAL_NETWORK rides along in the Bluetooth request rather than being asked for
    // separately: two permission dialogs cannot be in flight at once, and it is in the same
    // NEARBY_DEVICES group anyway, so this is one dialog rather than two. It still means the
    // user is asked at launch instead of being interrupted by the start button.
    private fun permissionsToRequest(): Array<String> =
        if (needsLocalNetworkPermission()) {
            permissions + Manifest.permission.ACCESS_LOCAL_NETWORK
        } else {
            permissions
        }

    // Deliberately not the launcher above: that one calls startHotspot() on grant, which is
    // right for the nearby-devices permission it was written for and wrong here — this
    // permission is needed by joining and shared network mode too, neither of which hosts a
    // hotspot. Granting here just tells the user to press Start again.
    private fun getLocalNetworkPermissionLauncher(): ActivityResultLauncher<String> {
        return registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                // Granted at launch is the normal path and needs no announcement; only a
                // grant raised from the start button interrupted something worth resuming.
                if (localNetworkPromptedFromStart) {
                    viewModel.outputText("Local network permission granted. Start the transfer again.")
                } else {
                    Log.i("Flying Carpet", "Local network permission granted at launch")
                }
            } else {
                // Worth spelling out: a denial does not produce an error, it produces a TCP
                // connect that times out a minute or two later (#137), so without this the
                // user has no way to connect the symptom to the cause.
                viewModel.outputText(
                    "Android 17 and later require local network permission to reach the other device. "
                            + "Without it a transfer will time out instead of failing immediately. "
                            + "Start the transfer again to be asked, or grant it under "
                            + "Settings > Apps > Flying Carpet > Permissions > Nearby devices."
                )
            }
        }
    }

    private fun getBarcodeLauncher(): ActivityResultLauncher<ScanOptions> {
        return registerForActivityResult(ScanContract()) { result ->
            if (result.contents == null) {
                viewModel.outputText("Scan cancelled, exiting transfer.")
                viewModel.cleanUpTransfer()
            } else if (viewModel.connectionMode == ConnectionMode.SharedNetwork) {
                // shared network QR codes contain just the password, but accept
                // "ssid;password" too in case the receiver is in hotspot mode
                val parts = result.contents.split(';')
                val password = if (parts.count() > 1) parts[1] else parts[0]
                viewModel.gotSharedNetworkPassword(password)
            } else {
                val ssidAndPassword = result.contents.split(';')
                if (ssidAndPassword.count() > 1) {
                    viewModel.ssid = ssidAndPassword[0]
                    viewModel.password = ssidAndPassword[1]
                } else {
                    viewModel.password = ssidAndPassword[0]
                    val (ssid, _) = getSsidAndKey(viewModel.password)
                    viewModel.ssid = ssid
                }
                // join hotspot
                viewModel.joinHotspot()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15 forces edge-to-edge when targeting SDK 35+: the system bars go
        // transparent and the window draws behind them. enable it on every version for a
        // consistent look, and pad the root view by the bar insets so content (especially
        // the QR code, which is pinned to the top corner) stays out from under the bars.
        enableEdgeToEdge()
        setContentView(layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(id.root)) { root, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        // set up file and folder pickers
        filePicker = getFilePicker()
        folderPicker = getFolderPicker()

        // set up permissions request
        viewModel.wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        viewModel.requestPermissionLauncher = getRequestPermissionLauncher()
        // Registered for the start-button fallback only. Deliberately *not* launched here:
        // Android runs one permission request at a time, and bluetoothOnCreate() already
        // raises one during onCreate. A second launch() in the same window is dropped and its
        // callback fires synchronously with an empty result map — see the guard on the
        // Bluetooth callback. ACCESS_LOCAL_NETWORK is asked for as part of that single
        // request instead, via permissionsToRequest().
        localNetworkPermissionLauncher = getLocalNetworkPermissionLauncher()

        cameraPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                startScannerPreview()
            } else {
                scannerHint?.text =
                    "Camera permission was declined, so type the password shown on the other device."
            }
        }
        viewModel.barcodeLauncher = getBarcodeLauncher()
        viewModel.displayQrCode = ::displayQrCode
        viewModel.cleanUpUi = ::cleanUpUi
        viewModel.enableBluetoothUi = ::enableBluetoothUi
        viewModel.promptForPassword = ::promptForPassword
        viewModel.displaySharedNetworkPassword = ::displaySharedNetworkPassword

        peerGroup = findViewById(id.peerGroup)
        peerInstruction = findViewById(id.peerInstruction)
        connectionGroup = findViewById(id.connectionGroup)
        outputBox = findViewById(id.outputBox)
        // Seed from the ViewModel, which survives rotation, rather than from the saved-state
        // Bundle, which can't hold a long transfer's log (see MainViewModel.outputSnapshot).
        val (transcript, seededThrough) = viewModel.outputSnapshot()
        outputBox.text = transcript
        var lastRenderedLine = seededThrough
        viewModel.output.observe(this) { line ->
            // LiveData redelivers its latest value on (re)subscription; skip it if the seed
            // already covers it, so rotation doesn't duplicate the last line.
            if (line.seq <= lastRenderedLine) return@observe
            lastRenderedLine = line.seq
            outputBox.append(line.text + '\n')
            scrollOutputToBottom()
        }
        scrollOutputToBottom()
        progressBar = findViewById(id.progressBar)
        viewModel.progressBar.observe(this) { value ->
            progressBar.progress = value
        }
        progressDetails = findViewById(id.progressDetails)
        viewModel.progressDetails.observe(this) { details ->
            progressDetails.text = details
            progressDetails.isVisible = details.isNotEmpty()
        }
        progressTotalDetails = findViewById(id.progressTotalDetails)
        totalProgressBar = findViewById(id.totalProgressBar)
        viewModel.progressTotalDetails.observe(this) { details ->
            // one file needs no second row -- the two bars would say the same thing
            val show = details.isNotEmpty() && !details.startsWith("File 1 of 1")
            progressTotalDetails.text = details
            progressTotalDetails.isVisible = show
            totalProgressBar.isVisible = show
        }
        viewModel.totalProgressBar.observe(this) { value ->
            totalProgressBar.progress = value
        }
        viewModel.transferFinished.observe(this) { finished ->
            // this was firing because when we started observing, we were running viewModel.cleanUpTransfer()
            // no matter what. and then _transferFinished was true. now initializing as false.
            if (finished) {
                viewModel.cleanUpTransfer()
            }
        }

        // Print the greeting as a real line, the way the desktop does, rather than relying on
        // the output box's android:hint. A hint only renders while the view is empty, and
        // since 91e8005 startup emits "Bluetooth initialized" almost immediately, so on any
        // device with Bluetooth on the greeting was overwritten before it could be read.
        // Emitted before bluetoothOnCreate() below so it stays the first line, and only when
        // the transcript is empty so a rotation (which replays the ViewModel's log) doesn't
        // repeat it.
        if (transcript.isEmpty()) {
            viewModel.outputText(getString(R.string.welcome))
        }

        // set up bluetooth
        bluetoothOnCreate()

        // connection mode (hotspot vs. shared network). registered after bluetoothOnCreate()
        // so applyConnectionModeUi() gets the last word on peer group visibility.
        connectionGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            viewModel.connectionMode = if (checkedId == id.sharedNetworkButton) {
                ConnectionMode.SharedNetwork
            } else {
                ConnectionMode.Hotspot
            }
            applyConnectionModeUi()
        }
        // the view model survives rotation, so restore its connection mode into the UI
        connectionGroup.check(
            if (viewModel.connectionMode == ConnectionMode.SharedNetwork) {
                id.sharedNetworkButton
            } else {
                id.hotspotButton
            }
        )
        applyConnectionModeUi()

        // start button
        // The two send buttons -- "Files to send" and "Directory to send" -- and, in receive mode,
        // the single button that picks where to receive. They all run the same start, differing
        // only in what gets picked afterwards; this used to be a "Send Folder" tick box that had
        // to be found and ticked before pressing Start (白い熊, 2026-08-08).
        val startButton = findViewById<Button>(id.startButton)
        val sendDirButton = findViewById<Button>(id.sendDirButton)
        startButton.setOnClickListener { startPressed(sendFolder = false) }
        sendDirButton.setOnClickListener { startPressed(sendFolder = true) }

        // cancel button
        val cancelButton = findViewById<Button>(id.cancelButton)
        cancelButton.setOnClickListener {
            // Said here rather than in cleanUpTransfer(), which every finished transfer runs
            // through, successful ones included. Without it the log simply stopped mid-sentence
            // and nothing distinguished a cancelled transfer from one that had died.
            viewModel.outputText("Transfer cancelled.")
            viewModel.cleanUpTransfer()
        }

        // Reuse the directory picked last time: identical to picking it again, minus the dialog.
        lastFolderButton = findViewById(id.lastFolderButton)
        lastFolderButton.setOnClickListener {
            val uri = lastReceiveDir() ?: return@setOnClickListener
            viewModel.receiveDir = uri
            viewModel.mode = Mode.Receiving
            // Arm the transfer, exactly as the start button does. Without this the whole receive
            // ran with transferIsRunning false, and the hotspot's onStarted callback -- which
            // treats that flag as "the user cancelled" -- gave the AP straight back 110 ms after
            // the framework handed it over, silently. Every one-tap receive stalled there.
            viewModel.transferIsRunning = true
            // Locked for the same reason as the start button: a recreation mid-transfer loses the
            // Activity state the callbacks are about to come back to.
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            toggleUI(false)
            beginTransferWithSelection()
        }

        // send/receive. A checked listener rather than a click listener on each button:
        // onRestoreInstanceState reselects the mode with modeGroup.check(), which fires this
        // but not a click, so after a rotation the label stayed at the layout's "Select Files"
        // while Receive was checked.
        val modeGroup = findViewById<MaterialButtonToggleGroup>(id.modeGroup)
        modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            if (checkedId == id.sendButton) {
                startButton.text = settings.textOr("start.filesText", getString(R.string.selectFiles))
                sendDirButton.isVisible = true
                refreshLastFolderButton()
            } else {
                startButton.text = settings.textOr("start.folderText", getString(R.string.selectFolder))
                sendDirButton.isVisible = false
                refreshLastFolderButton()
            }
            // which side shows the QR code and which scans it depends on this choice
            updateBluetoothHint()
        }

        // about button
        val aboutButton = findViewById<TextView>(id.aboutButton)
        aboutButton.setOnClickListener {
            val aboutFragment = About()
            aboutFragment.show(supportFragmentManager, "alert")
        }

        // 白い熊 魔法絨毯 UI customization page
        val uiButton = findViewById<TextView>(id.uiButton)
        uiButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Launched from the share sheet? Preselect what was shared and start looking for the peer.
        // Skipped on a recreate (rotation), where the files are already in the ViewModel.
        if (savedInstanceState == null && handleShareIntent(intent)) {
            startSharedTransferWhenReady()
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-apply the UI customizations every time we return to the screen (incl. from the settings page).
        Appearance.apply(this)
        // and re-evaluate the remembered-directory button: it was previously only refreshed on a
        // mode tap, so on a fresh launch it never appeared at all
        if (this::lastFolderButton.isInitialized) {
            refreshLastFolderButton()
        }
        // permissions may have been granted in system Settings while the app was in the
        // background: recover the Bluetooth switch without requiring a restart (#101)
        if (!bluetoothAvailable && bluetoothPermissionsMissing && checkForBluetoothPermissions()) {
            initializeBluetooth()
        }
        // pick the preview back up if the password dialog was open when we left
        scannerView?.let { if (it.visibility == View.VISIBLE) it.resume() }
    }

    override fun onPause() {
        super.onPause()
        // never hold the camera open behind another app: the dialog survives, the preview restarts
        // in onResume()
        scannerView?.pause()
    }

    // The directory picked last time, remembered across restarts so receiving is one tap. The tree
    // Uri only survives a restart if we take a persistable grant for it, which the picker does not
    // do on its own.
    private fun rememberReceiveDir(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: Exception) {
            Log.i("Receive", "Could not persist directory permission: $e")
        }
        settings.setText("receive.lastDir", uri.toString())
        refreshLastFolderButton()
    }

    private fun lastReceiveDir(): Uri? {
        val stored = settings.text("receive.lastDir")
        return if (stored.isEmpty()) null else Uri.parse(stored)
    }

    private fun refreshLastFolderButton() {
        val uri = lastReceiveDir()
        val receiving = findViewById<MaterialButtonToggleGroup>(id.modeGroup)?.checkedButtonId == id.receiveButton
        val show = uri != null && receiving && findViewById<Button>(id.startButton).isVisible
        lastFolderButton.isVisible = show
        if (uri != null) {
            val name = DocumentFile.fromTreeUri(applicationContext, uri)?.name
                ?: uri.lastPathSegment ?: uri.toString()
            lastFolderButton.text = getString(R.string.receiveIn, name)
        }
    }


    // What both send buttons (and the receive button, which is the same view) do: arm the transfer,
    // lock the UI, read the mode and peer, then open the picker the pressed button implies.
    private fun startPressed(sendFolder: Boolean) {

        // Fallback for a denial at launch. Checked here rather than in startHotspot()
        // because that only covers the hosting path — joining a hotspot and shared network
        // mode need this just as much, and shared network mode never calls startHotspot()
        // at all. Runs before any transfer state is touched, so bailing out is a plain
        // return rather than a half-started transfer to unwind.
        if (needsLocalNetworkPermission()) {
            localNetworkPromptedFromStart = true
            localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
            return
        }

        // determine send/receive, peer, show file pickers, show or read qr code, or display wifi info
        // then start or join tcp server and start sending or receiving files

        // register that the transfer is running. this is needed so that if the hotspot is kicked off, then the cancel button is hit,
        // the hotspot onStarted callback can bail out.
        viewModel.transferIsRunning = true
        // clear any hotspot flag left over from a previous transfer so this one can start one
        viewModel.hotspotRunning = false

        // disable UI elements while transfer is running
        toggleUI(false)

        // prevent screen rotation while transfer is running
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED

        // get mode
        val modeGroup = findViewById<MaterialButtonToggleGroup>(id.modeGroup)
        val selectedMode = modeGroup.checkedButtonId
        this.viewModel.mode = when (selectedMode) {
            id.sendButton -> Mode.Sending
            id.receiveButton -> Mode.Receiving
            else -> {
                viewModel.outputText("Must select whether this device is sending or receiving.")
                viewModel.cleanUpTransfer()
                return
            }
        }

        // get peer. not needed in shared network mode (discovery finds the peer)
        // or when using bluetooth (peer OS is exchanged over BLE)
        val selectedPeer = peerGroup.checkedButtonId
        if (viewModel.connectionMode == ConnectionMode.Hotspot && !viewModel.bluetooth.active) {
            this.viewModel.peer = when (selectedPeer) {
                id.androidButton -> Peer.Android
                id.iosButton -> Peer.iOS
                id.linuxButton -> Peer.Linux
                id.macButton -> Peer.macOS
                id.windowsButton -> Peer.Windows
                else -> {
                    viewModel.outputText("Must select operating system of other device.")
                    viewModel.cleanUpTransfer()
                    return
                }
            }
        }

        // which of the two send buttons was pressed
        this.viewModel.sendFolder = sendFolder

        when (viewModel.mode) {
            Mode.Sending -> {
                if (viewModel.sendFolder) {
                    folderPicker.launch(Uri.EMPTY)
                } else {
                    filePicker.launch(arrayOf("*/*"))
                }
            }
            Mode.Receiving -> folderPicker.launch(Uri.EMPTY)
        }

    }

    // Bluetooth is usable in both connection modes (fork, 白い熊 2026-08-07): in hotspot mode it
    // negotiates the hotspot credentials, in shared network mode it carries the transfer password
    // alone. The switch itself belongs to the user -- changing the connection mode never turns it
    // on or off, and the choice outlives a restart (useBluetoothRemembered). Only Bluetooth being
    // unavailable overrides it, and that disables the switch rather than pretending it is off.
    private fun applyConnectionModeUi() {
        val sharedNetwork = viewModel.connectionMode == ConnectionMode.SharedNetwork
        // usable if initialized, or if only permissions are missing (tapping the
        // switch then re-requests them, #101)
        setBluetoothSwitchEnabled(bluetoothAvailable || bluetoothPermissionsMissing)
        if (!bluetoothAvailable) {
            setBluetoothSwitchChecked(false)
        }
        // the switch listener only runs when the value actually changes, so assert this here too
        // rather than leaving "Bluetooth is on" and "the switch is on" free to disagree
        viewModel.bluetooth.active = bluetoothSwitch.isChecked
        bluetoothIcon.isVisible = bluetoothSwitch.isChecked
        // peer OS comes from discovery in shared network mode and over BLE when Bluetooth is on,
        // so the group is only asked for in hotspot mode without Bluetooth. Set after the switch
        // listener has run, since that makes the peer group visible.
        val needPeer = !sharedNetwork && !viewModel.bluetooth.active
        peerGroup.isVisible = needPeer
        peerInstruction.isVisible = needPeer
        updateBluetoothHint()
    }

    // The line under the switch. With Bluetooth off it says who will display the QR code and
    // password and who will scan or type it -- before the transfer starts, rather than when the QR
    // code is already on screen (白い熊 2026-08-07). Hotspot mode stays deliberately vague about
    // which device is which: there it follows from the peer's OS, not from send/receive.
    // Portrait only: the landscape layout has no room under the switch, and findViewById returns
    // null there, which this tolerates.
    private fun updateBluetoothHint() {
        val hint = findViewById<TextView>(id.bluetoothHint) ?: return
        val sending = findViewById<MaterialButtonToggleGroup>(id.modeGroup)?.checkedButtonId
        hint.text = when {
            bluetoothSwitch.isChecked -> getString(R.string.bluetoothHintOn)
            viewModel.connectionMode != ConnectionMode.SharedNetwork ->
                getString(R.string.bluetoothHintHotspot)
            sending == id.sendButton -> getString(R.string.bluetoothHintSending)
            sending == id.receiveButton -> getString(R.string.bluetoothHintReceiving)
            else -> getString(R.string.bluetoothHintOff)
        }
    }

    // shared network mode, sending: ask for the password displayed on the receiving device
    // Shared network mode, sending: one dialog that scans AND types. The camera preview is embedded
    // here rather than launched as zxing's full-screen CaptureActivity, so the password field stays
    // on screen the whole time -- there is nothing to back out of to reach the keyboard, which is
    // what a separate full-window scanner forced.
    //
    // The preview is only started once CAMERA has been granted; refused, or on a device without a
    // camera, the dialog quietly becomes the typing dialog it already is.
    private fun promptForPassword() {
        val accent = ForkDialog.accent(this)
        val box = ForkDialog.box(this)
        box.addView(ForkDialog.heading(this, "Password"))
        box.addView(ForkDialog.spacer(this, 12))

        val preview = DecoratedBarcodeView(this).apply {
            setStatusText("")
            barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ForkDialog.dp(this@MainActivity, 200),
            )
            visibility = View.GONE   // shown by startScannerPreview() once permission is in hand
        }
        box.addView(preview)
        box.addView(ForkDialog.spacer(this, 10))

        val hintLabel = ForkDialog.label(
            this,
            "Point the camera at the QR code on the other device, or type the password below.",
        )
        box.addView(hintLabel)
        box.addView(ForkDialog.spacer(this, 6))

        val input = EditText(this).apply {
            hint = "Password"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextColor(accent)
            setHintTextColor((accent and 0x00FFFFFF) or 0x80000000.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        box.addView(input)

        val dialog = ForkDialog.wrap(this, box, cancelable = false)
        scannerView = preview
        scannerHint = hintLabel

        // Everything that closes this dialog goes through here, so the camera is released exactly
        // once however the dialog ends -- scanned, typed, or cancelled.
        val close = {
            preview.pause()
            scannerView = null
            scannerHint = null
            dialog.dismiss()
        }
        val accept = { entered: String ->
            if (entered.length < 10) {
                viewModel.outputText("Password must be at least 10 characters. Please start the transfer again.")
                close()
                viewModel.cleanUpTransfer()
            } else {
                close()
                viewModel.gotSharedNetworkPassword(entered)
            }
        }

        preview.decodeSingle(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                val text = result.text ?: return
                // shared network QR codes carry just the password, but accept "ssid;password" too
                // in case the other device is showing a hotspot-mode code
                val parts = text.split(';')
                accept((if (parts.size > 1) parts[1] else parts[0]).trim())
            }

            override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>?) {}
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            clipChildren = false
            setPadding(0, ForkDialog.dp(this@MainActivity, 16), 0, 0)
        }
        row.addView(
            ForkDialog.pill(this, "Cancel") {
                close()
                viewModel.outputText("Transfer cancelled.")
                viewModel.cleanUpTransfer()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = ForkDialog.dp(this@MainActivity, 10) }
            },
        )
        row.addView(ForkDialog.pill(this, "OK") { accept(input.text.toString().trim()) })
        box.addView(row)

        dialog.show()
        startScannerPreview()
    }

    // Starts the embedded preview if we may use the camera, and asks for it if we have not been
    // told yet. A refusal is not fatal: the dialog stays up and the password can be typed.
    private fun startScannerPreview() {
        val preview = scannerView ?: return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            preview.visibility = View.VISIBLE
            preview.resume()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // shared network mode, receiving: show the generated password as a QR code with the password
    // itself printed underneath it, so both ways of passing it over are on screen at once and
    // nothing has to be dismissed first. (it's also in the output box, as a record.)
    private fun displaySharedNetworkPassword(password: String) {
        runOnUiThread {
            val qrCode = findViewById<ImageView>(id.qrCodeView)
            viewModel.qrBitmap = getQrCodeBitmapWithCaption(password, password)
            // never tint a QR code -- it must stay black on white to scan. The logo that normally
            // occupies this ImageView is tinted yellow by the fork, and that filter outlives the
            // drawable, so without clearing it here the code came out yellow on black.
            qrCode.colorFilter = null
            qrCode.setImageBitmap(viewModel.qrBitmap)
            qrCode.bringToFront()
        }
    }

    private fun cleanUpUi() {
        // toggle UI and replace icon
        runOnUiThread {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            toggleUI(true)
            val qrCode = findViewById<ImageView>(id.qrCodeView)
            val drawable = AppCompatResources.getDrawable(applicationContext, R.drawable.icon1024)
            qrCode.setImageDrawable(drawable)
            // The logo is back — re-apply its tint (cleared while a QR code was shown).
            Appearance.applyLogoTint(this)
        }
    }

    private fun displayQrCode(ssid: String, password: String) {
        if (viewModel.peer == Peer.iOS || viewModel.peer == Peer.Android) {
            // display qr code
            val qrCode = findViewById<ImageView>(id.qrCodeView)
            viewModel.qrBitmap = getQrCodeBitmap(ssid, password)
            qrCode.colorFilter = null   // never tint a QR code — it must stay black/white to scan
            qrCode.setImageBitmap(viewModel.qrBitmap)
            qrCode.bringToFront()
        } else { // peer is macOS, because if windows or linux we wouldn't be hosting
            val alertFragment =
                Alert("Start the transfer on macOS and enter these when prompted:\n\nSSID: $ssid\nPassword: $password")
            alertFragment.show(supportFragmentManager, "alert")
        }
    }

    private fun toggleUI(enabled: Boolean) {
        findViewById<Button>(id.sendButton).isEnabled = enabled
        findViewById<Button>(id.receiveButton).isEnabled = enabled
        findViewById<Button>(id.hotspotButton).isEnabled = enabled
        findViewById<Button>(id.sharedNetworkButton).isEnabled = enabled
        findViewById<Button>(id.androidButton).isEnabled = enabled
        findViewById<Button>(id.iosButton).isEnabled = enabled
        findViewById<Button>(id.linuxButton).isEnabled = enabled
        findViewById<Button>(id.macButton).isEnabled = enabled
        findViewById<Button>(id.windowsButton).isEnabled = enabled
        findViewById<Button>(id.sendDirButton).isEnabled = enabled

        findViewById<Button>(id.startButton).isInvisible = !enabled
        findViewById<Button>(id.cancelButton).isInvisible = enabled

        findViewById<TextView>(id.aboutButton).isClickable = enabled
        setBluetoothSwitchEnabled(
            enabled && (bluetoothAvailable || bluetoothPermissionsMissing)
        )

        // Last, not first. refreshLastFolderButton() decides visibility partly from the start
        // button's, so running it ahead of the line above made it read the value left over from the
        // transfer that had just ended -- and the one-tap receive button stayed gone until the mode
        // buttons were tapped again.
        if (this::lastFolderButton.isInitialized) {
            if (enabled) refreshLastFolderButton() else lastFolderButton.isVisible = false
        }
    }

    // The switch paints its thumb, track and label from state lists keyed on the view's
    // drawable state, and the Material switch drawables animate between those states. Setting
    // isEnabled updates the state but the repaint could be dropped — a transition started while
    // the transfer was running, or while the window wasn't drawing, left the switch showing the
    // disabled colors after the transfer ended and only correcting itself on the next drawable
    // state change, i.e. when the user touched it. Re-resolve the state and snap the drawables
    // (and any half-finished thumb animation) to it so a change to the switch is always visible
    // immediately. Every isEnabled write goes through here so no call site can reintroduce it.
    // Every programmatic check/uncheck goes through here, so the listener can tell the app's own
    // writes from the user's tap and only remember the latter.
    private fun setBluetoothSwitchChecked(checked: Boolean) {
        settingBluetoothSwitch = true
        bluetoothSwitch.isChecked = checked
        settingBluetoothSwitch = false
    }

    private fun setBluetoothSwitchEnabled(enabled: Boolean) {
        bluetoothSwitch.isEnabled = enabled
        bluetoothSwitch.refreshDrawableState()
        bluetoothSwitch.jumpDrawablesToCurrentState()
        bluetoothSwitch.invalidate()
    }

    // The output box is a bare TextView, not a ScrollView. android:gravity="bottom" keeps the
    // newest line visible only while the log is shorter than the box: TextView clamps its
    // vertical gravity offset to zero once the text is taller than the view, so past that point
    // the box pins to the top and stops following, which is why long transfers appear to stall.
    // scrollbars="vertical" only draws the scrollbar; it doesn't move the view. So scroll it
    // ourselves after every line, as the desktop and Apple apps already do.
    private fun scrollOutputToBottom() {
        // post() because append() invalidates the layout — the new line's position isn't
        // measurable until the next pass.
        outputBox.post {
            val layout = outputBox.layout ?: return@post
            if (layout.lineCount == 0) return@post
            val visibleHeight =
                outputBox.height - outputBox.compoundPaddingTop - outputBox.compoundPaddingBottom
            val overflow = layout.getLineBottom(layout.lineCount - 1) - visibleHeight
            // Below the overflow point this is a no-op scroll to 0, leaving gravity="bottom"
            // to hold the text against the bottom edge as before.
            outputBox.scrollTo(0, overflow.coerceAtLeast(0))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // NB: the output log is deliberately not saved here — it lives in the ViewModel so it
        // can't overflow the Binder transaction buffer. See MainViewModel.outputSnapshot().
        val modeGroup = findViewById<MaterialButtonToggleGroup>(id.modeGroup)
        val modeIndex = when (modeGroup.checkedButtonId) {
            id.sendButton -> 1
            id.receiveButton -> 2
            else -> 0
        }
        outState.putInt("mode", modeIndex)
        val peerGroup = findViewById<MaterialButtonToggleGroup>(id.peerGroup)
        val peerIndex = when (peerGroup.checkedButtonId) {
            id.androidButton -> 1
            id.iosButton -> 2
            id.linuxButton -> 3
            id.macButton -> 4
            id.windowsButton -> 5
            else -> 0
        }
        outState.putInt("peer", peerIndex)
        val transferRunning = !findViewById<Button>(id.startButton).isVisible
        outState.putBoolean("transferRunning", transferRunning)
        val progressBarValue = findViewById<ProgressBar>(id.progressBar).progress
        outState.putInt("progress", progressBarValue)
        outState.putBoolean("bluetoothEnabled", bluetoothSwitch.isEnabled)
        outState.putBoolean("sharedNetwork", viewModel.connectionMode == ConnectionMode.SharedNetwork)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val modeGroup = findViewById<MaterialButtonToggleGroup>(id.modeGroup)
        when (savedInstanceState.getInt("mode")) {
            1 -> modeGroup.check(id.sendButton)
            2 -> modeGroup.check(id.receiveButton)
        }
        val peerGroup = findViewById<MaterialButtonToggleGroup>(id.peerGroup)
        when (savedInstanceState.getInt("peer")) {
            1 -> peerGroup.check(id.androidButton)
            2 -> peerGroup.check(id.iosButton)
            3 -> peerGroup.check(id.linuxButton)
            4 -> peerGroup.check(id.macButton)
            5 -> peerGroup.check(id.windowsButton)
        }
        val transferRunning = savedInstanceState.getBoolean("transferRunning")
        toggleUI(!transferRunning)
        if (transferRunning) {
            viewModel.qrBitmap?.let {
                findViewById<ImageView>(id.qrCodeView).apply {
                    colorFilter = null   // restored view is showing a QR code; keep it untinted
                    setImageBitmap(it)
                }
            }
        }
        findViewById<ProgressBar>(id.progressBar).progress = savedInstanceState.getInt("progress")
        setBluetoothSwitchEnabled(savedInstanceState.getBoolean("bluetoothEnabled"))
        // restore connection mode last: checking the button fires the listener, which
        // reapplies peer group visibility and the bluetooth switch state
        connectionGroup.check(
            if (savedInstanceState.getBoolean("sharedNetwork")) {
                id.sharedNetworkButton
            } else {
                id.hotspotButton
            }
        )
    }

    // bluetooth

    // What we ask for at startup. The Bluetooth half is settled from Android 12; the WiFi half is
    // what moves. NEARBY_WIFI_DEVICES took over from ACCESS_FINE_LOCATION as the permission
    // startLocalOnlyHotspot() wants in Android 13, and asking for it here rather than leaving
    // startHotspot() to request it means the prompt lands with the others at startup instead of
    // interrupting a transfer that is already half-negotiated over Bluetooth. Location is not in
    // the 33+ list at all -- BLUETOOTH_SCAN carries neverForLocation, so scanning no longer needs
    // it, and the manifest stops declaring it above API 32.
    private var permissions = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
            // NEARBY_WIFI_DEVICES does not exist below 33, so the hotspot still needs this one
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
        )
        else -> arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.BLUETOOTH,
        )
    }

    // Logcat, not outputText: a raw permission constant is developer information, and this
    // runs on every onResume as well as at startup, so on a first launch the transfer output
    // opened with several lines of android.permission.* before the user had done anything.
    // The user-facing half of this is the denial message in the request callback, which says
    // what to do about it.
    private fun checkForBluetoothPermissions(): Boolean {
        for (permission in permissions) {
            if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                Log.i("Flying Carpet", "Missing permission: $permission")
                return false
            }
        }
        Log.i("Flying Carpet", "All permissions granted")
        return true
    }

    private fun bluetoothOnCreate() {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        viewModel.bluetooth.bluetoothManager = bluetoothManager

        bluetoothRequestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results: Map<String, Boolean> ->
            // An empty map means the request never reached the user: Android drops a
            // permission request raised while another is already in flight, and dispatches
            // the empty result synchronously, inside the launch() call. The loop below then
            // never runs, allPermissionsGranted stays true, and initializeBluetooth() is
            // re-entered — which finds the permissions still missing and launches again,
            // recursing until the stack overflows. Cheap to hit: any second launch() during
            // onCreate was enough. Bail instead; the switch stays tappable to retry (#101).
            if (results.isEmpty()) {
                Log.e("Bluetooth", "Permission request returned no results; not retrying")
                return@registerForActivityResult
            }
            var allPermissionsGranted = true
            for (result in results) {
                // one logcat line per permission rather than four constants in the output box;
                // the single summary below is what the user needs
                Log.i("Flying Carpet", "Have permission ${result.key}: ${result.value}")
                // Only the Bluetooth permissions gate Bluetooth. ACCESS_LOCAL_NETWORK is in
                // the same request but denying it must not disable BLE — it only affects
                // whether a transfer can reach the peer, which the start button reports.
                if (!result.value && result.key in permissions) {
                    allPermissionsGranted = false
                }
            }
            if (allPermissionsGranted) {
                viewModel.outputText("Bluetooth permissions granted")
                initializeBluetooth()
            } else {
                // leave the switch enabled: tapping it re-requests permissions (#101)
                viewModel.outputText("Bluetooth permissions denied. Tap the Bluetooth switch to grant them (or grant them in system Settings), or continue without Bluetooth.")
                Log.e("Bluetooth", "To use Flying Carpet, either grant Bluetooth permissions to the app, or turn off the Use Bluetooth switch.")
                setBluetoothSwitchChecked(false)
            }
        }

        bluetoothIcon = findViewById(id.bluetoothIcon)
        // the idle tint comes from the fork's Appearance layer rather than a hardcoded black:
        // the icon's resting color must follow the theme, or it goes invisible against the
        // dark background from the end of the first transfer (when status goes back to false).
        viewModel.bluetooth.status.observe(this) {
            bluetoothIcon.drawable.setTint(Appearance.bluetoothIconColor(this, it))
        }
        bluetoothSwitch = findViewById(id.bluetoothSwitch)
        bluetoothSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !bluetoothAvailable) {
                // user is turning Bluetooth on after a permission denial: retry
                // initialization, which re-requests permissions if still missing (#101).
                // uncheck first; initializeBluetooth() checks it on success.
                setBluetoothSwitchChecked(false)
                initializeBluetooth()
                return@setOnCheckedChangeListener
            }
            bluetoothIcon.isVisible = isChecked
            // shared network mode never asks for the peer OS, Bluetooth or not: discovery finds it
            val needPeer = !isChecked && viewModel.connectionMode == ConnectionMode.Hotspot
            peerGroup.isVisible = needPeer
            peerInstruction.isVisible = needPeer
            viewModel.bluetooth.active = isChecked
            updateBluetoothHint()
            // remember it across restarts -- but only when this is the user's doing, not the app
            // reasserting the UI (an unavailable radio, a failed callback)
            if (settingBluetoothSwitch) return@setOnCheckedChangeListener
            settings.setText(USE_BLUETOOTH_KEY, if (isChecked) "1" else "0")
        }

        // Register for bluetooth bonding events exactly once, and against the application rather
        // than this Activity: the receiver belongs to the ViewModel and outlives us, so a second
        // registration from a recreated Activity would deliver every bond broadcast twice.
        if (!viewModel.bluetooth.receiverRegistered) {
            val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            applicationContext.registerReceiver(viewModel.bluetooth.bluetoothReceiver, filter)
            viewModel.bluetooth.receiverRegistered = true
        }

        if (initializeBluetooth()) {
            viewModel.outputText("Bluetooth initialized")
        } else if (bluetoothPermissionsMissing) {
            // a permission request is in flight; its callback re-runs
            // initializeBluetooth() if granted. keep the switch enabled so the user
            // can re-trigger the request by tapping it after a denial (#101).
        } else {
            // Bluetooth merely being switched off arrives here by the same route as a device
            // with no BLE support — openGattServer() returns null either way — and "can't use"
            // reads as a hardware verdict, so name which one it is.
            val adapter = viewModel.bluetooth.bluetoothManager.adapter
            if (adapter != null && !adapter.isEnabled) {
                viewModel.outputText("Bluetooth is turned off. Turn it on and restart Flying Carpet to use it.")
            } else {
                viewModel.outputText("Device can't use Bluetooth")
            }
            setBluetoothSwitchChecked(false)
            setBluetoothSwitchEnabled(false)
        }
    }

    private fun initializeBluetooth(): Boolean {
        if (!checkForBluetoothPermissions()) {
            Log.e("Bluetooth", "Missing permissions")
            bluetoothPermissionsMissing = true
            bluetoothAvailable = false
            viewModel.bluetooth.active = false
            setBluetoothSwitchChecked(false)
            applyConnectionModeUi()
            bluetoothRequestPermissionLauncher.launch(permissionsToRequest())
            return false
        }
        bluetoothPermissionsMissing = false
        var initialized = false
        try {
            val initializedPeripheral = viewModel.bluetooth.initializePeripheral(this)
            val initializedCentral = viewModel.bluetooth.initializeCentral()
            if (!initializedPeripheral) {
                Log.e("Bluetooth", "Device cannot act as a Bluetooth peripheral")
            } else if (!initializedCentral) {
                Log.e("Bluetooth", "Device cannot act as a Bluetooth central")
            } else {
                initialized = true
            }
        } catch (e: Exception) {
            Log.e("Bluetooth", "Could not initialize Bluetooth: $e")
        }
        bluetoothAvailable = initialized
        // Not plain `isChecked = initialized`: an available radio comes up in whatever state it was
        // last left in (on for a first run), and this write must not be recorded as the user's own.
        setBluetoothSwitchChecked(initialized && useBluetoothRemembered())
        viewModel.bluetooth.active = bluetoothSwitch.isChecked
        applyConnectionModeUi()
        return initialized
    }

    // disable Bluetooth if a callback fails. every caller is a BLE callback, which the OS
    // delivers on a binder thread, so hop to the UI thread before touching these views —
    // unchecking the switch fires its listener, which changes the peer group's visibility and
    // would hit ViewRootImpl's thread check.
    private fun enableBluetoothUi(enabled: Boolean) {
        runOnUiThread {
            setBluetoothSwitchChecked(enabled)
            setBluetoothSwitchEnabled(enabled)
            bluetoothIcon.isVisible = enabled
        }
    }
}

// TODO:
//   share sheet
//   open folder button after receiving
//   need to not start peripheral when receiving, or central when sending? other how to check if we can initialize?
//   one permission check for all permissions?
//   transfer "completing" if receiving end quit?
//   test what happens if wifi is turned off - done. hotspot still runs, not sure about joining.
//   don't show progress bar till transfer starts?

// https://developers.google.com/ml-kit/code-scanner
