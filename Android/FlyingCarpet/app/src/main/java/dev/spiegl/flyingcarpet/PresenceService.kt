package dev.spiegl.flyingcarpet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

// Fork: "Stay reachable" — being findable with the app closed.
//
// This is the part of paired devices with a real cost, and the cost is stated on the switch
// that turns it on rather than buried here. Everything else in this feature works with the
// app open and costs nothing standing.
//
// **What it actually holds.** A TCP accept socket, and — through PresenceResponder — a UDP
// socket and a MulticastLock. The lock is the expensive one: holding it switches off the
// Wi-Fi chip's multicast and broadcast filtering, so the CPU is woken for every such frame on
// the network. That is why the receiver in this design does not beacon, and why a sender
// tries a peer's remembered address before it broadcasts anything: a unicast packet to the
// phone's own address arrives whether or not the lock is held, so the common case does not
// need this service at all.
//
// **What will fight it.** These phones freeze backgrounded apps. The manifest already says so
// beside AutomationDataService: "a backgrounded app writing for any length of time is frozen
// mid-stream on this phone". A foreground service is the strongest thing an ordinary app can
// do about that, and it is still not a guarantee — EMUI's protected-app list and a
// battery-optimisation exemption both matter, and a frozen process answers nothing. The
// switch says this, and offers the two settings screens.
//
// `specialUse` rather than `dataSync`: the app already holds FOREGROUND_SERVICE_SPECIAL_USE
// for the automation door, so this adds no permission, and Android 15+ caps a dataSync
// service at six hours in any twenty-four — which an always-on receiver would hit daily.
class PresenceService : Service() {

    private var controller: PairedController? = null
    private var viewModel: MainViewModel? = null
    private var scanning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat()

