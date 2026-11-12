package com.fish5.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;

import com.fish5.features.impl.render.Fullbright;

import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;

import org.joml.Vector4f;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fullbright, ported from the original fish5's FullbrightModule.
 *
 * <p>Rather than the old gamma-spoof (26.1 clamps the Brightness option to [0,1] and its codec
 * rejects out-of-range values), this overwrites the lightmap GPU texture with solid white after
 * {@link Lightmap#render} has computed it — full brightness regardless of gamma, server lightmap
 * shaders, or the Darkness effect, and dimension-safe (unlike the old gamma approach in the End).
 * {@code render} early-returns when its state doesn't need updating, but injecting at RETURN still
 * fires on every call, so the white clear persists every frame while enabled.
 *
 * <p>26.1.2 note: {@code CommandEncoder.clearColorTexture} takes an ARGB {@code int} here (0xFFFFFFFF
 * = opaque white), not the {@code Vector4f} used on the 26.2 line.
 */
@Mixin(Lightmap.class)
public class LightmapFullbrightMixin {

    @Shadow
    @Final
    private GpuTexture texture;

    @Inject(method = "render", at = @At("RETURN"))
    private void fish5$forceWhiteLightmap(LightmapRenderState renderState, CallbackInfo ci) {
        if (!Fullbright.INSTANCE.getEnabled()) return;
        RenderSystem.getDevice()
                .createCommandEncoder()
                .clearColorTexture(this.texture, new Vector4f(1.0f, 1.0f, 1.0f, 1.0f));
    }
}
