package dev.spiegl.flyingcarpet

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// Fork: the narrow-channel dialog.
//
// On 2026-11-21 phone → PC transfers ran at 13 MB/s while PC → phone ran at 49, and nothing in
// either app was slow: the access point had been sending to the PC on a 20 MHz channel for forty
// hours while everything else ran on 160. Reconnecting the PC's Wi-Fi fixed it in ten seconds —
// once somebody had spent an afternoon finding out that this was what was wrong.
//
// So the app finds out by itself. A few seconds into a paired transfer the phone asks the PC how
// its Wi-Fi link looks (PairedController.watchLink → REQUEST_LINK_STATUS; only the PC's radio
// knows). If the PC's hop of the transfer is narrow, this raises a dialog that says so in red,
// and from that one dialog 白い熊 can cancel the transfer, have the PC reconnect its Wi-Fi, see
// whether that worked, and start the transfer again — without picking the files a second time.
//
// The same dialog can be asked for — "Speed check" on a running transfer, "Wi-Fi speed check"
// in a device's menu — for everything the automatic check leaves alone on purpose: a hop on
// half the width, a PC parked on the access point's slower radio, a look before a big send.
// Asked for, it is drawn in the plain theme colours and judges nothing by width; it puts three
// figures side by side instead — how fast the transfer is moving, what the two radio hops allow,
// and the best these two devices have ever done — and says in one sentence what they add up to.
//
// What it cannot do is repair the *phone's* own link: Android lets no ordinary app reassociate
// the Wi-Fi. Only the PC's link is judged and only the PC is told to reconnect.

/** One direction of the PC's link. [mhz] is the channel width of the frames it last saw. */
data class LinkRate(val mbps: Double, val mhz: Int)

/** The PC's Wi-Fi link as it reported it. Mirrors core/src/linkstate.rs. */
data class LinkState(
    val wifi: Boolean,
    val iface: String,
    /** The width the network itself runs on. */
    val channelMhz: Int,
    /** Access point → PC: the hop a transfer *from* this phone ends on. */
    val rx: LinkRate,
    /** PC → access point: the hop a transfer *to* this phone starts on. */
    val tx: LinkRate,
    val signal: Int,
) {
    /** The PC's hop of a transfer in the given direction. */
    fun hop(phoneSending: Boolean): LinkRate = if (phoneSending) rx else tx

    /**
     * Whether that hop is narrow enough to be worth a dialog: a quarter of the network's own
     * width or less, on a network of 80 MHz or more. Half the width is left alone — an access
     * point drops from 160 to 80 for honest reasons, a weak signal among them, and the cure
     * offered here would not help. 20 MHz on a 160 MHz network is never that.
     */
    fun isNarrowFor(phoneSending: Boolean): Boolean {
        val width = hop(phoneSending).mhz
        return wifi && channelMhz >= 80 && width in 1..(channelMhz / 4)
    }

    /** "sktx is receiving from the access point on a 20 MHz channel at 172 Mbit/s, …" */
    fun sentence(peerName: String, phoneSending: Boolean): String {
        val hop = hop(phoneSending)
        val doing = if (phoneSending) "receiving from the access point" else "sending to the access point"
        return "$peerName is $doing on a ${hop.mhz} MHz channel at ${mbit(hop.mbps)}, " +
            "while the network runs on $channelMhz MHz."
    }

    /** The whole reading on one line, for the small print under the sentence. */
    fun details(): String =
        "Network $channelMhz MHz  ·  receives ${mbit(rx.mbps)} on ${rx.mhz} MHz  ·  " +
            "sends ${mbit(tx.mbps)} on ${tx.mhz} MHz  ·  signal ${signal.toString().replace('-', '−')} dBm"

    companion object {
        fun mbit(mbps: Double): String = String.format(Locale.US, "%.0f Mbit/s", mbps)

        /** `key=value` lines; unknown keys are ignored, missing ones read as nothing. */
        fun parse(text: String): LinkState? {
            val fields = fields(text)
            if (!fields.containsKey("wifi")) return null
            fun int(key: String) = fields[key]?.toIntOrNull() ?: 0
            fun double(key: String) = fields[key]?.toDoubleOrNull() ?: 0.0
            return LinkState(
                wifi = fields["wifi"] == "1",
                iface = fields["iface"].orEmpty(),
                channelMhz = int("channel_mhz"),
                rx = LinkRate(double("rx_mbps"), int("rx_mhz")),
                tx = LinkRate(double("tx_mbps"), int("tx_mhz")),
                signal = int("signal"),
            )
        }

        internal fun fields(text: String): Map<String, String> =
            text.lines().mapNotNull { line ->
                val at = line.indexOf('=')
                if (at <= 0) null else line.substring(0, at) to line.substring(at + 1)
            }.toMap()
    }
}

