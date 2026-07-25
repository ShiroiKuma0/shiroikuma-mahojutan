package dev.spiegl.flyingcarpet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// ── The 白い熊 家族 backup engine ─────────────────────────────────────────────────────────────
//
// The sister-app category-ZIP model (shiroikuma-kojiki's KojikiExport, shiroikuma-kxkb's
// BackupManager): ONE .zip per export, holding a manifest.json plus one entry per selected
// category. Import applies the selected categories the archive actually contains, MERGING per
// key rather than wiping, so restoring a partial backup never destroys what it didn't cover and
// re-importing the same file is idempotent.
//
// Everything settable in this app lives in the one "shiroikuma_ui" SharedPreferences file plus the
// external font files 白い熊 added, so the categories split that state along the lines the UI page
// itself uses: the main screen's look, this UI page's own look, and the imported font files.
//
// The engine is deliberately headless — export() takes a set of categories, an OutputStream and a
// progress callback, so the Export/Import panel (ExportImport.kt) and the automation receiver
// (StateExportReceiver.kt) are two thin callers of one implementation, never two copies of it.

/** One independently selectable part of a backup. [id] is both the `items` id and the ZIP entry stem. */
enum class Cat(val id: String, val label: String) {
    APPEARANCE("appearance", "Appearance — main screen"),
    PAGE("page", "Appearance — this UI page"),
    FONTS("fonts", "Imported fonts"),
    ;

    companion object {
        fun byId(id: String): Cat? = values().firstOrNull { it.id == id }
    }
}

/** Outcome of an export/import: one human line per category that worked, plus any that failed. */
data class BackupResult(val lines: List<String>, val errors: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
}

object Backup {

    const val FORMAT = "mahojutan-backup"
    const val FORMAT_VERSION = 1
    private const val MANIFEST = "manifest.json"

    // Both halves of the fork — this app and the Tauri desktop app — write `shiroikuma-mahojutan_*.zip`
    // (the family name convention keys off the repo name, which they share), so a folder synced
    // between the two holds both kinds. The values inside differ per platform (an Android colour is
    // an ARGB Int, a desktop one is "#rrggbb"), so the manifest says which app wrote the file and an
    // import of the other kind is refused with a message rather than silently corrupting settings.
    private const val PLATFORM = "android"

    // The family name convention (白い熊, 2026-07-25): `<english-app-name>_<yyyy-MM-dd_HH-mm-ss>.zip`,
    // no version, no infix, no suffix — every sister app's backups share one directory, so they must
    // sort and read uniformly.
    const val EXPORT_PREFIX = "shiroikuma-mahojutan_"

    /** Where imported/added .ttf/.otf files live, and where a restored one is written back to. */
    fun fontsDir(context: Context): File = File(context.filesDir, "fonts")

    // ── The export folder (device-local, never exported) ──────────────────────────────────────
    // Its own SharedPreferences file: the backup engine dumps "shiroikuma_ui" and nothing else, so
    // the folder 白い熊 picked on THIS phone can never travel inside a backup and overwrite the
    // folder on another one.
    private const val LOCAL_PREFS = "mahojutan_local"
    private const val KEY_EXPORT_DIR = "export_dir"

