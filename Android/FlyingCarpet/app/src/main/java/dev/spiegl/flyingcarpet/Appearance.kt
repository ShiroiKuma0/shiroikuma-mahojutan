package dev.spiegl.flyingcarpet

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.TextViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup

// ── Defaults ──────────────────────────────────────────────────────────────────
// The fork's baseline look is a high-contrast yellow-on-black theme: yellow text, yellow borders,
// black backgrounds — everywhere, unless 白い熊 overrides a specific property. Boxes and selectors
// default to a 1 dp yellow line with a 10 dp corner radius; action buttons are the theme's pills
// (Theme.kt). The sliders still start at 0, so a border can be removed.
object Defaults {
    val YELLOW = 0xFFFFFF00.toInt()
    val BLACK = 0xFF000000.toInt()

    // The one non-yellow accent in the fork: "you have to fix this" (no backup folder set). Used by
    // the Export/Import panel and by the UI page's export-folder row.
    val RED = 0xFFFF5252.toInt()
    const val BORDER_WIDTH = 1f   // dp
    const val CORNER_RADIUS = 10f // dp
}

// ── Catalog ─────────────────────────────────────────────────────────────────
// Describes every customizable surface, grouped by where it lives. SettingsActivity builds its UI from
// this; Appearance applies it. Keep the two in sync via the shared keys below.

// One editable text field (some surfaces have two, e.g. the Select Files button).
data class LabelField(val key: String, val label: String)

// A standalone colour control. `default` is the baseline colour shown/used when unset (see Defaults).
data class ColorField(val key: String, val label: String, val default: Int? = null)

// A dimension control (dp), rendered as a slider 0..max. `default` is the value used when unset (e.g. a
// 1 dp border, 10 dp radius); stored via Settings.setDim so an explicit 0 differs from "unset".
data class DimField(val key: String, val label: String, val max: Int = 16, val default: Float = 0f)

// A text surface: editable label(s) plus text colour / font / size, and optionally extra colour and
// dimension controls (fills, borders, widths, radii) folded into the same element so each UI element is
// configured in one place. hasTextColor=false drops the flat text-colour control (toggle buttons carry
// selected/unselected text colours in extraColors instead).
data class TextSurface(
    val key: String,
    val title: String,
    val labels: List<LabelField>,
    val colorLabel: String = "Text colour",
    val hasTextColor: Boolean = true,
    val extraColors: List<ColorField> = emptyList(),
    val extraDims: List<DimField> = emptyList(),
)

data class ColorGroup(val title: String, val colors: List<ColorField>, val dims: List<DimField> = emptyList())

