package dev.spiegl.flyingcarpet

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton

// Fork: the black-yellow theme's controls, in one place (the global black-yellow-theme skill is
// the spec). Every action is an outlined pill and never a filled one — 3 dp and bold for the one
// action a block is for, 1.25 dp and normal for the rest — and a switch is an outlined pill too,
// with a solid dot that sits right when on and left when off. Explanations are dim gold, never
// grey. The colours are settable roles on the UI page; these are only their defaults.
object Theme {
    const val PILL_MAIN_DP = 3f
    const val PILL_REGULAR_DP = 1.25f
    // More than half of any pill's height, so the ends are fully round.
    const val PILL_RADIUS_DP = 60f

    // "Off" is a darker, opaque yellow — 55 % of the way from black to the accent — never a
    // faded one, so a switch that is off reads as deliberate rather than disabled.
    val SWITCH_OFF = 0xFF8C8C00.toInt()
    // Explanations and descriptions, and the thin rule beside a described settings row.
    val DIM_GOLD = 0xFFC8C800.toInt()

    private val DISABLED = intArrayOf(-android.R.attr.state_enabled)
    private val CHECKED = intArrayOf(android.R.attr.state_checked)
    private val ANY = intArrayOf()

    /** [color] when enabled, the same colour at 38 % when not. */
    fun enabledList(color: Int): ColorStateList =
        ColorStateList(arrayOf(DISABLED, ANY), intArrayOf(alpha(color, 0.38f), color))

    fun ripple(accent: Int): Int = alpha(accent, 0.30f)

    private fun alpha(color: Int, a: Float): Int =
        (color and 0x00FFFFFF) or ((Color.alpha(color) * a).toInt() shl 24)

    private fun dp(context: Context, v: Float) = v * context.resources.displayMetrics.density

    fun switchOn(context: Context): Int =
        Settings(context).colorOrNull("switch.on") ?: Defaults.YELLOW

    fun switchOff(context: Context): Int =
        Settings(context).colorOrNull("switch.off") ?: SWITCH_OFF

    fun dimGold(context: Context): Int =
        Settings(context).colorOrNull("page.note.color") ?: DIM_GOLD

    /**
     * Dresses a MaterialButton as a pill. [width] overrides the theme's frame for a button whose
     * frame 白い熊 has set on the UI page; [main] still decides the weight of the label.
     */
    fun pill(b: MaterialButton, accent: Int, background: Int, main: Boolean, width: Float? = null) {
        val context = b.context
        b.backgroundTintList = ColorStateList.valueOf(background)
        b.strokeColor = enabledList(accent)
        val frame = width ?: if (main) PILL_MAIN_DP else PILL_REGULAR_DP
        // A set width of 0 removes the frame; anything else is at least one pixel.
        b.strokeWidth = if (frame <= 0f) 0 else dp(context, frame).toInt().coerceAtLeast(1)
        b.setTextColor(enabledList(accent))
        b.rippleColor = ColorStateList.valueOf(ripple(accent))
        b.isAllCaps = false
        b.typeface = Typeface.create(b.typeface, if (main) Typeface.BOLD else Typeface.NORMAL)
    }

    /**
     * Turns a SwitchCompat into the theme's switch: a 52 × 28 dp round outline, 2 dp, with a
     * solid dot 46 % of its height inset by the same gap all round. Nothing is filled, on or off;
     * only the colour (the accent on, a darker yellow off) and the dot's side change.
     */
    fun styleSwitch(sw: SwitchCompat) {
        val context = sw.context
        val on = switchOn(context)
        val off = switchOff(context)
        val colors = ColorStateList(
            arrayOf(DISABLED, CHECKED, ANY),
            intArrayOf(alpha(off, 0.38f), on, off),
        )
        val h = dp(context, 28f).toInt()
        val w = dp(context, 52f).toInt()
        val stroke = dp(context, 2f).toInt()
        val track = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = h / 2f
            setColor(Color.TRANSPARENT)
            setStroke(stroke, Color.WHITE)
            setSize(w, h)
        }
        val dotSize = (h * 0.46f).toInt()
        val gap = (h - dotSize) / 2
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setSize(dotSize, dotSize)
        }
        // The thumb is a square as tall as the track, with the dot in its middle, so the travel
        // (track width − thumb width) leaves the same gap at both ends.
        val thumb = InsetDrawable(dot, gap)
        sw.trackDrawable = track
        sw.thumbDrawable = thumb
        sw.trackTintList = colors
        sw.thumbTintList = colors
        sw.trackTintMode = android.graphics.PorterDuff.Mode.SRC_IN
        sw.thumbTintMode = android.graphics.PorterDuff.Mode.SRC_IN
        sw.showText = false
        sw.switchMinWidth = w
        sw.thumbTextPadding = 0
        sw.minHeight = 0
        sw.minimumHeight = 0
    }
}
