package dev.spiegl.flyingcarpet

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
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
// Colours follow the UI page's "page.*" keys where 白い熊 has set them, so a repainted settings page
// repaints its dialogs and panels too.

object ForkDialog {

    fun accent(context: Context): Int =
        Settings(context).colorOrNull("page.title.color") ?: Defaults.YELLOW

    fun surface(context: Context): Int =
        Settings(context).colorOrNull("page.bg") ?: Defaults.BLACK

    fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private fun dpF(context: Context, v: Float): Float = v * context.resources.displayMetrics.density

    /** The bordered black box every fork dialog and panel is built inside. */
    fun box(context: Context, padH: Int = 22, padTop: Int = 20): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, padH), dp(context, padTop), dp(context, padH), dp(context, 16))
        clipToPadding = false
        clipChildren = false
        background = GradientDrawable().apply {
            setColor(surface(context))
            setStroke(dpF(context, 2f).toInt(), accent(context))
            cornerRadius = dpF(context, 16f)
        }
    }

    fun heading(context: Context, s: String, sizeSp: Float = 19f): TextView =
        label(context, s, sizeSp, bold = true)

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

    /** A thin, dim rule — the same hairline the UI page uses between its sections. */
    fun divider(context: Context, topGap: Int = 0): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1))
            .apply { topMargin = dp(context, topGap) }
        setBackgroundColor(accent(context))
        alpha = 0.4f
    }

    fun spacer(context: Context, height: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, height))
    }

    /** An ArcaneChat-style round pill: black fill, thin accent stroke, accent text, accent ripple. */
    fun pill(context: Context, s: String, onClick: () -> Unit): Button = Button(context).apply {
        text = s
        isAllCaps = false
        setTextColor(accent(context))
        background = RippleDrawable(
            ColorStateList.valueOf((accent(context) and 0x00FFFFFF) or 0x33000000),
            GradientDrawable().apply {
                setColor(surface(context))
                setStroke(dpF(context, 1.5f).toInt(), accent(context))
                cornerRadius = dpF(context, 50f)   // > half the height → a pill
            },
            null,
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
    ): Dialog {
        val content = box(context)
        content.addView(heading(context, titleText))
        content.addView(label(context, bodyText).apply { setPadding(0, dp(context, 10), 0, 0) })
        val dialog = wrap(context, content, cancelable)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(context, 16), 0, 0)
        }
        actions.forEachIndexed { i, (buttonLabel, action) ->
            row.addView(
                pill(context, buttonLabel) { action(dialog) }.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { if (i < actions.size - 1) marginEnd = dp(context, 10) }
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

    /** A black/yellow chooser: [items] as tappable rows, plus a Cancel pill. */
    fun chooser(context: Context, titleText: String, items: List<String>, onPick: (Int) -> Unit): Dialog {
        val content = box(context)
        content.addView(heading(context, titleText, sizeSp = 16f))
        content.addView(divider(context, topGap = 8))
        val dialog = wrap(context, content, cancelable = true)
        items.forEachIndexed { i, itemLabel ->
            content.addView(
                label(context, itemLabel, 15f).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    setPadding(dp(context, 4), dp(context, 11), dp(context, 4), dp(context, 11))
                    isClickable = true
                    setBackgroundResource(rippleBackground(context))
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

    private fun rippleBackground(context: Context): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return value.resourceId
    }
}
