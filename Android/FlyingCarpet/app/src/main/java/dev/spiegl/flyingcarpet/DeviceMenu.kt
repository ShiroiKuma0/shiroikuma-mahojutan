package dev.spiegl.flyingcarpet

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout

// Fork: everything that can be done to one paired device, in one place.
//
// Reached by holding a device — on the main screen's pill strip, or on a row of the Devices
// sheet — so both surfaces offer the same things and cannot drift apart into two different
// half-menus.
//
// Note what "rename" means here. It writes an *alias*, not the device's name: the name is
// refreshed from every announcement that device makes, so a rename stored there would be
// undone by the next scan. The alias is local to this device and survives, which is also the
// honest model — what 白い熊 calls a phone is not the phone's business.
object DeviceMenu {

    /**
     * @param onPickFolder handed back to the caller, because choosing a folder needs an
     *   Activity result launcher and this object has no Activity of its own to register one.
     */
    fun show(
        activity: Activity,
        controller: PairedController,
        peer: PairedPeer,
        onPickFolder: (PairedPeer) -> Unit,
        onHotspot: (PairedPeer, Boolean) -> Unit,
        onChanged: () -> Unit,
    ) {
        val items = listOf(
            "Rename…",
            "Receive folder…",
            if (peer.autoAccept) "Ask before accepting" else "Accept without asking",
            "Send over hotspot",
            "Receive over hotspot",
            "Forget this device",
        )
        ForkDialog.chooser(activity, peer.displayName, items) { index ->
            when (index) {
                0 -> rename(activity, controller, peer, onChanged)
                1 -> onPickFolder(peer)
                2 -> {
                    controller.pairing.setAutoAccept(peer.deviceId, !peer.autoAccept)
                    onChanged()
                }
                3 -> onHotspot(peer, true)
                4 -> onHotspot(peer, false)
                5 -> confirmForget(activity, controller, peer, onChanged)
            }
        }.show()
    }

    private fun rename(
        activity: Activity,
        controller: PairedController,
        peer: PairedPeer,
        onChanged: () -> Unit,
    ) {
        val box = ForkDialog.box(activity)
        box.addView(ForkDialog.heading(activity, "Rename ${peer.displayName}"))
        box.addView(
            ForkDialog.label(
                activity,
                "What this device is called here. The other device keeps its own name; " +
                    "leave this empty to go back to using it.",
                13f,
            )
        )
        val field = EditText(activity).apply {
            setText(peer.alias.orEmpty())
            hint = peer.name.ifEmpty { "Device name" }
            setTextColor(ForkDialog.accent(activity))
            setHintTextColor(ForkDialog.accent(activity) and 0x66FFFFFF)
            setSingleLine()
        }
        box.addView(field)
        box.addView(ForkDialog.spacer(activity, 12))

        val dialog = ForkDialog.wrap(activity, box)
        val bar = LinearLayout(activity).apply { gravity = Gravity.END }
        bar.addView(ForkDialog.pill(activity, "Cancel") { dialog.dismiss() })
        bar.addView(
            ForkDialog.pill(activity, "Set") {
                controller.pairing.setAlias(peer.deviceId, field.text.toString())
                dialog.dismiss()
                onChanged()
            }
        )
        box.addView(bar)
        dialog.show()
    }

    /**
     * Asks first. Forgetting is not destructive in the way losing the group key is — the
     * device can be found again by scanning, as long as both ends still share a key — but it
     * does throw away that device's folder, its alias and its remembered address, and none of
     * those come back on their own.
     */
    private fun confirmForget(
        activity: Activity,
        controller: PairedController,
        peer: PairedPeer,
        onChanged: () -> Unit,
    ) {
        val box = ForkDialog.box(activity)
        box.addView(ForkDialog.heading(activity, "Forget ${peer.displayName}?"))
        box.addView(
            ForkDialog.label(
                activity,
                "It disappears from the list, along with the folder and name set for it here. " +
                    "It can be found again by looking, as long as both devices still share the " +
                    "same pairing key — this does not un-pair anything.",
                13f,
            )
        )
        box.addView(ForkDialog.spacer(activity, 12))
        val dialog = ForkDialog.wrap(activity, box)
        val bar = LinearLayout(activity).apply { gravity = Gravity.END }
        bar.addView(ForkDialog.pill(activity, "Cancel") { dialog.dismiss() })
        bar.addView(
            ForkDialog.pill(activity, "Forget") {
                controller.pairing.forgetPeer(peer.deviceId)
                dialog.dismiss()
                onChanged()
            }.apply { setTextColor(Color.parseColor("#FFFF5252")) }
        )
        box.addView(bar)
        dialog.show()
    }
}
