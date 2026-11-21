package dev.spiegl.flyingcarpet

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton

// Fork: the black-yellow theme's controls, in one place (the global black-yellow-theme skill is
// the spec). Every action is an outlined pill and never a filled one — 3 dp and bold for the one
// action a block is for, 1.25 dp and normal for the rest — and a switch is an outlined pill too,
// with a solid dot that sits right when on and left when off. Explanations are dim gold, never
// grey. Every colour is a settable role on the UI page (Roles.kt, Appearance.kt); nothing here
// names a colour value. The Color.WHITE in the drawables below is a tint or ripple mask.
object Theme {
    const val PILL_MAIN_DP = 3f
    const val PILL_REGULAR_DP = 1.25f
    // More than half of any pill's height, so the ends are fully round.
    const val PILL_RADIUS_DP = 60f

    // The two deliberate dims. Their values are written once, in the role table (Roles.kt);
    // these names are kept for the code that already says Theme.SWITCH_OFF / Theme.DIM_GOLD.
    const val SWITCH_OFF = Roles.SWITCH_OFF
    const val DIM_GOLD = Roles.DIM_GOLD

    private val DISABLED = intArrayOf(-android.R.attr.state_enabled)
    private val CHECKED = intArrayOf(android.R.attr.state_checked)
    private val ANY = intArrayOf()

    /** [color] when enabled, the same colour at 38 % when not. */
    fun enabledList(color: Int): ColorStateList =
        ColorStateList(arrayOf(DISABLED, ANY), intArrayOf(alpha(color, 0.38f), color))

    fun ripple(accent: Int): Int = alpha(accent, 0.30f)

    private fun alpha(color: Int, a: Float): Int = Roles.alpha(color, a)

    private fun dp(context: Context, v: Float) = v * context.resources.displayMetrics.density

    fun switchOn(context: Context): Int =
        Settings(context).colorOrNull("switch.on") ?: Roles.ACCENT

    fun switchOff(context: Context): Int =
        Settings(context).colorOrNull("switch.off") ?: SWITCH_OFF

