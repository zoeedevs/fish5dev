package com.fish5.hud

import com.fish5.clickgui.settings.impl.BooleanSetting
import com.fish5.clickgui.settings.impl.NumberSetting
import com.fish5.config.MacroConfig
import com.fish5.features.Category
import com.fish5.features.Macro
import com.fish5.features.MacroManager
import com.fish5.features.MacroSession
import com.fish5.features.Module
import com.fish5.features.impl.fishing.SeaCreatureTracker
import com.fish5.features.impl.render.ClickGUIModule
import com.fish5.session.RestScheduler
import com.fish5.utils.Color
import com.fish5.utils.ui.rendering.GGRenderer
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.resources.Identifier
import java.util.Locale

/**
 * Macro HUD: one panel for whichever macro is running. It shows the macro's name and what it is doing
 * ([Macro.hudStatus]), then the session stats from [MacroSession]: kills/hr (smoothed, see
 * [updateKph]), sea creatures killed + alive, session time, profit, next scheduled break, skill level
 * and time to the next one, and the most valuable drops.
 *
 * Nothing here is macro-specific: a new macro gets the panel for free and only overrides
 * [Macro.hudStatus]. The text is rebuilt at most every [REBUILD_MS]; frames only draw the cached strings.
 */
object MacroHud : Module(
    name = "Macro HUD",
    category = Category.RENDER,
    description = "Panel with the running macro's status and session stats.",
    toggled = true,
), HudElement {

    private val showItems by BooleanSetting("Top items", true, desc = "List the most valuable drops this session.")
    private val showForge by BooleanSetting("Forge timer", true,
        desc = "Show the Gemstone Chamber forge timer (tracked by Worm Helper): next chamber and when all are done.")
    private val showOnBreak by BooleanSetting("Show on break", true, desc = "Keep the panel up during a scheduled break.")
    private val warmupMinutes by NumberSetting<Int>("Kills/hr warm-up", 3, 0, 10, 1,
        desc = "Minutes of running before kills/hr counts as settled. Until then it shows greyed out as an estimate.", unit = "m")
    var hudXSetting by NumberSetting<Int>("HUD X", 8, 0, 1920, 1, desc = "HUD x position (GUI-scaled pixels).").hide()
    var hudYSetting by NumberSetting<Int>("HUD Y", 40, 0, 1080, 1, desc = "HUD y position (GUI-scaled pixels).").hide()

    override val hudLabel = "Macro HUD"
    override var hudX: Int
        get() = hudXSetting
        set(v) { hudXSetting = v }
    override var hudY: Int
        get() = hudYSetting
        set(v) { hudYSetting = v }
    override fun hudWidth() = W
    override fun hudHeight() = boxH

    private var registered = false

    fun initHud() {
        if (registered) return
        registered = true
        HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, Identifier.parse("fish5:macro_hud")) { graphics, _ ->
            if (enabled && mc.player != null && shownMacro() != null) {
                GGRenderer.frame(graphics) {
                    val scale = ClickGUIModule.getStandardGuiScale()
                    GGRenderer.scale(scale, scale)
                    draw(hudX.toFloat(), hudY.toFloat(), false)
                }
            }
        }
        // skill XP for the skill row (read-only, never changes the message)
        ClientReceiveMessageEvents.GAME.register(ClientReceiveMessageEvents.Game { message, overlay ->
            if (overlay && MacroSession.active) MacroSession.onActionBar(message.string)
        })
    }

    private fun shownMacro(): Macro? {
        MacroManager.running?.let { return it }
        return if (showOnBreak && RestScheduler.isOnBreak()) MacroSession.macro else null
    }

    override fun renderHudElement(editor: Boolean) = draw(hudX.toFloat(), hudY.toFloat(), editor)

    // -- cached text (rebuilt every REBUILD_MS) -----------------------------------------------------

    private const val REBUILD_MS = 250L
    private const val MAX_ITEMS_SHOWN = 3

    private var builtAt = 0L
    private var builtForEditor = false
    private var title = ""
    private var status = ""
    private var statusColor = 0
    private var kph = ""
    private var creatures = ""
    private var sessionTime = ""
    private var profit = ""
    private var nextBreak = ""
    private var skill = ""
    private var nextLevel = ""
    private var forgeNext = ""
    private var forgeAll = ""
    private var membranes = ""
    private var chambersReady = ""
    private var itemRows = 0
    private val itemName = arrayOfNulls<String>(MAX_ITEMS_SHOWN)
    private val itemCount = arrayOfNulls<String>(MAX_ITEMS_SHOWN)
    private val itemValue = arrayOfNulls<String>(MAX_ITEMS_SHOWN)
    private val topIdx = IntArray(MAX_ITEMS_SHOWN)

    private fun rebuild(editor: Boolean) {
        if (editor) { sample(); return }
        val m = shownMacro() ?: return
        title = m.name
        if (m.running) {
            status = m.hudStatus
            statusColor = TEXT
        } else {
            status = "On break, " + dur(RestScheduler.getBreakEndsAtMs() - System.currentTimeMillis()) + " left"
            statusColor = MUTED
        }
        updateKph()
        val alive = SeaCreatureTracker.count
        creatures = if (alive > 0) "${MacroSession.kills} +$alive" else MacroSession.kills.toString()
        sessionTime = dur(MacroSession.elapsedMs)
        profit = coins(MacroSession.coins) + " (" + coins(MacroSession.coinsPerHour) + "/h)"
        nextBreak = when {
            !MacroConfig.REST_ENABLED.get() -> "Off"
            RestScheduler.isOnBreak() -> "Now"
            RestScheduler.isBreakDue() -> "Soon"
            else -> dur(RestScheduler.getRemainingMacroMs())
        }
        val sk = MacroSession.skillName
        skill = when {
            sk == null -> "N/A"
            MacroSession.skillPercent < 0 -> sk
            else -> sk + (if (MacroSession.skillLevel > 0) " ${MacroSession.skillLevel}" else "") +
                " (" + String.format(Locale.US, "%.2f", MacroSession.skillPercent) + "%)"
        }
        val eta = MacroSession.msToNextLevel()
        nextLevel = if (eta < 0) "N/A" else dur(eta)
        if (showForge) {
            val (n, a) = com.fish5.features.impl.skyblock.WormfishProfitTracker.forgeHudText(::dur)
            forgeNext = n; forgeAll = a
            val sack = com.fish5.features.impl.skyblock.WormfishProfitTracker.sackMembranes
            membranes = if (sack < 0) "?" else num(sack)
            chambersReady = if (sack < 0) "?" else (sack / com.fish5.features.impl.skyblock.WormfishProfitTracker.MEMBRANE_PER_CHAMBER).toString()
        }
        itemRows =if (showItems) MacroSession.topItems(topIdx, MAX_ITEMS_SHOWN) else 0
        for (r in 0 until itemRows) {
            val i = topIdx[r]
            itemName[r] = MacroSession.itemName(i)
            itemCount[r] = "x" + num(MacroSession.itemCountAt(i))
            itemValue[r] = coins(MacroSession.itemValueAt(i))
        }
    }

    // -- kills/hr smoothing --------------------------------------------------------------------------
    //
    // Kills land in bursts (a whole batch of sea creatures dies at once), so the raw session average
    // jumps up on every batch and sags between them, and over the first few minutes it swings wildly.
    // Two things keep the number readable:
    //  - warm-up: for the first [warmupMinutes] of macro time the raw average is shown greyed out with
    //    a countdown, so it reads as an estimate, not a result;
    //  - after that, the shown value eases toward the session average instead of following it: it
    //    rises over ~[RISE_TAU_MS] and falls more slowly over ~[FALL_TAU_MS], so the gap between two
    //    batches doesn't read as a sudden drop. A real slowdown still shows within a few minutes.

    private const val RISE_TAU_MS = 30_000.0
    private const val FALL_TAU_MS = 120_000.0

    private var smoothKph = -1.0
    private var smoothAt = 0L
    private var lastElapsed = 0L
    private var kphSettled = false
    private var warmupLeft = ""

    private fun updateKph() {
        val now = System.currentTimeMillis()
        val elapsed = MacroSession.elapsedMs
        val raw = MacroSession.killsPerHour
        val warmupMs = warmupMinutes * 60_000L
        if (elapsed < lastElapsed) smoothKph = -1.0          // a new session started
        lastElapsed = elapsed
        if (elapsed < warmupMs || smoothKph < 0) {
            smoothKph = raw
        } else {
            val dt = (now - smoothAt).coerceIn(0L, 2_000L).toDouble()
            val tau = if (raw > smoothKph) RISE_TAU_MS else FALL_TAU_MS
            smoothKph += (raw - smoothKph) * (1.0 - kotlin.math.exp(-dt / tau))
        }
        smoothAt = now
        kphSettled = elapsed >= warmupMs
        warmupLeft = if (kphSettled) "" else "WARMING UP " + dur(warmupMs - elapsed)
        kph = num(Math.round(smoothKph))
    }

    /** Placeholder values so the panel can be positioned in the HUD editor. */
    private fun sample() {
        title = "Worm Fishing"; status = "Waiting for bite"; statusColor = TEXT
        kph = "1,069"; kphSettled = true; warmupLeft = ""
        creatures = "10 +8"; sessionTime = "1m 2s"
        profit = "362.42k (20.81M/h)"; nextBreak = "28m 14s"
        skill = "Fishing 44 (55.56%)"; nextLevel = "49m 54s"
        forgeNext = "1h 12m"; forgeAll = "3h 40m"
        membranes = "1,240"; chambersReady = "12"
        itemRows = if (showItems) 3 else 0
        itemName[0] = "Worm Membrane"; itemCount[0] = "x7"; itemValue[0] = "314.03k"
        itemName[1] = "Eternal Flame Ring"; itemCount[1] = "x1"; itemValue[1] = "40k"
        itemName[2] = "Gemstone Mixture"; itemCount[2] = "x1"; itemValue[2] = "8.39k"
    }

    // -- drawing ------------------------------------------------------------------------------------

    private const val W = 250f
    private const val PAD = 12f
    private var boxH = 220f

    private val BG = Color(27, 31, 34, 0.90f).rgba
    private val TEXT = Color(233, 238, 240).rgba
    private val LABEL = Color(140, 152, 158).rgba
    private val MUTED = Color(120, 130, 135).rgba

    private fun draw(x: Float, y: Float, editor: Boolean) {
        val now = System.currentTimeMillis()
        if (editor != builtForEditor || now - builtAt >= REBUILD_MS) {
            rebuild(editor)
            builtAt = now
            builtForEditor = editor
        }
        val accent = ClickGUIModule.clickGUIColor.rgba

        GGRenderer.rect(x, y, W, boxH, BG, 8f)

        // header: macro name, then what it is doing
        GGRenderer.text(title, x + PAD, y + PAD, 14f, accent)
        GGRenderer.text(status, x + PAD, y + PAD + 18f, 11f, statusColor)

        // kills / hr (greyed out with a countdown until the warm-up is over)
        var ry = y + PAD + 40f
        GGRenderer.text("KILLS / HR", x + PAD, ry, 8f, LABEL)
        if (warmupLeft.isNotEmpty()) GGRenderer.text(warmupLeft, x + W - PAD - GGRenderer.textWidth(warmupLeft, 8f), ry, 8f, LABEL)
        GGRenderer.text(kph, x + PAD, ry + 11f, 28f, if (kphSettled) TEXT else MUTED)
        ry += 46f

        // two-column stats
        ry = pair(x, ry, "SEA CREATURES", creatures, "SESSION TIME", sessionTime)
        ry = pair(x, ry, "PROFIT", profit, "NEXT BREAK", nextBreak)
        ry = pair(x, ry, "SKILL", skill, "NEXT LEVEL", nextLevel)
        if (showForge) {
            ry = pair(x, ry, "MEMBRANES IN SACK", membranes, "CHAMBERS' WORTH", chambersReady)
            ry = pair(x, ry, "FORGE NEXT", forgeNext, "FORGE ALL DONE", forgeAll)
        }

        if (itemRows > 0) {
            ry += 2f
            GGRenderer.text("TOP ITEMS", x + PAD, ry, 8f, LABEL)
            ry += 13f
            for (r in 0 until itemRows) {
                GGRenderer.circle(x + PAD + 3f, ry + 5f, 2.5f, accent)
                GGRenderer.text(itemName[r] ?: "", x + PAD + 11f, ry, 10.5f, TEXT)
                val v = itemValue[r] ?: ""
                val vw = GGRenderer.textWidth(v, 10.5f)
                GGRenderer.text(v, x + W - PAD - vw, ry, 10.5f, accent)
                val c = itemCount[r] ?: ""
                GGRenderer.text(c, x + W - PAD - vw - 8f - GGRenderer.textWidth(c, 10.5f), ry, 10.5f, LABEL)
                ry += 15f
            }
        }
        boxH = ry - y + PAD - 4f
    }

    private fun pair(x: Float, y: Float, l1: String, v1: String, l2: String, v2: String): Float {
        GGRenderer.text(l1, x + PAD, y, 8f, LABEL)
        GGRenderer.text(v1, x + PAD, y + 11f, 11f, TEXT)
        val rx = x + W - PAD
        GGRenderer.text(l2, rx - GGRenderer.textWidth(l2, 8f), y, 8f, LABEL)
        GGRenderer.text(v2, rx - GGRenderer.textWidth(v2, 11f), y + 11f, 11f, TEXT)
        return y + 30f
    }

    // -- formatting (only called from rebuild) ------------------------------------------------------

    private fun num(v: Long): String = String.format(Locale.US, "%,d", v)

    private fun coins(v: Double): String {
        val a = kotlin.math.abs(v)
        return when {
            a >= 1e9 -> trim(v / 1e9) + "B"
            a >= 1e6 -> trim(v / 1e6) + "M"
            a >= 1e3 -> trim(v / 1e3) + "k"
            else -> v.toLong().toString()
        }
    }

    private fun trim(v: Double): String = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    private fun dur(ms: Long): String {
        val s = ms.coerceAtLeast(0L) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${sec}s"
            else -> "${sec}s"
        }
    }
}
