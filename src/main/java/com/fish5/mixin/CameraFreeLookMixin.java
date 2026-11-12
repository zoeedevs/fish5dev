package com.fish5.mixin;

import com.fish5.features.impl.render.FreeCam;
import com.fish5.features.impl.render.FreeLook;

import net.minecraft.client.Camera;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Redirects the camera's rotation to FreeLook's or FreeCam's decoupled yaw/pitch while either is
 * active, instead of the entity's real rotation. Ported from Jasper Client's
 * {@code CameraFreeLookMixin}.
 */
@Mixin(Camera.class)
public abstract class CameraFreeLookMixin {

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Redirect(
            method = "alignWithEntity(F)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/Camera;setRotation(FF)V")
    )
    private void fish5$redirectFreeLookRotation(Camera camera, float yaw, float pitch) {
        if (FreeLook.isActive()) {
            setRotation(FreeLook.getYaw(), FreeLook.getPitch());
        } else if (FreeCam.isActive()) {
            setRotation(FreeCam.getYaw(), FreeCam.getPitch());
        } else {
            setRotation(yaw, pitch);
        }
    }
}