// A logical area of the app. Bundles that area's text surfaces and any colour-only groups.
data class Section(
    val title: String,
    val note: String? = null,
    val surfaces: List<TextSurface> = emptyList(),
    val colorGroups: List<ColorGroup> = emptyList(),
    // When true this section styles the settings page itself (keys under "page.*"), so SettingsActivity
    // applies it to its own chrome rather than feeding it to Appearance (which themes the main app).
    val pageStyle: Boolean = false,
)

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
    // A Send/Receive/OS toggle button: label + per-button selected/unselected fill & text, border colour,
    // and border-width / corner-radius sliders — all separate per button, in that button's own element.
    // Fork: a pill like every other button, and never filled — the selected one is told by its 3 dp
    // frame and bold label (白い熊, 2026-11-14), so both backgrounds default to black.
    private fun toggleSurface(key: String, title: String) = TextSurface(
        key, title,
        listOf(LabelField("$key.text", "Button text")),
        hasTextColor = false,
        extraColors = listOf(
            ColorField("$key.unselFill", "Background (unselected)", Defaults.BLACK),
            ColorField("$key.selFill", "Background (selected)", Defaults.BLACK),
            ColorField("$key.unselText", "Text (unselected)", Defaults.YELLOW),
            ColorField("$key.selText", "Text (selected)", Defaults.YELLOW),
            ColorField("$key.stroke", "Border colour", Defaults.YELLOW),
        ),
        extraDims = listOf(
            DimField("$key.strokeWidth", "Border width (unselected)", 16, Theme.PILL_REGULAR_DP),
            DimField("$key.cornerRadius", "Corner radius", 60, Theme.PILL_RADIUS_DP),
        ),
    )

    val sections = listOf(
        Section(
            "Main page",
            note = "The controls you reach for first — the Send, Receive, and Select Files buttons and the Bluetooth toggle. Each button's colours and borders are set right here.",
            surfaces = listOf(
                toggleSurface("send", "“Send” button"),
                toggleSurface("receive", "“Receive” button"),
                toggleSurface("hotspot", "“Hotspot” button"),
                toggleSurface("sharedNetwork", "“Shared Network” button"),
                TextSurface(
                    "start", "Send / receive buttons",
                    listOf(
                        LabelField("start.filesText", "“Files to send” text"),
                        LabelField("start.dirText", "“Directory to send” text"),
                        LabelField("start.folderText", "“Select directory” text (receive mode)"),
                        LabelField("start.sharedText", "Text after a share (“共有された N 件を送る”)"),
                    ),
                    extraColors = listOf(
                        ColorField("start.fill", "Background", Defaults.BLACK),
                        ColorField("start.stroke", "Border colour", Defaults.YELLOW),
                    ),
                    extraDims = listOf(
                        DimField("start.strokeWidth", "Border width", 16, Theme.PILL_MAIN_DP),
                        DimField("start.cornerRadius", "Corner radius", 60, Theme.PILL_RADIUS_DP),
                    ),
                ),
                TextSurface("bluetooth", "“Use Bluetooth” label", listOf(LabelField("bluetooth.text", "Label text"))),
                TextSurface("bluetoothHint", "Line under the Bluetooth switch", listOf()),
            ),
            colorGroups = listOf(
                // Every switch in the app, here and on this page: an outline with a dot, so one
                // colour for each state is all there is to set.
                ColorGroup(
                    "Switches",
                    listOf(
                        ColorField("switch.on", "On", Defaults.YELLOW),
                        ColorField("switch.off", "Off", Theme.SWITCH_OFF),
                    ),
                ),
                ColorGroup(
                    "Bluetooth icon",
                    listOf(
                        ColorField("bt.iconOn", "Tint (connected)", Defaults.YELLOW),
                        ColorField("bt.iconOff", "Tint (idle)", Defaults.YELLOW),
                    ),
                ),
            ),
        ),
        Section(
            "Title bar",
            surfaces = listOf(
                TextSurface("title", "Title", listOf(LabelField("title.text", "Title text"))),
                TextSurface("version", "Version label", listOf(LabelField("version.text", "Label text"))),
                TextSurface("about", "“About” link", listOf(LabelField("about.text", "Link text"))),
                TextSurface(
                    "devicesButton", "“Devices” button",
                    listOf(LabelField("devicesButton.text", "Button text")),
                ),
                TextSurface(
                    "uiButton", "“白い熊 魔法絨毯 UI” button", listOf(LabelField("uiButton.text", "Button text")),
                    extraColors = listOf(
                        ColorField("uiButton.bg", "Background", Defaults.BLACK),
                        ColorField("uiButton.stroke", "Border colour", Defaults.YELLOW),
                    ),
                    extraDims = listOf(
                        DimField("uiButton.strokeWidth", "Border width", 16, Theme.PILL_REGULAR_DP),
                        DimField("uiButton.cornerRadius", "Corner radius", 60, Theme.PILL_RADIUS_DP),
                    ),
                ),
            ),
            colorGroups = listOf(
                ColorGroup("App logo / picture", listOf(ColorField("logo.tint", "Tint", Defaults.YELLOW))),
            ),
        ),
        Section(
            "Step instructions",
            surfaces = listOf(
                TextSurface("modeInstruction", "Step 1 instruction", listOf(LabelField("modeInstruction.text", "Instruction text"))),
                TextSurface("connectionInstruction", "Connection-type instruction", listOf(LabelField("connectionInstruction.text", "Instruction text"))),
                TextSurface("peerInstruction", "Step 2 instruction", listOf(LabelField("peerInstruction.text", "Instruction text"))),
            ),
        ),
        Section(
            "Peer OS buttons",
            surfaces = listOf(
                toggleSurface("androidOs", "“Android” button"),
                toggleSurface("iosOs", "“iOS” button"),
                toggleSurface("linuxOs", "“Linux” button"),
                toggleSurface("macOs", "“macOS” button"),
                toggleSurface("windowsOs", "“Windows” button"),
            ),
        ),
        Section(
            "Cancel button",
            surfaces = listOf(
                TextSurface(
                    "cancel", "Cancel button", listOf(LabelField("cancel.text", "Button text")),
                    extraColors = listOf(
                        ColorField("cancel.fill", "Background", Defaults.BLACK),
                        ColorField("cancel.stroke", "Border colour", Defaults.YELLOW),
                    ),
                    extraDims = listOf(
                        DimField("cancel.strokeWidth", "Border width", 16, Theme.PILL_REGULAR_DP),
                        DimField("cancel.cornerRadius", "Corner radius", 60, Theme.PILL_RADIUS_DP),
                    ),
                ),
            ),
        ),
        Section(
            "Transfer output",
            note = "The log box at the bottom that shows status messages like “All permissions granted”.",
            surfaces = listOf(
                TextSurface(
                    "output", "Output log text", listOf(LabelField("output.hint", "Placeholder text")),
                    extraColors = listOf(
                        ColorField("output.bg", "Box background", Defaults.BLACK),
                        ColorField("output.stroke", "Box border colour", Defaults.YELLOW),
                        ColorField("output.hintColor", "Placeholder colour", Defaults.YELLOW),
                    ),
                    extraDims = listOf(
                        DimField("output.strokeWidth", "Box border width", 16, Defaults.BORDER_WIDTH),
                        DimField("output.cornerRadius", "Box corner radius", 60, Defaults.CORNER_RADIUS),
                    ),
                ),
            ),
            colorGroups = listOf(
                ColorGroup(
                    "Progress bar",
                    listOf(ColorField("progress.color", "Bar colour", Defaults.YELLOW)),
                    dims = listOf(DimField("progress.height", "Bar thickness", 60, 15f)),
                ),
            ),
        ),
        Section(
            "Window",
            colorGroups = listOf(
                ColorGroup(
                    "Window",
                    listOf(
                        ColorField("window.bg", "Background", Defaults.BLACK),
                        ColorField("window.statusBar", "Status bar", Defaults.BLACK),
                    ),
                ),
            ),
        ),
        Section(
            "About page",
            note = "The “About” dialog opened from the link under the title.",
            surfaces = listOf(
                TextSurface("aboutTitle", "Dialog title", emptyList()),
                TextSurface("aboutBody", "Body text", emptyList()),
            ),
            colorGroups = listOf(
                ColorGroup("About dialog", listOf(ColorField("aboutBg", "Background", Defaults.BLACK))),
            ),
        ),
        Section(
            "This settings page",
            note = "Restyle this customization screen itself. Each text kind previews live below; tap “Apply to this page” to repaint the page with your changes.",
            pageStyle = true,
            surfaces = listOf(
                TextSurface("page.title", "Page title", emptyList()),
                TextSurface("page.section", "Section headings", emptyList()),
                TextSurface("page.element", "Element headings", emptyList()),
                TextSurface("page.label", "Property labels", emptyList()),
                TextSurface("page.note", "Notes, hints and explanations", emptyList(), colorLabel = "Text colour (dim gold)"),
                TextSurface(
                    "page.button", "Buttons (Done / Reset / Apply)", emptyList(),
                    extraColors = listOf(
                        ColorField("page.button.bg", "Background", Defaults.BLACK),
                        ColorField("page.button.stroke", "Border colour", Defaults.YELLOW),
                    ),
                    extraDims = listOf(
                        DimField("page.button.strokeWidth", "Border width", 16, Theme.PILL_REGULAR_DP),
                        DimField("page.button.cornerRadius", "Corner radius", 60, Theme.PILL_RADIUS_DP),
                    ),
                ),
            ),
            colorGroups = listOf(
                ColorGroup("Page background", listOf(ColorField("page.bg", "Background", Defaults.BLACK))),
            ),
        ),
    )
}

