package com.fish5.mixin;

import com.fish5.features.impl.render.FreeCam;
import com.fish5.macro.MacroStateManager;

import net.minecraft.client.player.LocalPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The actual choke point for turning held keys into body movement: {@code LocalPlayer.applyInput()}
 * reads {@code this.input}'s move vector/keyPresses <i>once</i> per tick and writes the result straight
 * into {@code xxa}/{@code zza}/{@code jumping} — those three fields are what {@code aiStep} then moves
 * the body with. {@link KeyboardInputMixin} clears the input at the <i>production</i> end
 * (after {@code KeyboardInput.tick()} computes it from whatever is currently "down"), which covers real
 * keys and anything that fakes being "down" through the normal key-binding state; but anything that
 * writes straight into {@code ClientInput} or these fields instead — a macro mod driving the player
 * directly, or just a scheduling race with another mixin — would slip past that and still move the
 * body. Cancelling here, at the consumption end, catches all of it regardless of where the input came
 * from: real hardware, another mod's macro, or anything else. Zeroing {@code xxa}/{@code zza}/
 * {@code jumping} ourselves before cancelling matters — without it they'd simply freeze at whatever
 * they were the tick FreeCam turned on and the body would drift forever, which is the opposite of what
 * we want. Momentum the body already had keeps decaying on its own through {@code aiStep}'s normal
 * friction — this only stops *new* input from feeding it, same "release, don't freeze" intent as
 * {@link KeyboardInputMixin}.
 */
@Mixin(LocalPlayer.class)
public abstract class FreeCamBlockApplyInputMixin {

    @Inject(method = "applyInput", at = @At("HEAD"), cancellable = true)
    private void fish5$blockWhileFreeCam(CallbackInfo ci) {
        // A running macro moves the body through KeyboardInput (KeyboardInputMixin already dropped the
        // physical keys there), so only block here when no macro is driving.
        if (!FreeCam.isActive() || MacroStateManager.isMacroRunning()) return;
        LocalPlayer self = (LocalPlayer) (Object) this;
        self.xxa = 0f;
        self.zza = 0f;
        self.setJumping(false);
        ci.cancel();
    }
}
