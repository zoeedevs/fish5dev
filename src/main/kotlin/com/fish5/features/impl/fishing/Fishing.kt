package com.fish5.features.impl.fishing

import com.fish5.automation.Msg
import com.fish5.clickgui.settings.RenderableSetting
import com.fish5.clickgui.settings.Setting.Companion.withDependency
import com.fish5.clickgui.settings.impl.BooleanSetting
import com.fish5.clickgui.settings.impl.ColorSetting
import com.fish5.clickgui.settings.impl.NumberSetting
import com.fish5.clickgui.settings.impl.RangeSetting
import com.fish5.clickgui.settings.impl.SelectorSetting
import com.fish5.config.ConfigHelpers
import com.fish5.features.Category
import com.fish5.features.Module
import com.fish5.features.impl.fishing.PondFinder.FluidKind
import com.fish5.rotation.RotationManager
import com.fish5.utils.Color
import net.minecraft.core.BlockPos
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.util.ARGB
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult

/**
 * Shared fishing settings shown directly in the Fishing tab (no module card): where to cast, how to
 * aim, and the sea creature limits. Every fishing macro (Worm Fishing first) reads these. Not a
 * toggle; it's a settings holder registered with ModuleManager so the values are saved.
 */
object Fishing : Module(
    name = "Fishing",
    category = Category.MODULES,
    description = "Shared fishing settings: pond selection, aim and sea creature limits.",
    toggled = true,
) {

    // -- Pond selection ----------------------------------------------------------------------------

    private val pondMode = registerSetting(SelectorSetting(
        "Pond selection", "Automatic", listOf("Automatic", "Player rotation", "Looking point"),
        desc = "Automatic: the closest lava/water block you can cast to. Player rotation: the one closest to where you look. " +
            "Looking point: the exact spot you look at. Never the exact centre while Randomize aim is on.",
    ))
    private val fluid = registerSetting(SelectorSetting("Fluid", "Lava", listOf("Lava", "Water"), desc = "Which fluid to fish in."))
    private val highlightFluid = registerSetting(BooleanSetting(
        "Highlight fluid", true, desc = "While a fishing macro runs, box the fluid block it's fishing in (cyan by default).",
    ))
    private val fluidColor = registerSetting(ColorSetting(
        "Fluid highlight color", Color(63, 215, 224, 89 / 255f), allowAlpha = true,
        desc = "Color of the fished-block box. The outline is drawn solid; alpha sets the fill.",
    ).withDependency { highlightFluid.value })
    private val highlightStand = registerSetting(BooleanSetting(
        "Highlight stand block", true, desc = "While a fishing macro runs, fill the block you're standing on (magenta by default).",
    ))
    private val standColor = registerSetting(ColorSetting(
        "Stand highlight color", Color(192, 75, 208, 178 / 255f), allowAlpha = true,
        desc = "Fill color of the block you stand on.",
    ).withDependency { highlightStand.value })

    // -- Aim ---------------------------------------------------------------------------------------

    private val randomizeAimSetting = registerSetting(BooleanSetting(
        "Randomize aim", true,
        desc = "Cast to a random off-centre spot each time instead of the exact same block centre / your exact crosshair.",
    ))
    // renamed from "Aim offset"/"Aim jitter" (blocks) on purpose so old saved values don't carry over
    private val aimOffsetSetting = registerSetting(NumberSetting<Double>(
        "Anti-AFK move", 3.0, 1.0, 3.0, 1.0, unit = " px",
        desc = "Each cast aims at a random point 1 pixel up to this many pixels (1/16 block) from the block's centre.",
    ).withDependency { randomizeAimSetting.value })

    // -- Slide scale (hidden, measured by BobberSlide from our own casts) ----------------------------

    private val slideScaleSetting = registerSetting(NumberSetting<Double>(
        "Slide scale", 1.0, 0.0, BobberSlide.MAX_SCALE, 0.001,
        desc = "How far the bobber slides on the fluid compared with vanilla, learned from your casts.",
    )).also { it.hide() }
    private val slideScaleMeasuredSetting = registerSetting(BooleanSetting("Slide scale measured", false, desc = "")).also { it.hide() }

    var slideScale: Double
        get() = slideScaleSetting.value
        set(v) { slideScaleSetting.value = v }
    var slideScaleMeasured: Boolean
        get() = slideScaleMeasuredSetting.value
        set(v) { slideScaleMeasuredSetting.value = v }

    // -- Sea creatures ------------------------------------------------------------------------------

    private val scThresholdSetting = registerSetting(NumberSetting<Double>(
        "SC threshold", 10.0, 1.0, 10.0, 1.0,
        desc = "Kill once this many of your sea creatures are alive.",
    ))
    private val despawnTimerSetting = registerSetting(BooleanSetting(
        "Despawn timer", true,
        desc = "Kill anyway once your oldest sea creature has been alive this long, even under the threshold " +
            "(they despawn at 6 minutes).",
    ))
    private val killAfterSetting = registerSetting(NumberSetting<Double>(
        "Kill after", 300.0, 10.0, 300.0, 5.0, unit = " s",
        desc = "How old your oldest sea creature may get before killing. 300 s (SkyHanni's default) leaves a minute before despawn.",
    ).withDependency { despawnTimerSetting.value })

    // -- Delays (random in each range) -------------------------------------------------------------

    private val attackStepDelay = registerSetting(RangeSetting(
        "Attack step delay", 10, 80, 0, 500, 5, unit = " ms",
        desc = "Gap between each step of an attack: reel in -> swap to the weapon -> aim, and re-aiming at a moved target.",
    ))
    private val killClickDelay = registerSetting(RangeSetting(
        "Kill click delay", 20, 80, 0, 500, 5, unit = " ms",
        desc = "Wait before each weapon use-click of a hit or kill (it still waits for the last hit to land first).",
    ))
    private val rodRecastDelay = registerSetting(RangeSetting(
        "Rod recast delay", 10, 30, 0, 500, 5, unit = " ms",
        desc = "After swapping back to the rod, wait this long before recasting.",
    ))

    // -- What the Fishing tab shows (one section per list) -----------------------------------------

    fun pondSettings(): List<RenderableSetting<*>> = visible(pondMode, fluid, highlightFluid, fluidColor, highlightStand, standColor)
    fun aimSettings(): List<RenderableSetting<*>> = visible(randomizeAimSetting, aimOffsetSetting)
    fun seaCreatureSettings(): List<RenderableSetting<*>> = visible(scThresholdSetting, despawnTimerSetting, killAfterSetting)
    fun delaySettings(): List<RenderableSetting<*>> = visible(attackStepDelay, killClickDelay, rodRecastDelay)
    private fun visible(vararg s: RenderableSetting<*>) = s.filter { it.isVisible }

    // -- Getters for the macros --------------------------------------------------------------------

    val aimMode get() = CastAim.Mode.entries[pondMode.value]
    val fluidKind get() = if (fluid.value == 1) FluidKind.WATER else FluidKind.LAVA
    val randomizeAim get() = randomizeAimSetting.value
    /** Max anti-AFK aim step in blocks (the setting is in 1/16-block pixels). */
    val aimOffset get() = aimOffsetSetting.value / 16.0
    val scThreshold get() = scThresholdSetting.value.toInt()
    val despawnTimer get() = despawnTimerSetting.value
    /** Kill once our oldest sea creature is this old (ms). */
    val killAfterMs get() = (killAfterSetting.value * 1000).toLong()
    /** Random delays (ms) drawn from the Delays ranges. */
    fun attackStepMs() = ConfigHelpers.getRandomizedDelay(attackStepDelay.low, attackStepDelay.high)
    fun killClickMs() = ConfigHelpers.getRandomizedDelay(killClickDelay.low, killClickDelay.high)
    fun rodRecastMs() = ConfigHelpers.getRandomizedDelay(rodRecastDelay.low, rodRecastDelay.high)

    // -- Highlights while a macro fishes -----------------------------------------------------------
    //
    // Like the Macro HUD mockup: a cyan box on the block being fished (the cast target, which CastAim
    // keeps locked from cast to cast) and a magenta box on the block underfoot. Emitted every rendered
    // frame from LevelRendererEspMixin without persistence, so each frame draws exactly one of each
    // (persisted gizmos re-armed every tick would stack their fill alpha).

    // rebuilt only when the color setting changes, so frames don't allocate
    private var fishStyleRgba = 0
    private var fishStyle: GizmoStyle? = null
    private var standStyleRgba = 0
    private var standStyle: GizmoStyle? = null

    private fun fishStyle(): GizmoStyle {
        val c = fluidColor.value.rgba
        val cached = fishStyle
        if (cached != null && c == fishStyleRgba) return cached
        fishStyleRgba = c
        return GizmoStyle.strokeAndFill(c or (0xFF shl 24), 2f, c).also { fishStyle = it }
    }

    private fun standStyle(): GizmoStyle {
        val c = standColor.value.rgba
        val cached = standStyle
        if (cached != null && c == standStyleRgba) return cached
        standStyleRgba = c
        return GizmoStyle.fill(c).also { standStyle = it }
    }
    private const val INFLATE = 0.002  // just outside the block faces, so the fill doesn't z-fight them

    /** Called once per rendered frame (LevelRendererEspMixin). Two cuboids at most; nothing unless a macro runs. */
    @JvmStatic
    fun emitHighlights() {
        if (com.fish5.features.MacroManager.running == null) return
        val wantFish = highlightFluid.value
        val wantStand = highlightStand.value
        if (!wantFish && !wantStand) return
        val p = mc.player ?: return
        val plan = if (wantFish) CastAim.lastPlanned else null
        if (plan == null && !(wantStand && p.onGround())) return
        val col = mc.levelRenderer.collectPerFrameRenderThreadGizmos()
        try {
            if (plan != null) {
                val bx = Math.floor(plan.tx); val by = Math.floor(plan.ty - 0.01); val bz = Math.floor(plan.tz)
                Gizmos.cuboid(AABB(bx - INFLATE, by - INFLATE, bz - INFLATE, bx + 1 + INFLATE, by + 1 + INFLATE, bz + 1 + INFLATE), fishStyle())
            }
            if (wantStand && p.onGround()) {
                val b = p.onPos
                Gizmos.cuboid(AABB(b.x - INFLATE, b.y - INFLATE, b.z - INFLATE, b.x + 1 + INFLATE, b.y + 1 + INFLATE, b.z + 1 + INFLATE), standStyle())
            }
        } finally {
            col.close()
        }
    }

    // -- TEMP TEST: /f5 aim (remove once the cast loop uses CastAim) -------------------------------

    private const val SHOW_MS = 5000
    private const val MAX_OUTLINE_CELLS = 600

    fun testAim() {
        val p = mc.player ?: return
        val level = mc.level ?: return
        val kind = fluidKind

        // what's under the crosshair, and whether it counts as fishable surface
        val eye = p.eyePosition
        val look = level.clip(ClipContext(eye, eye.add(p.getViewVector(1f).scale(64.0)),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, p))
        if (look.type == HitResult.Type.BLOCK) {
            Msg.chat("§b[Fishing test] §7looking at: §f${PondFinder.describe(level, look.blockPos, kind)}")
        }

        val start = System.nanoTime()
        val plan = CastAim.plan(p, level, aimMode, kind, randomizeAim, aimOffset)
        val ms = (System.nanoTime() - start) / 1_000_000.0
        val mode = aimMode.name.lowercase().replace('_', ' ')
        val cells = CastAim.lastCells
        val why = "§8tried ${CastAim.tried}; rejected ${CastAim.rejectedOutOfRange} out of range, " +
            "${CastAim.rejectedBlocked} blocked, ${CastAim.rejectedMissed} landed elsewhere"
        if (aimMode != CastAim.Mode.LOOKING_POINT) {
            Msg.chat("§b[Fishing test] §7$mode: §f${cells.size}§7 ${kind.name.lowercase()} surface block(s) found" +
                if (PondFinder.lastScanCapped) " §e(scan hit its cap)" else "")
        }
        drawCells(level, cells)
        if (aimMode != CastAim.Mode.LOOKING_POINT && cells.isNotEmpty()) {
            // the block the mode ranked best (magenta), whether or not the cast ended up there
            val b = CastAim.lastBest
            val bx = BlockPos.getX(b); val by = BlockPos.getY(b); val bz = BlockPos.getZ(b)
            val dist = Math.sqrt(CastAim.eyeDistSq(p, level, b))
            val label = if (aimMode == CastAim.Mode.AUTOMATIC) "nearest" else "closest to crosshair"
            val note = if (randomizeAim && CastAim.lastBestReason == "not tried") "randomize picked a block next to it" else CastAim.lastBestReason
            Msg.chat("§b[Fishing test] §d$label§7 block §f${String.format("%.1f", dist)}m§7 from your eyes: §f$note" +
                if (CastAim.lastRank > 1) " §7(cast goes to rank #${CastAim.lastRank})" else "")
            val col = mc.collectPerTickGizmos()
            try {
                Gizmos.cuboid(AABB(bx.toDouble(), by.toDouble(), bz.toDouble(), bx + 1.0, by + 1.0, bz + 1.0),
                    GizmoStyle.stroke(ARGB.color(255, 255, 60, 255), 2.5f)).setAlwaysOnTop().persistForMillis(SHOW_MS)
            } finally {
                col.close()
            }
        }
        if (plan == null) {
            Msg.chat("§b[Fishing test] §cNo cast: ${CastAim.lastError} §8(${String.format("%.1f", ms)} ms)")
            Msg.chat("§b[Fishing test] $why")
            return
        }
        val dx = plan.tx - p.x; val dz = plan.tz - p.z
        Msg.chat(
            "§b[Fishing test] §7target §f${String.format("%.1f", Math.sqrt(dx * dx + dz * dz))}m§7 away, " +
                "yaw §f${String.format("%.1f", plan.yaw)}§7 pitch §f${String.format("%.1f", plan.pitch)}§7" +
                (if (plan.lob) " §d(lob)§7, " else ", ") +
                "sim settles §a${String.format("%.2f", plan.error)}§7 off in §f${plan.ticks}t §8(${String.format("%.1f", ms)} ms, slide x${String.format("%.2f", slideScale)}" +
                    "${if (slideScaleMeasured) "" else " vanilla"}${if (CastAim.lastFallback) ", closest-to-centre fallback" else ""})",
        )
        if (CastAim.tried > 1) Msg.chat("§b[Fishing test] $why")
        RotationManager.rotateToYawPitch(mc, plan.yaw, plan.pitch, ConfigHelpers.getRandomizedDelay(250, 450).toLong())

        // green = centre (always the middle), yellow = the offset area around it, white = where this cast lands
        val col = mc.collectPerTickGizmos()
        try {
            val isLook = aimMode == CastAim.Mode.LOOKING_POINT
            val cx = if (isLook) plan.tx else CastAim.lastCentreX
            val cy = if (isLook) plan.ty else CastAim.lastCentreY
            val cz = if (isLook) plan.tz else CastAim.lastCentreZ
            if (randomizeAim) {
                val r = if (isLook) aimOffset else 0.2  // CastAim keeps the aim in the block's middle 0.4 x 0.4
                Gizmos.cuboid(AABB(cx - r, cy, cz - r, cx + r, cy + 0.03, cz + r),
                    GizmoStyle.strokeAndFill(ARGB.color(255, 255, 220, 40), 2f, ARGB.color(50, 255, 220, 40))).setAlwaysOnTop().persistForMillis(SHOW_MS)
            }
            Gizmos.cuboid(AABB(cx - 0.12, cy, cz - 0.12, cx + 0.12, cy + 0.08, cz + 0.12),
                GizmoStyle.strokeAndFill(ARGB.color(255, 60, 255, 90), 2f, ARGB.color(140, 60, 255, 90))).setAlwaysOnTop().persistForMillis(SHOW_MS)
            Gizmos.cuboid(AABB(plan.lx - 0.08, plan.ly, plan.lz - 0.08, plan.lx + 0.08, plan.ly + 0.12, plan.lz + 0.08),
                GizmoStyle.strokeAndFill(ARGB.color(255, 255, 255, 255), 1.5f, ARGB.color(160, 255, 255, 255))).setAlwaysOnTop().persistForMillis(SHOW_MS)
        } finally {
            col.close()
        }
    }

    // every block counted as fishable surface (cyan), so gaps in a pool are obvious
    private fun drawCells(level: net.minecraft.world.level.Level, cells: it.unimi.dsi.fastutil.longs.LongOpenHashSet) {
        if (cells.isEmpty()) return
        val col = mc.collectPerTickGizmos()
        try {
            val style = GizmoStyle.stroke(ARGB.color(200, 60, 220, 255), 1.0f)
            val pos = BlockPos.MutableBlockPos()
            var drawn = 0
            val it = cells.iterator()
            while (it.hasNext() && drawn++ < MAX_OUTLINE_CELLS) {
                val c = it.nextLong()
                pos.set(BlockPos.getX(c), BlockPos.getY(c), BlockPos.getZ(c))
                val top = pos.y + level.getFluidState(pos).getHeight(level, pos).toDouble()
                Gizmos.cuboid(AABB(pos.x + 0.06, top, pos.z + 0.06, pos.x + 0.94, top + 0.02, pos.z + 0.94), style)
                    .setAlwaysOnTop().persistForMillis(SHOW_MS)
            }
        } finally {
            col.close()
        }
    }
}
