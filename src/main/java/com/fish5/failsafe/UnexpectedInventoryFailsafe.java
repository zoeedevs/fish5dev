// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.failsafe;

import com.fish5.config.MacroConfig;
import com.fish5.macro.MacroState;
import com.fish5.macro.MacroStateManager;
import com.fish5.utils.ClientUtils;
import net.minecraft.client.Minecraft;

final class UnexpectedInventoryFailsafe {
    enum State {
        IDLE,
        WAIT,
        TRIGGERED
    }

    private static volatile long inventoryOpenSince = 0L;
    private static volatile long inventoryOpenRandomDelayMs = 0L;
    private static volatile boolean triggered = false;
    private static volatile boolean expectingGui = false;

    private UnexpectedInventoryFailsafe() {
    }

    static void reset() {
        inventoryOpenSince = 0L;
        inventoryOpenRandomDelayMs = 0L;
        triggered = false;
    }

    static void setExpectingGui(boolean expecting) {
        expectingGui = expecting;
        if (expecting) {
            inventoryOpenSince = 0L;
            inventoryOpenRandomDelayMs = 0L;
        }
    }

    private static boolean shouldMonitor() {
        return MacroStateManager.getCurrentState() == MacroState.State.RUNNING && !expectingGui;
    }

    static void tick(Minecraft client) {
        if (client == null || client.player == null) {
            reset();
            return;
        }

        if (!MacroConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI.get() || !shouldMonitor()) {
            reset();
            return;
        }

        if (!ClientUtils.isInventoryScreenOpen()) {
            inventoryOpenSince = 0L;
            inventoryOpenRandomDelayMs = 0L;
            return;
        }

        long now = System.currentTimeMillis();
        if (inventoryOpenSince == 0L) {
            inventoryOpenSince = now;
            inventoryOpenRandomDelayMs = FailsafeManager.sampleAdditionalTriggerDelayMs();
            return;
        }

        long triggerDelayMs = Math.round(MacroConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI_DELAY_SECONDS.get() * 1000.0f)
                + inventoryOpenRandomDelayMs;
        if (now - inventoryOpenSince < triggerDelayMs) {
            return;
        }

        trigger(client);
    }

    static State getState(Minecraft client) {
        if (triggered) {
            return State.TRIGGERED;
        }
        if (client == null || client.player == null
                || !MacroConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI.get()
                || !shouldMonitor()
                || !ClientUtils.isInventoryScreenOpen()) {
            return State.IDLE;
        }

        return inventoryOpenSince == 0L ? State.IDLE : State.WAIT;
    }

    static long getTriggerRemainingMs() {
        if (inventoryOpenSince == 0L) {
            return 0L;
        }

        long triggerDelayMs = Math.round(MacroConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI_DELAY_SECONDS.get() * 1000.0f)
                + inventoryOpenRandomDelayMs;
        long elapsedMs = System.currentTimeMillis() - inventoryOpenSince;
        return Math.max(0L, triggerDelayMs - elapsedMs);
    }

    private static void trigger(Minecraft client) {
        if (triggered) {
            return;
        }

        FailsafeAction action = FailsafeManager.getUnexpectedInventoryGuiAction();
        triggered = true;
        FailsafeManager.notifyInGame(action, "Unexpected inventory GUI detected.");
        FailsafeManager.handleConfiguredAction(
                client,
                action,
                "unexpected inventory GUI detected while the macro was running.",
                "UnexpectedInventoryFailsafe: unexpected inventory GUI detected");
        reset();
    }
}
