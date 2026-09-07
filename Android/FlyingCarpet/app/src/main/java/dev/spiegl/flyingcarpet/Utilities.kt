package dev.spiegl.flyingcarpet

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set

fun getQrCodeBitmap(ssid: String, password: String): Bitmap {
    return getQrCodeBitmapFromContent("$ssid;$password")
}

// shared network mode QR codes contain just the password, matching the desktop version
fun getQrCodeBitmapFromContent(qrCodeContent: String): Bitmap {
    val size = 1024 // pixels
    val bits = QRCodeWriter().encode(qrCodeContent, BarcodeFormat.QR_CODE, size, size)
    return createBitmap(size, size, Bitmap.Config.RGB_565).also {
        for (x in 0 until size) {
            for (y in 0 until size) {
                it[x, y] = if (bits[x, y]) Color.BLACK else Color.WHITE
            }
        }
    }
}

// The QR code with the password printed underneath it, so the receiving screen shows both at once:
// scan it, or read it off and type it. Drawn into the bitmap rather than added to the layout on
// purpose -- the QR takes over the logo's ImageView, which has the "白い熊 魔法絨毯 UI" button
// directly beneath it in both orientations, so a label there would push the whole screen around.
//
// The caption sits on the same white as the code's quiet zone and never overlaps a module, so the
// code scans exactly as before.
fun getQrCodeBitmapWithCaption(qrCodeContent: String, caption: String): Bitmap {
    val size = 1024 // pixels
    val captionHeight = 220
    val bits = QRCodeWriter().encode(qrCodeContent, BarcodeFormat.QR_CODE, size, size)
    val bitmap = createBitmap(size, size + captionHeight, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap[x, y] = if (bits[x, y]) Color.BLACK else Color.WHITE
        }
    }
    val canvas = Canvas(bitmap)
    canvas.drawRect(
        0f, size.toFloat(), size.toFloat(), (size + captionHeight).toFloat(),
        Paint().also { it.color = Color.WHITE },
    )
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.color = Color.BLACK
        it.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        it.textAlign = Paint.Align.CENTER
        it.textSize = 150f
    }
    // shrink to fit rather than run off the edge, however long the password turns out to be
    while (paint.measureText(caption) > size - 80 && paint.textSize > 24f) {
        paint.textSize -= 4f
    }
    val baseline = size + captionHeight / 2f - (paint.descent() + paint.ascent()) / 2f
    canvas.drawText(caption, size / 2f, baseline, paint)
    return bitmap
}

fun longToBigEndianBytes(n: Long): ByteArray {
    val byteBuffer = ByteBuffer.allocate(8)
    byteBuffer.putLong(n)
    byteBuffer.rewind()
    val byteArray = ByteArray(8)
    byteBuffer.get(byteArray)
    return byteArray
}

fun makeSizeReadable(size: Long): String {
    val n = size.toDouble()
    return when {
        n < 1_000 -> "$n bytes"
        n < 1_000_000 -> "%.2fKB".format(n / 1_000)
        n < 1_000_000_000 -> "%.2fMB".format(n / 1_000_000)
        else -> "%.2fGB".format(n / 1_000_000_000)
    }
}

// Compact "2m 05s" for a countdown, as opposed to formatTime()'s prose, which reads well in the log
// after the fact but is far too long for a line rewritten several times a second.
fun formatEta(seconds: Double): String {
    if (!seconds.isFinite() || seconds < 0) {
        return "--"
    }
    val total = Math.round(seconds)
    return when {
        total >= 3600 -> "${total / 3600}h %02dm".format((total % 3600) / 60)
        total >= 60 -> "${total / 60}m %02ds".format(total % 60)
        else -> "${total}s"
    }
}

// Progress across the whole transfer, so the second bar and second line can say where we are
// overall and not just within the file in flight. Sending, every size is known up front and the
// figure is byte-accurate; receiving, sizes arrive one file at a time (the wire protocol carries
// filename + size per file, and a grand total would break the other platforms), so it is weighted
// by file count instead.
class Totals(val numFiles: Int, val totalBytes: Long?) {
    var fileIndex = 0      // 1-based
    var bytesDone = 0L     // completed files only
    val start = System.currentTimeMillis()
    // Its own window, so this line quotes the same rate as the per-file line above it rather than
    // a cumulative average sitting next to a recent one.
    private val rateWindow = RateWindow()