    fun dimGold(context: Context): Int = Roles.dimGold(context)

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
        b.iconTint = enabledList(accent)
        b.isAllCaps = false
        b.typeface = Typeface.create(b.typeface, if (main) Typeface.BOLD else Typeface.NORMAL)
    }

    /**
     * The pill as a background for any view: never filled beyond [ground], an outline of
     * [frame] — 3 dp when [main], 1.25 dp otherwise, 38 % when the view is disabled — fully round,
     * with the ripple inside it.
     */
    fun pillBackground(
        context: Context,
        frame: Int,
        ground: Int,
        main: Boolean = false,
        radii: FloatArray? = null,
    ): Drawable {
        val r = dp(context, PILL_RADIUS_DP)
        fun shape() = GradientDrawable().apply {
            if (radii != null) cornerRadii = radii else cornerRadius = r
        }
        return RippleDrawable(
            ColorStateList.valueOf(ripple(frame)),
            shape().apply {
                setColor(ground)
                setStroke(
                    dp(context, if (main) PILL_MAIN_DP else PILL_REGULAR_DP).toInt().coerceAtLeast(1),
                    enabledList(frame),
                )
            },
            shape().apply { setColor(Color.WHITE) },
        )
    }

    /** The press highlight of a plain tappable row: [color] at 30 %, no frame. */
    fun rowRipple(color: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(ripple(color)), null, ColorDrawable(Color.WHITE))

    /**
     * Turns a SwitchCompat into the theme's switch: a 52 × 28 dp round outline, 2 dp, with a
     * solid dot 46 % of its height inset by the same gap all round. Nothing is filled, on or off;
     * only the colour (the accent on, a darker yellow off) and the dot's side change. [compact]
     * is the smaller 44 × 24 dp one a menu's toggle row carries.
     */
    fun styleSwitch(sw: SwitchCompat, compact: Boolean = false) {
        val context = sw.context
        val on = switchOn(context)
        val off = switchOff(context)
        val colors = ColorStateList(
            arrayOf(DISABLED, CHECKED, ANY),
            intArrayOf(alpha(off, 0.38f), on, off),
        )
        val h = dp(context, if (compact) 24f else 28f).toInt()
        val w = dp(context, if (compact) 44f else 52f).toInt()
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
        // The switch's own press highlight would be Material's; it takes the on colour instead.
        sw.background = null
        sw.setTag(R.id.themeStyled, true)
    }

    // ── Stock controls, coloured from roles ─────────────────────────────────────────────────
    //
    // The themes carry no colour, so a text field, a slider or a tick box that nobody dressed
    // would show Material's own. They are dressed here, from the roles of the surface they sit
    // on: a dialog (and every sheet and menu built from ForkDialog) or the UI page.

    /** Which surface a stock control sits on, and so which roles colour it. */
    enum class Surface(val prefix: String) { DIALOG("dialog"), PAGE("page") }

    /** Text, placeholder, underline, cursor, selection and its handles — all from roles. */
    fun styleField(e: EditText, surface: Surface = Surface.DIALOG) {
        val context = e.context
        val p = surface.prefix
        val line = Roles.color(context, "$p.field.line")
        e.setTextColor(Roles.color(context, "$p.field.text"))
        e.setHintTextColor(Roles.color(context, "$p.field.hint"))
        e.backgroundTintList = ColorStateList.valueOf(line)
        e.highlightColor = Roles.color(context, "$p.field.selection")
        e.textCursorDrawable = GradientDrawable().apply {
            setColor(line)
            setSize(dp(context, 2f).toInt().coerceAtLeast(1), 0)
        }
        e.textSelectHandle?.mutate()?.let { it.setTint(line); e.setTextSelectHandle(it) }
        e.textSelectHandleLeft?.mutate()?.let { it.setTint(line); e.setTextSelectHandleLeft(it) }
        e.textSelectHandleRight?.mutate()?.let { it.setTint(line); e.setTextSelectHandleRight(it) }
        e.setTag(R.id.themeStyled, true)
    }

    /** Thumb and filled part in one role, the unfilled track in another. */
    fun styleSeekBar(sb: SeekBar, surface: Surface = Surface.DIALOG) {
        val context = sb.context
        val color = Roles.color(context, "${surface.prefix}.slider")
        sb.thumbTintList = ColorStateList.valueOf(color)
        sb.progressTintList = ColorStateList.valueOf(color)
        sb.progressBackgroundTintList =
            ColorStateList.valueOf(Roles.color(context, "${surface.prefix}.slider.track"))
        sb.background = null
        sb.setTag(R.id.themeStyled, true)
    }

    /** A tick box stays a selection mark (白い熊's decision); its mark and label take roles. */
    fun styleCheckBox(cb: CheckBox) {
        val context = cb.context
        val mark = Roles.color(context, "dialog.check")
        cb.buttonTintList = enabledList(mark)
        cb.setTextColor(enabledList(Roles.color(context, "dialog.check.text")))
        cb.background = rowRipple(mark)
        cb.setTag(R.id.themeStyled, true)
    }

    /**
     * Dresses every stock control under [root], now and whenever one is added later — a sheet
     * rebuilds its rows while it is showing. This is the one place every dialog passes through
     * (ForkDialog.wrap calls it), so a text field or a switch that some dialog builds without
     * dressing it is covered too.
     */
    fun watch(root: ViewGroup, surface: Surface = Surface.DIALOG) {
        for (i in 0 until root.childCount) adopt(root.getChildAt(i), surface)
        root.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) {
                if (child != null) adopt(child, surface)
            }
            override fun onChildViewRemoved(parent: View?, child: View?) {}
        })
    }

    private fun adopt(view: View, surface: Surface) {
        if (view.getTag(R.id.themeStyled) == null) {
            when (view) {
                is SwitchCompat -> styleSwitch(view)
                is CheckBox -> styleCheckBox(view)
                is EditText -> styleField(view, surface)
                is SeekBar -> styleSeekBar(view, surface)
            }
        }
        // Only plain containers are walked: a widget that is a ViewGroup keeps its own listener.
        if (view is ViewGroup && view.javaClass.name.let {
                it.startsWith("android.widget.") || it.startsWith("dev.spiegl.flyingcarpet.")
            } && view !is android.widget.AdapterView<*>
        ) {
            watch(view, surface)
        }
    }
}
