package dev.spiegl.flyingcarpet

import android.app.Dialog
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment

// Fork: both dialogs here were stock AlertDialogs — a grey panel with a bare-text button. They
// are the fork's own dialog now (ForkDialog): the bordered box, text in the theme's colours, and
// a pill to close them.

class Alert(private val message: String) : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireActivity()
        val box = ForkDialog.box(context)
        box.addView(ForkDialog.label(context, message))
        val dialog = ForkDialog.wrap(context, box)
        box.addView(
            LinearLayout(context).apply {
                gravity = Gravity.END
                clipChildren = false
                setPadding(0, ForkDialog.dp(context, 16), 0, 0)
                addView(ForkDialog.pill(context, "OK", main = true) { dialog.dismiss() })
            },
        )
        return dialog
    }
}

class About : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireActivity()
        val s = Settings(context)
        // The "About page" section of the UI page has roles of its own for this dialog — its
        // ground, and the colour, font and size of its title and body. Its frame and its button
        // are the dialogs'.
        val box = ForkDialog.box(context, ground = s.colorOrNull("aboutBg") ?: Roles.GROUND)
        box.addView(
            ForkDialog.heading(context, "About 白い熊 魔法絨毯").apply { styleAbout(s, this, "aboutTitle") },
        )
        box.addView(
            ForkDialog.label(context, AboutMessage.trimIndent()).apply {
                setPadding(0, ForkDialog.dp(context, 10), 0, 0)
                styleAbout(s, this, "aboutBody")
            },
        )
        val dialog = ForkDialog.wrap(context, box)
        box.addView(
            LinearLayout(context).apply {
                gravity = Gravity.END
                clipChildren = false
                setPadding(0, ForkDialog.dp(context, 16), 0, 0)
                addView(ForkDialog.pill(context, "Close", main = true) { dialog.dismiss() })
            },
        )
        return dialog
    }

    // Applies "<key>.color/family/style/size" to a dialog TextView, defaulting to the accent.
    private fun styleAbout(s: Settings, tv: TextView, key: String) {
        tv.setTextColor(s.colorOrNull("$key.color") ?: Roles.ACCENT)
        val family = s.family("$key.family")
        val style = s.style("$key.style")
        if (family.isNotEmpty() || style >= 0) {
            val eff = if (style >= 0) style else (tv.typeface?.style ?: Typeface.NORMAL)
            tv.typeface = FontUtil.typeface(family, eff) ?: Typeface.create(tv.typeface, eff)
        }
        s.size("$key.size").let { if (it > 0f) tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, it) }
    }
}

const val AboutMessage = """
    白い熊 魔法絨毯
    https://github.com/ShiroiKuma0/shiroikuma-mahojutan

    A personal fork of Flying Carpet, whose original author and copyright follow.

    https://flyingcarpet.spiegl.dev
    Version 10.0.3
    theron@spiegl.dev
    Copyright 2026, Theron Spiegl, all rights reserved.

    白い熊 魔法絨毯 transfers files between two Android, iOS, Linux, macOS, and Windows devices over ad hoc WiFi. In Hotspot mode, no access point or shared network is required, just two WiFi cards in close range. Hotspot mode does not work from one Apple device (macOS or iOS) to another, because Apple no longer allows hotspots to be started programmatically: use Shared Network mode for those transfers.

    In Shared Network mode, both devices must be connected to the same network. No hotspot is created: the devices find each other on the network automatically. The receiving device generates the password either way, and the "Use Bluetooth" switch decides how the sending device gets it: with the switch off the receiver displays the password and its QR code, to be scanned or typed on the sending device; with the switch on it is handed over Bluetooth and there is nothing to scan or type.

    INSTRUCTIONS

    Turn Bluetooth on or off on both devices. If one side fails to initialize Bluetooth or has it turned off, the other side must disable the "Use Bluetooth" switch in 白い熊 魔法絨毯.
    
    Select Sending on one device and Receiving on the other. If not using Bluetooth, select the operating system of the other device. Click the "Start Transfer" button on each device. On the sending device, select the files or directory to send. On the receiving device, select the directory in which to receive files. (To send a directory, check "Send Directory" before clicking "Start Transfer". A directory you send is recreated inside the destination directory on the receiving device, with its contents inside.)
    
    If using Bluetooth, confirm the 6-digit PIN on each side. The WiFi connection will be configured automatically. If not using Bluetooth, you will need to scan a QR code or type in a password.
    
    When prompted to join a WiFi network or modify WiFi settings, say Allow. On Windows you may have to grant permission to add a firewall rule. On macOS you may have to grant location permissions, which Apple requires to scan for WiFi networks. 白い熊 魔法絨毯 does not read or collect your location, nor any other data.
    
    TROUBLESHOOTING

    Disable any VPN on both devices.

    If using Bluetooth fails, try manually unpairing the devices from one another and starting a new transfer.
    
    If sending from macOS to Linux, disable Bluetooth on both sides.

    白い熊 魔法絨毯 may make multiple attempts to join the other device's hotspot.
    
    Licensed under the GPL3: https://www.gnu.org/licenses/gpl-3.0.html#license-text`
"""
