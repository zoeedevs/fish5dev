package com.fish5.clickgui

import com.mojang.blaze3d.platform.InputConstants
import com.fish5.Fish5.mc
import com.fish5.clickgui.settings.RenderableSetting
import com.fish5.clickgui.settings.impl.ColorSetting
import com.fish5.clickgui.settings.impl.KeybindSetting
import com.fish5.clickgui.settings.impl.ModuleToggleSetting
import com.fish5.features.Macro
import com.fish5.features.Module
import com.fish5.features.ModuleManager
import com.fish5.features.impl.render.ClickGUIModule
import com.fish5.hud.HudEditorScreen
import com.fish5.utils.ui.HoverHandler
import com.fish5.utils.ui.animations.EaseOutAnimation
import com.fish5.utils.ui.isAreaHovered
import com.fish5.utils.ui.rendering.GGRenderer
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import com.fish5.utils.ui.mouseX as guiMouseX
import com.fish5.utils.ui.mouseY as guiMouseY

/**
 * click GUI: a centered floating window (not full-screen) with an icon rail on the left
 * (feature groups, Edit HUD, and the player-head button that opens the Theme page), a tab strip across
 * the top, and a two-column masonry list of module rows / setting cards below. See [GuiLayout] for
 * which feature lives where.
 *
 * Tabs don't filter: every tab of the current group is one continuous scrolling page, and clicking a
 * tab smooth-scrolls to its section (the highlighted tab follows the scroll position).
 *
 * Module rows: the switch toggles the module, the key pill rebinds its toggle key (click, then press a
 * key; Esc/Backspace clears), clicking the card toggles it, and right-click / the dots open the module's settings page.
 */
object ClickGUI : Screen(Component.literal("Click GUI")) {

    /** True while this screen is the open one -- [ClickGUIModule.hoverScale] uses it to pick the GUI's own scale. */
    var isOpen = false
        private set

    // Legacy names still read by the setting impls / old Panel code.
    val gray38 get() = ClickGUIModule.elementColor
    val gray26 get() = ClickGUIModule.backgroundColor

    // ============================== STATE ==============================

    private sealed class Page {
        data class GroupPage(val group: GuiLayout.Group) : Page()
        data class ModulePage(val module: Module, val toggleable: Boolean, val group: GuiLayout.Group, val tab: GuiLayout.Tab) : Page()
        /** A macro's page: overview / instructions + activity on the left, settings on the right, START below. */
        data class MacroPage(val macro: Macro, val group: GuiLayout.Group) : Page()
        data object ThemePage : Page()
    }

    private var page: Page? = null
    private var scroll = 0f
    private var scrollTarget = 0f
    /** Tab the user clicked; stays highlighted until they scroll by hand (the target may be clamped short of it). */
    private var pendingTab: String? = null
    /** Tab to scroll to once the next frame has laid out its sections (set by back buttons). */
    private var scrollToAfterLayout: String? = null
    private var listeningKey: KeybindSetting? = null
    private var lastSearch = ""
    private var lastFrameNanos = 0L
    private val openAnim = EaseOutAnimation(240)
    private val toggleProgress = HashMap<Module, Float>()
    private val enableToggles = HashMap<Module, ModuleToggleSetting>()
    /** Macro page left card: 0 = Overview, 1 = Instructions. */
    private var macroTab = 0

    // -- layout constants (standard GUI-scale units; ~px at 1080p) --
    private const val RAIL_W = 78f
    private const val HEADER_H = 62f
    private const val COL_GAP = 12f
    private const val ROW_GAP = 9f
    private const val NAME_SIZE = 18f
    private const val DESC_SIZE = 13f
    private const val DESC_LH = 1.25f
    private const val SECTION_H = 24f
    private const val ROW_MIN_H = 68f
    private const val ROW_PAD_X = 16f
    private const val CARD_R = 10f
    private const val RAIL_BTN = 46f
    private const val RAIL_DIVIDER_H = 10f
    private const val PILL_H = 26f
    private const val PILL_TEXT = 12.5f
    private const val SET_PAD_X = 14f
    private const val SET_PAD_Y = 12f
    private const val SET_NAME = 16f
    private const val SET_DESC = 12.5f
    private const val SEARCH_W = 250f
    private const val SEARCH_H = 36f
    private const val FOOTER_H = 64f
    private const val START_W = 140f
    private const val START_H = 42f

    // -- per-frame geometry (also used by input handlers) --
    private var screenW = 0f
    private var screenH = 0f
    private var winX = 0f
    private var winY = 0f
    private var winW = 0f
    private var winH = 0f
    private var viewX = 0f
    private var viewY = 0f
    private var viewW = 0f
    private var viewH = 0f
    private var contentH = 0f
    private var placed: List<Placed> = emptyList()
    private var sectionY: Map<String, Float> = emptyMap()
    private var tabs: List<TabHit> = emptyList()
    private val renderedSettings = ArrayList<RenderableSetting<*>>()
    private var hoveredRailLabel: String? = null
    private var hoveredRailY = 0f

    // macro page geometry (set each frame while a macro page is shown)
    private var mpX = 0f
    private var mpY = 0f
    private var mpH = 0f
    private var mpLeftW = 0f
    private var mpRightX = 0f
    private var mpRightW = 0f
    private var footerY = 0f
    private var macroTabHits: List<TabHit> = emptyList()

    private fun macroLayout() = currentPage() is Page.MacroPage && SearchBar.currentSearch.isBlank()

    private fun scale() = ClickGUIModule.clickGuiScale()
    private fun mx() = guiMouseX / scale()
    private fun my() = guiMouseY / scale()

    private fun currentPage(): Page =
        page ?: Page.GroupPage(GuiLayout.groups.first()).also { page = it }

    private fun currentGroup(): GuiLayout.Group? = when (val p = currentPage()) {
        is Page.GroupPage -> p.group
        is Page.ModulePage -> p.group
        is Page.MacroPage -> p.group
        Page.ThemePage -> null
    }

    private fun setPage(p: Page) {
        page = p
        scroll = 0f; scrollTarget = 0f; pendingTab = null
        macroTab = 0
        listeningKey = null
        if (SearchBar.currentSearch.isNotEmpty()) SearchBar.clear()
    }

    /** Jump to a page by id: "theme", a [GuiLayout.Group] id, or "module:<Name>". Used by DevShot. */
    fun selectPage(pageId: String) {
        // DevShot: "<page>@<px>" also scrolls the page down that far
        val id = pageId.substringBefore('@')
        when {
            id == "theme" -> setPage(Page.ThemePage)
            id.startsWith("module:") -> ModuleManager.modules.values
                .firstOrNull { it.name.equals(id.removePrefix("module:"), true) }
                ?.let { if (it is Macro) openMacro(it) else openModule(it, true) }
            else -> GuiLayout.groups.firstOrNull { it.id == id }?.let { setPage(Page.GroupPage(it)) }
        }
        pageId.substringAfter('@', "").toFloatOrNull()?.let { scrollTarget = it; scroll = it }
    }

