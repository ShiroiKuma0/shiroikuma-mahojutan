package dev.spiegl.flyingcarpet

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.CompoundButtonCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup

// ── Catalog ─────────────────────────────────────────────────────────────────
// Describes every customizable surface on the single main page. SettingsActivity builds its UI from
// this; Appearance applies it. Keep the two in sync via the shared keys below.

// One editable text field (some surfaces have two, e.g. the Start button).
data class LabelField(val key: String, val label: String)

// A text surface: an editable label plus text colour / font / size.
data class TextSurface(
    val key: String,
    val title: String,
    val labels: List<LabelField>,
    val colorLabel: String = "Text colour",
)

// A standalone colour control (non-text surfaces, fills, borders, tints).
data class ColorField(val key: String, val label: String)

// A dimension control (dp), e.g. border width. Stored via Settings.size (Float dp, <=0 = inherit).
data class DimField(val key: String, val label: String)

data class ColorGroup(val title: String, val colors: List<ColorField>, val dims: List<DimField> = emptyList())

// Resolves a Typeface from a family value: "" = inherit (null), "file:<path>" = an external font file,
// anything else = a system family name. Cached per (family, style).
object FontUtil {
    private val cache = HashMap<String, Typeface?>()

    fun typeface(family: String, style: Int): Typeface? {
        if (family.isEmpty()) return null
        return cache.getOrPut("$family|$style") {
            try {
                if (family.startsWith("file:")) {
                    Typeface.create(Typeface.createFromFile(family.removePrefix("file:")), style)
                } else {
                    Typeface.create(family, style)
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    // For the font chooser: render each option in its own glyphs (style-agnostic).
    fun previewTypeface(family: String): Typeface = typeface(family, Typeface.NORMAL) ?: Typeface.DEFAULT
}

object UiCatalog {
    val textSurfaces = listOf(
        TextSurface("title", "Title", listOf(LabelField("title.text", "Title text"))),
        TextSurface("version", "Version label", listOf(LabelField("version.text", "Label text"))),
        TextSurface("about", "“About” link", listOf(LabelField("about.text", "Link text"))),
        TextSurface("bluetooth", "“Use Bluetooth” label", listOf(LabelField("bluetooth.text", "Label text"))),
        TextSurface("modeInstruction", "Step 1 instruction", listOf(LabelField("modeInstruction.text", "Instruction text"))),
        TextSurface("send", "“Send” button", listOf(LabelField("send.text", "Button text"))),
        TextSurface("receive", "“Receive” button", listOf(LabelField("receive.text", "Button text"))),
        TextSurface("peerInstruction", "Step 2 instruction", listOf(LabelField("peerInstruction.text", "Instruction text"))),
        TextSurface("androidOs", "“Android” button", listOf(LabelField("androidOs.text", "Button text"))),
        TextSurface("iosOs", "“iOS” button", listOf(LabelField("iosOs.text", "Button text"))),
        TextSurface("linuxOs", "“Linux” button", listOf(LabelField("linuxOs.text", "Button text"))),
        TextSurface("macOs", "“macOS” button", listOf(LabelField("macOs.text", "Button text"))),
        TextSurface("windowsOs", "“Windows” button", listOf(LabelField("windowsOs.text", "Button text"))),
        TextSurface(
            "start", "“Select Files” button",
            listOf(
                LabelField("start.filesText", "“Select Files” text"),
                LabelField("start.folderText", "“Select Folder” text (receive mode)"),
            ),
        ),
        TextSurface("cancel", "Cancel button", listOf(LabelField("cancel.text", "Button text"))),
        TextSurface("sendFolder", "“Send Folder” checkbox", listOf(LabelField("sendFolder.text", "Label text"))),
        TextSurface("output", "Output log", listOf(LabelField("output.hint", "Placeholder text")), colorLabel = "Text colour"),
    )

    val colorGroups = listOf(
        ColorGroup("Window", listOf(ColorField("window.bg", "Background"), ColorField("window.statusBar", "Status bar"))),
        ColorGroup(
            "Toggle buttons (Send/Receive + OS)",
            listOf(
                ColorField("toggle.selFill", "Selected fill"),
                ColorField("toggle.unselFill", "Unselected fill"),
                ColorField("toggle.selText", "Selected text"),
                ColorField("toggle.unselText", "Unselected text"),
                ColorField("toggle.stroke", "Border colour"),
            ),
            dims = listOf(DimField("toggle.strokeWidth", "Border width (dp)")),
        ),
        ColorGroup(
            "“Select Files” button",
            listOf(ColorField("start.fill", "Fill"), ColorField("start.stroke", "Border colour")),
            dims = listOf(DimField("start.strokeWidth", "Border width (dp)")),
        ),
        ColorGroup("Cancel button", listOf(ColorField("cancel.fill", "Fill"))),
        ColorGroup("“Send Folder” checkbox", listOf(ColorField("sendFolder.tint", "Box tint"))),
        ColorGroup(
            "Bluetooth switch",
            listOf(
                ColorField("bt.thumbOn", "Thumb (on)"),
                ColorField("bt.thumbOff", "Thumb (off)"),
                ColorField("bt.trackOn", "Track (on)"),
                ColorField("bt.trackOff", "Track (off)"),
            ),
        ),
        ColorGroup("Bluetooth icon", listOf(ColorField("bt.iconOn", "Tint (connected)"), ColorField("bt.iconOff", "Tint (idle)"))),
        ColorGroup("Progress bar", listOf(ColorField("progress.color", "Bar colour"))),
    )
}

// ── Applier ─────────────────────────────────────────────────────────────────

object Appearance {

    // The Bluetooth icon tint depends on live connection state, so MainActivity's observer asks here.
    fun bluetoothIconColor(activity: AppCompatActivity, connected: Boolean): Int {
        val s = Settings(activity)
        return if (connected) s.colorOrNull("bt.iconOn") ?: Color.BLUE
        else s.colorOrNull("bt.iconOff") ?: Color.BLACK
    }

    fun apply(activity: AppCompatActivity) {
        val s = Settings(activity)

        // Window background + status bar.
        s.colorOrNull("window.bg")?.let { activity.findViewById<View>(android.R.id.content).setBackgroundColor(it) }
        s.colorOrNull("window.statusBar")?.let { activity.window.statusBarColor = it }

        // Simple text surfaces (label + colour/font/size). programTitle is portrait-only; the version
        // label is `textView` in portrait and `textView2` in landscape — applyText no-ops on absent ids.
        applyText(activity, s, R.id.programTitle, "title", setText = true)
        applyText(activity, s, R.id.textView, "version", setText = true)
        applyText(activity, s, R.id.textView2, "version", setText = true)
        applyText(activity, s, R.id.aboutButton, "about", setText = true)
        applyText(activity, s, R.id.bluetoothSwitch, "bluetooth", setText = true)
        applyText(activity, s, R.id.modeInstruction, "modeInstruction", setText = true)
        applyText(activity, s, R.id.sendButton, "send", setText = true)
        applyText(activity, s, R.id.receiveButton, "receive", setText = true)
        applyText(activity, s, R.id.peerInstruction, "peerInstruction", setText = true)
        applyText(activity, s, R.id.androidButton, "androidOs", setText = true)
        applyText(activity, s, R.id.iosButton, "iosOs", setText = true)
        applyText(activity, s, R.id.linuxButton, "linuxOs", setText = true)
        applyText(activity, s, R.id.macButton, "macOs", setText = true)
        applyText(activity, s, R.id.windowsButton, "windowsOs", setText = true)
        applyText(activity, s, R.id.cancelButton, "cancel", setText = true)
        applyText(activity, s, R.id.sendFolderCheckBox, "sendFolder", setText = true)

        // Output log: never overwrite its accumulated text; theme it and edit only the hint.
        val output = activity.findViewById<TextView>(R.id.outputBox)
        applyText(activity, s, R.id.outputBox, "output", setText = false)
        s.text("output.hint").ifEmpty { null }?.let { output.hint = it }
        s.colorOrNull("output.color")?.let { output.setHintTextColor(it) }

        // Start button has dynamic text (Select Files / Select Folder) — apply theming + the right label.
        applyStartButton(activity, s)

        // Toggle button colours (Send/Receive + the five OS buttons).
        applyToggleColors(activity, s)

        // Start button fill/border.
        activity.findViewById<MaterialButton>(R.id.startButton)?.let { start ->
            s.colorOrNull("start.fill")?.let { start.backgroundTintList = ColorStateList.valueOf(it) }
            s.colorOrNull("start.stroke")?.let { start.strokeColor = ColorStateList.valueOf(it) }
            s.size("start.strokeWidth").let { if (it > 0f) start.strokeWidth = dpToPx(activity, it) }
        }

        // Toggle button (Send/Receive + OS) border width — independent of the colour overrides.
        s.size("toggle.strokeWidth").let { w ->
            if (w > 0f) {
                val px = dpToPx(activity, w)
                for (id in toggleIds) activity.findViewById<MaterialButton>(id)?.strokeWidth = px
            }
        }

        // Cancel button fill.
        s.colorOrNull("cancel.fill")?.let { c ->
            activity.findViewById<Button>(R.id.cancelButton)?.backgroundTintList = ColorStateList.valueOf(c)
        }

        // Send Folder checkbox tint.
        s.colorOrNull("sendFolder.tint")?.let { c ->
            activity.findViewById<CheckBox>(R.id.sendFolderCheckBox)?.let {
                CompoundButtonCompat.setButtonTintList(it, ColorStateList.valueOf(c))
            }
        }

        // Bluetooth switch + idle icon tint.
        applySwitch(activity, s)
        activity.findViewById<android.widget.ImageView>(R.id.bluetoothIcon)?.drawable
            ?.setTint(bluetoothIconColor(activity, connected = false))

        // Progress bar.
        s.colorOrNull("progress.color")?.let { c ->
            activity.findViewById<ProgressBar>(R.id.progressBar)?.progressTintList = ColorStateList.valueOf(c)
        }
    }

    private fun applyText(activity: AppCompatActivity, s: Settings, id: Int, key: String, setText: Boolean) {
        val v = activity.findViewById<TextView>(id) ?: return
        if (setText) s.text("$key.text").ifEmpty { null }?.let { v.text = it }
        s.colorOrNull("$key.color")?.let { v.setTextColor(it) }
        applyTypeface(s, v, key)
        s.size("$key.size").let { if (it > 0f) v.setTextSize(TypedValue.COMPLEX_UNIT_SP, it) }
    }

    private fun applyTypeface(s: Settings, v: TextView, key: String) {
        val family = s.family("$key.family")
        val style = s.style("$key.style")
        if (family.isEmpty() && style < 0) return
        val effectiveStyle = if (style >= 0) style else (v.typeface?.style ?: Typeface.NORMAL)
        v.typeface = FontUtil.typeface(family, effectiveStyle) ?: Typeface.create(v.typeface, effectiveStyle)
    }

    val toggleIds = listOf(
        R.id.sendButton, R.id.receiveButton, R.id.androidButton,
        R.id.iosButton, R.id.linuxButton, R.id.macButton, R.id.windowsButton,
    )

    private fun dpToPx(activity: AppCompatActivity, dp: Float) =
        (dp * activity.resources.displayMetrics.density).toInt()

    private fun applyStartButton(activity: AppCompatActivity, s: Settings) {
        val start = activity.findViewById<MaterialButton>(R.id.startButton)
        s.colorOrNull("start.color")?.let { start.setTextColor(it) }
        applyTypeface(s, start, "start")
        s.size("start.size").let { if (it > 0f) start.setTextSize(TypedValue.COMPLEX_UNIT_SP, it) }
        // Pick the label matching the current mode selection (defaults match the XML).
        val modeGroup = activity.findViewById<MaterialButtonToggleGroup>(R.id.modeGroup)
        start.text = if (modeGroup.checkedButtonId == R.id.receiveButton) {
            s.textOr("start.folderText", activity.getString(R.string.selectFolder))
        } else {
            s.textOr("start.filesText", activity.getString(R.string.selectFiles))
        }
    }

    private val CHECKED = intArrayOf(android.R.attr.state_checked)
    private val UNCHECKED = intArrayOf(-android.R.attr.state_checked)

    private fun isNight(activity: AppCompatActivity) =
        (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun applyToggleColors(activity: AppCompatActivity, s: Settings) {
        val keys = listOf("toggle.selFill", "toggle.unselFill", "toggle.selText", "toggle.unselText", "toggle.stroke")
        if (keys.none { s.colorOrNull(it) != null }) return // nothing set — leave the theme's own styling

        val onSurface = if (isNight(activity)) Color.WHITE else Color.BLACK
        val primary = ContextCompat.getColor(activity, R.color.buttonColor)
        val selFillDefault = (primary and 0x00FFFFFF) or (230 shl 24) // colorPrimary @ ~0.9 alpha, matches the selector

        val selFill = s.colorOrNull("toggle.selFill") ?: selFillDefault
        val unselFill = s.colorOrNull("toggle.unselFill") ?: Color.TRANSPARENT
        val selText = s.colorOrNull("toggle.selText") ?: Color.WHITE
        val unselText = s.colorOrNull("toggle.unselText") ?: onSurface
        val stroke = s.colorOrNull("toggle.stroke") ?: onSurface

        val fillCsl = ColorStateList(arrayOf(CHECKED, UNCHECKED), intArrayOf(selFill, unselFill))
        val textCsl = ColorStateList(arrayOf(CHECKED, UNCHECKED), intArrayOf(selText, unselText))
        val strokeCsl = ColorStateList.valueOf(stroke)

        for (id in toggleIds) {
            val b = activity.findViewById<MaterialButton>(id) ?: continue
            b.backgroundTintList = fillCsl
            b.setTextColor(textCsl)
            b.strokeColor = strokeCsl
        }
    }

    private fun applySwitch(activity: AppCompatActivity, s: Settings) {
        val keys = listOf("bt.thumbOn", "bt.thumbOff", "bt.trackOn", "bt.trackOff")
        if (keys.none { s.colorOrNull(it) != null }) return

        val primary = ContextCompat.getColor(activity, R.color.buttonColor)
        val thumbOn = s.colorOrNull("bt.thumbOn") ?: Color.WHITE
        val thumbOff = s.colorOrNull("bt.thumbOff") ?: 0xFF9B9B9B.toInt()
        val trackOn = s.colorOrNull("bt.trackOn") ?: primary
        val trackOff = s.colorOrNull("bt.trackOff") ?: 0xFFD6D6D6.toInt()

        val switch = activity.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.bluetoothSwitch) ?: return
        switch.thumbTintList = ColorStateList(arrayOf(CHECKED, UNCHECKED), intArrayOf(thumbOn, thumbOff))
        switch.trackTintList = ColorStateList(arrayOf(CHECKED, UNCHECKED), intArrayOf(trackOn, trackOff))
    }
}
