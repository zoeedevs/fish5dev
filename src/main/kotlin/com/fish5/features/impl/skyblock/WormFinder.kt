package com.fish5.features.impl.skyblock

import com.fish5.Fish5
import com.fish5.automation.AutoWorker
import com.fish5.automation.Warps
import com.fish5.notification.NotificationManager
import com.fish5.sb.SkyblockData
import com.fish5.utils.Color
import net.minecraft.client.Options
import net.minecraft.core.BlockPos
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.tags.FluidTags
import net.minecraft.tags.TagKey
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.Fluid
import java.util.concurrent.CompletableFuture

/**
 * `/f5 wormhelper find <lava|water>` — Crystal Hollows worm-fishing spot finder. Bumps render distance to
 * Extreme (32 chunks, see [Options.RENDER_DISTANCE_EXTREME]) so far-away pools are actually loaded,
 * then walks every loaded chunk column in range looking for exposed lava/water source blocks and
 * highlights the fishable ones with a solid-fill "cham" via the `net.minecraft.gizmos`
 * debug-draw API — the same mechanism vanilla's own hitbox/water debug renderers use, drawn through
 * [com.fish5.Fish5]'s normal client tick, independent of the F3 debug screen.
 *
 * A "spot" is a flat, exposed pool above y=68: a single water source block is enough (water spreads,
 * so even one tile reads as fishable), but lava always highlights as one or more clean 2x1/1x2/2x2 rows
 * found *within* the connected pool (capped at a handful per pool, see "Max spots per pool") rather than
 * the whole connected blob — an odd-shaped or oversized pool still has a real usable edge buried in it
 * (you can always destroy a lava source by placing a block into it, trimming an awkward shape down to a
 * clean row, just never add a source to pad one out), so what matters is whether a 2x1/2x2 exists inside
 * the pool, not whether the whole pool already is one. Lava spots only ever exist in the Precursor Ruins
 * quadrant (the "blue" area): Crystal Hollows splits into its four quadrants at
 * world X/Z = 513, and Precursor Ruins is `x > 513 && z > 513` — lava scanning is restricted to that
 * quadrant (also shrinking the scan area to a quarter); water isn't restricted.
 *
 * Scanning is spread over many client ticks (budgeted in chunk columns/tick) instead of done in one
 * freezing burst; `/f5 wormhelper find <lava|water>` toggles scanning/highlighting per fluid type and
 * `/f5 wormhelper find stop` drops everything at once.
 *
 * Render distance (and the Precursor Ruins chunk-cache focus) is only bumped for the duration of the
 * scan itself — it snaps back to your normal value the instant a scan finishes. The cham highlights
 * keep rendering afterwards regardless: [Gizmos] are pure world-space primitives drawn independently
 * of chunk mesh/load state, so a spot's cham stays visible even once its block is well outside your
 * restored render distance and no longer actually loaded.
 */
object WormFinder {

    private val mc get() = Fish5.mc

    enum class FluidKind(val label: String, val tag: TagKey<Fluid>, val minPoolSize: Int) {
        LAVA("Lava", FluidTags.LAVA, 2),
        WATER("Water", FluidTags.WATER, 1),
    }

    // Settings live on the combined Worm Helper module (one card in the click GUI).
    private val sAutoRenderDistance get() = WormHelper.findAutoRenderDistance
    private val sColumnsPerTick get() = WormHelper.findColumnsPerTick
    private val lavaColor get() = WormHelper.lavaColor
    private val waterColor get() = WormHelper.waterColor
    private val sChatUpdates get() = WormHelper.findChatUpdates
    private val sEnforceMinY get() = WormHelper.findEnforceMinY
    private val sForceLoadPrecursor get() = WormHelper.findForceLoadPrecursor
    private val sMaxSpotsPerPool get() = WormHelper.findMaxSpotsPerPool

    /** Pools at or below this Y don't count as fishable worm spots (toggle: [sEnforceMinY]). */
    private const val MIN_SPOT_Y = 68

