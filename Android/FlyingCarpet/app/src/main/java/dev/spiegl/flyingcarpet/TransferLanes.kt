package dev.spiegl.flyingcarpet

import android.app.Application
import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

// Fork: several paired transfers at once.
//
// A paired transfer over a shared network is one TCP connection with its own Noise session,
// so nothing on the wire ever stopped two of them running side by side — only the app did, by
// keeping every transfer's state in the one MainViewModel. A lane is that state, once per
// transfer (白い熊, 2026-11-17).
//
// The rules, which the desktop app keeps identically (core/src/lanes.rs):
//
//   * Up to MAX_LANES lanes run at once.
//   * A send to a device that already has a lane with this one — in either direction — waits
//     its turn behind it, and so does a send that would be the fifth. A waiting send keeps its
//     own files, shows on screen as queued, and can be cancelled.
//   * An incoming transfer that would be the fifth is refused as busy. One from a device that
//     already has a lane is accepted: only the sending side queues.
//   * Hotspot and password-mode transfers stay on the single slot (MainViewModel) and exclude
//     the lanes entirely, both ways: a hotspot takes the radio the lanes are using, and a
//     lane cannot run on a network this device is about to leave.

/** One paired shared-network transfer: its socket, its files, its progress, its Cancel. */
class PairedLane(
    override val app: Application,
    val sending: Boolean,
    peerId: String?,
    peerName: String,
    private val log: (String) -> Unit,
    private val ask: suspend (String, String, Boolean, Boolean) -> FileConflictAnswer =
        { _, _, _, _ -> FileConflictAnswer(FileConflictChoice.Skip, false) },
) : TransferLane {

    enum class State { NEW, QUEUED, RUNNING, DONE }

    val id: Long = nextId.incrementAndGet()

    /** Null on an incoming lane until its offer has said who is calling. */
    @Volatile
    var peerId: String? = peerId

    @Volatile
    var peerName: String = peerName
        set(value) {
            field = value
            title.postValue(heading())
        }

    @Volatile
    var state: State = State.NEW

    /** "→ Name" when sending, "← Name" when receiving. */
    val title = MutableLiveData(heading())

    /** Why a queued lane is waiting; empty once it runs. */
    val waiting = MutableLiveData("")

    /** Opened by the registry when this lane may start. Cancelled with the lane. */
    internal val gate = CompletableDeferred<Unit>()

    var job: Job? = null

    @Volatile
    var cancelled = false
        private set

    private fun heading(): String =
        (if (sending) "→ " else "← ") + peerName.ifEmpty { "A paired device" }

    // ── what the wire pipeline reads (TransferLane) ───────────────────────────────────────

    override lateinit var mode: Mode
    override var connectionMode: ConnectionMode = ConnectionMode.SharedNetwork
    override lateinit var psk: ByteArray
    override var files: MutableList<DocumentFile> = mutableListOf()
    override var fileStreams: MutableList<InputStream> = mutableListOf()
    override var filePaths: MutableList<String> = mutableListOf()
    override lateinit var receiveDir: Uri
    override val hasReceiveDir: Boolean get() = this::receiveDir.isInitialized
    override var safCache: SafDirectoryCache? = null
    override lateinit var client: Socket
    override lateinit var inputStream: InputStream
    override lateinit var outputStream: OutputStream
    override var pairedSession: PairedSession? = null
    override var conflictRule: ConflictRule? = null
    override var peerIsFork = false
    override var totals: Totals? = null

    // Each lane has its own bars. Every post also nudges TransferLanes.pulse, which is what the
    // foreground notification watches — it cannot observe a set of lanes that comes and goes.
    override val progressBarMut: MutableLiveData<Int> = Pulsing(0)
    override val progressDetailsMut: MutableLiveData<String> = Pulsing("")
    override val progressTotalDetailsMut: MutableLiveData<String> = Pulsing("")
    override val totalProgressBarMut: MutableLiveData<Int> = Pulsing(0)

    private class Pulsing<T>(initial: T) : MutableLiveData<T>(initial) {
        override fun postValue(value: T) {
            super.postValue(value)
            TransferLanes.pulse.postValue(System.currentTimeMillis())
        }
    }

    /**
     * Every line a lane says carries its device's name, because with several lanes writing
     * into one log a bare "Sending took 12s" belongs to nobody. The rule lines the single
     * slot draws between files are dropped: interleaved, they separate nothing.
     */
    override fun outputText(msg: String) {
        val line = msg.trim()
        if (line.isEmpty() || line.all { it == '=' }) return
        log("[${peerName.ifEmpty { "paired device" }}] $line")
    }

    /** A lane never has a hotspot: it runs on the network both devices are already on. */
    override fun isHosting(): Boolean = false

    override suspend fun askAboutExistingFile(
        name: String,
        identical: Boolean,
        moreFiles: Boolean,
    ): FileConflictAnswer = ask(peerName, name, identical, moreFiles)

    // ── running ───────────────────────────────────────────────────────────────────────────

    /**
     * Runs the transfer on the socket already in [client] — dialled by connectToPairedPeer(),
     * or handed over by the listener. The socket options are the single slot's, for the
     * reasons written out at length in MainViewModel.startTCP().
     */
    suspend fun run() {
        withContext(Dispatchers.IO) {
            client.tcpNoDelay = true
            client.sendBufferSize = chunkSize * 2
            client.keepAlive = true
            inputStream = client.getInputStream()
            outputStream = client.getOutputStream()
        }
        runProtocol()
    }

    /** The lane's own Cancel. Safe at any point, queued or running, and more than once. */
    fun cancel() {
        if (cancelled || state == State.DONE) return
        cancelled = true
        outputText("Transfer cancelled.")
        gate.cancel()
        job?.cancel()
        // A read blocked on the socket does not notice a cancelled coroutine; a closed socket
        // it does.
        close()
    }

    /** Lets go of the socket and the files. Idempotent. */
    fun close() {
        try {
            if (this::client.isInitialized) client.close()
        } catch (e: Exception) {
            Log.i("FlyingCarpet", "Could not close a lane's socket: $e")
        }
        for (stream in fileStreams) {
            try {
                stream.close()
            } catch (e: Exception) {
                // nothing useful to do about a file that will not close
            }
        }
    }

    companion object {
        private val nextId = AtomicLong(0)
    }
}

