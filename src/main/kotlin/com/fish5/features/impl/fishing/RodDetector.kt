package com.fish5.features.impl.fishing

import com.fish5.sb.SkyblockData
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Tells real SkyBlock fishing rods apart from everything else that is a vanilla `fishing_rod` item
 * (Soul Whip, Flaming Flay, Zombie Commander Whip, Grappling Hook...), and finds which slot a rod is in.
 *
 * Order of checks for a `fishing_rod` stack:
 *  1. SkyBlock id in [ROD_IDS] (Hypixel item API category `FISHING_ROD`, snapshot 2026-11-06) -> rod.
 *  2. SkyBlock id in [NOT_RODS] -> not a rod.
 *  3. Unknown SkyBlock id: lore rarity line ending in "FISHING ROD" -> rod (covers rods added later).
 *  4. No SkyBlock id at all (singleplayer / vanilla server) -> plain vanilla rod.
 *
 * Results are cached per [ItemStack] instance, so polling the held item every tick does no NBT copies.
 */
object RodDetector {

    enum class Kind { WATER, LAVA, VANILLA }

    /** Every SkyBlock FISHING_ROD id. Lava rods are the ones in [LAVA_ROD_IDS]. */
    val ROD_IDS: Set<String> = hashSetOf(
        "AUGER_ROD", "CARNIVAL_FISHING_ROD", "CHALLENGE_ROD", "CHAMP_ROD", "CHUM_ROD", "DIRT_ROD",
        "ELLES_LAVA_ROD", "FARMER_ROD", "FISHING_ROD", "GIANT_FISHING_ROD", "HELLFIRE_ROD", "ICE_ROD",
        "INFERNO_ROD", "LEGEND_ROD", "MAGMA_ROD", "PHANTOM_ROD", "POLISHED_TOPAZ_ROD", "PRISMARINE_ROD",
        "ROD_OF_THE_SEA", "SPEEDSTER_ROD", "SPONGE_ROD", "STARTER_LAVA_ROD", "THE_SHREDDER", "WINTER_ROD",
        "YETI_ROD",
    )

    /** Rods that can fish lava outside the Crystal Hollows (Crimson Isle). */
    val LAVA_ROD_IDS: Set<String> = hashSetOf(
        "STARTER_LAVA_ROD", "MAGMA_ROD", "INFERNO_ROD", "HELLFIRE_ROD", "ELLES_LAVA_ROD",
    )

    /** `fishing_rod`-material items that are not rods (weapons, grappling hook, joke items). */
    private val NOT_RODS: Set<String> = hashSetOf(
        "SOUL_WHIP", "FLAMING_FLAY", "ZOMBIE_COMMANDER_WHIP", "GRAPPLING_HOOK", "MIRRORED_FISHING_ROD",
    )

    // identity cache (fixed ring, big enough for hotbar + both hands): stack -> kind (null = not a rod)
    private const val CACHE_SIZE = 16
    private val cacheStacks = arrayOfNulls<ItemStack>(CACHE_SIZE)
    private val cacheKinds = arrayOfNulls<Kind>(CACHE_SIZE)
    private var cacheNext = 0

    /** The rod kind of [stack], or null if it is not a fishing rod. */
    fun kindOf(stack: ItemStack): Kind? {
        if (stack.isEmpty) return null
        for (i in 0 until CACHE_SIZE) if (cacheStacks[i] === stack) return cacheKinds[i]
        val kind = classify(stack)
        cacheStacks[cacheNext] = stack
        cacheKinds[cacheNext] = kind
        cacheNext = (cacheNext + 1) % CACHE_SIZE
        return kind
    }

    /** Drop cached stacks (world change), so old ItemStacks aren't kept alive. */
    fun clearCache() {
        cacheStacks.fill(null)
        cacheKinds.fill(null)
    }

    fun isRod(stack: ItemStack) = kindOf(stack) != null

    private fun classify(stack: ItemStack): Kind? {
        if (stack.isEmpty || !stack.`is`(Items.FISHING_ROD)) return null
        val id = SkyblockData.skyblockId(stack) ?: return Kind.VANILLA
        if (id in ROD_IDS) return if (id in LAVA_ROD_IDS) Kind.LAVA else Kind.WATER
        if (id in NOT_RODS) return null
        return if (loreSaysFishingRod(stack)) Kind.WATER else null
    }

    // the rarity line, e.g. "LEGENDARY FISHING ROD" (or "a LEGENDARY FISHING ROD a" when recombobulated)
    private fun loreSaysFishingRod(stack: ItemStack): Boolean {
        val lore = stack.get(DataComponents.LORE) ?: return false
        for (i in lore.lines().indices.reversed()) {
            val line = SkyblockData.stripColors(lore.lines()[i].string).trim()
            if (line.isEmpty()) continue
            return line.contains("FISHING ROD")
        }
        return false
    }

    /** The rod in the main hand (preferred) or offhand, or null. */
    fun heldRod(player: Player): Kind? = kindOf(player.mainHandItem) ?: kindOf(player.offhandItem)

    /** First hotbar slot (0-8) holding a rod of [kind] (any rod if null), or -1. Cheap enough per tick. */
    fun findHotbarRod(player: Player, kind: Kind? = null): Int {
        val inv = player.inventory
        for (slot in 0..8) {
            val k = kindOf(inv.getItem(slot)) ?: continue
            if (kind == null || k == kind) return slot
        }
        return -1
    }

}