// ── Applier ─────────────────────────────────────────────────────────────────

object Appearance {

    // The Bluetooth icon tint depends on live connection state, so MainActivity's observer asks here.
    fun bluetoothIconColor(activity: AppCompatActivity, connected: Boolean): Int {
        val s = Settings(activity)
        return if (connected) s.colorOrNull("bt.iconOn") ?: Defaults.YELLOW
        else s.colorOrNull("bt.iconOff") ?: Defaults.YELLOW
    }

    fun apply(activity: AppCompatActivity) {
        val s = Settings(activity)

        // Window background + status bar (black by default).
        activity.findViewById<View>(android.R.id.content).setBackgroundColor(s.colorOrNull("window.bg") ?: Defaults.BLACK)
        activity.window.statusBarColor = s.colorOrNull("window.statusBar") ?: Defaults.BLACK

        // Simple text surfaces (label + colour/font/size; default text colour yellow). programTitle is
        // portrait-only; the version label is `textView` (portrait) / `textView2` (landscape) — applyText
        // no-ops on absent ids. The toggle buttons' text colours are set per-state in applyToggleButtons.
        applyText(activity, s, R.id.programTitle, "title", setText = true)
        applyText(activity, s, R.id.textView, "version", setText = true)
        applyText(activity, s, R.id.textView2, "version", setText = true)
        // Version label default: the app's full versionName (e.g. "9.0.10+11"), no "Version" prefix —
        // unless 白い熊 set a custom "version.text". The XML string only matters before this runs.
        val versionDefault = try {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: ""
        } catch (e: Exception) { "" }
        s.text("version.text").ifEmpty { versionDefault }.ifEmpty { null }?.let { v ->
            activity.findViewById<TextView>(R.id.textView)?.text = v
            activity.findViewById<TextView>(R.id.textView2)?.text = v
        }
        applyText(activity, s, R.id.aboutButton, "about", setText = true)
        applyText(activity, s, R.id.bluetoothSwitch, "bluetooth", setText = true)
        // portrait only, and its text is set by MainActivity, never from the catalog
        applyText(activity, s, R.id.bluetoothHint, "bluetoothHint", setText = false, default = Theme.DIM_GOLD)
        // Fork: the "Stay reachable" switch wears the Bluetooth row's dress — same label
        // style, same hint style, same switch colours — so the two read as one block. Its
        // texts are fixed, and MainActivity paints the hint red while the switch is on.
        applyText(activity, s, R.id.reachableSwitch, "bluetooth", setText = false)
        applyText(activity, s, R.id.reachableHint, "bluetoothHint", setText = false, default = Theme.DIM_GOLD)
        applyText(activity, s, R.id.modeInstruction, "modeInstruction", setText = true)
        applyText(activity, s, R.id.sendButton, "send", setText = true)
        applyText(activity, s, R.id.receiveButton, "receive", setText = true)
        // v10's connection-type row. connectionInstruction is portrait-only, and applyText no-ops on
        // an id that isn't in the inflated layout.
        applyText(activity, s, R.id.connectionInstruction, "connectionInstruction", setText = true)
        applyText(activity, s, R.id.hotspotButton, "hotspot", setText = true)
        applyText(activity, s, R.id.sharedNetworkButton, "sharedNetwork", setText = true)
        applyText(activity, s, R.id.peerInstruction, "peerInstruction", setText = true)
        applyText(activity, s, R.id.androidButton, "androidOs", setText = true)
        applyText(activity, s, R.id.iosButton, "iosOs", setText = true)
        applyText(activity, s, R.id.linuxButton, "linuxOs", setText = true)
        applyText(activity, s, R.id.macButton, "macOs", setText = true)
        applyText(activity, s, R.id.windowsButton, "windowsOs", setText = true)
        applyText(activity, s, R.id.cancelButton, "cancel", setText = true)


        // "Customize UI" button: text/font/size via applyText, then background / border / corner radius.
        applyText(activity, s, R.id.uiButton, "uiButton", setText = true)
        // Fork: the Devices button is the UI button's pair and takes the same box, so one
        // border width sets both rather than two controls having to be kept in step. Without
        // this it inflated as a stock filled MaterialButton — a purple slab beside a yellow
        // pill, which is exactly how it looked on 白い熊's screen (2026-09-11).
        applyText(activity, s, R.id.devicesButton, "devicesButton", setText = true)
        for (buttonId in listOf(R.id.devicesButton, R.id.uiButton)) {
            activity.findViewById<MaterialButton>(buttonId)?.let { b ->
                applyPill(activity, s, b, "uiButton", bgKey = "uiButton.bg", textKey = "uiButton.color", main = false)
            }
        }

        // Output log: theme the message text/font/size via applyText ("output.color" = message colour),
        // set the placeholder text + colour, and give the box its background/border/corner radius.
        val output = activity.findViewById<TextView>(R.id.outputBox)
        applyText(activity, s, R.id.outputBox, "output", setText = false)
        s.text("output.hint").ifEmpty { null }?.let { output?.hint = it }
        output?.setHintTextColor(s.colorOrNull("output.hintColor") ?: Defaults.YELLOW)
        applyOutputBox(activity, s)

        // Start button: dynamic text (Select Files / Select Folder) + theming + background/border.
        applyStartButton(activity, s)
        applyLastFolderButton(activity, s)
        // Both send buttons and the remembered-directory button share one dress, so the row reads
        // as one control however many of them are showing. The start button is the one thing the
        // page is for, so it is the main pill; the other two are regular ones beside it.
        activity.findViewById<MaterialButton>(R.id.startButton)?.let { b ->
            applyPill(activity, s, b, "start", bgKey = "start.fill", textKey = "start.color", main = true)
        }
        activity.findViewById<MaterialButton>(R.id.sendDirButton)?.let { b ->
            applyPill(activity, s, b, "start", bgKey = "start.fill", textKey = "start.color", main = false, frameKey = null)
        }

        // Toggle buttons (Send/Receive + the five OS buttons), each styled independently.
        applyToggleButtons(activity, s)

        // Cancel button background / border / corner radius (a <Button> inflates as a MaterialButton).
        activity.findViewById<MaterialButton>(R.id.cancelButton)?.let { cancel ->
            applyPill(activity, s, cancel, "cancel", bgKey = "cancel.fill", textKey = "cancel.color", main = false)
        }

        // "About" was a bare word you could tap; every action in the theme is a pill.
        activity.findViewById<TextView>(R.id.aboutButton)?.let { about ->
            val color = s.colorOrNull("about.color") ?: Defaults.YELLOW
            about.background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(Theme.ripple(color)),
                GradientDrawable().apply {
                    cornerRadius = dpToPx(activity, Theme.PILL_RADIUS_DP).toFloat()
                    setColor(android.graphics.Color.TRANSPARENT)
                    setStroke(dpToPx(activity, Theme.PILL_REGULAR_DP).coerceAtLeast(1), color)
                },
                null,
            )
            val h = dpToPx(activity, 12f)
            val v = dpToPx(activity, 3f)
            about.setPadding(h, v, h, v)
        }