    fun openHudEditor() {
        mc.gui.setScreen(HudEditorScreen(this))
    }

    // ============================== LAYOUT MODEL ==============================

    private sealed class Node { abstract val h: Float }
    private class HeaderNode(val label: String) : Node() { override val h = SECTION_H }
    private class EmptyNode(val label: String) : Node() { override val h = 90f }
    private class ModuleNode(val module: Module, val toggleable: Boolean, val descW: Float, val descH: Float, override val h: Float) : Node()
    /** A macro row on the Macros page: name + status dot + description, a gear on the right. */
    private class MacroNode(val macro: Macro, val descW: Float, val descH: Float, override val h: Float) : Node()
    /**
     * A setting card. [inline] = inline row (name + description on the left drawn by us, the setting draws
     * only its control, vertically centred in a [headerH]-tall band); otherwise the setting lays itself out
     * full-width and its description goes underneath.
     */
    private class SettingNode(
        val setting: RenderableSetting<*>,
        val descH: Float,
        override val h: Float,
        val inline: Boolean = false,
        val textW: Float = 0f,
        val textH: Float = 0f,
        val headerH: Float = 0f,
        /** Spans both columns (see [RenderableSetting.fullWidth]); laid out at the full content width. */
        val span: Boolean = false,
    ) : Node()

    /**
     * One card holding [head] plus the settings grouped under it ([Setting.groupParent]). Stacked, or when
     * [wide] the card spans both columns and its settings fill it left to right, two per row.
     */
    private class GroupNode(val head: SettingNode, val children: List<SettingNode>, val wide: Boolean = false) : Node() {
        override val h: Float = head.h + if (!wide) children.sumOf { it.h.toDouble() }.toFloat() else {
            var sum = 0f
            var i = 0
            while (i < children.size) {
                sum += if (i + 1 < children.size) max(children[i].h, children[i + 1].h) else children[i].h
                i += 2
            }
            sum
        }
        val span get() = head.span || wide
    }

    private fun isSpan(n: Node) = (n is SettingNode && n.span) || (n is GroupNode && n.span)

    private class Placed(val node: Node, val x: Float, val y: Float, val w: Float)
    private class Section(val tab: String, val header: HeaderNode?, val nodes: List<Node>)
    private class TabHit(val label: String, val x: Float, val w: Float, val active: Boolean, val onClick: () -> Unit)

    private fun hasSettings(m: Module) = m.settings.values.any { it is RenderableSetting<*> && it.isVisible }

    private fun keyLabel(k: KeybindSetting) = if (listeningKey === k) "..." else k.keyLabel()
    private fun pillW(m: Module) = GGRenderer.textWidth(keyLabel(m.rowKeybind), PILL_TEXT) + 22f

    /** Width taken by a row's right-hand controls (dots, switch, key pill), for wrapping the description. */
    private fun controlsW(m: Module, toggleable: Boolean): Float {
        var w = 12f
        if (hasSettings(m)) w += 26f
        if (toggleable) w += Theme.TOGGLE_W + 10f + pillW(m) + 8f
        return w
    }

    private fun moduleNode(m: Module, toggleable: Boolean, colW: Float): ModuleNode {
        val descW = (colW - ROW_PAD_X - controlsW(m, toggleable) - 8f).coerceAtLeast(80f)
        val descH = if (m.description.isEmpty()) 0f else GGRenderer.wrappedTextBounds(m.description, descW, DESC_SIZE, null, DESC_LH)[3]
        val h = max(ROW_MIN_H, 14f + NAME_SIZE + 5f + descH + 14f)
        return ModuleNode(m, toggleable, descW, descH, h)
    }

    private const val RUNNING_TEXT = 11f
    private fun macroControlsW(m: Macro) = 12f + 30f + if (m.running) GGRenderer.textWidth("RUNNING", RUNNING_TEXT) + 12f else 0f

    private fun macroNode(m: Macro, colW: Float): MacroNode {
        val descW = (colW - ROW_PAD_X - macroControlsW(m) - 8f).coerceAtLeast(80f)
        val descH = if (m.description.isEmpty()) 0f else GGRenderer.wrappedTextBounds(m.description, descW, DESC_SIZE, null, DESC_LH)[3]
        val h = max(ROW_MIN_H, 14f + NAME_SIZE + 5f + descH + 14f)
        return MacroNode(m, descW, descH, h)
    }

    private fun statusColor(s: Macro.Status): Int = when (s) {
        Macro.Status.WORKING -> Theme.argb(255, 120, 205, 135)
        Macro.Status.TESTING -> Theme.argb(255, 230, 200, 95)
        Macro.Status.BROKEN -> Theme.argb(255, 230, 105, 105)
    }

    /**
     * A module's visible settings as cards: settings grouped under a parent ([com.fish5.clickgui.settings.Setting.groupParent])
     * ride inside the parent's card. [allowSpan]: honour [RenderableSetting.fullWidth] (two-column pages only).
     */
    private fun settingsNodes(list: List<RenderableSetting<*>>, colW: Float, allowSpan: Boolean): List<Node> {
        if (list.isEmpty()) return listOf(EmptyNode("No settings."))
        val grouped = list.filter { it.groupParent != null }.groupBy { it.groupParent }
        return list.filter { it.groupParent == null }.map { s ->
            val kids = grouped[s].orEmpty()
            // 2+ grouped settings: the card owns the full width and lays them out side by side;
            // 0-1 stays half width so another small card can sit next to it
            val wide = allowSpan && kids.size >= 2
            val head = settingNode(s, colW, allowSpan, forceSpan = wide)
            when {
                kids.isEmpty() -> head
                wide -> GroupNode(head, kids.map { settingNode(it, colW + COL_GAP / 2f) }, wide = true)
                else -> GroupNode(head, kids.map { settingNode(it, if (head.span) colW * 2f + COL_GAP else colW) })
            }
        }
    }

    /** [allowSpan]: only single-section pages can honour [RenderableSetting.fullWidth]. */
    private fun settingNode(s: RenderableSetting<*>, colW0: Float, allowSpan: Boolean = false, forceSpan: Boolean = false): SettingNode {
        val span = forceSpan || (allowSpan && s.fullWidth)
        val colW = if (span) colW0 * 2f + COL_GAP else colW0
        s.width = colW - 16f
        s.showHelpBadge = false
        val ctrl = s.inlineControlWidth()
        if (ctrl > 0f) {
            s.hideName = true
            val textW = (colW - SET_PAD_X - 14f - ctrl - 10f).coerceAtLeast(60f)
            val descH = if (s.description.isEmpty()) 0f else GGRenderer.wrappedTextBounds(s.description, textW, SET_DESC, null, DESC_LH)[3]
            val textH = SET_NAME + if (descH > 0f) 5f + descH else 0f
            val headerH = max(textH, Panel.HEIGHT)
            val extra = (s.getHeight() - Panel.HEIGHT).coerceAtLeast(0f) // an open dropdown / colour picker
            val h = SET_PAD_Y * 2f + headerH + extra
            return SettingNode(s, descH, h, inline = true, textW = textW, textH = textH, headerH = headerH, span = span)
        }
        s.hideName = false
        val descH = if (s.description.isEmpty()) 0f else GGRenderer.wrappedTextBounds(s.description, colW - 28f, SET_DESC, null, DESC_LH)[3]
        val h = 10f + s.getHeight() + (if (descH > 0f) descH + 4f else 0f) + 10f
        return SettingNode(s, descH, h, span = span)
    }

