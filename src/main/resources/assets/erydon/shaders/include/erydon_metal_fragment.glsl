flat in ivec4 erydonMetalBounds;
flat in ivec4 erydonMetalInfo;
flat in ivec2 erydonMetalAtlas;
flat in vec3 erydonMetalAlbedoMean;

float erydonMetalCoverage = 0.0;
vec3 erydonMetalF0 = vec3(0.0);
vec3 erydonMetalDiffuseComponent = vec3(0.0);
float erydonMetalRoughness = 0.22;
float erydonMetalPacked = 1.0;
float erydonMetalTag = 0.0;

// CU carries gamma-encoded working RGB until composite1. Remove only the
// metal's diffuse contribution; conductor absorption is not a painted base.
// CU has incomplete reflected illumination, especially from block lights.
// Keep a lighting-dependent broad contribution even on polished metal.
// Match this share in both opaque and translucent reflection composition.
vec3 erydonMetalBroadLighting(vec3 litColor, vec3 metalComponent, vec3 light, float roughness) {
    vec3 metalLit = min(max(metalComponent * light, vec3(0.0)), max(litColor, vec3(0.0)));
    float broadWeight = clamp(max(roughness * roughness, 0.30), 0.0, 1.0);
    return litColor - metalLit + metalLit * pow(broadWeight, 1.0 / 2.2);
}

vec3 erydonMetalNormal(vec3 nativeNormal, vec2 bevel, int kind) {
    // Authored and POM-generated normals belong to the actual surface. In
    // particular, never flatten an embedded groove's vertical wall or sculpted metal.
    if (kind != 1) return nativeNormal;
    vec2 slope = clamp(nativeNormal.xy * 0.15, vec2(-0.18), vec2(0.18)) + bevel;
    return normalize(vec3(slope, sqrt(max(0.0, 1.0 - dot(slope, slope)))));
}

float erydonMetalAt(ivec2 pixel, ivec2 size, int offset, int kind) {
    if (kind == 1 && (any(lessThan(pixel, ivec2(0))) || any(greaterThanEqual(pixel, size)))) return 0.0;
    pixel = clamp(pixel, ivec2(0), size - ivec2(1));
    return erydonMetalByte(offset + pixel.y * size.x + pixel.x);
}

float erydonMetalLevel(vec2 local, ivec2 size, int offset, int kind, int level) {
    for (int i = 0; i < 16; i++) {
        if (i >= level || (size.x == 1 && size.y == 1)) break;
        offset += size.x * size.y;
        size = max(size / 2, ivec2(1));
    }
    vec2 position = local * vec2(size) - 0.5;
    ivec2 pixel = ivec2(floor(position));
    vec2 blend = fract(position);
    return mix(mix(erydonMetalAt(pixel, size, offset, kind),
                   erydonMetalAt(pixel + ivec2(1, 0), size, offset, kind), blend.x),
               mix(erydonMetalAt(pixel + ivec2(0, 1), size, offset, kind),
                   erydonMetalAt(pixel + ivec2(1, 1), size, offset, kind), blend.x), blend.y);
}

float erydonMetalFiltered(vec2 local, ivec2 size, int offset, int kind, float lod) {
    int level = int(floor(lod));
    float first = erydonMetalLevel(local, size, offset, kind, level);
    if (fract(lod) < 0.01) return first;
    return mix(first, erydonMetalLevel(local, size, offset, kind, level + 1), fract(lod));
}

