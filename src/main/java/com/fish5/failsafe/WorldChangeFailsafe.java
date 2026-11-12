// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.failsafe;

import com.fish5.config.MacroConfig;
import com.fish5.macro.MacroState;
import com.fish5.macro.MacroStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

// a level swap the macro did not ask for (warp, kick to hub, limbo) while it was running
public final class WorldChangeFailsafe {
    private static ClientLevel lastObservedLevel;

    private WorldChangeFailsafe() {
    }

    public static void tick(Minecraft client) {
        ClientLevel currentLevel = client.level;
        if (currentLevel == null) {
            lastObservedLevel = null;
            return;
        }

        if (lastObservedLevel == null) {
            lastObservedLevel = currentLevel;
            return;
        }

        if (currentLevel != lastObservedLevel) {
            lastObservedLevel = currentLevel;
            if (shouldHandleWorldChange()) {
                FailsafeAction action = FailsafeManager.getWorldChangeAction();
                String details = "World changed unexpectedly.";
                FailsafeManager.notifyInGame(action, details);
                FailsafeManager.handleConfiguredAction(client, action, details, "World change detected");
            }
        }
    }

    private static boolean shouldHandleWorldChange() {
        // a macro warping on purpose sets RECOVERING around the warp
        return MacroConfig.FAILSAFE_WORLD_CHANGE.get()
                && MacroStateManager.isMacroRunning()
                && MacroStateManager.getCurrentState() != MacroState.State.RECOVERING;
    }
}