    // Pair(percent, text) for the whole transfer, given how far into the current file we are.
    fun snapshot(currentDone: Long, currentSize: Long): Pair<Int, String> {
        val files = "File ${maxOf(fileIndex, 1)} of $numFiles"
        val total = totalBytes
        return if (total != null && total > 0) {
            val done = minOf(bytesDone + currentDone, total)
            val elapsed = (System.currentTimeMillis() - start) / 1000.0
            // The file counter rides on the data line, so the split stays two rows, not three.
            val (data, clock) = progressDetailsParts(done, total, elapsed, rateWindow.sample(done))
            Pair(
                Math.round(done.toDouble() / total * 100).toInt(),
                "$files  ·  $data\n$clock",
            )
        } else {
            val within = if (currentSize > 0) currentDone.toDouble() / currentSize else 0.0
            val completed = maxOf(fileIndex - 1, 0).toDouble()
            val pct = Math.round((completed + within) / maxOf(numFiles, 1) * 100).toInt()
            Pair(
                minOf(pct, 100),
                "$files  ·  ${makeSizeReadable(bytesDone + currentDone)} received",
            )
        }
    }
}

// Recent rate over a trailing window rather than the whole-transfer average. The reasoning is in
// core/src/utils.rs's RateWindow; the short version is that averaging from the first byte drags
// the connection setup and the conflict-hash of a multi-gigabyte file through the figure for ever,
// so it reads low long after the transfer is up to speed, and a stall never shows at all.
class RateWindow {
    private val samples = ArrayDeque<Pair<Long, Long>>() // (millis, bytes done)

    fun sample(done: Long): Double? {
        val now = System.currentTimeMillis()
        samples.addLast(Pair(now, done))
        while (samples.size > 2 && now - samples.first().first > SPAN_MS) {
            samples.removeFirst()
        }
        val (oldestAt, oldestDone) = samples.first()
        val span = (now - oldestAt) / 1000.0
        if (span < 0.25) return null
        return (done - oldestDone).coerceAtLeast(0) / span
    }

    companion object {
        const val SPAN_MS = 5_000L
    }
}

// The two lines shown above the progress bar: how much and how fast on the first, the clock on
// the second. Kept in step with the desktop's progress_details_parts() in core/src/utils.rs --
// the reasoning for both the split and the added "elapsed" lives there.
fun progressDetailsParts(
    done: Long,
    total: Long,
    elapsedSecs: Double,
    recentRate: Double? = null,
): Pair<String, String> {
    val rate = recentRate ?: if (elapsedSecs > 0) done / elapsedSecs else 0.0
    val eta = if (rate > 0) formatEta((total - done) / rate) else "--"
    return Pair(
        "${makeSizeReadable(done)} / ${makeSizeReadable(total)}  ·  ${makeSizeReadable(rate.toLong())}/s",
        "${formatEta(elapsedSecs)} elapsed  ·  $eta left",
    )
}

fun progressDetails(done: Long, total: Long, elapsedSecs: Double): String {
    val (data, clock) = progressDetailsParts(done, total, elapsedSecs)
    return "$data\n$clock"
}

fun formatTime(seconds: Double): String {
    return if (seconds > 60) {
        val minutes = seconds.toInt() / 60
        val remainder = seconds % 60
        if (minutes > 1) {
            "%d minutes %.2f seconds".format(minutes, remainder)
        } else {
            "%d minute %.2f seconds".format(minutes, remainder)
        }
    } else {
        "%.2f seconds".format(seconds)
    }
}

// Peer-supplied filenames may carry "/" separators for folder transfers, but must not
// be able to escape the receive directory: reject ".." components and collapse
// empty/"." ones. Mirrors the desktop and Apple implementations — we don't rely on the
// SAF provider rejecting ".." display names.
/**
 * The "keep both" name for a file the other device already has: "example.jpg" ->
 * "example (copy).jpg".
 *
 * The extension stays last -- "example.jpg (copy).jpg" reads worse and opens worse. Lives here
 * rather than in the dialog because two callers need it: the rename box pre-fills with it, and a
 * sticky "rename everything" (ConflictRule.Rename) runs on it with no box to pre-fill. Twinned
 * with suggest_rename() in core/src/utils.rs and suggestRename() in Flying Carpet/src/main.js.
 */
