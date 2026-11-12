package com.fish5.mixin;

import com.fish5.features.impl.render.PlayerEsp;

import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.entity.Avatar;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Player ESP username label: vanilla (and servers via scoreboard teams, e.g. Hypixel) can decide a
 * player's nametag shouldn't show at all, so {@link EntityNameTagMixin} never gets asked. Force it on
 * for ESP'd players; {@code getNameTag} then supplies the plain username.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererShowNameMixin {

    @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/Avatar;D)Z", at = @At("RETURN"), cancellable = true)
    private void fish5$espShowName(Avatar entity, double distSq, CallbackInfoReturnable<Boolean> cir) {
        if (!PlayerEsp.usesUsernameTag()) return;
        if (PlayerEsp.shouldHighlight(entity)) cir.setReturnValue(true);
        else cir.setReturnValue(false);
    }
}
