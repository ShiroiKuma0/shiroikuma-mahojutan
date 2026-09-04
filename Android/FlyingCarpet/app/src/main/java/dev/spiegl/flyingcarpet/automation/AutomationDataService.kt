package dev.spiegl.flyingcarpet.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import dev.spiegl.flyingcarpet.Backup
import dev.spiegl.flyingcarpet.Cat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where a data export or import actually runs — the payload half of [AutomationProvider].
 *
 * ## Why a foreground service and not the provider call
 *
 * The call returns in milliseconds; this may not. Two hard reasons it cannot be done anywhere
 * cheaper:
 *
 * - **A binder call holds the caller.** 応用管理 is drawing a list; a long synchronous call would
 *   freeze its UI, report no progress and refuse cancellation.
 * - **A backgrounded app writing for any length of time is frozen mid-stream on this phone**, which
 *   yields a truncated archive underneath a success reply — the worst possible failure, because it
 *   is indistinguishable from a good backup until the day it is restored.
 *
 * ## The descriptor
 *
 * Already duplicated by [AutomationProvider] before it got here, because the original belongs to
 * the binder transaction and is closed the moment `call()` returns. This service owns the copy and
 * closes it in a `finally` — leaking one would hold the caller's file open indefinitely, and a
 * caller cannot checksum or encrypt a file that is still open.
 *
 * ## Cancelling here deletes nothing
 *
 * On the receiver's path (§1) a cancel must delete the `.part` file it was writing. Here the file
 * belongs to the **caller** — we only ever hold a descriptor into it — so unwinding means stopping
 * at a write boundary, closing our copy and answering `ERROR:cancelled`; what to do with the
 * half-written destination is 応用管理's own business, and it already discards a job it cancelled.
 */
