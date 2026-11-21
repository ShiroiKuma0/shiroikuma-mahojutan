package dev.spiegl.flyingcarpet

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

// A small ARGB colour picker: live preview, four 0–255 sliders, and an editable #AARRGGBB hex field.
// OK returns the chosen colour; "Default" returns null (revert to the inherited look); Cancel does nothing.
fun showColorPicker(context: Context, initial: Int, onResult: (Int?) -> Unit) {
    fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics,
    ).toInt()

    var current = initial
    var updating = false

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(12), 0, 0)
    }

    // The colour being picked, in a frame of the dialog's own — so a ground-coloured pick can
    // still be told from the dialog around it.
    val frame = Roles.color(context, "dialog.frame")
    fun swatch(color: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        setStroke(dp(1).coerceAtLeast(1), frame)
        cornerRadius = dp(6).toFloat()
    }
    val preview = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56))
        background = swatch(current)
    }
    root.addView(preview)

    // The field and the four sliders below take the dialog's field and slider roles when the
    // dialog is built (ForkDialog.wrap → Theme.watch).
    val hex = EditText(context).apply {
        setText(String.format("#%08X", current))
        gravity = Gravity.CENTER
        typeface = android.graphics.Typeface.MONOSPACE
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) }
    }
    root.addView(hex)

    val sliders = HashMap<String, SeekBar>()
    fun addSlider(name: String, value: Int): SeekBar {
        val label = TextView(context).apply {
            text = name
            setTextColor(ForkDialog.accent(context))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val bar = SeekBar(context).apply {
            max = 255
            progress = value
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label)
            addView(bar)
        }
        root.addView(row)
        sliders[name] = bar
        return bar
    }

    val aBar = addSlider("A", Color.alpha(current))
    val rBar = addSlider("R", Color.red(current))
    val gBar = addSlider("G", Color.green(current))
    val bBar = addSlider("B", Color.blue(current))

    fun syncFromSliders() {
        if (updating) return
        updating = true
        current = Color.argb(aBar.progress, rBar.progress, gBar.progress, bBar.progress)
        preview.background = swatch(current)
        hex.setText(String.format("#%08X", current))
        updating = false
    }

    val sliderListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) syncFromSliders()
        }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }
    listOf(aBar, rBar, gBar, bBar).forEach { it.setOnSeekBarChangeListener(sliderListener) }

    hex.addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(e: Editable?) {
            if (updating) return
            val parsed = try {
                Color.parseColor(e.toString().trim())
            } catch (ex: Exception) {
                return
            }
            updating = true
            current = parsed
            aBar.progress = Color.alpha(parsed)
            rBar.progress = Color.red(parsed)
            gBar.progress = Color.green(parsed)
            bBar.progress = Color.blue(parsed)
            preview.background = swatch(parsed)
            updating = false
        }
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    })

    ForkDialog.custom(
        context,
        "Pick a colour",
        root,
        listOf(
            "Cancel" to { d: android.app.Dialog -> d.dismiss() },
            "Default" to { d: android.app.Dialog -> d.dismiss(); onResult(null) },
            "OK" to { d: android.app.Dialog -> d.dismiss(); onResult(current) },
        ),
    )
}