    private fun entryNodes(e: GuiLayout.Entry, colW: Float): List<Node> = when (e) {
        is GuiLayout.Entry.Mod -> listOf(moduleNode(e.module, e.toggleable, colW))
        is GuiLayout.Entry.Settings -> e.provider().map { settingNode(it, colW) }
        is GuiLayout.Entry.MacroRow -> listOf(macroNode(e.macro, colW))
    }

    private fun matches(e: GuiLayout.Entry, q: String): List<GuiLayout.Entry> = when (e) {
        is GuiLayout.Entry.Mod ->
            if (e.module.name.contains(q, true) ||
                e.module.settings.values.any { it is RenderableSetting<*> && it.isVisible && it.name.contains(q, true) }) listOf(e)
            else emptyList()
        is GuiLayout.Entry.Settings -> {
            val hits = e.provider().filter { it.name.contains(q, true) }
            if (hits.isEmpty()) emptyList() else listOf(GuiLayout.Entry.Settings { hits })
        }
        is GuiLayout.Entry.MacroRow ->
            if (e.macro.name.contains(q, true) ||
                e.macro.settings.values.any { it is RenderableSetting<*> && it.isVisible && it.name.contains(q, true) }) listOf(e)
            else emptyList()
    }

    private fun buildSections(colW: Float): List<Section> {
        val q = SearchBar.currentSearch.trim()
        if (q.isNotEmpty()) {
            val out = GuiLayout.groups.mapNotNull { g ->
                val nodes = g.tabs.flatMap { t -> t.entries.flatMap { matches(it, q) } }.flatMap { entryNodes(it, colW) }
                if (nodes.isEmpty()) null else Section(g.label, HeaderNode(g.label.uppercase()), nodes)
            }
            return out.ifEmpty { listOf(Section("", null, listOf(EmptyNode("Nothing matches \"$q\"")))) }
        }
        return when (val p = currentPage()) {
            is Page.GroupPage -> p.group.tabs.map { t ->
                Section(t.name, HeaderNode(t.name.uppercase()), t.entries.flatMap { entryNodes(it, colW) })
            }
            is Page.ModulePage -> {
                val m = p.module
                val list = ArrayList<RenderableSetting<*>>()
                if (p.toggleable) list += enableToggles.getOrPut(m) { ModuleToggleSetting(m, "Enabled") }
                list += m.settings.values.filterIsInstance<RenderableSetting<*>>().filter { it.isVisible }
                listOf(Section(m.name, HeaderNode("SETTINGS"), settingsNodes(list, colW, allowSpan = true)))
            }
            // one column inside the settings panel; the panel draws its own "SETTINGS" label
            is Page.MacroPage -> listOf(Section(p.macro.name, null, settingsNodes(
                p.macro.settings.values.filterIsInstance<RenderableSetting<*>>().filter { it.isVisible }, colW, allowSpan = false,
            )))
            Page.ThemePage -> listOf(Section("Theme", HeaderNode("THEME"), GuiLayout.themeSettings().map { settingNode(it, colW) }))
        }
    }

    /**
     * Two-column masonry, a single section drops each row into the shorter column (header
     * on the left, a blank spacer on the right so the first rows line up); several sections each go whole into
     * whichever column is currently shorter, with their own header.
     */
    private fun layout(colW: Float) {
        val sections = buildSections(colW)
        val ys = floatArrayOf(0f, 0f)
        val out = ArrayList<Placed>()
        val secY = LinkedHashMap<String, Float>()
        fun put(col: Int, n: Node) {
            out += Placed(n, col * (colW + COL_GAP), ys[col], colW)
            ys[col] += n.h + if (n is HeaderNode) 2f else ROW_GAP
        }
        if (sections.size == 1) {
            val s = sections[0]
            secY[s.tab] = 0f
            if (s.header != null) { put(0, s.header); put(1, HeaderNode("")) }
            if (macroLayout()) {
                // macro settings panel: one column, stacked
                for (n in s.nodes) put(0, n)
                placed = out
                sectionY = secY
                contentH = ys[0]
                return
            }
            // Each card goes into the shorter column (grouped cards vary a lot in height); a full-width
            // node first levels both columns and spans them.
            for (n in s.nodes) {
                if (isSpan(n)) {
                    val y = max(ys[0], ys[1])
                    out += Placed(n, 0f, y, colW * 2f + COL_GAP)
                    ys[0] = y + n.h + ROW_GAP
                    ys[1] = ys[0]
                } else put(if (ys[0] <= ys[1]) 0 else 1, n)
            }
        } else {
            for (s in sections) {
                val col = if (ys[0] <= ys[1]) 0 else 1
                secY[s.tab] = ys[col]
                s.header?.let { put(col, it) }
                s.nodes.forEach { put(col, it) }
            }
        }
        placed = out
        sectionY = secY
        contentH = max(ys[0], ys[1])
    }

