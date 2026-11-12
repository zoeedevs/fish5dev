// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.failsafe;

import com.fish5.config.MacroConfig;
import com.fish5.macro.MacroState;
import com.fish5.macro.MacroStateManager;
import com.fish5.mixin.InventoryAccessor;
import com.fish5.utils.ClientUtils;
import net.minecraft.client.Minecraft;

final class InventorySlotFailsafe {
    enum State {
        IDLE,
        WAIT,
        TRIGGERED
    }

    private static final long EXPECTED_SLOT_GRACE_MS = 250L;

    private static volatile int expectedSelectedSlot = -1;
    private static volatile long expectedSelectedSlotAt = 0L;
    private static volatile long slotMismatchSince = 0L;
    private static volatile long slotMismatchRandomDelayMs = 0L;
    private static volatile boolean triggered = false;

    private InventorySlotFailsafe() {}

    static void reset() {
        expectedSelectedSlot = -1;
        expectedSelectedSlotAt = 0L;
        slotMismatchSince = 0L;
        slotMismatchRandomDelayMs = 0L;
        triggered = false;
    }

    static void syncSelectedSlotFromClient(Minecraft client) {
        if (client == null || client.player == null) return;

        expectedSelectedSlot = ((InventoryAccessor) client.player.getInventory()).getSelected();
        expectedSelectedSlotAt = System.currentTimeMillis();
        slotMismatchSince = 0L;
        slotMismatchRandomDelayMs = 0L;
        triggered = false;
    }

    static void expectSelectedHotbarSlot(int slot) {
        if (slot < 0 || slot > 8) return;

        expectedSelectedSlot = slot;
        expectedSelectedSlotAt = System.currentTimeMillis();
        slotMismatchSince = 0L;
        slotMismatchRandomDelayMs = 0L;
    }

    static void selectHotbarSlot(Minecraft client, int slot) {
        if (client == null || client.player == null || slot < 0 || slot > 8) return;

        expectSelectedHotbarSlot(slot);
        if (((InventoryAccessor) client.player.getInventory()).getSelected() == slot) {
            return;
        }

        if (client.isSameThread()) {
            ((InventoryAccessor) client.player.getInventory()).setSelected(slot);
        }

        ClientUtils.performHotbarSlotClick(slot);
    }

    static void tick(Minecraft client) {
        if (client == null || client.player == null) {
            reset();
            return;
        }

        if (!MacroStateManager.isMacroRunning()) {
            syncSelectedSlotFromClient(client);
            return;
        }

        if (MacroStateManager.getCurrentState() == MacroState.State.RECOVERING) {
            syncSelectedSlotFromClient(client);
            return;
        }

        if (!MacroConfig.FAILSAFE_INVENTORY_SLOT_CHANGED.get()) {
            slotMismatchSince = 0L;
            if (expectedSelectedSlot < 0 || expectedSelectedSlot > 8) {
                syncSelectedSlotFromClient(client);
            }
            return;
        }

        int currentSelectedSlot = ((InventoryAccessor) client.player.getInventory()).getSelected();
        if (expectedSelectedSlot < 0 || expectedSelectedSlot > 8) {
            expectedSelectedSlot = currentSelectedSlot;
            expectedSelectedSlotAt = System.currentTimeMillis();
            slotMismatchSince = 0L;
            slotMismatchRandomDelayMs = 0L;
            return;
        }

        long now = System.currentTimeMillis();
        if (currentSelectedSlot == expectedSelectedSlot) {
            slotMismatchSince = 0L;
            slotMismatchRandomDelayMs = 0L;
            return;
        }

        if (now - expectedSelectedSlotAt < EXPECTED_SLOT_GRACE_MS) {
            slotMismatchSince = 0L;
            slotMismatchRandomDelayMs = 0L;
            return;
        }

        if (slotMismatchSince == 0L) {
            slotMismatchSince = now;
            slotMismatchRandomDelayMs = FailsafeManager.sampleAdditionalTriggerDelayMs();
            return;
        }

        long triggerDelayMs = Math.round(MacroConfig.FAILSAFE_INVENTORY_SLOT_CHANGED_DELAY_SECONDS.get() * 1000.0f)
                + slotMismatchRandomDelayMs;
        if (now - slotMismatchSince < triggerDelayMs) {
            return;
        }

        trigger(client, expectedSelectedSlot, currentSelectedSlot);
    }

    static int getExpectedSelectedSlot() {
        return expectedSelectedSlot;
    }

    static int getCurrentSelectedSlot(Minecraft client) {
        if (client == null || client.player == null) return -1;
        return ((InventoryAccessor) client.player.getInventory()).getSelected();
    }

    static State getState(Minecraft client) {
        if (triggered) {
            return State.TRIGGERED;
        }
        if (client == null || client.player == null || !MacroStateManager.isMacroRunning()
                || MacroStateManager.getCurrentState() == MacroState.State.RECOVERING
                || !MacroConfig.FAILSAFE_INVENTORY_SLOT_CHANGED.get()) {
            return State.IDLE;
        }

        int currentSelectedSlot = getCurrentSelectedSlot(client);
        if (expectedSelectedSlot < 0 || expectedSelectedSlot > 8 || currentSelectedSlot == expectedSelectedSlot) {
            return State.IDLE;
        }
        return State.WAIT;
    }

    static long getTriggerRemainingMs() {
        if (slotMismatchSince == 0L) {
            return 0L;
        }

        long triggerDelayMs = Math.round(MacroConfig.FAILSAFE_INVENTORY_SLOT_CHANGED_DELAY_SECONDS.get() * 1000.0f)
                + slotMismatchRandomDelayMs;
        long elapsedMs = System.currentTimeMillis() - slotMismatchSince;
        return Math.max(0L, triggerDelayMs - elapsedMs);
    }

    private static void trigger(Minecraft client, int expectedSlot, int currentSlot) {
        if (triggered) {
            return;
        }

        FailsafeAction action = FailsafeManager.getInventorySlotChangedAction();
        triggered = true;
        FailsafeManager.notifyInGame(action, "Selected hotbar slot changed unexpectedly.");
        FailsafeManager.handleConfiguredAction(
                client,
                action,
                "selected hotbar slot changed unexpectedly. Expected slot "
                        + (expectedSlot + 1) + ", got slot " + (currentSlot + 1) + ".",
                "InventorySlotFailsafe: selected hotbar slot changed unexpectedly");
        reset();
    }
}
