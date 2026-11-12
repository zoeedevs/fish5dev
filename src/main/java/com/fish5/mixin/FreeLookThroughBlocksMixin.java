package com.fish5.mixin;

import com.fish5.features.impl.render.FreeLook;

import net.minecraft.client.Camera;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FreeLook's "Through blocks" setting: skips the wall-collision clamp entirely, returning the full
 * requested zoom distance so the camera clips through terrain instead of snapping close when something
 * is behind you. Ported from Jasper Client's {@code FreeLookThroughBlocksMixin}.
 */
@Mixin(Camera.class)
public abstract class FreeLookThroughBlocksMixin {

    @Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true)
    private void fish5$throughBlocks(float distance, CallbackInfoReturnable<Float> cir) {
        if (FreeLook.throughBlocks()) {
            cir.setReturnValue(distance);
        }
    }
}