    // ============================== RENDER ==============================

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0.016f else ((now - lastFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastFrameNanos = now

        GGRenderer.frame(context) {
            val s = scale()
            GGRenderer.scale(s, s)
            screenW = context.guiWidth() / s
            screenH = context.guiHeight() / s

            // Window size is decided at the *base* scale (GUI Scale 100%) and converted into the scaled
            // space, so GUI Scale only resizes what's inside (rows, text, controls) -- the window itself
            // keeps the same footprint on screen.
            val f = (ClickGUIModule.guiScalePercent.toFloat() / 100f).coerceAtLeast(0.1f)
            winW = min(screenW * f * 0.86f, 1560f) / f
            winH = min(screenH * f * 0.78f, 920f) / f
            winX = (screenW - winW) / 2f
            winY = (screenH - winH) / 2f
            viewX = winX + RAIL_W + 14f
            viewY = winY + HEADER_H + 10f
            viewW = winX + winW - 18f - viewX
            viewH = winY + winH - 12f - viewY

            if (SearchBar.currentSearch != lastSearch) {
                lastSearch = SearchBar.currentSearch
                scroll = 0f; scrollTarget = 0f; pendingTab = null
            }

            val macroPage = macroLayout()
            if (macroPage) {
                // left: overview card + activity + stats; right: settings panel (the scrolling view); footer: START
                mpX = winX + RAIL_W + 14f
                mpY = winY + HEADER_H + 14f
                val cw = winX + winW - 18f - mpX
                footerY = winY + winH - FOOTER_H
                mpH = footerY - 10f - mpY
                mpLeftW = (cw - 14f) * 0.56f
                mpRightX = mpX + mpLeftW + 14f
                mpRightW = mpX + cw - mpRightX
                viewX = mpRightX + 10f
                viewY = mpY + 36f
                viewW = mpRightW - 20f
                viewH = mpH - 46f
            }

            val colW = if (macroPage) viewW else (viewW - COL_GAP) / 2f
            layout(colW)
            scrollToAfterLayout?.let { scrollToAfterLayout = null; scrollTo(it) }

            val maxScroll = (contentH - viewH + 12f).coerceAtLeast(0f)
            pageScrolls = maxScroll > 0f
            scrollTarget = scrollTarget.coerceIn(0f, maxScroll)
            scroll += (scrollTarget - scroll) * min(1f, dt * 14f)
            if (abs(scrollTarget - scroll) < 0.3f) scroll = scrollTarget

            GGRenderer.rect(0f, 0f, screenW, screenH, Theme.backdrop)

            if (ClickGUIModule.openAnimation && openAnim.isAnimating()) {
                val a = openAnim.get(0.94f, 1f)
                val cx = winX + winW / 2f
                val cy = winY + winH / 2f
                GGRenderer.translate(cx, cy)
                GGRenderer.scale(a, a)
                GGRenderer.translate(-cx, -cy)
            }

            GGRenderer.rect(winX, winY, winW, winH, Theme.window, 14f)
            GGRenderer.hollowRect(winX, winY, winW, winH, 1f, Theme.border, 14f)

            drawRail()
            drawHeader()
            val mp = currentPage() as? Page.MacroPage
            if (macroPage && mp != null) drawMacroPanels(mp.macro)
            drawContent(dt)
            if (macroPage && mp != null) {
                drawMacroFooter(mp.macro)
                drawScrollbar(maxScroll, viewX + viewW + 3f)
            } else drawScrollbar(maxScroll, winX + winW - 10f)

            hoveredRailLabel?.let { label ->
                val tw = GGRenderer.textWidth(label, 12f)
                val tx = winX + RAIL_W - 10f
                GGRenderer.rect(tx, hoveredRailY + RAIL_BTN / 2f - 11f, tw + 16f, 22f, Theme.tooltip, 6f)
                GGRenderer.text(label, tx + 8f, hoveredRailY + RAIL_BTN / 2f - 6f, 12f, Theme.text)
            }

            desc.render()
        }
        super.extractRenderState(context, mouseX, mouseY, deltaTicks)
    }

    // -------- rail --------

    private class RailItem(val label: String, val y: Float, val active: Boolean, val draw: (Float, Float, Int) -> Unit, val onClick: () -> Unit)

    /** The current page is taller than the view (set each frame after layout). */
    private var pageScrolls = true

    /** Y of each rail divider line, filled by [railItems]. */
    private val railDividers = ArrayList<Float>(4)

    private fun railItems(): List<RailItem> {
        val out = ArrayList<RailItem>()
        val group = if (SearchBar.currentSearch.isBlank()) currentGroup() else null
        val groups = GuiLayout.groups
        val dividers = groups.count { it.dividerBefore }
        // squeeze the gaps on short windows so the groups never run into the Discord / Edit HUD buttons
        val bottom = avatarY() - 2f * RAIL_BTN - 24f
        val room = bottom - (winY + 18f) - groups.size * RAIL_BTN - dividers * RAIL_DIVIDER_H
        val gap = (room / maxOf(1, groups.size - 1)).coerceIn(2f, 8f)
        railDividers.clear()
        var y = winY + 18f
        for (g in groups) {
            if (g.dividerBefore && y > winY + 18f) {
                railDividers += y - gap + (RAIL_DIVIDER_H + gap) / 2f
                y += RAIL_DIVIDER_H
            }
            out += RailItem(g.label.uppercase(), y, g === group, { x, iy, c -> GGRenderer.icon(g.icon, x, iy, 22f, c) }) {
                setPage(Page.GroupPage(g))
            }
            y += RAIL_BTN + gap
        }
        // Discord link sits directly above the player-head (Theme) button; Edit HUD above that.
        out += RailItem("DISCORD", avatarY() - RAIL_BTN - 8f, false, { x, iy, c -> GGRenderer.icon(GuiLayout.iconDiscord, x, iy, 22f, c) }) { openDiscord() }
        out += RailItem("EDIT HUD", avatarY() - 2f * RAIL_BTN - 16f, false, { x, iy, c -> GGRenderer.icon(GuiLayout.iconEdit, x, iy, 22f, c) }) { openHudEditor() }
        return out
    }

    private fun openDiscord() {
        net.minecraft.util.Util.getPlatform().openUri(java.net.URI.create("https://discord.gg/r3bS2daqfT"))
    }

    private fun railBtnX() = winX + (RAIL_W - RAIL_BTN) / 2f
    private fun avatarY() = winY + winH - 18f - RAIL_BTN

    private fun drawRail() {
        hoveredRailLabel = null
        val bx = railBtnX()
        val items = railItems()
        for (dy in railDividers) GGRenderer.rect(bx + 8f, dy, RAIL_BTN - 16f, 1f, Theme.divider)
        for (item in items) {
            val hovered = isAreaHovered(bx, item.y, RAIL_BTN, RAIL_BTN, true)
            if (item.active) {
                GGRenderer.rect(bx, item.y, RAIL_BTN, RAIL_BTN, Theme.railActive, 11f)
                GGRenderer.rect(winX + 5f, item.y + 12f, 3f, RAIL_BTN - 24f, Theme.accent, 1.5f)
            } else if (hovered) {
                GGRenderer.rect(bx, item.y, RAIL_BTN, RAIL_BTN, Theme.railHover, 11f)
            }
            val color = if (item.active || hovered) Theme.text else Theme.textSecondary
            item.draw(bx + (RAIL_BTN - 22f) / 2f, item.y + (RAIL_BTN - 22f) / 2f, color)
            if (hovered) { hoveredRailLabel = item.label; hoveredRailY = item.y }
        }

        // Player head -> Theme page.
        val ay = avatarY()
        val hovered = isAreaHovered(bx, ay, RAIL_BTN, RAIL_BTN, true)
        val active = currentPage() == Page.ThemePage && SearchBar.currentSearch.isBlank()
        GGRenderer.rect(bx, ay, RAIL_BTN, RAIL_BTN, Theme.control, 11f)
        if (!GGRenderer.playerFace(bx + 5f, ay + 5f, RAIL_BTN - 10f)) {
            GGRenderer.icon(GuiLayout.iconUser, bx + 12f, ay + 12f, 22f, Theme.textSecondary)
        }
        GGRenderer.hollowRect(bx, ay, RAIL_BTN, RAIL_BTN, if (active || hovered) 2f else 1f, if (active) Theme.accent else if (hovered) Theme.text else Theme.border, 11f)
        if (hovered) { hoveredRailLabel = "THEME"; hoveredRailY = ay }
    }

    // -------- header --------

    private fun backBtnX() = winX + RAIL_W + 8f
    private fun headerTop() = winY + (HEADER_H - 34f) / 2f

    private fun computeTabs(): List<TabHit> {
        val out = ArrayList<TabHit>()
        val q = SearchBar.currentSearch.isNotBlank()
        var x = winX + RAIL_W + 6f
        fun add(label: String, active: Boolean, onClick: () -> Unit) {
            val w = GGRenderer.textWidth(label, 16.5f) + 24f
            out += TabHit(label, x, w, active, onClick)
            x += w
        }
        if (q) { add("Results", true) {}; return out }
        when (val p = currentPage()) {
            // the tabs only jump around a scrolling page: when it all fits, just name the group
            is Page.GroupPage -> if (!pageScrolls) add(p.group.label, true) {} else {
                val active = activeTab()
                add("All", active == "All") { scrollTo(null) }
                for (t in p.group.tabs) add(t.name, active == t.name) { scrollTo(t.name) }
            }
            is Page.ModulePage -> {
                x = backBtnX() + 34f + 10f
                x += GGRenderer.textWidth("${p.group.label} / ${p.tab.name} /", 13f) + 4f
                add(p.module.name, true) {}
            }
            is Page.MacroPage -> {} // title + breadcrumb drawn by drawHeader
            Page.ThemePage -> add("Theme", true) {}
        }
        return out
    }

    private fun scrollTo(tab: String?) {
        pendingTab = tab ?: "All"
        scrollTarget = if (tab == null) 0f else sectionY[tab] ?: 0f
    }

    private fun activeTab(): String {
        pendingTab?.let { return it }
        if (scroll < 1f) return "All"
        var best = "All"
        var bestY = -1f
        for ((name, y) in sectionY) if (y <= scroll + 8f && y >= bestY) { best = name; bestY = y }
        return best
    }

    private fun drawHeader() {
        val right = winX + winW - 16f
        GGRenderer.rect(winX + RAIL_W, winY + HEADER_H, right - (winX + RAIL_W), 1f, Theme.divider)

        val p = currentPage()
        if (p is Page.ModulePage && SearchBar.currentSearch.isBlank()) {
            val bx = backBtnX()
            val by = headerTop()
            val hovered = isAreaHovered(bx, by, 34f, 34f, true)
            GGRenderer.rect(bx, by, 34f, 34f, if (hovered) Theme.railActive else Theme.railHover, 9f)
            GGRenderer.icon(GuiLayout.iconBack, bx + 9f, by + 9f, 16f, Theme.text)
            GGRenderer.text("${p.group.label} / ${p.tab.name} /", bx + 44f, winY + HEADER_H / 2f - 6.5f, 13f, Theme.textSecondary)
        }
        if (p is Page.MacroPage && SearchBar.currentSearch.isBlank()) {
            val bx = backBtnX()
            val by = headerTop()
            val hovered = isAreaHovered(bx, by, 34f, 34f, true)
            GGRenderer.rect(bx, by, 34f, 34f, if (hovered) Theme.railActive else Theme.railHover, 9f)
            GGRenderer.icon(GuiLayout.iconBack, bx + 9f, by + 9f, 16f, Theme.text)
            GGRenderer.text(p.macro.name, bx + 48f, winY + HEADER_H / 2f - 15f, 17f, Theme.text)
            GGRenderer.text("${p.group.label.uppercase()}  ·  ${p.macro.area.uppercase()}", bx + 48f, winY + HEADER_H / 2f + 5f, 10.5f, Theme.textSecondary)
        }

        tabs = computeTabs()
        for (t in tabs) {
            val hovered = isAreaHovered(t.x, winY, t.w, HEADER_H, true)
            val color = if (t.active) Theme.accent else if (hovered) Theme.text else Theme.argb(255, 195, 197, 204)
            GGRenderer.text(t.label, t.x + 12f, winY + HEADER_H / 2f - 8.5f, 16.5f, color)
            if (t.active) GGRenderer.rect(t.x + 12f, winY + HEADER_H - 11f, t.w - 24f, 2f, Theme.accent, 1f)
        }

        SearchBar.draw(right - SEARCH_W, winY + (HEADER_H - SEARCH_H) / 2f, SEARCH_W, SEARCH_H, mx(), my())
    }

    // -------- content --------

    private fun progressFor(m: Module, dt: Float): Float {
        val target = if (m.enabled) 1f else 0f
        val cur = toggleProgress[m] ?: target
        val next = cur + (target - cur) * min(1f, dt * 16f)
        val v = if (abs(target - next) < 0.01f) target else next
        toggleProgress[m] = v
        return v
    }

    private class RowGeom(val dotsX: Float?, val toggleX: Float?, val pillX: Float?, val pillW: Float)

    private fun rowGeom(n: ModuleNode, x: Float, w: Float): RowGeom {
        var right = x + w - 10f
        var dots: Float? = null
        if (hasSettings(n.module)) { dots = right - 22f; right -= 26f } else right -= 6f
        var toggle: Float? = null
        var pill: Float? = null
        val pw = pillW(n.module)
        if (n.toggleable) {
            toggle = right - Theme.TOGGLE_W
            pill = toggle - 10f - pw
        }
        return RowGeom(dots, toggle, pill, pw)
    }

    private fun mouseInView() = isAreaHovered(viewX, viewY, viewW, viewH, true)

    private fun drawContent(dt: Float) {
        renderedSettings.clear()
        val inView = mouseInView()
        GGRenderer.pushScissor(viewX - 2f, viewY, viewW + 4f, viewH)
        for (p in placed) {
            val x = viewX + p.x
            val y = viewY + p.y - scroll
            if (y > viewY + viewH || y + p.node.h < viewY) continue
            when (val n = p.node) {
                is HeaderNode -> if (n.label.isNotEmpty())
                    GGRenderer.text(n.label, x + 2f, y + 9f, 12f, Theme.textSecondary)
                is EmptyNode -> {
                    val tw = GGRenderer.textWidth(n.label, 14f)
                    GGRenderer.text(n.label, viewX + viewW / 2f - tw / 2f, y + 36f, 14f, Theme.textSecondary)
                }
                is ModuleNode -> drawModuleRow(n, x, y, p.w, inView, dt)
                is MacroNode -> drawMacroRow(n, x, y, p.w, inView)
                is SettingNode -> drawSettingCard(n, x, y, p.w, inView)
                is GroupNode -> drawGroupCard(n, x, y, p.w, inView)
            }
        }
        GGRenderer.popScissor()
    }

    private fun drawModuleRow(n: ModuleNode, x: Float, y: Float, w: Float, inView: Boolean, dt: Float) {
        val m = n.module
        val hovered = inView && isAreaHovered(x, y, w, n.h, true)
        GGRenderer.rect(x, y, w, n.h, if (hovered) Theme.cardHover else Theme.card, CARD_R)
        GGRenderer.hollowRect(x, y, w, n.h, 1f, Theme.border, CARD_R)

        // Name + description block vertically centred in the row, like the controls on the right.
        val blockH = NAME_SIZE + if (n.descH > 0f) 5f + n.descH else 0f
        val ty = y + (n.h - blockH) / 2f
        GGRenderer.text(m.name, x + ROW_PAD_X, ty, NAME_SIZE, Theme.text)
        if (m.description.isNotEmpty())
            GGRenderer.drawWrappedString(m.description, x + ROW_PAD_X, ty + NAME_SIZE + 5f, n.descW, DESC_SIZE, Theme.textSecondary, null, DESC_LH)

        val g = rowGeom(n, x, w)
        val cy = y + n.h / 2f
        g.dotsX?.let { dx ->
            val dh = inView && isAreaHovered(dx, cy - 14f, 22f, 28f, true)
            GGRenderer.icon(GuiLayout.iconDots, dx + 3f, cy - 8f, 16f, if (dh) Theme.text else Theme.textSecondary)
        }
        if (g.toggleX != null && g.pillX != null) {
            val th = inView && isAreaHovered(g.toggleX, cy - Theme.TOGGLE_H / 2f, Theme.TOGGLE_W, Theme.TOGGLE_H, true)
            Theme.toggle(g.toggleX, cy - Theme.TOGGLE_H / 2f, progressFor(m, dt), th)

            val key = m.rowKeybind
            val ph = inView && isAreaHovered(g.pillX, cy - PILL_H / 2f, g.pillW, PILL_H, true)
            Theme.pill(g.pillX, cy - PILL_H / 2f, g.pillW, PILL_H, ph)
            if (listeningKey === key) GGRenderer.hollowRect(g.pillX, cy - PILL_H / 2f, g.pillW, PILL_H, 1.5f, Theme.accent, PILL_H / 2f)
            val label = keyLabel(key)
            val lw = GGRenderer.textWidth(label, PILL_TEXT)
            GGRenderer.text(label, g.pillX + (g.pillW - lw) / 2f, cy - PILL_TEXT / 2f + 0.75f, PILL_TEXT, Theme.text) // caps sit high when line-box centred
        }
    }

    // -------- macros --------

    private fun macroGearX(x: Float, w: Float) = x + w - 10f - 30f

    private fun drawMacroRow(n: MacroNode, x: Float, y: Float, w: Float, inView: Boolean) {
        val m = n.macro
        val hovered = inView && isAreaHovered(x, y, w, n.h, true)
        GGRenderer.rect(x, y, w, n.h, if (hovered) Theme.cardHover else Theme.card, CARD_R)
        GGRenderer.hollowRect(x, y, w, n.h, 1f, Theme.border, CARD_R)

        val blockH = NAME_SIZE + if (n.descH > 0f) 5f + n.descH else 0f
        val ty = y + (n.h - blockH) / 2f
        GGRenderer.text(m.name, x + ROW_PAD_X, ty, NAME_SIZE, Theme.text)
        val nameW = GGRenderer.textWidth(m.name, NAME_SIZE)
        GGRenderer.circle(x + ROW_PAD_X + nameW + 9f, ty + 3f, 3.5f, statusColor(m.status))
        if (m.description.isNotEmpty())
            GGRenderer.drawWrappedString(m.description, x + ROW_PAD_X, ty + NAME_SIZE + 5f, n.descW, DESC_SIZE, Theme.textSecondary, null, DESC_LH)

        val cy = y + n.h / 2f
        val gx = macroGearX(x, w)
        if (m.running) {
            val rw = GGRenderer.textWidth("RUNNING", RUNNING_TEXT)
            GGRenderer.text("RUNNING", gx - 6f - rw, cy - RUNNING_TEXT / 2f + 0.75f, RUNNING_TEXT, Theme.accent)
        }
        val gh = inView && isAreaHovered(gx, cy - 15f, 30f, 30f, true)
        GGRenderer.icon(GuiLayout.iconGear, gx + 5f, cy - 10f, 20f, if (gh || hovered) Theme.text else Theme.textSecondary)
    }

    private fun panel(x: Float, y: Float, w: Float, h: Float) {
        GGRenderer.rect(x, y, w, h, Theme.withAlpha(Theme.card, 0.55f), CARD_R)
        GGRenderer.hollowRect(x, y, w, h, 1f, Theme.border, CARD_R)
    }

    /** Wrapped text that also honours '\n' line breaks. Returns the height drawn. */
    private fun paragraphs(text: String, x: Float, y: Float, w: Float, size: Float, color: Int): Float {
        var cy = y
        for (line in text.split('\n')) {
            if (line.isBlank()) { cy += size * DESC_LH; continue }
            GGRenderer.drawWrappedString(line, x, cy, w, size, color, null, DESC_LH)
            cy += GGRenderer.wrappedTextBounds(line, w, size, null, DESC_LH)[3] + 3f
        }
        return cy - y
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "—"
        val mins = ms / 60_000L
        return when {
            mins < 1L -> "<1m"
            mins < 60L -> "${mins}m"
            else -> "${mins / 60L}h ${mins % 60L}m"
        }
    }

    private const val STATS_H = 62f
    private const val ACTIVITY_H = 58f

    private fun drawMacroPanels(m: Macro) {
        val x = mpX
        val w = mpLeftW
        val infoH = mpH - STATS_H - ACTIVITY_H - 20f

        // overview / instructions card
        panel(x, mpY, w, infoH)
        val tabs = ArrayList<TabHit>(2)
        var tx = x + 8f
        for ((i, label) in arrayOf("Overview", "Instructions").withIndex()) {
            val tw = GGRenderer.textWidth(label, 15f) + 16f
            val active = macroTab == i
            val hovered = isAreaHovered(tx, mpY + 6f, tw, 30f, true)
            GGRenderer.text(label, tx + 8f, mpY + 13f, 15f, if (active) Theme.text else if (hovered) Theme.text else Theme.textSecondary)
            if (active) GGRenderer.rect(tx + 8f, mpY + 32f, tw - 16f, 2f, Theme.accent, 1f)
            tabs += TabHit(label, tx, tw, active) { macroTab = i }
            tx += tw
        }
        macroTabHits = tabs
        val body = if (macroTab == 0) m.description else m.instructions.ifEmpty { "No instructions yet." }
        GGRenderer.pushScissor(x, mpY + 40f, w, infoH - 44f)
        paragraphs(body, x + 14f, mpY + 46f, w - 28f, 14f, Theme.text)
        GGRenderer.popScissor()

        // activity: one square per day, oldest on the left, brighter = more time that day
        val ay = mpY + infoH + 10f
        panel(x, ay, w, ACTIVITY_H)
        GGRenderer.text("ACTIVITY  ·  LAST ${Macro.ACTIVITY_DAYS} DAYS", x + 14f, ay + 10f, 10f, Theme.textSecondary)
        var maxMs = 0L
        for (d in 0 until Macro.ACTIVITY_DAYS) maxMs = max(maxMs, m.activityMs(d))
        val gap = 3f
        val cell = min(18f, (w - 28f - gap * (Macro.ACTIVITY_DAYS - 1)) / Macro.ACTIVITY_DAYS)
        val empty = Theme.withAlpha(Theme.text, 0.07f)
        val low = Theme.withAlpha(Theme.text, 0.22f)
        val high = Theme.withAlpha(Theme.text, 0.85f)
        for (i in 0 until Macro.ACTIVITY_DAYS) {
            val ms = m.activityMs(Macro.ACTIVITY_DAYS - 1 - i)
            val c = if (ms <= 0L || maxMs <= 0L) empty else Theme.lerp(low, high, ms.toFloat() / maxMs)
            GGRenderer.rect(x + 14f + i * (cell + gap), ay + 28f, cell, cell, c, 3f)
        }

        // total time + sessions
        val sy = ay + ACTIVITY_H + 10f
        val bw = (w - 10f) / 2f
        for ((i, pair) in arrayOf("TOTAL TIME" to formatDuration(m.totalTimeMs), "SESSIONS" to if (m.sessions > 0) m.sessions.toString() else "—").withIndex()) {
            val bx = x + i * (bw + 10f)
            panel(bx, sy, bw, STATS_H)
            GGRenderer.text(pair.first, bx + 14f, sy + 10f, 10f, Theme.textSecondary)
            GGRenderer.text(pair.second, bx + 14f, sy + 28f, 20f, Theme.text)
        }

        // settings panel (its cards are drawn by drawContent into the view inside it)
        panel(mpRightX, mpY, mpRightW, mpH)
        GGRenderer.text("SETTINGS", mpRightX + 14f, mpY + 14f, 11f, Theme.textSecondary)
        GGRenderer.rect(mpRightX + 14f, mpY + 31f, mpRightW - 28f, 1f, Theme.divider)
    }

    private fun startBtnX() = winX + winW - 18f - START_W
    private fun startBtnY() = footerY + (FOOTER_H - START_H) / 2f - 2f

    private fun drawMacroFooter(m: Macro) {
        GGRenderer.rect(winX + RAIL_W + 14f, footerY, winX + winW - 18f - (winX + RAIL_W + 14f), 1f, Theme.divider)

        // status where the screenshot's keybind sat (macros have no keybind)
        val ly = footerY + FOOTER_H / 2f - 2f
        GGRenderer.text("STATUS", mpX, ly - 14f, 10f, Theme.textSecondary)
        GGRenderer.circle(mpX + 4f, ly + 7f, 3.5f, statusColor(m.status))
        GGRenderer.text(m.status.label, mpX + 14f, ly + 1f, 13f, Theme.text)

        val bx = startBtnX()
        val by = startBtnY()
        val hovered = isAreaHovered(bx, by, START_W, START_H, true)
        val base = if (m.running) Theme.argb(255, 205, 80, 80) else Theme.accent
        GGRenderer.rect(bx, by, START_W, START_H, Theme.withAlpha(base, if (hovered) 0.55f else 0.35f), 12f)
        GGRenderer.hollowRect(bx, by, START_W, START_H, 1f, Theme.withAlpha(base, 0.7f), 12f)
        val label = if (m.running) "STOP" else "START"
        val lw = GGRenderer.textWidth(label, 18f)
        GGRenderer.text(label, bx + (START_W - lw) / 2f, by + START_H / 2f - 9f + 1f, 18f, Theme.text)
    }

    private fun drawSettingCard(n: SettingNode, x: Float, y: Float, w: Float, inView: Boolean) {
        val hovered = inView && isAreaHovered(x, y, w, n.h, true)
        GGRenderer.rect(x, y, w, n.h, if (hovered) Theme.cardHover else Theme.card, CARD_R)
        GGRenderer.hollowRect(x, y, w, n.h, 1f, Theme.border, CARD_R)
        drawSettingBody(n, x, y, w)
    }

    private fun drawGroupCard(n: GroupNode, x: Float, y: Float, w: Float, inView: Boolean) {
        val hovered = inView && isAreaHovered(x, y, w, n.h, true)
        GGRenderer.rect(x, y, w, n.h, if (hovered) Theme.cardHover else Theme.card, CARD_R)
        GGRenderer.hollowRect(x, y, w, n.h, 1f, Theme.border, CARD_R)
        drawSettingBody(n.head, x, y, w)
        var cy = y + n.head.h
        if (n.wide) {
            val half = w / 2f
            var i = 0
            while (i < n.children.size) {
                val a = n.children[i]
                val b = n.children.getOrNull(i + 1)
                val rowH = if (b != null) max(a.h, b.h) else a.h
                GGRenderer.rect(x + SET_PAD_X, cy, w - SET_PAD_X * 2f, 1f, Theme.divider)
                drawSettingBody(a, x, cy, half)
                if (b != null) {
                    GGRenderer.rect(x + half, cy + 8f, 1f, rowH - 16f, Theme.divider)
                    drawSettingBody(b, x + half, cy, half)
                }
                cy += rowH
                i += 2
            }
            return
        }
        for (child in n.children) {
            GGRenderer.rect(x + SET_PAD_X, cy, w - SET_PAD_X * 2f, 1f, Theme.divider)
            drawSettingBody(child, x, cy, w)
            cy += child.h
        }
    }

    private fun drawSettingBody(n: SettingNode, x: Float, y: Float, w: Float) {
        val s = n.setting
        s.width = w - 16f
        s.showHelpBadge = false
        if (n.inline) {
            val top = y + SET_PAD_Y
            // Control centred in the header band. While it's expanded (open dropdown / colour picker) the
            // band is bottom-aligned instead, so the expanded part starts below the name + description.
            val expanded = s.getHeight() > Panel.HEIGHT + 0.5f
            val bandY = if (expanded) top + n.headerH - Panel.HEIGHT else top + (n.headerH - Panel.HEIGHT) / 2f
            s.render(x + 8f, bandY, mx(), my())
            renderedSettings += s
            val textY = top + (n.headerH - n.textH) / 2f
            GGRenderer.text(s.name, x + SET_PAD_X, textY, SET_NAME, Theme.text)
            if (n.descH > 0f)
                GGRenderer.drawWrappedString(s.description, x + SET_PAD_X, textY + SET_NAME + 5f, n.textW, SET_DESC, Theme.textSecondary, null, DESC_LH)
            return
        }
        val sh = s.render(x + 8f, y + 10f, mx(), my())
        renderedSettings += s
        if (n.descH > 0f)
            GGRenderer.drawWrappedString(s.description, x + 14f, y + 10f + sh + 2f, w - 28f, SET_DESC, Theme.textSecondary, null, DESC_LH)
    }

    private fun drawScrollbar(maxScroll: Float, trackX: Float) {
        if (maxScroll <= 0f) return
        val thumbH = (viewH * viewH / contentH).coerceIn(30f, viewH)
        val thumbY = viewY + (viewH - thumbH) * (scroll / maxScroll)
        GGRenderer.rect(trackX, thumbY, 4f, thumbH, Theme.scrollbar, 2f)
    }

    // ============================== INPUT ==============================

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        scrollTarget -= (verticalAmount.sign * 60).toFloat()
        pendingTab = null
        return true
    }

