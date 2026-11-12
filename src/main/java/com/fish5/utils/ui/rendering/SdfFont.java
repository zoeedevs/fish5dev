package com.fish5.utils.ui.rendering;

import com.mojang.blaze3d.font.GlyphBitmap;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import java.nio.ByteBuffer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.font.providers.FreeTypeUtil;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.freetype.FT_Bitmap;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FT_GlyphSlot;
import org.lwjgl.util.freetype.FreeType;

/**
 * TrueType glyphs baked as signed distance fields, so text stays sharp at any scale.
 *
 * <p>A coverage atlas is a fixed-resolution picture of each glyph; drawn bigger or smaller than that,
 * Minecraft's NEAREST sampling stair-steps it and plain bilinear smears it. A distance field stores how
 * far each texel is from the outline instead, and {@code assets/minecraft/shaders/core/text.fsh} rebuilds
 * a one-screen-pixel anti-aliased edge from it at whatever size the glyph lands on screen.
 *
 * <p>FreeType bakes the field itself ({@code FT_RENDER_MODE_SDF}) from the ordinary coverage bitmap (its
 * {@code bsdf} module) -- rendering straight from the outline punches holes where a font's contours overlap.
 *
 * <p>Wiring: {@code TrueTypeGlyphSdfMixin} pads each glyph's box by {@link #SPREAD},
 * {@code NativeImageSdfMixin} uploads the field in place of coverage, {@code TrueTypeGlyphBitmapMixin} tags
 * the glyph bitmap, and {@code FontTextureSdfMixin} keeps distance-field glyphs on their own atlas pages,
 * marked by texel (0,0), which is how the shader tells the two kinds apart. Bitmap and colour glyphs
 * (Minecraft's own pixel font) are untouched.
 */
public final class SdfFont {
    /** Distance baked on each side of the outline, in atlas texels (1 GUI px at oversample 4).
     *  MUST equal {@code SDF_SPREAD} in {@code assets/minecraft/shaders/core/text.fsh}. */
    public static final int SPREAD = 4;

    /** Carried by TrueType glyph bitmaps (added by {@code TrueTypeGlyphBitmapMixin}). */
    public interface DistanceFieldBitmap {}

    /** Info for the 1x1 page marker; it is never drawn. */
    public static final GlyphInfo MARKER_INFO = GlyphInfo.simple(0.0F);

    /** The flags {@code TrueTypeGlyphProvider.loadGlyph} measures glyph boxes with
     *  (FT_LOAD_NO_BITMAP | FT_LOAD_BITMAP_METRICS_ONLY) -- the box the padded image is laid out from. */
    private static final int VANILLA_METRICS_FLAGS = 4194312;

    private static volatile Boolean supported;
    private static volatile Boolean otherOwner;

    private SdfFont() {}

    /** Reserved hook for standing down if another mod installs this pipeline; currently always false. */
    public static boolean ownedElsewhere() {
        Boolean o = otherOwner;
        if (o == null) {
            FabricLoader fl = FabricLoader.getInstance();
            otherOwner = o = false;
        }
        return o;
    }

    /** Whether our pipeline is active: not owned by another mod, and this FreeType build can bake distance
     *  fields. Decided once, before any glyph is measured, so padding, upload and page marking always agree. */
    public static boolean enabled() {
        if (ownedElsewhere()) return false;
        Boolean s = supported;
        if (s == null) {
            synchronized (FreeTypeUtil.LIBRARY_LOCK) {
                s = supported;
                if (s == null) supported = s = configureSpread();
            }
        }
        return s;
    }

