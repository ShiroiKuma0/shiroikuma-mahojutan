package dev.spiegl.flyingcarpet

import android.content.Context
import android.graphics.Color

// Fork: the colour role table (the black-yellow theme's rule: nothing hard-coded, every item
// settable). This file is the ONE place a colour value is written in the app:
//
//   - the two primaries, #FFFF00 and #000000, which every role falls back to;
//   - the settled defaults of the colours that carry meaning (the icon roles, the warning, the
//     delete red, the success green) and of the two deliberate dims (dim gold, switch off).
//
// Everything else — layouts, themes, drawables, the code that draws — names a role and never a
// value. A role is stored under its key in the same "shiroikuma_ui" preferences as the rest of
// the UI page, so it travels in the export with the page; an absent key means "the default".
//
// Two kinds of role live side by side:
//
//   - the older per-element catalogue (UiCatalog in Appearance.kt): one colour field per part of
//     the main screen and of the UI page, under the keys 白い熊's stored settings already use;
//   - the table below, for what the catalogue never covered: dialogs, menus, the Devices and
//     Send sheets, transfer lanes, the icon roles, warnings, and the rest of the UI page.
//
// A role whose item used to be painted from one of the older keys names that key as what it
// [Role.inherits] from, so a page 白い熊 had already repainted keeps its look until the new
// role is set by itself.

private typealias I = Icons.Icon

/** What a row does, and so which colour its icon takes (the theme's icon role table). */
enum class IconRole(val key: String) {
    CONNECT("icon.connect"),
    MESSAGE("icon.message"),
    CONTACT("icon.contact"),
    SHARE("icon.share"),
    VIEW("icon.view"),
    BLOCK("icon.block"),
    DELETE("icon.delete"),
}

/**
 * One settable colour. [section] is the title of the UI page section its swatch row sits in.
 * The default is, in this order: the value of the role or older key it [inherits] from, a colour
 * [derived] in code from other roles, or its own [base].
 */
class Role(
    val key: String,
    val section: String,
    val label: String,
    val icon: Icons.Icon,
    internal val base: Int = Roles.ACCENT,
    val inherits: String? = null,
    internal val derived: ((Context) -> Int)? = null,
)

object Roles {
    // ── The two primaries, and the settled meaning colours. Written here, once. ─────────────
    const val ACCENT = 0xFFFFFF00.toInt()
    const val GROUND = 0xFF000000.toInt()

    /** Explanations and the rule beside a described row. */
    const val DIM_GOLD = 0xFFC8C800.toInt()
    /** A switch that is off: an opaque darker yellow, 55 % of the way from the ground to the accent. */
    const val SWITCH_OFF = 0xFF8C8C00.toInt()
    /** “Something is wrong, fix this” — the red this app has used for it since its first build. */
    private val WARNING = 0xFFFF5252.toInt()
    private val DELETE = 0xFFFF0000.toInt()
    private val CONNECT = 0xFF00FF00.toInt()
    private val MESSAGE = 0xFF4D94FF.toInt()
    private val CONTACT = 0xFF00FFFF.toInt()
    private val SHARE = 0xFFFF8000.toInt()
    private val BLOCK = 0xFFFF00FF.toInt()

    // ── Section titles of the UI page that hold role rows. ──────────────────────────────────
    const val S_DIALOGS = "Dialogs"
    const val S_MENUS = "Menus"
    const val S_SHEETS = "Devices and sheets"
    const val S_LANES = "Transfer lanes"
    const val S_ICONS = "Icons"
    const val S_WARNINGS = "Warnings"
    const val S_PAGE = "This settings page"

    // Older catalogue keys a role may inherit from, and what they fall back to when unset.
    private val olderDefaults = mapOf(
        // Cancelling throws a running transfer away, so it wears the delete red unless set
        // otherwise (白い熊, 2026-11-21).
        "cancel.stroke" to DELETE,
        "cancel.color" to DELETE,
        "page.bg" to GROUND,
        "window.bg" to GROUND,
        "window.statusBar" to GROUND,
        "page.note.color" to DIM_GOLD,
        "bluetoothHint.color" to DIM_GOLD,
        "switch.off" to SWITCH_OFF,
    )

