package dev.spiegl.flyingcarpet

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch

// Fork: the Devices sheet — the phone end of paired devices.
//
// One list, one tap. Nothing to choose, nothing to type, nothing to do on the other device.
// Built from ForkDialog's primitives so it wears the same black-and-yellow as every other
// surface in this fork and answers to the same UI page — through roles of its own, the
// "Devices and sheets" section there.
//
// The layout follows the shiroikuma-kxkb shape the rest of the app uses: a line between
// top-level sections, a text-width underline under every heading, and the same indentation
// ladder from section to row. Every device row carries its icon in one column, and what can be
// pressed on its own inside a row is a pill.

object DevicesSheet {

    /**
     * The list, with the identity above it. [onSend] is handed a peer and is expected to
     * arm the selection and start the transfer; this sheet never touches the ViewModel.
     */
    fun show(
        activity: Activity,
        controller: PairedController,
        title: String,
        subtitle: String,
        onSend: (PairedPeer) -> Unit,
        onHotspot: (PairedPeer, Boolean) -> Unit,
        onPickDirectory: (PairedPeer) -> Unit,
    ): Dialog {
        val context = activity
        val box = ForkDialog.box(context)
        val dialog = ForkDialog.wrap(context, box)

        fun rebuild() {
            box.removeAllViews()
            box.addView(heading(context, title, 19f))
            box.addView(underline(context, box))
            if (subtitle.isNotEmpty()) {
                box.addView(
                    ForkDialog.label(context, subtitle, 13f, color = Roles.color(context, "sheet.secondary"))
                )
            }
            box.addView(ForkDialog.spacer(context, 10))

            addIdentity(context, box, controller) { rebuild() }

            box.addView(ForkDialog.spacer(context, 8))
            box.addView(divider(context))
            box.addView(ForkDialog.spacer(context, 10))
            box.addView(heading(context, "Paired devices", 16f))
            box.addView(underline(context, box))

            if (!controller.isPaired) {
                box.addView(
                    ForkDialog.label(
                        context,
                        "Pair two devices once — show a code on either one, scan or type it "
                            + "on the other. After that, sending is one tap and there is "
                            + "nothing to do on the device receiving.",
                        13f,
                        color = Roles.color(context, "sheet.hint"),
                    )
                )
            } else {
                val peers = controller.pairing.peers()
                for (peer in peers) {
                    box.addView(
                        peerRow(
                            context, peer, controller, onSend, onHotspot, onPickDirectory, dialog
                        ) { rebuild() }
                    )
                }
            }

            box.addView(ForkDialog.spacer(context, 10))
            box.addView(divider(context))
            box.addView(ForkDialog.spacer(context, 12))
            box.addView(bar(context, controller, activity, dialog) { rebuild() })
        }

        rebuild()
        dialog.show()
        // The list is drawn from what is already known, then refreshed by an actual scan, so
        // the sheet is useful the instant it opens rather than after a second and a half of
        // nothing.
        (activity as? LifecycleOwner)?.lifecycleScope?.launch {
            controller.scan()
            rebuild()
        }
        return dialog
    }

    /** A sheet heading, in the sheets' own heading role. */
    internal fun heading(context: android.content.Context, s: String, sizeSp: Float) =
        ForkDialog.heading(context, s, sizeSp, color = Roles.color(context, "sheet.heading"))

