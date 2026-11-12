package com.fish5.macro;

import com.fish5.failsafe.FailsafeColourFlashManager;
import com.fish5.failsafe.FailsafeManager;
import com.fish5.failsafe.FailsafeSoundManager;
import com.fish5.failsafe.WorldChangeFailsafe;
import com.fish5.pathfinding.PathfindingManager;
import com.fish5.pathfinding.debug.PathVisualizer;
import com.fish5.pathfinding.rotation.RotationExecutor;
import com.fish5.rotation.RotationManager;
import com.fish5.session.BanGuard;
import com.fish5.session.RefocusInputGuard;
import com.fish5.session.RestScheduler;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

// boots the rotation / pathfinding / failsafe core and owns the hooks its mixins call
public final class MacroRuntime {
    private static boolean initialized = false;
    private static volatile boolean attackSuppressed = false;

    private MacroRuntime() {}

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        ClassPreloader.warmUp();
        FailsafeSoundManager.init();
        PathVisualizer.register();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // these two also run while disconnected (break countdown, reconnect, ban lock)
            BanGuard.tick(client);
            RestScheduler.tick(client);
            if (client.player == null) {
                return;
            }
            RefocusInputGuard.tick(client);
            FailsafeManager.tick(client);
            WorldChangeFailsafe.tick(client);
            // rotations also step per frame (onRenderFrame); the tick keeps them moving while unfocused
            RotationManager.update();
            RotationExecutor.update();
            PathfindingManager.update();
        });

        HudElementRegistry.attachElementAfter(
                VanillaHudElements.CHAT,
                Identifier.parse("fish5:failsafe_flash"),
                (graphics, delta) -> FailsafeColourFlashManager.render(graphics));

        LevelRenderEvents.END_MAIN.register(ctx -> {
            if (PathVisualizer.shouldRender()) {
                PathVisualizer.renderWorld();
            }
        });
    }

    // -- mixin hooks

    public static void onRenderFrame(Minecraft client) {
        if (client.player == null) {
            return;
        }
        RotationManager.update();
        RotationExecutor.update();
    }

    public static boolean shouldCancelMouseTurn() {
        return RotationManager.isRotating();
    }

    // a swing during a scripted step (e.g. reeling) would disturb it, so the macro can block attacks outright
    public static boolean isAttackSuppressed() {
        return attackSuppressed;
    }

    public static void setAttackSuppressed(boolean suppressed) {
        attackSuppressed = suppressed;
    }

    public static void onUserInput() {
        FailsafeColourFlashManager.dismiss();
    }
}
