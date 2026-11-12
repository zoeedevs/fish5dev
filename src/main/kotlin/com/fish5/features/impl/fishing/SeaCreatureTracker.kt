package com.fish5.features.impl.fishing

import com.fish5.Fish5
import com.fish5.automation.Msg
import com.fish5.sb.SkyblockData
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.FishingHook
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB

/**
 * Tracks sea creatures and who fished them, read from the world only (no partner helper needed).
 *
 * **Ours** (always on), same idea as SkyHanni's SeaCreatureDetectionApi (originals/skyhanni):
 *  1. Hypixel only shows the catcher the catch line ("A Flaming Worm surfaces from the depths!").
 *     A partner's catch never reaches our chat, so that line is the ownership signal. "It's a Double
 *     Hook!" right before it means two spawns.
 *  2. For [CLAIM_WINDOW_MS] after that line, look for a fresh nametag of that creature next to where
 *     our bobber last was and claim the closest one(s). Chat/spawn order doesn't matter.
 *
 * **Other players'** (only while [trackOthers]: party macro or party HUD, see [want]): every [OTHERS_SCAN_TICKS],
 * fresh sea creature nametags nobody claimed within [OTHERS_MIN_AGE_TICKS] (so our own claim gets
 * first go) are credited to whoever's bobber was last seen closest to the spawn. That's the player
 * who reeled it in. No bobber close enough = owner [UNKNOWN]. SkyHanni only knows "not mine"; per
 * player counts are what party mode needs.
 *
 * Every tracked creature leaves when it dies, when it is gone while we're near where it was, or
 * after [DESPAWN_MS] (Hypixel's 6 min sea creature despawn, as in SkyHanni). Fixed arrays, cleared on
 * world change.
 *
 * Catch lines come from `assets/fish5/data/sea_creatures.txt` (`message|name`, from SkyHanni-REPO's
 * SeaCreatures.json, snapshot 2026-11-07), loaded on first use.
 */
object SeaCreatureTracker {

    const val DESPAWN_MS = 6 * 60_000L
    const val UNKNOWN = "?"

    private const val MAX_TRACKED = 64
    private const val CLAIM_WINDOW_MS = 1500L
    private const val DOUBLE_HOOK_WINDOW_MS = 600L
    private const val BOBBER_MEMORY_MS = 3000L      // our catch line must follow a bobber we had out
    private const val MAX_TAG_AGE_TICKS = 50
    private const val CLAIM_RADIUS = 3.5
    private const val GONE_NEAR_DISTANCE = 24.0     // missing while we're this close = dead/despawned
    private const val GONE_GRACE_MS = 300L       // missing this long while close = killed (Hypixel removes the body on death)
    private const val CHECK_EVERY_TICKS = 4
    private const val RUNIC_RECHECK_MS = 5_000L

    // other players
    private const val OTHERS_SCAN_TICKS = 10
    private const val OTHERS_MIN_AGE_TICKS = 25     // leave our own claim ~1.25 s first
    private const val OTHERS_MAX_AGE_TICKS = 70     // > MIN + SCAN, so every fresh tag is seen once in range
    private const val OTHERS_RADIUS = 40.0
    private const val BOBBER_SCAN_TICKS = 4
    private const val MAX_BOBBERS = 8
    private const val OWNER_RADIUS = 4.0
    private const val OWNER_TIME_MS = 4000L

    // -- catch lines -------------------------------------------------------------------------------

    private val catchLines: HashMap<String, String> by lazy {
        val map = HashMap<String, String>(128)
        runCatching {
            SeaCreatureTracker::class.java.getResourceAsStream("/assets/fish5/data/sea_creatures.txt")?.bufferedReader()?.use { r ->
                r.forEachLine { line ->
                    val bar = line.lastIndexOf('|')
                    if (bar > 0) map[line.substring(0, bar)] = line.substring(bar + 1)
                }
            }
        }.onFailure { Msg.LOG.warn("fish5: couldn't load sea creature list", it) }
        map
    }

    private val allNames: Array<String> by lazy { catchLines.values.toHashSet().toTypedArray() }

    // -- tracked creatures (parallel fixed arrays, no per-tick allocation) --------------------------

