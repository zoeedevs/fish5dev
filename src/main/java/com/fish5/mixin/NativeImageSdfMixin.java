package com.fish5.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.fish5.utils.ui.rendering.SdfFont;
import org.lwjgl.util.freetype.FT_Face;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Uploads TrueType glyphs as distance fields instead of coverage. {@code copyFromFont} has exactly one
 * caller -- the TrueType glyph bitmap's upload -- whose image is already sized to the padded box
 * ({@code TrueTypeGlyphSdfMixin}), so replacing it here covers every TTF glyph and nothing else.
 */
@Mixin(NativeImage.class)
public abstract class NativeImageSdfMixin {
    @Inject(method = "copyFromFont", at = @At("HEAD"), cancellable = true)
    private void fish5$bakeDistanceField(FT_Face face, int index, CallbackInfoReturnable<Boolean> cir) {
        if (!SdfFont.enabled()) return;
        cir.setReturnValue(SdfFont.render((NativeImage) (Object) this, face, index));
    }
}
