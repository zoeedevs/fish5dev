package com.fish5.features.impl.fishing

import com.fish5.features.impl.fishing.CastPredictor.Landing
import com.fish5.features.impl.fishing.PondFinder.FluidKind
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.HitResult
import java.util.concurrent.ThreadLocalRandom

/**
 * Picks where to cast and the rotation that lands the bobber there, block by block:
 *  - [Mode.AUTOMATIC]: the nearest fluid surface block a cast can land on.
 *  - [Mode.PLAYER_ROTATION]: the surface block closest to the crosshair direction.
 *  - [Mode.LOOKING_POINT]: the fluid spot under the crosshair.
 * Any distance is fine, including the block right next to you. Candidates
 * are tried best-first until one works (within [TIME_BUDGET_NS]), so an unreachable nearest block
 * (crevice, behind a wall) is skipped instead of failing the plan.
 *
 * Randomize on: a fresh random point 1 px .. `offset` (max 3 px) from the block's centre each cast.
 * Off: the block's centre.
 *
 * Rotation is solved against [CastPredictor] by searching every pitch (direct throws and steep lobs).
 * The aim is solved for where the bobber settles after sliding across the fluid, not where it first
 * touches it: the cast must come down on top of the fluid (never its side) and come to rest within
 * 3 px of the block's centre. Client thread only, once per cast.
 */
object CastAim {

    enum class Mode { AUTOMATIC, PLAYER_ROTATION, LOOKING_POINT }

    class Plan(
        val yaw: Float, val pitch: Float,
        /** where we aimed */
        val tx: Double, val ty: Double, val tz: Double,
        /** where the simulation says it settles (after sliding on the fluid) */
        val lx: Double, val ly: Double, val lz: Double,
        val ticks: Int,
        val lob: Boolean,
        /** where the simulation says it first touches the fluid surface (before sliding) */
        val landX: Double, val landZ: Double,
    ) {
        val error get() = Math.sqrt((lx - tx) * (lx - tx) + (lz - tz) * (lz - tz))
    }

    private const val MAX_ERROR = 0.75           // looking point: how close to the spot it must land
    private const val MAX_CANDIDATES = 80       // blocks tried (one centre check each)
    private const val TIME_BUDGET_NS = 100_000_000L
    private const val LOOK_REACH = 64.0

    /** Why the last [plan] returned null (for chat). */
    var lastError = ""
        private set
    /** Surface blocks the last [plan] scanned (for the test readout). */
    var lastCells: LongOpenHashSet = LongOpenHashSet()
        private set
    /** Candidate rejections in the last [plan]: no pitch reaches it / cast hits something first / lands elsewhere. */
    var rejectedOutOfRange = 0; private set
    var rejectedBlocked = 0; private set
    var rejectedMissed = 0; private set
    var tried = 0; private set
    /** Best-ranked block of the last plan (nearest / closest to crosshair), why it was or wasn't used, and the picked block's rank. */
    var lastBest = 0L; private set
    var lastBestReason = ""; private set
    var lastRank = 0; private set
    /** Centre of the offset area the last plan used (the base block's surface centre). */
    var lastCentreX = 0.0; private set
    var lastCentreY = 0.0; private set
    var lastCentreZ = 0.0; private set

    private const val RANDOM_POINTS = 10   // random tries inside one base block's offset square
    private const val PIXEL = 1.0 / 16.0      // smallest anti-AFK step: one texture pixel
    private const val CENTRE_TOLERANCE = 3 * PIXEL  // aim and resting point stay within 3 px of the block centre

    // where the last cast aimed, so the next one only steps a pixel or few from it
    private var lastAimCell = Long.MIN_VALUE
    /** The last plan aimed into the same block as the one before it (just an anti-AFK nudge). */
    var lastSameBlock = false; private set

    private val sim = CastPredictor.Result()

    /** The last plan handed out and when (ms), so [BobberSlide] can compare it with where the cast really stopped. */
    @Volatile var lastPlanned: Plan? = null; private set
    @Volatile var lastPlannedAt = 0L; private set

    /** True when the last plan is the closest-to-centre fallback (nothing settled within 3 px). */
    var lastFallback = false; private set

    // closest-to-centre cast seen during this plan, used when nothing settles within 3 px
    private var bestMiss: Plan? = null
    private var bestMissErr = Double.MAX_VALUE
    private var bestMissCell = Long.MIN_VALUE

