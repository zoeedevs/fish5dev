package com.fish5.mixin;

import com.fish5.features.impl.render.FreeCam;
import com.fish5.features.impl.render.FreeLook;
import com.fish5.features.impl.render.FreelookModule;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the raw mouse turn for FreeLook (Camera mode) or FreeCam instead of letting it turn the
 * player's real body. In FreeLook's Player mode the mouse turns the real body as normal — fall
 * through, don't cancel; the decoupled camera just auto-orbits in that mode (see
 * {@code FreeLook.tick}). Ported from Jasper Client's {@code MouseFreeLookMixin}.
 */
@Mixin(Entity.class)
public class MouseFreeLookMixin {

    @Inject(
            method = "turn(DD)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void fish5$captureFreeLookInput(double deltaX, double deltaY, CallbackInfo ci) {
        if (Minecraft.getInstance().player != (Object) this) return;
        if (FreeLook.isActive() && FreelookModule.isCameraMode()) {
            FreeLook.addMouseDelta(deltaX, deltaY);
            ci.cancel();
        } else if (FreeCam.isActive()) {
            FreeCam.addMouseDelta(deltaX, deltaY);
            ci.cancel();
        }
    }
}
