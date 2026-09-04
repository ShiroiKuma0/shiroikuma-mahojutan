package dev.spiegl.flyingcarpet.automation

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The automation work that is currently running, and the flag each piece of it watches to stop.
 *
 * Both doors cancel through here — the provider's `cancel` method (§2a), which always names a
 * `job_id` it was handed, and the receiver's `CANCEL_EXPORT` broadcast (§1), whose `reply_id` is
 * optional because "the export you are running" is unambiguous when only one may run at a time.
 *
 * Process-local and **never persisted**: a cancellation that outlived the process it belonged to
 * would wedge the next export for good, which is the same class of bug as persisting an
 * "export in progress" flag.
 */
object AutomationJobs {

    private val cancelled = ConcurrentHashMap<String, Boolean>()

    /**
     * The receiver's single in-flight export, so a `CANCEL_EXPORT` that names no `reply_id` has
     * something to aim at. The provider's jobs are deliberately not reachable this way: a broadcast
     * cancel from 自由作業盤 must not tear down a data export 応用管理 started through the other door.
     */
    @Volatile
    private var broadcastJob: String? = null

    /** A provider job — the caller gets this id back as `OK:<job_id>` and cancels by it. */
    fun begin(): String = UUID.randomUUID().toString().also { cancelled[it] = false }

    /** A receiver job, keyed by the caller's own `reply_id` so an explicit cancel can name it. */
    fun beginBroadcast(replyId: String): String {
        cancelled[replyId] = false
        broadcastJob = replyId
        return replyId
    }

    /**
     * Ask a job to stop. A no-op for an id that is finished or was never real.
     *
     * Deliberately silent: a cancel arriving after the work completed is the normal race, not an
     * error, and answering it as one would make every well-behaved caller look broken.
     */
    fun cancel(jobId: String?) {
        jobId?.let { cancelled.computeIfPresent(it) { _, _ -> true } }
    }

    /** `CANCEL_EXPORT`: the run named, or — when none is named — whichever export is in flight. */
    fun cancelBroadcast(replyId: String?) {
        cancel(replyId?.takeIf { it.isNotEmpty() } ?: broadcastJob)
    }

    /** Polled at write boundaries — never mid-write, so a cancelled archive is never half a file. */
    fun isCancelled(jobId: String): Boolean = cancelled[jobId] == true

    fun finish(jobId: String) {
        cancelled.remove(jobId)
        if (broadcastJob == jobId) broadcastJob = null
    }
}