/** This phone's own two hops, as Android reports them. Megabits per second; zero when unknown. */
data class PhoneLink(val txMbps: Double, val rxMbps: Double)

/** What file data can expect of a link, in MB/s. A range, because that is all it honestly is. */
data class SpeedRange(val low: Double, val high: Double) {
    override fun toString(): String = "about ${speed(low)}–${speed(high)} MB/s"

    companion object {
        fun speed(mbPerSec: Double): String =
            String.format(Locale.US, if (mbPerSec < 10) "%.1f" else "%.0f", mbPerSec)
    }
}

/**
 * What two radio hops in a row allow. Both devices hang off one access point on one channel,
 * so the hops take turns on the air and their rates combine like resistors in parallel; file
 * data then gets between a half and three quarters of that, the rest going to acknowledgements,
 * retries and the gaps between frames. Checked against the four measurements of 2026-11-21:
 *
 *   phone 1225 → PC 172 Mbit/s    allows  9–14 MB/s   measured 13.4–14.4
 *   phone 1225 → PC 576–865       allows 24–48        measured 28–31
 *   PC 1153 → phone 1921          allows 45–68        measured 46–51
 */
fun linkAllows(firstHopMbps: Double, secondHopMbps: Double): SpeedRange? {
    if (firstHopMbps <= 0 || secondHopMbps <= 0) return null
    val combined = 1.0 / (1.0 / firstHopMbps + 1.0 / secondHopMbps)
    return SpeedRange(combined * 0.5 / 8, combined * 0.75 / 8)
}

/**
 * The one sentence under the figures. [now] and [best] are MB/s; [now] is null when no
 * transfer is running, [best] when these two have never finished one in this direction.
 */
fun speedVerdict(now: Double?, allows: SpeedRange?, best: Double?, name: String): String = when {
    allows == null -> "The rates of the link could not be read."
    // The rates are those of the last frames each radio saw. A transfer plainly outrunning
    // them means they are old, and nothing should be concluded from them.
    now != null && now > 1.5 * allows.high ->
        "The transfer is moving faster than these rates allow, so the reading is stale. Check again in a moment."
    now != null && now < 0.6 * allows.low ->
        "The link could carry more. The brake is elsewhere — storage, or a busy device."
    best != null && allows.high < 0.6 * best ->
        if (now != null) "The Wi-Fi link is the brake. Reconnecting $name’s Wi-Fi may help."
        else "The Wi-Fi link is slower than it has been. Reconnecting $name’s Wi-Fi may help."
    best != null && now != null && now >= 0.85 * best -> "This is as fast as these two have gone."
    best == null -> "No earlier transfer to compare with."
    now != null -> "The transfer is running at what the link gives."
    else -> "The link is about as good as it has been."
}

/**
 * The fastest finished transfer with each device, each way. The only honest "it could be":
 * not what a 160 MHz network can do on paper, but what these rooms, this access point and
 * these two devices have actually delivered.
 */
