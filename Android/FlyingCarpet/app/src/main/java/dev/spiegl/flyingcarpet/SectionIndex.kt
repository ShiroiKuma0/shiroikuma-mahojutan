package dev.spiegl.flyingcarpet

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.DragEvent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.hypot

/**
 * Fork: the jump index of the UI page — a wrapping grid of small outlined chips, one for every
 * top-level section (black-yellow theme, "Every UI page"; after 白い熊 臨電話's `SkSectionIndex`).
 *
 * A **tap** on a chip unfolds its section and scrolls to it. A **long press** lifts the chip into
 * a drag: the chips reorder **live** under the finger — the drag is driven by its shadow, so
 * moving the chip in the tree does not cancel it — and on release the sections below are put in
 * the same order.
 *
 * The order itself, and which sections are unfolded, are kept by [Settings] under "page.*" keys,
 * so both travel in the export.
 */
object SectionIndex {
    class Item(val key: String, val label: String)

    private const val GAP_DP = 6f
    private const val FRAME_DP = 1.25f
    private const val LIFTED_ALPHA = 0.3f

    /**
     * The stored order applied to [built]: stored keys first, as far as they still exist, then
     * any section added since at its built place among the rest; unknown stored keys are dropped.
     */
    fun ordered(stored: List<String>, built: List<String>): List<String> {
        val kept = stored.filter { it in built }.distinct()
        return kept + built.filter { it !in kept }
    }

    fun build(
        context: Context,
        items: List<Item>,
        frame: Int,
        ink: Int,
        onJump: (String) -> Unit,
        onReordered: (List<String>) -> Unit,
    ): View {
        val density = context.resources.displayMetrics.density
        val flow = Flow(context, (GAP_DP * density).toInt())
        flow.setPadding(0, (2 * density).toInt(), 0, (6 * density).toInt())
        for (item in items) {
            val chip = chip(context, item.label, frame, ink, density)
            chip.tag = item.key
            chip.setOnClickListener { onJump(it.tag as String) }
            chip.setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                it.alpha = LIFTED_ALPHA
                it.startDragAndDrop(
                    ClipData.newPlainText("ui_section", it.tag as String),
                    View.DragShadowBuilder(it),
                    it,
                    0,
                )
                true
            }
            flow.addView(chip)
        }
        val before = items.map { it.key }
        flow.setOnDragListener { _, event ->
            val dragged = event.localState as? View
            when (event.action) {
                DragEvent.ACTION_DRAG_LOCATION -> if (dragged != null && dragged.parent === flow) {
                    val to = slotAt(flow, event.x, event.y)
                    val from = flow.indexOfChild(dragged)
                    if (to >= 0 && to != from) {
                        flow.removeView(dragged)
                        flow.addView(dragged, to.coerceAtMost(flow.childCount))
                    }
                }
                DragEvent.ACTION_DRAG_ENDED -> if (dragged != null && dragged.parent === flow) {
                    dragged.alpha = 1f
                    val order = (0 until flow.childCount).map { flow.getChildAt(it).tag as String }
                    // Rebuilding the page from inside the drag's own dispatch would pull the
                    // views out from under it.
                    if (order != before) flow.post { onReordered(order) }
                }
            }
            true
        }
        return flow
    }

    /** The chip the finger is over, or the one whose centre is nearest. */
    private fun slotAt(flow: ViewGroup, x: Float, y: Float): Int {
        var best = -1
        var bestDistance = Float.MAX_VALUE
        for (i in 0 until flow.childCount) {
            val child = flow.getChildAt(i)
            if (x >= child.left && x <= child.right && y >= child.top && y <= child.bottom) return i
            val distance = hypot(x - (child.left + child.right) / 2f, y - (child.top + child.bottom) / 2f)
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        return best
    }

    private fun chip(context: Context, label: String, frame: Int, ink: Int, density: Float) =
        TextView(context).apply {
            text = label
            setTextColor(ink)
            textSize = 12f
            setSingleLine()
            gravity = Gravity.CENTER
            setPadding(
                (12 * density).toInt(),
                (5 * density).toInt(),
                (12 * density).toInt(),
                (5 * density).toInt(),
            )
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = 100 * density
                setStroke((FRAME_DP * density + 0.5f).toInt().coerceAtLeast(1), frame)
            }
            isClickable = true
            isFocusable = true
        }

    /** A minimal wrapping row layout. */
    @SuppressLint("ViewConstructor")
    private class Flow(context: Context, private val gap: Int) : ViewGroup(context) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val width = MeasureSpec.getSize(widthSpec)
            val inner = width - paddingLeft - paddingRight
            var x = 0
            var y = paddingTop
            var rowHeight = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                measureChild(child, MeasureSpec.makeMeasureSpec(inner, MeasureSpec.AT_MOST), heightSpec)
                if (x > 0 && x + child.measuredWidth > inner) {
                    x = 0
                    y += rowHeight + gap
                    rowHeight = 0
                }
                x += child.measuredWidth + gap
                rowHeight = maxOf(rowHeight, child.measuredHeight)
            }
            setMeasuredDimension(width, y + rowHeight + paddingBottom)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val inner = width - paddingLeft - paddingRight
            var x = paddingLeft
            var y = paddingTop
            var rowHeight = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (x > paddingLeft && x + child.measuredWidth > paddingLeft + inner) {
                    x = paddingLeft
                    y += rowHeight + gap
                    rowHeight = 0
                }
                child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
                x += child.measuredWidth + gap
                rowHeight = maxOf(rowHeight, child.measuredHeight)
            }
        }
    }
}