    fun plan(player: LocalPlayer, level: Level, mode: Mode, kind: FluidKind, randomize: Boolean, offset: Double): Plan? {
        bestMiss = null; bestMissErr = Double.MAX_VALUE; bestMissCell = Long.MIN_VALUE; lastFallback = false
        val p = planInner(player, level, mode, kind, randomize, offset) ?: bestMiss?.also {
            // nothing settles within 3 px: throw the one that gets closest rather than stopping
            lastFallback = true
            if (bestMissCell != Long.MIN_VALUE) { lastSameBlock = bestMissCell == lastAimCell; lastAimCell = bestMissCell }
        }
        if (p != null) { lastPlanned = p; lastPlannedAt = System.currentTimeMillis() }
        return p
    }

    private fun planInner(player: LocalPlayer, level: Level, mode: Mode, kind: FluidKind, randomize: Boolean, offset: Double): Plan? {
        lastError = ""
        rejectedOutOfRange = 0; rejectedBlocked = 0; rejectedMissed = 0; tried = 0; lastRank = 0; lastBestReason = ""
        if (mode == Mode.LOOKING_POINT) { lastCells = LongOpenHashSet(); return planLookingPoint(player, level, kind, randomize, offset) }

        val cells = PondFinder.scan(player, level, kind)
        lastCells = cells
        if (cells.isEmpty()) { lastError = "no ${kind.name.lowercase()} within ${PondFinder.RADIUS} blocks"; return null }

        val castable = cells.toLongArray().asList()

        // best first: nearest by real 3D distance from your eyes to the surface (automatic), or
        // closest to the crosshair (player rotation)
        val sorted = if (mode == Mode.AUTOMATIC) castable.sortedBy { eyeDistSq(player, level, it) }
            else castable.sortedBy { lookAngle(player, level, it) }
        lastBest = sorted[0]
        lastBestReason = "not tried"
        // Sticky: the block we cast into last time goes first and keeps being used (with the anti-AFK
        // nudge) while it still works, instead of hopping between near-equal blocks every cast as the
        // learned slide or the camera (player-rotation mode) shifts the ranking a little.
        val ranked = if (lastAimCell != Long.MIN_VALUE && cells.contains(lastAimCell) && sorted[0] != lastAimCell)
            listOf(lastAimCell) + sorted.filter { it != lastAimCell } else sorted

        // Each base block (best first, the rest as fallbacks) is where the bobber has to rest, within
        // 3 px of its centre, so what we catch is thrown back over us instead of off the edge.
        val deadline = System.nanoTime() + TIME_BUDGET_NS
        for (base in ranked) {
            if (tried >= MAX_CANDIDATES || System.nanoTime() > deadline) break
            val y = BlockPos.getY(base)
            val x0 = BlockPos.getX(base).toDouble(); val z0 = BlockPos.getZ(base).toDouble()
            val cx = x0 + 0.5; val cz = z0 + 0.5
            val outBefore = rejectedOutOfRange; val missBefore = rejectedMissed
            val sy = y + surfaceHeight(level, BlockPos.getX(base), y, BlockPos.getZ(base))
            // The centre decides whether this block is castable at all, so an unreachable block costs one
            // try instead of a whole round of random points (which used to burn the candidate budget on
            // the first few blocks and never reach the rest of the pool).
            tried++
            val centrePlan = solve(player, level, kind, cx, sy, cz, base, cells)
            var plan: Plan? = if (randomize) null else centrePlan
            if (randomize && centrePlan != null) {
                // anti-AFK: a fresh random point 1 px .. `offset` (max 3 px) from the centre each cast
                val maxStep = Mth.clamp(offset, PIXEL, CENTRE_TOLERANCE)
                for (a in 0 until RANDOM_POINTS) {
                    if (System.nanoTime() > deadline) break
                    val step = rand(PIXEL, maxStep)
                    val ang = rand(0.0, Math.PI * 2)
                    val tx = cx + Math.cos(ang) * step
                    val tz = cz + Math.sin(ang) * step
                    plan = solve(player, level, kind, tx, sy, tz, base, cells, centrePlan)
                    if (plan != null) break
                }
                // every off-centre point missed: the centre is still a good cast
                if (plan == null) plan = centrePlan
            }
            if (base == lastBest) lastBestReason = when {
                plan != null -> "picked"
                rejectedOutOfRange > outBefore -> "no cast reaches it"
                rejectedMissed > missBefore -> "cast lands on a different block"
                else -> "cast hits something before it (wall/ledge/side)"
            }
            if (plan != null) {
                lastSameBlock = base == lastAimCell
                lastAimCell = base
                lastRank = ranked.indexOf(base) + 1
                lastCentreX = cx; lastCentreY = y + surfaceHeight(level, BlockPos.getX(base), y, BlockPos.getZ(base)); lastCentreZ = cz
                return plan
            }
        }
        lastError = "tried $tried of ${castable.size} ${kind.name.lowercase()} block(s), no cast lands on one " +
            "(no cast comes down on its top $rejectedOutOfRange, hits something first $rejectedBlocked, settles off-centre $rejectedMissed)"
        return null
    }

