package dev.spiegl.flyingcarpet

import android.content.Context
import android.content.pm.ActivityInfo
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible

// Fork: what the main screen shows of the lanes (TransferLanes.kt) — one block per paired
// transfer over this network, each with the device's name, its own bars and its own Cancel.
//
// Built in code rather than in the layout because the number of blocks changes while the
// screen is up, and kept out of MainActivity because nothing here needs anything of it but a
// place to put the views.

/** The lane list's scroller: as tall as its blocks, up to two fifths of the screen. */
class LaneScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = (resources.displayMetrics.heightPixels * 0.4f).toInt()
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
    }
}

object LaneViews {

    /**
     * Keeps [list] in step with the lanes for as long as [activity] lives. [singleSlotBusy]
     * says whether a hotspot or password-mode transfer is running, which also holds the
     * orientation and must not have it taken away here.
     */
    fun bind(
        activity: AppCompatActivity,
        scroll: View,
        list: LinearLayout,
        singleSlotBusy: () -> Boolean,
    ) {
        val blocks = mutableMapOf<Long, View>()
        TransferLanes.lanes.observe(activity) { lanes ->
            val live = lanes.map { it.id }.toSet()
            for (gone in blocks.keys.filter { it !in live }) {
                list.removeView(blocks.remove(gone))
            }
            for (lane in lanes) {
                if (lane.id in blocks) continue
                val block = block(activity, lane)
                blocks[lane.id] = block
                list.addView(block)
            }
            scroll.isVisible = lanes.isNotEmpty()
            // Held for as long as any lane exists, as the single slot holds it for its own
            // transfer and for the same reason: a recreated Activity loses the dialog a lane
            // may be waiting on an answer from.
            if (lanes.isNotEmpty()) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            } else if (!singleSlotBusy()) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    private fun block(activity: AppCompatActivity, lane: PairedLane): View {
        val color = Appearance.progressColor(activity)
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = ForkDialog.dp(activity, 8) }
        }

        // The device, and beside it the one thing that can be done to its transfer.
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = ForkDialog.label(activity, "", 15f, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val cancel = ForkDialog.pill(activity, "Cancel") { lane.cancel() }.apply {
            setPadding(
                ForkDialog.dp(activity, 14), ForkDialog.dp(activity, 4),
                ForkDialog.dp(activity, 14), ForkDialog.dp(activity, 4),
            )
            textSize = 13f
        }
        head.addView(title)
        head.addView(cancel)
        column.addView(head)

        val waiting = ForkDialog.label(activity, "", 13f, color = Theme.dimGold(activity))
        val details = detailLine(activity, color)
        val bar = bar(activity)
        val totalDetails = detailLine(activity, color)
        val totalBar = bar(activity)
        for (view in listOf(waiting, details, bar, totalDetails, totalBar)) {
            view.isVisible = false
            column.addView(view)
        }

        lane.title.observe(activity) { title.text = it }
        lane.waiting.observe(activity) {
            waiting.text = it
            waiting.isVisible = it.isNotEmpty()
        }
        lane.progressBarMut.observe(activity) { bar.progress = it }
        lane.progressDetailsMut.observe(activity) {
            // One line here where the single slot uses two: there may be four of these.
            details.text = it.replace("\n", "  ·  ")
            details.isVisible = it.isNotEmpty()
            bar.isVisible = it.isNotEmpty()
        }
        lane.totalProgressBarMut.observe(activity) { totalBar.progress = it }
        lane.progressTotalDetailsMut.observe(activity) {
            // one file needs no second row -- the two bars would say the same thing
            val show = it.isNotEmpty() && !it.startsWith("File 1 of 1")
            totalDetails.text = it.replace("\n", "  ·  ")
            totalDetails.isVisible = show
            totalBar.isVisible = show
        }
        return column
    }

    private fun detailLine(activity: AppCompatActivity, color: Int): TextView =
        ForkDialog.label(activity, "", 13f, color = color).apply {
            setPadding(0, ForkDialog.dp(activity, 2), 0, ForkDialog.dp(activity, 2))
        }

    private fun bar(activity: AppCompatActivity): ProgressBar =
        ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            Appearance.styleProgressBar(activity, this)
            (layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = ForkDialog.dp(activity, 2)
        }
}
