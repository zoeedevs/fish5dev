package com.fish5.mixin;

import com.mojang.blaze3d.vertex.PoseStack;

import com.fish5.features.impl.render.HeldItem;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Held Item transform (position + size), ported from the original fish5's AnimationsModule.
 *
 * <p>Translates and scales the first-person main-hand item model just before it is rendered.
 * The two {@code renderItem} call sites in {@code renderArmWithItem} are mutually exclusive
 * branches, each immediately followed by {@code popPose}, so transforming the {@link PoseStack}
 * here affects only the item model (not the arm) with no leak into the rest of the frame.
 * Order matches the original {@code applyTransform}: translate first (in item-render units), then
 * scale.
 */
@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererTransformMixin {

    @Inject(
        method = "submitArmWithItem",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem("
                + "Lnet/minecraft/world/entity/LivingEntity;"
                + "Lnet/minecraft/world/item/ItemStack;"
                + "Lnet/minecraft/world/item/ItemDisplayContext;"
                + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                + "Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"
        )
    )
    private void fish5$transformHeldItem(AbstractClientPlayer player, float tickProgress, float pitch,
                                        InteractionHand hand, float swingProgress, ItemStack item,
                                        float equipProgress, PoseStack matrices, SubmitNodeCollector collector,
                                        int light, CallbackInfo ci) {
        if (hand != InteractionHand.MAIN_HAND) return;

        HeldItem m = HeldItem.INSTANCE;
        if (!m.getEnabled()) return;

        matrices.translate(m.getPosX(), m.getPosY(), m.getPosZ());
        float s = m.getSize();
        matrices.scale(s, s, s);
    }
}
