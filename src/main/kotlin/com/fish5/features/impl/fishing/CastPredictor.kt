package com.fish5.features.impl.fishing

import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.Direction
import net.minecraft.tags.FluidTags
import net.minecraft.util.Mth
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * Predicts where a fishing-rod cast lands by replaying the bobber's flight tick by tick, the way the
 * Hypixel server simulates it. Each tick's motion is one [Level.clip] against block colliders and
 * fluid surfaces, so the first fluid the bobber touches is the landing spot.
 *
 * Hypixel runs 1.8 server logic (EntityFishHook): cast = unit look vector * 1.5, starting at
 * eye - 0.1 and 0.16 to the side; each tick it moves, then gravity -0.04, then drag 0.92. The cast's
 * random spread is ignored (mean values), so expect a small per-cast error.
 *
 * Any block hit ends the flight: in 1.8 a bobber whose ray hits a block sticks there (inGround), so
 * a wall or ceiling hit is a [Landing.BLOCK] (a stuck cast), just like landing on a block's top.
 *
 * Fluid doesn't stop the bobber: it keeps its horizontal speed and slides across the surface
 * (EntityFishHook drag 0.92, x0.9 once in the fluid) until it settles or sticks to a pool wall,
 * scaled by how far Hypixel's slide really goes compared with vanilla ([BobberSlide], measured). That
 * resting point ([Result.restX]/[Result.restZ]) is what [CastAim] aims at.
 */
object CastPredictor {

    enum class Landing { WATER, LAVA, BLOCK, NONE }

    /** Reusable result holder, so repeated simulations (aim search) don't allocate one each. */
    class Result {
        var landing = Landing.NONE
        var x = 0.0
        var y = 0.0
        var z = 0.0
        var ticks = 0
        /** Face of the block the bobber hit (UP = landed on top of the fluid/block). */
        var face: Direction = Direction.UP
        /** The block it landed in/on. */
        var bx = 0; var by = 0; var bz = 0
        /** Where it settles after sliding across the fluid (= the landing point for a block landing). */
        var restX = 0.0; var restZ = 0.0
        /** The block column it settles in. */
        var restBx = 0; var restBz = 0
    }

    private const val MAX_TICKS = 140
    private const val DEG = Mth.DEG_TO_RAD

    /** Simulate a cast at [yaw]/[pitch] from the player's current position into [out]. Client thread only. */
    fun simulate(player: LocalPlayer, level: Level, yaw: Float, pitch: Float, out: Result): Result {
        val yr = (yaw * DEG).toDouble()
        val pr = (pitch * DEG).toDouble()

        // 1.8 EntityFishHook(world, player): eye height 1.62 (1.54 sneaking) on the server
        var x = player.x - Mth.cos(yr) * 0.16
        var y = player.y + (if (player.isShiftKeyDown) 1.54 else 1.62) - 0.1
        var z = player.z - Mth.sin(yr) * 0.16
        val cp = Mth.cos(pr).toDouble()
        val mx = -Mth.sin(yr) * cp
        val my = -Mth.sin(pr).toDouble()
        val mz = Mth.cos(yr) * cp
        val len = Math.sqrt(mx * mx + my * my + mz * mz)
        var vx = mx / len * 1.5
        var vy = my / len * 1.5
        var vz = mz / len * 1.5

        out.landing = Landing.NONE
        out.ticks = 0
        val minY = level.minY - 4.0
        var from = Vec3(x, y, z)

        for (t in 1..MAX_TICKS) {
            val to = Vec3(x + vx, y + vy, z + vz)
            val hit = level.clip(ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, player))
            if (hit.type == HitResult.Type.BLOCK) {
                val fluid = level.getFluidState(hit.blockPos)
                val face = hit.direction
                val kind = when {
                    fluid.`is`(FluidTags.LAVA) -> Landing.LAVA
                    fluid.`is`(FluidTags.WATER) -> Landing.WATER
                    else -> Landing.BLOCK   // top, wall or ceiling: the bobber sticks
                }
                val loc = hit.location
                out.landing = kind
                out.x = loc.x; out.y = loc.y; out.z = loc.z
                out.ticks = t
                out.face = face
                out.bx = hit.blockPos.x; out.by = hit.blockPos.y; out.bz = hit.blockPos.z
                if (kind == Landing.BLOCK) {
                    out.restX = loc.x; out.restZ = loc.z
                } else {
                    // the rest of this tick's move happens in the fluid, then the slide decays
                    slide(player, level, loc.x, loc.y, loc.z, to.x - loc.x, to.z - loc.z, vx, vz, out)
                }
                out.restBx = Mth.floor(out.restX); out.restBz = Mth.floor(out.restZ)
                return out
            } else {
                x = to.x; y = to.y; z = to.z
                from = to
            }
            vy -= 0.04
            vx *= 0.92; vy *= 0.92; vz *= 0.92
            if (y < minY) break
        }
        return out
    }

    private const val HALF_WIDTH = 0.125      // bobber hitbox is 0.25 wide

    private const val FLUID_DRAG = 0.92 * 0.9  // 1.8 EntityFishHook: drag x0.9 once in the fluid
    private const val MAX_SLIDE_TICKS = 80

    /**
     * 1.8 slide on the fluid from the crossing: first the leftover of the landing tick's move, then the
     * horizontal velocity decaying by [FLUID_DRAG] per tick. So a fast, flat throw slides far (into the
     * pool wall) and a steep lob that comes down almost vertically stays where it lands. Clipped just
     * under the surface against block colliders, stopping a hitbox half-width short of a wall.
     */
    private fun slide(
        player: LocalPlayer, level: Level, x0: Double, y: Double, z0: Double,
        remX: Double, remZ: Double, vx0: Double, vz0: Double, out: Result,
    ) {
        val cy = y - 0.05
        // measured from our own casts: how Hypixel's slide compares with vanilla's (1 = vanilla)
        val k = BobberSlide.scale()
        var x = x0; var z = z0
        var mx = remX * k; var mz = remZ * k
        var vx = vx0 * FLUID_DRAG * k; var vz = vz0 * FLUID_DRAG * k
        for (t in 0 until MAX_SLIDE_TICKS) {
            if (mx * mx + mz * mz < 1e-6) {
                if (t > 0) break
            } else {
                val hit = level.clip(ClipContext(Vec3(x, cy, z), Vec3(x + mx, cy, z + mz), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
                if (hit.type == HitResult.Type.BLOCK && hit.direction.axis.isHorizontal) {
                    val face = hit.direction
                    // reaching a pool wall sticks it there (1.8 inGround), like any block hit
                    x = hit.location.x + face.stepX * HALF_WIDTH
                    z = hit.location.z + face.stepZ * HALF_WIDTH
                    break
                } else {
                    x += mx; z += mz
                }
            }
            mx = vx; mz = vz
            vx *= FLUID_DRAG; vz *= FLUID_DRAG
        }
        out.restX = x; out.restZ = z
    }
}