fun suggestRename(name: String): String {
    val dot = name.lastIndexOf('.')
    val slash = name.lastIndexOf('/')
    // A leading dot is a dotfile, not an extension: ".bashrc" keeps its whole name.
    return if (dot > slash + 1) {
        name.substring(0, dot) + " (copy)" + name.substring(dot)
    } else {
        "$name (copy)"
    }
}

fun sanitizeRelativeFilename(filename: String): String {
    val components = filename.split('/').filter { it.isNotEmpty() && it != "." }
    if (components.isEmpty() || components.contains("..")) {
        throw Exception("Received invalid filename: $filename")
    }
    return components.joinToString("/")
}

fun MainViewModel.makeParentDirectories(filename: String): DocumentFile? {
    val childDirs = File(filename).parent?.split('/') ?: return null
    val cache = safCache ?: run {
        // No cache outside a receive transfer: the original findFile() walk.
        var currentDir = DocumentFile.fromTreeUri(getApplication(), receiveDir)
        for (dir in childDirs) {
            if (currentDir == null) {
                throw Exception("Could not make parent directories, couldn't get currentDir.")
            }
            currentDir = currentDir.findFile(dir) ?: currentDir.createDirectory(dir)
        }
        return currentDir
    }
    var dirId = cache.rootDocumentId
    for (dir in childDirs) {
        val existing = cache.child(dirId, dir)
        if (existing != null && !existing.isDirectory) {
            // The old walk descended into whatever findFile() returned, so a file sharing a
            // folder's name became the destination directory and failed later, obscurely.
            throw Exception("Cannot create folder \"$dir\": a file by that name already exists.")
        }
        dirId = existing?.documentId ?: run {
            val parent = cache.documentFile(dirId)
                ?: throw Exception("Could not make parent directories, couldn't get currentDir.")
            val created = parent.createDirectory(dir)
                ?: throw Exception("Could not create directory \"$dir\".")
            val id = created.treeDocumentId()
            cache.note(dirId, SafDirectoryCache.Entry(id, created.name ?: dir, 0, true))
            id
        }
    }
    // Throws rather than returning null on failure: the caller reads a null return as "this
    // filename has no parent directories", and would silently write the file to the top of
    // the receive directory instead of the folder it belongs in.
    return cache.documentFile(dirId)
        ?: throw Exception("Could not open the destination folder for \"$filename\".")
}

// returns an array of tuples where the first item is the file and the second item is the path
// to get to it relative to root directory we're sending from.
//
// Callers seed pathSoFar with the selected folder's own name, so the peer recreates that
// folder inside their chosen destination instead of having its contents dumped loose into
// it (matching the desktop and Apple senders; see docs/send-folder-behavior.md). The join is
// guarded so a relative path can never begin with "/": seeding from "" used to produce
// "/sub/file.jpg" for anything below the top level, which the desktop receiver rejects
// outright as a rooted path.
fun getFilesInDir(dir: DocumentFile, pathSoFar: String): Array<Pair<DocumentFile, String>> {
    var allFiles: Array<Pair<DocumentFile, String>> = arrayOf()
    val files = dir.listFiles()
    for (file in files) {
        if (file.isFile) {
            allFiles += file to pathSoFar
        } else if (file.isDirectory) {
            val name = file.name ?: continue
            val newDirectoryPath = if (pathSoFar.isEmpty()) name else "$pathSoFar/$name"
            allFiles += getFilesInDir(file, newDirectoryPath)
        }
    }
    return allFiles
}

// ── Shared folders ────────────────────────────────────────────────────────────
// The share sheet hands over URIs; it does not say what they are, and a folder shared from a
// file manager arrives looking exactly like a file. It cannot even be caught by opening it: on
// Linux open(2) on a directory with O_RDONLY succeeds, so ContentResolver.openInputStream()
// returns a perfectly healthy-looking stream and the first read() fails with EISDIR, halfway
// into a transfer that has already announced a size (白い熊, 2026-09-06: "in transfer it
// complains it's a dir"). So the check has to happen at selection time, before anything is
// opened, and the folder has to be flattened into files the way the folder picker does.

/** Whether a shared URI names a directory. Covers the three shapes senders actually use: a
 *  `file://` path, a SAF tree URI, and a plain SAF document URI of the directory mime type. */
