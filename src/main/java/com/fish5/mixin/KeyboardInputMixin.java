// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.mixin;

import com.fish5.features.impl.render.FreeCam;
import com.fish5.utils.ProgrammaticMovementTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// ors macro-held movement keys into the player input so they survive the window losing focus.
// While Freecam is active the physical keys fly the camera, so they are released here first (as if let
// go, so momentum decays normally); macro-held keys are still applied after, so a macro keeps walking.
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
    @Inject(method = "tick", at = @At("TAIL"))
    private void fish5$applyProgrammaticMovement(CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (client.options == null) {
            return;
        }

        // Input lock: while a macro runs the player's own movement keys never move the body either
        if (FreeCam.isActive() || com.fish5.macro.InputLock.isActive()) {
            this.keyPresses = Input.EMPTY;
            this.moveVector = Vec2.ZERO;
        }

        boolean macroForward = ProgrammaticMovementTracker.isDown(client.options.keyUp);
        boolean macroBackward = ProgrammaticMovementTracker.isDown(client.options.keyDown);
        boolean macroLeft = ProgrammaticMovementTracker.isDown(client.options.keyLeft);
        boolean macroRight = ProgrammaticMovementTracker.isDown(client.options.keyRight);
        boolean macroJump = ProgrammaticMovementTracker.isDown(client.options.keyJump);
        boolean macroShift = ProgrammaticMovementTracker.isDown(client.options.keyShift);
        boolean macroSprint = ProgrammaticMovementTracker.isDown(client.options.keySprint);
        if (!(macroForward || macroBackward || macroLeft || macroRight
                || macroJump || macroShift || macroSprint)) {
            return;
        }

        boolean forward = macroForward || this.keyPresses.forward();
        boolean backward = macroBackward || this.keyPresses.backward();
        boolean left = macroLeft || this.keyPresses.left();
        boolean right = macroRight || this.keyPresses.right();
        boolean jump = macroJump || this.keyPresses.jump();
        boolean shift = macroShift || this.keyPresses.shift();
        boolean sprint = macroSprint || this.keyPresses.sprint();

        this.keyPresses = new Input(forward, backward, left, right, jump, shift, sprint);
        this.moveVector = new Vec2(
                fish5$impulse(left, right),
                fish5$impulse(forward, backward)
        ).normalized();
    }

    // @Unique so a client that widened vanilla's calculateImpulse (lunar) is not shadowed
    @Unique
    private static float fish5$impulse(boolean positive, boolean negative) {
        if (positive == negative) {
            return 0.0f;
        }
        return positive ? 1.0f : -1.0f;
    }
}
