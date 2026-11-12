package com.fish5.mixin;

import com.fish5.features.impl.render.FreeLook;

import net.minecraft.client.Camera;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies FreeLook's scroll-zoom FOV offset on top of {@code Camera.calculateFov}'s result. Ported
 * from Jasper Client's {@code FovFreeLookMixin} (minus its Zoom-feature offset — fish5 has no Zoom
 * module to stack with).
 */
@Mixin(Camera.class)
public class FovFreeLookMixin {

    @Inject(
            method = "calculateFov(F)F",
            at = @At("RETURN"),
            cancellable = true
    )
    private void fish5$applyFreeLookZoom(float tickDelta, CallbackInfoReturnable<Float> cir) {
        if (!FreeLook.isActive()) return;
        float offset = FreeLook.getZoomFovOffset();
        if (offset == 0f) return;
        // Floor at 1 degree — a low FOV option minus the offset could otherwise reach <= 0 degrees,
        // a degenerate/inverted projection matrix.
        cir.setReturnValue(Math.max(1f, cir.getReturnValue() + offset));
    }
}
