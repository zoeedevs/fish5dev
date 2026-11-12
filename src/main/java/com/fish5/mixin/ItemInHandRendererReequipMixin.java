package com.fish5.mixin;

import com.fish5.features.impl.render.CustomSwingSpeed;

import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Smooth Swing — the real fix for "the swing resets over and over while farming".
 *
 * <p>That reset is not the swing timer: it's the held-item re-equip animation. When your held
 * item changes, {@code tick()} calls {@code shouldInstantlyReplaceVisibleItem}; if that's false the
 * renderer lowers the item and pops it back up (the equip dip). Farming tools mutate their NBT
 * every break (counters like Cultivating/Cocoa, etc.), so the item "changes" constantly and the dip
 * plays over and over — exactly the reset being described.
 *
 * <p>Forcing an instant replace makes the visible item update with no dip, so a continuously-held
 * farm shows one uninterrupted swing. This mirrors DulkirMod's "Cancel Re-equip Animation".
 */
@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererReequipMixin {

    @Inject(method = "shouldInstantlyReplaceVisibleItem", at = @At("HEAD"), cancellable = true)
    private void fish5$cancelReequip(ItemStack from, ItemStack to, CallbackInfoReturnable<Boolean> cir) {
        CustomSwingSpeed m = CustomSwingSpeed.INSTANCE;
        if (m.getEnabled() && m.getSmoothSwing()) {
            cir.setReturnValue(true);
        }
    }
}
