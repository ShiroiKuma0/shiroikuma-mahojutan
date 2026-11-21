package dev.spiegl.flyingcarpet

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat

// Fork: the theme's menu — every list of actions in the app (the long-press menu of a device,
// the pairing choices, the directory browser, the backup picker).
//
// The rule (black-yellow theme, "Menus"): a menu must show at a glance which lines can be
// pressed. So an action row is an outlined pill spanning the full width of the menu, 1.25 dp,
// with its icon inside at the left, coloured by what the row does, and a › at its right end when
// it opens something. A destructive row lends the delete red to frame, icon and label. A toggle
// row stays plain — icon, label, and the theme's outlined switch, 36 dp tall — and is never a
// tick box. Groups are parted by a clear 1 dp darker-accent line, and the header a menu opens
// with is plain text.
//
// It is a ForkDialog underneath: the window takes the screen's whole height before its content
// scrolls, so a menu never scrolls while the screen has room, wherever it was opened from.
object ForkMenu {

    sealed class Entry

    /** A row that runs something. [opens] says it leads to a screen, dialog or picker (›). */
    class Action(
        val label: String,
        val icon: Icons.Icon,
        val role: IconRole = IconRole.VIEW,
        val opens: Boolean = false,
        val destructive: Boolean = false,
        val enabled: Boolean = true,
        /** A second, smaller line under the label: a size, a date. */
        val detail: String? = null,
        val onClick: () -> Unit,
    ) : Entry()

    /** A checkable row: plain, with the outlined switch. It acts in place; the menu stays open. */
    class Toggle(
        val label: String,
        val icon: Icons.Icon,
        val role: IconRole = IconRole.VIEW,
        val checked: Boolean,
        val onChange: (Boolean) -> Unit,
    ) : Entry()

    /** The line between two groups. */
    object Divider : Entry()

    /** A line that is only read. */
    class Note(val text: String) : Entry()

    // The measures settled on the device for the sister apps (2026-11-15).
    private const val SIDE_DP = 8          // from the menu's edge to a pill
    private const val ROW_DP = 48          // the stock row height a pill row keeps
    private const val PILL_GAP_DP = 3      // above and below a pill
    private const val ICON_INSET_DP = 14   // from the pill's edge to its icon
    private const val ICON_DP = 20f
    private const val TOGGLE_ROW_DP = 36

    /**
     * Shows [entries] under the plain header [title]. A tap outside closes it, as with any
     * menu; [cancelLabel] adds a pill for a picker that should say how to leave it.
     */
    fun show(
        context: Context,
        title: String,
        entries: List<Entry>,
        cancelLabel: String? = null,
    ): Dialog {
        fun dp(v: Int) = ForkDialog.dp(context, v)
        val ground = Roles.color(context, "menu.bg")
        val box = ForkDialog.box(
            context, padH = SIDE_DP, padTop = 12,
            ground = ground, frame = Roles.color(context, "menu.frame"),
        )
        val dialog = ForkDialog.wrap(context, box, cancelable = true)

        // The header: plain text, unframed, its edge on the pills' icon column.
        box.addView(
            ForkDialog.label(context, title, 16f, color = Roles.color(context, "menu.header"), bold = true).apply {
                setPadding(dp(ICON_INSET_DP), dp(2), dp(ICON_INSET_DP), dp(8))
            },
        )

        for (entry in entries) {
            when (entry) {
                is Action -> box.addView(actionRow(context, entry, ground, dialog))
                is Toggle -> box.addView(toggleRow(context, entry))
                is Divider -> box.addView(dividerRow(context))
                is Note -> box.addView(
                    ForkDialog.label(context, entry.text, 13f, color = Roles.color(context, "menu.note")).apply {
                        setPadding(dp(ICON_INSET_DP), dp(4), dp(ICON_INSET_DP), dp(4))
                    },
                )
            }
        }

        if (cancelLabel != null) {
            box.addView(dividerRow(context))
            box.addView(
                LinearLayout(context).apply {
                    gravity = Gravity.END
                    clipChildren = false
                    setPadding(0, dp(8), 0, 0)
                    addView(ForkDialog.pill(context, cancelLabel) { dialog.dismiss() })
                },
            )
        }
        dialog.show()
        return dialog
    }

