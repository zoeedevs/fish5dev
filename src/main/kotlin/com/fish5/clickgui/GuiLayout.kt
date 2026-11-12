package com.fish5.clickgui

import com.fish5.clickgui.settings.RenderableSetting
import com.fish5.clickgui.settings.impl.ActionSetting
import com.fish5.features.Macro
import com.fish5.features.MacroManager
import com.fish5.features.MacroSettings
import com.fish5.features.Module
import com.fish5.features.impl.misc.UngrabMouse
import com.fish5.features.impl.opsec.ProxyManager
import com.fish5.features.impl.render.CustomSwingSpeed
import com.fish5.features.impl.render.FreecamModule
import com.fish5.features.impl.render.FreelookModule
import com.fish5.features.impl.render.Fullbright
import com.fish5.features.impl.render.HeldItem
import com.fish5.features.impl.render.PlayerEsp
import com.fish5.features.impl.render.PlayerSize
import com.fish5.features.ModuleManager
import com.fish5.features.impl.failsafe.BreakSettings
import com.fish5.features.impl.failsafe.FailsafeSettings
import com.fish5.features.impl.fishing.PartySeaCreatures
import com.fish5.features.impl.failsafe.GuiOpenedFailsafeModule
import com.fish5.features.impl.failsafe.InventorySlotFailsafeModule
import com.fish5.features.impl.failsafe.RotationFailsafeModule
import com.fish5.features.impl.failsafe.WorldChangeFailsafeModule
import com.fish5.features.impl.fishing.Fishing
import com.fish5.features.impl.misc.ExploitPreventer
import com.fish5.features.impl.render.ClickGUIModule
import com.fish5.features.impl.skyblock.WormHelper
import com.fish5.utils.ui.rendering.GGRenderer
import com.fish5.utils.ui.rendering.Image

/**
 * Where every feature lives in the click GUI: rail group -> top tab -> entries.
 * Purely presentation -- modules keep their own [com.fish5.features.Category] for config /
 * back-compat. Any registered module not placed here lands in Misc > Other automatically, so a new
 * feature can never go missing from the GUI.
 */
object GuiLayout {

    sealed class Entry {
        class Mod(val module: Module, val toggleable: Boolean = true) : Entry()
        class Settings(val provider: () -> List<RenderableSetting<*>>) : Entry()
        /** A macro row: name, status dot, description and a gear; no switch or key. Opens the macro page. */
        class MacroRow(val macro: Macro) : Entry()
    }

    class Tab(val name: String, val entries: List<Entry>)
    /** [dividerBefore]: the rail draws a separator line above this group's button. */
    class Group(val id: String, val label: String, val icon: Image, val tabs: List<Tab>, val dividerBefore: Boolean = false)

    private fun icon(name: String) = GGRenderer.createImage("/assets/fish5/icons/$name.svg")

    val iconSearch = icon("search")
    val iconBack = icon("back")
    val iconDots = icon("dots")
    val iconEdit = icon("layout")
    val iconUser = icon("user")
    val iconDiscord = icon("discord")
    val iconGear = icon("gear")

    /** A standalone button card (not registered on any module) for the HUD > HUD Editor tab. */
    private val openHudEditor = ActionSetting("Open HUD Editor", "Drag and resize every HUD element: bars, overlays and trackers.") {
        ClickGUI.openHudEditor()
    }

    private fun guiSettings(): List<RenderableSetting<*>> =
        ClickGUIModule.settings.values.filterIsInstance<RenderableSetting<*>>()
            .filter { it.isVisible && it.name in ClickGUIModule.GUI_SETTING_NAMES && it.name !in ClickGUIModule.RENDER_SETTING_NAMES }

    /** General > Rendering: the visual-quality toggles (Supersampling, Smooth Font), grouped together. */
    private fun renderSettings(): List<RenderableSetting<*>> =
        ClickGUIModule.settings.values.filterIsInstance<RenderableSetting<*>>()
            .filter { it.isVisible && it.name in ClickGUIModule.RENDER_SETTING_NAMES }

    /** Everything on the Theme page (player-head button): the Theme module's non-GUI settings. */
    fun themeSettings(): List<RenderableSetting<*>> =
        ClickGUIModule.settings.values.filterIsInstance<RenderableSetting<*>>()
            .filter { it.isVisible && it.name !in ClickGUIModule.GUI_SETTING_NAMES }