    /** Crystal Hollows quadrant split, world block coords. Precursor
     * Ruins (the "blue" area lava spots live in) is x > min && z > min. */
    private const val PRECURSOR_MIN_X = 513
    private const val PRECURSOR_MIN_Z = 513
    private const val PRECURSOR_MAX_X = 1024
    private const val PRECURSOR_MAX_Z = 1024
    private val precursorCenterChunkX = ((PRECURSOR_MIN_X + PRECURSOR_MAX_X) / 2) shr 4
    private val precursorCenterChunkZ = ((PRECURSOR_MIN_Z + PRECURSOR_MAX_Z) / 2) shr 4

    private fun inPrecursorRuins(x: Int, z: Int) = x > PRECURSOR_MIN_X && z > PRECURSOR_MIN_Z

    private const val GIZMO_LIFETIME_MS = 400

    private class Job(val kind: FluidKind, val template: Boolean = false) {
        // scanning/active/spots are read from the auto-hop background thread (runScanAndWait) while
        // written from the client thread (start/finishScan/stop) — @Volatile for cross-thread visibility.
        @Volatile var active = false
        @Volatile var scanning = false
        var pending: ArrayDeque<ChunkPos> = ArrayDeque()
        val candidates: HashSet<BlockPos> = HashSet()
        /** Template scan only: build sites matched so far (lava cells first, then the stand cells). */
        val found: ArrayList<List<BlockPos>> = ArrayList()
        @Volatile var spots: List<List<BlockPos>> = emptyList()
        val label get() = if (template) "lava build" else kind.label.lowercase()
    }

    private val jobs: Map<FluidKind, Job> = FluidKind.entries.associateWith { Job(it) }

    /** `/f5 wormhelper find lava test` — the dig-out build template scan (see [BuildTemplate]). */
    private val templateJob = Job(FluidKind.LAVA, template = true)

    private val allJobs: List<Job> = jobs.values.toList() + templateJob

    private var savedRenderDistance: Int? = null

    /** Which fluid kind [autoHopLoop] is currently re-rolling instances for, null if not auto-hopping. */
    @Volatile private var autoHopKind: FluidKind? = null

    /** Safety cap on [autoHopLoop] so a stuck/unlucky run doesn't hop forever unattended. */
    private const val AUTO_HOP_MAX_ATTEMPTS = 50

    // -- Command entry point (Fish5's /f5 wormhelper find <lava|water>) ----

    /** `/f5 wormhelper find <lava|water>` — (re)starts a scan for [kind], whether or not the Worm Helper
     * module is enabled. Running it again while a scan is active clears it and starts fresh; use
     * [cancelAll] (`/f5 wormhelper find stop`) to actually stop.
     *
     * Checks [inCrystalHollows] first: scanning anywhere else can't find real spots (lava is gated to
     * the Precursor Ruins quadrant and water pools elsewhere won't pass the fluid/safe-wall checks
     * either), so if you're not there it warps you in first (the same `/warp nuc` [autoHopLoop] already
     * uses to re-roll instances) and only starts the scan once [waitForLocationChange] confirms you've
     * actually landed, instead of silently scanning the wrong place like it used to. */
    fun restart(kind: FluidKind) = restartJob(jobs.getValue(kind))

    /** `/f5 wormhelper find lava test` — scan for spots where the lava build can be dug out (see
     * [BuildTemplate]): a 2x1 lava row you stand level with, a 2x2 hole with a 2-block drop behind you,
     * 3 blocks of headroom, nothing to mine but air/Hardstone, and no glass or panes in it or its walls. */
    fun restartTemplate() = restartJob(templateJob)

    private fun restartJob(job: Job) {
        if (job.active) stop(job)

        if (inCrystalHollows()) {
            start(job)
            return
        }

        NotificationManager.notify("§e[Worm Finder] §7Not in the Crystal Hollows — warping there first (§f/warp nuc§7)…")
        val started = AutoWorker.submit("Worm Finder warp-to-CH (${job.label})") { warpToCrystalHollowsThenStart(job) }
        if (!started) NotificationManager.notify("§c[Worm Finder] §7Busy — another automation is already running. Try again shortly.")
    }

