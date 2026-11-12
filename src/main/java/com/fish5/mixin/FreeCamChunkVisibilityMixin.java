package com.fish5.mixin;

import com.fish5.features.impl.render.FreeCam;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FreeCam pulls the client's view radius/centre to the flying camera ({@code FreeCam.followChunks}),
 * but a freshly-focused section may not be compiled yet on the frame it first comes into view, which
 * would otherwise show a hole. Treat every section as ready while FreeCam is active, matching the
 * camera-follow it drives. Ported from Jasper Client's {@code FreeCamChunkVisibilityMixin}.
 */
@Mixin(LevelRenderer.class)
public abstract class FreeCamChunkVisibilityMixin {

    @Inject(method = "isSectionCompiledAndVisible", at = @At("HEAD"), cancellable = true)
    private void fish5$freecamSectionsReady(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (FreeCam.isActive()) {
            cir.setReturnValue(true);
        }
    }
}
