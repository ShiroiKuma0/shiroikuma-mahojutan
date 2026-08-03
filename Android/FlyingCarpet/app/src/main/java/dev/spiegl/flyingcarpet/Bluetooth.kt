package dev.spiegl.flyingcarpet

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.BOND_BONDED
import android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE
import android.bluetooth.BluetoothDevice.EXTRA_DEVICE
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
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
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.UUID

// how many UTF-8 bytes of the adapter name still fit in a 31-byte advertisement alongside
// the flags (3 bytes) and our 128-bit service UUID (18 bytes), minus the name AD header (2 bytes)
const val MAX_ADVERTISED_NAME_BYTES = 8

// Whether to drop the pairing with the peer after every transfer. It costs two dialogs per transfer
// on Android -- the authorize prompt and the passkey -- and keeping the bond instead was tried
// twice and fails for a reason that has nothing to do with the keys: once the peer is paired, BlueZ
// stops announcing it during discovery (it only emits DeviceAdded for an object it had pruned and
// sees again, and paired devices are never pruned), and the bonded identity record does not carry
// our service UUID, so the by-address lookup skips it too. A bonded peer is simply unfindable from
// Linux. Clearing the bond keeps it an ordinary unpaired device that discovery reports normally.
// Must stay in step with the Linux side, which removes the device in ConnectedPeripheral::drop --
// if one side keeps its keys and the other does not, the next transfer dies mid-handshake.
const val CLEAR_BOND_AFTER_TRANSFER = true

val SERVICE_UUID: UUID = UUID.fromString("A70BF3CA-F708-4314-8A0E-5E37C259BE5C")
val OS_CHARACTERISTIC_UUID: UUID = UUID.fromString("BEE14848-CC55-4FDE-8E9D-2E0F9EC45946")
val SSID_CHARACTERISTIC_UUID: UUID = UUID.fromString("0D820768-A329-4ED4-8F53-BDF364EDAC75")
val PASSWORD_CHARACTERISTIC_UUID: UUID = UUID.fromString("E1FA8F66-CF88-4572-9527-D5125A2E0762")
const val NO_SSID = "NONE"

