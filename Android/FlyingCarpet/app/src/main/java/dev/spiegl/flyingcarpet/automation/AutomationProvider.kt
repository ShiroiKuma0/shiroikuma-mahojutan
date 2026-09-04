package dev.spiegl.flyingcarpet.automation

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import dev.spiegl.flyingcarpet.AutomationAuth
import dev.spiegl.flyingcarpet.Backup
import dev.spiegl.flyingcarpet.Cat
import org.json.JSONArray
import org.json.JSONObject

/**
 * The data door: export this app's own state, and put it back, for a caller we can identify.
 *
 * ## Why a provider and not the broadcast receiver next to it
 *
 * Two reasons, and the first is the whole point of the v2 redesign.
 *
 * **A broadcast cannot tell you who sent it.** v1's answer to that was a shared secret, which
 * cannot survive the wipe this feature exists to recover from. A provider gets the caller's
 * identity from the framework for free — see [AutomationCallers] for what is actually checked, and
 * why a `shiroikuma.*` prefix would have been *weaker* than the token it replaced.
 *
 * **A list needs a synchronous answer.** 応用管理 draws a row per installed app before any export
 * exists; a broadcast round trip per app to fill a list is the wrong shape entirely.
 *
 * ## `import` lives ONLY here
 *
 * It never gets a broadcast action. An import overwrites this app's settings, and
 * [dev.spiegl.flyingcarpet.StateExportReceiver] is `exported="true"` with no permission — an import
 * there would let any app on the phone flatten any sister app.
 *
 * ## What does NOT happen here
 *
 * The payload. `call()` validates, starts a foreground service and returns — megabytes over minutes
 * inside a binder call would block the caller, report no progress, refuse cancellation and die
 * silently if this process were killed. The bytes go through a file descriptor the caller opened,
 * and the terminal answer comes back on the broadcast the family already proved on EMUI.
 *
 * ## Why a descriptor and not a path
 *
 * Because a backup is not a stable directory while it is being assembled. 応用管理 writes into a
 * temporary path and renames on commit; it encrypts and checksums **per file it knows about**. A
 * file this app dropped into that directory itself would be renamed out from under it, would sit in
 * plaintext inside an encrypted backup, and would be unverified rather than verified-and-failing.
 * A descriptor is also a capability that **expires when it is closed**.
 *
 * It also means the automation path no longer needs `MANAGE_EXTERNAL_STORAGE`. This app still holds
 * it for the Export/Import page's own folder and for the receiver's absolute `path` extra, but the
 * data door does not depend on it.
 */
class AutomationProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    /**
     * Every method answers a [Bundle] with [KEY_RESULT] — `OK…` or `ERROR:…`, the same vocabulary
     * the broadcast contract uses, so a caller has one grammar to parse rather than two.
     *
     * A refusal is returned, never thrown: an exception across a binder reaches the caller as a
     * `RuntimeException` with our stack trace in it, which tells 白い熊 nothing and tells a
     * misbehaving caller rather more than it should.
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return fail("ERROR:not ready")

        // WHO, before WHAT. A caller we cannot identify gets the same answer whatever it asked for.
        when (val verdict = AutomationCallers.verify(ctx, callingPackage)) {
            is AutomationCallers.Verdict.Refused -> return fail(verdict.why)
            AutomationCallers.Verdict.Allowed -> Unit
        }
        // Then the app's own switches — a token is ignored unless this app asks for one.
        AutomationAuth.refuse(ctx, extras?.getString(KEY_TOKEN))?.let { return fail(it) }

        return when (method) {
            METHOD_DESCRIBE -> ok(describe(ctx))
            METHOD_EXPORT -> start(ctx, extras, importing = false)
            METHOD_IMPORT -> start(ctx, extras, importing = true)
            METHOD_CANCEL -> {
                AutomationJobs.cancel(extras?.getString(KEY_JOB_ID))
                ok("OK:cancelled")
            }
            else -> fail("ERROR:unknown method: $method")
        }
    }

    /**
     * What this app would export, answered without exporting anything.
     *
     * Returned from the call rather than written into the archive, deliberately: 応用管理 must draw
     * a row before an export exists, and at restore must judge compatibility **before** streaming
     * into an app that would reject it — which it cannot do if the header is buried inside an
     * encrypted archive.
     *
     * `contains` is every category, because this app has no opt-out one: appearance, the UI page's
     * own look and the imported font files are all authored by 白い熊 and none of them can be made
     * again from anything else in the backup.
     */
    private fun describe(ctx: Context): String {
        val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        // `longVersionCode` is the modern accessor, but our codes are five digits and the caller's
        // header field is an int — the deprecated one is the value that belongs in it.
        @Suppress("DEPRECATION")
        val versionCode = pkg.versionCode
        val header = JSONObject()
            .put("app_id", ctx.packageName)
            .put("version_code", versionCode)
            .put("version_name", pkg.versionName ?: "")
            .put("format", FORMAT)
            .put("min_format_readable", MIN_FORMAT_READABLE)
            // This app writes its defaults lazily and [Backup.import] merges per key, so an import
            // into a never-launched install is safe and wants no special ordering.
            .put("requires_launch_first", false)
            .put("contains", JSONArray(Cat.values().map { it.label }))
        return "OK:$header"
    }

    /**
     * Hand the descriptor to a foreground service and get out of the way.
     *
     * The descriptor is **duplicated** before it leaves this method. The one in [extras] belongs to
     * the binder transaction and is closed when `call()` returns; a service reading it afterwards
     * would find it shut. That is a bug you only see under load, so it is not left to the service
     * to remember.
     */
    private fun start(ctx: Context, extras: Bundle?, importing: Boolean): Bundle {
        @Suppress("DEPRECATION")
        val fd = extras?.getParcelable<ParcelFileDescriptor>(KEY_FD)
            ?: return fail("ERROR:no descriptor")
        val dup = runCatching { fd.dup() }.getOrNull() ?: return fail("ERROR:descriptor unusable")
        val jobId = AutomationJobs.begin()
        // A background foreground-service start can be refused by the platform, and that must reach
        // the caller as a refusal rather than as our stack trace across the binder. Unwind fully
        // when it does: the dup we made is ours the moment we made it, and a job nobody will ever
        // finish would sit in the registry for the life of the process.
        val started = runCatching {
            AutomationDataService.start(ctx, jobId, dup, importing, extras)
        }
        if (started.isFailure) {
            AutomationDataService.abandon(jobId)
            AutomationJobs.finish(jobId)
            runCatching { dup.close() }
            val why = started.exceptionOrNull()
            return fail("ERROR:${why?.message ?: why?.javaClass?.simpleName ?: "service refused"}")
        }
        return ok("OK:$jobId")
    }

    private fun ok(result: String) = Bundle().apply { putString(KEY_RESULT, result) }
    private fun fail(why: String) = Bundle().apply { putString(KEY_RESULT, why) }

    // A provider that is only ever `call()`ed still has to answer these. Refusing loudly beats
    // returning an empty cursor, which reads downstream as "there is no data" rather than "wrong
    // door".
    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? =
        throw UnsupportedOperationException("automation is call() only")
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("automation is call() only")
    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int =
        throw UnsupportedOperationException("automation is call() only")
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int =
        throw UnsupportedOperationException("automation is call() only")

    companion object {
        const val METHOD_DESCRIBE = "describe"
        const val METHOD_EXPORT = "export"
        const val METHOD_IMPORT = "import"
        const val METHOD_CANCEL = "cancel"

        const val KEY_RESULT = "result"
        const val KEY_FD = "fd"
        const val KEY_TOKEN = "token"
        const val KEY_JOB_ID = "job_id"
        const val KEY_ITEMS = "items"
        const val KEY_REPLY_ACTION = "reply_action"
        const val KEY_REPLY_PACKAGE = "reply_package"
        const val KEY_PROGRESS_ACTION = "progress_action"

        /**
         * This app's archive format — the same number [Backup.FORMAT_VERSION] stamps into
         * `manifest.json`, so the header a caller reads here and the archive it later hands back
         * can never disagree about what version they are talking about.
         */
        const val FORMAT = Backup.FORMAT_VERSION

        /**
         * The oldest archive this build can still read.
         *
         * Version skew has a direction: old data into a newer app is normally fine, because an app
         * migrates its own storage; newer data into an older app is not. This field is what lets a
         * caller refuse the second case at discovery time, before anything is streamed.
         */
        const val MIN_FORMAT_READABLE = 1
    }
}
