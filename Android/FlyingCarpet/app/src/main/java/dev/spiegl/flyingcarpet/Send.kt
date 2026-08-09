package dev.spiegl.flyingcarpet

import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.ByteBuffer

// v10+: file contents are protected by the Noise transport (see Noise.kt), which wraps the
// whole connection, so chunks are sent as raw bytes here — no application-level encryption.
suspend fun MainViewModel.sendFile(file: DocumentFile, fileStream: InputStream, filePath: String) {
    val start = System.currentTimeMillis()
    outputText("File size: ${makeSizeReadable(file.length())}")
    val wireName = sendFileDetails(file, filePath)
    if (peerIsFork) {
        // Fork-only (version-guarded in confirmVersion): the other device says what it has, and
        // the choice is made HERE, where the user who picked the files is. Upstream skips an
        // identical file silently and renames a differing one without asking.
        val sendAs = resolveConflictSending(file, wireName) ?: run {
            outputText("Skipping this file: the other device already has it.")
            return
        }
        if (sendAs != wireName) {
            outputText("Sending it as \"$sendAs\".")
        }
    } else {
        val needTransfer = checkForFileSending(file)
        if (!needTransfer) {
            outputText("Recipient already has this file, skipping.")
            return
        }
    }
    var bytesLeft = file.length()
    val buffer = ByteArray(chunkSize)
    progressDetailsMut.postValue(progressDetails(0, file.length(), 0.0))
    totals?.snapshot(0, file.length())?.let { (pct, text) ->
        totalProgressBarMut.postValue(pct)
        progressTotalDetailsMut.postValue(text)
    }
    // Throttled: at 1 MB a chunk a fast link would redraw this dozens of times a second, and it is
    // a line of text a human is reading.
    var lastDetails = 0L
    while (bytesLeft > 0) {
        val bytesRead = withContext(Dispatchers.IO) {
            fileStream.read(buffer)
        }
        if (bytesRead == -1) {
            outputText("Hit EOF, shouldn't have.")
            break
        }

        bytesLeft -= bytesRead
        sendChunk(buffer.sliceArray(0 until bytesRead))
        val percentDone = ((file.length() - bytesLeft).toDouble() / file.length()) * 100
        progressBarMut.postValue(percentDone.toInt())
        val now = System.currentTimeMillis()
        if (now - lastDetails >= 250) {
            lastDetails = now
            val done = file.length() - bytesLeft
            progressDetailsMut.postValue(progressDetails(done, file.length(), (now - start) / 1000.0))
            totals?.snapshot(done, file.length())?.let { (pct, text) ->
                totalProgressBarMut.postValue(pct)
                progressTotalDetailsMut.postValue(text)
            }
        }
    }

    // send chunkSize of 0 to signal end of transfer
    withContext(Dispatchers.IO) {
        outputStream.write(zero)
    }
    progressBarMut.postValue(100)

    totals?.let { t ->
        t.bytesDone += file.length()
        val (pct, text) = t.snapshot(0, 0)
        totalProgressBarMut.postValue(pct)
        progressTotalDetailsMut.postValue(text)
    }

    // listen for receiving end to confirm that they have everything
    readNBytes(8, inputStream)

    // stats
    progressBarMut.postValue(100)
    val end = System.currentTimeMillis()
    val seconds = (end - start) / 1000.0
    outputText("Sending took ${formatTime(seconds)}")
    val megabits = 8 * (file.length() / 1_000_000.0)
    val mbps = megabits / seconds
    outputText("Speed: %.2fmbps".format(mbps))

    // write double confirmation
    withContext(Dispatchers.IO) {
        outputStream.write(one)
    }
}

private fun MainViewModel.sendChunk(chunk: ByteArray) {
    // length-prefixed raw bytes; confidentiality/integrity come from the Noise transport
    outputStream.write(longToBigEndianBytes(chunk.size.toLong()))
    outputStream.write(chunk)
}

/** Sends the header and returns the relative name the peer will see -- which is not `path`:
 *  that is the folder part, empty for individually picked files. Handing it back is the only way
 *  the conflict question can name the file the peer is actually talking about (白い熊, 2026-08-09:
 *  the dialog asked about "" and offered to rename it to " (copy)"). */
private fun MainViewModel.sendFileDetails(file: DocumentFile, path: String): String {
    // send size of filename
    if (file.name == null) {
        throw Exception("Could not get filename.")
    }
    val fullPath = path +
            if (path != "") { "/" } else { "" } +
            file.name!!
    val filenameBytes = fullPath.encodeToByteArray()
    val filenameSize = longToBigEndianBytes(filenameBytes.size.toLong())
    outputStream.write(filenameSize)
    // send filename
    outputStream.write(filenameBytes)
    // send file size
    outputStream.write(longToBigEndianBytes(file.length()))
    return fullPath
}


/**
 * The fork's file-conflict exchange, sending half. The wire format is documented in
 * core/src/sending.rs (resolve_conflict); the two must stay in step.
 *
 * Returns the name to send the file under, or null to skip it.
 */
private suspend fun MainViewModel.resolveConflictSending(
    file: DocumentFile,
    filePath: String,
): String? {
    val status = withContext(Dispatchers.IO) {
        ByteBuffer.wrap(readNBytes(8, inputStream)).long
    }
    if (status == 0L) {
        return filePath
    }
    val identical = if (status == 1L) {
        withContext(Dispatchers.IO) {
            outputStream.write(hashFile(file.uri))
            ByteBuffer.wrap(readNBytes(8, inputStream)).long == 1L
        }
    } else {
        false
    }
    outputText(
        "The other device already has \"$filePath\"" +
                if (identical) " (identical)." else " (a different file)."
    )
    return when (val choice = askAboutExistingFile(filePath, identical)) {
        is FileConflictChoice.Skip -> {
            withContext(Dispatchers.IO) { outputStream.write(zero) }
            null
        }
        is FileConflictChoice.Overwrite -> {
            withContext(Dispatchers.IO) { outputStream.write(longToBigEndianBytes(1)) }
            filePath
        }
        is FileConflictChoice.Rename -> {
            val name = choice.newName.ifBlank { filePath }
            withContext(Dispatchers.IO) {
                outputStream.write(longToBigEndianBytes(2))
                val bytes = name.toByteArray(Charsets.UTF_8)
                outputStream.write(longToBigEndianBytes(bytes.size.toLong()))
                outputStream.write(bytes)
            }
            name
        }
    }
}

private fun MainViewModel.checkForFileSending(file: DocumentFile): Boolean {
    // we've sent the file details already, so need to wait for receiving end to tell us if they
    // have a file by that name and size. if so, hash and send. if not, proceed with transfer.
    val hasFileBytes = readNBytes(8, inputStream)
    val hasFile = ByteBuffer.wrap(hasFileBytes).long == 1L
    return if (hasFile) {
        val localHash = hashFile(file.uri)
        outputStream.write(localHash)

        // if receiving end's copy of the file doesn't match, we need to do the transfer, so we return true
        // if they do match, we return false to indicate that we don't need to do the transfer
        val hashesMatchBytes = readNBytes(8, inputStream)
        val hashesMatch = ByteBuffer.wrap(hashesMatchBytes).long == 1L
        !hashesMatch
    } else {
        true
    }
}
