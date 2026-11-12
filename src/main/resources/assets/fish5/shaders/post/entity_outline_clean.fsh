#version 330

// Clean entity outline: instead of vanilla's sobel + box blur halo, supersample the silhouette mask
// on an N x N sub-pixel grid and turn the local coverage into a thin, anti-aliased line centred on
// the silhouette edge (coverage 0.5 -> full intensity, 0 or 1 -> nothing).

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

in vec2 texCoord;

out vec4 fragColor;

const int N = 6;        // sub-samples per axis (N*N taps per pixel)
const float R = 1.5;    // half-size of the sampling window, in texels (~line width)

void main() {
    vec2 oneTexel = 1.0 / InSize;

    float sumA = 0.0;
    vec3 sumRgb = vec3(0.0);
    for (int y = 0; y < N; y++) {
        for (int x = 0; x < N; x++) {
            vec2 o = ((vec2(float(x), float(y)) + 0.5) / float(N) * 2.0 - 1.0) * R;
            vec4 s = texture(InSampler, texCoord + o * oneTexel);
            sumA += s.a;
            sumRgb += s.rgb * s.a;
        }
    }

    float coverage = sumA / float(N * N);
    float line = clamp(4.0 * coverage * (1.0 - coverage), 0.0, 1.0);
    vec3 rgb = sumA > 0.0 ? sumRgb / sumA : vec3(0.0);
    fragColor = vec4(rgb, line);
}
