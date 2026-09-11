package dev.spiegl.flyingcarpet

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

// Fork: a transfer between two devices that already know each other.
//
// Written as extensions on MainViewModel, the way Send.kt and Receive.kt are, so the whole
// existing pipeline is reused rather than reimplemented: the same preamble, the same Noise
// handshake, the same sendFile/receiveFile, the same conflict dialog, the same SAF cache.
// What changes is only what happens before it:
//
//   stock shared network        paired
//   -------------------------   -----------------------------------------------
//   generate a password         nothing; the group key was agreed once, at pairing
//   show it / hand it over BLE  nothing
//   arm both devices            arm neither; the receiver has been listening all along
//   discover by role            connect straight to a known address
//   PBKDF2(password) → PSK      HMAC(groupKey) → PSK, no stretching needed
//
// The Rust twin is core/src/paired.rs and the offer exchange below must match it exactly.
// Note that a paired transfer reports itself as ConnectionMode.SharedNetwork throughout:
// the two are symmetric in the same way, so confirmMode's existing shared-network branch is
// correct as it stands and no new connection mode has to be threaded through the app.

/**
 * Version 2 adds [PairedOffer.request], which is what lets a sender ask a paired device to
 * raise a hotspot rather than take files down this connection. Bumped rather than sneaked in:
 * an unknown version is rejected loudly, which is a far better failure than a field read out
 * of the wrong offset. Must match core/src/paired.rs.
 */
const val OFFER_VERSION = 2L

/** Take these files down this connection. */
const val REQUEST_TRANSFER = 0L
/**
 * "Raise your hotspot and listen on it — I will leave this network and join you."
 *
 * This is what makes the hotspot route need no tap on the far device (白い熊, 2026-09-11): a
 * device that is running its listener can simply be told. The reply is the ordinary
 * accept/refuse, so a device that will not host says so in the usual way.
 */
const val REQUEST_RAISE_HOTSPOT = 1L

/** The same bound Receive.kt puts on a filename length, and for the same reason. */
private const val MAX_OFFER_FIELD_BYTES = 8192L
private const val CONNECT_TIMEOUT_MS = 5000

/** What the sender says about itself and the transfer, before a byte of file data moves. */
data class PairedOffer(
    /** [REQUEST_TRANSFER] or [REQUEST_RAISE_HOTSPOT]. */
    val request: Long,
    val deviceId: String,
    val name: String,
    val fileCount: Long,
    val totalBytes: Long,
    val firstName: String,
)

/**
 * Why an offer was refused. The sender is told which, so it can say something useful rather
 * than just "refused" — its user is the only one looking at a screen.
 */
enum class Refusal(val code: Long, val message: String) {
    BUSY(2, "The other device is busy with another transfer."),
    NOT_ALLOWED(
        3,
        "The other device is not set to accept transfers from this one without asking."
    ),
    TOO_LARGE(4, "The other device does not accept a transfer this large without asking."),
    NO_DESTINATION(5, "The other device has not been given a folder to receive into yet.");

    companion object {
        fun fromCode(code: Long): Refusal? = entries.find { it.code == code }
    }
}

/**
 * What a receiver decides about one offer. Accept carries the destination, because it is
 * decided per sending device: the peer is not known until its offer has been read, which
 * happens inside the transfer, so the folder cannot be chosen before it starts.
 */
sealed class Verdict {
    data class Accept(val directory: android.net.Uri) : Verdict()
    data class Refuse(val reason: Refusal) : Verdict()
}

/**
 * Thrown to unwind out of startTransfer() once a [REQUEST_RAISE_HOTSPOT] exchange is done.
 *
 * Not an error: the connection has served its whole purpose by the time the verdict is read,
 * and what follows — raising or joining an access point — is nothing the file loop below it
 * knows about. An exception rather than a return value because the exchange sits several
 * frames inside a function whose shape is upstream's, and threading a "stop here" flag back
 * out through all of them would mean editing every one.
 */
class HotspotRequested(val offer: PairedOffer?) : Exception("Hotspot requested")

/**
 * Marks the running transfer as a paired one and carries what only it needs. Its presence is
 * what the two hooks in MainViewModel.startTransfer() switch on; null means an ordinary
 * hotspot or shared-network transfer and nothing behaves differently.
 */