    private fun localPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)

    fun exportDir(context: Context): String? =
        localPrefs(context).getString(KEY_EXPORT_DIR, null)?.takeIf { it.isNotBlank() }

    fun setExportDir(context: Context, path: String) {
        localPrefs(context).edit().putString(KEY_EXPORT_DIR, path.trim()).apply()
    }

    /** Our backups in the configured folder, newest first. */
    fun listBackups(context: Context): List<File> {
        val dir = exportDir(context)?.let { File(it) } ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && isBackupFileName(f.name) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun exportFileName(now: Long = System.currentTimeMillis()): String =
        EXPORT_PREFIX + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date(now)) + ".zip"

    /** Ours, and only ours: 白い熊 keeps every app's backups in one folder, so filter by the prefix. */
    fun isBackupFileName(name: String): Boolean =
        name.startsWith(EXPORT_PREFIX) && name.endsWith(".zip")

    // ── Export ───────────────────────────────────────────────────────────────────────────────

    /**
     * Write [cats] to [out] as one backup ZIP. [onProgress] fires after each category with
     * `(done, total, label)` — the automation receiver turns those into real-count broadcasts.
     * A failing category is reported but never aborts the others.
     */
    fun export(
        context: Context,
        cats: Set<Cat>,
        out: OutputStream,
        onProgress: ((done: Int, total: Int, label: String) -> Unit)? = null,
    ): BackupResult {
        val lines = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val included = mutableListOf<String>()
        val total = Cat.values().count { it in cats }
        var done = 0

        ZipOutputStream(out).use { zip ->
            for (cat in Cat.values()) {
                if (cat !in cats) continue
                try {
                    val count = when (cat) {
                        Cat.APPEARANCE -> writeJson(zip, "${cat.id}.json", dumpPrefs(context) { isAppearanceKey(it) })
                        Cat.PAGE -> writeJson(zip, "${cat.id}.json", dumpPrefs(context) { isPageKey(it) })
                        Cat.FONTS -> writeFonts(context, zip)
                    }
                    included.add(cat.id)
                    lines.add("${cat.label}: $count")
                } catch (e: Exception) {
                    errors.add(cat.label)
                }
                done++
                onProgress?.invoke(done, total, cat.label)
            }

            val manifest = JSONObject()
                .put("format", FORMAT)
                .put("version", FORMAT_VERSION)
                .put("platform", PLATFORM)
                .put("app", context.packageName)
                .put("appVersion", appVersionName(context))
                .put("createdTs", System.currentTimeMillis())
                .put("categories", JSONArray(included))
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return BackupResult(lines, errors)
    }

    /** Writes one category's JSON payload and returns the number of items it carried. */
    private fun writeJson(zip: ZipOutputStream, name: String, payload: Pair<JSONObject, Int>): Int {
        zip.putNextEntry(ZipEntry(name))
        zip.write(payload.first.toString().toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        return payload.second
    }

    /**
     * The font category is the font FILES themselves (`fonts/<name>` entries) plus `fonts.json`,
     * which records the order 白い熊's font menu shows them in.
     */
    private fun writeFonts(context: Context, zip: ZipOutputStream): Int {
        val settings = Settings(context)
        val files = settings.fontFiles()           // (display name, absolute path), menu order
        val names = JSONArray()
        var written = 0
        for ((_, path) in files) {
            val file = File(path)
            if (!file.isFile) continue
            zip.putNextEntry(ZipEntry("fonts/${file.name}"))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            names.put(file.name)
            written++
        }
        zip.putNextEntry(ZipEntry("fonts.json"))
        zip.write(JSONObject().put("files", names).toString().toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        return written
    }

    // ── Import ───────────────────────────────────────────────────────────────────────────────

    /** The categories a ZIP actually holds, per its manifest (falling back to the entries present). */
    fun categoriesIn(zip: ByteArray): Set<Cat> {
        val files = readZip(zip)
        val manifest = files[MANIFEST]?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() }
        val ids = manifest?.optJSONArray("categories")
        if (ids != null) {
            return (0 until ids.length()).mapNotNull { Cat.byId(ids.optString(it)) }.toSet()
        }
        return Cat.values().filter { cat ->
            files.containsKey("${cat.id}.json") || (cat == Cat.FONTS && files.keys.any { it.startsWith("fonts/") })
        }.toSet()
    }

    /** Apply the selected [cats] that [zip] contains. Absent categories are skipped, never an error. */
    fun import(context: Context, zip: ByteArray, cats: Set<Cat>): BackupResult {
        val files = readZip(zip)
        val manifest = files[MANIFEST]?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() }
        val platform = manifest?.optString("platform").orEmpty()
        require(platform.isEmpty() || platform == PLATFORM) {
            "this backup was written by the $platform app, not the Android one"
        }
        val lines = mutableListOf<String>()
        val errors = mutableListOf<String>()

        for (cat in Cat.values()) {
            if (cat !in cats) continue
            try {
                val count = when (cat) {
                    Cat.APPEARANCE, Cat.PAGE -> {
                        val raw = files["${cat.id}.json"] ?: continue
                        mergePrefs(context, JSONObject(String(raw, Charsets.UTF_8)))
                    }
                    Cat.FONTS -> {
                        if (files.keys.none { it.startsWith("fonts/") }) continue
                        restoreFonts(context, files)
                    }
                }
                lines.add("${cat.label}: $count")
            } catch (e: Exception) {
                errors.add(cat.label)
            }
        }

        // Font selections are stored as "file:<absolute path>". Re-point every one of them at this
        // install's own fonts directory by file name, so a backup restores onto a device whose data
        // directory differs (or whose font was re-added under a different path).
        relinkFontFamilies(context)
        return BackupResult(lines, errors)
    }

    // ── Preferences (the one "shiroikuma_ui" file) ────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE)

    // The UI page styles itself through "page.*" keys; everything else styles the main screen.
    // The font-file list belongs to the FONTS category, so it is in neither prefs dump.
    private fun isPageKey(key: String) = key.startsWith("page.")
    private fun isAppearanceKey(key: String) = !isPageKey(key) && key != Settings.FONT_FILES_KEY

    /** A type-tagged dump, so an Int colour never comes back as a Float size. */
    private fun dumpPrefs(context: Context, keep: (String) -> Boolean): Pair<JSONObject, Int> {
        val values = JSONObject()
        var n = 0
        for ((key, value) in prefs(context).all) {
            if (!keep(key)) continue
            val entry = when (value) {
                is Int -> JSONObject().put("t", "i").put("v", value)
                is Long -> JSONObject().put("t", "l").put("v", value)
                is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                is Boolean -> JSONObject().put("t", "b").put("v", value)
                is String -> JSONObject().put("t", "s").put("v", value)
                else -> continue
            }
            values.put(key, entry)
            n++
        }
        return JSONObject().put("prefs", values) to n
    }

    /** Merge a dump back in, key by key — never a wipe-and-replace. */
    private fun mergePrefs(context: Context, payload: JSONObject): Int {
        val values = payload.optJSONObject("prefs") ?: return 0
        val editor = prefs(context).edit()
        var n = 0
        for (key in values.keys()) {
            val entry = values.optJSONObject(key) ?: continue
            when (entry.optString("t")) {
                "i" -> editor.putInt(key, entry.optInt("v"))
                "l" -> editor.putLong(key, entry.optLong("v"))
                "f" -> editor.putFloat(key, entry.optDouble("v").toFloat())
                "b" -> editor.putBoolean(key, entry.optBoolean("v"))
                "s" -> editor.putString(key, entry.optString("v"))
                else -> continue
            }
            n++
        }
        editor.apply()
        return n
    }

    // ── Fonts ────────────────────────────────────────────────────────────────────────────────

    private fun restoreFonts(context: Context, files: Map<String, ByteArray>): Int {
        val dir = fontsDir(context).apply { mkdirs() }
        val settings = Settings(context)
        // Restore in the order the backup recorded, so the font menu comes back as 白い熊 left it.
        val order = files["fonts.json"]?.let {
            runCatching { JSONObject(String(it, Charsets.UTF_8)).optJSONArray("files") }.getOrNull()
        }
        val ordered = mutableListOf<String>()
        if (order != null) for (i in 0 until order.length()) ordered.add(order.optString(i))
        for (name in files.keys) {
            val bare = name.removePrefix("fonts/")
            if (name.startsWith("fonts/") && bare.isNotEmpty() && bare !in ordered) ordered.add(bare)
        }

        var n = 0
        for (bare in ordered) {
            val bytes = files["fonts/$bare"] ?: continue
            val dest = File(dir, bare)
            dest.outputStream().use { it.write(bytes) }
            settings.addFontFile(dest.absolutePath)   // already de-duplicates by path
            n++
        }
        return n
    }

    /** Point every "<key>.family" = "file:…" at this install's fonts directory, matched by file name. */
    private fun relinkFontFamilies(context: Context) {
        val dir = fontsDir(context)
        if (!dir.isDirectory) return
        val editor = prefs(context).edit()
        var changed = false
        for ((key, value) in prefs(context).all) {
            if (!key.endsWith(".family") || value !is String || !value.startsWith("file:")) continue
            val local = File(dir, File(value.removePrefix("file:")).name)
            if (local.isFile && local.absolutePath != value.removePrefix("file:")) {
                editor.putString(key, "file:${local.absolutePath}")
                changed = true
            }
        }
        if (changed) editor.apply()
    }

    // ── ZIP plumbing ─────────────────────────────────────────────────────────────────────────

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry: ZipEntry = zip.nextEntry ?: break
                if (!entry.isDirectory) out[entry.name] = zip.readBytesFully()
                zip.closeEntry()
            }
        }
        return out
    }

    private fun InputStream.readBytesFully(): ByteArray {
        val buffer = ByteArrayOutputStream()
        copyTo(buffer)
        return buffer.toByteArray()
    }

    fun appVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "0"

    /** Display size for the automation reply and the panel's own lines — ROOT so it never says `4,6 MB`. */
    fun humanSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.2f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> String.format(Locale.ROOT, "%.1f KB", bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }
}