    /** The text-width rule the UI page puts under every heading. */
    internal fun underline(context: android.content.Context, parent: LinearLayout): View =
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ForkDialog.dp(context, 120), ForkDialog.dp(context, 1).coerceAtLeast(1)
            ).apply { bottomMargin = ForkDialog.dp(context, 8) }
            setBackgroundColor(Roles.color(context, "sheet.rule"))
        }

    /** The clear line between a sheet's groups. */
    internal fun divider(context: android.content.Context): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ForkDialog.dp(context, 1).coerceAtLeast(1)
        )
        setBackgroundColor(Roles.color(context, "sheet.divider"))
    }

    private fun addIdentity(
        context: android.content.Context,
        box: LinearLayout,
        controller: PairedController,
        refresh: () -> Unit,
    ) {
        box.addView(heading(context, "This device", 16f))
        box.addView(underline(context, box))
        // Coloured from the dialog's text-field roles when it joins the box (Theme.watch).
        val nameField = EditText(context).apply {
            setText(controller.pairing.name)
            hint = "Device name"
            setSingleLine()
            // Written on every keystroke rather than on a Done key, because the sheet can be
            // dismissed by tapping outside it and a name typed but not committed would be
            // silently lost.
            doAfterTextChanged { controller.pairing.name = it?.toString().orEmpty() }
        }
        box.addView(nameField)
        box.addView(
            ForkDialog.label(
                context,
                if (controller.pairing.shouldServe) {
                    "Listening on port $PRESENCE_PORT while this app is open."
                } else {
                    "Not paired with anything yet."
                },
                12f,
                color = Roles.color(context, "sheet.hint"),
            )
        )
    }

    internal fun peerRow(
        context: android.content.Context,
        peer: PairedPeer,
        controller: PairedController,
        onSend: (PairedPeer) -> Unit,
        onHotspot: (PairedPeer, Boolean) -> Unit,
        onPickDirectory: (PairedPeer) -> Unit,
        dialog: Dialog,
        refresh: () -> Unit,
    ): View {
        val warning = Roles.warning(context)
        // One icon column, one label edge: the device's mark at the left, every line of the
        // row in the column beside it.
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(
                ForkDialog.dp(context, 4), ForkDialog.dp(context, 8),
                ForkDialog.dp(context, 4), ForkDialog.dp(context, 8),
            )
            background = Theme.rowRipple(Roles.color(context, "sheet.text"))
        }
        row.addView(
            ImageView(context).apply {
                setImageDrawable(Icons.drawable(context, Icons.Icon.DEVICE, IconRole.CONTACT, 22f))
                layoutParams = LinearLayout.LayoutParams(
                    ForkDialog.dp(context, 22), ForkDialog.dp(context, 22)
                ).apply { marginEnd = ForkDialog.dp(context, 12); topMargin = ForkDialog.dp(context, 1) }
            }
        )
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(column)

        val fresh = System.currentTimeMillis() / 1000 - peer.lastSeen < 60
        column.addView(
            ForkDialog.label(
                context,
                peer.displayName,
                16f,
                color = Roles.color(context, "sheet.text"),
                bold = true,
            )
        )
        val state = when {
            fresh && peer.lastIp != null -> "● on this network — ${peer.lastIp}"
            peer.lastIp != null -> "○ ${whenSeen(peer.lastSeen)} at ${peer.lastIp}"
            else -> "○ ${whenSeen(peer.lastSeen)}"
        }
        column.addView(
            ForkDialog.label(
                context, "${peer.os.ifEmpty { "not yet heard from" }} · $state", 12f,
                // Within reach right now: the "it worked" colour. Otherwise a status line.
                color = if (fresh && peer.lastIp != null) Roles.success(context)
                else Roles.color(context, "sheet.secondary"),
            )
        )
        // Where files from THIS device land, pressable on its own, so the row's tap can stay
        // "send" while the directory is still one tap away — and a pill, so that it says it can
        // be pressed. Red when unset: a device with no directory refuses every transfer, and
        // that must be visible here rather than discovered when something fails to arrive.
        val unset = peer.receiveDir == null
        column.addView(
            ForkDialog.label(
                context,
                (peer.receiveDir?.let { "Receives into ${directoryName(context, it)}" }
                    ?: "No directory set — transfers from this device will be refused") + "  ›",
                12f,
                color = if (unset) warning else Roles.color(context, "sheet.pill.text"),
            ).apply {
                Icons.lead(
                    this, Icons.Icon.DIRECTORY, IconRole.VIEW, sizeDp = 14f,
                    color = if (unset) warning else null,
                )
                background = Theme.pillBackground(
                    context,
                    if (unset) warning else Roles.color(context, "sheet.pill.frame"),
                    Roles.color(context, "sheet.pill.bg"),
                )
                setPadding(
                    ForkDialog.dp(context, 12), ForkDialog.dp(context, 5),
                    ForkDialog.dp(context, 12), ForkDialog.dp(context, 5),
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = ForkDialog.dp(context, 5); bottomMargin = ForkDialog.dp(context, 4) }
                isClickable = true
                isFocusable = true
                setOnClickListener { dialog.dismiss(); onPickDirectory(peer) }
            }
        )
        column.addView(
            ForkDialog.label(
                context, "Tap to send · hold to rename, move or forget", 11f,
                color = Roles.color(context, "sheet.hint"),
            )
        )
        if (!peer.autoAccept) {
            column.addView(
                ForkDialog.label(
                    context,
                    "Set not to accept transfers from this device without asking",
                    12f,
                    color = warning,
                )
            )
        }
        row.setOnClickListener {
            dialog.dismiss()
            onSend(peer)
        }
        row.setOnLongClickListener {
            // The same menu the pill strip opens, so the two surfaces cannot drift into two
            // different half-menus.
            (context as? Activity)?.let { activity ->
                DeviceMenu.show(
                    activity = activity,
                    controller = controller,
                    peer = peer,
                    onPickDirectory = { target -> dialog.dismiss(); onPickDirectory(target) },
                    onHotspot = { target, sending -> dialog.dismiss(); onHotspot(target, sending) },
                    onChanged = { refresh() },
                )
            }
            true
        }
        return row
    }

    private fun bar(
        context: android.content.Context,
        controller: PairedController,
        activity: Activity,
        dialog: Dialog,
        refresh: () -> Unit,
    ): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(ForkDialog.pill(context, "Close") { dialog.dismiss() })
        bar.addView(
            View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            }
        )
        bar.addView(
            ForkDialog.pill(context, "Look again") {
                (activity as? LifecycleOwner)?.lifecycleScope?.launch {
                    controller.scan()
                    refresh()
                }
            }.apply {
                ForkDialog.icon(this, Icons.Icon.SEARCH, IconRole.VIEW)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = ForkDialog.dp(context, 8) }
            }
        )
        bar.addView(
            ForkDialog.pill(context, "Pair new device", main = true) {
                showPairChoices(activity, controller, dialog, refresh)
            }.apply { ForkDialog.icon(this, Icons.Icon.ADD, IconRole.CONNECT) }
        )
        return bar
    }

    // ── pairing ───────────────────────────────────────────────────────────────────────────

    private fun showPairChoices(
        activity: Activity,
        controller: PairedController,
        sheet: Dialog,
        refresh: () -> Unit,
    ) {
        // Pairwise, no group (白い熊, 2026-09-11): either device can show the code, the
        // other scans or types it, and a third pairing never touches the first.
        ForkMenu.show(
            activity,
            "Pair a device",
            listOf(
                ForkMenu.Action("Show my code", Icons.Icon.QR, IconRole.SHARE, opens = true) {
                    val uri = controller.pairing.newCode()
                    // Started before the code goes up, not after: the scanning device
                    // introduces itself the moment it has the key, and nothing answers a
                    // probe that arrives before this side is listening. The "Stay reachable"
                    // service reads the keys afresh per packet, so it needs no restart.
                    if (!PresenceService.running) controller.start()
                    showPairCode(activity, uri) { refresh() }
                    refresh()
                },
                ForkMenu.Action("Scan a code", Icons.Icon.CAMERA, IconRole.CONNECT, opens = true) {
                    // Closed before the camera opens, because the scanner's result reopens
                    // this sheet — leaving it up would stack a second one over the first,
                    // and the fresh list would be hidden behind the stale one.
                    sheet.dismiss()
                    (activity as? MainActivity)?.scanPairingCode()
                },
                ForkMenu.Action("Type a code", Icons.Icon.KEYBOARD, IconRole.MESSAGE, opens = true) {
                    typePairCode(activity, controller, refresh)
                },
            ),
            cancelLabel = "Cancel",
        )
    }

    /** The QR and, underneath it, the same code in a form a person can read across. */
    fun showPairCode(activity: Activity, uri: String, onClosed: () -> Unit = {}) {
        val context = activity
        val typed = typedCode(uri)
        val light = Roles.color(context, "sheet.qr.light")
        val dark = Roles.color(context, "sheet.qr.dark")
        val box = ForkDialog.box(context)
        box.addView(ForkDialog.heading(context, "Pair a device"))
        box.addView(
            ForkDialog.note(
                context,
                "On the other device, open Devices ＋ → Pair and scan this code — or type "
                    + "what is underneath it. The two are paired the moment it answers.",
            )
        )
        box.addView(ForkDialog.spacer(context, 12))

        val holder = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // The quiet zone is yellow like the code's light modules, so the block reads as
            // one object on the black page — the same treatment the password QR gets. The QR
            // itself is never tinted; that would stop it scanning.
            setBackgroundColor(light)
            val pad = ForkDialog.dp(context, 16)
            setPadding(pad, pad, pad, pad)
        }
        val size = ForkDialog.dp(context, 220)
        holder.addView(
            ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(size, size)
                setImageBitmap(qrBitmap(uri, size, light, dark))
            }
        )
        holder.addView(
            ForkDialog.label(context, typed, 12f, color = dark, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, ForkDialog.dp(context, 8), 0, 0)
            }
        )
        box.addView(holder)
        box.addView(ForkDialog.spacer(context, 10))
        box.addView(
            ForkDialog.label(
                context,
                "Whoever uses this code first becomes paired with this phone. Show it, don’t send it.",
                12f,
                color = Roles.warning(context),
            )
        )
        box.addView(ForkDialog.spacer(context, 12))

        val dialog = ForkDialog.wrap(context, box)
        val bar = LinearLayout(context).apply { gravity = Gravity.END }
        bar.addView(ForkDialog.pill(context, "Done", main = true) { dialog.dismiss() })
        box.addView(bar)
        // The other device is found by answering its probe while this is on screen, so the
        // list underneath is out of date by the time the code is put away.
        dialog.setOnDismissListener { onClosed() }
        dialog.show()
    }

    private fun typePairCode(
        activity: Activity,
        controller: PairedController,
        refresh: () -> Unit,
    ) {
        val context = activity
        val box = ForkDialog.box(context)
        box.addView(ForkDialog.heading(context, "Type a code"))
        box.addView(
            ForkDialog.note(
                context,
                "Type the 78 characters shown under the QR code on the other device. Spaces "
                    + "and capitals do not matter.",
            )
        )
        // Coloured from the dialog's text-field roles when it joins the box (Theme.watch).
        val field = EditText(context).apply { hint = "Pairing code" }
        box.addView(field)
        box.addView(ForkDialog.spacer(context, 12))

        val dialog = ForkDialog.wrap(context, box)
        val bar = LinearLayout(context).apply { gravity = Gravity.END }
        bar.addView(
            ForkDialog.pill(context, "Cancel") { dialog.dismiss() }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = ForkDialog.dp(context, 10) }
            }
        )
        bar.addView(
            ForkDialog.pill(context, "Pair", main = true) {
                val text = field.text.toString()
                val parsed = parsePairUri(text)
                if (parsed == null) {
                    ForkDialog.alert(
                        context,
                        "That is not a pairing code",
                        if (isOldPairUri(text)) {
                            "That code is from an older version of this app. Update the app on " +
                                "the other device and show the code again."
                        } else {
                            "A pairing code is 78 characters. Check it against the one shown " +
                                "under the QR code on the other device."
                        },
                    )
                    return@pill
                }
                dialog.dismiss()
                (activity as? MainActivity)?.pairFromCode(parsed) ?: run {
                    controller.pairing.addPeerFromCode(parsed)
                    controller.start()
                }
                refresh()
            }
        )
        box.addView(bar)
        dialog.show()
    }

    /** The tree's own name, not the opaque SAF URI, which says nothing to anyone. */
    private fun directoryName(context: android.content.Context, uri: String): String = try {
        val parsed = android.net.Uri.parse(uri)
        androidx.documentfile.provider.DocumentFile.fromTreeUri(context, parsed)?.name
            ?: parsed.lastPathSegment?.substringAfterLast(':')
            ?: uri
    } catch (e: Exception) {
        uri
    }

    internal fun whenSeen(seconds: Long): String {
        if (seconds == 0L) return "never seen on a network"
        val ago = System.currentTimeMillis() / 1000 - seconds
        return when {
            ago < 60 -> "seen just now"
            ago < 3600 -> "seen ${ago / 60} min ago"
            ago < 86400 -> "seen ${ago / 3600} h ago"
            else -> "seen ${ago / 86400} days ago"
        }
    }

    /**
     * Dark modules on the light colour, so the code sits on the same field as its quiet zone.
     * Both are roles; the defaults are black on the accent. Dark must stay darker than light:
     * inverting a QR code stops most scanners reading it.
     */
    private fun qrBitmap(text: String, size: Int, light: Int, dark: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix[x, y]) dark else light)
            }
        }
        return bitmap
    }
}