    override fun mouseClicked(mouseButtonEvent: MouseButtonEvent, bl: Boolean): Boolean {
        if (listeningKey != null) { listeningKey = null; return true }
        val mx = mx()
        val my = my()
        val left = mouseButtonEvent.button() == 0

        if (SearchBar.mouseClicked(mx, my, mouseButtonEvent)) return true

        if (left) {
            // rail
            val bx = railBtnX()
            for (item in railItems()) if (isAreaHovered(bx, item.y, RAIL_BTN, RAIL_BTN, true)) { item.onClick(); return true }
            if (isAreaHovered(bx, avatarY(), RAIL_BTN, RAIL_BTN, true)) { setPage(Page.ThemePage); return true }

            // header
            val p = currentPage()
            if (p is Page.ModulePage && SearchBar.currentSearch.isBlank() && isAreaHovered(backBtnX(), headerTop(), 34f, 34f, true)) {
                setPage(Page.GroupPage(p.group))
                scrollToAfterLayout = p.tab.name
                return true
            }
            if (p is Page.MacroPage && SearchBar.currentSearch.isBlank()) {
                if (isAreaHovered(backBtnX(), headerTop(), 34f, 34f, true)) {
                    setPage(Page.GroupPage(p.group))
                    scrollToAfterLayout = p.macro.area
                    return true
                }
                for (t in macroTabHits) if (isAreaHovered(t.x, mpY + 6f, t.w, 30f, true)) { t.onClick(); return true }
                if (isAreaHovered(startBtnX(), startBtnY(), START_W, START_H, true)) {
                    val m = p.macro
                    if (m.running) m.userStop()
                    else {
                        // close the GUI first: the macro (and the GUI failsafe) expect no screen open
                        onClose()
                        m.start()
                    }
                    return true
                }
            }
            for (t in tabs) if (isAreaHovered(t.x, winY, t.w, HEADER_H, true)) { t.onClick(); return true }
        }

        if (!mouseInView()) return super.mouseClicked(mouseButtonEvent, bl)

        // module rows: left-click toggles, right-click (or the dots) opens the settings page
        val right = mouseButtonEvent.button() == 1
        if (left || right) for (pl in placed) {
            val node = pl.node
            val x = viewX + pl.x
            val y = viewY + pl.y - scroll
            if (node is MacroNode) {
                if (isAreaHovered(x, y, pl.w, node.h, true)) { openMacro(node.macro); return true }
                continue
            }
            val n = node as? ModuleNode ?: continue
            if (!isAreaHovered(x, y, pl.w, n.h, true)) continue
            if (right) {
                if (hasSettings(n.module)) openModule(n.module, n.toggleable)
                return true
            }
            val g = rowGeom(n, x, pl.w)
            val cy = y + n.h / 2f
            when {
                g.pillX != null && isAreaHovered(g.pillX, cy - PILL_H / 2f, g.pillW, PILL_H, true) -> listeningKey = n.module.rowKeybind
                g.dotsX != null && isAreaHovered(g.dotsX, cy - 14f, 22f, 28f, true) -> openModule(n.module, n.toggleable)
                n.toggleable -> n.module.toggle()
                else -> openModule(n.module, n.toggleable)
            }
            return true
        }

        // setting cards -- every rendered one sees the click so focused text fields can let go of focus
        var handled = false
        for (s in renderedSettings) if (s.mouseClicked(mx, my, mouseButtonEvent)) handled = true
        return handled || super.mouseClicked(mouseButtonEvent, bl)
    }

