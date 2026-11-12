package com.fish5.automation;

import com.fish5.sb.SkyblockData;
import net.minecraft.client.Minecraft;

/**
 * Caches the purse once per second from the render thread so the automation
 * worker can read it (as a coin-safety guard) without touching world state.
 */
public final class PurseTracker {

    public static volatile long purse = -1;
    private static int tickCounter = 0;

    private PurseTracker() {}

    public static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            purse = -1;
            return;
        }
        if (++tickCounter % 20 == 0 && SkyblockData.isOnSkyblock(mc)) {
            purse = SkyblockData.getPurse(mc);
        }
    }
}