    private static boolean configureSpread() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer spread = stack.malloc(Integer.BYTES);
            spread.putInt(0, SPREAD);
            int error = FreeType.FT_Property_Set(FreeTypeUtil.getLibrary(), "bsdf", "spread", spread);
            if (error != 0) {
                System.out.println("[fish5] FreeType has no bsdf module (error " + error + ") - text stays coverage-rendered");
                return false;
            }
            System.out.println("[fish5] Distance-field text on (bsdf, spread " + SPREAD + " texels)");
            return true;
        } catch (Throwable t) {
            System.out.println("[fish5] Distance-field text unavailable: " + t);
            return false;
        }
    }

    public static boolean isDistanceField(GlyphBitmap glyph) {
        return glyph instanceof DistanceFieldBitmap && enabled();
    }

    /**
     * Bake glyph {@code index} as a distance field into {@code image}, which is sized to the glyph's box padded
     * by {@link #SPREAD} on every side. The field is placed by the glyph origin, not the image corner, so a
     * one-texel disagreement between the measured and rendered boxes only trims far-outside padding.
     */
    public static boolean render(NativeImage image, FT_Face face, int index) {
        synchronized (face) {
            if (FreeTypeUtil.checkError(FreeType.FT_Load_Glyph(face, index, VANILLA_METRICS_FLAGS), "Measuring glyph")) return false;
            FT_GlyphSlot slot = face.glyph();
            if (slot == null) return false;
            int imageLeft = slot.bitmap_left() - SPREAD;
            int imageTop = slot.bitmap_top() + SPREAD;

            if (FreeTypeUtil.checkError(FreeType.FT_Load_Glyph(face, index, FreeType.FT_LOAD_NO_BITMAP), "Loading glyph")) return false;
            slot = face.glyph();
            if (slot == null
                    || FreeTypeUtil.checkError(FreeType.FT_Render_Glyph(slot, FreeType.FT_RENDER_MODE_NORMAL), "Rendering glyph")
                    || FreeTypeUtil.checkError(FreeType.FT_Render_Glyph(slot, FreeType.FT_RENDER_MODE_SDF), "Rendering glyph distance field")) {
                return false;
            }
            FT_Bitmap field = slot.bitmap();
            if (field.pixel_mode() != FreeType.FT_PIXEL_MODE_GRAY) return false;

            int fieldW = field.width(), fieldH = field.rows(), pitch = field.pitch();
            ByteBuffer src = fieldW > 0 && fieldH > 0 ? field.buffer(Math.abs(pitch) * fieldH) : null;
            int offsetX = slot.bitmap_left() - imageLeft;
            int offsetY = imageTop - slot.bitmap_top();
            int width = image.getWidth(), height = image.getHeight();
            long dst = image.getPointer();
            for (int y = 0; y < height; y++) {
                int fy = y - offsetY;
                boolean rowInside = src != null && fy >= 0 && fy < fieldH;
                int rowStart = rowInside ? (pitch >= 0 ? fy * pitch : (fieldH - 1 - fy) * -pitch) : 0;
                for (int x = 0; x < width; x++) {
                    int fx = x - offsetX;
                    byte v = rowInside && fx >= 0 && fx < fieldW ? src.get(rowStart + fx) : 0;
                    MemoryUtil.memPutByte(dst + (long) y * width + x, v);
                }
            }
            return true;
        }
    }

    /** The 1x1 glyph {@code FontTextureSdfMixin} inserts first on every grayscale atlas page -- it always lands
     *  on texel (0,0) -- so the shader can read what the page holds: 255 = distance fields, 0 = coverage. */
    public static GlyphBitmap pageMarker(boolean distanceField) {
        return new GlyphBitmap() {
            @Override public int getPixelWidth() { return 1; }
            @Override public int getPixelHeight() { return 1; }
            @Override public float getOversample() { return 1.0F; }
            @Override public boolean isColored() { return false; }

            @Override
            public void upload(int x, int y, GpuTexture texture) {
                try (NativeImage image = new NativeImage(NativeImage.Format.LUMINANCE, 1, 1, false)) {
                    MemoryUtil.memPutByte(image.getPointer(), distanceField ? (byte) 0xFF : (byte) 0);
                    RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, image, 0, 0, x, y);
                }
            }
        };
    }
}