        // Its own ViewModel, not the Activity's: there may not be an Activity, and reaching
        // for one would be the whole reason this cannot work. Safe because MainViewModel's
        // Bluetooth object does nothing until it is initialized, and a paired transfer over a
        // network never initializes it — see the Bluetooth constructor.
        val model = MainViewModel(application)
        viewModel = model
        val paired = PairedController(applicationContext, model)
        paired.receiveDirProvider = { storedReceiveDir() }
        paired.onArrival = { offer -> notifyArrival(offer) }
        controller = paired
        paired.start()
        // The doorbell, if it is switched on. Registered here rather than in the Activity so
        // it lives exactly as long as the promise to be reachable does.
        if (paired.pairing.bleWake && WakeScanner.start(this)) {
            scanning = true
        }
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // The notification's Stop is the switch, not a pause: it clears the setting too,
            // so the main page's switch reads off and nothing restarts this behind the user's
            // back when the app is next opened.
            Pairing(applicationContext).stayReachable = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_WAKE_HOTSPOT) {
            val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
            val sending = intent.getBooleanExtra(EXTRA_SENDING, false)
            takeHotspotHalf(deviceId, sending)
            return START_STICKY
        }
        // START_STICKY: if the system kills this for memory it should come back, because the
        // whole promise of the switch is that the device stays reachable.
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        if (scanning) {
            WakeScanner.stop(this)
            scanning = false
        }
        controller?.stop()
        controller = null
        viewModel = null
        super.onDestroy()
    }

    /**
     * Answers a Bluetooth doorbell by taking this device's half of a paired hotspot transfer.
     *
     * Receiving is the only half that can be taken without a person: the sending half needs
     * files chosen, and there is nobody here to choose them. A ring asking this device to
     * send is therefore turned into a notification rather than an action — which is still
     * better than the alternative, because it says a device is waiting.
     */
    private fun takeHotspotHalf(deviceId: String?, sending: Boolean) {
        val paired = controller ?: return
        val peer = deviceId?.let { paired.pairing.findPeer(it) } ?: return
        if (sending) {
            notifyWaiting(peer)
            return
        }
        val destination = storedReceiveDir()
        if (destination == null) {
            notifyNoDestination(peer)
            return
        }
        // Presence and the hotspot cannot both have the Wi-Fi; overHotspot() drops the first.
        paired.overHotspot(peer, sending = false, receiveDir = destination) { }
    }

    /**
     * The directory an unattended transfer lands in — the same one the "Receive in …" button
     * uses, so there is nothing extra to configure and nothing that can disagree with it.
     */
    private fun storedReceiveDir(): Uri? {
        val stored = Settings(applicationContext).text("receive.lastDir")
        return if (stored.isEmpty()) null else Uri.parse(stored)
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Reachable for paired devices",
                // LOW: this notification is a legal requirement of running a foreground
                // service, not news. It must never make a sound.
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Shown while this device can receive from paired devices." }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ARRIVAL_CHANNEL_ID,
                "Files received",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Shown when a paired device sends something." }
        )
    }

    private fun startForegroundCompat() {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, PresenceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Reachable for paired devices")
            .setContentText("Paired devices can send to this one without it being opened.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(open)
            // A standing notification that cannot be switched off from itself is a bad
            // citizen, and this one has a real cost behind it.
            .addAction(0, "Stop", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Says what landed. Without this an unattended transfer is indistinguishable from nothing
     * happening, which is exactly the property that makes an unattended receiver feel unsafe.
     */
    private fun notifyArrival(offer: PairedOffer) {
        val who = offer.name.ifEmpty { "A paired device" }
        val what = if (offer.fileCount == 1L) {
            offer.firstName.ifEmpty { "one file" }
        } else {
            "${offer.firstName.ifEmpty { "a file" }} and ${offer.fileCount - 1} more"
        }
        val notification = NotificationCompat.Builder(this, ARRIVAL_CHANNEL_ID)
            .setContentTitle("$who sent you $what")
            .setContentText(makeSizeReadable(offer.totalBytes))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    2,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // A fresh id per arrival, so two transfers in a row do not overwrite one another.
        manager.notify(ARRIVAL_ID_BASE + (System.currentTimeMillis() % 1000).toInt(), notification)
    }

    private fun notifyWaiting(peer: PairedPeer) {
        postArrivalStyle(
            "${peer.displayName} wants to receive a file",
            "Open this app and pick what to send over the hotspot.",
        )
    }

    private fun notifyNoDestination(peer: PairedPeer) {
        postArrivalStyle(
            "${peer.displayName} is trying to send you something",
            "No folder has been chosen to receive into yet. Open this app and pick one.",
        )
    }

    private fun postArrivalStyle(title: String, body: String) {
        val notification = NotificationCompat.Builder(this, ARRIVAL_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    3,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(ARRIVAL_ID_BASE + (System.currentTimeMillis() % 1000).toInt(), notification)
    }

    companion object {
        private const val CHANNEL_ID = "paired_presence"
        private const val ARRIVAL_CHANNEL_ID = "paired_arrival"
        private const val NOTIFICATION_ID = 9720
        private const val ARRIVAL_ID_BASE = 9800
        const val ACTION_STOP = "dev.spiegl.flyingcarpet.action.STOP_PRESENCE"
        const val ACTION_WAKE_HOTSPOT = "dev.spiegl.flyingcarpet.action.WAKE_HOTSPOT"
        private const val EXTRA_DEVICE_ID = "device_id"
        private const val EXTRA_SENDING = "sending"

        /**
         * Whether this service currently holds the presence and listener sockets. MainActivity
         * consults it so the two never both bind: the listener sets SO_REUSEADDR, so a second
         * bind would succeed rather than fail and the two would silently split the incoming
         * connections between them — the kind of fault that looks like "it works most of the
         * time".
         */
        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, PresenceService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PresenceService::class.java))
        }

        /**
         * Called from WakeReceiver, which may arrive with this app not running — so this
         * starts the service as well as telling it what to do.
         */
        fun wakeForHotspot(context: Context, deviceId: String, sending: Boolean) {
            context.startForegroundService(
                Intent(context, PresenceService::class.java)
                    .setAction(ACTION_WAKE_HOTSPOT)
                    .putExtra(EXTRA_DEVICE_ID, deviceId)
                    .putExtra(EXTRA_SENDING, sending)
            )
        }
    }
}