    // -- target points ---------------------------------------------------------------------------

    private fun planLookingPoint(player: LocalPlayer, level: Level, kind: FluidKind, randomize: Boolean, offset: Double): Plan? {
        val eye = player.eyePosition
        val end = eye.add(player.getViewVector(1f).scale(LOOK_REACH))
        val hit = level.clip(ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, player))
        if (hit.type != HitResult.Type.BLOCK || !level.getFluidState(hit.blockPos).`is`(kind.tag)) {
            lastError = "not looking at ${kind.name.lowercase()}"
            return null
        }
        val base = hit.location
        val fy = hit.blockPos.y
        if (randomize) {
            // random point on a ring around the looked-at spot, still on open fluid
            repeat(8) {
                val r = rand(offset * 0.4, offset)
                val a = rand(0.0, Math.PI * 2)
                val tx = base.x + Math.cos(a) * r
                val tz = base.z + Math.sin(a) * r
                val bx = Mth.floor(tx); val bz = Mth.floor(tz)
                if (PondFinder.isSurface(level, bx, fy, bz, kind)) {
                    tried++
                    solve(player, level, kind, tx, fy + surfaceHeight(level, bx, fy, bz), tz, -1L, null)?.let { return it }
                }
            }
        }
        tried++
        solve(player, level, kind, base.x, base.y, base.z, -1L, null)?.let { return it }
        lastError = "can't land a cast on that spot (out of range or blocked)"
        return null
    }

    private fun horizDistSq(c: Long, x: Double, z: Double): Double {
        val dx = BlockPos.getX(c) + 0.5 - x
        val dz = BlockPos.getZ(c) + 0.5 - z
        return dx * dx + dz * dz
    }

    /** Squared 3D distance from the eyes to the centre of a block's fluid surface. */
    fun eyeDistSq(p: LocalPlayer, level: Level, c: Long): Double {
        val x = BlockPos.getX(c); val y = BlockPos.getY(c); val z = BlockPos.getZ(c)
        val dx = x + 0.5 - p.x; val dy = y + surfaceHeight(level, x, y, z) - p.eyeY; val dz = z + 0.5 - p.z
        return dx * dx + dy * dy + dz * dz
    }

    private val hpos = BlockPos.MutableBlockPos()
    private fun surfaceHeight(level: Level, x: Int, y: Int, z: Int): Double {
        hpos.set(x, y, z)
        return level.getFluidState(hpos).getHeight(level, hpos).toDouble()
    }

    /** Angle (degrees) between the crosshair and the direction to a block's fluid surface. */
    private fun lookAngle(p: LocalPlayer, level: Level, c: Long): Double {
        val x = BlockPos.getX(c); val y = BlockPos.getY(c); val z = BlockPos.getZ(c)
        val dx = x + 0.5 - p.x; val dy = y + surfaceHeight(level, x, y, z) - p.eyeY; val dz = z + 0.5 - p.z
        val yaw = Math.toDegrees(Math.atan2(dz, dx)) - 90.0
        val pitch = -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)))
        val dYaw = Mth.wrapDegrees(yaw - p.yRot)
        val dPitch = pitch - p.xRot
        return Math.sqrt(dYaw * dYaw + dPitch * dPitch)
    }

    // -- rotation solve --------------------------------------------------------------------------

    /**
     * [cell] = target block (-1 for looking point); [cells] = scanned surface set (null for looking
     * point, where the resting point just has to be within [MAX_ERROR] of the spot).
     *
     * Plain search, no assumptions about how distance changes with pitch (it jumps: a cast that comes
     * down on the fluid slides on, one that hits stone stops dead): sweep every pitch, keep the cast
     * whose resting point is closest to the target, refine around it, then correct yaw for the cast
     * starting beside the eye and refine again.
     */
    private fun solve(
        player: LocalPlayer, level: Level, kind: FluidKind,
        tx: Double, ty: Double, tz: Double, cell: Long, cells: LongOpenHashSet?,
        guess: Plan? = null,
    ): Plan? {
        val targetYaw = (Math.toDegrees(Math.atan2(tz - player.z, tx - player.x)) - 90.0).toFloat()
        val wanted = if (kind == FluidKind.LAVA) Landing.LAVA else Landing.WATER

        var yaw = targetYaw
        var pitch = Float.NaN
        if (guess != null) {
            // a nearby point in the same block: start from that solution, keep its yaw offset
            val guessTargetYaw = (Math.toDegrees(Math.atan2(guess.tz - player.z, guess.tx - player.x)) - 90.0).toFloat()
            yaw = targetYaw + Mth.wrapDegrees(guess.yaw - guessTargetYaw)
            pitch = guess.pitch
        } else {
            // coarse sweep over every pitch (nearly straight up .. straight down)
            var err = Double.MAX_VALUE
            var p = PITCH_MIN
            while (p <= PITCH_MAX) {
                val e = score(player, level, wanted, yaw, p, tx, tz)
                if (e < err) { err = e; pitch = p }
                p += COARSE_STEP
            }
            if (pitch.isNaN()) { rejectedOutOfRange++; return null }  // nothing comes down on top of this fluid
        }
        pitch = refine(player, level, wanted, yaw, pitch, COARSE_STEP, COARSE_STEP / 10f, tx, tz)
        pitch = refine(player, level, wanted, yaw, pitch, COARSE_STEP / 10f, COARSE_STEP / 100f, tx, tz)
        repeat(2) {
            // the cast starts beside the eye: turn by the resting point's angular miss, then re-refine
            CastPredictor.simulate(player, level, yaw, pitch, sim)
            if (sim.landing == Landing.NONE) return@repeat
            val restYaw = (Math.toDegrees(Math.atan2(sim.restZ - player.z, sim.restX - player.x)) - 90.0).toFloat()
            yaw += Mth.wrapDegrees(targetYaw - restYaw)
            pitch = refine(player, level, wanted, yaw, pitch, 1f, 0.1f, tx, tz)
            pitch = refine(player, level, wanted, yaw, pitch, 0.1f, 0.01f, tx, tz)
        }

        CastPredictor.simulate(player, level, yaw, pitch, sim)
        // must come down on top of the fluid, never in through a raised pool's side
        if (sim.landing != wanted || sim.face != Direction.UP) { rejectedBlocked++; return null }
        val onTarget = if (cells != null) {
            // settles within 3 px of the target block's centre
            Math.abs(sim.restX - (BlockPos.getX(cell) + 0.5)) <= CENTRE_TOLERANCE + 1e-6 &&
                Math.abs(sim.restZ - (BlockPos.getZ(cell) + 0.5)) <= CENTRE_TOLERANCE + 1e-6
        } else Math.hypot(sim.restX - tx, sim.restZ - tz) <= MAX_ERROR
        val result = Plan(Mth.wrapDegrees(yaw), pitch, tx, ty, tz, sim.restX, sim.y, sim.restZ, sim.ticks, pitch < 0f, sim.x, sim.z)
        if (!onTarget) {
            rejectedMissed++
            val err = if (cells != null) Math.hypot(sim.restX - (BlockPos.getX(cell) + 0.5), sim.restZ - (BlockPos.getZ(cell) + 0.5))
                else Math.hypot(sim.restX - tx, sim.restZ - tz)
            if (err < bestMissErr) { bestMissErr = err; bestMiss = result; bestMissCell = if (cells != null) cell else Long.MIN_VALUE }
            return null
        }
        return result
    }

    private const val PITCH_MIN = -89f   // steep lobs come down almost vertically, so they barely slide
    private const val PITCH_MAX = 90f
    private const val COARSE_STEP = 2f

    /** Best pitch within [center] +- [span] at [step], starting from [center] itself. */
    private fun refine(
        player: LocalPlayer, level: Level, wanted: Landing, yaw: Float,
        center: Float, span: Float, step: Float, tx: Double, tz: Double,
    ): Float {
        var best = center
        var bestErr = score(player, level, wanted, yaw, center, tx, tz)
        var p = Math.max(PITCH_MIN, center - span)
        val end = Math.min(PITCH_MAX, center + span)
        while (p <= end) {
            val e = score(player, level, wanted, yaw, p, tx, tz)
            if (e < bestErr) { bestErr = e; best = p }
            p += step
        }
        return best
    }

    /**
     * How far from the target this cast comes to rest (plus a tiny flight-time tiebreak, so the quicker
     * direct throw wins over an equal lob); MAX_VALUE unless it comes down on top of the [wanted] fluid.
     */
    private fun score(player: LocalPlayer, level: Level, wanted: Landing, yaw: Float, pitch: Float, tx: Double, tz: Double): Double {
        CastPredictor.simulate(player, level, yaw, pitch, sim)
        if (sim.landing != wanted || sim.face != Direction.UP) return Double.MAX_VALUE
        return Math.hypot(sim.restX - tx, sim.restZ - tz) + sim.ticks * 0.002
    }

    private fun rand(a: Double, b: Double) = if (b <= a) a else ThreadLocalRandom.current().nextDouble(a, b)
}