interface BluetoothDelegate {
    fun gotPeer(peerOS: String)
    fun gotSsid(ssid: String)
    fun gotPassword(password: String)
    fun connectToPeer()
    fun getWifiInfo(): Pair<String, String>
    fun outputText(msg: String)
    fun bluetoothFailed()
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
            val removed = device.javaClass.getMethod("removeBond").invoke(device) as? Boolean ?: false
            outputText(if (removed) "Cleared pairing with peer" else "Could not clear pairing with peer")
        } catch (e: Exception) {
            outputText("Could not clear pairing with peer: ${e.javaClass.simpleName}")
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
        bluetoothManager.adapter.bluetoothLeAdvertiser.stopAdvertising(advertiseCallback)
        outputText("Peer found us, stopped advertising")
    }

    fun stop(application: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            && ActivityCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
        {
            return
        }
        _status.postValue(false)
        // central
        if (this::bluetoothLeScanner.isInitialized) {
            bluetoothLeScanner.stopScan(leScanCallback)
        }
        bluetoothReceiver.bluetoothGatt = null
        // peripheral
        if (this::bluetoothManager.isInitialized) {
            advertising = false
            bluetoothManager.adapter.bluetoothLeAdvertiser.stopAdvertising(advertiseCallback)
        }
        // this prevents android from sending twice? but disabling it leaves it advertising or offering services even after the stopAdvertising() above?
        // need to clear services and replace between transfers?
        // bluetoothGattServer.close()
        if (this::bluetoothGattServer.isInitialized) {
            bluetoothGattServer.clearServices()
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

        // open server, create service
        bluetoothGattServer = bluetoothManager.openGattServer(application, serverCallback) ?: return false
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
                // deliberately NOT stopping the advertiser here. this callback fires for LE links
                // that have nothing to do with us -- on EMUI/Kirin a watch, earbuds or a system
                // service connecting is enough -- and stopping here took us silently off the air
                // moments after "Advertiser started", so the peer scanning right next to us never
                // found anything. we come off the air in stopAdvertisingForPeer() instead, once
                // something actually reads or writes one of OUR characteristics, which only the
                // real Flying Carpet peer does.
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
            if (characteristic == null) {
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
            if (characteristic == null) {
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
                        // than leaving the log looking stalled.
                        outputText("The other device is $os")
                        outputText("It is setting up its hotspot now — that takes a few seconds, since it has to drop its own WiFi first")
                        outputText("Waiting for it to send us the network name and password...")
                        gotPeer(os)
                    }
                }
                SSID_CHARACTERISTIC_UUID -> {
                    // central has written ssid to us as peripheral. if they wrote an ssid, we need to store it.
                    // if they didn't, we don't need to do anything, and just wait for them to write the password,
                    // at which point we can calculate the ssid and key.
                    if (value != null) {
                        val theirSsid = value.toString(Charsets.UTF_8)
                        outputText("Its hotspot will be $theirSsid — waiting for the password")
                        gotSsid(theirSsid)
                    }
                }
                PASSWORD_CHARACTERISTIC_UUID -> {
                    if (value != null) {
                        outputText("Got the password — joining its hotspot next")
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
        // BluetoothLeAdvertiser
        val bluetoothLeAdvertiser = bluetoothManager.adapter.bluetoothLeAdvertiser
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
        val nameBytes = bluetoothManager.adapter.name?.toByteArray(Charsets.UTF_8)?.size ?: 0
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
        if (bluetoothManager.adapter.bluetoothLeScanner == null) {
            return false
        }
        bluetoothLeScanner = bluetoothManager.adapter.bluetoothLeScanner
        return bluetoothManager.adapter != null
    }

    fun scan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            && ActivityCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
        {
            outputText("Missing permission BLUETOOTH_SCAN")
            return
        }
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val scanSettings = ScanSettings.Builder()
            // this was actually the culprit
            // .setLegacy(false)
            .build()
        bluetoothLeScanner.startScan(listOf(scanFilter), scanSettings, leScanCallback)
        _status.postValue(true)
        outputText("Scanning for Bluetooth peripherals...")
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
                if (bluetoothReceiver.waitingForConnection) {
                    outputText("Found device: ${result.device}")
                    bluetoothReceiver.waitingForConnection = false
                    bluetoothLeScanner.stopScan(this)
                    outputText("Stopped scanning")
                    //                address = result.device.address
                    bluetoothReceiver.result = result
                    peerDevice = result.device

//                    if (result.device.bondState == BOND_BONDED) {
                    result.device.connectGatt(
                        application.applicationContext,
                        false,
                        bluetoothReceiver.gattCallback,
                        BluetoothDevice.TRANSPORT_LE,
                    )
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
        private var bonded = false

        val gattCallback = object : BluetoothGattCallback() {
            // this is called when we as central have read a characteristic from the peer's peripheral
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                super.onCharacteristicRead(gatt, characteristic, value, status)
                val stringRepresentation = value.toString(Charsets.UTF_8)
                Log.i("Bluetooth", "Read characteristic: $stringRepresentation")
                when (characteristic.uuid) {
                    OS_CHARACTERISTIC_UUID -> {
                        gotPeer(value.toString(Charsets.UTF_8))
                    }
                    SSID_CHARACTERISTIC_UUID -> {
                        val ssid = value.toString(Charsets.UTF_8)
                        if (ssid == "") {
                            // peripheral hasn't stood up its hotspot yet, have to wait.
                            // kill a second, then read again, which will loop us back here.
                            outputText("Could not read peer's WiFi characteristic. trying again...")
                            Thread.sleep(1000)
                            read(SSID_CHARACTERISTIC_UUID)
                            return
                        }
                        gotSsid(ssid)
                        // doing this here instead of in gotSsid because if peripheral had SSID
                        // written to it, we wouldn't need to call read
                        // we read the SSID, now read the password.
                        read(PASSWORD_CHARACTERISTIC_UUID)
                    }
                    PASSWORD_CHARACTERISTIC_UUID -> gotPassword(value.toString(Charsets.UTF_8))
                }
            }

            // this is called when we as central have written a characteristic to the peripheral
            override fun onCharacteristicWrite(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int
            ) {
                super.onCharacteristicWrite(gatt, characteristic, status)
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
                        outputText("Wrote password to peer")
                        // we told the peripheral the password, now just have to wait for them to join the hotspot
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && ActivityCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                {
                    return
                }
                super.onServicesDiscovered(gatt, status)
                outputText("Discovered services")
                for (service in gatt?.services!!) {
                    // outputText("Service: ${service.uuid}")
                }
                val service = gatt.getService(SERVICE_UUID)
                if (service == null) {
                    outputText("Did not find service")
//                    outputText("Trying to find services again")
//                    Thread.sleep(1000)
//                    gatt.discoverServices()
                    return
                }
                // outputText("Got service: $service")
                osCharacteristic = service.getCharacteristic(OS_CHARACTERISTIC_UUID) ?: return
                ssidCharacteristic = service.getCharacteristic(SSID_CHARACTERISTIC_UUID) ?: return
                passwordCharacteristic = service.getCharacteristic(PASSWORD_CHARACTERISTIC_UUID) ?: return
                // outputText("Got characteristics: $osCharacteristic, $ssidCharacteristic, $passwordCharacteristic")
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
                // TODO: should this be enabled? does it cause problems? https://developer.android.com/reference/android/bluetooth/BluetoothGattCallback#onServiceChanged(android.bluetooth.BluetoothGatt)
                // gatt.discoverServices()
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
                    outputText("Connected")
                    // this was the reason android couldn't connect to macOS? no, was the setLegacy(false). diagnosed by comparing nRF Connect logs from Flying Carpet pairings to nRF Connect pairings.
                    Thread.sleep(1600)
                    gatt?.discoverServices()
                } else {
                    Log.i("Bluetooth", "New connection state: $newState")
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
            when (bondState) {
                BluetoothDevice.BOND_BONDING ->
                    outputText("Pairing with the other device — accept the passkey on BOTH")
                BOND_BONDED -> outputText("Paired with the other device")
                BluetoothDevice.BOND_NONE ->
                    outputText("Pairing did not complete — it may ask again")
            }
            if (bondState != BOND_BONDED) {
                Log.i("Bluetooth", "Not bonded")
                return
            }
            // outputText("Device: $peerDevice")

            if (result == null) {
                Log.e("Bluetooth", "Received ACTION_BOND_STATE_CHANGED but do not have device result")
                return
            }
            if (!bonded) {
                bonded = true
                result!!.device.connectGatt(
                    application.applicationContext,
                    true,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_AUTO,
                )
            } else {
                Log.e("Bluetooth", "Received ACTION_BOND_STATE_CHANGED but already bonded")
            }
        }

        // use to read peripheral's characteristic
        fun read(characteristicUuid: UUID) {
            // outputText("Reading $characteristicUuid")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ActivityCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            {
                outputText("No permission")
                return
            }
            when (characteristicUuid) {
                OS_CHARACTERISTIC_UUID -> bluetoothGatt?.readCharacteristic(osCharacteristic)
                SSID_CHARACTERISTIC_UUID -> bluetoothGatt?.readCharacteristic(ssidCharacteristic)
                PASSWORD_CHARACTERISTIC_UUID -> bluetoothGatt?.readCharacteristic(passwordCharacteristic)
            }
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
            val characteristic = when (characteristicUuid) {
                OS_CHARACTERISTIC_UUID -> osCharacteristic
                SSID_CHARACTERISTIC_UUID -> ssidCharacteristic
                PASSWORD_CHARACTERISTIC_UUID -> passwordCharacteristic
                else -> {
                    outputText("Bad characteristic: $characteristicUuid")
                    return
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bluetoothGatt?.writeCharacteristic(
                    characteristic!!,
                    value,
                    writeType
                )
            } else {
                characteristic?.value = value
                characteristic?.writeType = writeType
                bluetoothGatt?.writeCharacteristic(characteristic)
            }
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