object SpeedBook {
    private const val PREFS = "speed_best"
    /** Below this a transfer is over before it is up to speed, and proves nothing. */
    private const val MIN_BYTES = 50_000_000L
    private const val MIN_SECONDS = 2.0

    private fun key(deviceId: String, phoneSending: Boolean) =
        deviceId + if (phoneSending) ".send" else ".receive"

    /** MB/s and the day it was done, or null. */
    fun best(context: Context, deviceId: String, phoneSending: Boolean): Pair<Double, String>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = key(deviceId, phoneSending)
        val speed = prefs.getFloat(k, 0f).toDouble()
        return if (speed > 0) speed to prefs.getString("$k.date", "").orEmpty() else null
    }

    fun record(context: Context, deviceId: String, phoneSending: Boolean, bytes: Long, seconds: Double) {
        if (bytes < MIN_BYTES || seconds < MIN_SECONDS) return
        val speed = bytes / 1_000_000.0 / seconds
        val known = best(context, deviceId, phoneSending)?.first ?: 0.0
        if (speed <= known) return
        val k = key(deviceId, phoneSending)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(k, speed.toFloat())
            .putString("$k.date", java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date()))
            .apply()
    }
}

/** The answer to "reconnect" and to "send again": done, or why not — or no answer at all. */
data class ControlReply(val answered: Boolean, val ok: Boolean, val error: String?) {
    companion object {
        fun parse(text: String?): ControlReply {
            if (text == null) return ControlReply(answered = false, ok = false, error = null)
            val fields = LinkState.fields(text)
            return ControlReply(true, fields["ok"] == "1", fields["error"])
        }
    }
}

/** Whom a pass through the dialog is about: a device, and the transfer with it if one runs. */
class LinkSubject(val peer: PairedPeer, val lane: PairedLane?) {
    // What a restart needs, taken before the lane is cancelled and its streams are closed.
    val files: List<DocumentFile> = lane?.files?.toList().orEmpty()
    val paths: List<String> = lane?.filePaths?.toList().orEmpty()
    val name: String get() = peer.displayName
    /** Which way the transfer runs; null when there is none. */
    val sending: Boolean? get() = lane?.sending
}

/** Everything the dialog shows, read at one moment. */
class SpeedReading(
    val state: LinkState,
    val phone: PhoneLink?,
    /** MB/s of the running transfer, lately. */
    val now: Double?,
    /** "412MB of 1.57GB", when a transfer runs. */
    val progress: String?,
)

/**
 * Where one pass through the dialog stands. The pass belongs to the process, not to an
 * Activity: the screen is rebuilt whenever the phone is turned or unfolded, and a reconnect
 * that is under way on the PC must not lose its dialog — or its result — to that.
 */
sealed class LinkStage(val subject: LinkSubject) {
    class Working(subject: LinkSubject, val title: String, val message: String, val note: String? = null) :
        LinkStage(subject)
    /** The figures. [automatic]: raised by the link watch for a narrow channel, so in red. */
    class Check(subject: LinkSubject, val reading: SpeedReading, val automatic: Boolean) : LinkStage(subject)
    class Fixed(subject: LinkSubject, val reading: SpeedReading) : LinkStage(subject)
    class Failed(
        subject: LinkSubject,
        val title: String,
        val body: String,
        val retry: Boolean,
        val details: String? = null,
    ) : LinkStage(subject)
}

object LinkDoctor {
    /** How long "Keep going" keeps the automatic dialog away for that device. */
    private const val SNOOZE_MS = 15 * 60 * 1000L

    /** What the screen shows; null when there is nothing to show. */
    val stage = MutableLiveData<LinkStage?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val snoozedUntil = ConcurrentHashMap<String, Long>()

    /** The controller of whichever Activity is current; each new one replaces the last. */
    @Volatile
    private var controller: PairedController? = null

    @Volatile
    private var appContext: Context? = null

    fun snoozed(deviceId: String): Boolean =
        (snoozedUntil[deviceId] ?: 0L) > System.currentTimeMillis()

