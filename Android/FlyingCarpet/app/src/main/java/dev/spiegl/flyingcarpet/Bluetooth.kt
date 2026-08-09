package dev.spiegl.flyingcarpet

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.BOND_BONDED
import android.bluetooth.BluetoothDevice.BOND_BONDING
import android.bluetooth.BluetoothDevice.BOND_NONE
import android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE
import android.bluetooth.BluetoothDevice.EXTRA_DEVICE
import android.bluetooth.BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.UUID

// how many UTF-8 bytes of the adapter name still fit in a 31-byte advertisement alongside
// the flags (3 bytes) and our 128-bit service UUID (18 bytes), minus the name AD header (2 bytes)
const val MAX_ADVERTISED_NAME_BYTES = 8

// How many times to ask the stack again after it refuses to open the GATT connection, and how long
// to wait first. Status 133 is Android's catch-all GATT error and it lands on a first connect often
// enough that a single attempt is not a fair test of whether the peer is there -- the stack
// generally accepts the next one. A pause helps: the refusal tends to come from connecting in the
// same breath as stopping the scan, before the controller is finished with the radio.
const val MAX_CONNECT_RETRIES = 3
const val CONNECT_RETRY_DELAY_MS = 800L

// Whether to drop the pairing with the peer after every transfer.
//
// OFF since the v10 rebase, and it must stay off unless the desktop side changes with it. This was
// turned on in the fork against v9, where Linux cleared its half of the bond too, and the rule was
// that the two sides had to stay in step. Upstream v10 settled the question the other way: neither
// side removes a bond on cleanup, because a one-sided removal leaves the peer holding keys we have
// forgotten and its next connection tries to encrypt with an LTK that is gone (see the bond section
// of core/src/linux/bluetooth.rs and docs/bluetooth-field-guide.md, law 4). Linux now solves the
// discovery half of the problem -- a bonded peer that BlueZ no longer announces -- by verifying the
// service over a connection instead of trusting the cached UUID list, so clearing the bond is no
// longer what makes the peer findable. Leaving this on would recreate exactly the asymmetry v10
// removed, and it costs two dialogs per transfer besides.
const val CLEAR_BOND_AFTER_TRANSFER = false

val SERVICE_UUID: UUID = UUID.fromString("A70BF3CA-F708-4314-8A0E-5E37C259BE5C")
val OS_CHARACTERISTIC_UUID: UUID = UUID.fromString("BEE14848-CC55-4FDE-8E9D-2E0F9EC45946")
val SSID_CHARACTERISTIC_UUID: UUID = UUID.fromString("0D820768-A329-4ED4-8F53-BDF364EDAC75")
val PASSWORD_CHARACTERISTIC_UUID: UUID = UUID.fromString("E1FA8F66-CF88-4572-9527-D5125A2E0762")

// How many times to re-ask for a characteristic that comes back "not encrypted" while a bond
// already exists, before calling the bond stale and saying so.
private const val MAX_ENCRYPTED_READ_RETRIES = 3

// How many times to re-ask for a characteristic whose answer never arrived at all.
private const val MAX_LOST_READ_RETRIES = 3
const val NO_SSID = "NONE"

interface BluetoothDelegate {
    // Start (or replace) the ticking "still waiting" line, and stop it once the peer moves.
    fun bleWait(what: String)
    fun bleWaitDone()
    // Detail for the transcript file and logcat, never for the screen: the scan can hear dozens of
    // devices and the on-screen log is a bare TextView that a flood pushes straight out of view.
    fun logDetail(msg: String)
    // Shared network mode carries the password over BLE and nothing else -- there is no hotspot
    // to stand up or join at the end of the exchange. The GATT callbacks narrate what happens
    // next, so they have to know which of the two it is.
    fun usingSharedNetwork(): Boolean
    fun gotPeer(peerOS: String)
    fun gotSsid(ssid: String)
    fun gotPassword(password: String)
    fun connectToPeer()
    fun getWifiInfo(): Pair<String, String>
    fun outputText(msg: String)
    fun bluetoothFailed()
    // Reset the transfer without taking Bluetooth away. bluetoothFailed() switches the radio off in
    // the UI, which is right when Bluetooth itself will not work, and wrong when one connection
    // attempt simply did not land -- there we want the app idle and ready to be asked again.
    fun cleanUpTransfer()
}

class Bluetooth(val application: Application, private val delegate: BluetoothDelegate): BluetoothDelegate by delegate {

    lateinit var bluetoothManager: BluetoothManager
    lateinit var bluetoothGattServer: BluetoothGattServer
    lateinit var service: BluetoothGattService
    lateinit var bluetoothLeScanner: BluetoothLeScanner
    var bluetoothReceiver = BluetoothReceiver(application, null, delegate)
    var active = false

    // keeping these values here to stream wifiInfo over bluetooth since max packet size is 20
    // var wifiInfo = byteArrayOf()
    // var cursor = 0

    private var _status = MutableLiveData<Boolean>()
    val status: LiveData<Boolean>
        get() = _status

    // the three characteristics that belong to us -- a request for any of them proves the device
    // talking to our GATT server is the Flying Carpet peer and not some unrelated LE link
    private val ourCharacteristics = setOf(
        OS_CHARACTERISTIC_UUID, SSID_CHARACTERISTIC_UUID, PASSWORD_CHARACTERISTIC_UUID
    )
    private var advertising = false
    // Whether this transfer's role is the advertising one. The GATT server exists in both roles,
    // so the disconnect handling below has to know which of them we are in.
    private var weAreAdvertiser = false
    // True once WE connected out to the peer -- i.e. we are the GATT client. Both roles are offered
    // at the start of a transfer now (see MainActivity.beginTransferWithSelection), so which one we
    // end up in is decided by whoever makes contact first, not by send/receive.
    var weAreCentral = false
        private set

    // A line every few seconds while BLE is busy with something invisible -- advertising into an
    // empty room, scanning, waiting for the peer's next move. Each of those can run half a minute
    // with nothing to show for it, and a log that goes quiet for that long reads as a hang
    // (白い熊 2026-08-07). The desktop half ticks the same way (utils::with_progress).
    private val waitHandler = Handler(Looper.getMainLooper())
    private var waitRunnable: Runnable? = null

    fun startWaitTicker(what: String) {
        stopWaitTicker()
        val started = SystemClock.elapsedRealtime()
        val ticker = object : Runnable {
            override fun run() {
                val seconds = (SystemClock.elapsedRealtime() - started) / 1000
                val heard = if (heardDevices.isEmpty()) "" else ", ${heardDevices.size} devices heard"
                outputText("$what... (${seconds}s$heard)")
                waitHandler.postDelayed(this, 3000)
            }
        }
        waitRunnable = ticker
        waitHandler.postDelayed(ticker, 3000)
    }

    fun stopWaitTicker() {
        waitRunnable?.let { waitHandler.removeCallbacks(it) }
        waitRunnable = null
    }

    // The receiver object lives in the ViewModel and outlives the Activity, so registering it again
    // from a second bluetoothOnCreate() -- an Activity recreated for a share intent, say -- makes
    // every bond broadcast arrive once per registration. That is why the log paired up
    // "Pairing with the other device" and "Paired with the other device".
    var receiverRegistered = false

    // Whether bluetoothGattServer currently holds an open server. The field is lateinit and stays
    // set after close(), so it cannot answer this on its own.
    private var serverOpen = false

    @SuppressLint("MissingPermission")
    private fun closeServer() {
        if (!serverOpen) {
            return
        }
        serverOpen = false
        bluetoothGattServer.clearServices()
        bluetoothGattServer.close()
    }

    // A GATT server registration, a GATT client and a broadcast receiver all outlive the object
    // that made them, so a ViewModel that is thrown away without letting go leaves them registered
    // for the life of the process -- and a second live server is what let the peer read a copy of
    // our service we could no longer answer through.
    fun shutdown() {
        bluetoothReceiver.closeGatt()
        closeServer()
        if (receiverRegistered) {
            receiverRegistered = false
            try {
                application.unregisterReceiver(bluetoothReceiver)
            } catch (e: IllegalArgumentException) {
                Log.i("Bluetooth", "Bond receiver was not registered")
            }
        }
    }

    // The peer we talked to this transfer, so its bond can be dropped afterwards. Linux removes the
    // device (and therefore its keys) when a transfer ends; if we keep ours, the two sides disagree
    // and the next transfer's read of an ENCRYPTED_MITM characteristic dies with the link. A freshly
    // made bond works every time -- a reused one never did -- so both sides start clean each run.
    private var peerDevice: BluetoothDevice? = null