fun sharedUriIsDirectory(context: Context, uri: Uri): Boolean {
    if (uri.scheme == ContentResolver.SCHEME_FILE) {
        return uri.path?.let { File(it).isDirectory } ?: false
    }
    if (DocumentsContract.isTreeUri(uri)) {
        return true
    }
    val mimeType = try {
        context.contentResolver.getType(uri)
    } catch (e: Exception) {
        null
    }
    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
        return true
    }
    if (rawDirectoryFor(uri) != null) {
        return true
    }
    // The definitive test, and the only one that holds for every sender. A file manager sharing
    // through a plain FileProvider answers none of the questions above usefully: its getType()
    // guesses a mime from the name and returns something ordinary (白い熊, 2026-09-07: Total
    // Commander offered a folder called "... 1080p.10bit" as a 3.44KB file), its URI is not a
    // document URI so it carries no document id to resolve, and its openFile() hands over a real
    // directory descriptor without complaint. So ask the kernel what the descriptor actually is,
    // which no provider can misreport.
    return probeDescriptor(context, uri)?.isDirectory == true
}

/** What the descriptor behind a shared URI turns out to be: whether it is a directory, and the
 *  path it lives at when the kernel will name it. Null if it could not be opened at all. */
private class DescriptorProbe(val isDirectory: Boolean, val path: File?)

private fun probeDescriptor(context: Context, uri: Uri): DescriptorProbe? = try {
    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
        val isDirectory = OsConstants.S_ISDIR(Os.fstat(pfd.fileDescriptor).st_mode)
        // An open descriptor names itself in /proc, whoever opened it and whatever the URI looked
        // like. This is what recovers a path from a FileProvider URI, which has none to parse.
        val path = if (isDirectory) {
            try {
                File(Os.readlink("/proc/self/fd/${pfd.fd}")).takeIf { it.isDirectory }
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
        DescriptorProbe(isDirectory, path)
    }
} catch (e: Exception) {
    null
}

/** Flattens a shared directory into the same `(file, relative path)` pairs the folder picker
 *  produces, seeded with the folder's own name so the peer recreates it (see
 *  docs/send-folder-behavior.md). Returns null when the folder cannot be read at all — an empty
 *  array means the folder really is empty, which is a different thing to say to the user. */
fun expandSharedDirectory(context: Context, uri: Uri): Array<Pair<DocumentFile, String>>? {
    val treeUri = when {
        uri.scheme == ContentResolver.SCHEME_FILE -> null
        // A tree URI carries a grant over its children, so SAF can list it outright.
        DocumentsContract.isTreeUri(uri) -> uri
        // A plain document URI grants us that one document and nothing below it, so listing its
        // children over SAF is not ours to do. Some providers answer anyway once the id is
        // rebuilt as a tree URI, which costs one query to find out.
        else -> rebuildAsTreeUri(uri)
    }
    // Empty is not taken as an answer here: androidx swallows the SecurityException a refused
    // query throws and hands back an empty listing, which is indistinguishable from an empty
    // folder. Fall through and let the filesystem settle it.
    treeUri?.let { DocumentFile.fromTreeUri(context, it) }
        // Never seeded with "": that would send the folder's contents loose instead of recreating
        // the folder, and DISPLAY_NAME is one more query that can come back empty on a URI we were
        // only half granted. The document id always carries the name as its last path component.
        ?.let { dir -> getFilesInDir(dir, dir.name?.takeIf { it.isNotBlank() } ?: nameFromUri(uri)) }
        ?.takeIf { it.isNotEmpty() }
        ?.let { return it }
    // Everything else goes through the real filesystem, which this app can read because it holds
    // MANAGE_EXTERNAL_STORAGE for the Export / Import page. That covers `file://` shares and the
    // document URIs whose provider refused the tree query above; it cannot cover a folder living
    // in a cloud app, which has no path to walk and is reported as unreadable instead.
    val dir = rawDirectoryFor(uri) ?: probeDescriptor(context, uri)?.path
    if (dir != null && hasAllFilesAccess()) {
        return getFilesInRawDir(dir, dir.name, 0)
    }
    Log.i("Share", "No way in to shared folder $uri (all-files access: ${hasAllFilesAccess()})")
    // Neither route found anything. A tree URI we could open is trusted to mean an empty folder;
    // anything else we simply could not read.
    return if (treeUri != null && treeUri == uri) arrayOf() else null
}

/** The folder's own name recovered from the URI itself, for when DISPLAY_NAME cannot be read. */
private fun nameFromUri(uri: Uri): String =
    uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':').orEmpty()

