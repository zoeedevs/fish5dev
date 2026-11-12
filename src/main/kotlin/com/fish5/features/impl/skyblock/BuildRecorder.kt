package com.fish5.features.impl.skyblock

import com.fish5.Fish5
import com.fish5.automation.Msg
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import org.lwjgl.glfw.GLFW
import java.io.File

/**
 * `/f5 wormhelper find test debug` — records a hand-built test structure so its exact layout can be
 * turned into a [BuildTemplate]. While on, look at a block and press **K** to record it (lava source
 * blocks count; press K on an already-recorded block to remove it). Run the command again to stop.
 *
 * Every change rewrites `config/fish5/build-debug.txt`:
 * - the recorded blocks, relative to the first one recorded (plus absolute coords);
 * - a full layer-by-layer grid of everything (air included) in the recorded blocks' bounding box
 *   plus 1 block of margin, so nothing is lost if a block was skipped.
 */
object BuildRecorder {

    private val mc get() = Fish5.mc

    private const val MAX_RECORDS = 512
    private const val REACH = 8.0

    private var active = false
    private var keyWasDown = false
    private val records = LinkedHashMap<BlockPos, String>()

    private val file: File get() = File(Fish5.configDir, "build-debug.txt")

    fun toggle() {
        active = !active
        keyWasDown = true // ignore a K that's already held
        if (active) {
            records.clear()
            Msg.chat("§b[Build Debug] §7On. Look at a block and press §fK §7to record it (K again removes it). Run the command again to stop.")
        } else {
            write()
            Msg.chat("§b[Build Debug] §7Off. §f${records.size} §7block(s) saved to §f${file.path}")
        }
    }

    /** Client tick: edge-detected K while no screen is open. */
    fun tick() {
        if (!active) return
        val down = mc.gui.screen() == null && InputConstants.isKeyDown(mc.window, GLFW.GLFW_KEY_K)
        if (down && !keyWasDown) recordLookedAt()
        keyWasDown = down
    }

    private fun recordLookedAt() {
        val player = mc.player ?: return
        val level = mc.level ?: return
        val eye = player.eyePosition
        val to = eye.add(player.getViewVector(1f).scale(REACH))
        // SOURCE_ONLY so lava source blocks can be picked, not just the block under them
        val hit = level.clip(ClipContext(eye, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, player))
        if (hit.type != HitResult.Type.BLOCK) {
            Msg.chat("§c[Build Debug] §7Not looking at a block.")
            return
        }
        val pos = (hit as BlockHitResult).blockPos.immutable()
        if (records.remove(pos) != null) {
            Msg.chat("§e[Build Debug] §7Removed §f${pos.x} ${pos.y} ${pos.z}")
        } else {
            if (records.size >= MAX_RECORDS) {
                Msg.chat("§c[Build Debug] §7At the $MAX_RECORDS block cap.")
                return
            }
            val name = describe(level.getBlockState(pos))
            records[pos] = name
            Msg.chat("§a[Build Debug] §7#${records.size} §f$name §7at §f${pos.x} ${pos.y} ${pos.z}")
        }
        write()
    }

    private fun describe(state: BlockState): String {
        val fluid = state.fluidState
        if (!fluid.isEmpty) {
            val id = BuiltInRegistries.FLUID.getKey(fluid.type).path
            return if (fluid.isSource) "$id(source)" else id
        }
        return BuiltInRegistries.BLOCK.getKey(state.block).path
    }

    private fun write() {
        val level = mc.level ?: return
        val sb = StringBuilder()
        sb.append("# fish5 build recording\n")
        if (records.isEmpty()) {
            sb.append("# (nothing recorded)\n")
        } else {
            val origin = records.keys.first()
            sb.append("# origin = first recorded block at ${origin.x} ${origin.y} ${origin.z}\n")
            sb.append("# dx dy dz  block  (absolute x y z)\n")
            for ((p, name) in records) {
                sb.append("${p.x - origin.x} ${p.y - origin.y} ${p.z - origin.z}  $name  (${p.x} ${p.y} ${p.z})\n")
            }

            // full grid of the bounding box + 1 margin, top layer first; rows = z, columns = x
            var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var minZ = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE; var maxZ = Int.MIN_VALUE
            for (p in records.keys) {
                minX = minOf(minX, p.x); minY = minOf(minY, p.y); minZ = minOf(minZ, p.z)
                maxX = maxOf(maxX, p.x); maxY = maxOf(maxY, p.y); maxZ = maxOf(maxZ, p.z)
            }
            minX--; minY--; minZ--; maxX++; maxY++; maxZ++
            val legend = LinkedHashMap<String, Char>()
            legend["air"] = '.'
            val symbols = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            val cursor = BlockPos.MutableBlockPos()
            val grid = StringBuilder()
            for (y in maxY downTo minY) {
                grid.append("\nlayer dy=${y - origin.y}   (x ${minX - origin.x}..${maxX - origin.x} left->right, z ${minZ - origin.z}..${maxZ - origin.z} top->bottom)\n")
                for (z in minZ..maxZ) {
                    for (x in minX..maxX) {
                        cursor.set(x, y, z)
                        val state = level.getBlockState(cursor)
                        val name = if (state.isAir) "air" else describe(state)
                        val c = legend.getOrPut(name) { symbols.getOrElse(legend.size - 1) { '?' } }
                        grid.append(c)
                        // mark recorded cells so they stand out
                        grid.append(if (records.containsKey(cursor)) '*' else ' ')
                    }
                    grid.append('\n')
                }
            }
            sb.append("\n# grid legend (* = recorded)\n")
            for ((name, c) in legend) sb.append("$c = $name\n")
            sb.append(grid)
        }
        runCatching { file.writeText(sb.toString()) }
            .onFailure { Msg.chat("§c[Build Debug] §7Couldn't write ${file.name}: ${it.message}") }
    }
}
