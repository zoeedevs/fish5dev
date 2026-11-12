package com.fish5.mixin;

import com.fish5.features.impl.render.FreeCam;
import com.fish5.features.impl.render.FreeLook;

import net.minecraft.client.MouseHandler;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routes mouse-wheel scroll to FreeCam's speed-adjust or FreeLook's zoom instead of vanilla's scroll
 * handling (hotbar switch / zoom) while either is active. Ported from Jasper Client's
 * {@code ScrollFreeLookMixin} (minus its Zoom-feature branch — fish5 has no Zoom module).
 */
@Mixin(MouseHandler.class)
public class ScrollFreeLookMixin {

    @Inject(
            method = "onScroll(JDD)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void fish5$interceptScrollForFreeLook(long window, double horizontal, double vertical,
                                                   CallbackInfo ci) {
        if (vertical == 0.0) return;
        if (FreeCam.isActive()) {
            // input lock: never fall through to hotbar scrolling while a macro runs
            if (FreeCam.scroll(vertical) || com.fish5.macro.InputLock.isActive()) ci.cancel();
        } else if (FreeLook.isActive()) {
            FreeLook.scrollZoom(vertical);
            ci.cancel();
        }
    }
}
