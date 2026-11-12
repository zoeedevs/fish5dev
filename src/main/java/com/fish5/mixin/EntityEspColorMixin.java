package com.fish5.mixin;

import com.fish5.features.impl.render.PlayerEsp;

import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Player ESP cham: the outline colour comes from {@code getTeamColor}, so override it for highlighted players. */
@Mixin(Entity.class)
public abstract class EntityEspColorMixin {

    @Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
    private void fish5$espColor(CallbackInfoReturnable<Integer> cir) {
        if (PlayerEsp.wantsOutline((Entity) (Object) this)) {
            cir.setReturnValue(PlayerEsp.outlineRgb());
        }
    }
}
