package dev.spiegl.flyingcarpet

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

// Sanity bounds on peer-supplied header values, checked before they're used to size an
// allocation. Chosen so every legitimate transfer passes while negative or absurd
// values (memory-exhaustion levers) are rejected as corrupt/hostile.
const val MAX_FILENAME_BYTES = 8192L
const val MAX_CHUNK_BYTES = chunkSize.toLong() // max raw chunk (Apple/Android use 5MB)

// v10+: file contents are protected by the Noise transport (see Noise.kt), which wraps the
// whole connection, so chunks arrive as raw bytes here — no application-level decryption.
suspend fun MainViewModel.receiveFile(lastFile: Boolean) {
    val start = System.currentTimeMillis()

    // receive file details. the filename is peer-supplied: allow "/" separators for
    // folder transfers, but nothing that could escape the receive directory.
    val (rawFilename, fileSize) = receiveFileDetails()
    val filename = sanitizeRelativeFilename(rawFilename)
    outputText("Filename: $filename.  Size: ${makeSizeReadable(fileSize)}")
    // Fork-only: report what we have and let the SENDING device decide (see FileConflictChoice).
    // Upstream's rule -- skip an identical file, silently rename a differing one -- is a decision
    // made here, on the device whose user is not the one watching the transfer.
    var saveAs = filename
    var overwriteUri: Uri? = null
    if (peerIsFork) {
        when (val outcome = resolveConflictReceiving(filename, fileSize)) {
            is ConflictOutcome.Skip -> {
                outputText("The sending device chose to skip this file.")
                return
            }
            is ConflictOutcome.Overwrite -> {
                outputText("Replacing the copy we already had.")
                overwriteUri = outcome.uri
            }
            is ConflictOutcome.Receive -> {
                outcome.newName?.let {
                    saveAs = sanitizeRelativeFilename(it)
                    outputText("Saving it as \"$saveAs\".")
                }
            }
        }
    } else {
        val needTransfer = checkForFileReceiving(filename, fileSize)
        if (!needTransfer) {
            outputText("The same file already exists at this location, skipping.")
            return
        }
    }
    var bytesRead: Long = 0

    // detect if filename has folders in its path. if so, make them
    val destinationFolder = makeParentDirectories(saveAs)
        ?: safCache?.rootDocumentFile()
        ?: DocumentFile.fromTreeUri(getApplication(), receiveDir)
        ?: throw Exception("Could not get DocumentFile from receiveDir.")

    // open output file. Overwriting keeps the document we already had -- truncating it -- so the
    // file the user chose to replace is the one that changes, rather than a "(1) name" beside it.
    val fileOutputStream = if (overwriteUri != null) {
        getApplication<Application>().contentResolver.openOutputStream(overwriteUri, "wt")
            ?: throw Exception("Could not open the existing file to replace it")
    } else {
        // check if file being received already exists. if so, find new filename
        val newFilename = findNewFilename(destinationFolder, saveAs)
        getOutputStreamForFile(destinationFolder, newFilename)
    }

    // receive file
    progressDetailsMut.postValue(progressDetails(0, fileSize, 0.0))
    totals?.snapshot(0, fileSize)?.let { (pct, text) ->
        totalProgressBarMut.postValue(pct)
        progressTotalDetailsMut.postValue(text)
    }
    // Throttled: see the matching comment in Send.kt.
    var lastDetails = 0L
    while (true) {
        val chunk = receiveChunk()
        if (chunk.isEmpty()) {
            break
        }
        withContext(Dispatchers.IO) {
            fileOutputStream.write(chunk)
        }
        bytesRead += chunk.size
        val percentDone = (bytesRead.toDouble() / fileSize) * 100
        progressBarMut.postValue(percentDone.toInt())
        val now = System.currentTimeMillis()
        if (now - lastDetails >= 250) {
            lastDetails = now
            progressDetailsMut.postValue(progressDetails(bytesRead, fileSize, (now - start) / 1000.0))
            totals?.snapshot(bytesRead, fileSize)?.let { (pct, text) ->
                totalProgressBarMut.postValue(pct)
                progressTotalDetailsMut.postValue(text)
            }
        }
    }

    totals?.let { t ->
        t.bytesDone += fileSize
        val (pct, text) = t.snapshot(0, 0)
        totalProgressBarMut.postValue(pct)
        progressTotalDetailsMut.postValue(text)
    }

    // tell sending end we're finished
    withContext(Dispatchers.IO) {
        this@receiveFile.outputStream.write(one)
    }

    // stats
    progressBarMut.postValue(100)
    // outputText("Received $newFilename.")
    val end = System.currentTimeMillis()
    val seconds = (end - start) / 1000.0
    outputText("Receiving took ${formatTime(seconds)}")
    val megabits = 8 * (fileSize / 1_000_000.0)
    val mbps = megabits / seconds
    outputText("Speed: %.2fmbps".format(mbps))

    // wait for double confirmation
    // catch won't run in most cases because if peer closes hotspot, onLost in
    // NetworkCallback will fire, which will post transferFinished to MainActivity, which
    // will call cleanUpTransfer(), which will close inputStream and the coroutine this is
    // running in. but that's okay, i've tested it with sleep on sending end and it works.
    if (lastFile) {
        // timeout after 2 seconds in case sending end closes hotspot before we receive confirmation
        client.soTimeout = 2_000
        try {
            readNBytes(8, inputStream)
        } catch (e: Exception) {
            // swallowing this error because we don't want this to throw
            // if sending end tears stuff down and times out on last file
            outputText("Didn't receive confirmation from peer")
        }
    } else {
        // if not last file, we want to throw error if we can't read the confirmation
        readNBytes(8, inputStream)
    }
}