    /** Background half of [restart]'s warp -- runs on [AutoWorker]'s thread so the blocking warp +
     * location poll doesn't freeze the client; only [start] (via [mc.execute]) touches game state back
     * on the client thread. */
    private fun warpToCrystalHollowsThenStart(job: Job) {
        if (!Warps.warpWithRetry(mc, "warp nuc", 4)) {
            NotificationManager.notify("§c[Worm Finder] §7Couldn't warp to the Crystal Hollows — §f/warp nuc §7failed after retries.")
            return
        }
        if (AutoWorker.aborted()) return
        waitForLocationChange(wantCrystalHollows = true)
        if (AutoWorker.aborted()) return
        mc.execute { start(job) }
    }

    /** `/f5 wormhelper find <lava|water> auto` — keep re-rolling the Crystal Hollows instance (`/is` then
     * `/warp nuc`, which hands you a freshly generated instance on return) until a scan actually finds
     * at least one spot, then stops automatically: scan → 0 found → `/is` → wait → `/warp nuc` → wait →
     * clear + rescan → repeat until a spot turns up. Runs as an [AutoWorker] job, the same background
     * worker every other blocking automation sequence in this mod (menu flows, auto forge) already runs on — which means the existing "press X to abort" cancel-watcher in
     * [com.fish5.Fish5] already covers this for free, no extra wiring needed. */
    fun startAutoHop(kind: FluidKind) {
        if (autoHopKind != null) {
            // AutoWorker only runs one job at a time, and its "busy" check is synchronous against the
            // currently-submitted job -- the old run has to actually notice the abort flag and unwind
            // before a new submit can succeed. So the restart happens on a short-lived watcher thread
            // (not AutoWorker's own single executor, which is exactly what's being waited on) that
            // polls until the slot frees up, bounded so a stuck run can't hang this forever.
            NotificationManager.notify("§e[Worm Finder] §7Restarting auto-hop for ${kind.label.lowercase()}…")
            AutoWorker.requestAbort()
            Thread {
                val deadline = System.currentTimeMillis() + 5000
                while (AutoWorker.isBusy() && System.currentTimeMillis() < deadline) Thread.sleep(100)
                if (AutoWorker.submit("Worm Finder auto-hop (${kind.label})") { autoHopLoop(kind) }) autoHopKind = kind
                else NotificationManager.notify("§c[Worm Finder] §7Couldn't restart — the previous run didn't stop in time.")
            }.apply { isDaemon = true; name = "fish5-wormfinder-restart"; start() }
            return
        }
        val started = AutoWorker.submit("Worm Finder auto-hop (${kind.label})") { autoHopLoop(kind) }
        if (started) autoHopKind = kind
    }

    /** The actual auto-hop body, run on [AutoWorker]'s background thread — see [startAutoHop]. */
    private fun autoHopLoop(kind: FluidKind) {
        val job = jobs.getValue(kind)
        var success = false
        try {
            for (attempt in 1..AUTO_HOP_MAX_ATTEMPTS) {
                if (AutoWorker.aborted()) return
                if (sChatUpdates) {
                    NotificationManager.notify(
                        "§b[Worm Finder] §7Auto-hop: attempt §f$attempt§7/§f$AUTO_HOP_MAX_ATTEMPTS §7— scanning…",
                    )
                }
                if (!runScanAndWait(job)) {
                    NotificationManager.notify("§c[Worm Finder] §7Auto-hop stopped — couldn't start a scan (not in world/on SkyBlock).")
                    return
                }
                if (AutoWorker.aborted()) return
                if (job.spots.isNotEmpty()) {
                    NotificationManager.notify(
                        "§a[Worm Finder] §7Auto-hop found §f${job.spots.size} §7${kind.label.lowercase()} spot(s) " +
                            "after §f$attempt §7attempt(s) — stopping.",
                    )
                    success = true
                    return
                }

                if (sChatUpdates) {
                    NotificationManager.notify("§e[Worm Finder] §7Auto-hop: no spots — rerolling (§f/is§7 → §f/warp nuc§7)…")
                }
                if (!Warps.warpWithRetry(mc, "is", 4)) {
                    NotificationManager.notify("§c[Worm Finder] §7Auto-hop stopped — §f/is §7failed after retries.")
                    return
                }
                waitForLocationChange(wantCrystalHollows = false)
                if (AutoWorker.aborted()) return
                if (!Warps.warpWithRetry(mc, "warp nuc", 4)) {
                    NotificationManager.notify("§c[Worm Finder] §7Auto-hop stopped — §f/warp nuc §7failed after retries.")
                    return
                }
                waitForLocationChange(wantCrystalHollows = true)
            }
            NotificationManager.notify(
                "§c[Worm Finder] §7Auto-hop gave up — no ${kind.label.lowercase()} spots after §f$AUTO_HOP_MAX_ATTEMPTS §7attempts.",
            )
        } finally {
            autoHopKind = null
            // Leave the final successful scan's highlights/active state alone; any other exit (abort,
            // a failed warp, or running out of attempts) gets the normal full cleanup.
            if (!success) mc.execute { if (job.active) stop(job) }
        }
    }