    private val bodyId = IntArray(MAX_TRACKED)
    private val tagId = IntArray(MAX_TRACKED)
    private val names = arrayOfNulls<String>(MAX_TRACKED)
    /** null = ours, else the owner's name (or [UNKNOWN]). */
    private val owners = arrayOfNulls<String>(MAX_TRACKED)
    private val spawnAt = LongArray(MAX_TRACKED)
    private val lastSeenAt = LongArray(MAX_TRACKED)
    private val lastX = DoubleArray(MAX_TRACKED)
    private val lastY = DoubleArray(MAX_TRACKED)
    private val lastZ = DoubleArray(MAX_TRACKED)
    /** Runic: still counts toward the cap, but has a lot more health (combat may leave it to die from splash). */
    private val runic = BooleanArray(MAX_TRACKED)
    private var size = 0
    private var ownCount = 0

    /** Our sea creatures currently alive. */
    val count: Int get() = ownCount

    /** Other players' sea creatures we know of (only counted while [trackOthers] is on). */
    val othersCount: Int get() = size - ownCount

    /** Alive sea creatures credited to [player] (case-insensitive name). */
    fun countFor(player: String): Int {
        var n = 0
        for (i in 0 until size) { val o = owners[i]; if (o != null && o.equals(player, true)) n++ }
        return n
    }

    /** Highest count any single other (named) player has, 0 if none. */
    fun maxOtherCount(): Int {
        var best = 0
        for (i in 0 until size) {
            val o = owners[i] ?: continue
            if (o == UNKNOWN) continue
            var n = 0
            for (j in 0 until size) if (o == owners[j]) n++
            if (n > best) best = n
        }
        return best
    }

    /** Fills [into] with owner name -> count for other players (for the party GUI). Clears it first. */
    fun otherCounts(into: MutableMap<String, Int>) {
        into.clear()
        for (i in 0 until size) { val o = owners[i] ?: continue; into[o] = (into[o] ?: 0) + 1 }
    }

    /** When the oldest of ours spawned (ms epoch), or 0 if none. */
    val oldestSpawnAt: Long
        get() {
            var oldest = 0L
            for (i in 0 until size) if (owners[i] == null && (oldest == 0L || spawnAt[i] < oldest)) oldest = spawnAt[i]
            return oldest
        }

    /** Ms until the oldest of ours despawns (can go negative briefly), or -1 if none. */
    fun msUntilFirstDespawn(): Long {
        val o = oldestSpawnAt
        return if (o == 0L) -1L else o + DESPAWN_MS - System.currentTimeMillis()
    }

    private fun wanted(i: Int, includeOthers: Boolean, ignoreRunic: Boolean, spawnedBy: Long = Long.MAX_VALUE) =
        (owners[i] == null || includeOthers) && !(ignoreRunic && runic[i]) && spawnAt[i] <= spawnedBy

    /**
     * Creatures combat still has to kill: ours (plus everyone's if [includeOthers]), runic ones skipped if
     * [ignoreRunic]. [spawnedBy] limits it to the group alive when a kill started, so a creature that
     * spawns mid-kill (gear respawns, a late catch) waits for the next group instead of dragging the kill on.
     */
    fun killable(includeOthers: Boolean, ignoreRunic: Boolean, spawnedBy: Long = Long.MAX_VALUE): Int {
        var n = 0
        for (i in 0 until size) if (wanted(i, includeOthers, ignoreRunic, spawnedBy)) n++
        return n
    }

    /** Spawn time (ms epoch) of our oldest creature combat would kill, or 0 if none. */
    fun oldestKillableSpawnAt(ignoreRunic: Boolean): Long {
        var oldest = 0L
        for (i in 0 until size) if (wanted(i, false, ignoreRunic) && (oldest == 0L || spawnAt[i] < oldest)) oldest = spawnAt[i]
        return oldest
    }

