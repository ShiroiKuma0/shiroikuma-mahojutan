package dev.spiegl.flyingcarpet

import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

// Fork: the far end of the Bluetooth doorbell.
//
// Delivered by the platform's scan machinery, which means it can arrive with this app not
// running at all — that is the whole reason the scan is registered with a PendingIntent rather
// than a callback.
//
// Everything it is handed comes off the air, so nothing here is trusted: the payload has to
// carry a tag that only a holder of the group key can produce, the advertiser has to already
// be a paired device, and what it can ask for is exactly one thing — that this device take its
// half of a paired hotspot transfer, which then has to pass the Noise handshake like any
// other. There is no path from an advertisement to anything being read, written or sent.
class WakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WAKE) return
        val pairing = Pairing(context)
        // Two independent switches, both of which must be on. A wake is only meaningful for a
        // device that is trying to be reachable in the first place.
        if (!pairing.bleWake || !pairing.stayReachable) return
        val groupKey = pairing.groupKey ?: return
        val presenceKey = derivePresenceKey(groupKey)

        val results = readResults(intent)
        for (result in results) {
            val data = result.scanRecord?.getManufacturerSpecificData(COMPANY_ID)
            val parsed = parseWake(data, presenceKey) ?: continue
            val (role, idPrefix) = parsed
            // Two bytes of a device id is not an identity and is not treated as one: it
            // narrows the paired list, and the Noise handshake decides the rest. If it is
            // ambiguous, nothing is woken — guessing which device rang would be worse than
            // the tap this was saving.
            val candidates = pairing.peers().filter {
                val id = base32Decode(it.deviceId) ?: return@filter false
                id.size >= 2 && id[0] == idPrefix[0] && id[1] == idPrefix[1]
            }
            val peer = candidates.singleOrNull() ?: continue
            if (!peer.autoAccept) {
                Log.i(TAG, "${peer.displayName} rang, but it is set to ask first; ignoring")
                continue
            }

            // The advertiser said what IT is about to do, so this device takes the other half.
            val weSend = !wakeRoleIsSending(role)
            Log.i(TAG, "${peer.displayName} rang; taking the ${if (weSend) "sending" else "receiving"} half")
            PresenceService.wakeForHotspot(context, peer.deviceId, weSend)
            return
        }
    }

    private fun readResults(intent: Intent): List<ScanResult> {
        val list = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(EXTRA_RESULTS, ScanResult::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(EXTRA_RESULTS)
        }
        return list ?: emptyList()
    }

    companion object {
        const val ACTION_WAKE = "dev.spiegl.flyingcarpet.action.BLE_WAKE"
        private const val EXTRA_RESULTS = android.bluetooth.le.BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT
        private const val TAG = "BleWake"
    }
}
