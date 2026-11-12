package com.fish5.features.impl.fishing

import com.fish5.clickgui.settings.impl.BooleanSetting
import com.fish5.clickgui.settings.impl.NumberSetting
import com.fish5.features.Category
import com.fish5.features.Module
import com.fish5.features.impl.render.ClickGUIModule
import com.fish5.hud.HudElement
import com.fish5.sb.SkyblockData
import com.fish5.utils.Color
import com.fish5.utils.Colors
import com.fish5.utils.ui.rendering.GGRenderer
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.resources.Identifier

/**
 * Party Sea Creatures HUD: how many sea creatures you and each party member have alive, against the SC
 * threshold, plus when your oldest despawns. Party members come from /p list ([Party]); counts from
 * [SeaCreatureTracker], credited by whose bobber the creature came up at, so it works whatever the
 * others run. While enabled it keeps other players' creatures tracked and the party list fresh.
 */
object PartySeaCreatures : Module(
    name = "Party Sea Creatures",
    category = Category.MODULES,
    description = "HUD with your and each party member's live sea creature count, for party fishing.",
), HudElement {

    private val showUnknown by BooleanSetting(
        "Show unknown", true,
        desc = "Also list sea creatures nearby that couldn't be credited to anyone (no bobber close enough).",
    )
    private val onlyInParty by BooleanSetting(
        "Only in a party", true,
        desc = "Hide the HUD when you aren't in a party.",
    )
    var hudXSetting by NumberSetting<Int>("HUD X", 4, 0, 1920, 1, desc = "HUD x position (GUI-scaled pixels).").hide()
    var hudYSetting by NumberSetting<Int>("HUD Y", 200, 0, 1080, 1, desc = "HUD y position (GUI-scaled pixels).").hide()

    override val hudLabel = "Party Sea Creatures"
    override var hudX: Int
        get() = hudXSetting
        set(v) { hudXSetting = v }
    override var hudY: Int
        get() = hudYSetting
        set(v) { hudYSetting = v }

    private var boxW = 120f
    private var boxH = 40f
    override fun hudWidth() = boxW
    override fun hudHeight() = boxH

    private const val REBUILD_MS = 250L
    private const val PARTY_RETRY_MS = 60_000L

    // rebuilt at most every REBUILD_MS, not every frame
    private val lines = ArrayList<Pair<String, Int>>(10)
    private val counts = HashMap<String, Int>(8)
    private var builtAt = 0L
    private var nextPartyTry = 0L
    private var registered = false

    override fun onEnable() {
        nextPartyTry = 0L
    }

    /**
     * Client tick: keeps other players' creatures tracked + the party list auto-refreshed while enabled
     * (synced here, not in onEnable, so it also holds when config loads the module already on), and
     * reads /p list once per minute until the party is known (on SkyBlock only).
     */
    fun tick() {
        SeaCreatureTracker.want(SeaCreatureTracker.WANT_HUD, enabled)
        Party.wantAutoRefresh(Party.WANT_HUD, enabled)
        if (!enabled || Party.known || mc.player == null) return
        val now = System.currentTimeMillis()
        if (now < nextPartyTry || !SkyblockData.isOnSkyblock(mc)) return
        nextPartyTry = now + PARTY_RETRY_MS
        Party.refresh()
    }

    fun initHud() {
        if (registered) return
        registered = true
        HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, Identifier.parse("fish5:party_sea_creatures")) { graphics, _ ->
            if (enabled && mc.player != null && (!onlyInParty || Party.members.isNotEmpty())) {
                GGRenderer.frame(graphics) {
                    val scale = ClickGUIModule.getStandardGuiScale()
                    GGRenderer.scale(scale, scale)
                    draw(hudX.toFloat(), hudY.toFloat(), false)
                }
            }
        }
    }

    override fun renderHudElement(editor: Boolean) = draw(hudX.toFloat(), hudY.toFloat(), editor)

    private fun draw(x: Float, y: Float, editor: Boolean) {
        val now = System.currentTimeMillis()
        if (editor) {
            lines.clear()
            lines.add("You: 7/10 (4:12)" to Colors.WHITE.rgba)
            lines.add("The_Hunter1731: 10/10" to Colors.MINECRAFT_YELLOW.rgba)
            lines.add("Unknown: 2" to Colors.MINECRAFT_GRAY.rgba)
            builtAt = 0L
        } else if (now - builtAt >= REBUILD_MS) {
            build()
            builtAt = now
        }
        drawBox(x, y)
    }

    private fun build() {
        lines.clear()
        val threshold = Fishing.scThreshold
        val ours = SeaCreatureTracker.count
        val left = SeaCreatureTracker.msUntilFirstDespawn()
        val timer = if (left >= 0) " (${fmt(left)})" else ""
        lines.add("You: $ours/$threshold$timer" to (if (ours >= threshold) Colors.MINECRAFT_GREEN else Colors.WHITE).rgba)

        SeaCreatureTracker.otherCounts(counts)
        if (Party.members.isEmpty()) {
            lines.add((if (Party.known) "Not in a party" else "Party unknown (/p list)") to Colors.MINECRAFT_GRAY.rgba)
        } else {
            for (m in Party.members) {
                var n = 0
                for ((owner, c) in counts) if (owner.equals(m, true)) n += c
                lines.add("$m: $n/$threshold" to (if (n >= threshold) Colors.MINECRAFT_YELLOW else Colors.WHITE).rgba)
            }
        }
        if (showUnknown) {
            val unknown = counts[SeaCreatureTracker.UNKNOWN] ?: 0
            if (unknown > 0) lines.add("Unknown: $unknown" to Colors.MINECRAFT_GRAY.rgba)
        }
    }

    private fun fmt(ms: Long): String {
        val s = (ms.coerceAtLeast(0L) / 1000).toInt()
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    private val background = Color(18, 18, 18, 0.80f)

    private fun drawBox(x: Float, y: Float) {
        val font = GGRenderer.defaultFont
        val pad = 6f
        val titleSize = 13f
        val rowSize = 12f
        val rowH = 15f
        var maxW = GGRenderer.textWidth(hudLabel, titleSize, font)
        for ((text, _) in lines) maxW = maxOf(maxW, GGRenderer.textWidth(text, rowSize, font))
        val w = maxW + pad * 2 + 4f
        val h = pad * 2 + titleSize + 6f + lines.size * rowH
        GGRenderer.rect(x, y, w, h, background.rgba, 5f)
        GGRenderer.hollowRect(x, y, w, h, 1f, ClickGUIModule.clickGUIColor.rgba, 5f)
        GGRenderer.text(hudLabel, x + pad, y + pad, titleSize, Colors.WHITE.rgba, font)
        var ry = y + pad + titleSize + 6f
        for ((text, color) in lines) {
            GGRenderer.text(text, x + pad, ry, rowSize, color, font)
            ry += rowH
        }
        boxW = w
        boxH = h
    }
}
