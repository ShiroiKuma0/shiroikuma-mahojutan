package dev.spiegl.flyingcarpet

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer

// Fork: what one transfer needs in order to run, separated from who is running it.
//
// The wire pipeline — preamble, Noise handshake, the paired hello and offer, sendFile and
// receiveFile — used to be written against MainViewModel, whose fields were the transfer's
// state. That made "a transfer" and "the app" the same object, so there could only ever be one
// (白い熊, 2026-11-17: a twenty-minute send to the desktop blocked a small one to another
// phone). The pipeline is now written against this interface instead, and two things implement
// it:
//
//   * MainViewModel — the single slot hotspot and password-mode transfers run on, exactly as
//     before. Its fields simply gained `override`.
//   * PairedLane (TransferLanes.kt) — one per paired shared-network transfer, several at once.
//
// Send.kt, Receive.kt and Paired.kt differ from before only in the receiver type their
// extension functions name. Nothing on the wire changes.
interface TransferLane {
    val app: Application
    var mode: Mode
    var connectionMode: ConnectionMode
    var psk: ByteArray
    var files: MutableList<DocumentFile>
    var fileStreams: MutableList<InputStream>
    var filePaths: MutableList<String>
    var receiveDir: Uri
    /** Whether [receiveDir] has been given a value; reading it before that throws. */
    val hasReceiveDir: Boolean
    var safCache: SafDirectoryCache?
    var client: Socket
    var inputStream: InputStream
    var outputStream: OutputStream
    var pairedSession: PairedSession?
    var conflictRule: ConflictRule?
    var peerIsFork: Boolean
    var totals: Totals?
    val progressBarMut: MutableLiveData<Int>
    val progressDetailsMut: MutableLiveData<String>
    val progressTotalDetailsMut: MutableLiveData<String>
    val totalProgressBarMut: MutableLiveData<Int>

    fun outputText(msg: String)

    /** Whether this end hosts a hotspot. Always false on a lane: a lane never has one. */
    fun isHosting(): Boolean

    /** Ask on this device what to do about a file the other device already has. */
    suspend fun askAboutExistingFile(
        name: String,
        identical: Boolean,
        moreFiles: Boolean,
    ): FileConflictAnswer
}

/**
 * Everything that happens on an open connection, from the first preamble byte to "Transfer
 * complete". The caller has already put a connected socket's streams into [TransferLane.inputStream]
 * and [TransferLane.outputStream]; how that socket came to exist is the one thing that differs
 * between the single slot and a lane.
 */
suspend fun TransferLane.runProtocol() {
    // Plaintext preamble on the raw socket: version, then send/receive mode. Every
    // preamble byte, sent and received, is recorded and bound into the Noise prologue
    // below, so tampering with the preamble fails the handshake.
    val recordingIn = RecordingInputStream(inputStream)
    val recordingOut = RecordingOutputStream(outputStream)
    inputStream = recordingIn
    outputStream = recordingOut
    confirmVersion()
    confirmMode()
    // The Noise initiator is the TCP client, the responder is the TCP server.
    val role = if (connectionMode == ConnectionMode.SharedNetwork) {
        if (mode == Mode.Sending) NoiseRole.INITIATOR else NoiseRole.RESPONDER
    } else {
        if (isHosting()) NoiseRole.RESPONDER else NoiseRole.INITIATOR
    }
    // Fork: between paired devices the hello goes here, still in the clear and still on
    // the recording streams — who is calling and under which key — so the listening
    // side can pick its pair key before the handshake, and say so in words if it cannot.
    pairedSession?.let { exchangePairedHello(it, role) }
    inputStream = recordingIn.inner
    outputStream = recordingOut.inner
    // Establish the Noise encrypted transport over the same connection, for both modes,
    // with the preamble transcript bound in as the prologue. Everything after this —
    // file count, metadata, and file data — is confidential and tamper-evident. A wrong
    // password (or a tampered preamble) fails the handshake with a clear message.
    val prologue = if (role == NoiseRole.INITIATOR) {
        buildPrologue(recordingOut.transcript(), recordingIn.transcript())
    } else {
        buildPrologue(recordingIn.transcript(), recordingOut.transcript())
    }
    outputText("Establishing encrypted connection...")
    withContext(Dispatchers.IO) {
        val transport = noiseHandshake(inputStream, outputStream, role, psk, prologue)
        inputStream = transport.input
        outputStream = transport.output
    }
    outputText("Encrypted connection established.")
    // Fork: paired devices say who they are and what they are sending before any file
    // data, so an unattended receiver can refuse with a reason and can name the sender.
    // After the handshake, so it is encrypted and the peer has already proved it holds
    // the group key.
    pairedSession?.let { exchangePairedOffer(it) }
    // Assigned unconditionally: a cache left over from a previous transfer must not
    // survive into this one, whichever direction it runs in.
    safCache = if (mode == Mode.Receiving && hasReceiveDir) {
        SafDirectoryCache(app, receiveDir)
    } else {
        null
    }
    // send/receive
    if (mode == Mode.Sending) {
        // tell receiving end how many files we're sending
        val numFilesBytes = longToBigEndianBytes(fileStreams.size.toLong())
        withContext(Dispatchers.IO) {
            outputStream.write(numFilesBytes) // write to receiving end
        }

        // sizes are all known here, so the overall bar can be byte-accurate when sending
        totals = Totals(fileStreams.size, files.sumOf { it.length() })
        // No "apply to all" carried in from a previous transfer.
        conflictRule = null
        // send files
        for (i in 0 until fileStreams.size) {
            totals?.fileIndex = i + 1
            outputText("=========================")
            outputText("Sending file ${i + 1} of ${fileStreams.size}. Filename: ${files[i].name}.")
            val path = if (i < filePaths.size) { filePaths[i] } else { "" }
            sendFile(files[i], fileStreams[i], path, i + 1 < fileStreams.size)
        }

    } else if (mode == Mode.Receiving) {
        // find out how many files we're receiving. sanity bound: no legitimate
        // transfer approaches it, and a corrupt or hostile stream shouldn't be
        // able to put us into a near-endless receive loop.
        val numFilesBytes = readNBytes(8, inputStream)
        val numFiles = ByteBuffer.wrap(numFilesBytes).long
        if (numFiles < 0 || numFiles > 1_000_000) {
            throw Exception("File count $numFiles from peer is out of range")
        }

        // no grand total on this side: sizes arrive one file at a time
        totals = Totals(numFiles.toInt(), null)
        // receive files
        for (i in 0 until numFiles) {
            totals?.fileIndex = (i + 1).toInt()
            outputText("=========================")
            outputText("Receiving file ${i + 1} of $numFiles")
            receiveFile(i == numFiles - 1)
        }
    }
    outputText("=========================")
    outputText("Transfer complete\n")
}