    private fun snooze(deviceId: String) {
        snoozedUntil[deviceId] = System.currentTimeMillis() + SNOOZE_MS
    }

    /** This phone's own link rates; null off Wi-Fi. */
    private fun phoneLink(): PhoneLink? {
        val context = appContext ?: return null
        return try {
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            val info = wifi.connectionInfo ?: return null
            val tx = info.txLinkSpeedMbps
            val rx = info.rxLinkSpeedMbps
            if (tx <= 0 && rx <= 0) null else PhoneLink(tx.toDouble(), rx.toDouble())
        } catch (e: Exception) {
            null
        }
    }

    private fun reading(subject: LinkSubject, state: LinkState): SpeedReading {
        val totals = subject.lane?.takeIf { it.state == PairedLane.State.RUNNING }?.totals
        return SpeedReading(
            state = state,
            phone = phoneLink(),
            now = totals?.recentRate?.let { it / 1_000_000.0 },
            progress = totals?.let { t ->
                val total = t.totalBytes
                makeSizeReadable(t.moved) + if (total != null && total > 0) " of ${makeSizeReadable(total)}" else ""
            },
        )
    }

    /** Called by the link watch, from any thread, for a narrow channel. One pass at a time. */
    fun raise(peer: PairedPeer, lane: PairedLane, state: LinkState) {
        scope.launch {
            if (stage.value != null || snoozed(peer.deviceId)) return@launch
            if (lane.state != PairedLane.State.RUNNING || lane.cancelled) return@launch
            val subject = LinkSubject(peer, lane)
            stage.value = LinkStage.Check(subject, reading(subject, state), automatic = true)
        }
    }

    /** Whether a speed check can be offered for this lane: only the Linux build answers one. */
    fun canCheck(lane: PairedLane): Boolean {
        val peer = lane.peerId?.let { controller?.pairing?.findPeer(it) } ?: return false
        return canCheck(peer)
    }

    fun canCheck(peer: PairedPeer): Boolean = PeerOs.fromLabel(peer.os) == PeerOs.LINUX

    /** "Speed check" on a running transfer. */
    fun check(lane: PairedLane) {
        val peer = lane.peerId?.let { controller?.pairing?.findPeer(it) } ?: return
        check(LinkSubject(peer, lane))
    }

    /** "Wi-Fi speed check" in a device's menu: about its running transfer, if it has one. */
    fun check(peer: PairedPeer) {
        val lane = TransferLanes.current().firstOrNull {
            it.peerId == peer.deviceId && it.state == PairedLane.State.RUNNING && !it.cancelled
        }
        check(LinkSubject(peer, lane))
    }

    private fun check(subject: LinkSubject) {
        val controller = controller ?: return
        if (stage.value != null) return
        val name = subject.name
        stage.value = LinkStage.Working(subject, "Speed check — $name", "Reading $name’s Wi-Fi…")
        scope.launch {
            val state = controller.linkStatus(subject.peer)
            stage.value = when {
                state == null -> LinkStage.Failed(
                    subject,
                    "$name did not answer",
                    "It has to be on this network with the app open, on build 10.0.6+092 or later.",
                    retry = false,
                )
                !state.wifi -> LinkStage.Failed(
                    subject,
                    "$name is not on Wi-Fi",
                    "It is connected by cable or not at all, so there is no Wi-Fi link to read.",
                    retry = false,
                )
                else -> LinkStage.Check(subject, reading(subject, state), automatic = false)
            }
        }
    }

