package com.fish5.features.impl.fishing

import com.fish5.Fish5
import com.fish5.automation.Msg
import com.fish5.sb.SkyblockData
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack

/**
 * Watches what each party member near us holds, for the Killer's "Weapon swap" wait: a lootsharer
 * swaps from the rod to their weapon (Dreadlord Sword by default), hits, and swaps back to the rod.
 * Once every member who is fishing near us has done that, they've had their hit and we can kill.
 *
 * Read from the world only (other players' held item), so it works whatever the members run. Fixed
 * arrays indexed like [Party.members]; a held stack is only classified when it changes.
 */
object PartyWeaponWatch {

    private const val MAX = 8
    private const val RANGE_SQ = 40.0 * 40.0
    private const val VISIBLE_MS = 1500L
    private const val FISHING_RECENT_MS = 60_000L

    private const val OTHER = 0
    private const val WEAPON = 1
    private const val ROD = 2

    private val names = arrayOfNulls<String>(MAX)
    private val lastStack = arrayOfNulls<ItemStack>(MAX)
    private val lastKind = IntArray(MAX)
    private val visibleAt = LongArray(MAX)
    private val rodAt = LongArray(MAX)       // last seen with a rod (or a bobber out)
    private val weaponAt = LongArray(MAX)    // last seen holding the weapon
    private val backAt = LongArray(MAX)      // first time back on the rod after the weapon

    private var weaponLower = ""
    private var weaponId = ""
    private var weaponSetting: String? = null

    fun setWeapon(name: String) {
        if (name == weaponSetting) return
        weaponSetting = name
        weaponLower = name.trim().lowercase()
        weaponId = name.trim().uppercase().replace(' ', '_')
        lastStack.fill(null)
    }

    /** Client tick while the Killer waits on weapon swaps (cheap: a few name compares, no allocation). */
    fun tick() {
        val level = Fish5.mc.level ?: return
        val self = Fish5.mc.player ?: return
        val now = System.currentTimeMillis()
        val members = Party.members
        val n = minOf(members.size, MAX)
        val players = level.players()
        for (i in 0 until n) {
            val m = members[i]
            if (names[i] != m) reset(i, m)
            var found: Player? = null
            for (j in players.indices) {
                val p = players[j]
                if (p !== self && p.name.string.equals(m, true)) { found = p; break }
            }
            if (found == null || found.distanceToSqr(self) > RANGE_SQ) continue
            visibleAt[i] = now
            val stack = found.mainHandItem
            if (stack !== lastStack[i]) {
                lastStack[i] = stack
                val kind = classify(stack)
                if (kind != lastKind[i] && Msg.debug) {
                    Msg.dbg("Swap watch: $m now holds ${if (stack.isEmpty) "nothing" else SkyblockData.stripColors(stack.hoverName.string)}" +
                        " (${when (kind) { WEAPON -> "weapon"; ROD -> "rod"; else -> "other" }})")
                }
                lastKind[i] = kind
            }
            when (lastKind[i]) {
                WEAPON -> weaponAt[i] = now
                ROD -> {
                    rodAt[i] = now
                    if (weaponAt[i] > backAt[i]) backAt[i] = now
                }
            }
            if (found.fishing != null) rodAt[i] = now
        }
        for (i in n until MAX) if (names[i] != null) reset(i, null)
    }

    /**
     * When every party member fishing near us has swapped to the weapon at or after [since] and back
     * to the rod: the time the last of them got back. 0 while someone hasn't, or nobody is fishing near us.
     */
    fun allSwappedBackAt(since: Long): Long {
        val now = System.currentTimeMillis()
        var latest = 0L
        var fishing = 0
        for (i in 0 until minOf(Party.members.size, MAX)) {
            if (now - visibleAt[i] > VISIBLE_MS) continue                             // not near us: can't watch them
            if (now - rodAt[i] > FISHING_RECENT_MS && weaponAt[i] < since) continue    // here but not fishing
            fishing++
            if (weaponAt[i] < since || backAt[i] <= weaponAt[i]) return 0L
            if (backAt[i] > latest) latest = backAt[i]
        }
        return if (fishing == 0) 0L else latest
    }

    /** "name (state), ..." for each party member, for the status message. */
    fun describe(since: Long): String {
        val now = System.currentTimeMillis()
        val sb = StringBuilder()
        for (i in 0 until minOf(Party.members.size, MAX)) {
            if (sb.isNotEmpty()) sb.append(", ")
            sb.append(Party.members[i]).append(
                when {
                    now - visibleAt[i] > VISIBLE_MS -> " (not near)"
                    weaponAt[i] >= since && backAt[i] > weaponAt[i] -> " (swapped back)"
                    weaponAt[i] >= since -> " (on weapon)"
                    else -> " (waiting)"
                },
            )
        }
        return sb.toString()
    }

    private fun classify(stack: ItemStack): Int {
        if (stack.isEmpty) return OTHER
        if (RodDetector.isRod(stack)) return ROD
        if (weaponLower.isEmpty()) return OTHER
        if (SkyblockData.skyblockId(stack) == weaponId) return WEAPON
        return if (SkyblockData.stripColors(stack.hoverName.string).lowercase().contains(weaponLower)) WEAPON else OTHER
    }

    private fun reset(i: Int, name: String?) {
        names[i] = name
        lastStack[i] = null
        lastKind[i] = OTHER
        visibleAt[i] = 0L; rodAt[i] = 0L; weaponAt[i] = 0L; backAt[i] = 0L
    }

    fun clear() {
        for (i in 0 until MAX) reset(i, null)
    }
}