/**
 * Which queued sends may start now, as indices into [queued]. Pure, so the rules can be tested
 * without a device: [running] and [queued] are the device ids of the lanes in each state,
 * oldest first, null for an incoming lane whose caller has not been identified yet.
 *
 * The queue is not strictly first-in-first-out: a send waiting on its own device does not hold
 * up a later send to a different one — which is the whole point of having lanes.
 */
internal fun lanesToStart(running: List<String?>, queued: List<String?>, max: Int): List<Int> {
    val busy = running.filterNotNull().toMutableSet()
    var count = running.size
    val start = mutableListOf<Int>()
    for ((index, peer) in queued.withIndex()) {
        if (count >= max) break
        if (peer != null && peer in busy) continue
        start.add(index)
        count++
        if (peer != null) busy.add(peer)
    }
    return start
}

/**
 * Every lane in the process, queued and running. Process-wide rather than per ViewModel for
 * the same reason TransferFeed is: there are two ViewModels — the Activity's, and the one
 * PresenceService receives on — and the screen has to show a transfer whichever of them is
 * running it.
 */
object TransferLanes {
    const val MAX_LANES = 4

    private val lock = Any()
    private val all = mutableListOf<PairedLane>() // oldest first
    private var primaryCount = 0

    /** What the main screen draws: one block per lane, in this order. */
    val lanes = MutableLiveData<List<PairedLane>>(emptyList())

    /** Changes whenever any lane's progress does. See PairedLane.Pulsing. */
    val pulse = MutableLiveData(0L)

    /** A snapshot for anything that is not an observer — the notification, mostly. */
    fun current(): List<PairedLane> = synchronized(lock) { all.toList() }

    /** Whether a hotspot or password-mode transfer holds the single slot, in any ViewModel. */
    val primaryRunning: Boolean get() = synchronized(lock) { primaryCount > 0 }

    /** Whether any lane is running or queued. While true the single slot must not start. */
    val anyActive: Boolean get() = synchronized(lock) { all.isNotEmpty() }

    /** Called from MainViewModel.transferIsRunning's setter, on both edges. */
    fun primaryChanged(running: Boolean) {
        synchronized(lock) {
            primaryCount = (primaryCount + if (running) 1 else -1).coerceAtLeast(0)
        }
    }

