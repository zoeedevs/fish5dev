// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.pathfinding.rotation.strategy;

import com.fish5.pathfinding.rotation.AngleUtils;
import com.fish5.pathfinding.rotation.IRotationStrategy;
import com.fish5.pathfinding.rotation.Rotation;
import net.minecraft.client.player.LocalPlayer;

public final class TrackingRotationStrategy implements IRotationStrategy {
    private float degreesPerSecond;
    private long lastUpdate;

    public TrackingRotationStrategy(float degreesPerSecond) {
        setSpeed(degreesPerSecond);
    }

    public void setSpeed(float degreesPerSecond) {
        this.degreesPerSecond = Math.clamp(degreesPerSecond, 1.0f, 1440.0f);
    }

    @Override
    public void onStart() {
        lastUpdate = System.nanoTime();
    }

    @Override
    public Rotation onRotate(LocalPlayer player, float targetYaw, float targetPitch) {
        long now = System.nanoTime();
        double seconds = Math.clamp((now - lastUpdate) / 1.0e9, 0.0, 0.1);
        lastUpdate = now;
        return step(player.getYRot(), player.getXRot(), targetYaw, targetPitch, degreesPerSecond, seconds);
    }

    public static Rotation step(float yaw, float pitch, float targetYaw, float targetPitch,
                                float degreesPerSecond, double seconds) {
        float yawDelta = AngleUtils.getRotationDelta(yaw, targetYaw);
        float pitchDelta = Math.clamp(targetPitch, -90.0f, 90.0f) - pitch;
        double magnitude = Math.hypot(yawDelta, pitchDelta);
        double response = 1.0 - Math.exp(-10.0 * Math.max(0.0, seconds));
        double scale = magnitude < 1.0e-6 ? 0.0
                : Math.min(response, Math.max(0.0, degreesPerSecond * seconds) / magnitude);
        return new Rotation(yaw + (float) (yawDelta * scale),
                Math.clamp(pitch + (float) (pitchDelta * scale), -90.0f, 90.0f));
    }
}
