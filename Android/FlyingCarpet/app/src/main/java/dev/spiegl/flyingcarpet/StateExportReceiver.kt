package dev.spiegl.flyingcarpet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import dev.spiegl.flyingcarpet.automation.AutomationJobs
import dev.spiegl.flyingcarpet.automation.AutomationProgress
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The sister-app **state-export automation contract** — the wire shape every 白い熊 app exposes so
 * 自由作業盤's 保存復元 project can back them all up headlessly in one run (reference implementations:
 * renrakusaki's `BackupContactsReceiver`, the EMUI-proven round-trip, plus 自由作業盤's and kxkb's own
 * `StateExportReceiver`).
 *
 * - `<pkg>.action.EXPORT_STATE`: run the ordinary category-ZIP export ([Backup]) with no Activity and
 *   no user interaction. Extras (all String): `token` (optional in v2 — see [AutomationAuth]), `path`
 *   (optional absolute directory, wins over the app's configured backup folder), `items` (optional
 *   comma list of [Cat] ids; absent/empty = every category, which is also this app's default set),
 *   `progress_action` (optional — see below), plus the reply trio `reply_action` / `reply_package` /
 *   `reply_id`.
 * - `<pkg>.action.LIST_CATEGORIES`: instant category enumeration for the caller's checkbox picker.
 *   `id<TAB>label` per line; this app's categories are flat, so no third (parent-id) field is ever
 *   emitted, and none is opt-out, so no fourth (`off`) field either.
 * - `<pkg>.action.CANCEL_EXPORT`: stop the export in flight. Fire-and-forget — it never replies, and
 *   it is a silent no-op when nothing is running. See [cancel] for the four obligations it carries.
 *
 * **This receiver is the unauthenticated half of the surface, deliberately.** In v1 the token was
 * the gate; in v2 the switch ships on and the token is opt-in, and what makes that safe is that
 * everything here only ever *writes where it was told to* and reports what it did. Anything that
 * moves data through a caller-supplied descriptor — and the only `import` there is — lives behind
 * [dev.spiegl.flyingcarpet.automation.AutomationProvider], which knows exactly who is calling.
 *
 * **ONE ZIP per request** — the single file named by [Backup.exportFileName] is the whole backup,
 * with every selected category as an entry inside it. Nothing else is written next to it, and it is
 * written **atomically**: the bytes go to `<name>.part` and that is renamed into place only once the
 * archive is closed and complete. 白い熊 keeps every app's backups in one directory sorted by date,
 * so a truncated file left behind by a killed or cancelled export would silently become "the latest
 * backup" of this app.
 *
 * Reply: a FRESH broadcast to `reply_package` with action `reply_action`, extras `reply_id` (echoed
 * verbatim) and `result` = `OK:<path>|<bytes>|<human size>|<n> categories` (EXPORT_STATE), `OK:` plus
 * the `id<TAB>label` lines (LIST_CATEGORIES), or `ERROR:<reason>`. Exactly one terminal reply,
 * single-fire guarded by an [AtomicBoolean]. **No binders** (`ResultReceiver` / `PendingIntent` /
 * `Messenger`) and **no reliance on the ordered-broadcast result** — EMUI severs both between
 * third-party apps (verified on 白い熊's Mate XT, 2026-07-23); the plain reply broadcast is the only
 * channel that works. [Intent.FLAG_INCLUDE_STOPPED_PACKAGES] so a backgrounded or stopped caller
 * still hears us.
 *
 * Progress: while exporting, plain broadcasts to `reply_package` with action `progress_action` —
 * extras `reply_id`, `app` (display label), `item` (the [Cat] id being written, so the caller's panel
 * highlights the right row), `text` (numbers-first, e.g. `区分 2/3 — Imported fonts`), and the
 * structured `current`/`total` (long) + `unit` (String). Real counts, never a percentage; throttled
 * to at most one every 500 ms, with the completion one always sent.
 *
 * **Why `goAsync()` and no foreground service here.** The contract reserves the service for exports
 * that can outlast the broadcast window (~10 s foregrounded, ~60 s not). This app's export is the
 * `shiroikuma_ui` preferences dump plus whatever `.ttf`/`.otf` files 白い熊 has added — three
 * categories, a fraction of a second in practice, with no database, no media library and no
 * thousands of rows anywhere in it. It cannot reach that window, so the service (and the wakelock
 * and battery-exemption prompt that come with it) would be machinery guarding nothing. The data
 * door's [dev.spiegl.flyingcarpet.automation.AutomationDataService] is a foreground service because
 * it writes into a caller's descriptor and is not ours to bound.
 */
class StateExportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val action = intent.action ?: return
        val token = intent.getStringExtra(EXTRA_TOKEN)
        val replyAction = intent.getStringExtra(EXTRA_REPLY_ACTION)?.trim().orEmpty()
        val replyPackage = intent.getStringExtra(EXTRA_REPLY_PACKAGE)?.trim().orEmpty()
        val replyId = intent.getStringExtra(EXTRA_REPLY_ID)?.trim().orEmpty()
        val progressAction = intent.getStringExtra(EXTRA_PROGRESS_ACTION)?.trim().orEmpty()
        val pathOverride = intent.getStringExtra(EXTRA_PATH)?.trim().orEmpty()
        val items = intent.getStringExtra(EXTRA_ITEMS)?.trim().orEmpty()

        // CANCEL is answered before the reply machinery is even built, because it must never send
        // one: the single terminal reply belongs to the export it stops, not to the cancel itself.
        if (action == cancelExportAction(app)) {
            cancel(app, token, replyId)
            return
        }

        val replied = AtomicBoolean(false)
        fun reply(result: String) {
            if (replyAction.isEmpty() || replyPackage.isEmpty()) return
            if (!replied.compareAndSet(false, true)) return
            app.sendBroadcast(
                Intent(replyAction).apply {
                    setPackage(replyPackage)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    putExtra(EXTRA_REPLY_ID, replyId)
                    putExtra(EXTRA_RESULT, result)
                },
            )
        }

        // One gate, in one place — and a token this app does not require is ignored rather than
        // refused, so a caller configured last year still works after the switch was turned off.
        AutomationAuth.refuse(app, token)?.let {
            reply(it)
            return
        }

        when (action) {
            listCategoriesAction(app) ->
                reply("OK:" + Cat.values().joinToString("\n") { "${it.id}\t${it.label}" })

            exportStateAction(app) -> {
                val cats: Set<Cat> = if (items.isEmpty()) {
                    Cat.values().toSet()
                } else {
                    val ids = items.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val resolved = ids.mapNotNull { Cat.byId(it) }
                    if (resolved.size != ids.size) {
                        reply("ERROR:unknown category in items: $items")
                        return
                    }
                    resolved.toSet()
                }
                // One export at a time, process-local and released in a `finally` — never
                // persisted. A persisted flag survives one crash and then wedges the app for good.
                if (!RUNNING.compareAndSet(false, true)) {
                    reply("ERROR:export already running")
                    return
                }
                exportAsync(app, cats, pathOverride, progressAction, replyPackage, replyId, ::reply)
            }

            else -> reply("ERROR:unknown action: $action")
        }
    }

    /**
     * `CANCEL_EXPORT` — stop the run in flight, and answer nobody.
     *
     * The four obligations the contract puts on a cancel are met between here and [exportAsync]:
     * the flag is polled at category boundaries so the write unwinds rather than being torn up
     * mid-entry; the `.part` file is deleted in [exportAsync]'s `finally`; `ERROR:cancelled` goes
     * out as the terminal reply for the **original** request, through the same single-fire guard so
     * it can never double-fire with a success; and there is no foreground service or wakelock on
     * this path to stop (see the class note).
     *
     * **Safe to send at any time.** A cancel that arrives when nothing is running, or after the
     * export already finished, is a silent no-op — not an error, not a reply, not a crash. 自由作業盤
     * fires it whenever 白い熊 presses 中止, without knowing how far we got.
     */
    private fun cancel(app: Context, token: String?, replyId: String) {
        // Silent even when refused: a cancel has no reply channel to complain down, and a caller
        // that may not use this app's automation at all must not be answered as though it did.
        if (AutomationAuth.refuse(app, token) != null) return
        AutomationJobs.cancelBroadcast(replyId)
    }

    /** Holds the broadcast open with `goAsync()` and runs the real export off the main thread. */
    private fun exportAsync(
        app: Context,
        cats: Set<Cat>,
        pathOverride: String,
        progressAction: String,
        replyPackage: String,
        replyId: String,
        reply: (String) -> Unit,
    ) {
        // The one §3 sender, shared with the data door — see [AutomationProgress] for why there is
        // deliberately not a second copy of it here. This door correlates on `reply_id` alone.
        val progress = AutomationProgress(app, progressAction, replyPackage, replyId)

        // Keyed by the caller's own reply_id so an explicit CANCEL_EXPORT can name this run; a
        // cancel that names nothing finds it anyway, because only one export may be in flight.
        val jobId = AutomationJobs.beginBroadcast(replyId.ifEmpty { BROADCAST_JOB })
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // Declared out here so the `finally` can remove a partial file whatever went wrong —
            // a failure, a cancel, or an exception on the way to either.
            var part: File? = null
            try {
                // Directory precedence: the `path` extra → the app's configured folder → error.
                val dirPath = pathOverride.ifEmpty { Backup.exportDir(app).orEmpty() }
                if (dirPath.isEmpty()) {
                    reply("ERROR:no-directory")
                    return@launch
                }
                // We declare MANAGE_EXTERNAL_STORAGE for exactly this; it may still be ungranted.
                // Checked rather than discovered by failing, because `ERROR:no-storage-access` is
                // the exact string 自由作業盤 keys on to offer the grant button on the failed row.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                    reply("ERROR:no-storage-access")
                    return@launch
                }
                val dir = File(dirPath)
                dir.mkdirs()
                if (!dir.isDirectory) {
                    reply("ERROR:not a directory: $dirPath")
                    return@launch
                }
                val file = File(dir, Backup.exportFileName())
                // Atomic publish: write beside the final name, rename only once the ZIP is closed
                // and complete. Nothing that reads the backup directory ever sees a half-archive.
                val partFile = File(dir, file.name + PART_SUFFIX).also { part = it }
                val result = partFile.outputStream().use { out ->
                    Backup.export(
                        context = app,
                        cats = cats,
                        out = out,
                        onProgress = { done, total, cat -> progress.send(done, total, cat) },
                        isCancelled = { AutomationJobs.isCancelled(jobId) },
                    )
                }
                if (AutomationJobs.isCancelled(jobId)) {
                    // Obligations 2 and 3: the partial goes in the `finally` below, and the run's
                    // one terminal reply says why it ended — sent even though 自由作業盤 stopped
                    // listening the moment it pressed 中止, because the reply is what proves the
                    // export really ended rather than carrying on unseen.
                    reply("ERROR:cancelled")
                    return@launch
                }
                val written = cats.size - result.errors.size
                if (written <= 0) {
                    reply("ERROR:every category failed: ${result.errors.joinToString(", ")}")
                    return@launch
                }
                if (!partFile.renameTo(file)) {
                    reply("ERROR:could not finish writing ${file.name}")
                    return@launch
                }
                part = null
                val bytes = file.length()
                val failed = if (result.errors.isEmpty()) "" else " (${result.errors.size} failed)"
                reply("OK:${file.absolutePath}|$bytes|${Backup.humanSize(bytes)}|$written categories$failed")
            } catch (e: Exception) {
                reply("ERROR:${e.message ?: e.javaClass.simpleName}")
            } finally {
                // A cancelled, failed or killed export leaves the backup directory exactly as it
                // found it. Only a complete archive was ever given the real name.
                part?.let { runCatching { it.delete() } }
                AutomationJobs.finish(jobId)
                RUNNING.set(false)
                pending.finish()
            }
        }
    }

    companion object {
        // `<pkg>.action.…` — derived from the installed applicationId, exactly as the manifest
        // declares them with `${applicationId}` (this project generates no BuildConfig constant here).
        fun exportStateAction(context: Context): String = "${context.packageName}.action.EXPORT_STATE"

        fun listCategoriesAction(context: Context): String = "${context.packageName}.action.LIST_CATEGORIES"

        fun cancelExportAction(context: Context): String = "${context.packageName}.action.CANCEL_EXPORT"

        // Contract extras — deliberately bare names, shared verbatim by every sister app.
        const val EXTRA_TOKEN = "token"
        const val EXTRA_PATH = "path"
        const val EXTRA_ITEMS = "items"
        const val EXTRA_PROGRESS_ACTION = "progress_action"
        const val EXTRA_REPLY_ACTION = "reply_action"
        const val EXTRA_REPLY_PACKAGE = "reply_package"
        const val EXTRA_REPLY_ID = "reply_id"
        const val EXTRA_RESULT = "result"

        // The progress extras and their 500 ms throttle belong to AutomationProgress, which both
        // doors send through — see the note there on why there is only one of them.

        /** The in-progress name. Never matched by [Backup.isBackupFileName], which wants `.zip`. */
        private const val PART_SUFFIX = ".part"

        /** Stand-in job key for the (contract-violating) caller that sends no `reply_id`. */
        private const val BROADCAST_JOB = "broadcast"

        /**
         * One export at a time. Process-local, and released in a `finally` — see the note at the
         * guard itself for why it is never persisted.
         */
        private val RUNNING = AtomicBoolean(false)
    }
}
