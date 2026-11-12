package com.fish5.features.impl.skyblock

import com.fish5.beta.BetaGate
import com.fish5.features.impl.fishing.WormFishing

/**
 * PUBLIC BUILD PLACEHOLDER.
 *
 * The real Worm Fishing **Auto Mode** (automatic forging, claiming and Auction-House listing of
 * Gemstone Chambers, run between reels) is a beta feature. Its implementation is not part of this
 * open-source build — it lives in the private beta build.
 *
 * The "Auto Mode" and "List Chambers" toggles in [WormFishing] still appear and can be switched on,
 * but here they do nothing except show the beta notice via [BetaGate]. Ticked from `Fish5.kt`.
 *
 * See `fish5/CLAUDE.md` -> "Open-source vs beta features".
 */
object WormAuto {
    fun tick() {
        when {
            WormFishing.autoMode -> BetaGate.locked(BetaGate.AUTO_MODE)
            WormFishing.listChambers -> BetaGate.locked(BetaGate.LIST_CHAMBERS)
        }
    }
}