    /** Entity id (body, else nametag) of the creature to kill closest to (x, y, z), or -1. */
    fun nearestKillableId(x: Double, y: Double, z: Double, includeOthers: Boolean, ignoreRunic: Boolean,
                          spawnedBy: Long = Long.MAX_VALUE): Int {
        var best = -1
        var bestD = Double.MAX_VALUE
        for (i in 0 until size) {
            if (!wanted(i, includeOthers, ignoreRunic, spawnedBy)) continue
            val dx = lastX[i] - x; val dy = lastY[i] - y; val dz = lastZ[i] - z
            val d = dx * dx + dy * dy + dz * dz
            if (d < bestD) { bestD = d; best = if (bodyId[i] >= 0) bodyId[i] else tagId[i] }
        }
        return best
    }

    /** Body entity id of one of the tracked creatures (ours first if [preferOwner] is null), or -1. */
    fun anyBodyId(preferOwner: String? = null): Int {
        var fallback = -1
        for (i in 0 until size) {
            val id = if (bodyId[i] >= 0) bodyId[i] else tagId[i]
            val o = owners[i]
            if (if (preferOwner == null) o == null else o != null && o.equals(preferOwner, true)) return id
            if (fallback < 0) fallback = id
        }
        return fallback
    }

    /** Who wants other players' sea creatures tracked: the party macro and/or the party HUD. */
    const val WANT_MACRO = 1
    const val WANT_HUD = 2
    private var othersWanted = 0

    /** Track other players' sea creatures too, while any source wants it. When none do, they're dropped. */
    val trackOthers: Boolean get() = othersWanted != 0

    fun want(source: Int, on: Boolean) {
        val was = othersWanted != 0
        othersWanted = if (on) othersWanted or source else othersWanted and source.inv()
        if (was && othersWanted == 0) dropOthers()
    }

    // -- pending catch (ours) ----------------------------------------------------------------------

    private var pendingName: String? = null
    private var pendingUntil = 0L
    private var pendingCount = 0
    private var doubleHookAt = 0L

    /**
     * When Hypixel last said our sea creature cap is full ("There is not enough space for another Sea
     * Creature!"), 0 if not since [clearServerFull]. That line is the ground truth: if the tracker missed a
     * spawn or wrongly counted one as dead, we're at the cap while [count] is still below the threshold.
     */
    var serverFullAt = 0L
        private set

    fun clearServerFull() { serverFullAt = 0L }

    /** When Hypixel last reported an ability hitting something ("Your Spirit Sceptre hit 10 enemies ..."). */
    var lastAbilityHitAt = 0L
        private set

    /**
     * Marks every creature combat would still kill (same filter as [killable]) as runic and logs its
     * tag, for when combat sees it outlive hits that killed the rest of its group: detection at spawn
     * missed it. Returns how many were marked.
     */
    fun markRemainingRunic(includeOthers: Boolean, spawnedBy: Long): Int {
        val level = Fish5.mc.level
        var n = 0
        for (i in 0 until size) {
            if (!wanted(i, includeOthers, true, spawnedBy)) continue
            runic[i] = true
            n++
            val tag = level?.getEntity(tagId[i])
            val body = if (bodyId[i] >= 0) level?.getEntity(bodyId[i]) as? LivingEntity else null
            Msg.dbg("SC: ${names[i]} outlived the group, marking runic. Tag '${tag?.customName?.string}', " +
                "body hp ${body?.health} / base max ${body?.getAttributeBaseValue(Attributes.MAX_HEALTH)}")
        }
        return n
    }

    private var lastUntrackedScan = 0L
    private const val UNTRACKED_SCAN_EVERY_MS = 5_000L
    private const val UNTRACKED_RADIUS = 20.0

