package dev.spiegl.flyingcarpet

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

// Fork: waking a paired device over Bluetooth, so a hotspot transfer needs no tap on it.
//
// ── Read this before changing anything here ────────────────────────────────────────────────
//
// This is the last piece of the paired-devices feature and the first one to switch off. It is
// off by default, it only does anything while "Stay reachable" is also on, and nothing else in
// the feature depends on it: the LAN routes do not touch it, and the hotspot route works
// without it at the cost of one tap on the far device.
//
// It exists because of a genuine deadlock. A hotspot transfer cannot start from a device list,
// because until one device raises an access point there is no IP path between them at all —
// so somebody has to act on both. Bluetooth is the only channel this app has that works with
// no network, which makes it the only way to close that gap.
//
// **Why the caution.** Standing BLE presence on EMUI is where this fork has bled most. The
// 31-byte advertisement that went one byte over because Android measures a name in UTF-16
// units; the GATT server that stopped advertising on any incoming LE connection; the scan
// window that was an exact harmonic of BlueZ's advertising interval, so a packet that fell in
// the gap fell in the gap for ever; the thirty-seven bond records; `status 133`. The retraction
// that removed bonding entirely is from 2026-09-10. So this deliberately does none of the
// things that caused any of it:
//
//   * **No GATT, no bonding, no connection.** Nothing here connects to anything. The whole
//     exchange is one advertisement and one scan result — a doorbell, not a conversation.
//   * **No device name in the advertisement.** That is what overran the 31-byte budget, and
//     there is nothing here that needs one: the payload is 8 bytes of manufacturer data.
//   * **Nothing is trusted from the air.** The tag is an HMAC of the group key over a rolling
//     minute, so a stranger cannot ring the bell and a recording of one stops working within
//     two minutes. What it triggers is the ordinary paired hotspot route, which then has to
//     pass the Noise handshake like anything else.
//
// **What it still cannot fix.** A frozen process does not scan. A PendingIntent scan survives
// process death and Doze far better than a socket does, which is why it is used here, but EMUI
// may still refuse — and the honest answer when it does is the tap on the far device that this
// was trying to save.

/**
 * An unassigned 16-bit company identifier. Manufacturer data rather than a service UUID
 * because a 128-bit UUID costs 18 of the advertisement's 31 bytes before any payload; this
 * costs 2 and leaves room for the tag.
 */
internal const val COMPANY_ID = 0x0BEE

/** Role byte: what the *advertising* device is about to do. */
private const val ROLE_SENDING: Byte = 1
private const val ROLE_RECEIVING: Byte = 2

/** How long to ring the doorbell before giving up on it. */
const val WAKE_ADVERTISE_MS = 20_000L

private const val TAG = "BleWake"

/**
 * The payload: role(1) + the first two bytes of the caller's device id + a 5-byte rolling tag.
 * Eight bytes, so flags(3) + manufacturer data(2 + 2 + 8 = 12) leaves the 31-byte budget
 * comfortable — no repeat of the advertisement that was one byte over.
 */
private fun wakePayload(presenceKey: ByteArray, deviceId: ByteArray, role: Byte, minute: Long):
    ByteArray {
    val tag = wakeTag(presenceKey, deviceId, role, minute)
    return byteArrayOf(role, deviceId[0], deviceId[1]) + tag
}

private fun wakeTag(presenceKey: ByteArray, deviceId: ByteArray, role: Byte, minute: Long):
    ByteArray {
    val material = "mahojutan wake v1".toByteArray(Charsets.UTF_8) +
        byteArrayOf(role, deviceId[0], deviceId[1]) +
        minute.toString().toByteArray(Charsets.UTF_8)
    return computeHmac(presenceKey, material).copyOf(5)
}

private fun currentMinute(): Long = System.currentTimeMillis() / 60_000

/**
 * Reads a wake payload, or returns null. Accepts the previous, current and next minute, so a
 * clock a little out of step still works while a recording made three minutes ago does not.
 */
fun parseWake(data: ByteArray?, presenceKey: ByteArray): Pair<Byte, ByteArray>? {
    if (data == null || data.size < 8) return null
    val role = data[0]
    if (role != ROLE_SENDING && role != ROLE_RECEIVING) return null
    val idPrefix = byteArrayOf(data[1], data[2])
    val tag = data.copyOfRange(3, 8)
    val now = currentMinute()
    for (minute in (now - 1)..(now + 1)) {
        if (wakeTag(presenceKey, idPrefix, role, minute).contentEquals(tag)) {
            return Pair(role, idPrefix)
        }
    }
    return null
}

/**
 * Rings the doorbell. [sending] is what *this* device is about to do, so the woken device
 * takes the other half.
 */
@SuppressLint("MissingPermission") // the caller holds BLUETOOTH_ADVERTISE; checked below too
class WakeAdvertiser(private val context: Context) {
    private var advertiser: BluetoothLeAdvertiser? = null
    private var callback: AdvertiseCallback? = null

    fun start(presenceKey: ByteArray, deviceId: ByteArray, sending: Boolean): Boolean {
        stop()
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.i(TAG, "Bluetooth is off; not ringing")
            return false
        }
        val le = adapter.bluetoothLeAdvertiser
        if (le == null) {
            Log.i(TAG, "No LE advertiser on this device")
            return false
        }
        val role = if (sending) ROLE_SENDING else ROLE_RECEIVING
        val payload = wakePayload(presenceKey, deviceId, role, currentMinute())
        val data = AdvertiseData.Builder()
            // Deliberately false. This is the flag that overran the budget before, and there
            // is nothing here that needs a name.
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(COMPANY_ID, payload)
            .build()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            // Nothing connects to this; it is a doorbell.
            .setConnectable(false)
            .setTimeout(0)
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                // 1 is DATA_TOO_LARGE, which is the historic failure here and must never
                // come back silently.
                Log.w(TAG, "Wake advertisement refused, error $errorCode")
            }
        }
        callback = cb
        advertiser = le
        return try {
            le.startAdvertising(settings, data, cb)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not advertise: ${e.message}")
            false
        }
    }

    fun stop() {
        val le = advertiser
        val cb = callback
        advertiser = null
        callback = null
        if (le == null || cb == null) return
        try {
            le.stopAdvertising(cb)
        } catch (e: Exception) {
            Log.i(TAG, "stopAdvertising: ${e.message}")
        }
    }
}

/**
 * Listens for the doorbell, through a PendingIntent scan rather than a callback: a callback
 * dies with the process, and the whole point is to be woken when this app is not running.
 */
object WakeScanner {

    @SuppressLint("MissingPermission")
    fun start(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val scanner = manager?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return false
        // Filtered in the controller rather than in our own process, so the CPU is not woken
        // for every advertisement in the building — which on a phone is the whole cost.
        val filter = ScanFilter.Builder()
            .setManufacturerData(COMPANY_ID, byteArrayOf(), byteArrayOf())
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        return try {
            scanner.startScan(listOf(filter), settings, pendingIntent(context))
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not start the wake scan: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stop(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val scanner = manager?.adapter?.bluetoothLeScanner ?: return
        try {
            scanner.stopScan(pendingIntent(context))
        } catch (e: Exception) {
            Log.i(TAG, "stopScan: ${e.message}")
        }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, WakeReceiver::class.java).setAction(WakeReceiver.ACTION_WAKE),
        // MUTABLE because the platform fills the scan results into this intent; an immutable
        // one is delivered empty.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )
}

/** Exposed for the receiver, which has to answer the same role byte. */
internal fun wakeRoleIsSending(role: Byte): Boolean = role == ROLE_SENDING