    private fun openModule(m: Module, toggleable: Boolean) {
        val (g, t) = GuiLayout.groups.firstNotNullOfOrNull { g ->
            g.tabs.firstOrNull { t -> t.entries.any { it is GuiLayout.Entry.Mod && it.module === m } }?.let { g to it }
        } ?: return
        setPage(Page.ModulePage(m, toggleable, g, t))
    }

    private fun openMacro(m: Macro) {
        val g = GuiLayout.groups.firstOrNull { g ->
            g.tabs.any { t -> t.entries.any { it is GuiLayout.Entry.MacroRow && it.macro === m } }
        } ?: return
        setPage(Page.MacroPage(m, g))
    }

    override fun mouseReleased(mouseButtonEvent: MouseButtonEvent): Boolean {
        SearchBar.mouseReleased()
        for (s in renderedSettings) s.mouseReleased(mouseButtonEvent)
        return super.mouseReleased(mouseButtonEvent)
    }

    override fun charTyped(characterEvent: CharacterEvent): Boolean {
        if (listeningKey != null) return true
        if (SearchBar.keyTyped(characterEvent)) return true
        for (s in renderedSettings) if (s.keyTyped(characterEvent)) return true
        return super.charTyped(characterEvent)
    }

    override fun keyPressed(keyEvent: KeyEvent): Boolean {
        listeningKey?.let { k ->
            k.value = when (keyEvent.key) {
                GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_BACKSPACE -> InputConstants.UNKNOWN
                else -> InputConstants.getKey(keyEvent)
            }
            listeningKey = null
            return true
        }
        for (s in renderedSettings) if (s.keyPressed(keyEvent)) return true
        if (SearchBar.keyPressed(keyEvent)) return true
        return super.keyPressed(keyEvent)
    }

