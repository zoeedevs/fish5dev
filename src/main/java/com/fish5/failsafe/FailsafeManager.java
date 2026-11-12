// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.failsafe;

import com.fish5.config.MacroConfig;
import com.fish5.macro.MacroStateManager;
import com.fish5.notification.NotificationManager;
import com.fish5.utils.ClientUtils;
import net.minecraft.client.Minecraft;

public final class FailsafeManager {
    public enum InventorySlotState {
        IDLE,
        WAIT,
        TRIGGERED
    }

    public enum InventoryGuiState {
        IDLE,
        WAIT,
        TRIGGERED
    }

    public enum RotationState {
        IDLE,
        WAIT,
        TRIGGERED
    }

    private FailsafeManager() {}

    public static void reset() {
        resetRuntimeState();
        FailsafeTestManager.reset();
    }

    public static void resetRuntimeState() {
        InventorySlotFailsafe.reset();
        UnexpectedInventoryFailsafe.reset();
        RotationFailsafe.reset();
    }

    public static void syncSelectedSlotFromClient(Minecraft client) {
        InventorySlotFailsafe.syncSelectedSlotFromClient(client);
    }

    public static void expectSelectedHotbarSlot(int slot) {
        InventorySlotFailsafe.expectSelectedHotbarSlot(slot);
    }

    public static void selectHotbarSlot(Minecraft client, int slot) {
        InventorySlotFailsafe.selectHotbarSlot(client, slot);
    }

    public static void tick(Minecraft client) {
        FailsafeTestManager.tick(client);
        InventorySlotFailsafe.tick(client);
        UnexpectedInventoryFailsafe.tick(client);
        RotationFailsafe.tick(client);
    }

    public static void expectRotation(float yaw, float pitch) {
        RotationFailsafe.expectRotation(yaw, pitch);
    }

    public static void syncExpectedRotationFromClient(Minecraft client) {
        RotationFailsafe.syncExpectedRotationFromClient(client);
    }

    public static void reportExternalRotation() {
        RotationFailsafe.reportExternalRotation();
    }

    public static void addRotationGracePeriod(long durationMs) {
        RotationFailsafe.addGracePeriod(durationMs);
    }

    // set by the macro while it is following a live target (mob, bobber); see RotationFailsafe
    public static void setTargetTracking(boolean active) {
        RotationFailsafe.setTargetTracking(active);
    }

    public static boolean isTargetTracking() {
        return RotationFailsafe.isTargetTracking();
    }

    // a gui the macro is about to open on purpose must not read as an unexpected inventory
    public static void setExpectingGui(boolean expecting) {
        UnexpectedInventoryFailsafe.setExpectingGui(expecting);
    }

    public static int getExpectedSelectedSlot() {
        return InventorySlotFailsafe.getExpectedSelectedSlot();
    }

    public static int getCurrentSelectedSlot(Minecraft client) {
        return InventorySlotFailsafe.getCurrentSelectedSlot(client);
    }

    public static InventorySlotState getInventorySlotState(Minecraft client) {
        return switch (InventorySlotFailsafe.getState(client)) {
            case IDLE -> InventorySlotState.IDLE;
            case WAIT -> InventorySlotState.WAIT;
            case TRIGGERED -> InventorySlotState.TRIGGERED;
        };
    }

    public static long getInventorySlotTriggerRemainingMs() {
        return InventorySlotFailsafe.getTriggerRemainingMs();
    }

    public static InventoryGuiState getInventoryGuiState(Minecraft client) {
        return switch (UnexpectedInventoryFailsafe.getState(client)) {
            case IDLE -> InventoryGuiState.IDLE;
            case WAIT -> InventoryGuiState.WAIT;
            case TRIGGERED -> InventoryGuiState.TRIGGERED;
        };
    }

    public static long getInventoryGuiTriggerRemainingMs() {
        return UnexpectedInventoryFailsafe.getTriggerRemainingMs();
    }

    public static RotationState getRotationState(Minecraft client) {
        return switch (RotationFailsafe.getState(client)) {
            case IDLE -> RotationState.IDLE;
            case WAIT -> RotationState.WAIT;
            case TRIGGERED -> RotationState.TRIGGERED;
        };
    }

