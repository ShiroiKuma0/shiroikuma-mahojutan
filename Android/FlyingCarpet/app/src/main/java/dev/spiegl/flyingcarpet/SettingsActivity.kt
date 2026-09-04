package dev.spiegl.flyingcarpet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton
import java.io.File

// The "白い熊 魔法絨毯 UI" customization page. Built in code from UiCatalog so the surface list stays
// in one place, with the Export / Import section (backup folder + the automation surface) pinned at
// the very top. Each text surface carries a live preview that reflects its text, colour, font,
// style, and size as they are edited.
//
// The look is the shiroikuma-kxkb settings page (白い熊, 2026-07-25): every heading carries a
// **text-width** underline, top-level sections are separated by a full-width 1 px hairline, and the
// indentation ladder is kxkb's — section 36 dp → element 54 dp → control 72 dp.
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: Settings
    private lateinit var fontPicker: ActivityResultLauncher<Array<String>>
    private var previewBaseColor: Int = Color.BLACK

    // The Export/Import panel while it is open, so onResume can re-render it (it may have sent
    // 白い熊 to the All-Files-Access screen, and the grant prompt has to clear on the way back).
    private var exportPanel: ExportImportPanel? = null

    // Sentinel value for the "Add external font…" entry that lives in every font dropdown, and the
    // surface key whose dropdown launched the picker (so the new font auto-selects there).
    private val addFontValue = "__add_external_font__"
    private var pendingFontKey: String? = null

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()

    // kxkb's indentation ladder: section headings sit at 36 dp, element headings at 54 dp, and the
    // controls (and every settings row) at 72 dp — so the section → element → control hierarchy
    // reads at a glance without the page marching off the right edge.
    private val sectionIndent get() = dp(36)
    private val elementIndent get() = dp(54)
    private val indent get() = dp(72)

    private val sampleText = "Aa Gg 0123 — 白い熊 魔法絨毯"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        // The page is yellow-on-black by default, so previews start from yellow text.
        previewBaseColor = Defaults.YELLOW
        fontPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onFontPicked(uri) else pendingFontKey = null
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }

        // ── Header ── (the page styles itself from the "page.*" keys; see applyKind / "This settings page")
        content.addView(TextView(this).apply {
            text = getString(R.string.uiSettingsTitle)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTypeface(typeface, Typeface.BOLD)
            applyKind(this, "page.title")
        })
        content.addView(TextView(this).apply {
            val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
            text = "Build $ver"
            setPadding(0, dp(2), 0, 0)
            alpha = 0.6f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            applyKind(this, "page.note")
        })
        content.addView(TextView(this).apply {
            text = "Changes apply when you return to the main screen. Leave a field blank, or tap “Default” in a colour picker, to restore the yellow-on-black default. Border widths and corner radii are sliders that start at zero."
            setPadding(0, dp(8), 0, dp(8))
            alpha = 0.7f
            applyKind(this, "page.note")
        })

        val topButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(8))
        }
        topButtons.addView(pageButton("Done") { finish() })
        topButtons.addView(pageButton("Reset all to defaults") { confirmResetAll() }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(8) }
        })
        content.addView(topButtons)

        // ── Export / Import ──
        // First section on the page, matching the Kōjiki UI page: the backup folder and the panel
        // that carries every setting in this app out to a .zip and back. The automation rows sit in
        // this same section, directly below the panel entry — backup lives with backup (the
        // sister-app convention), never in a section of its own.
        content.addView(sectionHeader("Export / Import"))
        content.addView(sectionNote("Carry everything you set in this app to another device, or put it back."))
        addExportImportRows(content)

        // ── Sections ──
        // One block per logical area of the main screen (UiCatalog.sections). Each block carries its
        // own text surfaces and colour/border groups, so everything about an area lives together. Each
        // "Font" dropdown ends with "Add external font…", which adds a .ttf/.otf and makes it available
        // in every surface's font menu — so there's no separate fonts section.
        UiCatalog.sections.forEach { section ->
            content.addView(sectionHeader(section.title))
            section.note?.let { content.addView(sectionNote(it)) }

            // The self-styling section's changes affect this very page, so offer a repaint button.
            if (section.pageStyle) {
                content.addView(pageButton("Apply to this page") { recreate() }.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(4); bottomMargin = dp(4) }
                })
            }

            for (surface in section.surfaces) {
                content.addView(elementHeader(surface.title))
                val box = sectionBox()
                val preview = makePreview()
                val update = { stylePreview(preview, surface) }
                for (field in surface.labels) addTextField(box, field, update)
                if (surface.hasTextColor) addColorControl(box, "${surface.key}.color", surface.colorLabel, Defaults.YELLOW, update)
                addFontControls(box, surface.key, update)
                addNumberField(box, "${surface.key}.size", "Size (sp)", update)
                // Folded-in colours / borders / radii for this element (fills, strokes, sliders).
                for (color in surface.extraColors) addColorControl(box, color.key, color.label, color.default, null)
                for (dim in surface.extraDims) addSliderField(box, dim.key, dim.label, dim.max, dim.default, null)
                box.addView(propertyLabel("Preview"))
                box.addView(preview)
                addResetButton(box, surfaceKeys(surface))
                update()
                content.addView(box)
            }

            for (group in section.colorGroups) {
                content.addView(elementHeader(group.title))
                val box = sectionBox()
                for (color in group.colors) addColorControl(box, color.key, color.label, color.default, null)
                for (dim in group.dims) addSliderField(box, dim.key, dim.label, dim.max, dim.default, null)
                addResetButton(box, group.colors.map { it.key } + group.dims.map { it.key })
                content.addView(box)
            }
        }

        setContentView(ScrollView(this).apply {
            addView(content)
            settings.colorOrNull("page.bg")?.let { setBackgroundColor(it) }
        })
    }

    // ── Builders ──

    // The page's accent — the colour headings, underlines and hairlines are drawn in. Follows
    // "page.section.color" where 白い熊 set one, so a repainted page repaints its rules too.
    private val accent: Int get() = settings.colorOrNull("page.section.color") ?: Defaults.YELLOW

    /**
     * Top level: a logical area of the app ("Export / Import", "Main page", "Title bar", …). The
     * kxkb shape — a full-width 1 px hairline marking the boundary with the previous group, then an
     * indented bold heading whose underline is only as wide as the words above it.
     */
    private fun sectionHeader(title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        // Explicitly full width: the hairline below is MATCH_PARENT, and inside a wrap_content
        // parent that would collapse to the heading's width instead of spanning the page.
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        setPadding(0, dp(10), 0, dp(2))
        addView(
            View(this@SettingsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(accent)
            },
        )
        addView(underlinedHeading(title, sizeSp = 20f, indentPx = sectionIndent, rulePx = dp(3), kind = "page.section"))
    }

    /** Middle level: one customizable element within a section. Indented one step, thinner rule. */
    private fun elementHeader(title: String) =
        underlinedHeading(title, sizeSp = 17f, indentPx = elementIndent, rulePx = dp(2), kind = "page.element")
            .apply { setPadding(0, dp(12), 0, 0) }

    /**
     * A heading whose underline is **text-wide, never full-width** (白い熊, 2026-07-25): the label and
     * the rule share a wrap_content column, so the rule stops where the words do.
     */
    private fun underlinedHeading(title: String, sizeSp: Float, indentPx: Int, rulePx: Int, kind: String) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = indentPx }
            setPadding(0, dp(8), 0, 0)
            addView(
                TextView(this@SettingsActivity).apply {
                    text = title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
                    setTypeface(typeface, Typeface.BOLD)
                    applyKind(this, kind)
                },
            )
            addView(
                View(this@SettingsActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rulePx)
                        .apply { topMargin = dp(2) }
                    setBackgroundColor(settings.colorOrNull("$kind.color") ?: accent)
                },
            )
        }

    // The one-line explanation shown under some section titles.
    private fun sectionNote(text: String) = TextView(this).apply {
        this.text = text
        setPadding(elementIndent, dp(6), dp(16), dp(2))
        alpha = 0.7f
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        applyKind(this, "page.note")
    }

    private fun dpPx(v: Float) = (v * resources.displayMetrics.density).toInt()

    // Every preference key a text surface owns, for its "Reset to default" button.
    private fun surfaceKeys(s: TextSurface): List<String> {
        val keys = s.labels.map { it.key }.toMutableList()
        if (s.hasTextColor) keys.add("${s.key}.color")
        keys.add("${s.key}.family"); keys.add("${s.key}.style"); keys.add("${s.key}.size")
        s.extraColors.forEach { keys.add(it.key) }
        s.extraDims.forEach { keys.add(it.key) }
        return keys
    }

    // A per-group "Reset to default" button: clears that group's keys and rebuilds the page.
    private fun addResetButton(parent: LinearLayout, keys: List<String>) {
        parent.addView(pageButton("Reset to default") {
            settings.remove(keys)
            recreate()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
    }

    // The page's own buttons (Done / Reset / Apply). A MaterialButton so border + corner radius apply.
    // Styled from "page.button.*", defaulting to black fill, yellow text + 1 dp border, 10 dp radius.
    private fun pageButton(label: String, onClick: () -> Unit) = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        stylePageButton(this)
    }

    private fun stylePageButton(b: MaterialButton) {
        b.setTextColor(settings.colorOrNull("page.button.color") ?: Defaults.YELLOW)
        val family = settings.family("page.button.family")
        val style = settings.style("page.button.style")
        if (family.isNotEmpty() || style >= 0) {
            val eff = if (style >= 0) style else (b.typeface?.style ?: Typeface.NORMAL)
            b.typeface = FontUtil.typeface(family, eff) ?: Typeface.create(b.typeface, eff)
        }
        settings.size("page.button.size").let { if (it > 0f) b.setTextSize(TypedValue.COMPLEX_UNIT_SP, it) }
        b.backgroundTintList = ColorStateList.valueOf(settings.colorOrNull("page.button.bg") ?: Defaults.BLACK)
        b.strokeColor = ColorStateList.valueOf(settings.colorOrNull("page.button.stroke") ?: Defaults.YELLOW)
        b.strokeWidth = dpPx(settings.sizeOrNull("page.button.strokeWidth") ?: Defaults.BORDER_WIDTH)
        b.cornerRadius = dpPx(settings.sizeOrNull("page.button.cornerRadius") ?: Defaults.CORNER_RADIUS)
    }

    // Applies the settings page's own "page.<kind>.*" styling (colour / font / style / size) to one of
    // its chrome TextViews, on top of the builder's default look. Lets this screen restyle itself.
    private fun applyKind(tv: TextView, kind: String) {
        settings.colorOrNull("$kind.color")?.let { tv.setTextColor(it) }
        val family = settings.family("$kind.family")
        val style = settings.style("$kind.style")
        if (family.isNotEmpty() || style >= 0) {
            val eff = if (style >= 0) style else (tv.typeface?.style ?: Typeface.NORMAL)
            tv.typeface = FontUtil.typeface(family, eff) ?: Typeface.create(tv.typeface, eff)
        }
        settings.size("$kind.size").let { if (it > 0f) tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, it) }
    }

    // The indented container holding an element's controls (the ladder's bottom step, 72 dp).
    private fun sectionBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(indent, 0, 0, 0)
    }

    private fun propertyLabel(text: String) = TextView(this).apply {
        this.text = text
        setPadding(0, dp(10), 0, dp(2))
        alpha = 0.75f
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        applyKind(this, "page.label")
    }

    private fun fullWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun makePreview() = TextView(this).apply {
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setBackgroundColor(0x22FFFFFF)
        layoutParams = fullWidth().apply { topMargin = dp(6) }
    }

    // Render the preview with the surface's current text + colour + font + style + size. Surfaces with
    // no editable labels (the "This settings page" text kinds) just preview the sample string.
    private fun stylePreview(preview: TextView, surface: TextSurface) {
        val key = surface.key
        val labelKey = surface.labels.firstOrNull()?.key
        preview.text = (labelKey?.let { settings.text(it) } ?: "").ifEmpty { sampleText }
        preview.setTextColor(settings.colorOrNull("$key.color") ?: previewBaseColor)
        val size = settings.size("$key.size")
        preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (size > 0f) size else 18f)
        val style = settings.style("$key.style").let { if (it >= 0) it else Typeface.NORMAL }
        preview.typeface = FontUtil.typeface(settings.family("$key.family"), style)
            ?: Typeface.create(Typeface.DEFAULT, style)
    }

    private fun addTextField(parent: LinearLayout, field: LabelField, onChanged: (() -> Unit)?) {
        parent.addView(propertyLabel(field.label))
        parent.addView(EditText(this).apply {
            layoutParams = fullWidth()
            setText(settings.text(field.key))
            hint = "Default"
            inputType = InputType.TYPE_CLASS_TEXT
            addTextChangedListener(simpleWatcher {
                settings.setText(field.key, it)
                onChanged?.invoke()
            })
        })
    }

    private fun addNumberField(parent: LinearLayout, key: String, label: String, onChanged: (() -> Unit)?) {
        parent.addView(propertyLabel(label))
        parent.addView(EditText(this).apply {
            layoutParams = fullWidth()
            val v = settings.size(key)
            if (v > 0f) setText(if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString())
            hint = "Default"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            addTextChangedListener(simpleWatcher {
                settings.setSize(key, it.toFloatOrNull() ?: 0f)
                onChanged?.invoke()
            })
        })
    }

    private fun addFontControls(parent: LinearLayout, key: String, onChanged: (() -> Unit)?) {
        // System families + any external fonts already added, then an "Add external font…" action.
        val values = settings.familyValues() + addFontValue
        val labels = settings.familyLabels() + "➕  Add external font…"
        parent.addView(propertyLabel("Font"))
        parent.addView(Spinner(this).apply {
            layoutParams = fullWidth()
            adapter = FontAdapter(values, labels)
            var lastIndex = values.indexOf(settings.family("$key.family")).coerceAtLeast(0)
            setSelection(lastIndex)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (values[pos] == addFontValue) {
                        setSelection(lastIndex)            // keep the real selection; this is an action
                        pendingFontKey = key               // new font should land on this surface
                        fontPicker.launch(arrayOf("*/*"))
                    } else {
                        lastIndex = pos
                        settings.setFamily("$key.family", values[pos]); onChanged?.invoke()
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        })
        parent.addView(propertyLabel("Style"))
        parent.addView(Spinner(this).apply {
            layoutParams = fullWidth()
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, Settings.STYLE_LABELS)
            setSelection(Settings.STYLE_VALUES.indexOf(settings.style("$key.style")).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    settings.setStyle("$key.style", Settings.STYLE_VALUES[pos]); onChanged?.invoke()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        })
    }

    private fun addColorControl(parent: LinearLayout, key: String, label: String, default: Int?, onChanged: (() -> Unit)?) {
        parent.addView(propertyLabel(label))

        val swatch = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(36), dp(24)) }
        val valueText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) }
            applyKind(this, "page.label")
        }

        fun refresh() {
            val c = settings.colorOrNull(key)
            if (c == null) {
                // Unset → show the baseline default that will actually be used (yellow/black, usually).
                swatch.setBackgroundColor(default ?: Color.LTGRAY)
                valueText.text = if (default != null) String.format("Default (#%06X)", 0xFFFFFF and default) else "Default"
            } else {
                swatch.setBackgroundColor(c); valueText.text = String.format("#%08X", c)
            }
        }
        refresh()

        parent.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = fullWidth().apply { topMargin = dp(2) }
            addView(swatch)
            addView(valueText)
            setOnClickListener {
                showColorPicker(this@SettingsActivity, settings.colorOrNull(key) ?: default ?: Color.LTGRAY) { result ->
                    settings.setColor(key, result); refresh(); onChanged?.invoke()
                }
            }
        })
    }

    // A 0..max dp slider for border widths and corner radii. The track starts at 0, but the initial
    // position reflects the field's default (e.g. 1 dp border, 10 dp radius) until 白い熊 drags it.
    // Stored via setDim, so dragging to 0 stores an explicit 0 (no border) rather than reverting.
    private fun addSliderField(parent: LinearLayout, key: String, label: String, max: Int, default: Float, onChanged: (() -> Unit)?) {
        val current = (settings.sizeOrNull(key) ?: default).toInt().coerceIn(0, max)
        val valueLabel = propertyLabel("$label: $current dp")
        parent.addView(valueLabel)
        parent.addView(SeekBar(this).apply {
            layoutParams = fullWidth()
            this.max = max
            progress = current
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    settings.setDim(key, value.toFloat())
                    valueLabel.text = "$label: $value dp"
                    onChanged?.invoke()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        })
    }

    // A font spinner whose every entry is drawn in its own typeface.
    private inner class FontAdapter(private val values: List<String>, labels: List<String>) :
        ArrayAdapter<String>(this@SettingsActivity, android.R.layout.simple_spinner_item, labels) {
        init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            (super.getView(position, convertView, parent) as TextView).apply { typeface = FontUtil.previewTypeface(values[position]) }
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            (super.getDropDownView(position, convertView, parent) as TextView).apply {
                typeface = FontUtil.previewTypeface(values[position])
                setPadding(paddingLeft, dp(8), paddingRight, dp(8))
            }
    }

    private fun simpleWatcher(onText: (String) -> Unit) = object : TextWatcher {
        override fun afterTextChanged(e: Editable?) = onText(e?.toString() ?: "")
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    }

    // ── Custom font picking ──

    private fun onFontPicked(uri: Uri) {
        try {
            val dir = File(filesDir, "fonts").apply { mkdirs() }
            val dest = File(dir, sanitizeName(queryDisplayName(uri)))
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            } ?: run { toast("Could not open the selected file."); return }
            Typeface.createFromFile(dest) // validate it really is a font
            settings.addFontFile(dest.absolutePath)
            // Select the just-added font on the surface whose dropdown launched the picker.
            pendingFontKey?.let { settings.setFamily("$it.family", "file:${dest.absolutePath}") }
            pendingFontKey = null
            recreate()
        } catch (e: Exception) {
            pendingFontKey = null
            toast("That file isn’t a usable font.")
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.let { name -> return name }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "font.ttf"
    }

    private fun sanitizeName(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim()
        return cleaned.ifEmpty { "font.ttf" }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ── Export / Import section ──────────────────────────────────────────────────────────────

    // The folder line under "Export / Import…", kept so it can be refreshed in place after the panel
    // closes or after a trip to the All-Files-Access screen.
    private var exportSummaryView: TextView? = null

    override fun onResume() {
        super.onResume()
        refreshExportSummary()
        exportPanel?.refresh()
    }

    private fun refreshExportSummary() {
        val view = exportSummaryView ?: return
        val (summary, warn) = ExportImportStatus.summary(this)
        view.text = summary
        view.setTextColor(ExportImportStatus.color(this, warn))
    }

    /**
     * The Export/Import section's rows. The automation controls sit **inside** this section,
     * directly below the panel entry — the sister-app convention: this is a backup feature, so
     * 白い熊 finds it where backup lives, and every app looks the same.
     *
     * Four rows in contract order: the panel, the master switch (default ON),
     * 「Use authorization token?」 (default OFF), and the token itself — which is shown only while
     * the switch above it is on.
     */
    private fun addExportImportRows(parent: LinearLayout) {
        val (summary, warn) = ExportImportStatus.summary(this)
        val summaryView = rowSummary(summary, ExportImportStatus.color(this, warn))
        exportSummaryView = summaryView
        parent.addView(
            settingRow("Export / Import…", summaryView) {
                val panel = ExportImportPanel(this) { finish() }
                exportPanel = panel
                panel.show(onDismiss = { exportPanel = null; refreshExportSummary() })
            },
        )

        // Master switch — default ON since v2 of the contract. It stays a switch rather than being
        // removed because it is the only way to close this app off, and a feature that can be
        // turned on but never off is one 白い熊 cannot retreat from.
        val switch = SwitchCompat(this).apply {
            isChecked = AutomationAuth.enabled(this@SettingsActivity)
            thumbTintList = ColorStateList.valueOf(accent)
            trackTintList = ColorStateList.valueOf(accent)
            setOnCheckedChangeListener { _, checked -> AutomationAuth.setEnabled(this@SettingsActivity, checked) }
        }
        parent.addView(
            settingRow(
                "Automation export",
                rowSummary("Let sister apps trigger this app's export, and let 白い熊 応用管理 back its data up and put it back.", null),
                widget = switch,
            ) { switch.toggle() },
        )

        // Token row — tap copies the WHOLE token, "Regenerate" replaces it. Built before the switch
        // that governs it so the switch's listener has a row to show and hide.
        val tokenSummary = rowSummary(AutomationAuth.abbreviate(AutomationAuth.token(this)), null)
        val regenerate = TextView(this).apply {
            text = "Regenerate"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(8), dp(4), dp(8))
            applyKind(this, "page.label")
            setTextColor(accent)
            setOnClickListener {
                tokenSummary.text = AutomationAuth.abbreviate(AutomationAuth.regenerateToken(this@SettingsActivity))
                toast("New token — update anywhere you pasted the old one.")
            }
        }
        val tokenRow = settingRow("Automation token", tokenSummary, widget = regenerate) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(
                ClipData.newPlainText("mahojutan automation token", AutomationAuth.token(this)),
            )
            toast("Token copied to the clipboard.")
        }

        // 「Use authorization token?」 — default OFF (v2). A pasted secret cannot survive a wipe, and
        // the case this contract now serves is 応用管理 restoring apps onto a clean phone where
        // nothing has been configured and nobody has pasted anything.
        val requireToken = SwitchCompat(this).apply {
            isChecked = AutomationAuth.requireToken(this@SettingsActivity)
            thumbTintList = ColorStateList.valueOf(accent)
            trackTintList = ColorStateList.valueOf(accent)
            setOnCheckedChangeListener { _, checked ->
                AutomationAuth.setRequireToken(this@SettingsActivity, checked)
                // Hidden when the token is not being asked for: a 48-character secret sitting under
                // an off switch invites 白い熊 to paste it somewhere it will do nothing.
                tokenRow.visibility = if (checked) View.VISIBLE else View.GONE
            }
        }
        parent.addView(
            settingRow(
                "Use authorization token?",
                rowSummary(
                    "Off: any sister app may drive this app's automation. On: a caller must also present the token below. " +
                        "Either way the data door checks the caller's package, uid and signing certificate.",
                    null,
                ),
                widget = requireToken,
            ) { requireToken.toggle() },
        )

        tokenRow.visibility = if (AutomationAuth.requireToken(this)) View.VISIBLE else View.GONE
        parent.addView(tokenRow)
    }

    /** kxkb's settings-row shape: title over summary at the control indent, optional widget right. */
    private fun settingRow(
        title: String,
        summaryView: TextView,
        widget: View? = null,
        onClick: (() -> Unit)? = null,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(indent, dp(7), dp(16), dp(7))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        onClick?.let {
            isClickable = true
            val value = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
            setBackgroundResource(value.resourceId)
            setOnClickListener { _ -> it() }
        }
        addView(
            LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(
                    TextView(this@SettingsActivity).apply {
                        text = title
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        applyKind(this, "page.label")
                    },
                )
                addView(summaryView)
            },
        )
        widget?.let { addView(it) }
    }

    private fun rowSummary(text: String, color: Int?) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        applyKind(this, "page.note")
        color?.let { setTextColor(it) }
        if (color == null) alpha = 0.75f
    }

    private fun confirmResetAll() {
        AlertDialog.Builder(this)
            .setTitle("Reset all UI settings?")
            .setMessage("This clears every colour, text, font, size, and border override and restores the yellow-on-black defaults. (Added custom fonts are also cleared.)")
            .setPositiveButton("Reset") { _, _ ->
                settings.clearAll()
                recreate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