    /**
     * Hypixel says we're full but we may be tracking fewer: find sea creature nametags near us that we
     * don't track, log them (so a miss can be traced), and, when solo, adopt them as ours. Not adopted
     * while tracking others: an untracked tag there may be a partner's. Rare (once per full message, at
     * most every [UNTRACKED_SCAN_EVERY_MS]), so the one list allocation is fine.
     */
    private fun adoptUntracked(now: Long) {
        val level = Fish5.mc.level ?: return
        val player = Fish5.mc.player ?: return
        val box = player.boundingBox.inflate(UNTRACKED_RADIUS, 10.0, UNTRACKED_RADIUS)
        val tags = level.getEntitiesOfClass(ArmorStand::class.java, box) { it.customName != null && !isTrackedTag(it.id) }
        var found = 0
        for (tag in tags) {
            val text = SkyblockData.stripColors(tag.customName?.string ?: continue)
            var name: String? = null
            for (n in allNames) if (tagMatches(text, n)) { name = n; break }
            if (name == null) continue
            found++
            val adopt = !trackOthers && size < MAX_TRACKED
            Msg.dbg("SC: untracked '$text' (tag ${tag.id}, age ${tag.tickCount / 20}s, " +
                "${String.format(java.util.Locale.US, "%.1f", Math.sqrt(tag.distanceToSqr(player)))} blocks)" +
                if (adopt) ", adopting as ours" else ", not adopted (tracking others)")
            if (adopt) add(level, tag, name, null, now, countCatch = false)
        }
        Msg.dbg("SC: Hypixel says the cap is full with $ownCount tracked; $found untracked nearby")
    }

    private var bobberX = 0.0
    private var bobberY = 0.0
    private var bobberZ = 0.0
    private var bobberSeenAt = 0L

    // -- other players' bobbers (last known spot per owner) ----------------------------------------

    private val bobOwner = arrayOfNulls<String>(MAX_BOBBERS)
    private val bobX = DoubleArray(MAX_BOBBERS)
    private val bobY = DoubleArray(MAX_BOBBERS)
    private val bobZ = DoubleArray(MAX_BOBBERS)
    private val bobSeen = LongArray(MAX_BOBBERS)

    private var lastLevel: Level? = null
    private var aliveTicks = 0
    private var othersTicks = 0
    private var bobberTicks = 0

    // predicates allocated once; matchName is set right before each claim scan
    private var matchName = ""
    private val freshTag = java.util.function.Predicate<ArmorStand> { stand ->
        stand.tickCount <= MAX_TAG_AGE_TICKS && !isTrackedTag(stand.id) && tagMatches(stand, matchName)
    }
    private val otherTag = java.util.function.Predicate<ArmorStand> { stand ->
        stand.tickCount in OTHERS_MIN_AGE_TICKS..OTHERS_MAX_AGE_TICKS && stand.customName != null && !isTrackedTag(stand.id)
    }
    private val bodyFilter = java.util.function.Predicate<LivingEntity> { e ->
        e !is ArmorStand && e !is Player && e.isAlive && !isTrackedBody(e.id)
    }
    private val othersHook = java.util.function.Predicate<FishingHook> { h ->
        val o = h.playerOwner
        o != null && o !== Fish5.mc.player
    }

    /** Every non-actionbar chat line (raw text, colours allowed). */
    fun onChat(raw: String) {
        val text = SkyblockData.stripColors(raw).trim()
        if (text.startsWith("It's a Double Hook!")) {
            doubleHookAt = System.currentTimeMillis()
            return
        }
        // "Your Spirit Sceptre hit 10 enemies for 428,942.6 damage." (any ability: Implosion, etc.)
        if (text.startsWith("Your ") && text.indexOf(" hit ") > 0 &&
            (text.indexOf(" enemies for ") > 0 || text.indexOf(" enemy for ") > 0)) {
            lastAbilityHitAt = System.currentTimeMillis()
            return
        }
        if (text.startsWith("There is not enough space for another Sea Creature")) {
            val now = System.currentTimeMillis()
            serverFullAt = now
            if (now - lastUntrackedScan >= UNTRACKED_SCAN_EVERY_MS) {
                lastUntrackedScan = now
                adoptUntracked(now)
            }
            return
        }
        // a corrupted catch may name the creature with "Corrupted " in front: look it up without that too
        val name = catchLines[text]
            ?: (if (text.contains("Corrupted ")) catchLines[text.replace("Corrupted ", "")] else null)
        if (name == null) {
            if (Msg.debug && text.contains("Corrupted") && !text.contains("Fragment")) Msg.dbg("SC: unknown corrupted line '$text'")
            return
        }
        val now = System.currentTimeMillis()
        if (now - bobberSeenAt > BOBBER_MEMORY_MS) {
            Msg.dbg("SC: '$name' catch line but no recent bobber of ours, ignored")
            return
        }
        pendingName = name
        pendingCount = if (now - doubleHookAt <= DOUBLE_HOOK_WINDOW_MS) 2 else 1
        pendingUntil = now + CLAIM_WINDOW_MS
        doubleHookAt = 0L
        Msg.dbg("SC: caught $name x$pendingCount, looking for it")
    }