    /** Starts one scan on the client thread (via [start]) and blocks this background thread — sleeping,
     * not spinning — until it finishes. [start] can fail to actually begin (not in a world yet, e.g.
     * right after a warp is still loading); that's retried a few times with a short wait before this
     * gives up and returns false, which [autoHopLoop] treats as a hard stop rather than an empty result. */
    private fun runScanAndWait(job: Job): Boolean {
        var began = false
        for (attempt in 1..3) {
            if (AutoWorker.aborted()) return false
            val result = CompletableFuture<Boolean>()
            mc.execute { result.complete(start(job)) }
            began = try { result.get() } catch (e: Exception) { false }
            if (began) break
            AutoWorker.sleep(2000)
        }
        if (!began) return false
        while (job.scanning && !AutoWorker.aborted()) AutoWorker.sleep(150)
        return true
    }

    /** `/f5 wormhelper find stop` — drop every active scan/highlight at once. */
    fun cancelAll() {
        val active = allJobs.filter { it.active }
        if (active.isEmpty()) {
            NotificationManager.notify("§e[Worm Finder] §7Nothing active to cancel.")
            return
        }
        for (job in active) stop(job)
    }

    /** Returns true once a scan has actually begun (job.active/scanning flipped on), false if it bailed
     * out early (not in a world, or not on SkyBlock) — [runScanAndWait] relies on that to tell "couldn't
     * start yet" apart from "started and already finished with nothing". */
    private fun start(job: Job): Boolean {
        val level = mc.level
        val player = mc.player
        if (level == null || player == null) {
            NotificationManager.notify("§cNot in a world.")
            return false
        }
        if (!SkyblockData.isOnSkyblock(mc)) {
            NotificationManager.notify("§cNot on SkyBlock.")
            return false
        }
        if (!inCrystalHollows()) {
            NotificationManager.notify("§e[Worm Finder] §7Doesn't look like you're in the Crystal Hollows right now — scanning anyway.")
        }

        if (scanningJobCount() == 0 && sAutoRenderDistance) {
            savedRenderDistance = mc.options.renderDistance().get()
            mc.options.renderDistance().set(Options.RENDER_DISTANCE_EXTREME)
        }

        job.active = true
        job.scanning = true
        job.candidates.clear()
        job.found.clear()
        job.spots = emptyList()
        job.pending = buildColumnQueue(player.blockPosition(), mc.options.renderDistance().get(), job.kind)

        if (sChatUpdates) {
            NotificationManager.notify("§b[Worm Finder] §7Scanning §f${job.pending.size} §7chunks for ${job.label} spots…")
        }
        return true
    }

    private fun stop(job: Job) {
        job.active = false
        job.scanning = false
        job.pending.clear()
        job.candidates.clear()
        job.found.clear()
        job.spots = emptyList()

        // Mid-scan cancel: hand render distance + chunk focus back right away. If the scan had already
        // finished (just highlighting), these are harmless no-ops — both were already released/restored
        // back in finishScan().
        restoreRenderDistanceIfIdle()
        if (job.kind == FluidKind.LAVA) releasePrecursorFocus()

        if (sChatUpdates) {
            NotificationManager.notify("§b[Worm Finder] §7Stopped ${job.label} highlighting.")
        }
    }

