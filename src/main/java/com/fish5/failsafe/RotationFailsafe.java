// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.failsafe;

import com.fish5.config.MacroConfig;
import com.fish5.macro.MacroState;
import com.fish5.macro.MacroStateManager;
import com.fish5.rotation.RotationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

final class RotationFailsafe {
    enum State {
        IDLE,
        WAIT,
        TRIGGERED
    }

    private static volatile boolean expectedRotationSet = false;
    private static volatile float expectedYaw = 0.0f;
    private static volatile float expectedPitch = 0.0f;
    private static volatile long graceUntilMs = 0L;
    private static volatile long mismatchSince = 0L;
    private static volatile long mismatchRandomDelayMs = 0L;
    private static volatile boolean triggered = false;
    private static volatile boolean targetTracking = false;

    private RotationFailsafe() {}

    static void reset() {
        expectedRotationSet = false;
        expectedYaw = 0.0f;
        expectedPitch = 0.0f;
        graceUntilMs = 0L;
        mismatchSince = 0L;
        mismatchRandomDelayMs = 0L;
        triggered = false;
    }

    static void setTargetTracking(boolean active) {
        targetTracking = active;
    }

    static boolean isTargetTracking() {
        return targetTracking;
    }

    static void expectRotation(float yaw, float pitch) {
        expectedRotationSet = true;
        expectedYaw = Mth.wrapDegrees(yaw);
        expectedPitch = Mth.clamp(pitch, -90.0f, 90.0f);
        mismatchSince = 0L;
        mismatchRandomDelayMs = 0L;
    }

    static void syncExpectedRotationFromClient(Minecraft client) {
        if (client == null || client.player == null) return;
        expectRotation(client.player.getYRot(), client.player.getXRot());
        triggered = false;
    }

    static void reportExternalRotation() {
        if (mismatchSince != 0L) return;
        mismatchSince = System.currentTimeMillis();
        mismatchRandomDelayMs = FailsafeManager.sampleAdditionalTriggerDelayMs();
    }

    static void addGracePeriod(long durationMs) {
        if (durationMs <= 0L) return;
        long until = System.currentTimeMillis() + durationMs;
        graceUntilMs = Math.max(graceUntilMs, until);
        mismatchSince = 0L;
        mismatchRandomDelayMs = 0L;
    }

    private static void clearMismatch() {
        mismatchSince = 0L;
        mismatchRandomDelayMs = 0L;
    }

    static void tick(Minecraft client) {
        if (client == null || client.player == null) {
            reset();
            return;
        }

        MacroState.State state = MacroStateManager.getCurrentState();
        if (state == MacroState.State.OFF || state == MacroState.State.RECOVERING) {
            syncExpectedRotationFromClient(client);
            clearMismatch();
            return;
        }

        if (!MacroConfig.FAILSAFE_ROTATION.get()) {
            clearMismatch();
            if (!expectedRotationSet) {
                syncExpectedRotationFromClient(client);
            }
            return;
        }

        if (System.currentTimeMillis() < graceUntilMs) {
            syncExpectedRotationFromClient(client);
            clearMismatch();
            return;
        }

        if (targetTracking && !MacroConfig.FAILSAFE_ROTATION_TRIGGER_DURING_TRACKING.get()) {
            syncExpectedRotationFromClient(client);
            clearMismatch();
            return;
        }

        if (!expectedRotationSet) {
            syncExpectedRotationFromClient(client);
            return;
        }

        float currentYaw = Mth.wrapDegrees(client.player.getYRot());
        float currentPitch = Mth.clamp(client.player.getXRot(), -90.0f, 90.0f);
        float yawDiff = Math.abs(Mth.wrapDegrees(currentYaw - expectedYaw));
        float pitchDiff = Math.abs(currentPitch - expectedPitch);

        if (yawDiff <= MacroConfig.FAILSAFE_ROTATION_YAW_THRESHOLD.get()
                && pitchDiff <= MacroConfig.FAILSAFE_ROTATION_PITCH_THRESHOLD.get()) {
            clearMismatch();
            return;
        }

        if (mismatchSince == 0L) {
            mismatchSince = System.currentTimeMillis();
            mismatchRandomDelayMs = FailsafeManager.sampleAdditionalTriggerDelayMs();
            if (shouldSuppressTrackingRotation(client)) {
                RotationManager.cancelRotation();
            }
            return;
        }

        if (shouldSuppressTrackingRotation(client)) {
            RotationManager.cancelRotation();
        }

        long triggerDelayMs = getConfiguredTriggerDelayMs() + mismatchRandomDelayMs;
        if (System.currentTimeMillis() - mismatchSince < triggerDelayMs) {
            return;
        }

        trigger(client, currentYaw, currentPitch, yawDiff, pitchDiff);
    }

