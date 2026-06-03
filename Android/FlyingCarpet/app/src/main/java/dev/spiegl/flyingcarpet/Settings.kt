package dev.spiegl.flyingcarpet

import android.content.Context
import android.graphics.Typeface
import java.io.File

// Persistent store for the "白い熊 魔法絨毯 UI" customization page.
//
// Every value is optional: an absent key means "inherit the original look", so a fresh install is
// pixel-identical to upstream until 白い熊 changes something. Appearance.apply() only overrides a
// property when its key is present, and "Reset" simply clears the key.
//
//   color  -> Int, absent = inherit
//   text   -> String, "" = inherit
//   size   -> Float (sp, or dp for border widths), <=0 = inherit
//   family -> String, "" = inherit; a system family name, or "file:<path>" for an external font
//   style  -> Int, <0 = inherit (Typeface.NORMAL/BOLD/ITALIC/BOLD_ITALIC)
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun colorOrNull(key: String): Int? = if (prefs.contains(key)) prefs.getInt(key, 0) else null
    fun setColor(key: String, value: Int?) = prefs.edit().apply {
        if (value == null) remove(key) else putInt(key, value)
    }.apply()

    fun text(key: String): String = prefs.getString(key, "") ?: ""
    fun textOr(key: String, default: String): String = text(key).ifEmpty { default }
    fun setText(key: String, value: String) = prefs.edit().apply {
        if (value.isEmpty()) remove(key) else putString(key, value)
    }.apply()

    fun size(key: String): Float = prefs.getFloat(key, 0f)
    fun setSize(key: String, value: Float) = prefs.edit().apply {
        if (value <= 0f) remove(key) else putFloat(key, value)
    }.apply()

    fun family(key: String): String = prefs.getString(key, "") ?: ""
    fun setFamily(key: String, value: String) = prefs.edit().apply {
        if (value.isEmpty()) remove(key) else putString(key, value)
    }.apply()

    fun style(key: String): Int = prefs.getInt(key, -1)
    fun setStyle(key: String, value: Int) = prefs.edit().apply {
        if (value < 0) remove(key) else putInt(key, value)
    }.apply()

    fun clearAll() = prefs.edit().clear().apply()

    // ── External font files ──────────────────────────────────────────────────
    // 白い熊 picks .ttf/.otf files; SettingsActivity copies each into filesDir/fonts (named after the
    // original file) and records its path here, one absolute path per line. A surface's font family
    // then stores "file:<path>"; the chooser label is the file's name (without extension).
    private fun fontFilePaths(): List<String> =
        (prefs.getString(FONT_FILES_KEY, "") ?: "").split("\n").filter { it.isNotBlank() }

    fun fontFiles(): List<Pair<String, String>> =
        fontFilePaths().map { File(it).nameWithoutExtension to it }

    fun addFontFile(path: String) {
        val paths = fontFilePaths().filter { it != path }.toMutableList().apply { add(path) }
        prefs.edit().putString(FONT_FILES_KEY, paths.joinToString("\n")).apply()
    }

    fun removeFontFile(path: String) {
        prefs.edit().putString(FONT_FILES_KEY, fontFilePaths().filter { it != path }.joinToString("\n")).apply()
    }

    // System families plus any picked font files, for the per-surface font chooser.
    fun familyValues(): List<String> = FONT_VALUES + fontFiles().map { "file:${it.second}" }
    fun familyLabels(): List<String> = FONT_LABELS + fontFiles().map { it.first }

    companion object {
        const val PREFS = "shiroikuma_ui"
        const val FONT_FILES_KEY = "__fontFiles"

        // System font families offered for every text surface.
        val FONT_VALUES = listOf(
            "", "sans-serif", "sans-serif-light", "sans-serif-condensed", "sans-serif-medium",
            "sans-serif-black", "sans-serif-smallcaps", "serif", "monospace", "serif-monospace",
            "casual", "cursive",
        )
        val FONT_LABELS = listOf(
            "(Default)", "Sans Serif", "Sans Serif Light", "Sans Serif Condensed", "Sans Serif Medium",
            "Sans Serif Black", "Sans Serif Small Caps", "Serif", "Monospace", "Serif Monospace",
            "Casual", "Cursive",
        )

        val STYLE_VALUES = listOf(-1, Typeface.NORMAL, Typeface.BOLD, Typeface.ITALIC, Typeface.BOLD_ITALIC)
        val STYLE_LABELS = listOf("(Default)", "Normal", "Bold", "Italic", "Bold Italic")
    }
}