    /** Client tick (from Fish5). Cheap when idle: one bobber read, plus throttled checks. */
    fun tick() {
        val level = Fish5.mc.level
        val player = Fish5.mc.player
        if (level !== lastLevel) { reset(); lastLevel = level }
        if (level == null || player == null) return
        val now = System.currentTimeMillis()

        player.fishing?.let {
            bobberX = it.x; bobberY = it.y; bobberZ = it.z
            bobberSeenAt = now
        }

        if (pendingName != null) {
            if (now > pendingUntil) {
                Msg.dbg("SC: didn't find $pendingCount x $pendingName near the bobber")
                pendingName = null
            } else {
                claim(level, now)
            }
        }

        if (trackOthers) {
            if (++bobberTicks >= BOBBER_SCAN_TICKS) { bobberTicks = 0; updateBobbers(level, player, now) }
            if (++othersTicks >= OTHERS_SCAN_TICKS) { othersTicks = 0; scanOthers(level, player, now) }
        } else if (now - bobberSeenAt <= OWNER_TIME_MS && ++othersTicks >= OTHERS_SCAN_TICKS) {
            othersTicks = 0
            scanOwnCorrupted(level, now)
        }

        if (size > 0 && ++aliveTicks >= CHECK_EVERY_TICKS) {
            aliveTicks = 0
            checkAlive(level, player, now)
        }
    }

    // -- ours --------------------------------------------------------------------------------------

    private fun claim(level: Level, now: Long) {
        val name = pendingName ?: return
        matchName = name
        val box = AABB(bobberX - CLAIM_RADIUS, bobberY - 2.5, bobberZ - CLAIM_RADIUS,
            bobberX + CLAIM_RADIUS, bobberY + 4.0, bobberZ + CLAIM_RADIUS)
        val tags = level.getEntitiesOfClass(ArmorStand::class.java, box, freshTag)
        while (pendingCount > 0 && tags.isNotEmpty()) {
            var best = 0
            var bestD = Double.MAX_VALUE
            for (i in tags.indices) {
                val d = tags[i].distanceToSqr(bobberX, bobberY, bobberZ)
                if (d < bestD) { bestD = d; best = i }
            }
            add(level, tags.removeAt(best), name, null, now)
            pendingCount--
        }
        if (pendingCount <= 0) pendingName = null
    }

    // -- other players -----------------------------------------------------------------------------

    private fun updateBobbers(level: Level, player: Player, now: Long) {
        val box = player.boundingBox.inflate(OTHERS_RADIUS, 16.0, OTHERS_RADIUS)
        val hooks = level.getEntitiesOfClass(FishingHook::class.java, box, othersHook)
        for (h in hooks) {
            val owner = h.playerOwner ?: continue
            val name = owner.name.string
            var slot = -1
            var oldest = 0
            for (i in 0 until MAX_BOBBERS) {
                if (bobOwner[i] == name) { slot = i; break }
                if (bobSeen[i] < bobSeen[oldest]) oldest = i
            }
            if (slot < 0) { slot = oldest; bobOwner[slot] = name }
            bobX[slot] = h.x; bobY[slot] = h.y; bobZ[slot] = h.z
            bobSeen[slot] = now
        }
    }

    private fun scanOthers(level: Level, player: Player, now: Long) {
        val box = player.boundingBox.inflate(OTHERS_RADIUS, 16.0, OTHERS_RADIUS)
        val tags = level.getEntitiesOfClass(ArmorStand::class.java, box, otherTag)
        for (tag in tags) {
            if (size >= MAX_TRACKED) return
            val text = SkyblockData.stripColors(tag.customName?.string ?: continue)
            var name: String? = null
            for (n in allNames) if (tagMatches(text, n)) { name = n; break }
            if (name == null) continue
            val spawned = now - tag.tickCount * 50L
            // corrupted catches can come without the catch line our claim waits for, so our own bobber
            // competes for those too
            val owner = ownerNear(tag.x, tag.y, tag.z, spawned, includeSelf = text.contains("Corrupted "))
            add(level, tag, name, owner, now)
        }
    }

