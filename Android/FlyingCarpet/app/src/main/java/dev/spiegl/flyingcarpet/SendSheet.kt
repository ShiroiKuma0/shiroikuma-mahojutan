package dev.spiegl.flyingcarpet

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

// Fork: what a share into this app asks.
//
// The problem this fixes, precisely: a share used to arm the selection and then ask 白い熊 to
// press a button labelled "Files to send" — a label that, at that moment, is untrue. Pressing
// it sends the files already shared; it does not pick new ones. The pause itself is not the
// bug and must not be removed: MainActivity.promptForSharedSelection records 白い熊's decision
// of 2026-08-10 that a share must NOT start transferring on its own, because there was no
// moment in which to choose between Hotspot and Shared Network, and the two are not
// interchangeable — hotspot mode takes both devices off their network for the duration.
//
// So the pause stays and becomes a real question, and the question is "which device?".
//
// **The route is a toggle, not two buttons** (白い熊, 2026-09-11). Two pills at the foot read
// as two more things to press, which is the opposite of one tap: the sheet looked as though a
// device had to be chosen AND then a mode. As a toggle it is a statement about what the next
// tap will do — answered before the question is asked — and it is remembered, because
// somebody who flips it has a reason that will still be true the next time they share.

object SendSheet {

    fun show(
        activity: Activity,
        controller: PairedController,
        fileNames: List<String>,
        totalBytes: Long,
        onSendTo: (PairedPeer, Boolean) -> Unit,
        onClassic: (ConnectionMode) -> Unit,
        onPair: () -> Unit,
    ): Dialog {
        val context = activity
        val box = ForkDialog.box(context)
        val dialog = ForkDialog.wrap(context, box)

        fun rebuild() {
            val overHotspot = controller.pairing.shareOverHotspot
            box.removeAllViews()
            box.addView(ForkDialog.heading(context, sharedTitle(fileNames.size)))
            box.addView(DevicesSheet.underline(context, box))
            box.addView(
                ForkDialog.label(context, summarise(fileNames, totalBytes), 13f)
                    .apply { alpha = 0.85f }
            )
            box.addView(ForkDialog.spacer(context, 12))
            box.addView(
                routeToggle(context, overHotspot) {
                    controller.pairing.shareOverHotspot = it
                    rebuild()
                }
            )
            box.addView(ForkDialog.spacer(context, 12))
            box.addView(ForkDialog.divider(context))
            box.addView(ForkDialog.spacer(context, 10))

            val peers = if (controller.isPaired) controller.pairing.peers() else emptyList()
            if (peers.isEmpty()) {
                box.addView(
                    ForkDialog.label(
                        context,
                        if (controller.isPaired) {
                            "No paired devices found yet. Open the app on the other device, " +
                                "or send to one that isn't paired below."
                        } else {
                            "Pair a device once and sending becomes a single tap, with " +
                                "nothing to do on the device receiving."
                        },
                        13f,
                    )
                )
            } else {
                for (peer in peers) {
                    box.addView(
                        peerPill(context, peer, overHotspot) {
                            dialog.dismiss()
                            onSendTo(peer, overHotspot)
                        }
                    )
                }
            }

            box.addView(ForkDialog.spacer(context, 12))
            box.addView(ForkDialog.divider(context))
            box.addView(ForkDialog.spacer(context, 12))

            val bar = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            bar.addView(ForkDialog.pill(context, "Cancel") { dialog.dismiss() })
            bar.addView(
                View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }
            )
            if (!controller.isPaired) {
                bar.addView(ForkDialog.pill(context, "＋ Pair") { dialog.dismiss(); onPair() })
            }
            // The way out for a device that is not one of 白い熊's own — a stock peer, or
            // somebody else's phone. It takes the toggle's route, so the connection type is
            // still chosen here rather than left over from the last transfer, which is what
            // the 2026-08-10 requirement was actually about.
            bar.addView(
                ForkDialog.pill(context, "Not paired…") {
                    dialog.dismiss()
                    onClassic(
                        if (overHotspot) ConnectionMode.Hotspot else ConnectionMode.SharedNetwork
                    )
                }
            )
            box.addView(bar)
        }