    /** Called from the Activity's onCreate, every time one is created. */
    fun attach(activity: AppCompatActivity, controller: PairedController) {
        this.controller = controller
        appContext = activity.applicationContext
        var dialog: Dialog? = null
        stage.observe(activity) { current ->
            dialog?.dismiss()
            dialog = current?.let { LinkDialogs(activity).show(it) }
        }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                dialog?.dismiss()
                dialog = null
            }
        })
    }

    // ── what the dialog's pills do ────────────────────────────────────────────────────────

    fun keepGoing(subject: LinkSubject) {
        snooze(subject.peer.deviceId)
        stage.value = null
    }

    fun cancelTransfer(subject: LinkSubject) {
        subject.lane?.cancel()
        stage.value = null
    }

    fun close() {
        stage.value = null
    }

    fun reconnect(subject: LinkSubject) {
        val controller = controller ?: return close()
        val peer = subject.peer
        val name = subject.name
        val title = "Reconnecting $name’s Wi-Fi"
        val note = "This takes about a quarter of a minute."
        // The transfer cannot survive its far end leaving the network, so it is ended here,
        // in words, rather than left to die of a closed socket a few seconds later.
        subject.lane?.cancel()
        stage.value = LinkStage.Working(subject, title, "Asking $name to reconnect…", note)
        scope.launch {
            // A moment for the PC to notice the cut before it is asked.
            delay(900)
            var reply = controller.reassociate(peer)
            if (!reply.answered) {
                delay(1500)
                reply = controller.reassociate(peer)
            }
            if (!reply.ok) {
                stage.value = LinkStage.Failed(
                    subject,
                    "$name was not reconnected",
                    reply.error ?: "$name did not answer. Check that the app is open on it.",
                    retry = true,
                )
                return@launch
            }
            stage.value = LinkStage.Working(subject, title, "Waiting for $name to come back…", note)
            delay(6000)
            var state: LinkState? = null
            var attempts = 0
            val deadline = System.currentTimeMillis() + 55_000
            while (System.currentTimeMillis() < deadline) {
                state = controller.linkStatus(peer)?.takeIf { it.wifi }
                if (state != null) break
                attempts++
                // A reconnect can hand the PC a new address.
                if (attempts % 3 == 0) controller.relocate(peer)
                delay(2500)
            }
            if (state != null) {
                // Once more after a breath: the first answer is read off the link's first few
                // frames, and the second is the one the verdict should rest on.
                stage.value = LinkStage.Working(subject, title, "Checking the channel…", note)
                delay(2000)
                controller.linkStatus(peer)?.takeIf { it.wifi }?.let { state = it }
            }
            val result = state
            val sending = subject.sending
            stage.value = when {
                result == null -> LinkStage.Failed(
                    subject,
                    "$name did not come back",
                    "It did not answer within a minute of reconnecting. Check its Wi-Fi, then send again.",
                    retry = false,
                )
                sending != null && result.isNarrowFor(sending) -> LinkStage.Failed(
                    subject,
                    "Still on a narrow channel",
                    result.sentence(name, sending) + " Reconnecting did not change it.",
                    retry = true,
                    details = result.details(),
                )
                else -> LinkStage.Fixed(subject, reading(subject, result))
            }
        }
    }

    /** [anyway]: asked for with the channel still narrow, so it is not raised again at once. */
    fun restart(subject: LinkSubject, anyway: Boolean = false) {
        val controller = controller ?: return close()
        if (anyway) snooze(subject.peer.deviceId)
        stage.value = null
        val sending = subject.sending ?: return
        // The store's copy, not the subject's: it carries the address the PC came back on.
        val current = controller.pairing.findPeer(subject.peer.deviceId) ?: subject.peer
        if (sending) {
            controller.sendAgain(current, subject.files, subject.paths)
            return
        }
        // The files were the PC's to send, so the PC is asked to send them again.
        scope.launch {
            val reply = controller.askResend(current)
            if (!reply.ok) {
                stage.value = LinkStage.Failed(
                    LinkSubject(current, null),
                    "Could not restart the transfer",
                    reply.error ?: "${subject.name} did not answer. Send the files again from ${subject.name}.",
                    retry = false,
                )
            }
        }
    }
}

