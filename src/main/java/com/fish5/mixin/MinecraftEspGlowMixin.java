package com.fish5.mixin;

import com.fish5.features.impl.render.PlayerEsp;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Player ESP cham: make highlighted players render into the entity outline buffer. */
@Mixin(Minecraft.class)
public abstract class MinecraftEspGlowMixin {

    @Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
    private void fish5$espGlow(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (PlayerEsp.wantsOutline(entity)) {
            cir.setReturnValue(true);
        }
    }
}
