package com.fish5.features.impl.skyblock

import com.fish5.beta.BetaGate

/**
 * PUBLIC BUILD PLACEHOLDER.
 *
 * Gemstone Chamber Auction-House listing/selling is a beta feature; the real implementation is not
 * part of this open-source build. `testCommand()` is wired to the `/f5 dev auction` debug command in
 * `Fish5.kt` and here only shows the beta notice.
 *
 * See `fish5/CLAUDE.md` -> "Open-source vs beta features".
 */
object ChamberSeller {
    fun testCommand() {
        BetaGate.locked(BetaGate.LIST_CHAMBERS)
    }
}
