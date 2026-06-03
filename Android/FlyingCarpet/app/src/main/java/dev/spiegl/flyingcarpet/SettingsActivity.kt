package dev.spiegl.flyingcarpet

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
import com.google.android.material.button.MaterialButton
import java.io.File

// The "白い熊 魔法絨毯 UI" customization page. Built in code from UiCatalog so the surface list stays
// in one place. Indentation is deliberately deep (3× the usual ~16dp) per 白い熊's request, so the
// hierarchy — section → property → control — reads at a glance. Each text surface carries a live
// preview that reflects its text, colour, font, style, and size as they are edited.
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: Settings
    private lateinit var fontPicker: ActivityResultLauncher<Array<String>>
    private var previewBaseColor: Int = Color.BLACK

    // Sentinel value for the "Add external font…" entry that lives in every font dropdown, and the
    // surface key whose dropdown launched the picker (so the new font auto-selects there).
    private val addFontValue = "__add_external_font__"
    private var pendingFontKey: String? = null

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()

    // Base indentation unit for a section's controls. Deliberately deep so the section → element →
    // control hierarchy reads at a glance (16dp normal × 3, then ×3 again per 白い熊's request).
    private val indent get() = dp(144)

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

        // ── Sections ──
        // One block per logical area of the main screen (UiCatalog.sections). Each block carries its
        // own text surfaces and colour/border groups, so everything about an area lives together. Each
        // "Font" dropdown ends with "Add external font…", which adds a .ttf/.otf and makes it available
        // in every surface's font menu — so there's no separate fonts section.
        UiCatalog.sections.forEachIndexed { i, section ->
            if (i > 0) content.addView(divider())
            content.addView(sectionTitle(section.title))
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

    // Top level: a logical area of the main screen ("Main page", "Title bar", …). Flush left so the
    // nested element headers and deeply-indented controls read as belonging under it.
    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(2))
        applyKind(this, "page.section")
    }

    // The one-line explanation shown under some section titles.
    private fun sectionNote(text: String) = TextView(this).apply {
        this.text = text
        setPadding(dp(12), 0, 0, dp(4))
        alpha = 0.7f
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        applyKind(this, "page.note")
    }

    // A hairline between sections (faint white, visible on the dark page).
    private fun divider() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(20)
        }
        setBackgroundColor(0x33FFFFFF)
    }

    // Middle level: one customizable element within a section (a button, a label, a colour group).
    // Sub-item indent doubled (dp 36 → 72) per 白い熊's request.
    private fun elementHeader(title: String) = TextView(this).apply {
        text = title
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(72), dp(16), 0, dp(2))
        applyKind(this, "page.element")
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

    // The indented container holding an element's controls (3× indent).
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
