package com.fish5.mixin;

import com.mojang.blaze3d.font.GlyphBitmap;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.fish5.utils.ui.rendering.SdfFont;
import net.minecraft.client.gui.font.FontTexture;
import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Font atlas pages for the distance-field text pipeline (see {@link SdfFont}).
 *
 * <ul>
 *   <li>Grayscale pages hold ONE kind of glyph: distance fields (TrueType) or coverage (Unifont etc.). The
 *       first glyph on a fresh page decides; the other kind is refused so the stitcher files it on another
 *       page. Before that first glyph a 1x1 marker is inserted -- the allocator always places the first insert
 *       at texel (0,0) -- so {@code core/text.fsh} can read the page kind from that texel.</li>
 *   <li>Each fresh page is cleared to transparent: with bilinear filtering a glyph quad's edge blends half a
 *       texel into the 1px packing gap, and undefined driver memory there would fringe glyph borders. The
 *       clear needs USAGE_RENDER_ATTACHMENT, which vanilla's creation flags lack, so it is added.</li>
 *   <li>The page's own sampler (used by world text: nameplates, signs) becomes LINEAR + clamp-to-edge.
 *       GUI text is sampled separately -- see {@link GlyphRenderStateLinearMixin}.</li>
 * </ul>
 * Everything is skipped when {@link SdfFont#ownedElsewhere()} says another mod owns the pipeline.
 */
@Mixin(FontTexture.class)
public abstract class FontTextureSdfMixin extends AbstractTexture {

    @Unique private static final Vector4f FISH5$TRANSPARENT = new Vector4f(0f, 0f, 0f, 0f);
    @Unique private static final int FISH5$COVERAGE = 1;
    @Unique private static final int FISH5$DISTANCE_FIELD = 2;

    @Shadow @Final private boolean colored;

    /** 0 until the first glyph arrives, then COVERAGE or DISTANCE_FIELD. */
    @Unique private int fish5$pageKind;
    @Unique private boolean fish5$placingMarker;

    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void fish5$onePageKind(GlyphInfo info, GlyphBitmap glyph, CallbackInfoReturnable<BakedSheetGlyph> cir) {
        if (SdfFont.ownedElsewhere()) return;
        if (this.colored || this.fish5$placingMarker || glyph.isColored()) return;
        int kind = SdfFont.isDistanceField(glyph) ? FISH5$DISTANCE_FIELD : FISH5$COVERAGE;
        if (this.fish5$pageKind == 0) {
            this.fish5$pageKind = kind;
            this.fish5$placingMarker = true;
            try {
                ((FontTexture) (Object) this).add(SdfFont.MARKER_INFO, SdfFont.pageMarker(kind == FISH5$DISTANCE_FIELD));
            } finally {
                this.fish5$placingMarker = false;
            }
        } else if (this.fish5$pageKind != kind) {
            cir.setReturnValue(null);
        }
    }

    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/GpuFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;"),
            index = 1)
    private int fish5$clearableFontPages(int usage) {
        return SdfFont.ownedElsewhere() ? usage : usage | GpuTexture.USAGE_RENDER_ATTACHMENT;
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void fish5$smoothFontPages(CallbackInfo ci) {
        if (SdfFont.ownedElsewhere()) return;
        RenderSystem.getDevice().createCommandEncoder().clearColorTexture(this.texture, FISH5$TRANSPARENT);
        // CLAMP_TO_EDGE, not REPEAT: a 256x256 glyph atlas must never wrap, or a glyph packed against an
        // edge would sample a sliver of an unrelated glyph from the opposite edge under bilinear filtering.
        this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
    }
}