    val all: List<Role> = listOf(
        // ── Dialogs ──
        Role("dialog.bg", S_DIALOGS, "Ground", I.PALETTE, GROUND, inherits = "page.bg"),
        Role("dialog.frame", S_DIALOGS, "Frame", I.FRAME, inherits = "page.title.color"),
        Role("dialog.title", S_DIALOGS, "Title", I.TEXT, inherits = "page.title.color"),
        Role("dialog.text", S_DIALOGS, "Text", I.TEXT, inherits = "page.title.color"),
        Role("dialog.note", S_DIALOGS, "Notes and hints", I.INFO, DIM_GOLD, inherits = "page.note.color"),
        Role(
            "dialog.divider", S_DIALOGS, "Divider", I.DIVIDER,
            derived = { blend(color(it, "dialog.bg"), color(it, "dialog.frame"), 0.7f) },
        ),
        Role("dialog.pill.frame", S_DIALOGS, "Button frame", I.FRAME, inherits = "dialog.frame"),
        Role("dialog.pill.text", S_DIALOGS, "Button text", I.TEXT, inherits = "dialog.text"),
        Role("dialog.pill.bg", S_DIALOGS, "Button ground", I.PALETTE, GROUND, inherits = "dialog.bg"),
        Role("dialog.field.text", S_DIALOGS, "Text field: text", I.EDIT, inherits = "dialog.text"),
        Role(
            "dialog.field.hint", S_DIALOGS, "Text field: placeholder", I.EDIT,
            derived = { blend(color(it, "dialog.bg"), color(it, "dialog.field.text"), 0.5f) },
        ),
        Role("dialog.field.line", S_DIALOGS, "Text field: underline and cursor", I.EDIT, inherits = "dialog.frame"),
        Role(
            "dialog.field.selection", S_DIALOGS, "Text field: selection", I.EDIT,
            derived = { alpha(color(it, "dialog.field.line"), 0.35f) },
        ),
        Role("dialog.slider", S_DIALOGS, "Slider", I.SLIDERS, inherits = "dialog.frame"),
        Role(
            "dialog.slider.track", S_DIALOGS, "Slider track", I.SLIDERS,
            derived = { blend(color(it, "dialog.bg"), color(it, "dialog.slider"), 0.55f) },
        ),
        Role("dialog.check", S_DIALOGS, "Tick box", I.CHECK, inherits = "dialog.frame"),
        Role("dialog.check.text", S_DIALOGS, "Tick box label", I.TEXT, inherits = "dialog.text"),
        Role("dialog.choice.frame", S_DIALOGS, "Choice row frame", I.FRAME, inherits = "dialog.frame"),
        Role("dialog.choice.text", S_DIALOGS, "Choice row text", I.TEXT, inherits = "dialog.text"),

        // ── Menus ──
        Role("menu.bg", S_MENUS, "Ground", I.PALETTE, GROUND, inherits = "dialog.bg"),
        Role("menu.frame", S_MENUS, "Frame", I.FRAME, inherits = "dialog.frame"),
        Role("menu.header", S_MENUS, "Header line", I.TEXT, inherits = "dialog.title"),
        Role("menu.row.frame", S_MENUS, "Row frame", I.FRAME, inherits = "dialog.frame"),
        Role("menu.row.text", S_MENUS, "Row label", I.TEXT, inherits = "dialog.text"),
        Role("menu.toggle.text", S_MENUS, "Switch row label", I.TOGGLE, inherits = "dialog.text"),
        Role("menu.note", S_MENUS, "Notes", I.INFO, DIM_GOLD, inherits = "dialog.note"),
        Role(
            "menu.divider", S_MENUS, "Group divider", I.DIVIDER,
            derived = { blend(color(it, "menu.bg"), color(it, "menu.row.frame"), 0.7f) },
        ),

        // ── Devices and sheets ──
        Role("sheet.heading", S_SHEETS, "Headings", I.TEXT, inherits = "dialog.title"),
        Role("sheet.rule", S_SHEETS, "Heading underline", I.DIVIDER, inherits = "sheet.heading"),
        Role("sheet.text", S_SHEETS, "Device names and text", I.DEVICE, inherits = "dialog.text"),
        Role("sheet.secondary", S_SHEETS, "Status lines", I.INFO, DIM_GOLD, inherits = "dialog.note"),
        Role("sheet.hint", S_SHEETS, "Hints", I.INFO, DIM_GOLD, inherits = "dialog.note"),
        Role(
            "sheet.divider", S_SHEETS, "Group divider", I.DIVIDER,
            derived = { blend(color(it, "dialog.bg"), color(it, "sheet.heading"), 0.7f) },
        ),
        Role("sheet.pill.frame", S_SHEETS, "Device pill frame", I.FRAME, inherits = "dialog.pill.frame"),
        Role("sheet.pill.text", S_SHEETS, "Device pill text", I.TEXT, inherits = "dialog.pill.text"),
        Role("sheet.pill.bg", S_SHEETS, "Device pill ground", I.PALETTE, GROUND, inherits = "dialog.pill.bg"),
        Role("sheet.segment.frame", S_SHEETS, "Route selector frame", I.FRAME, inherits = "dialog.pill.frame"),
        Role("sheet.segment.text", S_SHEETS, "Route selector text", I.TEXT, inherits = "dialog.pill.text"),
        Role("sheet.qr.light", S_SHEETS, "Pairing code: light modules", I.QR, inherits = "dialog.frame"),
        Role("sheet.qr.dark", S_SHEETS, "Pairing code: dark modules and text", I.QR, GROUND),

        // ── Transfer lanes ──
        Role("lane.title", S_LANES, "Device name", I.DEVICE),
        Role("lane.waiting", S_LANES, "Waiting line", I.INFO, DIM_GOLD, inherits = "page.note.color"),
        Role("lane.details", S_LANES, "Progress lines", I.TEXT, inherits = "progress.color"),
        Role("lane.bar", S_LANES, "Bar", I.SLIDERS, inherits = "progress.color"),
        Role("lane.bar.bg", S_LANES, "Bar ground", I.PALETTE, GROUND, inherits = "window.bg"),
        Role("lane.cancel.frame", S_LANES, "Cancel frame", I.FRAME, DELETE, inherits = "warn.delete"),
        Role("lane.cancel.text", S_LANES, "Cancel text", I.TEXT, DELETE, inherits = "warn.delete"),
        Role("lane.cancel.bg", S_LANES, "Cancel ground", I.PALETTE, GROUND, inherits = "window.bg"),

        // ── Icons: coloured by what the row does ──
        Role(IconRole.CONNECT.key, S_ICONS, "Connects, sends, receives", I.WIFI, CONNECT),
        Role(IconRole.MESSAGE.key, S_ICONS, "Writes or types", I.KEYBOARD, MESSAGE),
        Role(IconRole.CONTACT.key, S_ICONS, "Is about a device", I.DEVICE, CONTACT),
        Role(IconRole.SHARE.key, S_ICONS, "Copies, shares, exports", I.EXPORT, SHARE),
        Role(IconRole.VIEW.key, S_ICONS, "Views, opens, settings", I.EYE, ACCENT),
        Role(IconRole.BLOCK.key, S_ICONS, "Blocks, refuses, leaves", I.BLOCK, BLOCK),
        Role(IconRole.DELETE.key, S_ICONS, "Deletes, forgets, resets", I.DELETE, DELETE, inherits = "warn.delete"),

        // ── Warnings ──
        Role("warn.warning", S_WARNINGS, "Something is wrong", I.INFO, WARNING),
        Role("warn.delete", S_WARNINGS, "Deleting and resetting", I.DELETE, DELETE),
        Role("warn.success", S_WARNINGS, "It worked", I.CHECK, CONNECT),

        // ── This settings page (beside the catalogue's own page.* fields) ──
        Role("page.statusBar", S_PAGE, "Status bar", I.PALETTE, GROUND, inherits = "window.statusBar"),
        Role("page.navBar", S_PAGE, "Navigation bar", I.PALETTE, GROUND, inherits = "page.bg"),
        Role("page.chip.frame", S_PAGE, "Index chip frame", I.FRAME, inherits = "page.section.color"),
        Role("page.chip.text", S_PAGE, "Index chip text", I.TEXT, inherits = "page.section.color"),
        Role("page.chevron", S_PAGE, "Fold marks", I.RIGHT, inherits = "page.section.color"),
        Role("page.section.rule", S_PAGE, "Section heading underline", I.DIVIDER, inherits = "page.section.color"),
        Role("page.element.rule", S_PAGE, "Element heading underline", I.DIVIDER, inherits = "page.element.color"),
        Role(
            "page.divider", S_PAGE, "Line between sections", I.DIVIDER,
            derived = { blend(color(it, "page.bg"), color(it, "page.section.color"), 0.7f) },
        ),
        Role("page.rule", S_PAGE, "Rule beside explained rows", I.DIVIDER, DIM_GOLD, inherits = "page.note.color"),
        Role("page.pill.frame", S_PAGE, "Action pill frame", I.FRAME, inherits = "page.label.color"),
        Role("page.pill.text", S_PAGE, "Action pill text", I.TEXT, inherits = "page.label.color"),
        Role("page.value", S_PAGE, "Values", I.TEXT, inherits = "page.label.color"),
        Role("page.swatch.frame", S_PAGE, "Swatch outline", I.FRAME, inherits = "page.label.color"),
        Role("page.field.text", S_PAGE, "Text field: text", I.EDIT, inherits = "page.label.color"),
        Role(
            "page.field.hint", S_PAGE, "Text field: placeholder", I.EDIT,
            derived = { blend(color(it, "page.bg"), color(it, "page.field.text"), 0.5f) },
        ),
        Role("page.field.line", S_PAGE, "Text field: underline and cursor", I.EDIT, inherits = "page.label.color"),
        Role(
            "page.field.selection", S_PAGE, "Text field: selection", I.EDIT,
            derived = { alpha(color(it, "page.field.line"), 0.35f) },
        ),
        Role("page.slider", S_PAGE, "Slider", I.SLIDERS, inherits = "page.label.color"),
        Role(
            "page.slider.track", S_PAGE, "Slider track", I.SLIDERS,
            derived = { blend(color(it, "page.bg"), color(it, "page.slider"), 0.55f) },
        ),
        Role(
            "page.preview.bg", S_PAGE, "Preview ground", I.PALETTE,
            derived = { blend(color(it, "page.bg"), color(it, "page.label.color"), 0.13f) },
        ),
    )

