package com.fish5.features.impl.skyblock

import net.minecraft.core.BlockPos
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

/**
 * The lava worm-fishing build `/f5 wormhelper find lava test` looks for. Side view, 2 blocks wide
 * (`s` = 0..1), `f` runs from the lava (0) back through the hole (3), `y` is relative to the lava:
 *
 * ```
 *          f=0   f=1    f=2  f=3
 * y+3       W     W      W    W     roof (wall)
 * y+2       .     .      .    .
 * y+1       .     .      .    .
 * y+0       L     .      .    .     you stand at f=1, level with the lava
 * y-1       S     S      .    .
 * y-2       W     W      .    .     2x2 hole, 2-block drop behind you
 * y-3                    S    S
 * ```
 *
 * - `L` must be lava source blocks (the 2x1 row).
 * - `.` must be diggable: air or Hardstone (vanilla Stone). Nothing else in Crystal Hollows is
 *   reliably breakable without regenerating, and glass/panes (gemstones) are explicitly ruled out.
 * - `S` and `W` (under the lava, under your feet, the hole floor, and every block touching the
 *   dug-out space: sides, roof) must be a full block of stone, cyan terracotta or cobblestone. That
 *   rules out glass/panes, air openings, and partial blocks like cobblestone slabs/stairs.
 *
 * All offsets are precomputed per facing, so a match check allocates nothing until it succeeds.
 */
internal object BuildTemplate {

    private const val WIDTH = 2

    /** One facing: flat (dx, y, dz) triples for each cell role. */
    private class Facing(
        val lava: IntArray,
        val dig: IntArray,
        val support: IntArray,
        val wall: IntArray,
        val stand: IntArray,
    )

    private val facings: Array<Facing> = buildFacings()

    private fun buildFacings(): Array<Facing> {
        // local cells as (s, f, y)
        val lava = ArrayList<List<Int>>()
        val dig = ArrayList<List<Int>>()
        val support = ArrayList<List<Int>>()
        val stand = ArrayList<List<Int>>()
        for (s in 0 until WIDTH) {
            lava += listOf(s, 0, 0)
            for (y in 1..2) dig += listOf(s, 0, y)
            for (y in 0..2) dig += listOf(s, 1, y)
            for (f in 2..3) for (y in -2..2) dig += listOf(s, f, y)
            support += listOf(s, 0, -1)
            support += listOf(s, 1, -1)
            for (f in 2..3) support += listOf(s, f, -3)
            stand += listOf(s, 1, 0)
        }
        val pocket = HashSet<List<Int>>(lava + dig)
        val supportSet = HashSet(support)
        val wall = LinkedHashSet<List<Int>>()
        // the 6 unit steps in local (s, f, y)
        val n6 = arrayOf(
            intArrayOf(1, 0, 0), intArrayOf(-1, 0, 0), intArrayOf(0, 1, 0),
            intArrayOf(0, -1, 0), intArrayOf(0, 0, 1), intArrayOf(0, 0, -1),
        )
        for (c in pocket) for (o in n6) {
            val n = listOf(c[0] + o[0], c[1] + o[1], c[2] + o[2])
            if (n !in pocket && n !in supportSet) wall += n
        }

        val dirs = arrayOf(intArrayOf(1, 0), intArrayOf(0, 1), intArrayOf(-1, 0), intArrayOf(0, -1))
        return Array(dirs.size) { i ->
            val fx = dirs[i][0]
            val fz = dirs[i][1]
            val sx = -fz
            val sz = fx
            fun rotate(cells: Collection<List<Int>>): IntArray {
                val out = IntArray(cells.size * 3)
                var k = 0
                for (c in cells) {
                    out[k++] = c[0] * sx + c[1] * fx
                    out[k++] = c[2]
                    out[k++] = c[0] * sz + c[1] * fz
                }
                return out
            }
            Facing(rotate(lava), rotate(dig), rotate(support), rotate(wall), rotate(stand))
        }
    }

    /** What the walls, roof and floors may be: full blocks of Hardstone (stone), cyan terracotta or
     * cobblestone. Slabs/stairs/walls are separate blocks, so they (and glass/panes) never match. */
    private val WALL_BLOCKS: Set<Block> = setOf(Blocks.STONE, Blocks.DYED_TERRACOTTA.cyan(), Blocks.COBBLESTONE)

    private val cursor = BlockPos.MutableBlockPos()

    /** Client thread only (shares [cursor]). Tries all 4 facings with [origin] as the first lava cell
     * and adds each match to [out] as [lava0, lava1, stand0, stand1]. */
    fun matchAt(level: Level, origin: BlockPos, out: MutableList<List<BlockPos>>) {
        // the template reaches at most 5 blocks out (hole + wall); an unloaded chunk reads as air
        val r = 5
        if (!level.hasChunk((origin.x - r) shr 4, (origin.z - r) shr 4) ||
            !level.hasChunk((origin.x + r) shr 4, (origin.z - r) shr 4) ||
            !level.hasChunk((origin.x - r) shr 4, (origin.z + r) shr 4) ||
            !level.hasChunk((origin.x + r) shr 4, (origin.z + r) shr 4)
        ) return

        for (facing in facings) {
            if (!matches(level, origin, facing)) continue
            val cells = ArrayList<BlockPos>(4)
            addCells(origin, facing.lava, cells)
            addCells(origin, facing.stand, cells)
            out += cells
        }
    }

    private fun matches(level: Level, o: BlockPos, f: Facing): Boolean {
        // cheapest / most selective first
        if (!all(level, o, f.lava) { st -> st.fluidState.isSource && st.fluidState.`is`(FluidTags.LAVA) }) return false
        if (!all(level, o, f.dig) { st -> st.isAir || st.block == Blocks.STONE }) return false
        if (!all(level, o, f.support) { st -> st.block in WALL_BLOCKS }) return false
        return all(level, o, f.wall) { st -> st.block in WALL_BLOCKS }
    }

    private inline fun all(level: Level, o: BlockPos, cells: IntArray, test: (BlockState) -> Boolean): Boolean {
        var i = 0
        while (i < cells.size) {
            cursor.set(o.x + cells[i], o.y + cells[i + 1], o.z + cells[i + 2])
            if (!test(level.getBlockState(cursor))) return false
            i += 3
        }
        return true
    }

    private fun addCells(o: BlockPos, cells: IntArray, into: MutableList<BlockPos>) {
        var i = 0
        while (i < cells.size) {
            into += BlockPos(o.x + cells[i], o.y + cells[i + 1], o.z + cells[i + 2])
            i += 3
        }
    }
}
