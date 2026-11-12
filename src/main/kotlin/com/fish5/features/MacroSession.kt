package com.fish5.features

import com.fish5.Fish5
import com.fish5.sb.SkyblockData
import com.fish5.session.RestScheduler

/**
 * Stats for the current macro session, shared by every [Macro] and shown by the Macro HUD.
 *
 * A session starts when a macro starts and keeps going across a scheduled break, so a break doesn't
 * wipe the numbers. Starting a macro by hand (or a different macro) begins a new session. The clock
 * only counts while the macro is actually running.
 *
 * Fed from the trackers that already see the events, so nothing here scans the world:
 *  - [onCatch] / [onKill]: our own sea creatures appearing and dying ([com.fish5.features.impl.fishing.SeaCreatureTracker]).
 *  - [addLoot]: valued drops (e.g. the wormfish profit tracker).
 *  - [onActionBar]: skill XP from the action bar, for the level progress and time to next level.
 * All of it is a no-op while no macro is running.
 */
object MacroSession {

    /** The macro this session belongs to (also kept during a break, so the HUD can still show it). */
    var macro: Macro? = null
        private set

    private var runningSince = 0L     // 0 while not running
    private var accumulatedMs = 0L    // finished runs in this session
    private var resumeAfterBreak = false

    var caught = 0
        private set
    var kills = 0
        private set
    var coins = 0.0
        private set

    val active: Boolean get() = runningSince != 0L

    /** Running time in this session, in ms (breaks excluded). */
    val elapsedMs: Long
        get() = accumulatedMs + if (runningSince != 0L) (System.currentTimeMillis() - runningSince).coerceAtLeast(0L) else 0L

    private val hours: Double get() = elapsedMs / 3_600_000.0

    val killsPerHour: Double get() = hours.let { if (it < MIN_RATE_HOURS) 0.0 else kills / it }
    val coinsPerHour: Double get() = hours.let { if (it < MIN_RATE_HOURS) 0.0 else coins / it }

    // -- lifecycle (called by Macro) ----------------------------------------------------------------

    fun begin(m: Macro) {
        if (!(resumeAfterBreak && macro === m)) reset()
        resumeAfterBreak = false
        macro = m
        runningSince = System.currentTimeMillis()
    }

    fun end(m: Macro) {
        if (macro !== m || runningSince == 0L) return
        accumulatedMs += (System.currentTimeMillis() - runningSince).coerceAtLeast(0L)
        runningSince = 0L
        // the scheduler stops the macro right before the break starts, so the break is still "due" here
        resumeAfterBreak = RestScheduler.isBreakDue() || RestScheduler.isOnBreak()
    }

    private fun reset() {
        accumulatedMs = 0L
        caught = 0; kills = 0; coins = 0.0
        for (i in 0 until itemCount) itemNames[i] = null
        itemCount = 0
        skillName = null; skillLevel = -1; skillPercent = -1.0
        skillXpGained = 0.0; skillXpRemaining = -1.0
        pctBase = -1.0; pctBaseMs = 0L; pctLatestMs = 0L
        nextTabRead = 0L
    }

    // -- creatures & loot ---------------------------------------------------------------------------

    fun onCatch() { if (active) caught++ }

    fun onKill() { if (active) kills++ }

    /** Adds [count] of [name] worth [value] coins in total. At most [MAX_ITEMS] different names are kept. */
    fun addLoot(name: String, count: Long, value: Double) {
        if (!active || count <= 0) return
        coins += value
        for (i in 0 until itemCount) {
            if (itemNames[i] == name) {
                itemCounts[i] += count
                itemValues[i] += value
                return
            }
        }
        if (itemCount >= MAX_ITEMS) return
        itemNames[itemCount] = name
        itemCounts[itemCount] = count
        itemValues[itemCount] = value
        itemCount++
    }

    private val itemNames = arrayOfNulls<String>(MAX_ITEMS)
    private val itemCounts = LongArray(MAX_ITEMS)
    private val itemValues = DoubleArray(MAX_ITEMS)
    private var itemCount = 0

    /**
     * Fills [out] with the indices of the [n] most valuable items, best first (no allocation), and
     * returns how many. Read them with [itemName] / [itemCountAt] / [itemValueAt].
     */
    fun topItems(out: IntArray, n: Int): Int {
        var filled = 0
        for (i in 0 until itemCount) {
            var pos = filled
            while (pos > 0 && itemValues[out[pos - 1]] < itemValues[i]) pos--
            if (pos >= n) continue
            if (filled < n) filled++
            for (k in filled - 1 downTo pos + 1) out[k] = out[k - 1]
            out[pos] = i
        }
        return filled
    }

    fun itemName(i: Int): String = itemNames[i] ?: ""
    fun itemCountAt(i: Int): Long = itemCounts[i]
    fun itemValueAt(i: Int): Double = itemValues[i]