    private val byKey: Map<String, Role> = all.associateBy { it.key }

    fun find(key: String): Role? = byKey[key]

    fun inSection(section: String): List<Role> = all.filter { it.section == section }

    /** The colour to draw [key] in: what 白い熊 set, or its default. Also reads the older keys. */
    fun color(context: Context, key: String): Int =
        Settings(context).colorOrNull(key) ?: default(context, key)

    /** What [key] shows while it is unset. */
    fun default(context: Context, key: String): Int {
        val role = byKey[key] ?: return olderDefaults[key] ?: ACCENT
        role.inherits?.let { return color(context, it) }
        role.derived?.let { return it(context) }
        return role.base
    }

    /** The default of one of the older catalogue keys, which needs no context to know. */
    fun default(key: String): Int = olderDefaults[key] ?: ACCENT

    fun set(context: Context, key: String, value: Int?) = Settings(context).setColor(key, value)

    // ── The getters other code is meant to call ─────────────────────────────────────────────

    /** The “something is wrong” red. */
    fun warning(context: Context): Int = color(context, "warn.warning")

    /** The “it worked” colour; the connect green by default. */
    fun success(context: Context): Int = color(context, "warn.success")

    /** The red a deleting, forgetting or resetting action lends to its frame and label. */
    fun delete(context: Context): Int = color(context, "warn.delete")

