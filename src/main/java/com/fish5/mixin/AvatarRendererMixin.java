package com.fish5.mixin;

import com.mojang.blaze3d.vertex.PoseStack;

import com.fish5.features.impl.render.PlayerSize;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Player Size, ported from Odin's PlayerSize / AvatarRendererMixin (same MC 26.1.2 Mojmap base).
 *
 * <p>Scales the local player's model at the start of {@code AvatarRenderer#scale}. Only the local
 * player is affected — identified via the render state's entity {@code id} (Odin attaches the
 * GameProfile through a Fabric render-state data key because it also rescales other players via a
 * networked "randoms" list; that dev-server feature is intentionally not ported, so the entity-id
 * check is all we need). A negative Y scale flips the model upside-down; the matching translate
 * keeps it positioned, exactly as Odin does.
 */
@Mixin(AvatarRenderer.class)
public class AvatarRendererMixin {

    @Inject(
        method = "scale(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
        at = @At("HEAD")
    )
    private void fish5$playerSize(AvatarRenderState state, PoseStack poseStack, CallbackInfo ci) {
        PlayerSize m = PlayerSize.INSTANCE;
        if (!m.getEnabled()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || state.id != mc.player.getId()) return; // local player only

        float x = m.getSizeX();
        float y = m.getSizeY();
        float z = m.getSizeZ();
        if (y < 0) poseStack.translate(0f, y * 2f, 0f);
        poseStack.scale(x, y, z);
    }
}
