package com.fish5.features.impl.fishing

import com.fish5.Fish5.mc
import net.minecraft.util.Mth

/**
 * Measures how far our bobber really slides on the fluid compared with vanilla 1.8 physics, and keeps
 * a scale for [CastPredictor]'s slide (1 = vanilla, 0 = stops where it lands). Hypixel's float doesn't
 * have to match vanilla, so it's measured as it happens.
 *
 * Per planned cast ([CastAim.lastPlanned], so casts by hand are ignored): once the bobber is in the
 * fluid and stops moving sideways, sample = actual slide along the cast / predicted slide. Only casts
 * predicted to slide a fair bit count (a near-vertical lob says nothing about the scale). Samples go
 * into a running average saved in [Fishing]. Ticked every client tick; nothing to do while no bobber
 * is out.
 */
object BobberSlide {

    const val MAX_SCALE = 1.5
    // never 0: with no predicted slide no cast would qualify to learn from again
    private const val MIN_SCALE = 0.1

    private const val MIN_PREDICTED = 0.25       // predicted slide (blocks) needed to learn from a cast
    private const val PLAN_MAX_AGE_MS = 6000L    // a plan this recent belongs to the cast that just spawned
    private const val STILL_EPS = 0.004          // sideways move per tick that counts as stopped
    private const val STILL_TICKS = 4
    private const val MAX_TICKS = 100            // give up if it never settles (bite, reeled)
    private const val MAX_SIDEWAYS = 1.0         // further off the cast line than this: something else moved it
    private const val ALPHA = 0.4                // weight of a new sample in the running average

    private var hookId = Int.MIN_VALUE
    private var plan: CastAim.Plan? = null
    private var consumed: CastAim.Plan? = null
    private var prevX = 0.0; private var prevZ = 0.0
    private var landed = false
    private var done = true
    private var still = 0
    private var ticksSinceLand = 0

    /** Slide scale used by [CastPredictor] (1 = vanilla 1.8 slide). */
    @JvmStatic
    fun scale(): Double = Fishing.slideScale

    fun tick() {
        val hook = mc.player?.fishing
        if (hook == null) { hookId = Int.MIN_VALUE; return }
        val x = hook.x; val z = hook.z
        if (hook.id != hookId) {
            hookId = hook.id
            landed = false; still = 0; ticksSinceLand = 0
            val p = CastAim.lastPlanned
            plan = if (p != null && p !== consumed && System.currentTimeMillis() - CastAim.lastPlannedAt < PLAN_MAX_AGE_MS) p else null
            if (plan != null) consumed = plan
            done = plan == null
            prevX = x; prevZ = z
            return
        }
        if (done) return

        if (!landed) {
            landed = hook.isInLava || hook.isInWater
        } else {
            ticksSinceLand++
            val dx = x - prevX; val dz = z - prevZ
            still = if (dx * dx + dz * dz < STILL_EPS * STILL_EPS) still + 1 else 0
            if (still >= STILL_TICKS) { done = true; record(x, z) }
            else if (ticksSinceLand > MAX_TICKS) done = true
        }
        prevX = x; prevZ = z
    }

    private fun record(restX: Double, restZ: Double) {
        val p = plan ?: return
        val predicted = Math.hypot(p.lx - p.landX, p.lz - p.landZ)
        if (predicted < MIN_PREDICTED) return
        val yr = (p.yaw * Mth.DEG_TO_RAD).toDouble()
        val dirX = -Math.sin(yr); val dirZ = Math.cos(yr)
        val ox = restX - p.landX; val oz = restZ - p.landZ
        if (Math.abs(ox * dirZ - oz * dirX) > MAX_SIDEWAYS) return
        // the predicted slide was made with the current scale; un-scale it to get the vanilla slide
        val vanilla = predicted / Math.max(scale(), 0.05)
        val sample = ((ox * dirX + oz * dirZ) / vanilla).coerceIn(MIN_SCALE, MAX_SCALE)
        Fishing.slideScale = if (!Fishing.slideScaleMeasured) sample
            else Fishing.slideScale * (1 - ALPHA) + sample * ALPHA
        Fishing.slideScaleMeasured = true
    }
}
