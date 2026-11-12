package com.fish5.features.impl.skyblock

import com.fish5.beta.BetaGate

/**
 * PUBLIC BUILD PLACEHOLDER.
 *
 * Gemstone Chamber forging/claiming (opening and operating the Forge menu) is a beta feature; the
 * real implementation is not part of this open-source build.
 *
 * See `fish5/CLAUDE.md` -> "Open-source vs beta features".
 */
object WormForge {
    /**
     * Beta-only. Returns `false` so that "Check forge on start" in Worm Fishing degrades
     * gracefully: the macro reports it couldn't read the forge and carries on fishing without it.
     * (Called from `WormFishing.startSackCheck`.)
     */
    fun readTimersBlocking(): Boolean = false

    /** Wired to `/f5 dev forge` in `Fish5.kt`; shows the beta notice. */
    fun testCommand() {
        BetaGate.locked(BetaGate.AUTO_MODE)
    }
}