private suspend fun TransferLane.confirmVersion() {
    outputText("Checking both devices speak the same version...")
    withContext(Dispatchers.IO) {
        val peerVersion: Long
        if (connectionMode == ConnectionMode.SharedNetwork) {
            // symmetric: both sides send their version, then read the peer's.
            // safe from deadlock because TCP buffers the 8-byte writes.
            outputStream.write(longToBigEndianBytes(WIRE_VERSION))
            peerVersion = ByteBuffer.wrap(readNBytes(8, inputStream)).long
        } else if (isHosting()) {
            // wait for peer's version
            val peerVersionBytes = readNBytes(8, inputStream)
            peerVersion = ByteBuffer.wrap(peerVersionBytes).long
            // send our version
            outputStream.write(longToBigEndianBytes(WIRE_VERSION))
        } else {
            // send our version
            outputStream.write(longToBigEndianBytes(WIRE_VERSION))
            // wait for peer's version
            val peerVersionBytes = readNBytes(8, inputStream)
            peerVersion = ByteBuffer.wrap(peerVersionBytes).long
        }
        if (peerVersion < WIRE_VERSION) {
            // peer's version is lower, so we make the decision and report it to them.
            // v10 is a clean break from earlier versions; if transferring with a higher
            // version, that version decides compatibility.
            if (peerVersion >= 10) {
                outputStream.write(one)
            } else {
                outputStream.write(zero)
                throw Exception("The other device is running 白い熊 魔法絨毯 version $peerVersion, which is not compatible with this version ($MAJOR_VERSION). Please update both devices to the latest version at https://github.com/ShiroiKuma0/shiroikuma-mahojutan.")
            }
        } else if (peerVersion > WIRE_VERSION) {
            // peer's version is higher, so they make the decision
            val isCompatibleBytes = readNBytes(8, inputStream)
            if (ByteBuffer.wrap(isCompatibleBytes).long != 1L) {
                throw Exception("The other device is running 白い熊 魔法絨毯 version $peerVersion, which is not compatible with this version ($MAJOR_VERSION). Please update both devices to the latest version at https://github.com/ShiroiKuma0/shiroikuma-mahojutan.")
            }
        } // otherwise versions match, implicitly compatible
        // A peer that announced a fork wire version understands the file-conflict exchange.
        peerIsFork = peerVersion >= FORK_WIRE_FLOOR
    }
    if (peerIsFork) {
        outputText("The other device is running this fork; file conflicts will be asked about.")
    }
}