        // Bluetooth switch + idle icon tint.
        applySwitch(activity)
        activity.findViewById<ImageView>(R.id.bluetoothIcon)?.drawable
            ?.setTint(bluetoothIconColor(activity, connected = false))

        // Progress bars: the desktop's shape rather than Android's hairline -- a bordered empty
        // box that fills up, at whatever thickness 白い熊 sets (白い熊, 2026-08-09).
        for (barId in listOf(R.id.progressBar, R.id.totalProgressBar)) {
            applyProgressBar(activity, s, barId)
        }
        // the sent/total, rate and ETA lines above the bars follow the bar's colour
        for (labelId in listOf(R.id.progressDetails, R.id.progressTotalDetails)) {
            activity.findViewById<TextView>(labelId)
                ?.setTextColor(s.colorOrNull("progress.color") ?: Defaults.YELLOW)
        }

        // App logo / picture (the icon shown top-right while idle).
        applyLogoTint(activity)
    }

    // Tints the main-page logo (qrCodeView while it shows the app icon). MainActivity clears this tint
    // before drawing a QR code there — a tinted QR would be unscannable — and calls this again after.
    // Skipped while the view shows a QR code (tagged QR_TAG by MainActivity). It used to be skipped
    // for the whole length of a transfer, judged by the hidden start button — so a transfer that
    // never put up a code, a paired send for one, left the carpet untinted white (白い熊,
    // 2026-11-14).
    //
    // Uses a luminance-preserving colorize (not a flat SRC_IN tint): each pixel keeps its brightness but
    // takes the tint's hue, so the carpet's internal detail — the "FC" letters — stays visible instead of
    // flattening into one solid yellow shape.
    const val QR_TAG = "qr"

    fun applyLogoTint(activity: AppCompatActivity) {
        val iv = activity.findViewById<ImageView>(R.id.qrCodeView) ?: return
        if (iv.tag == QR_TAG) return
        iv.imageTintList = null // ensure no leftover flat tint
        val c = Settings(activity).colorOrNull("logo.tint") ?: Defaults.YELLOW
        iv.colorFilter = luminanceColorize(c)
    }

    // Maps greyscale luminance onto the given colour: dark pixels → black, bright pixels → full colour.
    private fun luminanceColorize(color: Int): android.graphics.ColorFilter {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val lr = 0.299f; val lg = 0.587f; val lb = 0.114f
        val m = floatArrayOf(
            r * lr, r * lg, r * lb, 0f, 0f,
            g * lr, g * lg, g * lb, 0f, 0f,
            b * lr, b * lg, b * lb, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
        return android.graphics.ColorMatrixColorFilter(m)
    }


    /// The desktop's progress bar, on Android: an empty box with a one-pixel border that fills
    /// with the bar colour, at a settable thickness. Android's stock bar is a hairline with a
    /// faint track, which reads as a different control entirely.
    private fun applyProgressBar(activity: AppCompatActivity, s: Settings, barId: Int) {
        val bar = activity.findViewById<ProgressBar>(barId) ?: return
        val color = s.colorOrNull("progress.color") ?: Defaults.YELLOW
        val height = dpToPx(activity, s.sizeOrNull("progress.height") ?: 15f)

        val track = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(s.colorOrNull("window.bg") ?: Defaults.BLACK)
            setStroke(dpToPx(activity, 1f), color)
            cornerRadius = dpToPx(activity, 2f).toFloat()
        }
        val fill = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
        }
        val clipped = ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL)
        val layers = LayerDrawable(arrayOf(track, clipped))
        layers.setId(0, android.R.id.background)
        layers.setId(1, android.R.id.progress)
        // The fill sits inside the border rather than on top of it.
        val inset = dpToPx(activity, 2f)
        layers.setLayerInset(1, inset, inset, inset, inset)

        val progress = bar.progress
        bar.progressDrawable = layers
        bar.progressTintList = null
        bar.progress = 0
        bar.progress = progress
        bar.layoutParams = bar.layoutParams.apply { this.height = height }
        bar.minimumHeight = height
    }

    // An explicit size and XML autosizing are mutually exclusive: setTextSize() on a view with
    // autoSizeTextType="uniform" throws IllegalStateException. The program title and the three
    // send-row buttons autosize so they shrink to fit the Mate XT's folded panel instead of being
    // clipped, which would have made every one of these call sites crash the moment 白い熊 set a
    // size for one of them on the UI page. Switching autosizing off first is the right resolution
    // rather than a workaround: an explicit size is 白い熊 saying exactly how big it should be, and
    // that has to win over the layout fitting it automatically.
    private fun setTextSizeSp(v: TextView, sp: Float) {
        TextViewCompat.setAutoSizeTextTypeWithDefaults(v, TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE)
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    private fun applyText(
        activity: AppCompatActivity, s: Settings, id: Int, key: String, setText: Boolean,
        default: Int = Defaults.YELLOW,
    ) {
        val v = activity.findViewById<TextView>(id) ?: return
        if (setText) s.text("$key.text").ifEmpty { null }?.let { v.text = it }
        v.setTextColor(s.colorOrNull("$key.color") ?: default)
        applyTypeface(s, v, key)
        s.size("$key.size").let { if (it > 0f) setTextSizeSp(v, it) }
    }

    private fun applyTypeface(s: Settings, v: TextView, key: String) {
        val family = s.family("$key.family")
        val style = s.style("$key.style")
        if (family.isEmpty() && style < 0) return
        val effectiveStyle = if (style >= 0) style else (v.typeface?.style ?: Typeface.NORMAL)
        v.typeface = FontUtil.typeface(family, effectiveStyle) ?: Typeface.create(v.typeface, effectiveStyle)
    }

    // (button view id -> catalog key) for the seven toggle buttons.
    private val toggleButtons = listOf(
        R.id.sendButton to "send",
        R.id.receiveButton to "receive",
        R.id.hotspotButton to "hotspot",
        R.id.sharedNetworkButton to "sharedNetwork",
        R.id.androidButton to "androidOs",
        R.id.iosButton to "iosOs",
        R.id.linuxButton to "linuxOs",
        R.id.macButton to "macOs",
        R.id.windowsButton to "windowsOs",
    )

    val toggleIds = toggleButtons.map { it.first }


    private fun dpToPx(activity: AppCompatActivity, dp: Float) =
        (dp * activity.resources.displayMetrics.density).toInt()

    // Each toggle is its own pill, as on the desktop: the group spaces them apart and rounds the
    // inner corners too, where it would otherwise join them into one segmented bar. Nothing is
    // filled; the selected button carries the main pill's 3 dp frame and bold label, the others the
    // regular 1.25 dp one, so a group change restyles the whole group.
    private fun applyToggleButtons(activity: AppCompatActivity, s: Settings) {
        val groups = toggleButtons.mapNotNull { (id, _) ->
            activity.findViewById<MaterialButton>(id)?.parent as? MaterialButtonToggleGroup
        }.distinct()
        for (group in groups) {
            group.spacing = dpToPx(activity, 8f)
            group.innerCornerSize = com.google.android.material.shape.RelativeCornerSize(0.5f)
            // Once per group, however often onResume re-applies the look.
            if (group.getTag(R.id.toggleStyleListener) == null) {
                group.setTag(R.id.toggleStyleListener, true)
                group.addOnButtonCheckedListener { _, _, _ -> styleToggles(activity, Settings(activity)) }
            }
        }
        styleToggles(activity, s)
    }

    private fun styleToggles(activity: AppCompatActivity, s: Settings) {
        for ((id, key) in toggleButtons) {
            val b = activity.findViewById<MaterialButton>(id) ?: continue
            val checked = b.isChecked
            val fill = if (checked) s.colorOrNull("$key.selFill") else s.colorOrNull("$key.unselFill")
            val text = if (checked) s.colorOrNull("$key.selText") else s.colorOrNull("$key.unselText")
            Theme.pill(
                b,
                accent = s.colorOrNull("$key.stroke") ?: Defaults.YELLOW,
                background = fill ?: Defaults.BLACK,
                main = checked,
                width = if (checked) null else s.sizeOrNull("$key.strokeWidth"),
            )
            b.setTextColor(Theme.enabledList(text ?: Defaults.YELLOW))
            b.cornerRadius = dpToPx(activity, s.sizeOrNull("$key.cornerRadius") ?: Theme.PILL_RADIUS_DP)
            applyTypeface(s, b, key)
        }
    }

    // The output log is a plain TextView, so its background/border/corner radius come from a custom
    // GradientDrawable. The fork default gives it a black fill; a border appears once a width is set.
    private fun applyOutputBox(activity: AppCompatActivity, s: Settings) {
        val output = activity.findViewById<TextView>(R.id.outputBox) ?: return
        val d = android.graphics.drawable.GradientDrawable()
        d.setColor(s.colorOrNull("output.bg") ?: Defaults.BLACK)
        val width = s.sizeOrNull("output.strokeWidth") ?: Defaults.BORDER_WIDTH
        if (width > 0f) d.setStroke(dpToPx(activity, width), s.colorOrNull("output.stroke") ?: Defaults.YELLOW)
        val radius = s.sizeOrNull("output.cornerRadius") ?: Defaults.CORNER_RADIUS
        if (radius > 0f) d.cornerRadius = dpToPx(activity, radius).toFloat()
        output.background = d
        val pad = dpToPx(activity, 8f)
        output.setPadding(pad, pad, pad, pad)
    }

    // The remembered-directory button sits under the start button and is styled with it, so the
    // pair reads as one control rather than two unrelated ones.
    private fun applyLastFolderButton(activity: AppCompatActivity, s: Settings) {
        val b = activity.findViewById<MaterialButton>(R.id.lastFolderButton) ?: return
        applyPill(activity, s, b, "start", bgKey = "start.fill", textKey = "start.color", main = false, frameKey = null)
        applyTypeface(s, b, "start")
        s.size("start.size").let { if (it > 0f) setTextSizeSp(b, it) }
    }

    /**
     * One of the page's action buttons as a theme pill (Theme.pill). The colours and the radius
     * are still 白い熊's to set on the UI page under [key]; the frame follows "<key>.strokeWidth"
     * when that is set, and the theme's main / regular weight when it is not. A button that only
     * borrows another's colours passes frameKey = null, so it keeps its own weight.
     */
    private fun applyPill(
        activity: AppCompatActivity,
        s: Settings,
        b: MaterialButton,
        key: String,
        bgKey: String,
        textKey: String,
        main: Boolean,
        frameKey: String? = "$key.strokeWidth",
    ) {
        val accent = s.colorOrNull("$key.stroke") ?: Defaults.YELLOW
        Theme.pill(
            b,
            accent = accent,
            background = s.colorOrNull(bgKey) ?: Defaults.BLACK,
            main = main,
            width = frameKey?.let { s.sizeOrNull(it) },
        )
        // The label keeps its own colour where one is set; the frame's otherwise.
        b.setTextColor(Theme.enabledList(s.colorOrNull(textKey) ?: accent))
        b.cornerRadius = dpToPx(activity, s.sizeOrNull("$key.cornerRadius") ?: Theme.PILL_RADIUS_DP)
        applyTypeface(s, b, key)
    }

    private fun applyStartButton(activity: AppCompatActivity, s: Settings) {
        val start = activity.findViewById<MaterialButton>(R.id.startButton) ?: return
        applyTypeface(s, start, "start")
        s.size("start.size").let { if (it > 0f) setTextSizeSp(start, it) }
        // Pick the label matching the current mode selection (defaults match the XML).
        val modeGroup = activity.findViewById<MaterialButtonToggleGroup>(R.id.modeGroup)
        start.text = if (modeGroup?.checkedButtonId == R.id.receiveButton) {
            s.textOr("start.folderText", activity.getString(R.string.selectFolder))
        } else {
            s.textOr("start.filesText", activity.getString(R.string.selectFiles))
        }
        // The directory button says the same thing in either mode; it is only shown while sending.
        activity.findViewById<MaterialButton>(R.id.sendDirButton)?.let { dir ->
            applyTypeface(s, dir, "start")
            s.size("start.size").let { if (it > 0f) setTextSizeSp(dir, it) }
            dir.text = s.textOr("start.dirText", activity.getString(R.string.directoryToSend))
        }
    }

    // The theme's switch — outline and dot, never filled (Theme.styleSwitch).
    private fun applySwitch(activity: AppCompatActivity) {
        for (switchId in listOf(R.id.bluetoothSwitch, R.id.reachableSwitch)) {
            activity.findViewById<androidx.appcompat.widget.SwitchCompat>(switchId)
                ?.let { Theme.styleSwitch(it) }
        }
    }
}
