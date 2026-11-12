package com.fish5.mixin;

import com.fish5.features.impl.render.FreeCam;

import net.minecraft.client.Camera;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Positions the camera at FreeCam's flying position while active, after {@code alignWithEntity} has
 * otherwise positioned it on the entity. Runs before {@code update(DeltaTracker)} builds the cull
 * frustum, so culling follows the flying camera too. Ported from Jasper Client's
 * {@code FreeCamCameraMixin}.
 */
@Mixin(Camera.class)
public abstract class FreeCamCameraMixin {

    @Shadow protected abstract void setPosition(double x, double y, double z);

    @Inject(
            method = "alignWithEntity(F)V",
            at = @At("RETURN")
    )
    private void fish5$freecamPosition(float tickDelta, CallbackInfo ci) {
        if (!FreeCam.isActive()) return;
        setPosition(
            FreeCam.getInterpolatedX(tickDelta),
            FreeCam.getInterpolatedY(tickDelta),
            FreeCam.getInterpolatedZ(tickDelta)
        );
    }
}
