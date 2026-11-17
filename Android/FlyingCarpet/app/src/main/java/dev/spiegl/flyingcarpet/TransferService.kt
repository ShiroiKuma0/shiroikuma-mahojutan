package dev.spiegl.flyingcarpet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Observer

// Fork: keeps a running transfer alive with the app in the background.
//
// A transfer runs in this process's coroutines, not in any screen, so in principle it does not
// care whether the Activity is in front. In practice this phone freezes a backgrounded app within
// seconds and kills it soon after: a send of 33 files from the file manager stopped dead at file 4
// the moment the app went behind another, the PC sat on "early eof" with its bars frozen, and the
// app came back as a fresh process with an empty log (白い熊, 2026-11-14). The partial WakeLock in
// MainViewModel keeps the CPU up; it does nothing against the freezer. A foreground service is the
// one thing an ordinary app can hold that the freezer respects.
//
// It holds nothing itself. It exists for exactly as long as some MainViewModel has a transfer
// running — MainViewModel.transferIsRunning's setter starts and stops it — and its notification
// mirrors the progress lines the main screen shows (TransferFeed), so the transfer can be watched
// from the shade and tapping it brings the screen back over the same transfer.
//
// `specialUse`, like PresenceService and AutomationDataService: the permission is already held,
// and Android 15+ caps dataSync at six hours in any twenty-four.
class TransferService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastPost = 0L
    private var pending = false

    private val observer = Observer<Any> { schedule() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        TransferFeed.progressBar.observeForever(observer)
        TransferFeed.totalProgressBar.observeForever(observer)
        TransferFeed.progressDetails.observeForever(observer)
        TransferFeed.progressTotalDetails.observeForever(observer)
        TransferLanes.pulse.observeForever(observer)
    }

    // NOT_STICKY: a transfer that died with the process is gone; a revived service would only
    // put up a notification for nothing.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        TransferFeed.progressBar.removeObserver(observer)
        TransferFeed.totalProgressBar.removeObserver(observer)
        TransferFeed.progressDetails.removeObserver(observer)
        TransferFeed.progressTotalDetails.removeObserver(observer)
        TransferLanes.pulse.removeObserver(observer)
        super.onDestroy()
    }

    // The bars move many times a second; the shade is redrawn at most once a second, which is
    // also under the platform's own rate limit for notification updates.
    private fun schedule() {
        if (pending) return
        val wait = (lastPost + UPDATE_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        pending = true
        handler.postDelayed({
            pending = false
            lastPost = SystemClock.elapsedRealtime()
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, build())
        }, wait)
    }

    private fun build(): Notification {
        val total = TransferFeed.progressTotalDetails.value.orEmpty()
        val file = TransferFeed.progressDetails.value.orEmpty()
        // The whole transfer when there is more than one file, otherwise the one file.
        var (line, percent) = if (total.isNotEmpty()) {
            total to (TransferFeed.totalProgressBar.value ?: 0)
        } else {
            file to (TransferFeed.progressBar.value ?: 0)
        }
        // Lanes — paired transfers over the network, several at once — have bars of their
        // own rather than TransferFeed's. The shade has room for one, so it shows the oldest
        // running lane and says how many there are.
        val lanes = TransferLanes.current()
        var title = "Transfer running"
        if (line.isEmpty() && lanes.isNotEmpty()) {
            val lane = lanes.firstOrNull { it.state == PairedLane.State.RUNNING } ?: lanes[0]
            val laneTotal = lane.progressTotalDetailsMut.value.orEmpty()
            val laneFile = lane.progressDetailsMut.value.orEmpty()
            val whole = laneTotal.isNotEmpty() && !laneTotal.startsWith("File 1 of 1")
            line = (if (whole) laneTotal else laneFile).replace("\n", " · ")
            percent = (if (whole) lane.totalProgressBarMut.value else lane.progressBarMut.value) ?: 0
            title = if (lanes.size == 1) {
                lane.title.value.orEmpty().ifEmpty { title }
            } else {
                "${lanes.size} transfers · ${lane.title.value.orEmpty()}"
            }
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(line.ifEmpty { "Connecting…" })
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
        if (line.isEmpty()) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, percent, false)
            if (total.isNotEmpty() && file.isNotEmpty()) {
                builder.setStyle(NotificationCompat.BigTextStyle().bigText("$total\n$file"))
            }
        }
        return builder.build()
    }

    private fun createChannel() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Transfer in progress",
                    // LOW: it is updated every second and must never make a sound.
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Shown while a transfer runs, so it keeps running in the background." }
            )
    }

    companion object {
        private const val CHANNEL_ID = "transfer_running"
        private const val NOTIFICATION_ID = 9730
        private const val UPDATE_MS = 1000L

        // How many ViewModels have a transfer running. Two can: the Activity's, and the one
        // PresenceService receives on. The setter that drives this runs on whichever thread the
        // transfer is on, hence the lock.
        private var active = 0

        @Synchronized
        fun transferStarted(context: Context) {
            active++
            if (active > 1) return
            try {
                context.startForegroundService(Intent(context, TransferService::class.java))
            } catch (e: Exception) {
                // Refused from the background (Android 12+); the transfer runs regardless, it
                // just has nothing keeping it alive if the app is left.
                Log.i("FlyingCarpet", "Could not start the transfer service: $e")
            }
        }

        @Synchronized
        fun transferStopped(context: Context) {
            if (active == 0) return
            active--
            if (active == 0) {
                context.stopService(Intent(context, TransferService::class.java))
            }
        }
    }
}