    private fun mods(vararg m: Module): List<Entry> = m.map { Entry.Mod(it) }

    val groups: List<Group> by lazy {
        // rail order: General (the page the GUI opens on), HUD | Macros, Fishing, Failsafes | QOL, Visuals, Misc
        val base = listOf(
            Group("general", "General", icon("sliders"), listOf(
                Tab("GUI", listOf(Entry.Settings(::guiSettings))),
                Tab("Rendering", listOf(Entry.Settings(::renderSettings))),
            )),
            Group("hud", "HUD", icon("grid"), listOf(
                Tab("HUD Editor", listOf(Entry.Settings { listOf(openHudEditor) })),
                Tab("Macro HUD", mods(com.fish5.hud.MacroHud)),
            )),
            // the one keybind (General Macro Toggle), then one tab per area with its macro rows
            Group("macros", "Macros", icon("play"),
                listOf(Tab("General", listOf(Entry.Settings(MacroSettings::generalSettings)))) +
                    MacroManager.macros.groupBy { it.area }.map { (area, ms) -> Tab(area, ms.map { Entry.MacroRow(it) }) },
                dividerBefore = true,
            ),
            // shared fishing settings straight on the page (no module card), then the worm tools
            Group("fishing", "Fishing", icon("fish"), listOf(
                Tab("Pond Selection", listOf(Entry.Settings(Fishing::pondSettings))),
                Tab("Aim", listOf(Entry.Settings(Fishing::aimSettings))),
                Tab("Sea Creatures", listOf(Entry.Settings(Fishing::seaCreatureSettings))),
                Tab("Delays", listOf(Entry.Settings(Fishing::delaySettings))),
                Tab("Worm Helper", mods(WormHelper)),
                Tab("Party Sea Creatures", mods(PartySeaCreatures)),
            )),
            // like aether's Failsafes tab: shared settings, then one toggleable page per failsafe
            Group("failsafes", "Failsafes", icon("shield"), listOf(
                Tab("General", listOf(Entry.Mod(FailsafeSettings, toggleable = false))),
                Tab("Rotation", mods(RotationFailsafeModule)),
                Tab("Inventory Slot", mods(InventorySlotFailsafeModule)),
                Tab("GUI Opened", mods(GuiOpenedFailsafeModule)),
                Tab("World Change", mods(WorldChangeFailsafeModule)),
                Tab("Breaks", mods(BreakSettings)),
            )),
            Group("qol", "QOL", icon("star"), listOf(
                Tab("Auto Sell", mods(com.fish5.features.impl.misc.AutoSell)),
                Tab("Ungrab Mouse", mods(UngrabMouse)),
            ), dividerBefore = true),
            Group("visuals", "Visuals", icon("eye"), listOf(
                Tab("Camera", mods(FreecamModule, FreelookModule, Fullbright)),
                Tab("Player", mods(PlayerEsp, PlayerSize)),
                Tab("Item", mods(HeldItem, CustomSwingSpeed)),
            )),
            Group("misc", "Misc", icon("layers"), listOf(
                Tab("Exploit Preventer", mods(ExploitPreventer)),
            )),
        )

        // Safety net: anything registered but not placed above (a newly added module) -> Misc > Other.
        // Theme is the player-head page.
        val placed = base.flatMap { g -> g.tabs.flatMap { t -> t.entries.filterIsInstance<Entry.Mod>().map { it.module } } }.toSet()
        // Fishing is a settings holder shown via Entry.Settings above, not a module row; ProxyManager is
        // only edited from the vanilla Proxy screen (registered just so its settings persist)
        val leftovers = ModuleManager.modules.values.filter {
            it !in placed && it !== ClickGUIModule && it !== Fishing && it !is Macro && it !== MacroSettings &&
                it !== ProxyManager
        }
        if (leftovers.isEmpty()) base.filter { it.tabs.isNotEmpty() }
        else base.map { g -> if (g.id != "misc") g else Group(g.id, g.label, g.icon, g.tabs + Tab("Other", mods(*leftovers.toTypedArray()))) }
    }
}