    @SuppressLint("MissingPermission")
    private fun clearBond() {
        val device = peerDevice ?: return
        peerDevice = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            && ActivityCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
        {
            return
        }
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            return
        }
        // removeBond() has no public API. If the platform blocks the reflective call we simply stay
        // bonded -- the transfer already finished, and the failure is visible in the log.
        try {
            // Tell the bond receiver this one is ours, so the BOND_NONE it produces is not
            // announced as a pairing failure.
            bluetoothReceiver.clearingBond = true
            val removed = device.javaClass.getMethod("removeBond").invoke(device) as? Boolean ?: false
            if (!removed) {
                bluetoothReceiver.clearingBond = false
            }
            outputText(if (removed) "Cleared pairing with peer" else "Could not clear pairing with peer")
        } catch (e: Exception) {
            bluetoothReceiver.clearingBond = false
            outputText("Could not clear pairing with peer: ${e.javaClass.simpleName}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startPairingWith(device: BluetoothDevice) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            && ActivityCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
        {
            return
        }
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            return
        }
        outputText("Pairing has to start on this side — asking to pair, accept it on both screens")
        if (!device.createBond()) {
            outputText("Could not start pairing with the other device")
        }
    }

    // stop advertising once the peer has actually engaged with our service. never call this from
    // onConnectionStateChange -- see the comment there.
    @SuppressLint("MissingPermission")
    private fun stopAdvertisingForPeer() {
        if (!advertising) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            && ActivityCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED)
        {
            return
        }
        advertising = false
        // null if Bluetooth was switched off between the connection and this call
        bluetoothManager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        stopWaitTicker()
        outputText("Peer found us, stopped advertising")
    }

    fun stop(application: Context) {
        // Per-transfer state, so clear it at the per-transfer teardown rather than only in
        // scan(): scan() runs for the central role alone, and a peripheral transfer following
        // one that had completed its exchange would inherit `true` and spend the whole transfer
        // ignoring genuine Bluetooth failures. Before the permission gate below because this is
        // just a flag — nothing here needs a permission we might not have.
        bluetoothReceiver.exchangeComplete = false
        bluetoothReceiver.encryptedReadRetries = 0
        // Same reasoning for `bonded`: it means "this transfer's post-bond connection has been
        // opened", and it was never cleared anywhere, so the first fresh pairing in an app
        // session disarmed the post-bond connectGatt — the connection that reliably completes
        // the exchange — for every later fresh pairing. And for `result`: a stale scan result
        // left here would let an unrelated bond event connect to the previous transfer's peer.
        bluetoothReceiver.bonded = false
        bluetoothReceiver.result = null
        // From here until the next scan()/advertise(), no transfer owns the BLE stack, and
        // any callback that still arrives — ours or the peer's teardown — must read as
        // noise, not as a failure that flips the Bluetooth switch off (see bluetoothFailed
        // in MainViewModel). Set before the closes below so their own callbacks are covered.
        bluetoothReceiver.tearingDown = true
        weAreAdvertiser = false
        stopWaitTicker()
        cancelUnfilteredFallback()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            && ActivityCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
        {
            return
        }
        _status.postValue(false)
        // central: disconnect AND close every client connection this transfer opened.
        // Nulling the reference alone left the underlying BluetoothGatt connected with its
        // callback registered, so it would reconnect and re-run the whole OS exchange after
        // the transfer had ended — and closing only `bluetoothGatt` (the most recently
        // connected client) left the *other* one of the deliberately coexisting pre-bond and
        // post-bond pair registered and holding the ACL. Confirmed 2026-07-25, iOS→Android:
        // the leftover pre-bond client received iOS's teardown Service Changed right after
        // this method had cleared exchangeComplete, re-discovered, found the service gone,
        // and turned the Bluetooth switch off via bluetoothFailed().
        // Stop on the stored scanner, not a freshly fetched one: stopScan() matches the callback
        // against the instance that started the scan. Guard on adapter state instead, because
        // unlike stopAdvertising() below, stopScan() throws IllegalStateException("BT Adapter is
        // not turned ON") when Bluetooth has been switched off since the scan started; the catch
        // covers it going off between the check and the call.
        if (this::bluetoothLeScanner.isInitialized && bluetoothManager.adapter?.isEnabled == true) {
            try {
                bluetoothLeScanner.stopScan(leScanCallback)
            } catch (e: IllegalStateException) {
                Log.i("Bluetooth", "Bluetooth turned off during teardown: $e")
            }
        }
        bluetoothReceiver.closeAllConnections()
        // peripheral. adapter and bluetoothLeAdvertiser are null when Bluetooth is off or
        // unsupported — a user flipping Bluetooth off mid-transfer must not crash teardown.
        if (this::bluetoothManager.isInitialized) {
            advertising = false
            bluetoothManager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        }
        // Close the GATT server, not just clearServices(): an open server stays connectable
        // between transfers, so a peer (e.g. iOS starting its next transfer before this
        // device does) can reconnect and drive the exchange again. Reopen a fresh server so
        // the next transfer is ready with no client connections carried over. initializePeripheral
        // closes the old server before opening the new one and re-adds the service.
        if (this::bluetoothGattServer.isInitialized) {
            initializePeripheral(application)
        }
        if (CLEAR_BOND_AFTER_TRANSFER) {
            clearBond()
        }
    }

    // peripheral

    fun initializePeripheral(application: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            && ActivityCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
        {
            return false
        }
        if (bluetoothManager.adapter == null) {
            return false
        }

        // close any server from a previous transfer (or a previous onResume) before opening
        // a new one, so servers and their attached client connections don't accumulate
        if (this::bluetoothGattServer.isInitialized) {
            bluetoothGattServer.close()
        }

        // open server, create service
        bluetoothGattServer = bluetoothManager.openGattServer(application, serverCallback) ?: return false
        serverOpen = true
        service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // add characteristics to service
        for (characteristicUuid in arrayOf(OS_CHARACTERISTIC_UUID, SSID_CHARACTERISTIC_UUID, PASSWORD_CHARACTERISTIC_UUID)) {
            val characteristic = BluetoothGattCharacteristic(
                characteristicUuid,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM or BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM,
            )
            service.addCharacteristic(characteristic)
        }

        // add service to server
        bluetoothGattServer.addService(service)
        return true
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            Log.i("Bluetooth", "In serverCallback")
            super.onConnectionStateChange(device, status, newState)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                outputText("A device connected over Bluetooth")
                peerDevice = device
                // Start the pairing from THIS side. Our characteristics need an encrypted,
                // MITM-protected link, so somebody has to bond -- and when the other device
                // initiates it, EMUI's stack cannot present the numeric comparison it is asked
                // for: `smp_send_app_cback: Unexpected event: 6` (SMP_NC_REQ_EVT), no dialog, and
                // the bond resets about four seconds later. Measured on 白い熊's phone twice,
                // 2026-08-08, at 12:37 and 21:27. Initiating here puts the phone in the role its
                // own stack handles properly: it shows its own pairing prompt, the other device's
                // agent shows the matching passkey, and both confirm.
                // Only while we are advertising for a transfer, so an unrelated LE connection
                // (a watch, earbuds) is never dragged into a pairing.
                if (weAreAdvertiser && !bluetoothReceiver.exchangeComplete
                    && !bluetoothReceiver.tearingDown && device != null) {
                    startPairingWith(device)
                }
                // deliberately NOT stopping the advertiser here. this callback fires for LE links
                // that have nothing to do with us -- on EMUI/Kirin a watch, earbuds or a system
                // service connecting is enough -- and stopping here took us silently off the air
                // moments after "Advertiser started", so the peer scanning right next to us never
                // found anything. we come off the air in stopAdvertisingForPeer() instead, once
                // something actually reads or writes one of OUR characteristics, which only the
                // real Flying Carpet peer does.
            } else if (weAreAdvertiser && !advertising
                && !bluetoothReceiver.exchangeComplete && !bluetoothReceiver.tearingDown) {
                // The peer went away before it had our credentials, so this is not the ordinary
                // hand-off to WiFi -- a pairing that did not complete, typically. We came off the
                // air at its first read or write (stopAdvertisingForPeer), and nothing put us back
                // on: its next scan then finds NOTHING, however long it looks, and both sides sit
                // there forever. Measured on 白い熊's phone 2026-08-08: bond failed at 12:37:38,
                // and four minutes later the phone still had a live GATT server, an open transfer
                // and an empty advertisement list while the PC rescanned.
                outputText("The other device disconnected before we finished — advertising again")
                advertise()
            } else {
                // Not a failure: the BLE link has done its job by this point and is released so
                // the transfer can move to WiFi. Worded so it does not read as an error.
                outputText("Bluetooth connection released")
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ActivityCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            {
                return
            }
            // device is nullable as of the SDK 37 stubs, and sendResponse requires it non-null
            if (device == null || characteristic == null) {
                return
            }
            if (characteristic.uuid in ourCharacteristics) {
                stopAdvertisingForPeer()
            }
            when (characteristic.uuid) {
                // tell peer we're android
                OS_CHARACTERISTIC_UUID -> {
                    outputText("Receiving device asked what we are, told it Android")
                    bluetoothGattServer.sendResponse(
                        device, requestId, BluetoothGatt.GATT_SUCCESS, 0, "android".toByteArray()
                    )
                }
                // if we've started wifi hotspot, this will send the details. if not, it will send a blank string and the peer will need to wait and try again
                SSID_CHARACTERISTIC_UUID -> {
                    val (ssid, _) = getWifiInfo()
                    outputText(
                        if (ssid.isEmpty()) "It asked for our network name before the hotspot was ready; it will ask again"
                        else "Gave it our network name ($ssid)"
                    )
                    bluetoothGattServer.sendResponse(
                        device, requestId, BluetoothGatt.GATT_SUCCESS, 0, ssid.toByteArray()
                    )
                }
                PASSWORD_CHARACTERISTIC_UUID -> {
                    val (_, password) = getWifiInfo()
                    stopWaitTicker()
                    outputText("Gave it our password — it should join the hotspot now")
                    bluetoothGattServer.sendResponse(
                        device, requestId, BluetoothGatt.GATT_SUCCESS, 0, password.toByteArray()
                    )
                }
                else -> {
                    outputText("Invalid characteristic")
                    bluetoothGattServer.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                        0,
                        null
                    )
                    return
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onCharacteristicWriteRequest(
                device,
                requestId,
                characteristic,
                preparedWrite,
                responseNeeded,
                offset,
                value
            )

            Log.i("Bluetooth", "Central peer wrote something: \"${value?.toString(Charsets.UTF_8)}\"")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ActivityCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            {
                return
            }
            if (device == null || characteristic == null) {
                return
            }
            if (characteristic.uuid in ourCharacteristics) {
                stopAdvertisingForPeer()
            }
            when (characteristic.uuid) {
                OS_CHARACTERISTIC_UUID -> {
                    // now we know peer's OS
                    // thought we had to figure out hosting and connect here, but that doesn't
                    // happen till central writes wifi info
                    value?.let {
                        val os = it.toString(Charsets.UTF_8)
                        // This is the long quiet stretch on the sending side: the receiver goes off
                        // to tear down its WiFi and bring a hotspot up, which takes the better part
                        // of twenty seconds, and only then writes us the details. Say so, rather
                        // than leaving the log looking stalled. In shared network mode there is no
                        // hotspot to wait on -- only the password, which arrives right away.
                        outputText("The other device is $os")
                        if (usingSharedNetwork()) {
                            outputText("Waiting for it to send us the password...")
                            startWaitTicker("Waiting for the password over Bluetooth")
                        } else {
                            outputText("It is setting up its hotspot now — that takes a few seconds, since it has to drop its own WiFi first")
                            outputText("Waiting for it to send us the network name and password...")
                            startWaitTicker("Waiting for the other device's hotspot details")
                        }
                        gotPeer(os)
                    }
                }
                SSID_CHARACTERISTIC_UUID -> {
                    // central has written ssid to us as peripheral. if they wrote an ssid, we need to store it.
                    // if they didn't, we don't need to do anything, and just wait for them to write the password,
                    // at which point we can calculate the ssid and key.
                    if (value != null) {
                        val theirSsid = value.toString(Charsets.UTF_8)
                        outputText(
                            if (usingSharedNetwork()) "Waiting for the password"
                            else "Its hotspot will be $theirSsid — waiting for the password"
                        )
                        startWaitTicker("Waiting for the password over Bluetooth")
                        gotSsid(theirSsid)
                    }
                }
                PASSWORD_CHARACTERISTIC_UUID -> {
                    if (value != null) {
                        stopWaitTicker()
                        outputText(
                            if (usingSharedNetwork()) "Got the password over Bluetooth"
                            else "Got the password — joining its hotspot next"
                        )
                        gotPassword(value.toString(Charsets.UTF_8))
                    }
                }
                else -> {
                    outputText("Invalid characteristic")
                    bluetoothGattServer.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                        0,
                        null
                    )
                    return
                }
            }
            bluetoothGattServer.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                0,
                null
            )
        }
    }

    fun advertise() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            && ActivityCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED)
        {
            return
        }
        // new transfer (peripheral role): re-arm bluetoothFailed(), which stop() disarms —
        // the peripheral never calls scan(), so it must clear the flag here
        bluetoothReceiver.tearingDown = false
        weAreAdvertiser = true
        weAreCentral = false
        // BluetoothLeAdvertiser. null when Bluetooth is off: report and fail the transfer
        // rather than crash — this used to be an unguarded platform-type dereference.
        val bluetoothLeAdvertiser = bluetoothManager.adapter?.bluetoothLeAdvertiser
        if (bluetoothLeAdvertiser == null) {
            outputText("Bluetooth advertiser unavailable. Is Bluetooth turned on?")
            active = false
            bluetoothFailed()
            return
        }
        val settingsBuilder = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            settingsBuilder.setDiscoverable(true)
        }
        val settings = settingsBuilder.build()

        // an advertisement is 31 bytes: 3 for flags, 18 for our 128-bit service UUID, leaving 10,
        // of which the name AD structure spends 2 on its header. so the name fits in 8 bytes -- and
        // the packet counts UTF-8 BYTES, not characters: a 3-character name like "白い熊" is 9 bytes
        // and overflows. (String.length counts UTF-16 units, so it says 3 and lets the packet through,
        // after which the controller rejects it -- EMUI surfaces that as advertise error 18, HCI 0x12
        // "Invalid HCI Command Parameters".)
        // (the adapter itself can vanish if Bluetooth is switched off mid-flight)
        val nameBytes = bluetoothManager.adapter?.name?.toByteArray(Charsets.UTF_8)?.size ?: 0
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(nameBytes in 1..MAX_ADVERTISED_NAME_BYTES)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        if (nameBytes > MAX_ADVERTISED_NAME_BYTES) {
            outputText("Bluetooth name is $nameBytes bytes, advertising without it")
        }
        bluetoothLeAdvertiser.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            super.onStartSuccess(settingsInEffect)
            _status.postValue(true)
            advertising = true
            outputText("Advertiser started")
            outputText("Nothing happens here until the other device starts its transfer and finds us.")
            startWaitTicker("Advertising over Bluetooth, waiting for the other device to connect")
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            outputText("Advertiser failed to start: ${advertiseErrorName(errorCode)}")
            outputText("Bluetooth turned off. Select the other device's OS, then pick your files again.")
            advertising = false
            active = false
            bluetoothFailed()
        }
    }

    // android documents exactly five failure codes; anything else is the vendor stack's own status
    // leaking through the framework wrapper, so print the number rather than pretending to know it
    private fun advertiseErrorName(code: Int) = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "advertisement too large ($code)"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers ($code)"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "already started ($code)"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "internal error ($code)"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "not supported by this device ($code)"
        else -> "vendor error $code"
    }

    private fun scanErrorName(code: Int) = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "already started ($code)"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "registration failed ($code)"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "internal error ($code)"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "not supported by this device ($code)"
        else -> "vendor error $code"
    }

    // central

    fun initializeCentral(): Boolean {
        // adapter is null when Bluetooth is unsupported; check it before dereferencing
        // rather than after (the old order NPE'd on the adapter access itself)
        val scanner = bluetoothManager.adapter?.bluetoothLeScanner ?: return false
        bluetoothLeScanner = scanner
        return true
    }

    // The master toggle, not the permission: the app can hold ACCESS_FINE_LOCATION and still
    // get nothing back from a scan while location is switched off system-wide.
    private fun locationEnabled(): Boolean {
        val locationManager =
            application.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        return locationManager?.isLocationEnabled ?: true
    }

    fun scan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            && ActivityCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
        {
            outputText("Missing permission BLUETOOTH_SCAN")
            return
        }
        // Before API 31 a scan needs the system location toggle on, not just the location
        // permission, and with it off startScan() reports success and delivers nothing: no
        // results, no onScanFailed, so the peer is simply never found. API 31+ is exempt via
        // neverForLocation on BLUETOOTH_SCAN, and telling those users to switch location on
        // would be asking for something the app doesn't need.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !locationEnabled()) {
            outputText(
                "Location is turned off. This version of Android requires it to find Bluetooth " +
                    "devices. Turn it on, or turn Bluetooth off here and use the QR code or " +
                    "password instead."
            )
        }
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        // setLegacy(false) means "report extended advertisements as well as legacy ones", not
        // "use extended". The default is legacy-only, for compatibility with old apps -- and a
        // peer whose stack chose an extended advertising set is then completely invisible, which
        // is indistinguishable from a peer that is not there (白い熊, 2026-08-08: the desktop
        // reported itself advertising while the phone, scanning with no filter at all, collected
        // 23 other devices and never saw it). Asking for both costs nothing.
        // LOW_LATENCY, not the default LOW_POWER. The default listens for 512 ms every 5120 ms,
        // and BlueZ's default advertising interval is 1280 ms -- exactly a quarter of that scan
        // period. Harmonically related periods never drift apart, so an advertisement that first
        // lands in the gap between two scan windows stays in that gap for ever: the phone hears
        // beacons three rooms away and not the computer on the same desk, run after run, until
        // something happens to shift the phase (白い熊, 2026-08-08 -- the "it worked perfectly and
        // now it never works" of this whole evening). A continuous scan cannot be dodged, and the
        // battery cost lasts only as long as the transfer is being set up.
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setLegacy(false)
                }
            }
            .build()
        // new transfer: allow the credential exchange (and its retry) to run again, let
        // this transfer's pairing (if one happens) open its own post-bond connection, and
        // re-arm bluetoothFailed() — this transfer's BLE events are real again
        bluetoothReceiver.exchangeComplete = false
        bluetoothReceiver.bonded = false
        bluetoothReceiver.encryptedReadRetries = 0
        bluetoothReceiver.tearingDown = false
        heardDevices.clear()
        bluetoothLeScanner.startScan(listOf(scanFilter), scanSettings, leScanCallback)
        _status.postValue(true)
        outputText("Scanning for Bluetooth peripherals...")
        outputText("The other device has to be advertising -- start the transfer there too.")
        startWaitTicker("Looking for the other device over Bluetooth")
        scheduleUnfilteredFallback(scanSettings)
        scheduleRescan(scanSettings)
    }

    // Android stops delivering results on a long-running scan without saying so -- no callback, no
    // error, the scan simply goes quiet -- so a scan that has been running for a minute is not
    // evidence that the peer is absent. Restarting it costs nothing and puts us back on the air's
    // receiving end. Well inside the framework's limit of five scan starts per thirty seconds.
    private var rescanRunnable: Runnable? = null

    @SuppressLint("MissingPermission")
    private fun scheduleRescan(scanSettings: ScanSettings) {
        cancelRescan()
        val rescan = object : Runnable {
            override fun run() {
                if (!bluetoothReceiver.waitingForConnection) {
                    return
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && ActivityCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                {
                    return
                }
                logDetail("Restarting the Bluetooth scan (long scans go quiet without saying so)")
                try {
                    bluetoothLeScanner.stopScan(leScanCallback)
                    bluetoothLeScanner.startScan(null, scanSettings, leScanCallback)
                } catch (e: Exception) {
                    Log.e("Bluetooth", "Could not restart the scan: $e")
                }
                waitHandler.postDelayed(this, 45000)
            }
        }
        rescanRunnable = rescan
        waitHandler.postDelayed(rescan, 45000)
    }

    private fun cancelRescan() {
        rescanRunnable?.let { waitHandler.removeCallbacks(it) }
        rescanRunnable = null
    }

    // A filtered scan asks the framework to match our service UUID for us, and it is the fast,
    // cheap way to find the peer -- when the UUID is where it expects it. It is not always: an
    // advertisement is 31 bytes, and a peer that overfills it can end up with the UUID in the scan
    // response or missing altogether, at which point a filtered scan returns *nothing*, for ever,
    // with everything looking healthy (白い熊, 2026-08-08: three minutes, zero results, the desktop
    // advertising the whole time). So if the filter has found nothing after fifteen seconds, drop
    // it and look at every advertisement ourselves -- onScanResult checks the UUID either way.
    private var fallbackRunnable: Runnable? = null

    // Addresses already noted in this transfer's scan, so the log records each device once.
    private val heardDevices = mutableSetOf<String>()

    @SuppressLint("MissingPermission")
    private fun scheduleUnfilteredFallback(scanSettings: ScanSettings) {
        cancelUnfilteredFallback()
        val runnable = Runnable {
            if (!bluetoothReceiver.waitingForConnection) {
                return@Runnable
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ActivityCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
            {
                return@Runnable
            }
            outputText(
                "Nothing matched the filtered scan; looking at every Bluetooth device instead "
                        + "(${heardDevices.size} heard so far)"
            )
            try {
                bluetoothLeScanner.stopScan(leScanCallback)
                bluetoothLeScanner.startScan(null, scanSettings, leScanCallback)
            } catch (e: Exception) {
                Log.e("Bluetooth", "Could not restart the scan without a filter: $e")
            }
        }
        fallbackRunnable = runnable
        waitHandler.postDelayed(runnable, 15000)
    }

    private fun cancelUnfilteredFallback() {
        fallbackRunnable?.let { waitHandler.removeCallbacks(it) }
        fallbackRunnable = null
        cancelRescan()
    }

    private val leScanCallback = object : ScanCallback() {
        // this is called when we've scanned for a peripheral and found it. this calls createBond(),
        // and once the bonding process is complete, Android will send us the ACTION_BOND_STATE_CHANGED
        // event and we'll resume in BluetoothReceiver, which will discover services, then characteristics,
        // and store those in itself.
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ActivityCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
            {
                outputText("Missing permission BLUETOOTH_SCAN")
                return
            }
            if (result != null) {
                // With the fallback scan there is no filter doing this for us, and even with one
                // this is free: the peer is the device advertising our service, nothing else.
                val advertisesOurService =
                    result.scanRecord?.serviceUuids?.contains(ParcelUuid(SERVICE_UUID)) == true
                if (!advertisesOurService) {
                    // Every device heard, once, in the transcript: the difference between "the
                    // other device is not advertising" and "we cannot hear it" is otherwise
                    // invisible from this side, and it is the difference that matters.
                    val address = result.device?.address ?: return
                    if (heardDevices.add(address)) {
                        val uuids = result.scanRecord?.serviceUuids
                        logDetail(
                            "Heard $address (${result.scanRecord?.deviceName ?: "no name"}), " +
                                    "rssi ${result.rssi}, uuids ${uuids ?: "none"}"
                        )
                    }
                    return
                }
                if (bluetoothReceiver.waitingForConnection) {
                    cancelUnfilteredFallback()
                    outputText("Found device: ${result.device}")
                    bluetoothReceiver.waitingForConnection = false
                    bluetoothLeScanner.stopScan(this)
                    outputText("Stopped scanning")
                    // We found them, so we connect: that makes us the central, and we come off the
                    // air so they do not connect to us at the same time.
                    weAreCentral = true
                    if (advertising) {
                        advertising = false
                        bluetoothManager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
                        weAreAdvertiser = false
                        outputText("Taking the connecting role; stopped advertising")
                    }
                    // the next silent stretch: pairing (if this is a first meeting), connecting,
                    // and resolving the peer's GATT database, all of it several seconds at best
                    startWaitTicker("Connecting to the other device over Bluetooth")
                    //                address = result.device.address
                    bluetoothReceiver.result = result
                    peerDevice = result.device

//                    if (result.device.bondState == BOND_BONDED) {
                    bluetoothReceiver.trackConnection(result.device.connectGatt(
                        application.applicationContext,
                        false,
                        bluetoothReceiver.gattCallback,
                        BluetoothDevice.TRANSPORT_LE,
                    ))
                    Log.i("Bluetooth", "Called connectGatt()")
//                    } else {
//                        result.device.createBond()
//                        outputText("Called createBond()")
//                    }
                } else {
//                    outputText("Connected but not waiting for connection")
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("Bluetooth", "Scan failed: $errorCode")
            super.onScanFailed(errorCode)
            outputText("Bluetooth scan failed: ${scanErrorName(errorCode)}")
            outputText("Bluetooth turned off. Select the other device's OS, then pick your folder again.")
            active = false
            bluetoothFailed()
        }
    }

    // this class receives the bluetooth bonded events
    // TODO: rename?
    class BluetoothReceiver(
        private val application: Application,
        var result: ScanResult?,
        private val delegate: BluetoothDelegate,
    ): BroadcastReceiver(), BluetoothDelegate by delegate {

        private var peerDevice: BluetoothDevice? = null
        var bluetoothGatt: BluetoothGatt? = null
        var osCharacteristic: BluetoothGattCharacteristic? = null
        var ssidCharacteristic: BluetoothGattCharacteristic? = null
        var passwordCharacteristic: BluetoothGattCharacteristic? = null
        var waitingForConnection = false
        // "This transfer's post-bond connection has been opened." Cleared in scan() and
        // stop(), like exchangeComplete: left latched, the first fresh pairing in an app
        // session would suppress the post-bond connectGatt for every later fresh pairing.
        var bonded = false
        // Set once the credential exchange has actually completed. Gates the post-bond
        // connection's replay: the replay must stay available as a retry until the exchange
        // succeeds once, then be suppressed so a reconnect doesn't re-run read-OS → write-OS →
        // connectToPeer against a transfer already in progress. Set only *after* success (not on
        // connect, which was the exchangeStarted mistake), so it can only ever remove a redundant
        // replay, never a needed retry. Also gates bluetoothFailed(), since the peer's teardown
        // arrives after this point and is indistinguishable from a failure.
        //
        // It must be set for **every role that reaches those guards**, which is the mistake worth
        // remembering: it was originally set only in connectToPeer()'s isHosting() branch, so a
        // *joining* device — Android receiving from Linux or Windows, an entirely ordinary
        // configuration — ran with all of them disarmed. Two writers now cover the four role
        // axes: connectToPeer() for the host, gotPassword() for either kind of joiner.
        // Cleared in scan() and, for roles that never scan, in stop().
        var exchangeComplete = false

        // How many times a read has come back "not encrypted" against a bond that already exists.
        var encryptedReadRetries = 0

        // True from stop() until the next scan() or advertise() — i.e. whenever no transfer
        // owns the BLE stack. Gates bluetoothFailed() (MainViewModel) the same way
        // exchangeComplete does, but for the window *between* transfers, which
        // exchangeComplete can't cover because stop() clears it: between transfers a late
        // BLE callback is a log line, never a reason to flip the Bluetooth switch off.
        // Starts true because no transfer is active until one starts.
        var tearingDown = true

        // Every GATT client this transfer opened, in the order opened. The pre-bond and
        // post-bond connections deliberately coexist (see onConnectionStateChange), and
        // `bluetoothGatt` only tracks the most recently connected one — so a teardown that
        // closes only `bluetoothGatt` strands the other client, still registered and still
        // holding the ACL between transfers (law 9 in docs/bluetooth-field-guide.md).
        private val openConnections = mutableListOf<BluetoothGatt>()

        fun trackConnection(gatt: BluetoothGatt?) {
            if (gatt != null) {
                synchronized(openConnections) { openConnections.add(gatt) }
            }
        }

        private fun untrackConnection(gatt: BluetoothGatt) {
            synchronized(openConnections) { openConnections.remove(gatt) }
        }

        // Close every client, not just the last-connected one — callbacks stop after
        // close(), which is what makes stop() actually final.
        @SuppressLint("MissingPermission")
        fun closeAllConnections() {
            val toClose = synchronized(openConnections) {
                val copy = openConnections.toList()
                openConnections.clear()
                copy
            }
            for (gatt in toClose) {
                gatt.disconnect()
                gatt.close()
            }
            bluetoothGatt = null
        }

        // For delays that used to be Thread.sleep() on the GATT binder thread: sleeping
        // there stalls every other callback behind it (Apple's equivalent was converted to
        // asyncAfter for the same reason), so schedule instead.
        private val handler = Handler(Looper.getMainLooper())

        // True from the moment discoverServices() is accepted until onServicesDiscovered fires
        // for it. Two call sites start a discovery — onConnectionStateChange after its settle,
        // and onServiceChanged when the peer's database changes — and nothing stopped them
        // overlapping. A peripheral that registers its service right as we connect makes them
        // overlap every time: observed 2026-07-25 on Linux→Android, two discoveries completing
        // 13 ms apart, each calling read(OS), the second silently dropped by the busy GATT
        // queue. Both chains were identical so it didn't matter, but two concurrent walks of
        // read-OS → write-OS → connectToPeer is not a state this code reasons about.
        private var discoveryOutstanding = false

        // Single door for discoverServices(), so both call sites get the overlap guard and
        // neither can ignore the return value. discoverServices() reports "busy" the same way
        // readCharacteristic() does — a false return, no callback, nothing logged — and the
        // discovery is what produces the characteristics, so dropping one silently strands the
        // transfer with an empty log.
        private fun startDiscovery(gatt: BluetoothGatt, reason: String) {
            if (discoveryOutstanding) {
                Log.i("Bluetooth", "Discovery already outstanding; not starting another ($reason)")
                return
            }
            if (!gatt.discoverServices()) {
                outputText("Could not start Bluetooth service discovery ($reason).")
                return
            }
            discoveryOutstanding = true
        }

        // How many times we have re-asked the stack to connect for this transfer. Reset once a
        // connection is established, and again on cleanup, so each transfer gets the full budget.
        private var connectRetries = 0

        // The characteristic whose read is still outstanding. Our characteristics are
        // ENCRYPTED_MITM, so the first read of a fresh link is answered with "insufficient
        // authentication" while the stack goes off to bond; this remembers what to ask for again
        // once the bond lands.
        private var pendingRead: UUID? = null

        // The last bond state we put in the log, so a repeated transition is not announced twice.
        private var lastAnnouncedBondState = -1

        // Set while we drop the bond ourselves at the end of a transfer. The removal raises a
        // BOND_NONE like any other, and without this our own deliberate tidy-up was announced as
        // "Pairing did not complete" -- immediately after "Cleared pairing with peer" had said the
        // opposite, on a transfer that had just succeeded.
        var clearingBond = false

        val gattCallback = object : BluetoothGattCallback() {
            // this is called when we as central have read a characteristic from the peer's peripheral
            // Both callback shapes, one handler. The framework calls the four-argument form on modern
            // Android and the deprecated three-argument one elsewhere -- and a vendor stack that calls
            // the one we did not override drops the answer on the floor with nothing logged, which is
            // what a read that the other device demonstrably answered, and that never arrived here,
            // looks like (白い熊, 2026-08-08: the desktop served "linux" and the phone sat on
            // "Connecting..." for ever).
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                super.onCharacteristicRead(gatt, characteristic, value, status)
                handleCharacteristicRead(gatt, characteristic, value, status)
            }

            @Deprecated("Called by stacks that predate the value-carrying callback", ReplaceWith(""))
            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int
            ) {
                if (gatt == null || characteristic == null) return
                handleCharacteristicRead(gatt, characteristic, characteristic.value ?: ByteArray(0), status)
            }

            private fun handleCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    // Insufficient authentication/encryption is the *expected* answer to the
                    // first read of an ENCRYPTED_MITM characteristic: the stack starts bonding, and
                    // onReceive() asks again once it reports BOND_BONDED. Leave pendingRead set so
                    // it knows what to ask for, and do not treat this as a failure.
                    //
                    // Unless we are already bonded. Then no BOND_BONDED broadcast is ever coming,
                    // and waiting for one is waiting for ever -- which is what a stall that shows
                    // nothing but "Connecting to the other device..." for minutes on end actually
                    // is (白い熊, 2026-08-08). A refusal against a live bond means the two halves of
                    // that bond no longer match, so ask again a couple of times to give the stack a
                    // chance to re-encrypt, then say what has to be done about it.
                    if (status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION
                        || status == BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION) {
                        Log.i("Bluetooth", "Read of ${characteristic.uuid} needs pairing first")
                        val bonded = try {
                            gatt.device?.bondState == BluetoothDevice.BOND_BONDED
                        } catch (e: SecurityException) {
                            false
                        }
                        if (!bonded) {
                            outputText("Waiting until we are paired to read the other device's details")
                            return
                        }
                        if (encryptedReadRetries < MAX_ENCRYPTED_READ_RETRIES) {
                            encryptedReadRetries++
                            outputText(
                                "We are paired, but the link is not encrypted yet — asking again "
                                        + "($encryptedReadRetries of $MAX_ENCRYPTED_READ_RETRIES)"
                            )
                            handler.postDelayed({ read(characteristic.uuid) }, 1500)
                            return
                        }
                        outputText(
                            "The other device will not answer over this pairing. Remove this phone "
                                    + "from the other device's Bluetooth settings AND that device "
                                    + "from this phone's, then start the transfer again."
                        )
                        bluetoothFailed()
                        return
                    }
                    // without this, a failed read (e.g. after declined pairing) stalls the
                    // transfer, or propagates an empty value as the peer's OS/SSID/password
                    outputText("Failed to read Bluetooth characteristic (status $status).")
                    bluetoothFailed()
                    return
                }
                // the read landed, so nothing is waiting on the bond any more: leaving it set would
                // make a later BOND_BONDED re-issue a read that has already been answered
                pendingRead = null
                cancelReadWatchdog()
                val stringRepresentation = value.toString(Charsets.UTF_8)
                Log.i("Bluetooth", "Read characteristic: $stringRepresentation")
                when (characteristic.uuid) {
                    OS_CHARACTERISTIC_UUID -> {
                        bleWaitDone()
                        gotPeer(value.toString(Charsets.UTF_8))
                    }
                    SSID_CHARACTERISTIC_UUID -> {
                        val ssid = value.toString(Charsets.UTF_8)
                        if (ssid == "" || ssid == NO_SSID) {
                            // "" is an Android host whose hotspot isn't up yet; NO_SSID is a
                            // Windows host whose main thread hasn't generated credentials yet
                            // (our read can race it right after the OS exchange). Either way
                            // the credentials don't exist yet — wait a second and read again,
                            // which loops us back here. NO_SSID used to fall through as a
                            // final answer, which joined a hotspot derived from an empty
                            // password while the host waited forever for a real SSID read.
                            outputText("Could not read peer's WiFi characteristic. trying again...")
                            handler.postDelayed({ read(SSID_CHARACTERISTIC_UUID) }, 1000)
                            return
                        }
                        gotSsid(ssid)
                        bleWait("Waiting for the other device's password")
                        // doing this here instead of in gotSsid because if peripheral had SSID
                        // written to it, we wouldn't need to call read
                        // we read the SSID, now read the password.
                        read(PASSWORD_CHARACTERISTIC_UUID)
                    }
                    PASSWORD_CHARACTERISTIC_UUID -> {
                        bleWaitDone()
                        gotPassword(value.toString(Charsets.UTF_8))
                    }
                }
            }

            // this is called when we as central have written a characteristic to the peripheral
            override fun onCharacteristicWrite(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int
            ) {
                super.onCharacteristicWrite(gatt, characteristic, status)
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    outputText("Failed to write Bluetooth characteristic (status $status).")
                    bluetoothFailed()
                    return
                }
                when (characteristic?.uuid) {
                    OS_CHARACTERISTIC_UUID -> {
                        outputText("Wrote OS to peer")
                        connectToPeer()
                    }
                    SSID_CHARACTERISTIC_UUID -> {
                        outputText("Wrote SSID to peer")
                        val (_, password) = getWifiInfo()
                        // outputText("Fetched password = $password")
                        write(PASSWORD_CHARACTERISTIC_UUID, password.toByteArray())
                    }
                    PASSWORD_CHARACTERISTIC_UUID -> {
                        bleWaitDone()
                        outputText("Wrote password to peer")
                        // we told the peripheral the password, now just have to wait for them to join the hotspot
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                // before the permission gate: the discovery finished either way, and latching
                // this flag on would suppress every later re-discovery on this connection
                discoveryOutstanding = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && ActivityCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                {
                    return
                }
                super.onServicesDiscovered(gatt, status)
                // Every exit below used to `return` silently, leaving the transfer waiting
                // forever for a credential exchange that would never happen — three of them
                // without printing anything at all. Linux errors, Windows retries then
                // errors, and Apple cleans up; Android was the only platform that hung. See
                // docs/bluetooth-field-guide.md.
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    outputText("Bluetooth service discovery failed with status $status.")
                    bluetoothFailed()
                    return
                }
                if (gatt == null) {
                    outputText("Bluetooth service discovery returned no GATT client.")
                    bluetoothFailed()
                    return
                }
                // Post-exchange this is the peer's teardown being observed, not progress worth
                // reporting: BLE has nothing left to contribute and the user is watching for
                // the Wi-Fi join. Keep it in logcat, out of the transfer output.
                if (exchangeComplete || tearingDown) {
                    Log.i("Bluetooth", "Discovered ${gatt.services.size} services")
                } else {
                    outputText("Discovered ${gatt.services.size} services")
                }
                val service = gatt.getService(SERVICE_UUID)
                if (service == null) {
                    // Android caches the GATT database for bonded devices, and every peripheral
                    // removes its service at teardown, so a stale cache lands exactly here and
                    // the unpair advice is the right remedy.
                    //
                    // *After* the credential exchange it means the opposite: the peer removed
                    // its service on purpose, the transfer has already moved to Wi-Fi, and
                    // nothing is wrong. bluetoothFailed() has been gated on exchangeComplete for
                    // that case, but the gate is downstream — this message printed first and
                    // unconditionally, so the benign teardown still announced a failure, and it
                    // landed in the gap between "Joining <ssid>" and the hotspot association
                    // where the user has nothing else to look at. Reported as looking like a
                    // failed transfer that then succeeded anyway.
                    if (exchangeComplete || tearingDown) {
                        Log.i(
                            "Bluetooth",
                            "Peer removed its Flying Carpet service after the exchange; expected"
                        )
                    } else {
                        outputText(
                            "Did not find the Flying Carpet service on the peer. If the other " +
                            "device has started its transfer, try unpairing the two devices from " +
                            "each other and running the transfer again."
                        )
                    }
                    bluetoothFailed()
                    return
                }
                val os = service.getCharacteristic(OS_CHARACTERISTIC_UUID)
                val ssid = service.getCharacteristic(SSID_CHARACTERISTIC_UUID)
                val password = service.getCharacteristic(PASSWORD_CHARACTERISTIC_UUID)
                if (os == null || ssid == null || password == null) {
                    outputText(
                        "Peer's Flying Carpet service is missing characteristics " +
                        "(os: ${os != null}, ssid: ${ssid != null}, password: ${password != null})."
                    )
                    bluetoothFailed()
                    return
                }
                osCharacteristic = os
                ssidCharacteristic = ssid
                passwordCharacteristic = password
                // Bond first, explicitly, before touching anything that needs an encrypted link.
                // Letting the read trigger the bonding puts the stack on its implicit
                // authentication path (`gatt_security_check_start: unknown gatt_sec_act`), and on
                // this phone that path does not complete: the read comes back 137 and the bond
                // resets seconds later. Asking for the bond up front is the same thing that made
                // the advertising side work in +024 -- the phone shows its own prompt, the peer's
                // agent shows the matching passkey, and the read goes out afterwards. The bond
                // receiver re-issues pendingRead when BOND_BONDED arrives.
                val peer = gatt.device
                val bonded = try {
                    peer?.bondState == BluetoothDevice.BOND_BONDED
                } catch (e: SecurityException) {
                    false
                }
                if (!bonded && peer != null) {
                    pendingRead = OS_CHARACTERISTIC_UUID
                    outputText("Pairing first — accept it on both screens")
                    if (!peer.createBond()) {
                        outputText("Could not start pairing; asking for the details anyway")
                        read(OS_CHARACTERISTIC_UUID)
                    }
                    return
                }
                read(OS_CHARACTERISTIC_UUID)
            }

            override fun onServiceChanged(gatt: BluetoothGatt) {
                super.onServiceChanged(gatt)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && ActivityCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                {
                    return
                }
                outputText("Services changed")
                // This is the peer telling us its GATT database changed, and it is the only
                // signal Android gives us that its cache for a *bonded* device is stale.
                // Every peripheral removes its Flying Carpet service when a transfer ends and
                // re-adds it on the next one, so without re-discovering here a bonded central
                // can keep serving a snapshot that has no Flying Carpet service in it.
                //
                // The TODO this replaces asked whether enabling it causes problems. It does,
                // if left ungated: onServicesDiscovered re-reads the characteristics and calls
                // read(OS_CHARACTERISTIC_UUID), which restarts the credential exchange. That
                // is the same re-entrancy hazard onConnectionStateChange already guards with
                // exchangeComplete, so guard it the same way — before the exchange finishes we
                // want the re-discovery, after it the transfer is on TCP and this is pure
                // interference.
                if (exchangeComplete) {
                    Log.i("Bluetooth", "Ignoring service change; credential exchange already complete")
                    return
                }
                // Between transfers this is the peer's teardown removing its service —
                // re-discovering would find the service gone and fail a transfer that no
                // longer exists. This is the callback that flipped the Bluetooth switch off
                // on 2026-07-25 (iOS→Android), delivered to a leftover client after stop()
                // had cleared exchangeComplete.
                if (tearingDown) {
                    Log.i("Bluetooth", "Ignoring service change after teardown")
                    return
                }
                startDiscovery(gatt, "service change")
            }

            override fun onConnectionStateChange(
                gatt: BluetoothGatt?,
                status: Int,
                newState: Int
            ) {
                super.onConnectionStateChange(gatt, status, newState)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && ActivityCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                {
                    return
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    bluetoothGatt = gatt
                    // a fresh connection has no discovery outstanding on it, whatever the
                    // previous one left behind
                    discoveryOutstanding = false
                    connectRetries = 0
                    outputText("Connected")
                    // Both GATT connections we open — the autoConnect=false one from
                    // onScanResult and the autoConnect=true one the bond receiver opens after
                    // pairing — run discoverServices() and replay read-OS → write-OS. That
                    // repetition is deliberate: the first connection's encrypted read only
                    // *triggers* pairing, and its write-back can be lost while the link is
                    // still bonding, so the post-bond connection is the one that reliably
                    // completes the exchange.
                    //
                    // Once the exchange has actually completed, though, further replays are
                    // pure waste — the transfer is over TCP now — and the autoConnect=true
                    // link re-establishing mid-transfer would otherwise re-run the whole chain.
                    // So skip discovery once the exchange is done; until then, keep retrying.
                    if (exchangeComplete || tearingDown) {
                        Log.i("Bluetooth", "Skipping rediscovery; credential exchange complete or transfer torn down")
                        return
                    }
                    // 1600ms is Nordic's Android-BLE-Library constant: on a bonded device,
                    // wait ~1.6s before discoverServices() so the Service Changed indication
                    // and key exchange land first, or you enumerate a stale GATT database. We
                    // bond and never invalidate the cache. Possibly removable (Nordic say it
                    // was an Android 6 problem; minSdk is 29) — see
                    // docs/hardcoded-delays-audit.md before touching it.
                    //
                    // Scheduled rather than slept: this callback runs on the GATT binder
                    // thread, and sleeping there stalls every other callback behind it. Only
                    // fire if this is still the live connection, or a link that dropped during
                    // the delay produces a spurious "could not start discovery".
                    gatt?.let {
                        handler.postDelayed({
                            // Re-check on fire, not just at schedule time: the exchange can
                            // finish inside the 1600ms (~900ms observed) and the peer drops its
                            // service when it does, so a stale discovery lands in the
                            // service-missing branch and printed the "try unpairing" advice
                            // mid-join.
                            if (exchangeComplete || tearingDown) {
                                Log.i(
                                    "Bluetooth",
                                    "Skipping scheduled discovery; exchange completed during the settle delay"
                                )
                                return@postDelayed
                            }
                            if (bluetoothGatt === it) {
                                startDiscovery(it, "connected")
                            }
                        }, 1600)
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    // This was a bare Log.i that ignored `status` entirely — the last silent
                    // failure on the Android central path. 6b29695 instrumented every exit
                    // from onServicesDiscovered, but a connect that never succeeds never
                    // reaches it, so the UI simply went quiet right after "Stopped scanning"
                    // and the status code that names the cause was dropped on the floor.
                    // Observed 2026-07-25, Linux->Android.
                    //
                    // close() is not optional. Android leaks the underlying client if it is
                    // not called after a disconnect, and connectGatt() starts failing with
                    // status 133 once enough have leaked. Nothing closed it here, and
                    // Bluetooth.stop() only closes `bluetoothGatt`, which a *failed* connect
                    // never assigns — so every failed attempt leaked one and the retries
                    // leaked more. That is what turns one transient error into a device that
                    // stays broken until the app is restarted.
                    val current = bluetoothGatt
                    gatt?.close()
                    gatt?.let { untrackConnection(it) }
                    if (current === gatt) {
                        bluetoothGatt = null
                        // a discovery on a link that is gone will never call back, so don't let
                        // it latch the guard on and block the next connection's discovery
                        discoveryOutstanding = false
                    }
                    when {
                        // The peer hangs up once it has our credentials and the transfer has
                        // moved to TCP. Expected — Linux now does exactly this.
                        exchangeComplete ->
                            Log.i("Bluetooth", "Disconnected after exchange (status $status)")
                        // Between stop() and the next transfer, disconnects are teardown
                        // fallout — ours or the peer's — never a failure.
                        tearingDown ->
                            Log.i("Bluetooth", "Disconnected during teardown (status $status)")
                        // Pairing in flight. The first connection's encrypted read only
                        // *triggers* bonding, and the link commonly drops doing it; the
                        // ACTION_BOND_STATE_CHANGED receiver then opens the connection that
                        // actually completes the exchange. A step, not a failure.
                        result?.device?.bondState == BOND_BONDING ->
                            Log.i("Bluetooth", "Disconnected while bonding (status $status)")
                        // An older connection dropping while a newer one is live. The two
                        // overlap deliberately after bonding (see the comment above), so only
                        // the live one is allowed to fail the transfer.
                        current != null && current !== gatt ->
                            Log.i("Bluetooth", "Stale connection dropped (status $status)")
                        else -> {
                            outputText("Bluetooth connection failed with status $status.")
                            if (status == 133) {
                                outputText(
                                    "Status 133 is Android's generic GATT failure. If it keeps " +
                                    "happening, restart Flying Carpet on this device; if it " +
                                    "still fails, unpair the two devices from each other."
                                )
                            }
                            bluetoothFailed()
                        }
                    }
                } else {
                    Log.i("Bluetooth", "New connection state: $newState, status: $status")
                    // A disconnect we were never connected for is a *failed connection attempt*,
                    // not the end of a transfer: bluetoothGatt is set only once we reach CONNECTED,
                    // and closeGatt() clears it, after which no further callback arrives. Scanning
                    // has already been stopped by the time we get here, so with nothing done about
                    // it the app simply stopped: the log ended at "Stopped scanning" and never
                    // moved again, while the sender sat there advertising.
                    if (bluetoothGatt == null && status != BluetoothGatt.GATT_SUCCESS) {
                        gatt?.close()
                        connectionAttemptFailed(status)
                    }
                }
            }
        }

        // called when we get a bluetooth bonding event from the OS
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i("Bluetooth", "Action: ${intent?.action}")
            peerDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                intent?.getParcelableExtra(EXTRA_DEVICE)
            }
            val bondState = intent?.getIntExtra(EXTRA_BOND_STATE, -1)
            // The sending device is the passive half of pairing -- the system dialog is raised on
            // its behalf and nothing in the app hears about it except this broadcast. Report it, so
            // the sender's log tracks the pairing the receiver is already narrating.
            if (bondState == BluetoothDevice.BOND_NONE && clearingBond) {
                clearingBond = false
                lastAnnouncedBondState = BluetoothDevice.BOND_NONE
                Log.i("Bluetooth", "Bond removed at our own request")
                return
            }
            // The stack reports a single pairing with more than one broadcast -- BOND_BONDING
            // arrives twice on both phones -- so echoing every transition printed each line twice
            // and made it read as though the devices had paired two separate times. Announce only
            // states we have not already announced.
            if (bondState != lastAnnouncedBondState) {
                lastAnnouncedBondState = bondState ?: -1
                when (bondState) {
                    BluetoothDevice.BOND_BONDING ->
                        outputText("Pairing with the other device — accept the passkey on BOTH")
                    BOND_BONDED -> outputText("Paired with the other device")
                    BluetoothDevice.BOND_NONE ->
                        outputText("Pairing did not complete — it may ask again")
                }
            }
            if (bondState != BOND_BONDED) {
                // BONDING -> NONE means pairing failed or the user declined the system
                // pairing dialog. Without this, the transfer waits forever for
                // characteristic reads that will never happen. This receiver is registered
                // for the whole activity, so only react to our peer's bond events.
                val previousBondState = intent?.getIntExtra(EXTRA_PREVIOUS_BOND_STATE, -1)
                if (bondState == BOND_NONE && previousBondState == BOND_BONDING
                    && peerDevice != null && peerDevice?.address == result?.device?.address
                ) {
                    outputText("Bluetooth pairing failed or was declined.")
                    bluetoothFailed()
                } else {
                    Log.i("Bluetooth", "Not bonded")
                }
                return
            }
            // outputText("Device: $peerDevice")

            // The bond is what the ENCRYPTED_MITM read was waiting for, so resume it on the
            // connection we already have.
            //
            // Do NOT open a second GATT client here. This used to call connectGatt() again, left
            // over from when the scan callback only called createBond(); now that the scan callback
            // connects directly, a second client races the first -- bluetoothGatt is set by
            // whichever connection changes state last and the characteristics by whichever
            // discovers services last, and when those are different instances readCharacteristic()
            // cannot find the characteristic in its own handle map, returns false and never calls
            // back. That silent stall is what left both phones sitting on "Bluetooth connection
            // released" until the link timed out.
            val liveGatt = bluetoothGatt
            if (liveGatt != null) {
                val retry = pendingRead
                if (retry != null) {
                    outputText("Paired — asking the other device for its details again")
                    read(retry)
                } else {
                    // Nothing is waiting on the bond yet: service discovery is still in flight and
                    // issues the first read itself. Starting another one here only races it.
                    Log.i("Bluetooth", "Bonded with a live connection, no pending read")
                }
                return
            }

            if (result == null) {
                Log.e("Bluetooth", "Received ACTION_BOND_STATE_CHANGED but do not have device result")
                return
            }
            // This receiver hears every bond event on the system, not just our peer's. A
            // headset bonding mid-transfer must not open (or use up) the post-bond
            // connection meant for the device we scanned.
            if (peerDevice?.address != result?.device?.address) {
                Log.i("Bluetooth", "Bond state change for a different device; ignoring")
                return
            }
            if (!bonded) {
                bonded = true
                trackConnection(result!!.device.connectGatt(
                    application.applicationContext,
                    true,
                    gattCallback,
                    // TRANSPORT_LE, never TRANSPORT_AUTO. Flying Carpet's GATT service exists
                    // only over LE, and this fires the instant bonding completes — exactly
                    // when cross-transport key derivation has minted BR/EDR keys alongside the
                    // LE ones. Letting the stack choose from a dual-transport bond is what
                    // made BlueZ page classic and fail with br-connection-canceled against a
                    // peer that serves no GATT there (docs/bluetooth-field-guide.md).
                    BluetoothDevice.TRANSPORT_LE,
                ))
            } else {
                Log.e("Bluetooth", "Received ACTION_BOND_STATE_CHANGED but already bonded")
            }
        }

        // Which characteristic a UUID refers to on the peer, once discovery has resolved them.
        private fun characteristicFor(characteristicUuid: UUID): BluetoothGattCharacteristic? {
            return when (characteristicUuid) {
                OS_CHARACTERISTIC_UUID -> osCharacteristic
                SSID_CHARACTERISTIC_UUID -> ssidCharacteristic
                PASSWORD_CHARACTERISTIC_UUID -> passwordCharacteristic
                else -> null
            }
        }

        // use to read peripheral's characteristic
        // A read that goes out and never comes back used to stall the transfer silently and for
        // ever: every step of the exchange is driven by the previous step's callback, so one lost
        // answer stops everything with nothing in the log (白い熊, 2026-08-08). Whatever the cause
        // -- a vendor stack calling a callback shape we do not override, a link that dropped
        // between request and answer -- asking again is safe, and giving up out loud beats waiting.
        private var readWatchdog: Runnable? = null
        private var readRetries = 0

        private fun armReadWatchdog(characteristicUuid: UUID) {
            cancelReadWatchdog()
            val watchdog = Runnable {
                if (pendingRead != characteristicUuid) {
                    return@Runnable
                }
                if (readRetries < MAX_LOST_READ_RETRIES) {
                    readRetries++
                    outputText(
                        "No answer to our request yet — asking again "
                                + "($readRetries of $MAX_LOST_READ_RETRIES)"
                    )
                    read(characteristicUuid)
                } else {
                    readRetries = 0
                    pendingRead = null
                    outputText(
                        "The other device is connected but will not answer. Start the transfer "
                                + "again on both devices."
                    )
                    bluetoothFailed()
                }
            }
            readWatchdog = watchdog
            handler.postDelayed(watchdog, 8000)
        }

        private fun cancelReadWatchdog() {
            readWatchdog?.let { handler.removeCallbacks(it) }
            readWatchdog = null
            readRetries = 0
        }

        fun read(characteristicUuid: UUID) {
            // outputText("Reading $characteristicUuid")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ActivityCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            {
                outputText("No permission")
                return
            }
            // Three ways this used to do nothing whatsoever, none of them printing anything: a
            // null bluetoothGatt (the `?.` swallowed the entire call), a null characteristic (an
            // unknown UUID fell through the `when` with no else), and readCharacteristic()
            // returning false because the GATT queue was busy or the link was gone. Each read is
            // what triggers the callback that issues the next step, so any drop stalls the
            // exchange for good — and one was observed doing exactly that on 2026-07-25.
            //
            // Reported, not fatal. A false return is legitimately transient: the two GATT
            // connections after bonding deliberately coexist and can each be walking the
            // exchange, so one of them finding the queue busy is not grounds for killing a
            // transfer the other is about to complete.
            //
            // Exactly ONE readCharacteristic() per call, and the retry goes through the handler.
            // This used to issue the read twice -- once to test its return value for the message
            // above, once "for real" -- and Android's GATT queue takes one operation at a time, so
            // the second call was refused *because the first was in flight*. Then Thread.sleep(500)
            // blocked the very thread the first read's callback arrives on, the third call was
            // refused too, and the transfer died with "The other device would not answer" while the
            // read it was complaining about had already succeeded. Observed PC -> phone on
            // 2026-08-08: the desktop logged one OS read ("linux") and never heard from the phone
            // again, and the phone switched its own Bluetooth off.
            val gatt = bluetoothGatt
            val characteristic = characteristicFor(characteristicUuid)
            if (gatt == null) {
                Log.e("Bluetooth", "read($characteristicUuid): no gatt")
                outputText("Could not read $characteristicUuid: no Bluetooth connection.")
                return
            }
            if (characteristic == null) {
                Log.e("Bluetooth", "read($characteristicUuid): characteristic not discovered")
                outputText("Could not read $characteristicUuid: characteristic not discovered.")
                return
            }
            pendingRead = characteristicUuid
            if (gatt.readCharacteristic(characteristic)) {
                armReadWatchdog(characteristicUuid)
                return
            }
            // A refusal here is legitimately transient -- the pre-bond and post-bond connections
            // deliberately coexist and either can be mid-operation -- so try once more after the
            // queue has had a moment, without blocking this thread.
            Log.e("Bluetooth", "readCharacteristic($characteristicUuid) returned false, retrying")
            outputText("Bluetooth read of $characteristicUuid was rejected (queue busy); retrying")
            handler.postDelayed({
                val retryGatt = bluetoothGatt
                if (retryGatt == null || !retryGatt.readCharacteristic(characteristic)) {
                    pendingRead = null
                    Log.e("Bluetooth", "readCharacteristic($characteristicUuid) returned false again")
                    outputText("The other device would not answer. Turn Bluetooth off and on, then try again.")
                    bluetoothFailed()
                }
            }, 500)
        }

        // The stack would not open the connection. Ask it again a few times before believing it:
        // 133 in particular is thrown by a healthy stack talking to a peer that is right there and
        // still advertising, and the next attempt usually lands. Give up eventually, and when we
        // do, say so and unlock the UI -- a transfer that cannot start must not leave the app
        // looking busy for ever.
        @SuppressLint("MissingPermission")
        private fun connectionAttemptFailed(status: Int) {
            val device = result?.device
            if (device == null || connectRetries >= MAX_CONNECT_RETRIES) {
                outputText(
                    "The other device would not accept a Bluetooth connection (error $status). " +
                            "Start receiving again to try afresh."
                )
                connectRetries = 0
                waitingForConnection = false
                cleanUpTransfer()
                return
            }
            connectRetries += 1
            outputText("It did not accept the connection (error $status) — trying again ($connectRetries of $MAX_CONNECT_RETRIES)")
            Handler(Looper.getMainLooper()).postDelayed({
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                    || ActivityCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
                {
                    device.connectGatt(
                        application.applicationContext,
                        false,
                        gattCallback,
                        BluetoothDevice.TRANSPORT_LE,
                    )
                }
            }, CONNECT_RETRY_DELAY_MS)
        }

        // Close the client rather than just dropping the reference: close() unregisters the app's
        // GATT client interface, and without it ours stayed registered long after the transfer
        // ended. A stale second client is what made readCharacteristic() a silent no-op.
        @SuppressLint("MissingPermission")
        fun closeGatt() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || ActivityCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
            {
                bluetoothGatt?.close()
            }
            bluetoothGatt = null
            osCharacteristic = null
            ssidCharacteristic = null
            passwordCharacteristic = null
            pendingRead = null
            bonded = false
            connectRetries = 0
            lastAnnouncedBondState = -1
        }

        // private fun writeSinglePacket(characteristicUuid: UUID, value: ByteArray, waitForResponse: Boolean) {
        fun write(characteristicUuid: UUID, value: ByteArray) {
            // outputText("Writing to $characteristicUuid")
            // val writeType = if (waitForResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ActivityCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            {
                return
            }
            // Same three silent drops as read() above, plus a `characteristic!!` on the Tiramisu
            // path that would have thrown instead of reporting. Reported, not fatal, for the same
            // reason.
            val gatt = bluetoothGatt
            val characteristic = characteristicFor(characteristicUuid)
            if (gatt == null) {
                outputText("Could not write $characteristicUuid: no Bluetooth connection.")
                return
            }
            if (characteristic == null) {
                outputText("Could not write $characteristicUuid: characteristic not discovered.")
                return
            }
            // One write per call, retried through the handler -- the same double-issue bug read()
            // carried, and with the same consequence: the second call was refused because the
            // first was still in flight, and the transfer was declared dead over a write that had
            // gone out. Writing our OS back is the step that triggers connectToPeer(), so a write
            // wrongly given up on strands both sides waiting.
            if (writeCharacteristicCompat(gatt, characteristic, value, writeType)) {
                return
            }
            Log.e("Bluetooth", "writeCharacteristic($characteristicUuid) failed, retrying")
            outputText("Bluetooth write of $characteristicUuid was rejected (queue busy); retrying")
            handler.postDelayed({
                val retryGatt = bluetoothGatt
                if (retryGatt == null
                    || !writeCharacteristicCompat(retryGatt, characteristic, value, writeType)) {
                    outputText("Could not send our details to the other device. Turn Bluetooth off and on, then try again.")
                    bluetoothFailed()
                }
            }, 500)
        }

        @SuppressLint("MissingPermission")
        private fun writeCharacteristicCompat(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            writeType: Int,
        ): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val code = gatt.writeCharacteristic(characteristic, value, writeType)
                if (code != BluetoothStatusCodes.SUCCESS) {
                    Log.e("Bluetooth", "writeCharacteristic(${characteristic.uuid}) returned $code")
                }
                return code == BluetoothStatusCodes.SUCCESS
            }
            @Suppress("DEPRECATION")
            characteristic.value = value
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            return gatt.writeCharacteristic(characteristic)
        }

        // going to split ssid and password into separate characteristics to avoid having to implement streaming,
        // in the hope that android will never make hotspots with SSIDs or passwords longer than 20 characters
//        fun write(characteristicUuid: UUID, value: ByteArray) {
//            var cursor = 0
//            while (cursor < value.size) {
//                val chunk = value.slice(cursor until min(cursor + packetSize, value.size))
//                cursor += chunk.size
//                writeSinglePacket(characteristicUuid, chunk.toByteArray(), false)
//            }
//            writeSinglePacket(characteristicUuid, messageTerminator, true)
//        }
    }


}