/** Draws one stage. Holds nothing: every pill hands the decision back to [LinkDoctor]. */
private class LinkDialogs(private val activity: AppCompatActivity) {
    private val warning get() = Roles.warning(activity)
    private val success get() = Roles.success(activity)
    private val note get() = Theme.dimGold(activity)

    private fun heading(s: String, color: Int? = null): TextView =
        ForkDialog.heading(activity, s, color = color)

    private fun pill(
        s: String,
        main: Boolean = false,
        destructive: Boolean = false,
        onClick: () -> Unit,
    ): android.widget.Button =
        ForkDialog.pill(activity, s, main = main, destructive = destructive, onClick = onClick)

    private fun text(s: String, sizeSp: Float = 15f, color: Int? = null, top: Int = 10): TextView =
        ForkDialog.label(activity, s, sizeSp, color = color).apply {
            setPadding(0, ForkDialog.dp(activity, top), 0, 0)
        }

    /** One line of the figures: what it is on the left, the figure on the right, in bold. */
    private fun figure(label: String, value: String, top: Int = 4): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, ForkDialog.dp(activity, top), 0, 0)
            addView(
                ForkDialog.label(activity, label, 15f),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = ForkDialog.dp(activity, 12) },
            )
            addView(ForkDialog.label(activity, value, 15f, bold = true).apply { gravity = Gravity.END })
        }

    /**
     * The three figures and the sentence they add up to. For a running transfer, its own
     * direction; with none, both, since either may be the one about to be used.
     */
    private fun figures(box: LinearLayout, subject: LinkSubject, reading: SpeedReading, showNow: Boolean = true) {
        val top = 12
        val name = subject.name
        val state = reading.state
        val phone = reading.phone
        fun allows(phoneSending: Boolean): SpeedRange? = if (phone == null) null else if (phoneSending) {
            linkAllows(phone.txMbps, state.rx.mbps)
        } else {
            linkAllows(state.tx.mbps, phone.rxMbps)
        }
        fun best(phoneSending: Boolean) = SpeedBook.best(activity, subject.peer.deviceId, phoneSending)
        fun bestText(phoneSending: Boolean): String =
            best(phoneSending)?.let { (speed, date) ->
                SpeedRange.speed(speed) + " MB/s" + if (date.isNotEmpty()) "  ($date)" else ""
            } ?: "none yet"
        fun way(phoneSending: Boolean) = if (phoneSending) "phone → $name" else "$name → phone"

        box.addView(ForkDialog.spacer(activity, top))
        val sending = subject.sending
        if (sending != null) {
            val now = reading.now.takeIf { showNow }
            if (showNow) {
                box.addView(figure("Now", now?.let { SpeedRange.speed(it) + " MB/s" } ?: "not moving yet"))
            }
            box.addView(figure("This link allows", allows(sending)?.toString() ?: "unknown"))
            box.addView(figure("Best seen, ${way(sending)}", bestText(sending)))
            box.addView(
                text(speedVerdict(now, allows(sending), best(sending)?.first, name), top = 10)
            )
        } else {
            for (direction in listOf(true, false)) {
                box.addView(figure("${way(direction)} allows", allows(direction)?.toString() ?: "unknown"))
                box.addView(figure("Best seen, ${way(direction)}", bestText(direction)))
            }
            // Judged on the direction that was slow on 2026-11-21: the one into the PC.
            box.addView(text(speedVerdict(null, allows(true), best(true)?.first, name), top = 10))
        }
        box.addView(text("$name: " + state.details(), 13f, color = note, top = 8))
        phone?.let {
            box.addView(
                text(
                    "This phone: sends ${LinkState.mbit(it.txMbps)}  ·  receives ${LinkState.mbit(it.rxMbps)}",
                    13f, color = note, top = 2,
                )
            )
        }
    }

    /** A column of right-aligned rows of pills: three in one row do not fit the folded screen. */
    private fun buttonRows(vararg rows: List<android.view.View>): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, ForkDialog.dp(activity, 16), 0, 0)
            for (row in rows) {
                if (row.isEmpty()) continue
                addView(
                    LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.END
                        clipChildren = false
                        clipToPadding = false
                        setPadding(0, ForkDialog.dp(activity, 5), 0, ForkDialog.dp(activity, 5))
                        row.forEachIndexed { i, button ->
                            addView(
                                button,
                                LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                ).apply { if (i > 0) marginStart = ForkDialog.dp(activity, 10) },
                            )
                        }
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        }

    fun show(stage: LinkStage): Dialog {
        val box = ForkDialog.box(activity)
        val subject = stage.subject
        val name = subject.name
        val running = subject.lane != null
        when (stage) {
            is LinkStage.Check -> {
                val sending = subject.sending
                if (stage.automatic && sending != null) {
                    box.addView(heading("Narrow Wi-Fi channel", color = warning))
                    box.addView(text(stage.reading.state.sentence(name, sending), color = warning))
                } else {
                    box.addView(heading("Speed check — $name"))
                }
                figures(box, subject, stage.reading)
                // What the cure costs, said before it is taken.
                box.addView(
                    text(
                        if (running) {
                            "Reconnecting $name’s Wi-Fi cuts every transfer running with it and takes " +
                                "about a quarter of a minute. This one can be restarted from here " +
                                "afterwards, from the beginning" +
                                (stage.reading.progress?.let { " — $it has moved so far." } ?: ".")
                        } else {
                            "Reconnecting $name’s Wi-Fi takes about a quarter of a minute."
                        },
                        13f, color = note, top = 8,
                    )
                )
                val reconnect = pill("Reconnect $name’s Wi-Fi", main = true) { LinkDoctor.reconnect(subject) }
                if (stage.automatic) {
                    box.addView(
                        buttonRows(
                            listOf(reconnect),
                            listOf(
                                pill("Keep going") { LinkDoctor.keepGoing(subject) },
                                pill("Cancel transfer", destructive = true) { LinkDoctor.cancelTransfer(subject) },
                            ),
                        )
                    )
                } else {
                    box.addView(buttonRows(listOf(pill("Close") { LinkDoctor.close() }, reconnect)))
                }
            }
            is LinkStage.Working -> {
                box.addView(heading(stage.title))
                box.addView(text(stage.message))
                stage.note?.let { box.addView(text(it, 13f, color = note, top = 8)) }
            }
            is LinkStage.Fixed -> {
                val state = stage.reading.state
                box.addView(heading("Wi-Fi reconnected", color = success))
                box.addView(
                    text(
                        "$name is back on the network: receiving on ${state.rx.mhz} MHz, " +
                            "sending on ${state.tx.mhz} MHz.",
                        color = success,
                    )
                )
                // The transfer is cut by now, so there is no "now" to show: the link and the best.
                figures(box, subject, stage.reading, showNow = false)
                val buttons = mutableListOf<android.view.View>(pill("Close", main = !running) { LinkDoctor.close() })
                if (running) buttons.add(pill("Restart the transfer", main = true) { LinkDoctor.restart(subject) })
                box.addView(buttonRows(buttons))
            }
            is LinkStage.Failed -> {
                box.addView(heading(stage.title, color = warning))
                box.addView(text(stage.body, color = warning))
                stage.details?.let { box.addView(text(it, 13f, color = note, top = 8)) }
                val second = mutableListOf<android.view.View>(pill("Close", main = !running && !stage.retry) { LinkDoctor.close() })
                if (stage.retry) second.add(pill("Reconnect again", main = !running) { LinkDoctor.reconnect(subject) })
                box.addView(
                    buttonRows(
                        if (running) {
                            listOf(
                                pill("Restart the transfer anyway", main = true) {
                                    LinkDoctor.restart(subject, anyway = true)
                                },
                            )
                        } else {
                            emptyList()
                        },
                        second,
                    )
                )
            }
        }
        return ForkDialog.wrap(activity, box, cancelable = false).also { it.show() }
    }
}
