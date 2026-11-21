package dev.spiegl.flyingcarpet

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// ── The fork's dialog look ────────────────────────────────────────────────────────────────────
//
// Black fill, yellow border, yellow text, round-pill actions. Material's own dialog stroke and text
// colours can't be pushed all the way to this look, so — exactly as shiroikuma-kojiki's
// ExportImportBottomSheet does — we own the whole surface: a plain Dialog whose window background is
// transparent, with our own bordered box inside it.
//
// Every colour is a role of its own (Roles.kt, the "Dialogs" section of the UI page): ground,
// frame, title, text, notes, divider, the three parts of a button, text fields, sliders and tick
// boxes. Unset, each falls back to the "page.*" key the dialogs used to be painted from, so a
// repainted settings page still repaints its dialogs until a dialog role is set by itself.

object ForkDialog {

    /** The colour of a dialog's text. (The name is from when one accent painted everything.) */
    fun accent(context: Context): Int = Roles.color(context, "dialog.text")

    /** The dialog ground. */
    fun surface(context: Context): Int = Roles.color(context, "dialog.bg")

    fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private fun dpF(context: Context, v: Float): Float = v * context.resources.displayMetrics.density

    /**
     * The bordered black box every fork dialog and panel is built inside. [ground] and [frame]
     * are for a surface with roles of its own (a menu, the About dialog).
     */
    fun box(
        context: Context,
        padH: Int = 22,
        padTop: Int = 20,
        ground: Int? = null,
        frame: Int? = null,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, padH), dp(context, padTop), dp(context, padH), dp(context, 16))
        clipToPadding = false
        clipChildren = false
        background = GradientDrawable().apply {
            setColor(ground ?: surface(context))
            setStroke(dpF(context, 2f).toInt(), frame ?: Roles.color(context, "dialog.frame"))
            cornerRadius = dpF(context, 16f)
        }
    }

    /** A dialog's title. [color] overrides the title role — a warning, a success. */
    fun heading(context: Context, s: String, sizeSp: Float = 19f, color: Int? = null): TextView =
        label(context, s, sizeSp, color = color ?: Roles.color(context, "dialog.title"), bold = true)

    fun label(
        context: Context,
        s: String,
        sizeSp: Float = 14f,
        color: Int? = null,
        bold: Boolean = false,
    ): TextView = TextView(context).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color ?: accent(context))
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    /** An explanation or hint: the dialog's note role (dim gold by default), never grey. */
    fun note(context: Context, s: String, sizeSp: Float = 13f): TextView =
        label(context, s, sizeSp, color = Roles.color(context, "dialog.note"))

    /**
     * A tick box, the same one the Export/Import panel uses for its categories. A tick box is a
     * selection mark and stays one (白い熊's decision); its mark and label take roles.
     */
    fun checkbox(context: Context, s: String, sizeSp: Float = 15f): CheckBox = CheckBox(context).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        Theme.styleCheckBox(this)
        setPadding(dp(context, 8), dp(context, 7), 0, dp(context, 7))
    }

    /** The clear 1 dp line between groups: an opaque darker accent, a role of its own. */
    fun divider(context: Context, topGap: Int = 0): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1).coerceAtLeast(1))
            .apply { topMargin = dp(context, topGap) }
        setBackgroundColor(Roles.color(context, "dialog.divider"))
    }

    fun spacer(context: Context, height: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, height))
    }

    /** The width of a regular pill's outline, in pixels — what two halves of a split pill overlap by. */
    fun strokePx(context: Context): Int = dpF(context, Theme.PILL_REGULAR_DP).coerceAtLeast(1f).toInt()

    /** Which corners a pill rounds: all of them, or only one end of a pill split in two. */
    enum class Corners { ALL, START, END }

    /**
     * The theme's pill: never filled — the background shows through an accent outline. [main]
     * marks the one action its block is for, with a 3 dp frame and a bold label; every other
     * action has a 1.25 dp frame and a normal one. Disabled, frame and text drop to 38 %.
     * [destructive] lends the delete red to frame and label, still unfilled.
     *
     * A whole pill takes the dialog button roles. A half of a split pill ([corners] other than
     * ALL) is a device pill — the only thing built from halves — and takes the device pill roles.
     */
    fun pill(
        context: Context,
        s: String,
        corners: Corners = Corners.ALL,
        main: Boolean = false,
        destructive: Boolean = false,
        onClick: () -> Unit,
    ): Button = Button(context).apply {
        text = s
        isAllCaps = false
        val family = if (corners == Corners.ALL) "dialog.pill" else "sheet.pill"
        val frame = if (destructive) Roles.delete(context) else Roles.color(context, "$family.frame")
        val ink = if (destructive) Roles.delete(context) else Roles.color(context, "$family.text")
        setTextColor(Theme.enabledList(ink))
        typeface = if (main) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        val r = dpF(context, 50f)   // > half the height → a pill
        background = Theme.pillBackground(
            context, frame, Roles.color(context, "$family.bg"), main,
            radii = when (corners) {
                Corners.ALL -> null
                // top-left, top-right, bottom-right, bottom-left — two floats each
                Corners.START -> floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                Corners.END -> floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            },
        )
        // Explicit padding + zeroed minimums so the rounded stroke is never clipped at the view edge.
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(context, 20), dp(context, 8), dp(context, 20), dp(context, 8))
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    /**
     * Puts an icon at the head of a pill, sized to sit with the text rather than at the
     * drawable's own 24dp. The icon is coloured by what its pill does, from the icon roles — a
     * route mark (the Wi-Fi sign) in the connect green, the directory glyph in the share orange —
     * unless [color] names another. The drawable's own fill is only a mask.
     *
     * [dim] is for a device that has not been heard from recently: the icon says which route
     * the pill takes, and its weight says whether that route is known to be there.
     */
    fun icon(button: Button, resId: Int, sizeDp: Int = 16, dim: Boolean = false, color: Int? = null) {
        val context = button.context
        val drawable = androidx.core.content.ContextCompat.getDrawable(context, resId)?.mutate()
            ?: return
        val role = if (resId == R.drawable.ic_directory) IconRole.SHARE else IconRole.CONNECT
        val tint = color ?: Roles.icon(context, role)
        drawable.setTint(if (dim) Roles.alpha(tint, 0.38f) else tint)
        drawable.setBounds(0, 0, dp(context, sizeDp), dp(context, sizeDp))
        button.setCompoundDrawablesRelative(drawable, null, null, null)
        button.compoundDrawablePadding = dp(context, 6)
    }

    /** The same, with one of the icons drawn in code (Icons.kt), coloured by [role]. */
    fun icon(button: Button, icon: Icons.Icon, role: IconRole = IconRole.CONNECT, sizeDp: Int = 16) {
        val context = button.context
        button.setCompoundDrawablesRelative(
            Icons.drawable(context, icon, role, sizeDp.toFloat()), null, null, null,
        )
        button.compoundDrawablePadding = dp(context, 6)
    }

    /** Wraps [content] in a transparent-windowed dialog so only our bordered box is visible. */
    fun wrap(context: Context, content: View, cancelable: Boolean = true): Dialog =
        Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            val scroll = ScrollView(context).apply {
                clipToPadding = false
                clipChildren = false
                val m = dp(context, 12)
                // ScrollView is a FrameLayout: its child MUST carry FrameLayout.LayoutParams.
                addView(
                    content,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { setMargins(m, m, m, m) },
                )
            }
            // Every dialog, sheet and menu passes through here, so this is where the stock
            // controls inside one — text fields, sliders, tick boxes, switches — are dressed
            // from the colour roles, also when the dialog that built them did not.
            (content as? ViewGroup)?.let { Theme.watch(it) }
            setContentView(scroll)
            setCancelable(cancelable)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

    /**
     * The fork's info dialog: title, body, and one or more right-aligned pills. Each action decides
     * for itself what to close — a successful export closes the whole chain (this dialog, the
     * Export/Import panel under it, and the UI page), a failure closes only this dialog so the panel
     * stays open and 白い熊 can fix what went wrong.
     */
    fun info(
        context: Context,
        titleText: String,
        bodyText: String,
        actions: List<Pair<String, (Dialog) -> Unit>>,
        cancelable: Boolean = false,
        destructive: Boolean = false,
    ): Dialog = custom(
        context,
        titleText,
        label(context, bodyText).apply { setPadding(0, dp(context, 10), 0, 0) },
        actions,
        cancelable,
        destructive,
    )

    /**
     * [info] with any view as its body — a text field, a colour picker. [destructive] makes the
     * rightmost action, the one the dialog is for, a red pill: a dialog that deletes or resets.
     */
    fun custom(
        context: Context,
        titleText: String,
        body: View,
        actions: List<Pair<String, (Dialog) -> Unit>>,
        cancelable: Boolean = true,
        destructive: Boolean = false,
    ): Dialog {
        val content = box(context)
        content.addView(heading(context, titleText))
        content.addView(body)
        val dialog = wrap(context, content, cancelable)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(context, 16), 0, 0)
        }
        actions.forEachIndexed { i, (buttonLabel, action) ->
            val last = i == actions.size - 1
            row.addView(
                // The rightmost action is the one the dialog is for — OK, Save, Open settings.
                pill(context, buttonLabel, main = last, destructive = last && destructive) { action(dialog) }.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { if (!last) marginEnd = dp(context, 10) }
                },
            )
        }
        content.addView(row)
        dialog.show()
        return dialog
    }

    /** A single-OK info dialog that closes only itself — every failure message uses this. */
    fun alert(context: Context, titleText: String, bodyText: String) {
        info(context, titleText, bodyText, listOf("OK" to { d: Dialog -> d.dismiss() }), cancelable = true)
    }

    /**
     * A chooser for a list of pure choices: each of [items] a full-width outlined pill, so it
     * is plain which lines can be pressed, plus a Cancel pill.
     *
     * A list of actions — rows that do different things — is a [ForkMenu], which gives every
     * row its icon.
     */
    fun chooser(context: Context, titleText: String, items: List<String>, onPick: (Int) -> Unit): Dialog =
        picker(context, titleText, items, onPick = onPick)

    /**
     * [chooser] for a value that has a choice in force (a style, a font): [selected] is marked
     * by the main pill's 3 dp frame and bold label, and [typefaces] draws each choice in a face
     * of its own.
     */
    fun picker(
        context: Context,
        titleText: String,
        items: List<String>,
        selected: Int = -1,
        typefaces: List<Typeface?>? = null,
        onPick: (Int) -> Unit,
    ): Dialog {
        val content = box(context)
        content.addView(heading(context, titleText, sizeSp = 16f))
        content.addView(divider(context, topGap = 8))
        content.addView(spacer(context, 4))
        val dialog = wrap(context, content, cancelable = true)
        val frame = Roles.color(context, "dialog.choice.frame")
        val ink = Roles.color(context, "dialog.choice.text")
        items.forEachIndexed { i, itemLabel ->
            content.addView(
                label(context, itemLabel, 15f, color = ink, bold = i == selected).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(context, 3); bottomMargin = dp(context, 3) }
                    typefaces?.getOrNull(i)?.let { face ->
                        typeface = if (i == selected) Typeface.create(face, Typeface.BOLD) else face
                    }
                    setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10))
                    isClickable = true
                    isFocusable = true
                    background = Theme.pillBackground(context, frame, Color.TRANSPARENT, main = i == selected)
                    setOnClickListener { dialog.dismiss(); onPick(i) }
                },
            )
        }
        content.addView(divider(context, topGap = 8))
        content.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                clipChildren = false
                setPadding(0, dp(context, 14), 0, 0)
                addView(pill(context, "Cancel") { dialog.dismiss() })
            },
        )
        dialog.show()
        return dialog
    }
}