    private fun actionRow(context: Context, entry: Action, ground: Int, dialog: Dialog): View {
        fun dp(v: Int) = ForkDialog.dp(context, v)
        // One colour for frame, label and ›: the row's own. Only the icon carries the role's.
        val delete = Roles.delete(context)
        val frame = if (entry.destructive) delete else Roles.color(context, "menu.row.frame")
        val ink = if (entry.destructive) delete else Roles.color(context, "menu.row.text")
        val iconColor = if (entry.destructive) delete else Roles.icon(context, entry.role)

        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ROW_DP - 2 * PILL_GAP_DP)
            setPadding(dp(ICON_INSET_DP), dp(4), dp(ICON_INSET_DP), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(PILL_GAP_DP); bottomMargin = dp(PILL_GAP_DP) }
            // The whole pill is the touch target and carries the ripple.
            background = Theme.pillBackground(context, frame, ground, main = false)
            isEnabled = entry.enabled
            isClickable = true
            isFocusable = true
            // Disabled: the same pill, its frame (through the state list) and content at 38 %.
            setOnClickListener {
                dialog.dismiss()
                entry.onClick()
            }
        }
        val fade = if (entry.enabled) 1f else 0.38f
        pill.addView(
            ImageView(context).apply {
                setImageDrawable(Icons.drawable(context, entry.icon, iconColor, ICON_DP))
                alpha = fade
                layoutParams = LinearLayout.LayoutParams(dp(ICON_DP.toInt()), dp(ICON_DP.toInt()))
                    .apply { marginEnd = dp(12) }
            },
        )
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(ForkDialog.label(context, entry.label, 15f, color = ink).apply { alpha = fade })
            entry.detail?.let {
                addView(
                    ForkDialog.label(context, it, 12f, color = Roles.color(context, "menu.note"))
                        .apply { alpha = fade },
                )
            }
        }
        pill.addView(texts)
        if (entry.opens) {
            pill.addView(
                ForkDialog.label(context, "›", 18f, color = ink, bold = true).apply {
                    alpha = fade
                    setPadding(dp(8), 0, 0, 0)
                },
            )
        }
        return pill
    }

    private fun toggleRow(context: Context, entry: Toggle): View {
        fun dp(v: Int) = ForkDialog.dp(context, v)
        val ink = Roles.color(context, "menu.toggle.text")
        val switch = SwitchCompat(context).apply {
            Theme.styleSwitch(this, compact = true)
            isChecked = entry.checked
            setOnCheckedChangeListener { _, checked -> entry.onChange(checked) }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(TOGGLE_ROW_DP)
            // The icon and label line up with the pills' contents; the switch's right edge sits
            // on the pills' right edge.
            setPadding(dp(ICON_INSET_DP), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            background = Theme.rowRipple(ink)
            isClickable = true
            isFocusable = true
            setOnClickListener { switch.toggle() }
            addView(
                ImageView(context).apply {
                    setImageDrawable(Icons.drawable(context, entry.icon, entry.role, ICON_DP))
                    layoutParams = LinearLayout.LayoutParams(dp(ICON_DP.toInt()), dp(ICON_DP.toInt()))
                        .apply { marginEnd = dp(12) }
                },
            )
            addView(
                TextView(context).apply {
                    text = entry.label
                    textSize = 15f
                    setTextColor(ink)
                    typeface = Typeface.DEFAULT
                    // A long label stops 8 dp short of the switch.
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginEnd = dp(8) }
                },
            )
            addView(switch)
        }
    }

    /** Clear, 1 dp, an opaque darker accent; its ends meet the pill edges. */
    private fun dividerRow(context: Context): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ForkDialog.dp(context, 1).coerceAtLeast(1),
        ).apply {
            topMargin = ForkDialog.dp(context, 5)
            bottomMargin = ForkDialog.dp(context, 5)
        }
        setBackgroundColor(Roles.color(context, "menu.divider"))
    }
}