class PairedSession(
    val groupKey: ByteArray,
    val sending: Boolean,
    /**
     * True when this paired transfer rides a hotspot rather than a network both devices are
     * already on. The socket is then opened by the ordinary hotspot machinery — nothing about
     * how the access point is raised changes — so only the *credentials* come from the group
     * key. See §5 of the plan for why this still needs a tap on the far device.
     */
    val overHotspot: Boolean = false,
    /**
     * What this side is asking for. [REQUEST_RAISE_HOTSPOT] turns the connection into a
     * request rather than a transfer — the files follow over the hotspot the peer then raises.
     */
    val request: Long = REQUEST_TRANSFER,
    /** Filled in on the receiving side once the offer has been read, for the log line. */
    var offer: PairedOffer? = null,
    val localId: String,
    val localName: String,
    val decide: (PairedOffer) -> Verdict = { Verdict.Refuse(Refusal.NO_DESTINATION) },
)

// ── the offer exchange ────────────────────────────────────────────────────────────────────

private fun writeBytes(out: OutputStream, data: ByteArray) {
    out.write(longToBigEndianBytes(data.size.toLong()))
    out.write(data)
}

private fun readBytes(input: InputStream): ByteArray {
    val length = ByteBuffer.wrap(readNBytesOrThrow(input, 8)).long
    if (length < 0 || length > MAX_OFFER_FIELD_BYTES) {
        throw Exception("Offer field length $length is out of range")
    }
    return readNBytesOrThrow(input, length.toInt())
}

private fun readNBytesOrThrow(input: InputStream, n: Int): ByteArray {
    val buffer = ByteArray(n)
    var read = 0
    while (read < n) {
        val got = input.read(buffer, read, n - read)
        if (got < 0) throw Exception("The other device closed the connection.")
        read += got
    }
    return buffer
}

fun MainViewModel.writePairedOffer(offer: PairedOffer) {
    outputStream.write(longToBigEndianBytes(OFFER_VERSION))
    outputStream.write(longToBigEndianBytes(offer.request))
    writeBytes(outputStream, base32Decode(offer.deviceId) ?: ByteArray(16))
    writeBytes(outputStream, clampName(offer.name).toByteArray(Charsets.UTF_8))
    outputStream.write(longToBigEndianBytes(offer.fileCount))
    outputStream.write(longToBigEndianBytes(offer.totalBytes))
    writeBytes(outputStream, offer.firstName.toByteArray(Charsets.UTF_8))
    outputStream.flush()
}

fun MainViewModel.readPairedOffer(): PairedOffer {
    val version = ByteBuffer.wrap(readNBytesOrThrow(inputStream, 8)).long
    if (version != OFFER_VERSION) {
        throw Exception(
            "The other device sent an offer of version $version, which this version does " +
                "not understand. Update both devices."
        )
    }
    val request = ByteBuffer.wrap(readNBytesOrThrow(inputStream, 8)).long
    val idBytes = readBytes(inputStream)
    if (idBytes.size != 16) throw Exception("The other device sent a malformed identity")
    val name = String(readBytes(inputStream), Charsets.UTF_8)
    val fileCount = ByteBuffer.wrap(readNBytesOrThrow(inputStream, 8)).long
    val totalBytes = ByteBuffer.wrap(readNBytesOrThrow(inputStream, 8)).long
    val firstName = String(readBytes(inputStream), Charsets.UTF_8)
    return PairedOffer(request, base32Encode(idBytes), name, fileCount, totalBytes, firstName)
}

/**
 * Runs the offer exchange, from whichever end this device is on. Called from
 * startTransfer() immediately after the Noise handshake, so everything here is already
 * encrypted and the peer has already proved it holds the group key.
 */