    override fun init() {
        isOpen = true
        if (ClickGUIModule.openAnimation) openAnim.start()
        lastFrameNanos = 0L
        super.init()
    }

    override fun removed() {
        isOpen = false
        super.removed()
    }

    override fun onClose() {
        listeningKey = null
        for (m in ModuleManager.modules.values) for (s in m.settings.values.filterIsInstance<RenderableSetting<*>>()) {
            if (s is ColorSetting) s.section = null
            s.listening = false
        }
        ModuleManager.saveConfigurations()
        super.onClose()
    }

    override fun isPauseScreen(): Boolean = false

    // ============================== LEGACY TOOLTIP ==============================

    private var desc = Description("", 0f, 0f, HoverHandler(150))

    /** Sets the description without creating a new data class which isn't optimal */
    fun setDescription(text: String, x: Float, y: Float, hoverHandler: HoverHandler) {
        desc.text = text
        desc.x = x
        desc.y = y
        desc.hoverHandler = hoverHandler
    }

    data class Description(var text: String, var x: Float, var y: Float, var hoverHandler: HoverHandler) {
        fun render() {
            if (text.isEmpty() || hoverHandler.percent() < 100) return
            val area = GGRenderer.wrappedTextBounds(text, 300f, 14f, null)
            val boxW = area[2] - area[0] + 16f
            val boxH = area[3] - area[1] + 16f
            // Clamped into the GUI-scaled canvas actually drawn into (see extractRenderState).
            val drawX = x.coerceIn(8f, (screenW - boxW - 8f).coerceAtLeast(8f))
            val drawY = y.coerceIn(8f, (screenH - boxH - 8f).coerceAtLeast(8f))
            GGRenderer.rect(drawX, drawY, boxW, boxH, Theme.tooltip, 8f)
            GGRenderer.drawWrappedString(text, drawX + 8f, drawY + 8f, 300f, 14f, Theme.text, null)
        }
    }

    val movementImage = GGRenderer.createImage("/assets/fish5/MovementIcon.svg")
    val hueImage = GGRenderer.createImage("/assets/fish5/HueGradient.png")
    val chevronImage = GGRenderer.createImage("/assets/fish5/chevron.svg")
}
