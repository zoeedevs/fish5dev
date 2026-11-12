#version 330

// Fish 5 override of vanilla core/text.fsh (26.2). Only the IS_GRAYSCALE path differs.
//
// Grayscale font pages (TTF glyphs, and bitmap glyphs once FontTextureMixin samples them LINEAR) hold
// glyph COVERAGE in .r. Vanilla reads it as .rrrr - colour AND alpha scaled by coverage - and the
// TRANSLUCENT blend scales by alpha again, so an edge pixel gets coverage^2 of the text colour: a dark
// rim round every glyph (hollow-looking letters), and under fake bold the two copies' rims open a gap
// between them (every bold character drawn twice). Here coverage is alpha only.
//
// Coverage is also averaged over the pixel's footprint (4 bilinear taps, each at most half a texel out,
// so they reach the atlas' 1-texel packing gap but never a neighbouring glyph). The atlas is baked 4x
// oversampled; drawn small (GUI labels at 0.7x, far nametags) plain bilinear skips texels and aliases.
// Magnified, the taps coincide and this is plain bilinear.
//
// TrueType glyphs are instead baked as signed distance fields on their own pages (SdfFont /
// FontTextureMixin; texel (0,0) of every grayscale page says which kind it is). A distance field keeps
// a one-screen-pixel edge at any magnification - titles at 4x no longer smear across ~4 pixels.

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#moj_import <minecraft:fog.glsl>
#endif

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
in float sphericalVertexDistance;
in float cylindricalVertexDistance;
#endif

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

#ifdef IS_GRAYSCALE
// Distance baked each side of the outline, in atlas texels. MUST equal SdfFont.SPREAD.
#define SDF_SPREAD 4.0
// FreeType writes the outline itself as 128 (inside > 128).
#define SDF_EDGE (128.0 / 255.0)

float glyphCoverage(vec2 uv, vec2 dUVdx, vec2 dUVdy) {
    vec2 halfTexel = 0.5 / vec2(textureSize(Sampler0, 0));
    vec2 dx = clamp(dUVdx * 0.25, -halfTexel, halfTexel);
    vec2 dy = clamp(dUVdy * 0.25, -halfTexel, halfTexel);
    return 0.25 * (texture(Sampler0, uv - dx - dy).r + texture(Sampler0, uv + dx - dy).r
                 + texture(Sampler0, uv - dx + dy).r + texture(Sampler0, uv + dx + dy).r);
}

float distanceAlphaAt(vec2 uv, float pxRange) {
    return clamp(pxRange * (texture(Sampler0, uv).r - SDF_EDGE) + 0.5, 0.0, 1.0);
}

float glyphDistanceAlpha(vec2 uv, vec2 dUVdx, vec2 dUVdy) {
    // Screen pixels spanned by the field's full 2*SPREAD range; scales the distance to a 1px-wide edge.
    vec2 unitRange = vec2(2.0 * SDF_SPREAD) / vec2(textureSize(Sampler0, 0));
    vec2 screenTexSize = vec2(1.0) / (abs(dUVdx) + abs(dUVdy));
    float screenPxRange = max(0.5 * dot(unitRange, screenTexSize), 1.0);
    // 4x rotated-grid supersample of the pixel's footprint. Each sub-sample resolves a half-pixel edge
    // (2x the range) and the four are box-filtered, so small text (HUD labels) gets a true 1px
    // antialiased edge with thin stems kept instead of one sample deciding the whole pixel.
    // Magnified, the taps sit inside one texel and this matches the single-sample result.
    float r = 2.0 * screenPxRange;
    vec2 a = dUVdx * 0.125 + dUVdy * 0.375;
    vec2 b = dUVdx * 0.375 - dUVdy * 0.125;
    return 0.25 * (distanceAlphaAt(uv + a, r) + distanceAlphaAt(uv - a, r)
                 + distanceAlphaAt(uv + b, r) + distanceAlphaAt(uv - b, r));
}
#endif

// The world pass writes depth, so a near-invisible fringe must still be cut there (vanilla's 10%);
// the GUI and see-through passes write no depth and keep the whole antialiased edge.
#if defined(IS_GRAYSCALE) && (defined(IS_GUI) || defined(IS_SEE_THROUGH))
#define ALPHA_CUTOFF 0.004
#else
#define ALPHA_CUTOFF 0.1
#endif

void main() {
#ifdef IS_GRAYSCALE
    vec2 dUVdx = dFdx(texCoord0);
    vec2 dUVdy = dFdy(texCoord0);
    bool distanceField = texelFetch(Sampler0, ivec2(0, 0), 0).r > 0.5;
    float glyphAlpha = distanceField ? glyphDistanceAlpha(texCoord0, dUVdx, dUVdy)
                                     : glyphCoverage(texCoord0, dUVdx, dUVdy);
    vec4 texColor = vec4(1.0, 1.0, 1.0, glyphAlpha);
#else
    vec4 texColor = texture(Sampler0, texCoord0);
#endif

#ifdef IS_SEE_THROUGH
    vec4 color = texColor * vertexColor;
#else
    vec4 color = texColor * vertexColor * ColorModulator;
#endif
    if (color.a < ALPHA_CUTOFF) {
        discard;
    }

#ifdef IS_SEE_THROUGH
    fragColor = color * ColorModulator;
#elif defined(IS_GUI)
    fragColor = color;
#else
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
#endif
}