suspend fun MainViewModel.exchangePairedOffer(session: PairedSession) {
    withContext(Dispatchers.IO) {
        if (session.sending) {
            val total = files.sumOf { it.length() }
            writePairedOffer(
                PairedOffer(
                    request = session.request,
                    deviceId = session.localId,
                    name = session.localName,
                    fileCount = files.size.toLong(),
                    totalBytes = total,
                    firstName = files.firstOrNull()?.name.orEmpty(),
                )
            )
            val verdict = ByteBuffer.wrap(readNBytesOrThrow(inputStream, 8)).long
            if (verdict != 1L) {
                throw Exception(
                    Refusal.fromCode(verdict)?.message
                        ?: "The other device refused the transfer."
                )
            }
            if (session.request == REQUEST_RAISE_HOTSPOT) {
                // Done: it has agreed to raise one. Everything after this happens on the
                // hotspot, not on this connection.
                outputText("The other device is raising its hotspot.")
                throw HotspotRequested(null)
            }
            outputText("The other device accepted. Sending...")
        } else {
            val offer = readPairedOffer()
            session.offer = offer
            if (offer.request == REQUEST_RAISE_HOTSPOT) {
                // Asked, not told: the same accept/refuse the file case uses, so a device
                // that will not host says so in a sentence the caller can act on.
                when (val verdict = session.decide(offer)) {
                    is Verdict.Accept -> outputStream.write(longToBigEndianBytes(1L))
                    is Verdict.Refuse -> {
                        outputStream.write(longToBigEndianBytes(verdict.reason.code))
                        outputStream.flush()
                        throw Exception(
                            "Refused a hotspot request from ${offer.name}: " +
                                verdict.reason.message
                        )
                    }
                }
                outputStream.flush()
                outputText("=========================")
                outputText(
                    "${offer.name.ifEmpty { "A paired device" }} wants to send over a hotspot. " +
                        "Raising one — nothing to do here."
                )
                throw HotspotRequested(offer)
            }
            when (val verdict = session.decide(offer)) {
                is Verdict.Accept -> {
                    // Set here rather than before the transfer, because which folder this is
                    // depends on which device is calling — and that is only known now.
                    // startTransfer() builds the SAF cache on the next line but one, so this
                    // lands in time.
                    receiveDir = verdict.directory
                    outputStream.write(longToBigEndianBytes(1L))
                }
                is Verdict.Refuse -> {
                    outputStream.write(longToBigEndianBytes(verdict.reason.code))
                    outputStream.flush()
                    throw Exception("Refused a transfer from ${offer.name}: ${verdict.reason.message}")
                }
            }
            outputStream.flush()
            val who = offer.name.ifEmpty { "A paired device" }
            outputText("=========================")
            outputText(
                "$who is sending ${offer.fileCount} file" +
                    (if (offer.fileCount == 1L) "" else "s") + "."
            )
        }
    }
}

// ── sending ───────────────────────────────────────────────────────────────────────────────

/**
 * Opens the connection for a paired send. Deliberately separate from startTCP(): the address
 * is already known, so there is nothing to discover and nothing to retry against a peer that
 * has not been armed — if it does not answer, it is not listening, and saying so at once is
 * more useful than fifteen attempts over thirty seconds.
 */
suspend fun MainViewModel.connectToPairedPeer(address: String): Boolean =
    withContext(Dispatchers.IO) {
        try {
            outputText("Connecting to $address...")
            val socket = Socket()
            socket.connect(InetSocketAddress(InetAddress.getByName(address), PRESENCE_PORT),
                CONNECT_TIMEOUT_MS)
            client = socket
            true
        } catch (e: Exception) {
            outputText("Could not reach $address: ${e.message}")
            false
        }
    }

/**
 * Closes the socket a control connection used, without running the full transfer teardown —
 * which would release locks and clear UI state the hotspot about to be raised still needs.
 */
fun MainViewModel.closePairedControlConnection() {
    try {
        client.close()
    } catch (e: UninitializedPropertyAccessException) {
        // Never connected; nothing to close.
    } catch (e: Exception) {
        Log.i("Paired", "Could not close the control connection: ${e.message}")
    }
}

// ── receiving ─────────────────────────────────────────────────────────────────────────────

/**
 * The standing receiver's listening socket. Held separately from `server`, which belongs to
 * the shared-network receive path and is closed by cleanUpTransfer() — this one has to
 * outlive individual transfers, because its whole purpose is to still be there for the next
 * one without anybody arming it.
 */
class PairedListener {
    private var socket: ServerSocket? = null

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.w("Paired", "Could not close the paired listener: ${e.message}")
        }
        socket = null
    }

    /**
     * Accepts until cancelled, handing each connection to [onConnection]. A session that
     * fails is logged and the loop carries on: one peer with a stale group key must never
     * take away the ability to receive from every other.
     */
    suspend fun listen(onConnection: suspend (Socket) -> Unit) = withContext(Dispatchers.IO) {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(PRESENCE_PORT))
        // A poll rather than a block, so cancellation is noticed without waiting for a peer.
        server.soTimeout = 250
        socket = server
        try {
            while (!cancelled && coroutineContext.isActive) {
                val accepted = try {
                    server.accept()
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (cancelled) break else continue
                }
                try {
                    onConnection(accepted)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("Paired", "Transfer from ${accepted.inetAddress} ended: ${e.message}")
                    try {
                        accepted.close()
                    } catch (ignored: Exception) {
                    }
                }
            }
        } finally {
            try {
                server.close()
            } catch (ignored: Exception) {
            }
            socket = null
        }
    }
}