private fun MainViewModel.receiveChunk(): ByteArray {
    // receive size. 0 is the legitimate end-of-file sentinel; a larger-than-possible value
    // means a corrupt or hostile stream, and must be rejected before we allocate a receive
    // buffer of that size (or truncate it into a negative Int).
    val sizeBytes = readNBytes(8, inputStream)
    val size = ByteBuffer.wrap(sizeBytes).long

    if (size == 0L) {
        return ByteArray(0)
    }
    if (size < 0 || size > MAX_CHUNK_BYTES) {
        throw Exception("Chunk size $size from peer is out of range")
    }

    // receive chunk (raw bytes; the Noise transport already authenticated and decrypted it)
    return readNBytes(size.toInt(), inputStream)
}

private fun MainViewModel.receiveFileDetails(): Pair<String, Long> {
    // receive size of filename. real paths fit comfortably under the bound; a negative
    // or unbounded value is rejected before we allocate that many bytes.
    val filenameLenBytes = readNBytes(8, inputStream)
    val filenameLen = ByteBuffer.wrap(filenameLenBytes).long
    if (filenameLen < 0 || filenameLen > MAX_FILENAME_BYTES) {
        throw Exception("Filename length $filenameLen from peer is out of range")
    }

    // receive filename
    val filenameBytes = readNBytes(filenameLen.toInt(), inputStream)
    val filename = String(filenameBytes)

    // receive file size
    val fileSizeBytes = readNBytes(8, inputStream)
    val fileSize = ByteBuffer.wrap(fileSizeBytes).long

    return Pair(filename, fileSize)
}


/** What the sending device decided about a file we already have. */
private sealed class ConflictOutcome {
    data object Skip : ConflictOutcome()
    /** Replace the document we already have, at this URI. */
    class Overwrite(val uri: Uri) : ConflictOutcome()
    /** Receive it, under the name we were given (or the one already agreed, null). */
    class Receive(val newName: String?) : ConflictOutcome()
}

/**
 * The fork's file-conflict exchange, receiving half. The wire format is documented in
 * core/src/sending.rs (resolve_conflict); this half only reports and obeys.
 */
private suspend fun MainViewModel.resolveConflictReceiving(
    filename: String,
    incomingSize: Long,
): ConflictOutcome = withContext(Dispatchers.IO) {
    val existing = resolveExistingFile(filename)
    if (existing == null) {
        outputStream.write(zero)
        return@withContext ConflictOutcome.Receive(null)
    }
    val sameSize = existing.size == incomingSize
    outputStream.write(longToBigEndianBytes(if (sameSize) 1 else 2))
    if (sameSize) {
        val localHash = hashFile(existing.uri)
        val peerHash = readNBytes(32, inputStream)
        var identical = true
        for (i in 0 until 32) {
            if (localHash[i] != peerHash[i]) {
                identical = false
            }
        }
        outputStream.write(if (identical) one else zero)
    }
    when (ByteBuffer.wrap(readNBytes(8, inputStream)).long) {
        0L -> ConflictOutcome.Skip
        1L -> ConflictOutcome.Overwrite(existing.uri)
        2L -> {
            val nameLength = ByteBuffer.wrap(readNBytes(8, inputStream)).long
            if (nameLength <= 0 || nameLength > 8192) {
                throw Exception("Peer sent an unreasonable replacement filename length: $nameLength")
            }
            ConflictOutcome.Receive(
                String(readNBytes(nameLength.toInt(), inputStream), Charsets.UTF_8)
            )
        }
        else -> throw Exception("Peer sent an unknown file decision")
    }
}

// returns true if we need the transfer, false if not
private fun MainViewModel.checkForFileReceiving(filename: String, size: Long): Boolean {
    // Does a file of this name and size already exist? Resolved against the cached directory
    // listing; the equivalent findFile() walk cost a full directory enumeration per path
    // component (see SafDirectoryCache).
    val existing = resolveExistingFile(filename)
    if (existing != null && existing.size == size) {
        // name and size both match, so we need to ask sending end for the hash and calculate it ourselves
        outputStream.write(one)
        val localHash = hashFile(existing.uri)
        val peerHash = readNBytes(32, inputStream)
        var hashesMatch = true
        for (i in 0 until 32) {
            if (localHash[i] != peerHash[i]) {
                hashesMatch = false
            }
        }
        outputStream.write(if (hashesMatch) { one } else { zero })
        return !hashesMatch
    }
    // either it doesn't exist or the size differs, so tell the sending end we need the
    // transfer and return true
    outputStream.write(zero)
    return true
}

// A file already at `filename` (relative, "/"-separated) under the receive directory, with
// its size and URI, or null if there's nothing there.
private class ExistingFile(val uri: Uri, val size: Long)

private fun MainViewModel.resolveExistingFile(filename: String): ExistingFile? {
    safCache?.let { cache ->
        val entry = cache.resolve(filename) ?: return null
        if (entry.isDirectory) return null
        return ExistingFile(cache.documentUri(entry.documentId), entry.size)
    }
    // No cache outside a receive transfer: the original findFile() walk.
    var target: DocumentFile? = DocumentFile.fromTreeUri(getApplication(), receiveDir)
        ?: throw Exception("Error reading folder: $receiveDir")
    for (component in filename.split('/')) {
        target = target?.findFile(component) ?: return null
    }
    val found = target ?: return null
    return ExistingFile(found.uri, found.length())
}
