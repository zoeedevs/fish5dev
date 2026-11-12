// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.mixin;

import com.fish5.macro.MacroRuntime;
import com.fish5.utils.ProgrammaticAttackTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public class MinecraftAttackInputMixin {
    // a left click can come from a stale macro latch, a queued click, or the player's own mouse
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void fish5$blockAttack(CallbackInfoReturnable<Boolean> cir) {
        if (MacroRuntime.isAttackSuppressed()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void fish5$blockHeldAttack(boolean holding, CallbackInfo ci) {
        if (MacroRuntime.isAttackSuppressed()) {
            ci.cancel();
        }
    }

    // a macro-held attack keeps swinging while the window is unfocused and the mouse is released
    @Redirect(
            method = "handleKeybinds",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/MouseHandler;isMouseGrabbed()Z"
            )
    )
    private boolean fish5$useHeldAttackInput(MouseHandler mouseHandler) {
        return mouseHandler.isMouseGrabbed()
                || ProgrammaticAttackTracker.shouldTreatMouseAsGrabbed((Minecraft) (Object) this);
    }

    @Redirect(
            method = "handleKeybinds",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/KeyMapping;isDown()Z"
            )
    )
    private boolean fish5$useLatchedAttackKey(KeyMapping mapping) {
        Minecraft client = (Minecraft) (Object) this;
        return mapping.isDown()
                || (client.gui.screen() == null
                && client.options != null
                && mapping == client.options.keyAttack
                && ProgrammaticAttackTracker.isHeld(mapping));
    }
}
