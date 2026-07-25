package dev.spiegl.flyingcarpet

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Export / Import panel — the fork's single home for carrying this app's settings between
 * devices. The visual format is the shiroikuma-kojiki sheet, which shiroikuma-kxkb also follows:
 * one bordered rounded box carries the whole panel — centred bold title, dim description, a bordered
 * tappable folder box (small label over a bold value, **red** when unset and yellow once set), the
 * last-export line, a hairline, 全選択 + the category checkboxes, a hairline, then the ArcaneChat
 * button bar — Cancel alone on the left, Import and Export grouped on the right, all round pills.
 *
 * Closing behaviour (白い熊, 2026-07-25): a **successful** export or import ends with an info dialog
 * whose acknowledgement closes the whole chain — the info dialog, this panel, and the UI page beneath
 * it. Failures ("Export failed…", "No categories selected.") only close the info dialog, leaving the
 * panel open so the problem can be fixed on the spot.
 *
 * The folder is a real filesystem path (All-Files-Access, no SAF) so the automation receiver can be
 * handed an absolute `path` by 自由作業盤 and write to exactly the same place, with exactly the same
 * name, as this panel does.
 */
class ExportImportPanel(
    private val activity: AppCompatActivity,
    private val onChainClose: () -> Unit,
) {

    private var dialog: Dialog? = null
    private var box: LinearLayout? = null
    private val selected: MutableSet<Cat> = Cat.values().toMutableSet()

    private val accent get() = ForkDialog.accent(activity)
    private val dim get() = 0xFFCCCC66.toInt()
    private val red get() = Defaults.RED

    private fun dp(v: Int) = ForkDialog.dp(activity, v)

    fun show(onDismiss: () -> Unit = {}) {
        val content = ForkDialog.box(activity)
        box = content
        val d = ForkDialog.wrap(activity, content, cancelable = true)
        dialog = d
        d.setOnDismissListener { onDismiss() }
        rebuild()
        d.show()
    }

    /** Re-render (the UI page calls this from onResume, e.g. after the All-Files-Access screen). */
    fun refresh() {
        if (dialog?.isShowing == true) rebuild()
    }

    private fun dismissPanel() {
        dialog?.dismiss()
        dialog = null
    }

    /** The whole chain: info dialog → this panel → the UI page. */
    private fun closeChain(info: Dialog) {
        info.dismiss()
        dismissPanel()
        onChainClose()
    }

    // ── Layout ───────────────────────────────────────────────────────────────────────────────

    private fun rebuild() {
        val content = box ?: return
        content.removeAllViews()

        content.addView(
            ForkDialog.heading(activity, "Export / Import").apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, dp(6))
            },
        )
        content.addView(
            ForkDialog.label(
                activity,
                "Save everything you have set in this app to one .zip in your backup folder, or " +
                    "restore it from one. Importing merges: categories the file doesn’t carry are left alone.",
                13f,
                color = dim,
            ).apply {
                alpha = 0.85f
                setPadding(0, 0, 0, dp(10))
            },
        )

        if (!hasAllFilesAccess()) {
            content.addView(
                ForkDialog.label(activity, "This app needs All-Files-Access to write into your backup folder.", 13f, color = red),
            )
            content.addView(
                ForkDialog.pill(activity, "Grant access") { requestAllFilesAccess() }.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(8) }
                },
            )
            content.addView(ForkDialog.spacer(activity, 6))
        }

        content.addView(directoryRow())
        content.addView(statusLine())

        content.addView(ForkDialog.divider(activity))
        content.addView(selectAllRow())
        Cat.values().forEach { content.addView(categoryRow(it)) }

        content.addView(ForkDialog.divider(activity, topGap = 8))
        content.addView(buttonBar())
    }

    /** The folder box: bordered and clearly tappable — small label over the bold value. */
    private fun directoryRow(): View {
        val path = Backup.exportDir(activity)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(ForkDialog.surface(activity))
                setStroke(dp(2), if (path == null) red else accent)
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener { editDirectory(path) }
            addView(ForkDialog.label(activity, "Backup folder", 12f))
            addView(
                ForkDialog.label(
                    activity,
                    path ?: "No backup folder set — tap to choose one",
                    15f,
                    color = if (path == null) red else dim,
                    bold = true,
                ),
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6); bottomMargin = dp(6) }
        }
    }

    /** The "latest export" line, queried from the folder every time the panel opens. */
    private fun statusLine(): View {
        val (message, warn) = lastExportStatus()
        return ForkDialog.label(activity, message, 14f, color = if (warn) red else dim).apply {
            alpha = if (warn) 1f else 0.8f
            setPadding(dp(2), 0, 0, dp(8))
        }
    }

    private fun lastExportStatus(): Pair<String, Boolean> {
        if (Backup.exportDir(activity) == null) return "No backup folder set." to true
        val newest = Backup.listBackups(activity).firstOrNull()
            ?: return "No backup in this folder yet." to true
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(newest.lastModified()))
        return "Latest export: $stamp (${Backup.humanSize(newest.length())})" to false
    }

    private fun selectAllRow(): View = checkbox("Select all", bold = true).apply {
        isChecked = selected.size == Cat.values().size
        setOnClickListener {
            if (isChecked) selected.addAll(Cat.values()) else selected.clear()
            rebuild()
        }
    }

    private fun categoryRow(cat: Cat): View = checkbox(cat.label).apply {
        isChecked = cat in selected
        setOnCheckedChangeListener { _, checked -> if (checked) selected.add(cat) else selected.remove(cat) }
    }

    private fun checkbox(text: String, bold: Boolean = false): CheckBox = CheckBox(activity).apply {
        this.text = text
        setTextColor(accent)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        buttonTintList = ColorStateList.valueOf(accent)
        setPadding(dp(8), dp(7), 0, dp(7))
    }

    /** The ArcaneChat button bar: Cancel alone on the left, Import + Export grouped on the right. */
    private fun buttonBar(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(0, dp(14), 0, 0)
        addView(ForkDialog.pill(activity, "Cancel") { dismissPanel() })
        addView(View(activity), LinearLayout.LayoutParams(0, 0, 1f))
        addView(
            ForkDialog.pill(activity, "Import") { onImport() }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(8) }
            },
        )
        addView(ForkDialog.pill(activity, "Export") { onExport() })
    }

    // ── Export ───────────────────────────────────────────────────────────────────────────────

    private fun onExport() {
        if (!ensureReady()) return
        if (selected.isEmpty()) {
            ForkDialog.alert(activity, "Export", "No categories selected.")
            return
        }
        val dir = File(Backup.exportDir(activity)!!)
        val cats = selected.toSet()
        val name = Backup.exportFileName()
        CoroutineScope(Dispatchers.Main).launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    dir.mkdirs()
                    val file = File(dir, name)
                    val result = file.outputStream().use { Backup.export(activity, cats, it) }
                    file to result
                }
            }
            outcome.onSuccess { (file, result) ->
                val body = buildString {
                    appendLine(file.absolutePath)
                    appendLine("${Backup.humanSize(file.length())} · ${result.lines.size} categories")
                    result.lines.forEach { appendLine("· $it") }
                    if (result.errors.isNotEmpty()) appendLine("⚠ failed: ${result.errors.joinToString(", ")}")
                }.trim()
                // Success: acknowledging closes this dialog, the panel, and the UI page.
                ForkDialog.info(
                    activity, "Export finished", body,
                    listOf("OK" to { d: Dialog -> closeChain(d) }),
                )
            }.onFailure { e ->
                ForkDialog.alert(activity, "Export failed", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // ── Import ───────────────────────────────────────────────────────────────────────────────

    private fun onImport() {
        if (!ensureReady()) return
        if (selected.isEmpty()) {
            ForkDialog.alert(activity, "Import", "No categories selected.")
            return
        }
        val backups = Backup.listBackups(activity)
        if (backups.isEmpty()) {
            ForkDialog.alert(activity, "Import", "No backup of this app was found in the backup folder.")
            return
        }
        ForkDialog.chooser(
            activity,
            "Choose a backup",
            backups.map { "${it.name}\n${Backup.humanSize(it.length())}" },
        ) { which -> runImport(backups[which]) }
    }

    private fun runImport(file: File) {
        val cats = selected.toSet()
        CoroutineScope(Dispatchers.Main).launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = file.readBytes()
                    require(Backup.categoriesIn(bytes).isNotEmpty()) { "not a ${Backup.FORMAT} archive" }
                    Backup.import(activity, bytes, cats)
                }
            }
            outcome.onSuccess { result ->
                val body = buildString {
                    result.lines.forEach { appendLine("· $it") }
                    if (result.lines.isEmpty()) appendLine("Nothing in this backup matched the selected categories.")
                    if (result.errors.isNotEmpty()) appendLine("⚠ failed: ${result.errors.joinToString(", ")}")
                    appendLine()
                    append("Restart the app for everything to take effect.")
                }.trim()
                ForkDialog.info(
                    activity, "Import finished", body,
                    listOf(
                        "Later" to { d: Dialog -> closeChain(d) },
                        "Restart now" to { _: Dialog -> restartApp() },
                    ),
                )
            }.onFailure { e ->
                ForkDialog.alert(activity, "Import failed", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun restartApp() {
        val pm: PackageManager = activity.packageManager
        val intent = pm.getLaunchIntentForPackage(activity.packageName) ?: return
        activity.startActivity(Intent.makeRestartActivityTask(intent.component))
        Runtime.getRuntime().exit(0)
    }

    // ── Folder ───────────────────────────────────────────────────────────────────────────────

    private fun ensureReady(): Boolean {
        if (!hasAllFilesAccess()) {
            requestAllFilesAccess(); return false
        }
        if (Backup.exportDir(activity) == null) {
            editDirectory(null); return false
        }
        return true
    }

    /** Type a path, or browse for one. Same model as the sister apps: a real path, not a SAF tree. */
    private fun editDirectory(current: String?) {
        val content = ForkDialog.box(activity)
        content.addView(ForkDialog.heading(activity, "Backup folder", sizeSp = 17f))
        content.addView(
            ForkDialog.label(
                activity,
                "The folder every 白い熊 app writes its backups into, e.g. /storage/emulated/0/〇/[979] バックアップ.",
                13f, color = dim,
            ).apply { setPadding(0, dp(8), 0, dp(8)) },
        )
        val input = EditText(activity).apply {
            setText(current ?: "")
            hint = "/storage/emulated/0/…"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextColor(accent)
            setHintTextColor((accent and 0x00FFFFFF) or 0x80000000.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        content.addView(input)

        val d = ForkDialog.wrap(activity, content, cancelable = true)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            clipChildren = false
            setPadding(0, dp(16), 0, 0)
        }
        row.addView(
            ForkDialog.pill(activity, "Browse") {
                d.dismiss()
                val start = current?.let { File(it) }?.takeIf { it.isDirectory }
                    ?: Environment.getExternalStorageDirectory()
                browse(start) { picked -> saveDirectory(picked.absolutePath) }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(10) }
            },
        )
        row.addView(
            ForkDialog.pill(activity, "Save") {
                d.dismiss()
                saveDirectory(input.text.toString())
            },
        )
        content.addView(row)
        d.show()
    }

    private fun saveDirectory(path: String) {
        if (path.isBlank()) return
        Backup.setExportDir(activity, path)
        rebuild()
    }

    private fun browse(dir: File, onPick: (File) -> Unit) {
        if (!hasAllFilesAccess()) {
            requestAllFilesAccess(); return
        }
        val subDirs = dir.listFiles { f -> f.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()
        val labels = mutableListOf<String>()
        val targets = mutableListOf<File?>()
        labels.add("✓ Use this folder"); targets.add(null)
        dir.parentFile?.let { labels.add(".. (${it.name.ifBlank { "/" }})"); targets.add(it) }
        subDirs.forEach { labels.add("📁 ${it.name}"); targets.add(it) }
        ForkDialog.chooser(activity, dir.absolutePath, labels) { which ->
            val target = targets[which]
            if (target == null) onPick(dir) else browse(target, onPick)
        }
    }

    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private fun requestAllFilesAccess() {
        try {
            activity.startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${activity.packageName}"),
                ),
            )
        } catch (e: Exception) {
            try {
                activity.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e2: Exception) {
                ForkDialog.alert(activity, "All-Files-Access", "Could not open the All-Files-Access settings screen.")
            }
        }
    }
}

/**
 * The one-line folder summary the UI page shows under its "Export / Import…" row, and the colour it
 * is drawn in — **red** while no folder is set (白い熊, 2026-07-25: an unset backup folder must be
 * impossible to miss anywhere it appears), the page's own label colour once one is.
 */
object ExportImportStatus {
    fun summary(context: Context): Pair<String, Boolean> {
        val path = Backup.exportDir(context) ?: return "No backup folder set" to true
        val newest = Backup.listBackups(context).firstOrNull()
            ?: return "$path — no backup yet" to true
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(newest.lastModified()))
        return "$path — latest $stamp" to false
    }

    fun color(context: Context, warn: Boolean): Int =
        if (warn) Defaults.RED else (Settings(context).colorOrNull("page.label.color") ?: Defaults.YELLOW)
}