    /** The colour of an icon whose row does [role]. */
    fun icon(context: Context, role: IconRole): Int = color(context, role.key)

    fun dimGold(context: Context): Int = color(context, "page.note.color")

    // ── Reset ───────────────────────────────────────────────────────────────────────────────

    /** Every colour key the app stores: this table and the catalogue's colour fields. */
    fun allColorKeys(): List<String> = all.map { it.key } + UiCatalog.colorKeys()

    /** “Reset all colours”: texts, fonts, sizes, borders and the page's arrangement are kept. */
    fun resetAll(context: Context) = Settings(context).remove(allColorKeys())

    // ── Deriving ────────────────────────────────────────────────────────────────────────────

    /** The opaque colour [t] of the way from [from] to [to]. */
    fun blend(from: Int, to: Int, t: Float): Int {
        fun mix(a: Int, b: Int) = (a + (b - a) * t).toInt().coerceIn(0, 255)
        return Color.argb(
            255,
            mix(Color.red(from), Color.red(to)),
            mix(Color.green(from), Color.green(to)),
            mix(Color.blue(from), Color.blue(to)),
        )
    }

    /** [color] at [a] of its own opacity. */
    fun alpha(color: Int, a: Float): Int =
        (color and 0x00FFFFFF) or ((Color.alpha(color) * a).toInt() shl 24)

    // ── For the shims in Defaults (Appearance.kt), which have no Context to hand ────────────
    @Volatile
    private var appContext: Context? = null

    internal fun attach(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    internal fun warningOrDefault(): Int = appContext?.let { warning(it) } ?: WARNING
}
