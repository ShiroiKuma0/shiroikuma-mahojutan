package dev.spiegl.flyingcarpet

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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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

    // Base indentation unit: 3× a normal 16dp indent.
    private val indent get() = dp(48)

    private val sampleText = "Aa Gg 0123 — 白い熊 魔法絨毯"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        previewBaseColor = TextView(this).currentTextColor
        fontPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onFontPicked(uri) else pendingFontKey = null
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }

        // ── Header ──
        content.addView(TextView(this).apply {
            text = getString(R.string.uiSettingsTitle)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTypeface(typeface, Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
            text = "Build $ver"
            setPadding(0, dp(2), 0, 0)
            alpha = 0.6f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        content.addView(TextView(this).apply {
            text = "Changes apply when you return to the main screen. Leave a field blank, or tap “Default” in a colour picker, to restore the original."
            setPadding(0, dp(8), 0, dp(8))
            alpha = 0.7f
        })

        val topButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(8))
        }
        topButtons.addView(Button(this).apply {
            text = "Done"
            setOnClickListener { finish() }
        })
        topButtons.addView(Button(this).apply {
            text = "Reset all to defaults"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(8) }
            setOnClickListener { confirmResetAll() }
        })
        content.addView(topButtons)

        // ── Text surfaces ──
        // Each "Font" dropdown ends with "Add external font…", which adds a .ttf/.otf and makes it
        // available in every surface's font menu — so there's no separate fonts section up here.
        content.addView(groupTitle("TEXT, FONTS & SIZES"))
        for (surface in UiCatalog.textSurfaces) {
            content.addView(sectionHeader(surface.title))
            val box = sectionBox()
            val preview = makePreview()
            val update = { stylePreview(preview, surface) }
            for (field in surface.labels) addTextField(box, field, update)
            addColorControl(box, "${surface.key}.color", surface.colorLabel, update)
            addFontControls(box, surface.key, update)
            addNumberField(box, "${surface.key}.size", "Size (sp)", update)
            box.addView(propertyLabel("Preview"))
            box.addView(preview)
            update()
            content.addView(box)
        }

        // ── Colour-only surfaces (+ border widths) ──
        content.addView(groupTitle("COLOURS & BORDERS"))
        for (group in UiCatalog.colorGroups) {
            content.addView(sectionHeader(group.title))
            val box = sectionBox()
            for (color in group.colors) addColorControl(box, color.key, color.label, null)
            for (dim in group.dims) addNumberField(box, dim.key, dim.label, null)
            content.addView(box)
        }

        setContentView(ScrollView(this).apply { addView(content) })
    }

    // ── Builders ──

    private fun groupTitle(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(24), 0, dp(4))
        alpha = 0.6f
    }

    private fun sectionHeader(title: String) = TextView(this).apply {
        text = title
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(8), dp(16), 0, dp(4))
    }

    // The indented container holding a section's controls (3× indent).
    private fun sectionBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(indent, 0, 0, 0)
    }

    private fun propertyLabel(text: String) = TextView(this).apply {
        this.text = text
        setPadding(0, dp(10), 0, dp(2))
        alpha = 0.75f
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    }

    private fun fullWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun makePreview() = TextView(this).apply {
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setBackgroundColor(0x14000000)
        layoutParams = fullWidth().apply { topMargin = dp(6) }
    }

    // Render the preview with the surface's current text + colour + font + style + size.
    private fun stylePreview(preview: TextView, surface: TextSurface) {
        val key = surface.key
        preview.text = settings.text(surface.labels.first().key).ifEmpty { sampleText }
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

    private fun addColorControl(parent: LinearLayout, key: String, label: String, onChanged: (() -> Unit)?) {
        parent.addView(propertyLabel(label))

        val swatch = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(36), dp(24)) }
        val valueText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) }
        }

        fun refresh() {
            val c = settings.colorOrNull(key)
            if (c == null) {
                swatch.setBackgroundColor(Color.LTGRAY); valueText.text = "Default"
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
                showColorPicker(this@SettingsActivity, settings.colorOrNull(key) ?: Color.LTGRAY) { result ->
                    settings.setColor(key, result); refresh(); onChanged?.invoke()
                }
            }
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
            .setMessage("This clears every colour, text, font, size, and border override and restores the original look. (Added custom fonts are also cleared.)")
            .setPositiveButton("Reset") { _, _ ->
                settings.clearAll()
                recreate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