        rebuild()
        dialog.show()
        // Drawn from what is already known, then refreshed by a real scan — so the sheet is
        // useful the instant the share lands rather than a second and a half later.
        (activity as? LifecycleOwner)?.lifecycleScope?.launch {
            controller.scan()
            rebuild()
        }
        return dialog
    }

    /**
     * Two halves of one control, not two buttons: whichever is filled is what the next tap on
     * a device will do. Built by hand rather than from a MaterialButtonToggleGroup so it wears
     * the same pill as everything else on this sheet.
     */
    private fun routeToggle(
        context: android.content.Context,
        overHotspot: Boolean,
        onChange: (Boolean) -> Unit,
    ): View {
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(
            ForkDialog.label(context, "Send over", 13f).apply {
                alpha = 0.85f
                setPadding(0, 0, ForkDialog.dp(context, 10), 0)
            }
        )
        row.addView(segment(context, "This network", !overHotspot) { onChange(false) })
        row.addView(segment(context, "Hotspot", overHotspot) { onChange(true) })
        return row
    }

    private fun segment(
        context: android.content.Context,
        text: String,
        selected: Boolean,
        onClick: () -> Unit,
    ): Button {
        val accent = ForkDialog.accent(context)
        val surface = ForkDialog.surface(context)
        return Button(context).apply {
            this.text = text
            isAllCaps = false
            typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            // Filled reads as "this is what will happen"; outlined as "this is available".
            setTextColor(if (selected) surface else accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = RippleDrawable(
                ColorStateList.valueOf((accent and 0x00FFFFFF) or 0x33000000),
                GradientDrawable().apply {
                    setColor(if (selected) accent else surface)
                    setStroke(ForkDialog.dp(context, 1), accent)
                    cornerRadius = ForkDialog.dp(context, 50).toFloat()
                },
                null,
            )
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(
                ForkDialog.dp(context, 14), ForkDialog.dp(context, 6),
                ForkDialog.dp(context, 14), ForkDialog.dp(context, 6),
            )
            stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = ForkDialog.dp(context, 6) }
            setOnClickListener { onClick() }
        }
    }

    /**
     * One device, as one thing to press. The route is already decided by the toggle above, so
     * this says what will happen rather than asking a second question.
     */
    private fun peerPill(
        context: android.content.Context,
        peer: PairedPeer,
        overHotspot: Boolean,
        onClick: () -> Unit,
    ): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = ForkDialog.dp(context, 8) }
        }
        val reachable = System.currentTimeMillis() / 1000 - peer.lastSeen < 60 &&
            peer.lastIp != null
        val name = peer.displayName
        column.addView(
            // As wide as its name and no wider — the same pill as on the main page. Stretched
            // across the sheet it stopped reading as a device and started reading as a bar
            // (白い熊, 2026-09-11).
            ForkDialog.pill(context, if (overHotspot) "⚡ $name" else name, onClick).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                if (!overHotspot) {
                    ForkDialog.icon(this, R.drawable.ic_wifi)
                }
            }
        )
        // Only for the network route. Over a hotspot there is a tap to make on the far device
        // regardless, so "not seen recently" would be noise rather than information.
        if (!overHotspot && !reachable) {
            column.addView(
                ForkDialog.label(
                    context,
                    "Not seen on this network recently — it will be looked for.",
                    11f,
                ).apply { alpha = 0.7f }
            )
        }
        if (overHotspot) {
            column.addView(
                ForkDialog.label(context, "Tap $name on that device too.", 11f)
                    .apply { alpha = 0.7f }
            )
        }
        return column
    }

    private fun sharedTitle(count: Int): String =
        if (count == 1) "共有された 1 件を送る" else "共有された $count 件を送る"

    /**
     * The first few names and then a count, rather than a wall of filenames: what is wanted
     * here is recognition — "yes, those are the ones I shared" — not an inventory.
     */
    private fun summarise(names: List<String>, totalBytes: Long): String {
        val shown = names.take(3).joinToString(", ")
        val rest = names.size - 3
        val head = if (rest > 0) "$shown ほか $rest 件" else shown
        return if (totalBytes > 0) "$head · ${makeSizeReadable(totalBytes)}" else head
    }
}