    private fun scanningJobCount(): Int = allJobs.count { it.scanning }

    /** Hands render distance back the moment nothing is scanning any more (highlighting doesn't need it). */
    private fun restoreRenderDistanceIfIdle() {
        if (scanningJobCount() != 0) return
        savedRenderDistance?.let { mc.options.renderDistance().set(it) }
        savedRenderDistance = null
    }

    /** Checks both the sidebar's "Area:" line and the tab-list Info panel's own "Area:" line — the
     * sidebar can report the island-level area rather than exactly where you are, so the tab list
     * is checked too; either source saying "Crystal Hollows" is enough. */
    /** Replaces a flat ~6s guess with polling the scoreboard's "Area:" line (via [inCrystalHollows])
     * so a re-roll proceeds the moment the warp actually lands, instead of always waiting the full
     * window -- capped at [timeoutMs] so a stuck/slow load can't hang the loop. */
    private fun waitForLocationChange(wantCrystalHollows: Boolean, timeoutMs: Long = 10_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (AutoWorker.aborted()) return false
            if (inCrystalHollows() == wantCrystalHollows) return true
            AutoWorker.sleep(200)
        }
        return inCrystalHollows() == wantCrystalHollows
    }

    // Widened from private so WormfishProfitTracker's "actively worm fishing" check can reuse this
    // instead of duplicating the same sidebar/tab-list area detection.
    internal fun inCrystalHollows(): Boolean =
        SkyblockData.getSidebarLines(mc).any { it.contains("Crystal Hollows", ignoreCase = true) } ||
            SkyblockData.getTabListLines(mc).any { it.contains("Crystal Hollows", ignoreCase = true) }

    // -- Tick loop (called from Fish5's END_CLIENT_TICK) ---------------

    fun tick() {
        val level = mc.level ?: return
        var any = false
        for (job in allJobs) {
            if (!job.active) continue
            any = true
            if (job.scanning) processScan(job, level)
        }
        if (any) emitGizmos()
    }

    private fun processScan(job: Job, level: Level) {
        // Re-centre the client's chunk view onto Precursor Ruins every tick while lava scans, for this reason: a server-driven
        // chunk-cache-center update (e.g. from the real player moving) would otherwise silently undo a
        // one-time override.
        if (job.kind == FluidKind.LAVA && sForceLoadPrecursor) forcePrecursorFocus()

        var budget = sColumnsPerTick
        while (budget > 0 && job.pending.isNotEmpty()) {
            val cp = job.pending.removeFirst()
            if (!level.hasChunk(cp.x, cp.z)) continue // ungenerated/unloaded — free skip, no scanning cost
            scanColumn(level, cp, job)
            budget--
        }
        if (job.pending.isEmpty()) finishScan(job)
    }

    /** Non-respawning wall materials in the Precursor Ruins: a pool bordered by one of these can be
     * dug out and boxed in for a worm trap without the walls growing back (unlike cobblestone, which
     * the Ruins regenerate). Plain stone only -- not cobblestone. */
    // Per-color blocks are ColorCollections indexed by DyeColor (Blocks.WOOL / Blocks.DYED_TERRACOTTA).
    private val SAFE_WALL_BLOCKS = setOf(Blocks.WOOL.gray(), Blocks.DYED_TERRACOTTA.cyan(), Blocks.STONE)

    /** Needs 2 full blocks of safe wall stacked at the same adjacent column -- not just one -- so the
     * wall you'd actually be standing next to (player is 2 tall) is non-respawning top to bottom, not
     * just the bottom block with something else above it. */
    private fun hasSafeWall(level: Level, pos: BlockPos): Boolean {
        val mutable = BlockPos.MutableBlockPos()
        for ((dx, dz) in arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
            mutable.set(pos.x + dx, pos.y, pos.z + dz)
            if (level.getBlockState(mutable).block !in SAFE_WALL_BLOCKS) continue
            mutable.set(pos.x + dx, pos.y + 1, pos.z + dz)
            if (level.getBlockState(mutable).block in SAFE_WALL_BLOCKS) return true
        }
        return false
    }

    /** Air, or plain vanilla Stone -- the Crystal Hollows resource pack re-skins Stone as "Hardstone",
     * but it's still just Stone underneath and breaks like any other block, so it's no obstacle to a
     * spot being usable. Anything else (safe-wall materials, ores, etc.) is left as a real blocker. */
    private fun isClearOrHardstone(state: BlockState): Boolean =
        state.isAir || state.block == Blocks.STONE

    private fun scanColumn(level: Level, cp: ChunkPos, job: Job) {
        val minY = if (sEnforceMinY) maxOf(level.minY, MIN_SPOT_Y + 1) else level.minY
        val maxY = level.maxY
        val baseX = cp.minBlockX
        val baseZ = cp.minBlockZ
        val tag = job.kind.tag
        val pos = BlockPos.MutableBlockPos()
        for (dx in 0 until 16) {
            for (dz in 0 until 16) {
                for (y in minY until maxY) {
                    pos.set(baseX + dx, y, baseZ + dz)
                    val fluid = level.getFluidState(pos)
                    if (fluid.isEmpty || !fluid.isSource || !fluid.`is`(tag)) continue
                    if (job.template) {
                        if (inPrecursorRuins(pos.x, pos.z)) BuildTemplate.matchAt(level, pos, job.found)
                        continue
                    }
                    // 2 blocks of clearance above the source, not just 1 -- so once dug out there's
                    // room to actually stand in it (player is 2 tall), not just a 1-high crawlspace.
                    // Doesn't need to already be air: plain Hardstone (vanilla Stone, re-skinned by
                    // the Crystal Hollows resource pack) is freely breakable, so a spot that's merely
                    // capped with Hardstone is just as usable as one that's already open.
                    if (!isClearOrHardstone(level.getBlockState(pos.above()))) continue
                    if (!isClearOrHardstone(level.getBlockState(pos.above().above()))) continue
                    if (job.kind == FluidKind.LAVA && !inPrecursorRuins(pos.x, pos.z)) continue
                    if (job.kind == FluidKind.LAVA && !hasSafeWall(level, pos)) continue
                    job.candidates.add(pos.immutable())
                }
            }
        }
    }

    private fun finishScan(job: Job) {
        job.scanning = false
        // Hand render distance back immediately — the cham doesn't need the chunk loaded to keep
        // rendering, only the scan did. Restore first so releasePrecursorFocus() below re-centres the
        // chunk cache at the final (now-restored) radius, not the still-bumped one.
        restoreRenderDistanceIfIdle()
        if (job.kind == FluidKind.LAVA) releasePrecursorFocus()
        if (job.template) {
            job.spots = ArrayList(job.found)
            job.found.clear()
            reportTemplateSpots(job.spots)
            return
        }
        job.spots = clusterSpots(job.candidates, job.kind)
        if (sChatUpdates) {
            val blocks = job.spots.sumOf { it.size }
            NotificationManager.notify(
                "§a[Worm Finder] §7Found §f${job.spots.size} §7${job.label} spot(s) (§f$blocks §7blocks).",
            )
        }
    }

    /** Always reported (it's a test command): the count plus the nearest few sites' lava coords. */
    private fun reportTemplateSpots(spots: List<List<BlockPos>>) {
        if (spots.isEmpty()) {
            NotificationManager.notify("§e[Worm Finder] §7No lava build spots found.")
            return
        }
        NotificationManager.notify("§a[Worm Finder] §7Found §f${spots.size} §7lava build spot(s).")
        val feet = mc.player?.blockPosition() ?: return
        for (spot in spots.sortedBy { distSq(it[0], feet) }.take(5)) {
            val a = spot[0]
            val b = spot[1]
            com.fish5.automation.Msg.chat("§7  lava §f${a.x} ${a.y} ${a.z} §7+ §f${b.x} ${b.y} ${b.z}")
        }
    }

    private val HORIZONTAL_OFFSETS = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    /** 4-connected (same-Y) flood fill over the candidate set, keeping only groups at least
     * [FluidKind.minPoolSize]. Water is kept as a single whole-group spot regardless of shape — any one
     * exposed tile is already fishable, no row needed. Lava always goes through [pickEdgeSpots] instead
     * of being highlighted as a whole blob, no matter how small: even an awkward L/T-shaped puddle has a
     * real lava source you can block off (placing a solid block destroys a lava source outright, so any
     * "extra" block hanging off a clean 2x1/2x2 can always be trimmed away — the one thing you can't do
     * is add a source block to turn a lone 1x1 into something usable). So what actually matters for a
     * spot is whether a clean 2x1/1x2/2x2 rectangle exists *somewhere inside* the pool, not whether the
     * whole connected blob already is one — [pickEdgeSpots] finds those pieces and caps the pick at
     * [sMaxSpotsPerPool] so a big lake doesn't light up in full either. */
    private fun clusterSpots(candidates: Set<BlockPos>, kind: FluidKind): List<List<BlockPos>> {
        val visited = HashSet<BlockPos>()
        val result = ArrayList<List<BlockPos>>()
        for (start in candidates) {
            if (!visited.add(start)) continue
            val group = ArrayList<BlockPos>()
            val queue = ArrayDeque<BlockPos>()
            queue.add(start)
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                group.add(cur)
                for ((ox, oz) in HORIZONTAL_OFFSETS) {
                    val n = cur.offset(ox, 0, oz)
                    if (n in candidates && visited.add(n)) queue.add(n)
                }
            }
            if (group.size < kind.minPoolSize) continue
            if (kind == FluidKind.WATER) {
                result.add(group)
                continue
            }
            result.addAll(pickEdgeSpots(group))
        }
        return result
    }

    /** Carves up to [sMaxSpotsPerPool] solid 2x1/1x2/2x2 rectangles out of a [group] instead of
     * highlighting the whole thing. A candidate rectangle must (a) have every one of its cells actually
     * in the pool, and (b) "touch the boundary" — at least one of its cells has a horizontal neighbor
     * that's NOT in the pool, meaning there's open ground right there to stand on and cast from. That
     * rules out picking a rectangle buried in the middle of the pool, which you can't approach in a
     * straight line. Picks are greedy-spread (each next pick is the farthest candidate from what's
     * already chosen) so the highlighted spots land around the shoreline instead of bunching in one
     * corner, and candidates overlapping an already-picked rectangle's blocks are skipped. */
    private fun pickEdgeSpots(group: List<BlockPos>): List<List<BlockPos>> {
        val poolSet = group.toHashSet()

        fun touchesBoundary(cells: List<BlockPos>): Boolean = cells.any { p ->
            HORIZONTAL_OFFSETS.any { (ox, oz) -> BlockPos(p.x + ox, p.y, p.z + oz) !in poolSet }
        }

        val candidates = ArrayList<List<BlockPos>>()
        for (p in group) {
            val east = BlockPos(p.x + 1, p.y, p.z)
            val south = BlockPos(p.x, p.y, p.z + 1)
            val southEast = BlockPos(p.x + 1, p.y, p.z + 1)
            if (east in poolSet) {
                val cells = listOf(p, east)
                if (touchesBoundary(cells)) candidates.add(cells)
            }
            if (south in poolSet) {
                val cells = listOf(p, south)
                if (touchesBoundary(cells)) candidates.add(cells)
            }
            if (east in poolSet && south in poolSet && southEast in poolSet) {
                val cells = listOf(p, east, south, southEast)
                if (touchesBoundary(cells)) candidates.add(cells)
            }
        }
        if (candidates.isEmpty()) return emptyList()

        val picked = ArrayList<List<BlockPos>>()
        val used = HashSet<BlockPos>()
        val remaining = candidates.toMutableList()
        while (picked.size < sMaxSpotsPerPool && remaining.isNotEmpty()) {
            val next = if (picked.isEmpty()) {
                remaining.first()
            } else {
                remaining.maxBy { cand -> cand.minOf { c -> picked.minOf { spot -> spot.minOf { distSq(it, c) } } } }
            }
            remaining.remove(next)
            if (next.any { it in used }) continue
            picked.add(next)
            used.addAll(next)
        }
        return picked
    }

    /** Squared horizontal (X/Z) distance between two same-Y positions — used only to spread
     * [pickEdgeSpots]'s picks apart, so no need to involve Y or go through a full 3D distance call. */
    private fun distSq(a: BlockPos, b: BlockPos): Long {
        val dx = (a.x - b.x).toLong()
        val dz = (a.z - b.z).toLong()
        return dx * dx + dz * dz
    }

    /** All chunk columns within [radiusChunks] of [center], nearest-first. Lava drops any chunk that
     * can't possibly touch the Precursor Ruins quadrant, shrinking its scan to roughly a quarter. */
    private fun buildColumnQueue(center: BlockPos, radiusChunks: Int, kind: FluidKind): ArrayDeque<ChunkPos> {
        val cx = center.x shr 4
        val cz = center.z shr 4
        val list = ArrayList<ChunkPos>((2 * radiusChunks + 1) * (2 * radiusChunks + 1))
        for (dx in -radiusChunks..radiusChunks) {
            for (dz in -radiusChunks..radiusChunks) {
                val cp = ChunkPos(cx + dx, cz + dz)
                if (kind == FluidKind.LAVA && (cp.maxBlockX <= PRECURSOR_MIN_X || cp.maxBlockZ <= PRECURSOR_MIN_Z)) continue
                list.add(cp)
            }
        }
        val origin = ChunkPos(cx, cz)
        list.sortBy { it.distanceSquared(origin) }
        return ArrayDeque(list)
    }

    // -- Force-load Precursor Ruins ---------------------------------------
    // Directly re-centres the client's own ClientChunkCache instead of the player's real position, so
    // terrain far outside normal view range is loaded and scannable.

    private fun forcePrecursorFocus() {
        val cache = mc.level?.chunkSource ?: return
        cache.updateViewRadius(mc.options.renderDistance().get())
        cache.updateViewCenter(precursorCenterChunkX, precursorCenterChunkZ)
    }

    private fun releasePrecursorFocus() {
        val level = mc.level ?: return
        val player = mc.player ?: return
        val cache = level.chunkSource
        val feet = player.chunkPosition()
        cache.updateViewCenter(feet.x, feet.z)
        cache.updateViewRadius(mc.options.renderDistance().get())
    }

    // -- Cham (solid-fill gizmo boxes, re-armed every tick while active) --

    private fun emitGizmos() {
        val collection = mc.collectPerTickGizmos()
        try {
            for (job in allJobs) {
                if (!job.active || job.spots.isEmpty()) continue
                val style = GizmoStyle.fill(colorFor(job.kind).rgba)
                for (spot in job.spots) {
                    for (i in spot.indices) {
                        val pos = spot[i]
                        // template sites: lava cells in the lava color, the 2 stand cells in white
                        if (job.template && i >= 2) {
                            Gizmos.cuboid(pos, STAND_STYLE).setAlwaysOnTop().persistForMillis(GIZMO_LIFETIME_MS)
                            continue
                        }
                        // setAlwaysOnTop() skips the normal depth test (see LevelRenderer.finalizeGizmoCollection,
                        // which routes "always on top" gizmos into a separate draw pass) — that's what makes this
                        // read as a cham through walls instead of getting occluded by terrain in front of it.
                        Gizmos.cuboid(pos, style).setAlwaysOnTop().persistForMillis(GIZMO_LIFETIME_MS)
                    }
                }
            }
        } finally {
            collection.close()
        }
    }

    private fun colorFor(kind: FluidKind): Color = if (kind == FluidKind.LAVA) lavaColor else waterColor

    private val STAND_STYLE = GizmoStyle.fill(0x66FFFFFF)
}
