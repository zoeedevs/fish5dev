package com.fish5.mixin;

import com.mojang.blaze3d.font.TrueTypeGlyphProvider;
import com.fish5.utils.ui.rendering.SdfFont;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Pads every TrueType glyph's atlas box by {@link SdfFont#SPREAD} texels on each side: a distance field
 * extends past the outline, and the quad is drawn from this box. The advance is untouched, so text layout
 * and widths do not change.
 *
 * <p>Arg indices count the constructor's synthetic outer instance:
 * {@code Glyph(TrueTypeGlyphProvider, float left, float top, int width, int height, float advance, int index)}.
 */
@Mixin(TrueTypeGlyphProvider.class)
public abstract class TrueTypeGlyphSdfMixin {
    private static final String GLYPH_INIT =
            "Lcom/mojang/blaze3d/font/TrueTypeGlyphProvider$Glyph;<init>(Lcom/mojang/blaze3d/font/TrueTypeGlyphProvider;FFIIFI)V";

    @ModifyArg(method = "loadGlyph", at = @At(value = "INVOKE", target = GLYPH_INIT), index = 1)
    private float fish5$padLeft(float left) {
        return SdfFont.enabled() ? left - SdfFont.SPREAD : left;
    }

    @ModifyArg(method = "loadGlyph", at = @At(value = "INVOKE", target = GLYPH_INIT), index = 2)
    private float fish5$padTop(float top) {
        return SdfFont.enabled() ? top + SdfFont.SPREAD : top;
    }

    @ModifyArg(method = "loadGlyph", at = @At(value = "INVOKE", target = GLYPH_INIT), index = 3)
    private int fish5$padWidth(int width) {
        return SdfFont.enabled() ? width + 2 * SdfFont.SPREAD : width;
    }

    @ModifyArg(method = "loadGlyph", at = @At(value = "INVOKE", target = GLYPH_INIT), index = 4)
    private int fish5$padHeight(int height) {
        return SdfFont.enabled() ? height + 2 * SdfFont.SPREAD : height;
    }
}
