package dev.spiegl.flyingcarpet

import android.app.Activity
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout

// Fork: everything that can be done to one paired device, in one place.
//
// Reached by holding a device — on the main screen's pill strip, or on a row of the Devices
// sheet — so both surfaces offer the same things and cannot drift apart into two different
// half-menus.
//
// It is the theme's menu (ForkMenu): every action a full-width outlined pill with its icon,
// coloured by what the row does; "accept without asking" a plain toggle row with the outlined
// switch, because it is a state and not an action; the groups parted by a clear line; and
// "Forget this device" in the delete red.
//
// Note what "rename" means here. It writes an *alias*, not the device's name: the name is
// refreshed from every announcement that device makes, so a rename stored there would be
// undone by the next scan. The alias is local to this device and survives, which is also the
// honest model — what 白い熊 calls a phone is not the phone's business.
object DeviceMenu {

    /**
     * @param onPickDirectory handed back to the caller, because choosing a directory needs an
     *   Activity result launcher and this object has no Activity of its own to register one.
     */
    fun show(
        activity: Activity,
        controller: PairedController,
        peer: PairedPeer,
        onPickDirectory: (PairedPeer) -> Unit,
        onHotspot: (PairedPeer, Boolean) -> Unit,
        onChanged: () -> Unit,
    ) {
        val entries = mutableListOf<ForkMenu.Entry>()
        // Only a PC answers one, so only a PC is offered it.
        if (LinkDoctor.canCheck(peer)) {
            entries.add(
                ForkMenu.Action("Wi-Fi speed check", Icons.Icon.WIFI, IconRole.CONNECT, opens = true) {
                    LinkDoctor.check(peer)
                }
            )
            entries.add(ForkMenu.Divider)
        }
        ForkMenu.show(
            activity,
            peer.displayName,
            entries + listOf(
                ForkMenu.Action("Rename", Icons.Icon.EDIT, IconRole.CONTACT, opens = true) {
                    rename(activity, controller, peer, onChanged)
                },
                ForkMenu.Action("Receive directory", Icons.Icon.DIRECTORY, IconRole.VIEW, opens = true) {
                    onPickDirectory(peer)
                },
                ForkMenu.Divider,
                // A state, so a switch: it flips in place and the menu stays open to show it.
                ForkMenu.Toggle(
                    "Accept without asking", Icons.Icon.CHECK, IconRole.CONNECT,
                    checked = peer.autoAccept,
                ) { checked ->
                    controller.pairing.setAutoAccept(peer.deviceId, checked)
                    onChanged()
                },
                ForkMenu.Divider,
                ForkMenu.Action("Send over hotspot", Icons.Icon.BOLT, IconRole.CONNECT) {
                    onHotspot(peer, true)
                },
                ForkMenu.Action("Receive over hotspot", Icons.Icon.IMPORT, IconRole.CONNECT) {
                    onHotspot(peer, false)
                },
                ForkMenu.Divider,
                // Acts in place, after a confirmation — so no ›.
                ForkMenu.Action("Forget this device", Icons.Icon.DELETE, IconRole.DELETE, destructive = true) {
                    confirmForget(activity, controller, peer, onChanged)
                },
            ),
        )
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
            ForkDialog.note(
                activity,
                "What this device is called here. The other device keeps its own name; " +
                    "leave this empty to go back to using it.",
            )
        )
        // Coloured from the dialog's text-field roles when it joins the box (Theme.watch).
        val field = EditText(activity).apply {
            setText(peer.alias.orEmpty())
            hint = peer.name.ifEmpty { "Device name" }
            setSingleLine()
        }
        box.addView(field)
        box.addView(ForkDialog.spacer(activity, 12))

        val dialog = ForkDialog.wrap(activity, box)
        val bar = LinearLayout(activity).apply { gravity = Gravity.END }
        bar.addView(
            ForkDialog.pill(activity, "Cancel") { dialog.dismiss() }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = ForkDialog.dp(activity, 10) }
            }
        )
        bar.addView(
            ForkDialog.pill(activity, "Set", main = true) {
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
     * does throw away that device's directory, its alias and its remembered address, and none
     * of those come back on their own. So the confirming pill is red, frame and label.
     */
    private fun confirmForget(
        activity: Activity,
        controller: PairedController,
        peer: PairedPeer,
        onChanged: () -> Unit,
    ) {
        ForkDialog.info(
            activity,
            "Forget ${peer.displayName}?",
            "It disappears from the list, along with the directory and name set for it here. " +
                "It can be found again by looking, as long as both devices still share the " +
                "same pairing key — this does not un-pair anything.",
            listOf(
                "Cancel" to { d: android.app.Dialog -> d.dismiss() },
                "Forget" to { d: android.app.Dialog ->
                    controller.pairing.forgetPeer(peer.deviceId)
                    d.dismiss()
                    onChanged()
                },
            ),
            cancelable = true,
            destructive = true,
        )
    }
}