    // -- skill XP -----------------------------------------------------------------------------------

    /** Skill seen in the action bar this session ("Fishing"), or null. */
    var skillName: String? = null
        private set
    /** Level from the tab list's Skills widget, or -1 when it isn't shown there. */
    var skillLevel = -1
        private set
    /** Progress through the current level, 0-100, or -1. */
    var skillPercent = -1.0
        private set

    private var skillXpGained = 0.0
    private var skillXpRemaining = -1.0
    // percent-only action bars (no "cur/needed"): estimate the rate from how fast the percent climbs
    private var pctBase = -1.0
    private var pctBaseMs = 0L
    private var pctLatestMs = 0L
    private var nextTabRead = 0L

    private val skillPattern = Regex(
        """\+([\d,.]+)\s+(Farming|Mining|Combat|Foraging|Fishing|Enchanting|Alchemy|Taming|Carpentry|Hunting)\s+\(([\d,.]+[kKmM]?)(?:/([\d,.]+[kKmM]?)|%)\)""",
    )

    /** Called with every action-bar message. */
    fun onActionBar(text: String) {
        if (!active || text.indexOf('+') < 0) return
        val m = skillPattern.find(SkyblockData.stripColors(text)) ?: return
        val name = m.groupValues[2]
        if (name != skillName) {
            skillName = name; skillLevel = -1; skillXpGained = 0.0; skillXpRemaining = -1.0
            skillPercent = -1.0; pctBase = -1.0; nextTabRead = 0L
        }
        val now = System.currentTimeMillis()
        skillXpGained += parseNum(m.groupValues[1])
        val cur = parseNum(m.groupValues[3])
        if (m.groupValues[4].isNotEmpty()) {
            val need = parseNum(m.groupValues[4])
            if (need > 0) {
                skillPercent = (cur / need * 100.0).coerceIn(0.0, 100.0)
                skillXpRemaining = (need - cur).coerceAtLeast(0.0)
            }
        } else {
            skillXpRemaining = -1.0
            if (pctBase < 0 || cur < skillPercent) { pctBase = cur; pctBaseMs = now }  // first sighting or level up
            skillPercent = cur
            pctLatestMs = now
        }
        if (now >= nextTabRead) {
            nextTabRead = now + TAB_READ_MS
            readLevelFromTab(name)
        }
    }

    /** Estimated ms to the next level, or -1 when there isn't enough data yet. */
    fun msToNextLevel(): Long {
        if (skillXpRemaining >= 0) {
            val h = hours
            if (h < MIN_RATE_HOURS || skillXpGained <= 0) return -1
            return (skillXpRemaining / (skillXpGained / h) * 3_600_000.0).toLong()
        }
        if (pctBase < 0 || skillPercent <= pctBase) return -1
        val spanMs = (pctLatestMs - pctBaseMs).toDouble()
        if (spanMs < 60_000.0) return -1
        val perMs = (skillPercent - pctBase) / spanMs
        return ((100.0 - skillPercent) / perMs).toLong()
    }

    // The tab list's Skills widget has lines like " Fishing 44: 55.6%". Read at most every TAB_READ_MS.
    private fun readLevelFromTab(name: String) {
        val lines = runCatching { SkyblockData.getTabListLines(Fish5.mc) }.getOrNull() ?: return
        val prefix = "$name "
        for (raw in lines) {
            val line = SkyblockData.stripColors(raw).trim()
            if (!line.startsWith(prefix)) continue
            val rest = line.substring(prefix.length)
            val colon = rest.indexOf(':')
            if (colon <= 0) continue
            val lvl = rest.substring(0, colon).trim()
            val n = lvl.toIntOrNull() ?: roman(lvl)
            if (n > 0) { skillLevel = n; return }
        }
    }

    private fun roman(s: String): Int {
        var total = 0
        var prev = 0
        for (k in s.length - 1 downTo 0) {
            val v = when (s[k]) { 'I' -> 1; 'V' -> 5; 'X' -> 10; 'L' -> 50; 'C' -> 100; else -> return -1 }
            if (v < prev) total -= v else { total += v; prev = v }
        }
        return total
    }

    private fun parseNum(s: String): Double {
        if (s.isEmpty()) return 0.0
        val mult = when (s.last()) { 'k', 'K' -> 1_000.0; 'm', 'M' -> 1_000_000.0; else -> 1.0 }
        val digits = if (mult != 1.0) s.dropLast(1) else s
        return (digits.replace(",", "").toDoubleOrNull() ?: 0.0) * mult
    }

    private const val MAX_ITEMS = 32
    private const val TAB_READ_MS = 30_000L
    /** Don't show a per-hour rate until this much time has passed (the first seconds swing wildly). */
    private const val MIN_RATE_HOURS = 10.0 / 3600.0
}