private fun rebuildAsTreeUri(uri: Uri): Uri? {
    val authority = uri.authority ?: return null
    val docId = try {
        DocumentsContract.getDocumentId(uri)
    } catch (e: Exception) {
        null
    } ?: return null
    return try {
        DocumentsContract.buildTreeDocumentUri(authority, docId)
    } catch (e: Exception) {
        null
    }
}

fun hasAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

/** The on-disk directory a shared URI points at, or null if it does not point at one. Document
 *  ids are `<volume>:<relative path>` for the platform's storage provider, and some file managers
 *  use an absolute path as the id outright. */
private fun rawDirectoryFor(uri: Uri): File? {
    if (uri.scheme == ContentResolver.SCHEME_FILE) {
        return uri.path?.let { File(it) }?.takeIf { it.isDirectory }
    }
    val docId = try {
        if (DocumentsContract.isTreeUri(uri)) {
            DocumentsContract.getTreeDocumentId(uri)
        } else {
            DocumentsContract.getDocumentId(uri)
        }
    } catch (e: Exception) {
        null
    } ?: return null
    val candidates = mutableListOf<String>()
    if (docId.startsWith("/")) {
        candidates += docId
    }
    val parts = docId.split(':', limit = 2)
    if (parts.size == 2 && parts[1].isNotEmpty()) {
        val (volume, relative) = parts
        if (volume.equals("primary", ignoreCase = true)) {
            candidates += "${Environment.getExternalStorageDirectory()}/$relative"
        }
        candidates += "/storage/$volume/$relative"
    }
    return candidates.map { File(it) }.firstOrNull { it.isDirectory }
}

// The java.io twin of getFilesInDir, wrapping each file so the rest of the send path cannot tell
// the difference: DocumentFile.fromFile() gives a file:// uri, which openInputStream() and
// hashFile() both accept. Depth-limited because this one walks a real filesystem, where a
// symlink pointing at its own ancestor would otherwise recurse for ever.
private const val MAX_RAW_DIR_DEPTH = 64

private fun getFilesInRawDir(
    dir: File,
    pathSoFar: String,
    depth: Int,
): Array<Pair<DocumentFile, String>> {
    var allFiles: Array<Pair<DocumentFile, String>> = arrayOf()
    if (depth > MAX_RAW_DIR_DEPTH) {
        return allFiles
    }
    val children = dir.listFiles() ?: return allFiles
    for (child in children.sortedBy { it.name }) {
        if (child.isFile) {
            allFiles += DocumentFile.fromFile(child) to pathSoFar
        } else if (child.isDirectory) {
            val name = child.name
            val newDirectoryPath = if (pathSoFar.isEmpty()) name else "$pathSoFar/$name"
            allFiles += getFilesInRawDir(child, newDirectoryPath, depth + 1)
        }
    }
    return allFiles
}

fun MainViewModel.hashFile(uri: Uri): ByteArray {
    val stream = getApplication<Application>().contentResolver.openInputStream(uri)
        ?: throw Exception("Could not open file to hash")
    val buffer = ByteArray(1_000_000)
    val hasher = MessageDigest.getInstance("SHA-256")
    while (true) {
        val bytesRead = stream.read(buffer)
        if (bytesRead == -1) {
            break
        }
        hasher.update(buffer, 0, bytesRead)
    }
    stream.close()
    return hasher.digest()
}

fun getSsidAndKey(password: String): Pair<String, ByteArray> {
    val hasher = MessageDigest.getInstance("SHA-256")
    hasher.update(password.encodeToByteArray())
    val key = hasher.digest()
    val ssid = "flyingCarpet_%02x%02x".format(key[0], key[1])
    return Pair(ssid, key)
}

fun computeHmac(key: ByteArray, data: ByteArray): ByteArray {
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

fun verifyHmac(key: ByteArray, data: ByteArray, expected: ByteArray): Boolean {
    val computed = computeHmac(key, data)
    return MessageDigest.isEqual(computed, expected)
}
