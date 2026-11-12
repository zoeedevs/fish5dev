package com.fish5.beta

import com.fish5.automation.Msg

/**
 * Open-source build gate for **beta features**.
 *
 * Some features ship in this public (open-source) build only as placeholders: the toggle appears in
 * the click GUI and can be switched on, but the real implementation is **not** in this source tree —
 * it lives in the private beta build. Switching a beta feature on does nothing except show the notice
 * below.
 *
 * Keep [FEATURES] in sync with the "Current beta features" list in `fish5/CLAUDE.md`. When a feature
 * graduates to open source, its real implementation is ported from the beta build and the entry here
 * (and the placeholder stub) is removed.
 */
object BetaGate {

    const val AUTO_MODE = "Worm Fishing: Auto Mode"
    const val LIST_CHAMBERS = "Worm Fishing: List Chambers (Auction House)"

    /** Every beta-locked feature surfaced in this build. */
    val FEATURES: List<String> = listOf(AUTO_MODE, LIST_CHAMBERS)

    private const val THROTTLE_MS = 5_000L
    private val lastShown = HashMap<String, Long>()

    /**
     * Announce that [feature] is beta-only, then do nothing. Throttled to one message per
     * [THROTTLE_MS] per feature, so a per-tick caller can't spam chat. Always returns `false`, so a
     * call site can read as `if (BetaGate.locked(...)) return`.
     */
    fun locked(feature: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - (lastShown[feature] ?: 0L) >= THROTTLE_MS) {
            lastShown[feature] = now
            Msg.chat(
                "§b§l[fish5]§r §f$feature §eis still in development. " +
                    "§7Wait for the release, or purchase beta access in the Discord."
            )
        }
        return false
    }
}