    public static float getExpectedYaw() {
        return RotationFailsafe.getExpectedYaw();
    }

    public static float getExpectedPitch() {
        return RotationFailsafe.getExpectedPitch();
    }

    public static long getRotationTriggerRemainingMs() {
        return RotationFailsafe.getTriggerRemainingMs();
    }

    public static boolean shouldSuppressTrackingRotation(Minecraft client) {
        return RotationFailsafe.shouldSuppressTrackingRotation(client);
    }

    public static long sampleAdditionalTriggerDelayMs() {
        float maxAdditionalDelaySeconds = MacroConfig.FAILSAFE_ADDITIONAL_RANDOM_DELAY_SECONDS.get();
        if (maxAdditionalDelaySeconds <= 0.0f) {
            return 0L;
        }

        long maxAdditionalDelayMs = Math.round(maxAdditionalDelaySeconds * 1000.0f);
        return java.util.concurrent.ThreadLocalRandom.current().nextLong(maxAdditionalDelayMs + 1L);
    }

    public static void onFailsafeTriggered(FailsafeAction action, String details) {
        FailsafeTriggerHistory.recordTrigger(details);
        FailsafeColourFlashManager.trigger();
        FailsafeSoundManager.playConfiguredSound(action);
        FailsafeWindowFocusManager.bringWindowToFront();
    }

    public static FailsafeAction getInventorySlotChangedAction() {
        return FailsafeAction.fromConfig(MacroConfig.FAILSAFE_INVENTORY_SLOT_CHANGED_ACTION.get());
    }

    public static FailsafeAction getUnexpectedInventoryGuiAction() {
        return FailsafeAction.fromConfig(MacroConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI_ACTION.get());
    }

    public static FailsafeAction getRotationAction(boolean trackingRotation) {
        return FailsafeAction.fromConfig(
                trackingRotation
                        ? MacroConfig.FAILSAFE_TRACKING_ROTATION_ACTION.get()
                        : MacroConfig.FAILSAFE_ROTATION_ACTION.get());
    }

    public static FailsafeAction getWorldChangeAction() {
        return FailsafeAction.fromConfig(MacroConfig.FAILSAFE_WORLD_CHANGE_ACTION.get());
    }

    public static boolean shouldStopMacroOnTrigger(FailsafeAction action) {
        return action == FailsafeAction.STOP;
    }

    public static String getNotificationTitle(FailsafeAction action) {
        return shouldStopMacroOnTrigger(action) ? "Failsafe Triggered" : "Failsafe Detected";
    }

    public static void notifyInGame(FailsafeAction action, String details) {
        NotificationManager.INSTANCE.notify(getNotificationTitle(action) + ": " + details);
    }

    public static void handleConfiguredAction(Minecraft client, FailsafeAction action, String details, String debugReason) {
        handleConfiguredAction(client, action, details, debugReason, getDefaultActionDone(action));
    }

    public static void handleConfiguredAction(
            Minecraft client,
            FailsafeAction action,
            String details,
            String debugReason,
            String actionDone
    ) {
        onFailsafeTriggered(action, details);
        if (MacroConfig.FAILSAFE_DESKTOP_NOTIFICATION_ENABLED.get()) {
            DesktopNotificationManager.notify(
                    getNotificationTitle(action),
                    (details + " " + actionDone).trim(),
                    shouldStopMacroOnTrigger(action));
        }

        if (shouldStopMacroOnTrigger(action)) {
            ClientUtils.sendMessage("§cFailsafe triggered: " + details + " Macro stopped.", false);
            MacroStateManager.stopMacro(client, debugReason, false);
            return;
        }

        ClientUtils.sendMessage("§eFailsafe detected (ignored): " + details, false);
        ClientUtils.sendDebugMessage(debugReason + " (ignored)");
    }

    private static String getDefaultActionDone(FailsafeAction action) {
        return switch (action) {
            case STOP -> "Macro stopped.";
            case IGNORE -> "Ignored.";
        };
    }
}
