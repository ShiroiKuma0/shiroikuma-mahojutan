package dev.spiegl.flyingcarpet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton
import java.io.File

// The "白い熊 魔法絨毯 UI" customization page. Built in code: the Export / Import and Paired
// devices sections, one section per area of UiCatalog, one per family of colour roles (Roles.kt),
// and Maintenance. Each text surface carries a live preview that reflects its text, colour, font,
// style, and size as they are edited.
//
// The page is the black-yellow theme's UI page (白い熊, 2026-11-20):
//
//   - under the title, a wrapping index of small chips, one for every top-level section — a tap
//     unfolds the section and scrolls to it, a long press drags the chip and the sections follow;
//   - every section folds, › when folded and ⌄ when open, and every one starts folded; the rows
//     of a folded section are not built until it is first opened, which keeps the page quick;
//   - the order of the sections and what is unfolded are remembered (Settings: "page.*" keys)
//     and travel in the export; "Reset section order" is in Maintenance;
//   - every row carries an icon coloured by what the row does, every row that does something
//     when pressed is a pill as wide as its label, and rows are only as tall as their content.
//
// The look of the rows is the shiroikuma-kxkb settings page (白い熊, 2026-07-25): every heading
// carries a **text-width** underline, top-level sections are separated by a full-width 1 px line,
// and the indentation ladder is kxkb's — section 36 dp → element 54 dp → control 72 dp.
//
// No colour comes from the theme: every TextView, field, slider, switch and pill here is painted
// from a role, and the page's own roles are set in its "This settings page" section.
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: Settings
    private lateinit var fontPicker: ActivityResultLauncher<Array<String>>
    private var previewBaseColor: Int = Roles.ACCENT

    // The Export/Import panel while it is open, so onResume can re-render it (it may have sent
    // 白い熊 to the All-Files-Access screen, and the grant prompt has to clear on the way back).
    private var exportPanel: ExportImportPanel? = null

    // The surface key whose font row launched the file picker, so the new font lands there.
    private var pendingFontKey: String? = null

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()

    // kxkb's indentation ladder: section headings sit at 36 dp, element headings at 54 dp, and the
    // controls (and every settings row) at 72 dp — so the section → element → control hierarchy
    // reads at a glance without the page marching off the right edge.
    private val sectionIndent get() = dp(36)
    private val elementIndent get() = dp(54)
    private val indent get() = dp(72)

    private val sampleText = "Aa Gg 0123 — 白い熊 魔法絨毯"

    // ── Sections, folding, index ─────────────────────────────────────────────────────────────

    /** One top-level section: a stable key (what the stored order and fold state name), and its rows. */
    private class PageSection(val key: String, val title: String, val build: (LinearLayout) -> Unit)

    private lateinit var scroll: ScrollView
    private lateinit var indexHolder: LinearLayout
    private lateinit var sectionHolder: LinearLayout
    private val sectionWrappers = HashMap<String, View>()
    private val sectionBodies = HashMap<String, LinearLayout>()
    private val sectionChevrons = HashMap<String, TextView>()
    private val builtSections = HashSet<String>()

    // Stable keys for the catalogue's sections. A title is what 白い熊 reads and may be reworded;
    // the key is what the stored order names and must not move.
    private val catalogKeys = mapOf(
        "Main page" to "main",
        "Title bar" to "titleBar",
        "Step instructions" to "steps",
        "Peer OS buttons" to "peerOs",
        "Cancel button" to "cancel",
        "Transfer output" to "output",
        "Window" to "window",
        "About page" to "about",
        "This settings page" to "page",
    )

    private fun catalogKey(title: String) =
        catalogKeys[title] ?: title.lowercase().filter { it.isLetterOrDigit() }

    /** Every section in its built order. The stored order is laid over this (SectionIndex.ordered). */
    private fun sections(): List<PageSection> {
        val list = mutableListOf<PageSection>()
        // Backup first, as on the Kōjiki UI page; then this device among its devices.
        list += PageSection("export", "Export / Import") { addExportSection(it) }
        list += PageSection("paired", "Paired devices") { addPairedSection(it) }
        // One block per logical area of the main screen (UiCatalog.sections). The page's own
        // section comes after the role sections, so it is taken out here and added below.
        val (pageStyle, areas) = UiCatalog.sections.partition { it.pageStyle }
        for (area in areas) {
            list += PageSection(catalogKey(area.title), area.title) { addCatalogSection(it, area) }
        }
        // What the catalogue never covered: one section per family of colour roles.
        list += PageSection("dialogs", Roles.S_DIALOGS) {
            addRoleSection(
                it, Roles.S_DIALOGS,
                "Every dialog and panel: its ground, frame and texts, its buttons, and the text fields, sliders and tick boxes inside it.",
            )
        }
        list += PageSection("menus", Roles.S_MENUS) {
            addRoleSection(
                it, Roles.S_MENUS,
                "The menu a held device opens, and every other list of actions. The icons take their colours from “Icons”, the switches from “Main page”.",
            )
        }
        list += PageSection("sheets", Roles.S_SHEETS) {
            addRoleSection(
                it, Roles.S_SHEETS,
                "The Devices sheet, the sheet a share opens, the device pills and the pairing code.",
            )
        }
        list += PageSection("lanes", Roles.S_LANES) {
            addRoleSection(it, Roles.S_LANES, "The block each running paired transfer gets on the main screen.")
        }
        list += PageSection("icons", Roles.S_ICONS) {
            addRoleSection(
                it, Roles.S_ICONS,
                "An icon is coloured by what its row does, the same way in every menu, sheet and page.",
            )
        }
        list += PageSection("warnings", Roles.S_WARNINGS) {
            addRoleSection(it, Roles.S_WARNINGS, "The colours that carry a meaning of their own.")
        }
        for (area in pageStyle) {
            list += PageSection(catalogKey(area.title), area.title) { addCatalogSection(it, area) }
        }
        list += PageSection("maintenance", "Maintenance") { addMaintenanceSection(it) }
        return list
    }

    private fun orderedSections(): List<PageSection> {
        val built = sections()
        val byKey = built.associateBy { it.key }
        return SectionIndex.ordered(settings.sectionOrder(), built.map { it.key }).mapNotNull { byKey[it] }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        // The page is yellow-on-black by default, so previews start from the page's label colour.
        previewBaseColor = Roles.color(this, "page.label.color")
        fontPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onFontPicked(uri) else pendingFontKey = null
        }

        // What the theme paints before this runs is literal black; from here the roles decide.
        val ground = Roles.color(this, "page.bg")
        window.setBackgroundDrawable(ColorDrawable(ground))
        window.statusBarColor = Roles.color(this, "page.statusBar")
        window.navigationBarColor = Roles.color(this, "page.navBar")

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        // Every text field, slider and switch added anywhere below is dressed from the page's
        // roles as it joins the tree, whichever builder made it.
        Theme.watch(content, Theme.Surface.PAGE)

        // ── Header ── (the page styles itself from the "page.*" keys; see applyKind / "This settings page")
        // The title, and beside it the one way out. The app has no settings screen of its own,
        // so there is no settings-link pill under the title — the chip index comes right after.
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@SettingsActivity).apply {
                text = getString(R.string.uiSettingsTitle)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                setTypeface(typeface, Typeface.BOLD)
                applyKind(this, "page.title")
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(pageButton("Done", main = true, icon = Icons.Icon.CHECK) { finish() })
        })
        content.addView(TextView(this).apply {
            val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
            text = "Build $ver"
            setPadding(0, dp(2), 0, 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            applyKind(this, "page.note")
        })

        // The chip index, right under the title; what the page asks of 白い熊 comes after it.
        indexHolder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        content.addView(indexHolder)
        content.addView(TextView(this).apply {
            text = "Tap a chip to open a section, hold one to move it. Changes apply when you return to the main screen. Leave a field blank, or tap “Default” in a colour picker, to restore the default."
            setPadding(0, dp(2), 0, dp(4))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            applyKind(this, "page.note")
        })
        sectionHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(sectionHolder)
        buildIndex()
        buildSections()

        scroll = ScrollView(this).apply {
            addView(content)
            setBackgroundColor(ground)
        }
        setContentView(scroll)
        // A repaint rebuilds the page; put 白い熊 back where the change was made.
        val y = restoreScrollY
        restoreScrollY = 0
        if (y > 0) scroll.post { scroll.scrollTo(0, y) }
    }

    /** Rebuilds the page so that a changed setting shows, keeping the place it was changed at. */
    private fun repaint() {
        restoreScrollY = scroll.scrollY
        recreate()
    }

    /** The chip index: one small outlined chip per section; a tap jumps, a long press drags. */
    private fun buildIndex() {
        indexHolder.removeAllViews()
        indexHolder.addView(
            SectionIndex.build(
                this,
                orderedSections().map { SectionIndex.Item(it.key, it.title) },
                frame = Roles.color(this, "page.chip.frame"),
                ink = Roles.color(this, "page.chip.text"),
                onJump = { jumpToSection(it) },
                onReordered = {
                    settings.setSectionOrder(it)
                    buildSections()
                },
            ),
        )
    }

    private fun buildSections() {
        sectionHolder.removeAllViews()
        sectionWrappers.clear()
        sectionBodies.clear()
        sectionChevrons.clear()
        builtSections.clear()
        exportSummaryView = null
        orderedSections().forEach { addSection(it) }
    }

    /**
     * A folding section: the 1 px line that parts it from the one above — kept whether it is
     * folded or open — then the settled heading with a › (folded) or ⌄ (open) at the row's
     * right, and its rows in a box that folds away. Folded unless the stored state says
     * otherwise; the rows are not built until the section is first opened.
     */
    private fun addSection(section: PageSection) {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = fullWidth()
            setPadding(0, dp(10), 0, dp(2))
        }
        wrapper.addView(
            View(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(Roles.color(this@SettingsActivity, "page.divider"))
            },
        )
        val chevron = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Roles.color(this@SettingsActivity, "page.chevron"))
            setPadding(dp(12), 0, dp(8), 0)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = fullWidth()
            background = Theme.rowRipple(Roles.color(this@SettingsActivity, "page.section.color"))
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleSection(section) }
            addView(
                underlinedHeading(
                    section.title, sizeSp = 20f, indentPx = sectionIndent, rulePx = dp(3),
                    kind = "page.section", ruleRole = "page.section.rule",
                ),
            )
            addView(View(this@SettingsActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(chevron)
        }
        wrapper.addView(header)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = fullWidth()
            setPadding(0, 0, 0, dp(6))
        }
        wrapper.addView(body)
        sectionHolder.addView(wrapper)
        sectionWrappers[section.key] = wrapper
        sectionBodies[section.key] = body
        sectionChevrons[section.key] = chevron
        showSection(section, section.key in settings.openSections())
    }

    private fun toggleSection(section: PageSection) {
        val open = section.key !in settings.openSections()
        settings.setSectionOpen(section.key, open)
        showSection(section, open)
    }

    private fun showSection(section: PageSection, open: Boolean) {
        val body = sectionBodies[section.key] ?: return
        if (open && builtSections.add(section.key)) section.build(body)
        body.visibility = if (open) View.VISIBLE else View.GONE
        sectionChevrons[section.key]?.text = if (open) "⌄" else "›"
    }

    /** A tap on an index chip: unfold the section and scroll to it. */
    private fun jumpToSection(key: String) {
        val section = sections().firstOrNull { it.key == key } ?: return
        val wrapper = sectionWrappers[key] ?: return
        settings.setSectionOpen(key, true)
        showSection(section, true)
        scroll.post { scroll.smoothScrollTo(0, sectionHolder.top + wrapper.top) }
    }

    // ── The catalogue's sections ─────────────────────────────────────────────────────────────

    private fun addCatalogSection(parent: LinearLayout, section: Section) {
        section.note?.let { parent.addView(sectionNote(it)) }

        // The self-styling section's changes affect this very page, so offer a repaint button.
        if (section.pageStyle) {
            parent.addView(
                LinearLayout(this).apply {
                    setPadding(elementIndent, dp(3), 0, dp(3))
                    addView(pageButton("Apply to this page", icon = Icons.Icon.CHECK) { repaint() })
                },
            )
        }

        for (surface in section.surfaces) {
            parent.addView(elementHeader(surface.title))
            val box = sectionBox()
            val preview = makePreview()
            val update = { stylePreview(preview, surface) }
            for (field in surface.labels) addTextField(box, field, update)
            if (surface.hasTextColor) {
                addColorControl(box, "${surface.key}.color", surface.colorLabel, null, update)
            }
            addFontControls(box, surface.key, update)
            addNumberField(box, "${surface.key}.size", "Size (sp)", update)
            // Folded-in colours / borders / radii for this element (fills, strokes, sliders).
            for (color in surface.extraColors) addColorControl(box, color.key, color.label, color.default, null)
            for (dim in surface.extraDims) addSliderField(box, dim.key, dim.label, dim.max, dim.default, null)
            box.addView(propertyLabel("Preview", Icons.Icon.EYE))
            box.addView(preview)
            addResetButton(box, surfaceKeys(surface))
            update()
            parent.addView(box)
        }

        for (group in section.colorGroups) {
            parent.addView(elementHeader(group.title))
            val box = sectionBox()
            for (color in group.colors) addColorControl(box, color.key, color.label, color.default, null)
            for (dim in group.dims) addSliderField(box, dim.key, dim.label, dim.max, dim.default, null)
            addResetButton(box, group.colors.map { it.key } + group.dims.map { it.key })
            parent.addView(box)
        }

        // The roles of this area that the catalogue has no field for (the page's own chips,
        // rules, fields and sliders), in the section their items belong to.
        val roles = Roles.inSection(section.title)
        if (roles.isNotEmpty()) {
            parent.addView(elementHeader("Other parts"))
            val box = sectionBox()
            for (role in roles) addRoleRow(box, role)
            addResetButton(box, roles.map { it.key })
            parent.addView(box)
        }
    }

    // ── The role sections ────────────────────────────────────────────────────────────────────

    /** A section of colour roles: a swatch row with an icon for each, and one reset for them all. */
    private fun addRoleSection(parent: LinearLayout, sectionTitle: String, note: String) {
        parent.addView(sectionNote(note))
        val box = sectionBox()
        val roles = Roles.inSection(sectionTitle)
        for (role in roles) addRoleRow(box, role)
        addResetButton(box, roles.map { it.key }, "Reset these colours")
        parent.addView(box)
    }

    private fun addRoleRow(parent: LinearLayout, role: Role) =
        addColorControl(parent, role.key, role.label, null, null, role.icon)

    // ── Maintenance ──────────────────────────────────────────────────────────────────────────

    private fun addMaintenanceSection(parent: LinearLayout) {
        parent.addView(sectionNote("Putting things back."))
        parent.addView(
            actionRow(
                "Reset section order",
                rowSummary("Puts the sections of this page back in their original order. What is unfolded stays unfolded.", null),
                opens = false, icon = Icons.Icon.RESET, role = IconRole.VIEW,
            ) {
                settings.resetSectionOrder()
                buildIndex()
                buildSections()
            },
        )
        parent.addView(
            actionRow(
                "Reset all colours",
                rowSummary("Every colour on every page goes back to its default. Texts, fonts, sizes, borders and the order of this page are kept.", null),
                opens = false, icon = Icons.Icon.PALETTE, destructive = true,
            ) { confirmResetColours() },
        )
        parent.addView(
            actionRow(
                "Reset all to defaults",
                rowSummary("Clears everything set on this page — colours, texts, fonts, sizes, borders, added fonts and the arrangement of the page.", null),
                opens = false, icon = Icons.Icon.DELETE, destructive = true,
            ) { confirmResetAll() },
        )
    }

    // ── Builders ──

    /** Middle level: one customizable element within a section. Indented one step, thinner rule. */
    private fun elementHeader(title: String) =
        underlinedHeading(
            title, sizeSp = 17f, indentPx = elementIndent, rulePx = dp(2),
            kind = "page.element", ruleRole = "page.element.rule",
        ).apply { setPadding(0, dp(12), 0, dp(2)) }

    /**
     * A heading whose underline is **text-wide, never full-width** (白い熊, 2026-07-25): the label and
     * the rule share a wrap_content column, so the rule stops where the words do.
     */
    private fun underlinedHeading(title: String, sizeSp: Float, indentPx: Int, rulePx: Int, kind: String, ruleRole: String) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = indentPx }
            setPadding(0, dp(8), 0, 0)
            addView(
                TextView(this@SettingsActivity).apply {
                    text = title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
                    setTypeface(typeface, Typeface.BOLD)
                    applyKind(this, kind)
                },
            )
            addView(
                View(this@SettingsActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rulePx)
                        .apply { topMargin = dp(2) }
                    setBackgroundColor(Roles.color(this@SettingsActivity, ruleRole))
                },
            )
        }

    // The one-line explanation shown under some section titles.
    // Dim gold, with no rule: it belongs to the section, not to any one row.
    private fun sectionNote(text: String) = TextView(this).apply {
        this.text = text
        setPadding(elementIndent, dp(4), dp(16), dp(2))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        applyKind(this, "page.note")
    }

    // Explanations: the theme's dim gold, which 白い熊 can change as "Notes, hints and
    // explanations" under "This settings page". The rule beside a described row has a role of
    // its own that follows it until set.
    private val dimGold: Int get() = Theme.dimGold(this)

    private fun dpPx(v: Float) = (v * resources.displayMetrics.density).toInt()

    // Every preference key a text surface owns, for its "Reset to default" button.
    private fun surfaceKeys(s: TextSurface): List<String> {
        val keys = s.labels.map { it.key }.toMutableList()
        if (s.hasTextColor) keys.add("${s.key}.color")
        keys.add("${s.key}.family"); keys.add("${s.key}.style"); keys.add("${s.key}.size")
        s.extraColors.forEach { keys.add(it.key) }
        s.extraDims.forEach { keys.add(it.key) }
        return keys
    }

    // A per-group reset: clears that group's keys and rebuilds the page. It throws settings
    // away, so the pill is red — frame, icon and label — and still unfilled.
    private fun addResetButton(parent: LinearLayout, keys: List<String>, label: String = "Reset to default") {
        parent.addView(
            LinearLayout(this).apply {
                setPadding(0, dp(6), 0, dp(3))
                addView(
                    pageButton(label, icon = Icons.Icon.RESET, destructive = true) {
                        settings.remove(keys)
                        repaint()
                    }.apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f) },
                )
            },
        )
    }

    // The page's own buttons (Done / Reset / Apply): the theme's pills (Theme.pill), as wide as
    // their label, Done the main one. Colours, font, radius and — when set — the frame come from
    // "page.button.*"; a destructive one takes the delete red instead. Each carries its icon.
    private fun pageButton(
        label: String,
        main: Boolean = false,
        icon: Icons.Icon? = null,
        destructive: Boolean = false,
        onClick: () -> Unit,
    ) = MaterialButton(this).apply {
        text = label
        setOnClickListener { onClick() }
        stylePageButton(this, main, destructive)
        if (icon != null) {
            val tint = if (destructive) Roles.delete(this@SettingsActivity)
            else Roles.icon(this@SettingsActivity, IconRole.VIEW)
            this.icon = Icons.drawable(this@SettingsActivity, icon, tint, 16f)
            iconTint = null
            iconSize = dp(16)
            iconPadding = dp(6)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun stylePageButton(b: MaterialButton, main: Boolean, destructive: Boolean) {
        val delete = Roles.delete(this)
        Theme.pill(
            b,
            accent = if (destructive) delete else settings.colorOrNull("page.button.stroke") ?: Roles.ACCENT,
            background = settings.colorOrNull("page.button.bg") ?: Roles.GROUND,
            main = main,
            width = settings.sizeOrNull("page.button.strokeWidth"),
        )
        b.setTextColor(
            Theme.enabledList(if (destructive) delete else settings.colorOrNull("page.button.color") ?: Roles.ACCENT),
        )
        b.minWidth = 0
        b.minimumWidth = 0
        b.minHeight = 0
        b.minimumHeight = 0
        b.insetTop = 0
        b.insetBottom = 0
        b.setPadding(dp(16), dp(6), dp(16), dp(6))
        val family = settings.family("page.button.family")
        val style = settings.style("page.button.style")
        if (family.isNotEmpty() || style >= 0) {
            val eff = if (style >= 0) style else (b.typeface?.style ?: Typeface.NORMAL)
            b.typeface = FontUtil.typeface(family, eff) ?: Typeface.create(b.typeface, eff)
        }
        settings.size("page.button.size").let { if (it > 0f) b.setTextSize(TypedValue.COMPLEX_UNIT_SP, it) }
        b.cornerRadius = dpPx(settings.sizeOrNull("page.button.cornerRadius") ?: Theme.PILL_RADIUS_DP)
    }

    // Applies the settings page's own "page.<kind>.*" styling (colour / font / style / size) to one of
    // its chrome TextViews, on top of the builder's default look. Lets this screen restyle itself.
    // The colour is always set — the theme supplies none — to the kind's own or its default.
    private fun applyKind(tv: TextView, kind: String) {
        tv.setTextColor(Roles.color(this, "$kind.color"))
        val family = settings.family("$kind.family")
        val style = settings.style("$kind.style")
        if (family.isNotEmpty() || style >= 0) {
            val eff = if (style >= 0) style else (tv.typeface?.style ?: Typeface.NORMAL)
            tv.typeface = FontUtil.typeface(family, eff) ?: Typeface.create(tv.typeface, eff)
        }
        settings.size("$kind.size").let { if (it > 0f) tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, it) }
    }

    // The indented container holding an element's controls (the ladder's bottom step, 72 dp).
    private fun sectionBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(indent, 0, 0, 0)
    }

    /** A control's label, with the row's icon before it. Minimal padding: 2 dp above and below. */
    private fun propertyLabel(
        text: String,
        icon: Icons.Icon,
        role: IconRole = IconRole.VIEW,
    ) = TextView(this).apply {
        this.text = text
        setPadding(0, dp(2), 0, dp(2))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        applyKind(this, "page.label")
        Icons.lead(this, icon, role, sizeDp = 16f)
    }

    private fun fullWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun makePreview() = TextView(this).apply {
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setBackgroundColor(Roles.color(this@SettingsActivity, "page.preview.bg"))
        layoutParams = fullWidth().apply { topMargin = dp(2) }
    }

    // Render the preview with the surface's current text + colour + font + style + size. Surfaces with
    // no editable labels (the "This settings page" text kinds) just preview the sample string.
    private fun stylePreview(preview: TextView, surface: TextSurface) {
        val key = surface.key
        val labelKey = surface.labels.firstOrNull()?.key
        preview.text = (labelKey?.let { settings.text(it) } ?: "").ifEmpty { sampleText }
        preview.setTextColor(settings.colorOrNull("$key.color") ?: previewBaseColor)
        val size = settings.size("$key.size")
        preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (size > 0f) size else 18f)
        val style = settings.style("$key.style").let { if (it >= 0) it else Typeface.NORMAL }
        preview.typeface = FontUtil.typeface(settings.family("$key.family"), style)
            ?: Typeface.create(Typeface.DEFAULT, style)
    }

    /** A text field of the page: no minimum height, 2 dp above its text, coloured from the page's field roles. */
    private fun field() = EditText(this).apply {
        layoutParams = fullWidth()
        hint = "Default"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        minHeight = 0
        minimumHeight = 0
        setPadding(paddingLeft, dp(2), paddingRight, dp(6))
        Theme.styleField(this, Theme.Surface.PAGE)
    }

    private fun addTextField(parent: LinearLayout, field: LabelField, onChanged: (() -> Unit)?) {
        parent.addView(propertyLabel(field.label, Icons.Icon.EDIT, IconRole.MESSAGE))
        parent.addView(field().apply {
            setText(settings.text(field.key))
            inputType = InputType.TYPE_CLASS_TEXT
            addTextChangedListener(simpleWatcher {
                settings.setText(field.key, it)
                onChanged?.invoke()
            })
        })
    }

    private fun addNumberField(parent: LinearLayout, key: String, label: String, onChanged: (() -> Unit)?) {
        parent.addView(propertyLabel(label, Icons.Icon.SIZE))
        parent.addView(field().apply {
            val v = settings.size(key)
            if (v > 0f) setText(if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString())
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            addTextChangedListener(simpleWatcher {
                settings.setSize(key, it.toFloatOrNull() ?: 0f)
                onChanged?.invoke()
            })
        })
    }

    /**
     * A plain control row — the value on the right is the affordance: icon and label, then the
     * value, and the theme's picker when the row is tapped. Replaces the stock dropdowns, whose
     * popups could not be given the theme's colours.
     */
    private fun valueRow(
        label: String,
        icon: Icons.Icon,
        value: () -> String,
        typeface: (() -> Typeface?)? = null,
        onClick: (refresh: () -> Unit) -> Unit,
    ): View {
        val valueText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Roles.color(this@SettingsActivity, "page.value"))
            gravity = Gravity.END
        }
        val refresh = {
            valueText.text = value()
            valueText.typeface = typeface?.invoke() ?: Typeface.DEFAULT
        }
        refresh()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = fullWidth()
            setPadding(0, dp(2), dp(4), dp(2))
            background = Theme.rowRipple(Roles.color(this@SettingsActivity, "page.label.color"))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick(refresh) }
            addView(
                propertyLabel(label, icon).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            addView(valueText)
        }
    }

    private fun addFontControls(parent: LinearLayout, key: String, onChanged: (() -> Unit)?) {
        // System families + any external fonts already added; each is shown in its own glyphs.
        parent.addView(
            valueRow(
                "Font", Icons.Icon.TEXT,
                value = {
                    val values = settings.familyValues()
                    settings.familyLabels().getOrNull(values.indexOf(settings.family("$key.family")))
                        ?: Settings.FONT_LABELS.first()
                },
                typeface = { FontUtil.previewTypeface(settings.family("$key.family")) },
            ) { refresh -> pickFont(key) { refresh(); onChanged?.invoke() } },
        )
        parent.addView(
            valueRow(
                "Style", Icons.Icon.TEXT,
                value = {
                    Settings.STYLE_LABELS[Settings.STYLE_VALUES.indexOf(settings.style("$key.style")).coerceAtLeast(0)]
                },
            ) { refresh ->
                ForkDialog.picker(
                    this, "Style", Settings.STYLE_LABELS,
                    selected = Settings.STYLE_VALUES.indexOf(settings.style("$key.style")).coerceAtLeast(0),
                    typefaces = Settings.STYLE_VALUES.map { Typeface.create(Typeface.DEFAULT, it.coerceAtLeast(0)) },
                ) { which ->
                    settings.setStyle("$key.style", Settings.STYLE_VALUES[which])
                    refresh()
                    onChanged?.invoke()
                }
            },
        )
    }

    /**
     * The font picker: the families as a list of choices, each drawn in its own face, and at
     * its foot the one action — adding a font file — which then becomes a choice everywhere.
     */
    private fun pickFont(key: String, onPicked: () -> Unit) {
        val values = settings.familyValues()
        val labels = settings.familyLabels() + "＋ Add external font…"
        ForkDialog.picker(
            this, "Font", labels,
            selected = values.indexOf(settings.family("$key.family")).coerceAtLeast(0),
            typefaces = values.map { FontUtil.previewTypeface(it) } + listOf<Typeface?>(null),
        ) { which ->
            if (which >= values.size) {
                pendingFontKey = key               // the new font should land on this surface
                fontPicker.launch(arrayOf("*/*"))
            } else {
                settings.setFamily("$key.family", values[which])
                onPicked()
            }
        }
    }

    /** The icon a colour row gets when it has none of its own: what kind of thing is coloured. */
    private fun colorIcon(label: String): Icons.Icon = when {
        label.contains("border", ignoreCase = true) || label.contains("frame", ignoreCase = true) -> Icons.Icon.FRAME
        label.contains("text", ignoreCase = true) || label.contains("placeholder", ignoreCase = true) -> Icons.Icon.TEXT
        label == "On" || label == "Off" -> Icons.Icon.TOGGLE
        else -> Icons.Icon.PALETTE
    }

    /**
     * A colour as one plain row: icon and label, the value, and its swatch — the swatch is the
     * affordance. A tap opens the picker, whose “Default” puts this one colour back. [default] is
     * what the catalogue names for an unset field; a role knows its own.
     */
    private fun addColorControl(
        parent: LinearLayout,
        key: String,
        label: String,
        default: Int?,
        onChanged: (() -> Unit)?,
        icon: Icons.Icon = colorIcon(label),
    ) {
        fun fallback(): Int =
            if (Roles.find(key) != null || default == null) Roles.default(this, key) else default

        val swatchFrame = Roles.color(this, "page.swatch.frame")
        val swatch = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(24)).apply { marginStart = dp(10) }
        }
        val valueText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Roles.color(this@SettingsActivity, "page.value"))
            typeface = Typeface.MONOSPACE
        }

        fun refresh() {
            val c = settings.colorOrNull(key)
            val shown = c ?: fallback()
            // An outline round the swatch, so a ground-coloured one can still be seen.
            swatch.background = GradientDrawable().apply {
                setColor(shown)
                setStroke(dp(1).coerceAtLeast(1), swatchFrame)
                cornerRadius = dp(4).toFloat()
            }
            valueText.text = if (c == null) "Default" else String.format("#%08X", c)
        }
        refresh()

        parent.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = fullWidth()
            setPadding(0, dp(2), dp(4), dp(2))
            background = Theme.rowRipple(Roles.color(this@SettingsActivity, "page.label.color"))
            isClickable = true
            isFocusable = true
            addView(
                propertyLabel(label, icon).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            addView(valueText)
            addView(swatch)
            setOnClickListener {
                showColorPicker(this@SettingsActivity, settings.colorOrNull(key) ?: fallback()) { result ->
                    settings.setColor(key, result); refresh(); onChanged?.invoke()
                }
            }
        })
    }

    // A 0..max dp slider for border widths and corner radii. The track starts at 0, but the initial
    // position reflects the field's default (e.g. 1 dp border, 10 dp radius) until 白い熊 drags it.
    // Stored via setDim, so dragging to 0 stores an explicit 0 (no border) rather than reverting.
    private fun addSliderField(parent: LinearLayout, key: String, label: String, max: Int, default: Float, onChanged: (() -> Unit)?) {
        val current = (settings.sizeOrNull(key) ?: default).toInt().coerceIn(0, max)
        val valueLabel = propertyLabel("$label: $current dp", Icons.Icon.SLIDERS)
        parent.addView(valueLabel)
        parent.addView(SeekBar(this).apply {
            layoutParams = fullWidth()
            // Only as tall as its thumb: no minimum height, 2 dp above and below.
            minimumHeight = 0
            setPadding(dp(10), dp(2), dp(14), dp(2))
            Theme.styleSeekBar(this, Theme.Surface.PAGE)
            this.max = max
            progress = current
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    settings.setDim(key, value.toFloat())
                    valueLabel.text = "$label: $value dp"
                    onChanged?.invoke()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        })
    }

    private fun simpleWatcher(onText: (String) -> Unit) = object : TextWatcher {
        override fun afterTextChanged(e: Editable?) = onText(e?.toString() ?: "")
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    }

    // ── Custom font picking ──

    private fun onFontPicked(uri: Uri) {
        try {
            val dir = File(filesDir, "fonts").apply { mkdirs() }
            val dest = File(dir, sanitizeName(queryDisplayName(uri)))
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            } ?: run { toast("Could not open the selected file."); return }
            Typeface.createFromFile(dest) // validate it really is a font
            settings.addFontFile(dest.absolutePath)
            // Select the just-added font on the surface whose font row launched the picker.
            pendingFontKey?.let { settings.setFamily("$it.family", "file:${dest.absolutePath}") }
            pendingFontKey = null
            repaint()
        } catch (e: Exception) {
            pendingFontKey = null
            toast("That file isn’t a usable font.")
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.let { name -> return name }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "font.ttf"
    }

    private fun sanitizeName(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim()
        return cleaned.ifEmpty { "font.ttf" }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ── Export / Import section ──────────────────────────────────────────────────────────────

    // The directory line under "Export / Import", kept so it can be refreshed in place after the
    // panel closes or after a trip to the All-Files-Access screen. Null while the section is folded.
    private var exportSummaryView: TextView? = null

    override fun onResume() {
        super.onResume()
        refreshExportSummary()
        exportPanel?.refresh()
    }

    private fun refreshExportSummary() {
        val view = exportSummaryView ?: return
        val (summary, warn) = ExportImportStatus.summary(this)
        view.text = summary
        view.setTextColor(ExportImportStatus.color(this, warn))
    }

    /**
     * The Export/Import section's rows. The automation controls sit **inside** this section,
     * directly below the panel entry — the sister-app convention: this is a backup feature, so
     * 白い熊 finds it where backup lives, and every app looks the same.
     *
     * Four rows in contract order: the panel, the master switch (default ON),
     * 「Use authorization token?」 (default OFF), and the token itself — which is shown only while
     * the switch above it is on.
     */
    private fun addExportSection(parent: LinearLayout) {
        parent.addView(sectionNote("Carry everything you set in this app to another device, or put it back."))
        val (summary, warn) = ExportImportStatus.summary(this)
        val summaryView = rowSummary(summary, ExportImportStatus.color(this, warn))
        exportSummaryView = summaryView
        parent.addView(
            actionRow(
                "Export / Import", summaryView, main = true,
                icon = Icons.Icon.EXPORT, role = IconRole.SHARE,
            ) {
                val panel = ExportImportPanel(this) { finish() }
                exportPanel = panel
                panel.show(onDismiss = { exportPanel = null; refreshExportSummary() })
            },
        )

        // Master switch — default ON since v2 of the contract. It stays a switch rather than being
        // removed because it is the only way to close this app off, and a feature that can be
        // turned on but never off is one 白い熊 cannot retreat from.
        val switch = SwitchCompat(this).apply {
            Theme.styleSwitch(this)
            isChecked = AutomationAuth.enabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked -> AutomationAuth.setEnabled(this@SettingsActivity, checked) }
        }
        parent.addView(
            settingRow(
                "Automation export",
                rowSummary("Let sister apps trigger this app’s export, and let 白い熊 応用管理 back its data up and put it back.", null),
                widget = switch, icon = Icons.Icon.LINK, role = IconRole.SHARE,
            ) { switch.toggle() },
        )

        // Token row — tap copies the WHOLE token, "Regenerate" replaces it. Built before the switch
        // that governs it so the switch's listener has a row to show and hide.
        val tokenSummary = rowSummary(AutomationAuth.abbreviate(AutomationAuth.token(this)), null)
        val regenerate = ForkDialog.pill(this, "Regenerate") {
            tokenSummary.text = AutomationAuth.abbreviate(AutomationAuth.regenerateToken(this@SettingsActivity))
            toast("New token — update anywhere you pasted the old one.")
        }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        val tokenRow = settingRow(
            "Automation token", tokenSummary, widget = regenerate,
            icon = Icons.Icon.KEY, role = IconRole.SHARE,
        ) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(
                ClipData.newPlainText("mahojutan automation token", AutomationAuth.token(this)),
            )
            toast("Token copied to the clipboard.")
        }

        // 「Use authorization token?」 — default OFF (v2). A pasted secret cannot survive a wipe, and
        // the case this contract now serves is 応用管理 restoring apps onto a clean phone where
        // nothing has been configured and nobody has pasted anything.
        val requireToken = SwitchCompat(this).apply {
            Theme.styleSwitch(this)
            isChecked = AutomationAuth.requireToken(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                AutomationAuth.setRequireToken(this@SettingsActivity, checked)
                // Hidden when the token is not being asked for: a 48-character secret sitting under
                // an off switch invites 白い熊 to paste it somewhere it will do nothing.
                tokenRow.visibility = if (checked) View.VISIBLE else View.GONE
            }
        }
        parent.addView(
            settingRow(
                "Use authorization token?",
                rowSummary(
                    "Off: any sister app may drive this app’s automation. On: a caller must also present the token below. " +
                        "Either way the data door checks the caller’s package, uid and signing certificate.",
                    null,
                ),
                widget = requireToken, icon = Icons.Icon.LOCK, role = IconRole.BLOCK,
            ) { requireToken.toggle() },
        )

        tokenRow.visibility = if (AutomationAuth.requireToken(this)) View.VISIBLE else View.GONE
        parent.addView(tokenRow)
    }

    // ── Paired devices ─────────────────────────────────────────────────────────────────────

    private val pairing by lazy { Pairing(applicationContext) }

    private fun addPairedSection(container: LinearLayout) {
        container.addView(
            sectionNote("Send to your other devices in one tap, with nothing to do on the device receiving."),
        )
        container.addView(
            settingRow(
                "This device’s name",
                rowSummary(pairing.name, null),
                icon = Icons.Icon.DEVICE, role = IconRole.CONTACT,
                onClick = { renameDevice() },
            )
        )

        val warning = Roles.warning(this)
        val reachable = SwitchCompat(this).apply {
            Theme.styleSwitch(this)
            // The setting alone, as on the main page: the service follows it (MainActivity
            // restarts a killed one on resume), so "on but not running" is a moment, not a
            // state worth a different switch position.
            isChecked = pairing.stayReachable
            setOnCheckedChangeListener { _, checked ->
                if (checked) requestReachable() else stopReachable()
            }
        }
        container.addView(
            settingRow(
                "Stay reachable",
                // Red, like every other "this is not configured the way you think" line on
                // this page. The cost belongs on the switch, not in a document.
                rowSummary(
                    if (pairing.stayReachable) {
                        "On — a notification stays up and the Wi-Fi radio is kept listening. " +
                            "This phone freezes background apps, so also allow the app in " +
                            "Battery settings and in EMUI’s protected-app list, or it will " +
                            "still be killed."
                    } else {
                        "Off — paired devices can reach this one only while the app is open. " +
                            "Turning this on costs battery: it holds the Wi-Fi radio " +
                            "listening and keeps a notification up."
                    },
                    if (pairing.stayReachable) warning else null,
                ),
                widget = reachable, icon = Icons.Icon.WIFI, role = IconRole.CONNECT,
            )
        )

        val wake = SwitchCompat(this).apply {
            Theme.styleSwitch(this)
            isChecked = pairing.bleWake
            isEnabled = pairing.stayReachable
            setOnCheckedChangeListener { _, checked ->
                pairing.bleWake = checked
                // Taking effect means restarting the service, which is where the scan is
                // registered — a switch that only works after a reboot is not a switch.
                if (pairing.stayReachable) {
                    PresenceService.stop(this@SettingsActivity)
                    PresenceService.start(this@SettingsActivity)
                }
                repaint()
            }
        }
        container.addView(
            settingRow(
                "Wake over Bluetooth",
                rowSummary(
                    when {
                        !pairing.stayReachable ->
                            "Needs “Stay reachable” first — there is nothing to wake otherwise."
                        pairing.bleWake ->
                            "On — a paired device can raise a hotspot with this one without " +
                                "anybody touching it. Costs battery on top of the above, and " +
                                "is the least reliable part of this feature on these phones."
                        else ->
                            "Off — a hotspot transfer needs one tap on this device. Turning " +
                                "this on lets a paired device start one on its own."
                    },
                    if (pairing.stayReachable && pairing.bleWake) warning else null,
                ),
                widget = wake, icon = Icons.Icon.BLUETOOTH, role = IconRole.CONNECT,
            )
        )

        container.addView(
            actionRow(
                "Battery settings for this app",
                rowSummary(
                    "Android stops a background app from being reached. Exempt this one here.",
                    null,
                ),
                icon = Icons.Icon.BATTERY, role = IconRole.VIEW,
            ) { openBatterySettings() }
        )

        val peers = pairing.peers()
        container.addView(
            actionRow(
                "Devices",
                rowSummary(
                    when {
                        !pairing.isPaired -> "Not paired with anything yet."
                        peers.isEmpty() -> "Paired, but no devices found yet."
                        else -> peers.joinToString(", ") { it.displayName }
                    },
                    if (pairing.isPaired) null else warning,
                ),
                main = true, icon = Icons.Icon.DEVICE, role = IconRole.CONTACT,
            ) {
                // The list, the pairing codes and the per-device rules all live on one
                // sheet, reached from the main screen too: two places that both half-do
                // it would be worse than one that does all of it.
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .setAction(MainActivity.ACTION_OPEN_DEVICES)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
                finish()
            }
        )
    }

    private fun renameDevice() {
        // Coloured from the dialog's text-field roles when the dialog is built (Theme.watch).
        val field = EditText(this).apply {
            setText(pairing.name)
            setSingleLine()
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                ForkDialog.note(this@SettingsActivity, "What the other devices call this one.")
                    .apply { setPadding(0, dp(8), 0, dp(4)) },
            )
            addView(field)
        }
        ForkDialog.custom(
            this,
            "This device’s name",
            body,
            listOf(
                "Cancel" to { d: android.app.Dialog -> d.dismiss() },
                "Set" to { d: android.app.Dialog ->
                    d.dismiss()
                    pairing.name = field.text.toString()
                    repaint()
                },
            ),
        )
    }

    /**
     * Turning the switch on needs the notification permission first: a foreground service
     * whose notification cannot be shown is a service Android will not let run.
     */
    private fun requestReachable() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 77)
            return
        }
        if (!pairing.isPaired) {
            ForkDialog.alert(
                this,
                "Nothing to be reachable for",
                "Pair this device with another one first; there is no one to receive from yet.",
            )
            return
        }
        pairing.stayReachable = true
        PresenceService.start(this)
        repaint()
    }

    private fun stopReachable() {
        pairing.stayReachable = false
        PresenceService.stop(this)
        repaint()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 77) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            requestReachable()
        } else {
            // Said plainly rather than leaving a switch that silently refuses to stay on.
            ForkDialog.alert(
                this,
                "Notifications are required",
                "Android only lets an app stay reachable in the background while it shows " +
                    "a notification saying so. Without that permission this switch cannot " +
                    "be turned on.",
            )
            repaint()
        }
    }

    private fun openBatterySettings() {
        // The generic screen, not REQUEST_IGNORE_BATTERY_OPTIMIZATIONS: that one is a
        // policy-restricted intent, and EMUI's protected-app list is a separate thing again
        // that no intent reaches at all — which is why the summary above names it in words.
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.fromParts("package", packageName, null))
        try {
            startActivity(intent)
        } catch (e: Exception) {
            ForkDialog.alert(
                this,
                "Could not open settings",
                "Android would not open the settings screen: ${e.message}",
            )
        }
    }

    /**
     * kxkb's settings-row shape, as the theme's explained row: a bold label with the row's icon
     * before it, its dim-gold explanation beneath, the 2 dp rule down the left edge, and an
     * optional control at the right. Minimal padding: the row is as tall as its content.
     */
    private fun settingRow(
        title: String,
        summaryView: TextView,
        widget: View? = null,
        icon: Icons.Icon,
        role: IconRole = IconRole.VIEW,
        onClick: (() -> Unit)? = null,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // Minimal padding: the row is as tall as its content, never a 48 dp list item.
        setPadding(indent, dp(2), dp(16), dp(2))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        // The thin dim-gold rule down the item's left edge, ~10 dp before its text.
        addView(
            View(this@SettingsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(2), ViewGroup.LayoutParams.MATCH_PARENT)
                    .apply { marginEnd = dp(10) }
                setBackgroundColor(Roles.color(this@SettingsActivity, "page.rule"))
            },
        )
        onClick?.let {
            isClickable = true
            isFocusable = true
            background = Theme.rowRipple(Roles.color(this@SettingsActivity, "page.label.color"))
            setOnClickListener { _ -> it() }
        }
        addView(
            LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(8) }
                addView(
                    TextView(this@SettingsActivity).apply {
                        text = title
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        setTypeface(typeface, Typeface.BOLD)
                        applyKind(this, "page.label")
                        Icons.lead(this, icon, role)
                    },
                )
                addView(summaryView)
            },
        )
        widget?.let { addView(it) }
    }

    /**
     * A row that does something when pressed, drawn so that it says so (the black-yellow theme's
     * settings-page rule): an outlined pill as wide as its label, never filled and never stretched
     * across the line, with its icon inside and the explanation beneath it aligned with the
     * label. A 3 dp frame and bold for the main action of its section, 1.25 dp otherwise; › when
     * it opens something; the delete red on frame, icon and label when it is [destructive]. The
     * pill alone is the touch target — the explanation is not. Rows with their own control
     * (a switch, a value) stay [settingRow]s.
     */
    private fun actionRow(
        title: String,
        summaryView: TextView,
        main: Boolean = false,
        opens: Boolean = true,
        icon: Icons.Icon,
        role: IconRole = IconRole.VIEW,
        destructive: Boolean = false,
        onClick: () -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(indent, dp(3), dp(16), dp(3))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        // The same thin rule as an explained row, spanning the pill and its explanation.
        addView(
            View(this@SettingsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(2), ViewGroup.LayoutParams.MATCH_PARENT)
                    .apply { marginEnd = dp(10) }
                setBackgroundColor(Roles.color(this@SettingsActivity, "page.rule"))
            },
        )
        val column = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        addView(column)
        val delete = Roles.delete(this@SettingsActivity)
        val frame = if (destructive) delete else Roles.color(this@SettingsActivity, "page.pill.frame")
        val ink = if (destructive) delete else Roles.color(this@SettingsActivity, "page.pill.text")
        val stroke = dpPx(if (main) Theme.PILL_MAIN_DP else Theme.PILL_REGULAR_DP)
        val padH = dp(14)
        val pill = TextView(this@SettingsActivity).apply {
            text = if (opens) "$title  ›" else title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            applyKind(this, "page.label")
            setTextColor(ink)
            if (main) setTypeface(typeface, Typeface.BOLD)
            Icons.lead(this, icon, role, color = if (destructive) delete else null)
            setPadding(padH, dp(9), padH, dp(9))
            background = Theme.pillBackground(this@SettingsActivity, frame, Color.TRANSPARENT, main)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        column.addView(pill)
        // Under the label — past the pill's edge and its icon — not under the pill's edge.
        summaryView.setPadding(stroke + padH + dp(18 + 8), dp(3), 0, 0)
        column.addView(summaryView)
    }

    // Dim gold, never grey; a colour that means something (red: "fix this") overrides it.
    private fun rowSummary(text: String, color: Int?) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        applyKind(this, "page.note")
        color?.let { setTextColor(it) }
    }

    private fun confirmResetColours() {
        ForkDialog.info(
            this,
            "Reset all colours?",
            "Every colour on every page goes back to its default. Texts, fonts, sizes, borders and the order of this page are kept.",
            listOf(
                "Cancel" to { d: android.app.Dialog -> d.dismiss() },
                "Reset" to { d: android.app.Dialog ->
                    d.dismiss()
                    Roles.resetAll(this)
                    repaint()
                },
            ),
            cancelable = true,
            destructive = true,
        )
    }

    private fun confirmResetAll() {
        ForkDialog.info(
            this,
            "Reset all UI settings?",
            "This clears every colour, text, font, size, and border override and restores the yellow-on-black defaults. Added custom fonts and the arrangement of this page are cleared too.",
            listOf(
                "Cancel" to { d: android.app.Dialog -> d.dismiss() },
                "Reset" to { d: android.app.Dialog ->
                    d.dismiss()
                    settings.clearAll()
                    repaint()
                },
            ),
            cancelable = true,
            destructive = true,
        )
    }

    companion object {
        // Where the page was scrolled to when it last rebuilt itself; consumed by the next onCreate.
        private var restoreScrollY = 0
    }
}
