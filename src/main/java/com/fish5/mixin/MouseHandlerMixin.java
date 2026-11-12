// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.mixin;

import com.fish5.features.impl.misc.UngrabMouse;
import com.fish5.features.impl.render.FreeCam;
import com.fish5.macro.InputLock;
import com.fish5.macro.MacroRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class MouseHandlerMixin {
    // the mouse would fight a rotation in progress, and RotationManager reads any drift as an external turn
    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void fish5$cancelTurnWhileRotating(double frameTime, CallbackInfo ci) {
        if (MacroRuntime.shouldCancelMouseTurn()) {
            ci.cancel();
            return;
        }
        // Input lock: only Freecam / Freelook may use the mouse to look around while a macro runs
        if (InputLock.isActive() && !InputLock.cameraFree() && Minecraft.getInstance().gui.screen() == null) {
            ci.cancel();
        }
    }

    // Ungrab Mouse: vanilla re-grabs on screen close / window click; keep the cursor free while released
    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private void fish5$keepUngrabbed(CallbackInfo ci) {
        if (UngrabMouse.isMouseUngrabbed()) {
            ci.cancel();
        }
    }

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void fish5$onButton(long window, MouseButtonInfo buttonInfo, int action, CallbackInfo ci) {
        if (action == 1) {
            MacroRuntime.onUserInput();
        }
        // a released cursor clicking another window over the game must not attack / use in game, and
        // physical clicks in Freecam would act on the body you left behind
        // Input lock: no in-game clicks at all while a macro runs (menus still get them)
        boolean inGame = Minecraft.getInstance().gui.screen() == null;
        if (UngrabMouse.isMouseUngrabbed()
                || (inGame && (FreeCam.isActive() || InputLock.isActive()))) {
            ci.cancel();
        }
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void fish5$onScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
        MacroRuntime.onUserInput();
        if (UngrabMouse.isMouseUngrabbed()) {
            ci.cancel();
            return;
        }
        // Input lock: no hotbar scrolling; Freecam / Freelook scroll is handled by ScrollFreeLookMixin
        if (InputLock.isActive() && !InputLock.cameraFree() && Minecraft.getInstance().gui.screen() == null) {
            ci.cancel();
        }
    }

    @Inject(method = "onMove", at = @At("HEAD"))
    private void fish5$onMove(long window, double mouseX, double mouseY, CallbackInfo ci) {
        MacroRuntime.onUserInput();
    }
}