    static State getState(Minecraft client) {
        if (triggered) return State.TRIGGERED;
        MacroState.State state = MacroStateManager.getCurrentState();
        if (client == null || client.player == null
                || state == MacroState.State.OFF
                || state == MacroState.State.RECOVERING
                || !MacroConfig.FAILSAFE_ROTATION.get() || !expectedRotationSet) {
            return State.IDLE;
        }
        if (System.currentTimeMillis() < graceUntilMs) {
            return State.IDLE;
        }

        if (targetTracking && !MacroConfig.FAILSAFE_ROTATION_TRIGGER_DURING_TRACKING.get()) {
            return State.IDLE;
        }

        float currentYaw = Mth.wrapDegrees(client.player.getYRot());
        float currentPitch = Mth.clamp(client.player.getXRot(), -90.0f, 90.0f);
        float yawDiff = Math.abs(Mth.wrapDegrees(currentYaw - expectedYaw));
        float pitchDiff = Math.abs(currentPitch - expectedPitch);
        if (yawDiff > MacroConfig.FAILSAFE_ROTATION_YAW_THRESHOLD.get()
                || pitchDiff > MacroConfig.FAILSAFE_ROTATION_PITCH_THRESHOLD.get()) {
            return State.WAIT;
        }
        return State.IDLE;
    }

    static float getExpectedYaw() {
        return expectedYaw;
    }

    static float getExpectedPitch() {
        return expectedPitch;
    }

    static long getTriggerRemainingMs() {
        if (mismatchSince == 0L) return 0L;
        long elapsedMs = System.currentTimeMillis() - mismatchSince;
        long triggerDelayMs = getConfiguredTriggerDelayMs() + mismatchRandomDelayMs;
        return Math.max(0L, triggerDelayMs - elapsedMs);
    }

    // a check that turns the camera mid-track must not be answered by the tracker turning it straight back
    static boolean shouldSuppressTrackingRotation(Minecraft client) {
        return client != null
                && mismatchSince != 0L
                && targetTracking
                && MacroConfig.FAILSAFE_ROTATION_TRIGGER_DURING_TRACKING.get()
                && getState(client) == State.WAIT;
    }

    private static long getConfiguredTriggerDelayMs() {
        if (targetTracking && MacroConfig.FAILSAFE_ROTATION_TRIGGER_DURING_TRACKING.get()) {
            return MacroConfig.FAILSAFE_ROTATION_TRACKING_DELAY_MS.get();
        }
        return Math.round(MacroConfig.FAILSAFE_ROTATION_TRIGGER_DELAY_SECONDS.get() * 1000.0f);
    }

    private static void trigger(Minecraft client, float currentYaw, float currentPitch, float yawDiff, float pitchDiff) {
        if (triggered) {
            return;
        }

        boolean trackingRotation = targetTracking;
        FailsafeAction action = FailsafeManager.getRotationAction(trackingRotation);
        triggered = true;
        FailsafeManager.notifyInGame(action, "Player rotation changed unexpectedly.");
        FailsafeManager.handleConfiguredAction(
                client,
                action,
                String.format(java.util.Locale.US,
                        "unexpected rotation. Current yaw/pitch %.1f / %.1f, expected %.1f / %.1f, diff %.1f / %.1f.",
                        currentYaw, currentPitch, expectedYaw, expectedPitch, yawDiff, pitchDiff),
                String.format(java.util.Locale.US,
                        "RotationFailsafe: unexpected rotation (current %.1f / %.1f, expected %.1f / %.1f, diff %.1f / %.1f)",
                        currentYaw, currentPitch, expectedYaw, expectedPitch, yawDiff, pitchDiff));
        reset();
    }
}
