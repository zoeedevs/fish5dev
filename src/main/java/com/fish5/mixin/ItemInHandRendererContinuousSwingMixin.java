package com.fish5.mixin;

import com.fish5.features.impl.render.ContinuousSwing;
import com.fish5.features.impl.render.CustomSwingSpeed;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Continuous Swing — visual-only looping of the first-person swing while actively farming/mining.
 *
 * <p>{@code renderHandsWithItems} reads {@code localPlayer.getAttackAnim(tickProgress)} once and
 * feeds that swing progress to the arm/item transforms. We redirect only that call, so the change is
 * confined to the first-person hand — third-person, other players, and all gameplay are untouched.
 * When Continuous Swing is off (or we're at rest) we return the unmodified vanilla value.
 *
 * @see ContinuousSwing
 */
@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererContinuousSwingMixin {

    @Redirect(
        method = "submitHandsWithItems",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;getAttackAnim(F)F"
        )
    )
    private float fish5$continuousAttackAnim(LocalPlayer player, float partialTick) {
        float vanilla = player.getAttackAnim(partialTick);

        CustomSwingSpeed m = CustomSwingSpeed.INSTANCE;
        if (m.getEnabled() && m.getSmoothSwing()) {
            float p = ContinuousSwing.INSTANCE.progress(m.getSpeed());
            if (p >= 0f) return p;
        }
        return vanilla;
    }
}