    // solo: no one else is tracked, so a corrupted creature our claim missed, right at our bobber, is ours
    private fun scanOwnCorrupted(level: Level, now: Long) {
        val box = AABB(bobberX - OWNER_RADIUS, bobberY - 2.5, bobberZ - OWNER_RADIUS,
            bobberX + OWNER_RADIUS, bobberY + 4.0, bobberZ + OWNER_RADIUS)
        val tags = level.getEntitiesOfClass(ArmorStand::class.java, box, otherTag)
        for (tag in tags) {
            if (size >= MAX_TRACKED) return
            val text = SkyblockData.stripColors(tag.customName?.string ?: continue)
            if (!text.contains("Corrupted ")) continue
            var name: String? = null
            for (n in allNames) if (tagMatches(text, n)) { name = n; break }
            if (name == null) { Msg.dbg("SC: corrupted tag '$text' matched no sea creature"); continue }
            Msg.dbg("SC: corrupted $name at our bobber without a catch line, counting it as ours")
            add(level, tag, name, null, now)
        }
    }

    // whoever's bobber was last seen closest to the spawn, around the time it spawned (null = ours,
    // only with includeSelf)
    private fun ownerNear(x: Double, y: Double, z: Double, spawned: Long, includeSelf: Boolean = false): String? {
        var best: String? = null
        var bestD = OWNER_RADIUS * OWNER_RADIUS
        if (includeSelf && Math.abs(bobberSeenAt - spawned) <= OWNER_TIME_MS) {
            val dx = bobberX - x; val dz = bobberZ - z; val dy = (bobberY - y) * 0.5
            val d = dx * dx + dy * dy + dz * dz
            if (d < bestD) {
                bestD = d
                var other: String? = null
                for (i in 0 until MAX_BOBBERS) {
                    val o = bobOwner[i] ?: continue
                    if (Math.abs(bobSeen[i] - spawned) > OWNER_TIME_MS) continue
                    val ex = bobX[i] - x; val ez = bobZ[i] - z; val ey = (bobY[i] - y) * 0.5
                    val e = ex * ex + ey * ey + ez * ez
                    if (e < bestD) { bestD = e; other = o }
                }
                return other
            }
        }
        for (i in 0 until MAX_BOBBERS) {
            val o = bobOwner[i] ?: continue
            if (Math.abs(bobSeen[i] - spawned) > OWNER_TIME_MS) continue
            val dx = bobX[i] - x; val dz = bobZ[i] - z; val dy = (bobY[i] - y) * 0.5
            val d = dx * dx + dy * dy + dz * dz
            if (d < bestD) { bestD = d; best = o }
        }
        return best ?: UNKNOWN
    }

    private fun dropOthers() {
        var i = 0
        while (i < size) if (owners[i] != null) removeAt(i) else i++
        bobOwner.fill(null)
    }

    // -- shared ------------------------------------------------------------------------------------

    private fun add(level: Level, tag: ArmorStand, name: String, owner: String?, now: Long, countCatch: Boolean = true) {
        if (size >= MAX_TRACKED) return
        val body = findBody(level, tag)
        val ref: Entity = body ?: tag
        val i = size++
        if (owner == null) {
            ownCount++
            if (countCatch) com.fish5.features.MacroSession.onCatch()
        }
        bodyId[i] = body?.id ?: -1
        tagId[i] = tag.id
        names[i] = name
        owners[i] = owner
        spawnAt[i] = now - ref.tickCount * 50L
        lastSeenAt[i] = now
        lastX[i] = ref.x; lastY[i] = ref.y; lastZ[i] = ref.z
        runic[i] = isRunic(body, tag)
        Msg.dbg("SC: +$name${if (runic[i]) " (runic)" else ""} (${owner ?: "ours"}, body ${bodyId[i]}, tag ${tag.id}), " +
            "ours $ownCount, others ${size - ownCount}")
    }