// The mask has its own linear mip pyramid. Material-category filtering in Iris
// cannot turn a quarter-covered metal pixel into an unrelated dielectric F0.
float erydonMetalMask(vec2 uv, ivec4 bounds, ivec4 info, vec2 dx, vec2 dy) {
    if (info.w < 0) return 1.0;
    vec2 pixel = uv * vec2(erydonMetalAtlas) - vec2(bounds.xy);
    // Eigenvalues of J*transpose(J) give the footprint axes even when a
    // grazing surface runs diagonally across the screen. Derivative lengths
    // alone overestimate the minor axis and erase narrow diagonal details.
    float xx = dx.x * dx.x + dy.x * dy.x;
    float yy = dx.y * dx.y + dy.y * dy.y;
    float xy = dx.x * dx.y + dy.x * dy.y;
    float difference = xx - yy;
    float discriminant = sqrt(max(difference * difference + 4.0 * xy * xy, 0.0));
    float majorSquared = max(0.5 * (xx + yy + discriminant), 0.0);
    float major = sqrt(majorSquared);
    if (major < 0.75) return erydonMetalAt(ivec2(floor(pixel)), bounds.zw, info.w, info.x);
    // determinant/major avoids cancellation in trace-discriminant for thin
    // footprints; the 4:1 clamp bounds work to the four samples below.
    float determinant = dx.x * dy.y - dx.y * dy.x;
    float minor = max(abs(determinant) / max(major, 0.000001), major * 0.25);
    float lod = clamp(log2(max(minor, 1.0)), 0.0, floor(log2(float(max(bounds.z, bounds.w)))));
    vec2 local = pixel / vec2(bounds.zw);
    int sampleCount = int(clamp(ceil(major / max(minor, 1.0)), 1.0, 4.0));
    if (sampleCount == 1) return erydonMetalFiltered(local, bounds.zw, info.w, info.x, lod);
    vec2 eigenvector = xx >= yy ? vec2(majorSquared - yy, xy) : vec2(xy, majorSquared - xx);
    float axisSquared = dot(eigenvector, eigenvector);
    vec2 direction = axisSquared > 0.000000000001
            ? eigenvector * inversesqrt(axisSquared) : vec2(1.0, 0.0);
    vec2 axis = direction * major;
    vec2 stepUv = axis / vec2(bounds.zw);
    // Isotropic footprints use one sample. Up to four preserve grazing detail.
    float result = 0.0;
    for (int i = 0; i < 4; i++) {
        if (i >= sampleCount) break;
        float offset = (float(i) + 0.5) / float(sampleCount) - 0.5;
        result += erydonMetalFiltered(local + offset * stepUv,
                                      bounds.zw, info.w, info.x, lod);
    }
    return clamp(result / float(sampleCount), 0.0, 1.0);
}

// A shallow radius inside the existing texel, never an expanded silhouette.
// Opposite edges of a one-pixel line have separate slopes instead of cancelling.
vec2 erydonMetalBevel(vec2 uv, ivec4 bounds, ivec4 info, float footprint) {
    if (info.w < 0 || info.x == 3 || footprint >= 1.5) return vec2(0.0);
    vec2 pixel = uv * vec2(erydonMetalAtlas) - vec2(bounds.xy);
    ivec2 center = ivec2(floor(pixel));
    if (erydonMetalAt(center, bounds.zw, info.w, info.x) < 0.5) return vec2(0.0);
    vec2 phase = fract(pixel);
    vec2 left = max(vec2(1.0) - phase / 0.32, vec2(0.0));
    vec2 right = max(vec2(1.0) - (vec2(1.0) - phase) / 0.32, vec2(0.0));
    vec2 low = vec2(erydonMetalAt(center - ivec2(1, 0), bounds.zw, info.w, info.x),
                    erydonMetalAt(center - ivec2(0, 1), bounds.zw, info.w, info.x));
    vec2 high = vec2(erydonMetalAt(center + ivec2(1, 0), bounds.zw, info.w, info.x),
                     erydonMetalAt(center + ivec2(0, 1), bounds.zw, info.w, info.x));
    return 0.28 * (right * right * (1.0 - high) - left * left * (1.0 - low))
                 * (1.0 - smoothstep(0.6, 1.5, footprint));
}

vec3 erydonConductorF0(int alloy, vec3 albedo) {
    if (alloy == 1) return vec3(0.92, 0.70, 0.30);
    if (alloy == 2) return vec3(0.95, 0.93, 0.88);
    return clamp(pow(max(albedo, vec3(0.0)), vec3(2.2)), vec3(0.04), vec3(0.98));
}

vec3 erydonMetalAlbedo(vec3 sampled, vec3 authoredMean, vec3 f0, float coverage, vec3 tint) {
    // The sampled albedo already contains the metal's coverage. Replace only
    // its mean contribution, retaining the stone contribution and authored wear.
    // Mixing the whole sample again would count metal coverage twice.
    return clamp(sampled + coverage * (pow(f0, vec3(1.0 / 2.2)) - authoredMean) * tint,
                 vec3(0.0), vec3(1.0));
}

float erydonMetalCutout(vec2 uv, float threshold) {
    if (erydonMetalInfo.x != 1) return -1.0;
    vec2 dx = dFdx(uv) * vec2(erydonMetalAtlas);
    vec2 dy = dFdy(uv) * vec2(erydonMetalAtlas);
    float coverage = erydonMetalMask(uv, erydonMetalBounds, erydonMetalInfo, dx, dy);
    // Stable coverage sampling replaces the hard 50% alpha loss only for metal overlays.
    // The caller supplies CU's Bayer/TAA sequence; no extra noise texture is needed.
    return coverage > threshold ? 1.0 : 0.0;
}
