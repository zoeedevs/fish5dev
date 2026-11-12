package com.fish5.mixin;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.FilterMode;
import com.fish5.utils.ui.rendering.SdfFont;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.renderer.state.gui.GlyphRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * GUI text is sampled with a sampler hardcoded in {@code GlyphRenderState.textureSetup()} -- NEAREST -- not
 * the atlas page's own one. That is why changing the font texture's filter alone never touched the click GUI.
 *
 * <p>Distance-field (and coverage) glyphs need LINEAR to reconstruct a smooth edge, so grayscale (R8) pages
 * get LINEAR here. Colour pages -- Minecraft's own pixel font, emoji -- keep NEAREST: bilinear would blur the
 * blocky vanilla font, which is meant to stay crisp. (A full font replacement could flip every page since it replaces the
 * vanilla font outright; Fish5 does not.) Pairs with the {@code core/text.fsh} override, which treats
 * grayscale samples as alpha only so LINEAR's partial edges do not blend in squared.
 */
@Mixin(GlyphRenderState.class)
public abstract class GlyphRenderStateLinearMixin {

    @Shadow @Final private TextRenderable renderable;

    @ModifyArg(
            method = "textureSetup",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/SamplerCache;getClampToEdge(Lcom/mojang/blaze3d/textures/FilterMode;)Lcom/mojang/blaze3d/textures/GpuSampler;"))
    private FilterMode fish5$smoothGrayscaleGuiText(FilterMode filter) {
        if (SdfFont.ownedElsewhere()) return filter; // another mod already handles this
        try {
            if (this.renderable.textureView().texture().getFormat() == GpuFormat.R8_UNORM) return FilterMode.LINEAR;
        } catch (Throwable ignored) {
            // Leave vanilla's choice if anything about the texture can't be read.
        }
        return filter;
    }
}
