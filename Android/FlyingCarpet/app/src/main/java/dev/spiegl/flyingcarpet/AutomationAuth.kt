package dev.spiegl.flyingcarpet

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The gate in front of both automation doors — the broadcast receiver ([StateExportReceiver]) and
 * the data provider ([dev.spiegl.flyingcarpet.automation.AutomationProvider]).
 *
 * ## v2: the switch is ON and the token is OFF
 *
 * v1 shipped this app closed: `automation_enabled` defaulted to false and every request also had to
 * carry a 48-character secret 白い熊 had pasted out of this page and into the caller's. That is the
 * wrong shape for where the family went. **A pasted secret cannot survive a wipe**, and the case
 * this contract now exists to serve is 応用管理 restoring apps *with their data* onto a clean phone,
 * where nothing has been configured and nobody has pasted anything. A gate that only works once the
 * phone is already set up is no gate for setting the phone up.
 *
 * So [enabled] defaults to **true**, [requireToken] is new and defaults to **false**, and the token
 * itself is unchanged — 24 `SecureRandom` bytes, hex, generated lazily on first read.
 *
 * Identity did not disappear with the token, it moved: the provider — the only door that moves data
 * through a caller-supplied descriptor, and the only one that can import — checks the caller's exact
 * package name, its uid and its pinned signing certificate
 * ([dev.spiegl.flyingcarpet.automation.AutomationCallers]). The receiver stays unauthenticated on
 * purpose: it only ever writes where it was told to and reports what it did.
 *
 * ## A token we do not want is IGNORED, never refused
 *
 * Tokens live in task arguments and workspace variables that outlive the setting they were pasted
 * for. A caller still sending one — because it was configured last year, or because another app on
 * the batch does want one — must be served. Refusing it would turn "白い熊 turned a switch off" into
 * "half the batch mysteriously fails", which is precisely the friction the switch exists to remove.
 * That is why [refuse] looks at [requireToken] *before* it looks at the candidate at all.
 *
 * Device-local by design: this is its OWN SharedPreferences file, and the backup engine ([Backup])
 * exports "shiroikuma_ui" and the font files only — never this file. The token therefore never
 * travels in a backup ZIP and never leaves the phone.
 */
object AutomationAuth {

    private const val PREFS_FILE = "mahojutan_automation"
    private const val KEY_ENABLED = "automation_enabled"
    private const val KEY_REQUIRE_TOKEN = "automation_require_token"
    private const val KEY_TOKEN = "automation_token"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /**
     * The master switch — **default ON** (v2). It stays a switch rather than being removed because
     * it is the only way to close this app off, and a feature that can be turned on but never off
     * is one 白い熊 cannot retreat from.
     */
    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /** 「Use authorization token?」 — **default OFF** (v2). See the class note for why. */
    fun requireToken(context: Context): Boolean = prefs(context).getBoolean(KEY_REQUIRE_TOKEN, false)

    fun setRequireToken(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_REQUIRE_TOKEN, value).apply()
    }

    /** The shared secret — 24 random bytes, hex; generated on first read so the row always shows one. */
    fun token(context: Context): String =
        prefs(context).getString(KEY_TOKEN, null)?.takeIf { it.isNotEmpty() } ?: regenerateToken(context)

    fun regenerateToken(context: Context): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        prefs(context).edit().putString(KEY_TOKEN, token).apply()
        return token
    }

    /** Abbreviated form for the settings row — `80922d8c…4c49a87c`. */
    fun abbreviate(token: String): String =
        if (token.length <= 20) token else "${token.take(8)}…${token.takeLast(8)}"

    /**
     * True when the caller's token matches the stored secret. Compared **constant-time**
     * ([MessageDigest.isEqual]) so a wrong token leaks nothing through timing. Only ever consulted
     * when [requireToken] is on — see [refuse].
     */
    fun isTokenValid(context: Context, candidate: String?): Boolean {
        if (candidate.isNullOrEmpty()) return false
        return MessageDigest.isEqual(candidate.toByteArray(), token(context).toByteArray())
    }

    /**
     * **The one gate.** `null` = proceed; otherwise the exact `ERROR:` line to answer with.
     *
     * Written once and called from every entry point on purpose: two checks spelled out at each
     * door is how "disabled" and "bad token" drift apart across forty-two apps. The two stay
     * distinct errors because they debug differently — one is a switch on this page, the other is a
     * string in the caller's configuration.
     */
    fun refuse(context: Context, candidate: String?): String? = when {
        !enabled(context) -> "ERROR:automation disabled"
        requireToken(context) && !isTokenValid(context, candidate) -> "ERROR:bad token"
        else -> null
    }
}
