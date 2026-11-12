package com.fish5.mixin;

import com.fish5.features.impl.render.PlayerEsp;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Player ESP "Username" mode: hide every vanilla player nametag and show a plain username only on
 * ESP'd players. {@code getNameTag} lives once on {@link EntityRenderer}, so this one hook covers
 * every player's floating name.
 */
@Mixin(EntityRenderer.class)
public class EntityNameTagMixin {

    @Inject(method = "getNameTag", at = @At("RETURN"), cancellable = true)
    private void fish5$espNameTag(Entity entity, CallbackInfoReturnable<Component> cir) {
        if (!(entity instanceof Player) || !PlayerEsp.usesUsernameTag()) return;
        cir.setReturnValue(PlayerEsp.shouldHighlight(entity) ? entity.getName() : null);
    }
}