    /**
     * Takes an outgoing lane. It starts at once if it may and waits otherwise; either way the
     * caller then awaits [awaitTurn]. Returns true if it has to wait.
     */
    fun enqueue(lane: PairedLane): Boolean = synchronized(lock) {
        lane.state = PairedLane.State.QUEUED
        all.add(lane)
        hold(lane.app)
        pump()
        publish()
        lane.state == PairedLane.State.QUEUED
    }

    /** Suspends until the lane may run. Throws CancellationException if it is cancelled first. */
    suspend fun awaitTurn(lane: PairedLane) = lane.gate.await()

    /**
     * Takes an incoming lane, or refuses it: false means "tell the caller this device is
     * busy". Called from inside the offer exchange, which is the one place a refusal can
     * still be put on the wire as a sentence.
     */
    fun admitIncoming(lane: PairedLane): Boolean = synchronized(lock) {
        if (primaryCount > 0) return false
        if (all.count { it.state == PairedLane.State.RUNNING } >= MAX_LANES) return false
        lane.state = PairedLane.State.RUNNING
        all.add(lane)
        hold(lane.app)
        publish()
        true
    }

    /** The lane is over, however it ended. A no-op for a lane that was never taken. */
    fun finish(lane: PairedLane) {
        synchronized(lock) {
            if (!all.remove(lane)) return
            lane.state = PairedLane.State.DONE
            release(lane.app)
            pump()
            publish()
        }
    }

    /** Starts whatever may start, and tells the rest what they are waiting for. Lock held. */
    private fun pump() {
        val running = all.filter { it.state == PairedLane.State.RUNNING }
        val queued = all.filter { it.state == PairedLane.State.QUEUED }
        val start = lanesToStart(running.map { it.peerId }, queued.map { it.peerId }, MAX_LANES)
        for (index in start) {
            val lane = queued[index]
            lane.state = PairedLane.State.RUNNING
            lane.waiting.postValue("")
            lane.gate.complete(Unit)
        }
        val runningNow = all.filter { it.state == PairedLane.State.RUNNING }
        for (lane in all) {
            if (lane.state != PairedLane.State.QUEUED) continue
            val sameDevice = runningNow.any { it.peerId != null && it.peerId == lane.peerId }
            lane.waiting.postValue(
                if (sameDevice) {
                    "Queued — it starts when the transfer with this device finishes."
                } else {
                    "Queued — $MAX_LANES transfers are already running."
                }
            )
        }
    }

    private fun publish() {
        lanes.postValue(all.toList())
        pulse.postValue(System.currentTimeMillis())
    }

    // ── one question at a time ────────────────────────────────────────────────────────────

    private val questionMutex = Mutex()

    /** Serialises the file-conflict dialog across every transfer in the process. */
    suspend fun <T> oneQuestionAtATime(block: suspend () -> T): T =
        questionMutex.withLock { block() }

    // ── keeping the device awake ──────────────────────────────────────────────────────────
    //
    // The same two locks MainViewModel holds for the single slot, for the reasons written out
    // there — held here once for as long as any lane exists, queued ones included, since a
    // queued send that the system froze would never start. TransferService is counted the
    // same way and already expects several holders.

    private var holders = 0
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private fun hold(app: Application) {
        TransferService.transferStarted(app)
        holders++
        try {
            if (wifiLock == null) {
                val wifi = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wifi.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "FlyingCarpet:lanes"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            // Re-armed on every new lane: the timeout is a backstop against a leak, and a
            // long run of lanes must not quietly outlive it.
            val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wake = wakeLock ?: power.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "flyingcarpet:lanes"
            ).apply { setReferenceCounted(false) }.also { wakeLock = it }
            wake.acquire(TRANSFER_WAKELOCK_TIMEOUT_MS)
        } catch (e: Exception) {
            // A missing lock costs speed, never correctness -- never the transfer.
            Log.i("FlyingCarpet", "Could not acquire the lane locks: $e")
        }
    }

    private fun release(app: Application) {
        TransferService.transferStopped(app)
        holders = (holders - 1).coerceAtLeast(0)
        if (holders > 0) return
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.i("FlyingCarpet", "Could not release the lane locks: $e")
        }
        wifiLock = null
        wakeLock = null
    }
}