class AutomationDataService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val jobId = intent?.getStringExtra(EXTRA_JOB) ?: return stop(startId)
        // Taken out of the handover map FIRST, so the descriptor has exactly one owner from the
        // moment it arrives and every path below unwinds through something that closes it. Drain it
        // after startForeground() instead and a throw there leaves the caller's file held open in a
        // map nothing will ever read again.
        val fd = HANDOVER.remove(jobId) ?: return stop(startId)
        val importing = intent.getBooleanExtra(EXTRA_IMPORTING, false)
        val replyAction = intent.getStringExtra(AutomationProvider.KEY_REPLY_ACTION)
        val replyPackage = intent.getStringExtra(AutomationProvider.KEY_REPLY_PACKAGE)
        val progressAction = intent.getStringExtra(AutomationProvider.KEY_PROGRESS_ACTION)

        val replied = AtomicBoolean(false)
        fun reply(result: String) {
            // Exactly one terminal answer per job, whatever path got here — a synchronous failure
            // and an asynchronous success must never both fire. The same guard the broadcast
            // contract has carried since the first sister app.
            if (!replied.compareAndSet(false, true)) return
            AutomationJobs.finish(jobId)
            if (replyAction.isNullOrEmpty() || replyPackage.isNullOrEmpty()) return
            sendBroadcast(
                Intent(replyAction).apply {
                    setPackage(replyPackage)
                    // Without this a caller that has been backgrounded never hears the answer, and
                    // on a clean phone the caller may not have been launched at all.
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    putExtra(AutomationProvider.KEY_JOB_ID, jobId)
                    putExtra(AutomationProvider.KEY_RESULT, result)
                },
            )
        }

        // Within 5 s of the service starting, or the system kills us for it — and caught rather
        // than trusted: a background start can be refused outright on API 31+, and a foreground
        // type disagreeing with the manifest throws here too. Either way the descriptor is ours to
        // close and the caller is owed a refusal, not a dead job.
        try {
            startForegroundCompat(importing)
        } catch (t: Throwable) {
            runCatching { fd.close() }
            reply("ERROR:foreground service refused: ${t.message ?: t.javaClass.simpleName}")
            return stop(startId)
        }

        scope.launch {
            try {
                fd.use { open ->
                    if (importing) {
                        runImport(open, ::reply)
                    } else {
                        runExport(
                            jobId = jobId,
                            fd = open,
                            items = intent.getStringExtra(AutomationProvider.KEY_ITEMS),
                            progressAction = progressAction,
                            replyPackage = replyPackage,
                            reply = ::reply,
                        )
                    }
                }
            } catch (t: Throwable) {
                reply("ERROR:${t.message ?: t.javaClass.simpleName}")
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun runExport(
        jobId: String,
        fd: ParcelFileDescriptor,
        items: String?,
        progressAction: String?,
        replyPackage: String?,
        reply: (String) -> Unit,
    ) {
        val cats = resolve(items) ?: run { reply("ERROR:unknown category in items: $items"); return }
        if (cats.isEmpty()) { reply("ERROR:no categories selected"); return }
        // The one §3 sender, shared with the receiver. The data door correlates on `job_id`, so it
        // is set in both `job_id` and `reply_id` — one progress reader then serves both doors.
        val progress = AutomationProgress(
            context = this,
            action = progressAction,
            target = replyPackage,
            correlationId = jobId,
            alsoAsJobId = true,
        )
        var written = 0L
        ParcelFileDescriptor.AutoCloseOutputStream(fd).use { out ->
            // Counted as it goes rather than stat'ed afterwards: the caller owns the file and we
            // may not be able to see it at all — it can be an anonymous pipe or a descriptor into a
            // directory this app cannot list.
            val counting = object : OutputStream() {
                override fun write(b: Int) { out.write(b); written++ }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    out.write(b, off, len); written += len
                }
            }
            Backup.export(
                context = this,
                cats = cats,
                out = counting,
                onProgress = { done, total, cat -> progress.send(done, total, cat, written) },
                isCancelled = { AutomationJobs.isCancelled(jobId) },
            )
        }
        if (AutomationJobs.isCancelled(jobId)) reply("ERROR:cancelled")
        else reply("OK:$written|${cats.size} categories")
    }

    /**
     * Receive the whole archive before touching anything — **spooled to disk**, not into memory.
     *
     * Reading a caller's descriptor into a byte array to sniff it is fine for a settings ZIP and
     * fatal for an app whose archive carries a corpus, so the bound belongs on disk rather than in
     * RAM. This app's own backup is a preferences dump plus 白い熊's imported `.ttf`/`.otf` files, so
     * it is nowhere near the size that forced the rule — but the spool costs a cache file and
     * removes the question entirely, and the contract's guarantee is what actually matters here:
     * **nothing is written until the whole archive has arrived and been checked.** A partial read
     * that failed halfway would otherwise import half an archive, and a half-restored app is worse
     * than one that refused.
     *
     * The spool goes in `cacheDir` — this app's own private storage, never the backup folder, so a
     * restore can never leave something behind that looks like a backup.
     */
    private fun runImport(fd: ParcelFileDescriptor, reply: (String) -> Unit) {
        val spool = File(cacheDir, "automation-import-${System.nanoTime()}.zip")
        try {
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                spool.outputStream().use { input.copyTo(it) }
            }
            if (spool.length() == 0L) { reply("ERROR:empty archive"); return }
            // Every category the archive actually carries, not every category we know about: asking
            // for one the archive lacks is how a restore reports success over nothing.
            val present = Backup.categoriesIn(spool)
            if (present.isEmpty()) { reply("ERROR:archive carries no categories"); return }
            // Backup.import refuses a desktop-written archive by throwing; the message it throws is
            // the one worth showing, and onStartCommand's catch turns it into ERROR:<that message>.
            val result = Backup.import(this, spool, present)
            if (result.lines.isEmpty()) {
                reply("ERROR:nothing restored: ${result.errors.joinToString(", ")}")
                return
            }
            val failed = if (result.errors.isEmpty()) "" else " (${result.errors.size} failed)"
            // The caller force-stops us straight after this. That is deliberate and belongs on its
            // side: a running process writes its cached SharedPreferences back out at orderly
            // shutdown and would silently undo the import that just happened.
            reply("OK:${result.lines.size} restored$failed")
        } finally {
            // Never left behind, on any path — a stale spool is a copy of 白い熊's settings sitting
            // in the cache for no reason.
            runCatching { spool.delete() }
        }
    }


    /**
     * `items` absent means **this app's default set**, which here is every category: appearance,
     * the UI page's own look and the imported fonts are all authored, and none of them is the
     * large-derived-and-re-creatable kind the contract wants marked off.
     */
    private fun resolve(items: String?): Set<Cat>? {
        if (items.isNullOrBlank()) return Cat.values().toSet()
        val wanted = items.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val found = wanted.mapNotNull { Cat.byId(it) }
        return if (found.size == wanted.size) found.toSet() else null
    }

    /**
     * `specialUse` is an API 34 value, so which overload is correct is a **build-time** fact about
     * the platform we are running on, not a runtime preference. Below 34 the typed overload has no
     * type to be given; from 34 the untyped one is what throws. This is the one place where EMUI
     * reporting `SDK_INT = 31` on a platform based on Android 13 is a live hazard rather than a
     * curiosity — so the branch asks only about API availability, and the caller catches either
     * arm failing rather than trusting the answer.
     */
    private fun startForegroundCompat(importing: Boolean) {
        val notification = notification(importing)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(importing: Boolean): Notification {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "自動化データ", NotificationManager.IMPORTANCE_LOW),
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(if (importing) "データを戻しています" else "データを書き出しています")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
    }

    private fun stop(startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "automation_data"
        private const val NOTIFICATION_ID = 9714
        private const val EXTRA_JOB = "job"
        private const val EXTRA_IMPORTING = "importing"

        /**
         * The descriptor's way across, because an Intent is the wrong vehicle for one.
         *
         * A `ParcelFileDescriptor` in an Intent extra is duplicated by the system on delivery and
         * the copy's lifetime stops being ours to reason about. Handing it through a map keyed by
         * the job id keeps exactly one open descriptor with exactly one owner — the service, which
         * closes it in a `finally`.
         */
        private val HANDOVER = ConcurrentHashMap<String, ParcelFileDescriptor>()

        fun start(
            context: Context,
            jobId: String,
            fd: ParcelFileDescriptor,
            importing: Boolean,
            extras: Bundle?,
        ) {
            HANDOVER[jobId] = fd
            context.startForegroundService(
                Intent(context, AutomationDataService::class.java).apply {
                    putExtra(EXTRA_JOB, jobId)
                    putExtra(EXTRA_IMPORTING, importing)
                    putExtra(AutomationProvider.KEY_ITEMS, extras?.getString(AutomationProvider.KEY_ITEMS))
                    putExtra(
                        AutomationProvider.KEY_REPLY_ACTION,
                        extras?.getString(AutomationProvider.KEY_REPLY_ACTION),
                    )
                    putExtra(
                        AutomationProvider.KEY_REPLY_PACKAGE,
                        extras?.getString(AutomationProvider.KEY_REPLY_PACKAGE),
                    )
                    putExtra(
                        AutomationProvider.KEY_PROGRESS_ACTION,
                        extras?.getString(AutomationProvider.KEY_PROGRESS_ACTION),
                    )
                },
            )
        }

        /**
         * Drop a descriptor that will never be collected, because the service it was staged for
         * could not be started. Without this the map would hold the caller's file open for the life
         * of the process — the exact leak the `finally` in [onStartCommand] exists to prevent.
         */
        fun abandon(jobId: String) {
            HANDOVER.remove(jobId)
        }
    }
}
