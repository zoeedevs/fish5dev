package com.fish5.mixin;

import com.fish5.features.impl.render.ContinuousSwing;
import com.fish5.features.impl.render.CustomSwingSpeed;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Custom swing speed, ported from the original fish5's AnimationsModule.
 *
 * <p>Wraps {@code getCurrentSwingDuration()} (read by both {@code updateSwingTime} and {@code swing})
 * so overriding it re-times the swing consistently. {@code speed} is the swing duration in ticks
 * (lower = faster, vanilla = 6); "ignore haste" pins it to that constant instead of letting Haste /
 * Mining Fatigue scale it.
 *
 * <p>Note: the "swing resets while farming" problem is <b>not</b> here — that's the held-item
 * re-equip animation, handled by {@link ItemInHandRendererReequipMixin}.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntitySwingMixin {

    @Inject(method = "getCurrentSwingDuration", at = @At("RETURN"), cancellable = true)
    private void fish5$customSwingSpeed(CallbackInfoReturnable<Integer> cir) {
        CustomSwingSpeed m = CustomSwingSpeed.INSTANCE;
        if (!m.getEnabled()) return;

        int speed = m.getSpeed();
        boolean ignoreHaste = m.getIgnoreHaste();

        // Vanilla speed while still honouring haste → leave vanilla behaviour untouched.
        if (speed == 6 && !ignoreHaste) return;

        int vanilla = cir.getReturnValueI();
        int duration = ignoreHaste ? speed : (vanilla + speed - 6);
        cir.setReturnValue(Math.max(1, duration));
    }

    /**
     * Continuous Swing — record that the local player just swung so the visual phase keeps looping.
     * This only marks the animation active; it does NOT reset the phase and does not alter the swing
     * itself (no cancel, no packet change). Both {@code swing(hand)} overloads route through here.
     *
     * @see ContinuousSwing
     */
    @Inject(method = "swing(Lnet/minecraft/world/InteractionHand;Z)V", at = @At("HEAD"))
    private void fish5$recordContinuousSwing(InteractionHand hand, boolean updateSelf, CallbackInfo ci) {
        CustomSwingSpeed m = CustomSwingSpeed.INSTANCE;
        if (!m.getEnabled() || !m.getSmoothSwing()) return;
        if ((Object) this == Minecraft.getInstance().player) {
            ContinuousSwing.INSTANCE.onSwing();
        }
    }
}
