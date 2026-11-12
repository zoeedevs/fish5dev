package com.fish5.features.impl.skyblock

import com.fish5.clickgui.settings.Setting.Companion.inGroup
import com.fish5.clickgui.settings.impl.ActionSetting
import com.fish5.clickgui.settings.impl.BooleanSetting
import com.fish5.clickgui.settings.impl.ColorSetting
import com.fish5.clickgui.settings.impl.GroupSetting
import com.fish5.clickgui.settings.impl.NumberSetting
import com.fish5.clickgui.settings.impl.StringSetting
import com.fish5.features.Category
import com.fish5.features.Module
import com.fish5.utils.Color

/**
 * Worm Helper: the Crystal Hollows worm-fishing tools that aren't part of the Worm Fishing macro, with
 * one command tree (`/f5 wormhelper ...`, also `/wormhelper ...`):
 *  - `find <lava|water> [auto] | find stop` -- [WormFinder] spot scanner / instance re-roller
 *  - `calc [lifetime]`, `report`, `reset`, `resetlifetime`, `debug` -- [WormfishProfitTracker]
 *
 * Forging, claiming and listing moved into the Worm Fishing macro (its Auto Mode, run by [WormAuto]).
 * Module enabled = live profit tracking (session/lifetime stats). The commands work whether or not it
 * is enabled. Settings keep the names they had on the old separate modules so [legacyNames] can carry
 * saved values (lifetime stats) across.
 */
object WormHelper : Module(
    name = "Worm Helper",
    category = Category.MODULES,
    description = "Worm fishing toolkit: spot finder (/f5 wormhelper find) and profit tracker + calc. " +
        "Auto forging lives in the Worm Fishing macro.",
) {
    override val legacyNames = listOf("Wormfish Profit Tracker", "Worm Finder")

    // Each area is one card (GroupSetting + inGroup children) so the page stays compact.

    // -- Spot finder -------------------------------------------------------------------------------

    private val scanCard = registerSetting(GroupSetting("Spot Finder", "How /f5 wormhelper find searches loaded chunks for pools."))
    val findAutoRenderDistance by BooleanSetting(
        "Max render distance", true,
        desc = "Bump render distance to Extreme (32 chunks) while a scan runs, restoring it when the scan finishes.",
    ).inGroup(scanCard)
    val findColumnsPerTick by NumberSetting<Int>(
        "Scan speed", 12, 1, 64, 1,
        desc = "Chunk columns scanned per client tick. Higher finishes the scan faster but can stutter.",
    ).inGroup(scanCard)
    val findEnforceMinY by BooleanSetting(
        "Require Y > 68", true,
        desc = "Only count pools above y=68 as valid spots. Turn off to scan the full height range.",
    ).inGroup(scanCard)
    val findForceLoadPrecursor by BooleanSetting(
        "Force-load Precursor Ruins", true,
        desc = "While scanning lava, re-centre the chunk cache onto the Precursor Ruins so it can be scanned from anywhere in the Hollows.",
    ).inGroup(scanCard)
    val findMaxSpotsPerPool by NumberSetting<Int>(
        "Max spots per pool", 3, 1, 10, 1,
        desc = "Highlight at most this many 2x1/1x2/2x2 rows inside one connected lava pool.",
    ).inGroup(scanCard)

    private val highlightCard = registerSetting(GroupSetting("Spot Highlights", "How found spots are shown."))
    val lavaColor by ColorSetting("Lava spot color", Color(255, 90, 20), allowAlpha = true, desc = "Cham color for lava fishing spots.").inGroup(highlightCard)
    val waterColor by ColorSetting("Water spot color", Color(40, 170, 255), allowAlpha = true, desc = "Cham color for water fishing spots.").inGroup(highlightCard)
    val findChatUpdates by BooleanSetting("Chat updates", true, desc = "Announce scan start/finish and spot counts in chat.").inGroup(highlightCard)

    // -- Profit tracker ----------------------------------------------------------------------------

    private val profitCard = registerSetting(GroupSetting("Profit Tracker", "Live coins/hr + lifetime stats while the module is enabled."))
    val priceRefreshMin by NumberSetting<Int>(
        "Price refresh", 10, 2, 60, 1, unit = " min",
        desc = "How often live prices (chamber 7d AH avg, mixture bazaar cost, NPC prices) are re-fetched.",
    ).inGroup(profitCard)
    val apiKey by StringSetting(
        "Hypixel API key", "", 40,
        desc = "developer.hypixel.net API key, used for exact sack counts. Leave blank to use the built-in key.",
    ).inGroup(profitCard)
    @Suppress("unused")
    private val resetAction by ActionSetting("Reset session", desc = "Zero the catch counts and coins total, and restart the coins/hr clock.") {
        WormfishProfitTracker.resetSessionCommand()
    }.inGroup(profitCard)
    @Suppress("unused")
    private val resetLifetimeAction by ActionSetting("Reset lifetime", desc = "Zero lifetime + session stats, then restart lifetime membranes from what's in your sack right now.") {
        WormfishProfitTracker.resetLifetimeCommand()
    }.inGroup(profitCard)

    // -- Persisted state (hidden) ------------------------------------------------------------------

    var lifetimeCoins: Double by NumberSetting<Double>("Lifetime coins", 0.0, 0.0, 1.0e15, 1.0, desc = "Total coins earned from worm fishing, all sessions combined.").hide()
    var lifetimeRings: Int by NumberSetting<Int>("Lifetime rings", 0, 0, Int.MAX_VALUE, 1, desc = "Total Eternal Flame Rings caught, all sessions combined.").hide()
    var lifetimeMembranes: Int by NumberSetting<Int>("Lifetime membranes", 0, 0, Int.MAX_VALUE, 1, desc = "Total Worm Membranes caught, all sessions combined.").hide()

    override fun onEnable() = WormfishProfitTracker.onEnabled()
    override fun onDisable() = WormfishProfitTracker.onDisabled()
}