private suspend fun TransferLane.confirmMode() {
    withContext(Dispatchers.IO) {
        val ourMode = if (mode == Mode.Sending) {
            1L
        } else {
            0L
        }
        if (connectionMode == ConnectionMode.SharedNetwork) {
            // symmetric: both sides send their mode, read the peer's, and verify they're opposite
            outputStream.write(if (ourMode == 1L) one else zero)
            val peerMode = ByteBuffer.wrap(readNBytes(8, inputStream)).long
            if (peerMode == ourMode) {
                throw Exception("Both ends of the transfer selected $mode")
            }
        } else if (isHosting()) {
            // we're hosting, so wait for guest to say what mode they selected, compare to our own, and report back
            val peerModeBytes = readNBytes(8, inputStream)
            val peerMode = ByteBuffer.wrap(peerModeBytes).long
            if (ourMode == peerMode) {
                outputStream.write(zero)
                throw Exception("Both ends of the transfer selected $mode")
            } else {
                // write success to guest
                outputStream.write(one)
            }
        } else {
            // we're joining, so tell host what mode we selected and wait for confirmation that they don't match
            // if we're in this branch, we're not hosting, so we will have joined a hotspot, so onLinkPropertiesChanged() will have
            // been called, so peerIP should not be null
            if (mode == Mode.Sending) {
                outputStream.write(one)
            } else {
                outputStream.write(zero)
            }
            // wait to ensure host responds that mode selection was correct
            val confirmationBytes = readNBytes(8, inputStream)
            val confirmation = ByteBuffer.wrap(confirmationBytes).long
            if (confirmation == 0L) {
                throw Exception("Both ends of the transfer selected $mode")
            }
        }
    }
}

fun readNBytes(n: Int, inputStream: InputStream): ByteArray {
    val b = ByteArray(n)
    var bytesRead = 0
    while (bytesRead < n) {
        try {
            val br = inputStream.read(b, bytesRead, n - bytesRead)
            if (br == -1) {
                throw Exception("Peer connection closed")
            }
            bytesRead += br
        } catch (e: SocketException) {
            throw Exception("Peer connection closed")
        }
    }
    return b
}

fun TransferLane.findNewFilename(destinationDir: DocumentFile, filename: String): String {
    // work with the base name: destinationDir is already the file's parent
    // directory, and a "(n) name" alternative must not contain separators
    val base = filename.split("/").last()
    val dirId = destinationDir.treeDocumentId()
    val cache = safCache
    // Answered from the cached directory listing: this loop used to cost one full
    // directory enumeration per candidate name.
    fun taken(name: String) = if (cache != null) {
        cache.hasChild(dirId, name)
    } else {
        destinationDir.findFile(name) != null
    }
    var newFileName = base
    var i = 1
    while (taken(newFileName)) {
        newFileName = "($i) $base"
        i++
    }
    return newFileName
}

// Receive into "<name>.part", and put it under its real name only once the file is whole.
// Writing straight to the final name left a cancelled transfer's half-file wearing that name,
// indistinguishable from a complete one (白い熊, 2026-08-11). Mirrors the desktop receiver in
// core/src/receiving.rs; the difference is only that SAF renames a document rather than a path.
//
// Nothing is written to safCache here. The cache answers "is this name taken" for the
// collision loop, and until the rename lands the answer for the *final* name is still no --
// files are received one at a time, so promotePartFile() notes it in time for the next one.
fun TransferLane.createPartFile(
    destinationDir: DocumentFile,
    finalName: String,
): Pair<DocumentFile, OutputStream> {
    val part = destinationDir.createFile("*/*", "$finalName.part")
        ?: throw Exception("Could not create .part file URI")
    val stream = app.contentResolver.openOutputStream(part.uri)
        ?: throw Exception("Could not open output stream to .part file")
    return Pair(part, stream)
}

// The counterpart: drop whatever we are replacing, then rename the finished .part onto the
// real name. `replacing` is set only when the sending device chose Overwrite, and the delete
// has to come first -- SAF will not rename onto an occupied name, it silently de-duplicates
// to "name (1)", which would leave both copies behind.
fun TransferLane.promotePartFile(
    part: DocumentFile,
    finalName: String,
    replacing: Uri?,
    destinationDir: DocumentFile,
) {
    replacing?.let { uri ->
        try {
            DocumentsContract.deleteDocument(
                app.contentResolver, uri
            )
        } catch (e: Exception) {
            Log.i("FlyingCarpet", "Could not delete the file being replaced: $e")
        }
    }
    if (!part.renameTo(finalName)) {
        throw Exception("Could not rename \"${part.name}\" to \"$finalName\"")
    }
    safCache?.note(
        destinationDir.treeDocumentId(),
        SafDirectoryCache.Entry(part.treeDocumentId(), finalName, part.length(), false),
    )
}
