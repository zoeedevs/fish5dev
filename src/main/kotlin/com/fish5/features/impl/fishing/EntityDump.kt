package com.fish5.features.impl.fishing

import com.fish5.Fish5
import com.fish5.automation.Msg
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import java.io.File

/**
 * `/f5 sc dump`: writes everything the client knows about one sea creature (and the armor stands
 * around it) to `config/fish5/entity-dump.txt`. Used to check by hand whether Hypixel puts anything
 * owner-related on the entity. Dump your own worm, then a partner's, and compare.
 *
 * The target is the entity under the crosshair, or else the nearest non-player mob within 16 blocks.
 * Runs only when the command is typed, so it allocates freely.
 */
object EntityDump {

    private val mc get() = Fish5.mc
    private const val SEARCH_RADIUS = 16.0
    private const val NEIGHBOUR_RADIUS = 3.0

    fun run() {
        val p = mc.player ?: return
        val level = mc.level ?: return

        var target: Entity? = mc.crosshairPickEntity?.takeIf { it !is Player }
        if (target is ArmorStand) {
            // looking at the nametag: dump the mob under it instead
            target = nearestMob(level.getEntitiesOfClass(LivingEntity::class.java, target.boundingBox.inflate(1.5, 3.5, 1.5)), target) ?: target
        }
        if (target == null) {
            target = nearestMob(level.getEntitiesOfClass(LivingEntity::class.java, p.boundingBox.inflate(SEARCH_RADIUS)), p)
        }
        if (target == null) {
            Msg.chat("§b[SC dump] §cNo mob within ${SEARCH_RADIUS.toInt()} blocks. Look at a sea creature and try again.")
            return
        }

        val owner = SeaCreatureTracker.describeOwner(target.id) ?: "not tracked"
        val out = StringBuilder(4096)
        out.append("fish5 entity dump  ").append(java.time.LocalDateTime.now()).append('\n')
        out.append("you: ").append(p.scoreboardName).append(" at ").append(pos(p)).append('\n')
        out.append("tracker says: ").append(owner).append("\n\n")
        dump(out, "TARGET", target)

        val around = level.getEntities(target, AABB(target.x - NEIGHBOUR_RADIUS, target.y - NEIGHBOUR_RADIUS, target.z - NEIGHBOUR_RADIUS,
            target.x + NEIGHBOUR_RADIUS, target.y + NEIGHBOUR_RADIUS + 2.0, target.z + NEIGHBOUR_RADIUS)) { it !is Player }
        for (e in around) dump(out, if (e.id == target.id + 1) "NEARBY (id+1, likely its nametag)" else "NEARBY", e)

        val file = File(mc.gameDirectory, "config/fish5/entity-dump.txt")
        val ok = runCatching {
            file.parentFile.mkdirs()
            file.writeText(out.toString())
        }.onFailure { Msg.LOG.warn("[SC dump] couldn't write {}", file, it) }.isSuccess
        Msg.LOG.info("[SC dump]\n{}", out)

        val name = target.customName?.string ?: target.type.description.string
        Msg.chat("§b[SC dump] §7Dumped §f$name§7 (id ${target.id}) + §f${around.size}§7 entities around it, tracker: §f$owner")
        Msg.chat(if (ok) "§b[SC dump] §7Saved to §fconfig/fish5/entity-dump.txt§7 (overwritten each time)." else "§c[SC dump] Couldn't write the file, see the log.")
    }

    private fun nearestMob(list: List<LivingEntity>, from: Entity): LivingEntity? {
        var best: LivingEntity? = null
        var bestD = Double.MAX_VALUE
        for (e in list) {
            if (e is Player || e is ArmorStand || e === from) continue
            val d = e.distanceToSqr(from)
            if (d < bestD) { bestD = d; best = e }
        }
        return best
    }

    private fun dump(out: StringBuilder, label: String, e: Entity) {
        out.append("== ").append(label).append(" ==\n")
        out.append("class: ").append(e.javaClass.simpleName).append("  type: ").append(e.type.toString()).append('\n')
        out.append("id: ").append(e.id).append("  uuid: ").append(e.stringUUID).append('\n')
        out.append("pos: ").append(pos(e)).append("  dist to you: ").append(String.format("%.1f", mc.player?.distanceTo(e) ?: 0f)).append('\n')
        out.append("customName: ").append(e.customName?.string).append("  visible: ").append(e.isCustomNameVisible).append('\n')
        out.append("customName (raw): ").append(e.customName).append('\n')
        out.append("invisible: ").append(e.isInvisible).append("  glowing: ").append(e.isCurrentlyGlowing).append("  teamColor: ")
            .append(Integer.toHexString(e.teamColor)).append('\n')
        out.append("scoreboardName: ").append(e.scoreboardName).append("  team: ").append(e.team?.name)
        e.team?.let { t -> out.append(" prefix='").append(t.playerPrefix.string).append("' suffix='").append(t.playerSuffix.string).append('\'') }
        out.append('\n')
        out.append("tags: ").append(e.entityTags()).append('\n')
        out.append("vehicle: ").append(e.vehicle?.let { "${it.javaClass.simpleName}#${it.id}" }).append("  passengers: ")
            .append(e.passengers.joinToString { "${it.javaClass.simpleName}#${it.id}" }).append('\n')
        if (e is LivingEntity) {
            out.append("health: ").append(e.health).append(" / ").append(e.maxHealth).append('\n')
            out.append("effects: ").append(e.activeEffects.joinToString()).append('\n')
            for (slot in EquipmentSlot.VALUES) {
                val s = e.getItemBySlot(slot)
                if (s.isEmpty) continue
                out.append("equip ").append(slot.name).append(": ").append(s.hoverName.string).append("  ")
                    .append(s.componentsPatch.toString().take(1500)).append('\n')
            }
        }
        out.append("synced data (non-default):\n")
        val values = e.entityData.nonDefaultValues
        if (values.isNullOrEmpty()) out.append("  (none)\n")
        else for (v in values) out.append("  [").append(v.id()).append("] ").append(v.value().toString().take(1500)).append('\n')
        out.append('\n')
    }

    private fun pos(e: Entity) = String.format("%.2f, %.2f, %.2f", e.x, e.y, e.z)
}