    // SkyHanni's check (EntityUtils.isRunic): at spawn a runic mob's base max-health attribute is 4x its
    // health (12x if also corrupted), after undoing mayor perks: Derpy's double health halves it, Aura's
    // "Work Harder" takes it to 10/11. SkyHanni reads the mayor; we accept every runic ratio instead,
    // since none of them overlap a non-runic one (1, 3, and those x1/2, x10/11, x5/11).
    // Read once, at claim time, while it's still at full health.
    private val RUNIC_RATIOS = doubleArrayOf(
        4.0, 12.0,                    // no perk
        2.0, 6.0,                     // Derpy
        40.0 / 11, 120.0 / 11,        // Aura
        20.0 / 11, 60.0 / 11,         // both
    )

    private fun isRunic(body: LivingEntity?, tag: ArmorStand): Boolean {
        val tagText = tag.customName?.string
        if (tagText != null && SkyblockData.stripColors(tagText).contains("Runic ")) return true
        if (body == null) return false
        val hp = body.health.toDouble()
        if (hp <= 0.0 || hp == 1024.0) return false
        val ratio = body.getAttributeBaseValue(Attributes.MAX_HEALTH) / hp
        for (r in RUNIC_RATIOS) if (Math.abs(ratio - r) <= r * 0.02) return true
        return false
    }

    // Hypixel spawns the body first and its nametag stand right after (id + 1); fall back to the
    // nearest living thing under the tag.
    private fun findBody(level: Level, tag: ArmorStand): LivingEntity? {
        val byId = level.getEntity(tag.id - 1)
        if (byId is LivingEntity && byId !is ArmorStand && byId !is Player && byId.distanceToSqr(tag) < 9.0) return byId
        val box = AABB(tag.x - 1.5, tag.y - 3.5, tag.z - 1.5, tag.x + 1.5, tag.y + 0.5, tag.z + 1.5)
        val near = level.getEntitiesOfClass(LivingEntity::class.java, box, bodyFilter)
        var best: LivingEntity? = null
        var bestD = Double.MAX_VALUE
        for (e in near) {
            val d = e.distanceToSqr(tag)
            if (d < bestD) { bestD = d; best = e }
        }
        return best
    }

    /** Re-check every tracked creature right now (combat calls this before each click, so it stops on the kill). */
    fun refreshNow() {
        if (size == 0) return
        val level = Fish5.mc.level ?: return
        val player = Fish5.mc.player ?: return
        checkAlive(level, player, System.currentTimeMillis())
    }

    private fun checkAlive(level: Level, player: Player, now: Long) {
        var i = 0
        while (i < size) {
            val reason = goneReason(level, player, i, now)
            if (reason != null) {
                Msg.dbg("SC: -${names[i]} (${owners[i] ?: "ours"}, $reason)")
                if (owners[i] == null && reason == "died") com.fish5.features.MacroSession.onKill()
                removeAt(i)
            } else {
                i++
            }
        }
    }

    private fun goneReason(level: Level, player: Player, i: Int, now: Long): String? {
        if (now - spawnAt[i] > DESPAWN_MS) return "despawn time"
        val e: Entity? = if (bodyId[i] >= 0) level.getEntity(bodyId[i]) else level.getEntity(tagId[i])
        if (e != null) {
            if (e.isRemoved || (e is LivingEntity && e.isDeadOrDying)) return "died"
            // the tag can be renamed after we claim it: look for "Runic" again for the first few seconds
            if (!runic[i] && now - spawnAt[i] < RUNIC_RECHECK_MS) {
                val t = level.getEntity(tagId[i])?.customName?.string
                if (t != null && SkyblockData.stripColors(t).contains("Runic ")) {
                    runic[i] = true
                    Msg.dbg("SC: ${names[i]} tag now says runic")
                }
            }
            lastSeenAt[i] = now
            lastX[i] = e.x; lastY[i] = e.y; lastZ[i] = e.z
            return null
        }
        // not loaded: if we're right there it really is gone; if we walked off, keep it until despawn
        val dx = player.x - lastX[i]; val dy = player.y - lastY[i]; val dz = player.z - lastZ[i]
        val near = dx * dx + dy * dy + dz * dz < GONE_NEAR_DISTANCE * GONE_NEAR_DISTANCE
        return if (near && now - lastSeenAt[i] > GONE_GRACE_MS) "gone" else null
    }

