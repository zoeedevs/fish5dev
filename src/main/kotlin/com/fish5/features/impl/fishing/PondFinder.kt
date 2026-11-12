package com.fish5.features.impl.fishing

import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.tags.FluidTags
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.material.Fluid

/**
 * Finds fishable fluid surface blocks near the player, block by block (no pond grouping): the top
 * fluid block of a column, with no fluid and no full solid block on top. Kept deliberately loose:
 * [CastAim] simulates every candidate, so anything a bobber can't actually land on is rejected there.
 * Runs on demand when a cast target is picked, never per tick; bounded by [RADIUS] / [MAX_CELLS].
 */
object PondFinder {

    enum class FluidKind(val tag: TagKey<Fluid>) { LAVA(FluidTags.LAVA), WATER(FluidTags.WATER) }

    const val RADIUS = 24          // horizontal blocks around the player (a cast reaches ~20-30)
    private const val DOWN = 12    // fluid below the player
    private const val UP = 4       // ...and slightly above
    private const val MAX_CELLS = 8192

    /** True if the last [scan] stopped at [MAX_CELLS] (some far/deep fluid was skipped). */
    var lastScanCapped = false
        private set

    // layers nearest the player's feet first (0, -1, +1, -2, ...), so a big lake far below can't use
    // up the cell cap before the fluid the player is actually standing at is scanned
    private val DY_ORDER = IntArray(DOWN + UP + 1).also { a ->
        var i = 0
        a[i++] = 0
        for (d in 1..maxOf(DOWN, UP)) {
            if (d <= DOWN) a[i++] = -d
            if (d <= UP) a[i++] = d
        }
    }

    private val scratch = BlockPos.MutableBlockPos()
    private val above = BlockPos.MutableBlockPos()

    /**
     * Top-of-column [kind] *source* block with nothing solid on it (no fluid / full block above).
     * Source only: the thin flowing lava/water spreading from a fall or a leak isn't a fishing spot.
     */
    fun isSurface(level: Level, x: Int, y: Int, z: Int, kind: FluidKind): Boolean {
        scratch.set(x, y, z)
        val fluid = level.getFluidState(scratch)
        if (!fluid.`is`(kind.tag) || !fluid.isSource) return false
        above.set(x, y + 1, z)
        if (!level.getFluidState(above).isEmpty) return false
        return !level.getBlockState(above).isCollisionShapeFullBlock(level, above)
    }

    /** Every surface block of [kind] around [player] (packed [BlockPos.asLong]). Client thread only. */
    fun scan(player: Player, level: Level, kind: FluidKind): LongOpenHashSet {
        val px = player.blockX; val py = player.blockY; val pz = player.blockZ
        val out = LongOpenHashSet()
        lastScanCapped = false
        loop@ for (dy in DY_ORDER) {
            for (dx in -RADIUS..RADIUS) for (dz in -RADIUS..RADIUS) {
                if (dx * dx + dz * dz > RADIUS * RADIUS) continue
                if (isSurface(level, px + dx, py + dy, pz + dz, kind)) {
                    out.add(BlockPos.asLong(px + dx, py + dy, pz + dz))
                    if (out.size >= MAX_CELLS) { lastScanCapped = true; break@loop }
                }
            }
        }
        return out
    }

    /** Debug: what a fluid block and the block above it are, and which surface rule it fails. */
    fun describe(level: Level, pos: BlockPos, kind: FluidKind): String {
        val fluid = level.getFluidState(pos)
        val up = pos.above()
        val upState = level.getBlockState(up)
        val upFluid = level.getFluidState(up)
        val here = if (fluid.isEmpty) "no fluid" else
            "${BuiltInRegistries.FLUID.getKey(fluid.type).path} (${if (fluid.isSource) "source" else "level ${fluid.amount}"})"
        val upName = BuiltInRegistries.BLOCK.getKey(upState.block).path
        val verdict = when {
            !fluid.`is`(kind.tag) -> "not ${kind.name.lowercase()}"
            !fluid.isSource -> "flowing, not a source"
            !upFluid.isEmpty -> "fluid above"
            upState.isCollisionShapeFullBlock(level, up) -> "solid block above"
            else -> "OK surface"
        }
        return "$here, above: $upName" + (if (!upFluid.isEmpty) " +fluid" else "") +
            (if (!upState.getCollisionShape(level, up).isEmpty) " (has collision)" else "") + " -> $verdict"
    }
}
