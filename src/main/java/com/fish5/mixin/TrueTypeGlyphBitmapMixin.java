package com.fish5.mixin;

import com.fish5.utils.ui.rendering.SdfFont;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Tags the TrueType glyph bitmap (the anonymous {@code GlyphBitmap} built in
 * {@code TrueTypeGlyphProvider.Glyph#bake}) so {@code FontTextureSdfMixin} can file its distance field on a
 * distance-field atlas page.
 */
@Mixin(targets = "com.mojang.blaze3d.font.TrueTypeGlyphProvider$Glyph$1")
public abstract class TrueTypeGlyphBitmapMixin implements SdfFont.DistanceFieldBitmap {
}