    private fun removeAt(i: Int) {
        if (owners[i] == null) ownCount--
        val last = --size
        if (i != last) {
            bodyId[i] = bodyId[last]; tagId[i] = tagId[last]; names[i] = names[last]; owners[i] = owners[last]
            spawnAt[i] = spawnAt[last]; lastSeenAt[i] = lastSeenAt[last]; runic[i] = runic[last]
            lastX[i] = lastX[last]; lastY[i] = lastY[last]; lastZ[i] = lastZ[last]
        }
        names[last] = null
        owners[last] = null
    }

    private fun isTrackedTag(id: Int): Boolean {
        for (i in 0 until size) if (tagId[i] == id) return true
        return false
    }

    private fun isTrackedBody(id: Int): Boolean {
        for (i in 0 until size) if (bodyId[i] == id) return true
        return false
    }

    private fun tagMatches(stand: ArmorStand, name: String): Boolean {
        val custom = stand.customName ?: return false
        return tagMatches(SkyblockData.stripColors(custom.string), name)
    }

    // "[Lv5] Flaming Worm 1,200/1,200❤", maybe with symbol prefixes or a Corrupted/Runic tag. The name
    // has to be a whole match, so "Water Worm" doesn't claim a "Poisoned Water Worm".
    // Corrupted tags wrap the name in one extra character on each side ("[Lv5] ⚓ xCorrupted Flaming Wormx 1,200❤",
    // see SkyHanni's MobFilter.mobNameFilter + removeCorruptedSuffix), so allow those two characters.
    private fun tagMatches(text: String, name: String): Boolean {
        val idx = text.indexOf(name)
        if (idx < 0) return false
        val cor = text.lastIndexOf("Corrupted ", idx)
        val corrupted = cor >= 0
        val end = idx + name.length
        if (end < text.length && text[end] != ' ') {
            // corrupted: one suffix character, then the space before the health
            if (!corrupted || (end + 1 < text.length && text[end + 1] != ' ')) return false
        }
        var j = 0
        while (j < idx) {
            if (corrupted && j == cor - 1) { j++; continue }  // the character glued in front of "Corrupted"
            val c = text[j]
            if (c == '[') {                       // [Lv123]
                val close = text.indexOf(']', j)
                if (close < 0 || close >= idx) return false
                j = close + 1
                continue
            }
            if (c.isLetter()) {
                val word = if (text.startsWith("Corrupted ", j)) 10 else if (text.startsWith("Runic ", j)) 6 else return false
                j += word
                continue
            }
            j++
        }
        return true
    }

    fun reset() {
        size = 0
        ownCount = 0
        names.fill(null)
        owners.fill(null)
        bobOwner.fill(null)
        pendingName = null
        pendingCount = 0
        doubleHookAt = 0L
        serverFullAt = 0L
        lastAbilityHitAt = 0L
        bobberSeenAt = 0L
        aliveTicks = 0; othersTicks = 0; bobberTicks = 0
    }

    /** /f5 sc: what is tracked right now. */
    /** Who the tracker credits the creature with this body or tag id to ("you", a name, "?"), or null if untracked. */
    fun describeOwner(entityId: Int): String? {
        for (i in 0 until size) {
            if (bodyId[i] == entityId || tagId[i] == entityId) return owners[i] ?: "you"
        }
        return null
    }

    fun report() {
        val now = System.currentTimeMillis()
        Msg.chat("§b[Sea creatures] §f$ownCount§7 yours, §f${size - ownCount}§7 others" +
            if (trackOthers) "" else " §8(others only tracked in party mode or with the Party Sea Creatures HUD)")
        for (i in 0 until size) {
            val left = (spawnAt[i] + DESPAWN_MS - now) / 1000
            val who = owners[i]?.let { "§e$it" } ?: "§ayou"
            Msg.chat("§7  ${names[i]}${if (runic[i]) " §d(runic)" else ""} §8- $who §7despawns in §f${left / 60}m ${left % 60}s")
        }
    }
}
