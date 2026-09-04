package dev.spiegl.flyingcarpet.automation

import android.content.Context
import android.content.Intent
import dev.spiegl.flyingcarpet.Cat

/**
 * The §3 progress sender — **one** implementation, used by both automation doors.
 *
 * The receiver (§1) and the data door (§2a) send the same shape and obey the same watchdog, so they
 * share this rather than each carrying a copy: two implementations of one watchdog drift, and the
 * one that drifts is always the one nobody is looking at. What differs between the doors is only
 * the correlation id's name, which is why that is the parameter.
 *
 * Real counts, never a percentage — 白い熊's explicit requirement. `item` rides on every message
 * because the caller's panel highlights the row it names and cannot work that out from `current`,
 * which is only ever "whatever we happen to be counting at this moment".
 */
class AutomationProgress(
    private val context: Context,
    private val action: String?,
    private val target: String?,
    private val correlationId: String,
    /**
     * The data door correlates on `job_id` while §3 names the field `reply_id`; when this is set
     * both extras carry the same value, so one progress reader serves both doors.
     */
    private val alsoAsJobId: Boolean = false,
) {

    private var lastAt = 0L

    private val appLabel: String by lazy {
        runCatching {
            context.packageManager.getApplicationLabel(context.applicationInfo).toString()
        }.getOrDefault(context.packageName)
    }

    /**
     * One category's worth of progress. [done] is the **position** of the category being written —
     * 「区分 4/9 — Imported fonts」 means fonts is category 4, not that four are finished — and
     * [total] is the number actually being exported after `items` filtering, which is what lets the
     * caller recognise the count as a walk through its own list.
     *
     * [bytes] is the second counter, sent when we know it. There is deliberately no `bytes_total`:
     * nothing knows a ZIP's finished size until it is closed, and an invented total is worse than
     * none.
     */
    fun send(done: Int, total: Int, cat: Cat, bytes: Long? = null) {
        if (action.isNullOrEmpty() || target.isNullOrEmpty()) return
        val now = System.currentTimeMillis()
        // At most one every 500 ms — but the completion one always goes out.
        if (done < total && now - lastAt < MIN_INTERVAL_MS) return
        lastAt = now
        context.sendBroadcast(
            Intent(action).apply {
                setPackage(target)
                // Without this a backgrounded or stopped caller never hears a word of it.
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra(EXTRA_REPLY_ID, correlationId)
                if (alsoAsJobId) putExtra(EXTRA_JOB_ID, correlationId)
                putExtra(EXTRA_APP, appLabel)
                putExtra(EXTRA_ITEM, cat.id)
                putExtra(EXTRA_TEXT, "区分 $done/$total — ${cat.label}")
                putExtra(EXTRA_CURRENT, done.toLong())
                putExtra(EXTRA_TOTAL, total.toLong())
                putExtra(EXTRA_UNIT, "区分")
                bytes?.let { putExtra(EXTRA_BYTES, it) }
            },
        )
    }

    companion object {
        // §3's extra names — bare, and shared verbatim by every sister app.
        const val EXTRA_REPLY_ID = "reply_id"
        const val EXTRA_JOB_ID = "job_id"
        const val EXTRA_APP = "app"
        const val EXTRA_ITEM = "item"
        const val EXTRA_TEXT = "text"
        const val EXTRA_CURRENT = "current"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_UNIT = "unit"
        const val EXTRA_BYTES = "bytes"

        private const val MIN_INTERVAL_MS = 500L
    }
}
