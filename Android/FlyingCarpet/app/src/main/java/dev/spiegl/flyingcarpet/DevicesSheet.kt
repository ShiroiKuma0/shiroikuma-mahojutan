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
// surface in this fork and answers to the same UI page.
//
// The layout follows the shiroikuma-kxkb shape the rest of the app uses: a hairline between
// top-level sections, a text-width underline under every heading, and the same indentation
// ladder from section to row.

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
        onPickFolder: (PairedPeer) -> Unit,
    ): Dialog {
        val context = activity
        val box = ForkDialog.box(context)
        val dialog = ForkDialog.wrap(context, box)

        fun rebuild() {
            box.removeAllViews()
            box.addView(ForkDialog.heading(context, title))
            box.addView(underline(context, box))
            if (subtitle.isNotEmpty()) {
                box.addView(
                    ForkDialog.label(context, subtitle, 13f).apply { alpha = 0.8f }
                )
            }
            box.addView(ForkDialog.spacer(context, 10))

            addIdentity(context, box, controller) { rebuild() }

            box.addView(ForkDialog.spacer(context, 8))
            box.addView(ForkDialog.divider(context))
            box.addView(ForkDialog.spacer(context, 10))
            box.addView(ForkDialog.heading(context, "Paired devices", 16f))
            box.addView(underline(context, box))

            if (!controller.isPaired) {
                box.addView(
                    ForkDialog.label(
                        context,
                        "Pair two devices once — show a code on either one, scan or type it "
                            + "on the other. After that, sending is one tap and there is "
                            + "nothing to do on the device receiving.",
                        13f,
                    )
                )
            } else {
                val peers = controller.pairing.peers()
                for (peer in peers) {
                    box.addView(
                        peerRow(
                            context, peer, controller, onSend, onHotspot, onPickFolder, dialog
                        ) { rebuild() }
                    )
                }
            }

            box.addView(ForkDialog.spacer(context, 14))
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

    /** The text-width rule the UI page puts under every heading. */
    internal fun underline(context: android.content.Context, parent: LinearLayout): View =
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ForkDialog.dp(context, 120), ForkDialog.dp(context, 1)
            ).apply { bottomMargin = ForkDialog.dp(context, 8) }
            setBackgroundColor(ForkDialog.accent(context))
            alpha = 0.6f
        }

    private fun addIdentity(
        context: android.content.Context,
        box: LinearLayout,
        controller: PairedController,
        refresh: () -> Unit,
    ) {
        box.addView(ForkDialog.heading(context, "This device", 16f))
        box.addView(underline(context, box))
        val nameField = EditText(context).apply {
            setText(controller.pairing.name)
            setTextColor(ForkDialog.accent(context))
            setHintTextColor(ForkDialog.accent(context) and 0x66FFFFFF)
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
            ).apply { alpha = 0.75f }
        )
    }

    internal fun peerRow(
        context: android.content.Context,
        peer: PairedPeer,
        controller: PairedController,
        onSend: (PairedPeer) -> Unit,
        onHotspot: (PairedPeer, Boolean) -> Unit,
        onPickFolder: (PairedPeer) -> Unit,
        dialog: Dialog,
        refresh: () -> Unit,
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                ForkDialog.dp(context, 8), ForkDialog.dp(context, 8),
                ForkDialog.dp(context, 8), ForkDialog.dp(context, 8),
            )
        }
        val fresh = System.currentTimeMillis() / 1000 - peer.lastSeen < 60
        row.addView(
            ForkDialog.label(
                context,
                peer.displayName,
                16f,
                bold = true,
            )
        )
        val state = when {
            fresh && peer.lastIp != null -> "● on this network — ${peer.lastIp}"
            peer.lastIp != null -> "○ ${whenSeen(peer.lastSeen)} at ${peer.lastIp}"
            else -> "○ ${whenSeen(peer.lastSeen)}"
        }
        row.addView(
            ForkDialog.label(
                context, "${peer.os.ifEmpty { "not yet heard from" }} · $state", 12f
            ).apply { alpha = 0.8f }
        )
        // Where files from THIS device land, on its own line and clickable on its own, so the
        // row's tap can stay "send" while the folder is still one tap away. Red when unset —
        // a device with no folder refuses every transfer, and that must be visible here
        // rather than discovered when something fails to arrive.
        val folderLine = ForkDialog.label(
            context,
            peer.receiveDir?.let { "Receives into ${folderName(context, it)}" }
                ?: "No folder set — transfers from this device will be refused",
            12f,
            color = if (peer.receiveDir == null) Color.parseColor("#FFFF5252") else null,
        ).apply {
            setPadding(0, ForkDialog.dp(context, 4), 0, ForkDialog.dp(context, 4))
            setOnClickListener { dialog.dismiss(); onPickFolder(peer) }
        }
        row.addView(folderLine)
        row.addView(
            ForkDialog.label(context, "Tap to send · hold to rename, move or forget", 11f)
                .apply { alpha = 0.6f }
        )
        if (!peer.autoAccept) {
            row.addView(
                ForkDialog.label(
                    context,
                    "Set not to accept transfers from this device without asking",
                    12f,
                    color = Color.parseColor("#FFFF5252"),
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
                    onPickFolder = { target -> dialog.dismiss(); onPickFolder(target) },
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
            }
        )
        bar.addView(
            ForkDialog.pill(context, "＋ Pair new device") {
                showPairChoices(activity, controller, dialog, refresh)
            }
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
        ForkDialog.chooser(
            activity,
            "Pair a device",
            listOf("Show my code", "Scan a code", "Type a code"),
        ) { index ->
            when (index) {
                0 -> {
                    val uri = controller.pairing.newCode()
                    // Started before the code goes up, not after: the scanning device
                    // introduces itself the moment it has the key, and nothing answers a
                    // probe that arrives before this side is listening. The "Stay reachable"
                    // service reads the keys afresh per packet, so it needs no restart.
                    if (!PresenceService.running) controller.start()
                    showPairCode(activity, uri) { refresh() }
                    refresh()
                }
                1 -> {
                    // Closed before the camera opens, because the scanner's result reopens
                    // this sheet — leaving it up would stack a second one over the first,
                    // and the fresh list would be hidden behind the stale one.
                    sheet.dismiss()
                    (activity as? MainActivity)?.scanPairingCode()
                }
                2 -> typePairCode(activity, controller, refresh)
            }
        }.show()
    }

    /** The QR and, underneath it, the same code in a form a person can read across. */
    fun showPairCode(activity: Activity, uri: String, onClosed: () -> Unit = {}) {
        val context = activity
        val typed = typedCode(uri)
        val box = ForkDialog.box(context)
        box.addView(ForkDialog.heading(context, "Pair a device"))
        box.addView(
            ForkDialog.label(
                context,
                "On the other device, open Devices ＋ → Pair and scan this code — or type "
                    + "what is underneath it. The two are paired the moment it answers.",
                13f,
            )
        )
        box.addView(ForkDialog.spacer(context, 12))

        val holder = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // The quiet zone is yellow like the code's light modules, so the block reads as
            // one object on the black page — the same treatment the password QR gets. The QR
            // itself is never tinted; that would stop it scanning.
            setBackgroundColor(ForkDialog.accent(context))
            val pad = ForkDialog.dp(context, 16)
            setPadding(pad, pad, pad, pad)
        }
        val size = ForkDialog.dp(context, 220)
        holder.addView(
            ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(size, size)
                setImageBitmap(qrBitmap(uri, size, ForkDialog.accent(context)))
            }
        )
        holder.addView(
            ForkDialog.label(context, typed, 12f, color = Color.BLACK, bold = true).apply {
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
                color = Color.parseColor("#FFFF5252"),
            )
        )
        box.addView(ForkDialog.spacer(context, 12))

        val dialog = ForkDialog.wrap(context, box)
        val bar = LinearLayout(context).apply { gravity = Gravity.END }
        bar.addView(ForkDialog.pill(context, "Done") { dialog.dismiss() })
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
            ForkDialog.label(
                context,
                "Type the 78 characters shown under the QR code on the other device. Spaces "
                    + "and capitals do not matter.",
                13f,
            )
        )
        val field = EditText(context).apply {
            setTextColor(ForkDialog.accent(context))
            setHintTextColor(ForkDialog.accent(context) and 0x66FFFFFF)
            hint = "Pairing code"
        }
        box.addView(field)
        box.addView(ForkDialog.spacer(context, 12))

        val dialog = ForkDialog.wrap(context, box)
        val bar = LinearLayout(context).apply { gravity = Gravity.END }
        bar.addView(ForkDialog.pill(context, "Cancel") { dialog.dismiss() })
        bar.addView(
            ForkDialog.pill(context, "Pair") {
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
    private fun folderName(context: android.content.Context, uri: String): String = try {
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
     * Black modules on the accent colour, so the code sits on the same yellow field as its
     * quiet zone. Dark stays black: inverting a QR code stops most scanners reading it.
     */
    private fun qrBitmap(text: String, size: Int, light: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else light)
            }
        }
        return bitmap
    }
}
