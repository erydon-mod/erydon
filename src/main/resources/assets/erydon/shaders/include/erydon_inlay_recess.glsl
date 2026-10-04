// ERYDON recessed inlay composite. This source is specialized before insertion;
// it contains no preprocessor directives and reuses the existing CTM lookup.

vec2 erydonInlayShadowLocal = vec2(0.0);
float erydonInlayShadowBaseFade = 0.0;
float erydonInlayShadowBaseHeight = 1.0;
float erydonInlayCavityDepth = 0.0;
mat2 erydonInlayRibbonToBase = mat2(1.0);
mat2 erydonInlayRibbonNormal = mat2(1.0);
vec2 erydonInlayRibbonOffset = vec2(0.0);
vec2 erydonInlayRibbonScale = vec2(1.0);

// Perimeter ribbons rotate their albedo, while the stone below remains world-phased.
// Derivatives recover the affine UV transform without another vertex-format field.
void erydonInlayRibbonFrame(vec2 local) {
    if (erydonInlayRibbon != 1) return;
    erydonInlayRibbonOffset = erydonInlayBaseLocal - local;
    mat2 ribbon = mat2(dFdx(local), dFdy(local));
    float determinantValue = ribbon[0].x * ribbon[1].y - ribbon[1].x * ribbon[0].y;
    if (abs(determinantValue) < 0.000000000001) return;
    mat2 inverseRibbon = mat2(ribbon[1].y, -ribbon[0].y, -ribbon[1].x, ribbon[0].x) / determinantValue;
    erydonInlayRibbonToBase = mat2(dFdx(erydonInlayBaseLocal), dFdy(erydonInlayBaseLocal)) * inverseRibbon;
    erydonInlayRibbonOffset = erydonInlayBaseLocal - erydonInlayRibbonToBase * local;
    erydonInlayRibbonScale = max(vec2(length(erydonInlayRibbonToBase[0]), length(erydonInlayRibbonToBase[1])), vec2(0.000001));
    erydonInlayRibbonNormal = mat2(erydonInlayRibbonToBase[0] / erydonInlayRibbonScale.x,
                                  erydonInlayRibbonToBase[1] / erydonInlayRibbonScale.y);
}

vec2 erydonInlayGradient(vec2 gradient, vec2 scale) {
    if (erydonInlayRibbon != 1) return gradient * scale;
    vec2 size = vec2(erydonMetalBounds.zw), atlas = vec2(erydonMetalAtlas);
    return (erydonInlayRibbonToBase * (gradient * atlas / size)) * size * scale / atlas;
}

// A mask cell is a groove opening; empty cells inside the tile are stone walls.
// DDA finds the first wall exactly instead of stepping across one-pixel grooves.
float erydonInlayTraceGroove(vec2 localStart, vec2 rayTexels,
                            out vec2 localHit, out vec3 wallNormal) {
    vec2 size = vec2(erydonMetalBounds.zw);
    vec2 start = localStart * size;
    ivec2 cell = ivec2(floor(start));
    localHit = localStart;
    wallNormal = vec3(0.0);
    if (any(lessThan(cell, ivec2(0))) || any(greaterThanEqual(cell, erydonMetalBounds.zw))
            || (erydonMetalInfo.w >= 0 && erydonMetalAt(cell, erydonMetalBounds.zw, erydonMetalInfo.w, 1) < 0.5)) return 0.0;
    float travel = abs(rayTexels.x) + abs(rayTexels.y);
    rayTexels *= min(1.0, 20.0 / max(travel, 0.000001));
    ivec2 stepCell = ivec2(sign(rayTexels));
    vec2 delta = vec2(1.0) / max(abs(rayTexels), vec2(0.000001));
    vec2 edge = vec2(cell) + vec2(stepCell.x > 0 ? 1.0 : 0.0, stepCell.y > 0 ? 1.0 : 0.0);
    vec2 next = abs(edge - start) * delta;
    if (stepCell.x == 0) next.x = 1000000.0;
    if (stepCell.y == 0) next.y = 1000000.0;
    for (int i = 0; i < 24; i++) {
        float t = min(next.x, next.y);
        if (t >= 1.0) {
            localHit = (start + rayTexels) / size;
            return 1.0;
        }
        bool crossX = next.x <= next.y;
        bool crossY = next.y <= next.x;
        if (crossX) { cell.x += stepCell.x; next.x += delta.x; }
        if (crossY) { cell.y += stepCell.y; next.y += delta.y; }
        if (any(lessThan(cell, ivec2(0))) || any(greaterThanEqual(cell, erydonMetalBounds.zw))) {
            // The adjacent connected tile can have a different 47-tile mask.
            // Keep this known opening flat for this ray instead of inventing
            // an end wall or sampling an unrelated atlas sprite.
            localHit = localStart;
            return 1.0;
        }
        if (erydonMetalInfo.w >= 0 && erydonMetalAt(cell, erydonMetalBounds.zw, erydonMetalInfo.w, 1) < 0.5) {
            localHit = (start + rayTexels * t) / size;
            wallNormal = crossX ? vec3(-float(stepCell.x), 0.0, 0.0)
                               : vec3(0.0, -float(stepCell.y), 0.0);
            return 0.0;
        }
    }
    // The 20-cell L1 bound is below the loop budget; remain opaque on bad inputs.
    wallNormal = vec3(0.0, 0.0, 1.0);
    return 0.0;
}

vec4 erydonInlaySubstrateBounds(vec2 local) {
    vec4 bounds = erydonInlayBaseBounds;
    vec2 tiles = floor(local);
    if (all(equal(tiles, vec2(0.0)))) return bounds;
    vec2 repeatDelta;
    // Ribbons already use the canonical world U/V basis above. Their rotated
    // tangent cannot pass the ordinary axis-aligned repeat-delta guard.
    if (erydonInlayRibbon == 1) repeatDelta = tiles;
    else if (!erydonCtmPomRepeatDelta(tiles, repeatDelta)) return bounds;
    float record = float(erydonInlaySubstrateRecord);
    float family = floor(record / 36.0) * 36.0;
    float phase = record - family;
    vec2 target = mod(vec2(mod(phase, 6.0), floor(phase / 6.0)) + repeatDelta, 6.0);
    float targetRecord = family + target.y * 6.0 + target.x;
    if (targetRecord < 0.0 || targetRecord >= erydonCtmPomRecordCount()) return bounds;
    return erydonCtmPomReadBoundsPx(targetRecord);
}

vec2 erydonInlaySubstrateUv(vec2 local, out vec2 gradientScale) {
    if (erydonInlayRibbon == 1) local = erydonInlayRibbonToBase * local + erydonInlayRibbonOffset;
    vec4 bounds = erydonInlaySubstrateBounds(local);
    // Explicit gradients retain the right mip, while clamped centres prevent
    // the displaced ray from reading an unrelated neighbouring atlas sprite.
    vec2 point = clamp(fract(local) * bounds.zw, vec2(0.5), bounds.zw - vec2(0.5));
    gradientScale = bounds.zw / vec2(erydonMetalBounds.zw);
    return (bounds.xy + point) / vec2(erydonMetalAtlas);
}

float erydonInlaySubstrateHeight(vec2 local) {
    vec2 scale;
    vec2 uv = erydonInlaySubstrateUv(local, scale);
    return textureGrad(normals, uv, erydonInlayGradient(dcdx, scale), erydonInlayGradient(dcdy, scale)).a;
}

void erydonInlayTrace(inout vec2 sampledUv, inout vec4 color, float distanceToSurface) {
    erydonInlayCavityDepth = 0.0;
    vec2 local = (texCoord * vec2(erydonMetalAtlas) - vec2(erydonMetalBounds.xy))
                  / vec2(erydonMetalBounds.zw);
    erydonInlayRibbonFrame(local);
    float fade = clamp(1.0 - distanceToSurface * distanceToSurface
            / (ERYDON_INLAY_POM_DISTANCE * ERYDON_INLAY_POM_DISTANCE), 0.0, 1.0);
    vec2 viewRay = viewVector.xy / max(-viewVector.z, 0.05);
    if (viewVector.z >= 0.0) viewRay = vec2(0.0);
    viewRay /= erydonInlayRibbonScale;
    // Reproduce the substrate's own relief before descending into its groove.
    // Thirty-two coarse steps plus five refinements bound work independently
    // of CU's global POM quality. Flat substrate exits on its first sample.
    float lo = 0.0, hi = 0.0;
    float height = erydonInlaySubstrateHeight(local);
    // CU also fades relief near the height-field ceiling. Match that factor
    // so the same substrate is not displaced differently on adjacent quads.
    float baseFade = max(0.0, fade - pow(height, 64.0));
    erydonInlayShadowBaseFade = baseFade;
    vec2 baseRay = viewRay * (0.25 * ERYDON_INLAY_POM_DEPTH * baseFade);
    if (height < 0.999 && baseFade > 0.0) {
        for (int i = 1; i <= 32; i++) {
            hi = float(i) / 32.0;
            if (1.0 - hi <= erydonInlaySubstrateHeight(local + baseRay * hi)) break;
            lo = hi;
        }
        for (int i = 0; i < 5; i++) {
            float mid = (lo + hi) * 0.5;
            if (1.0 - mid <= erydonInlaySubstrateHeight(local + baseRay * mid)) hi = mid;
            else lo = mid;
        }
        local += baseRay * hi;
    }
    vec2 maskDx = dcdx * vec2(erydonMetalAtlas);
    vec2 maskDy = dcdy * vec2(erydonMetalAtlas);
    float footprint = max(length(maskDx), length(maskDy));
    vec2 hit = local;
    vec3 wall = vec3(0.0);
    float coverage;
    if (footprint >= 1.5) {
        vec2 overlayUv = (vec2(erydonMetalBounds.xy) + local * vec2(erydonMetalBounds.zw)) / vec2(erydonMetalAtlas);
        coverage = erydonMetalMask(overlayUv, erydonMetalBounds, erydonMetalInfo, maskDx, maskDy);
    } else {
        // A quarter of a 64x texel at depth=1; half a texel at depth=2.
        float depth = (0.25 / 64.0) * ERYDON_INLAY_POM_DEPTH * fade;
        depth *= 1.0 - smoothstep(0.75, 1.5, footprint);
        erydonInlayCavityDepth = depth;
        coverage = erydonInlayTraceGroove(local, viewRay * depth * vec2(erydonMetalBounds.zw), hit, wall);
    }
    vec2 scale;
    vec2 baseUv = erydonInlaySubstrateUv(hit, scale);
    vec2 baseDx = erydonInlayGradient(dcdx, scale), baseDy = erydonInlayGradient(dcdy, scale);
    vec4 baseColor = textureGrad(tex, baseUv, baseDx, baseDy);
    erydonInlayNormalSample = textureGrad(normals, baseUv, baseDx, baseDy);
    if (erydonInlayRibbon == 1) {
        // Normal RGB belongs to the stone's UV frame, not the rotated ribbon.
        vec2 baseNormal = erydonInlayNormalSample.rg * 2.0 - 1.0;
        erydonInlayNormalSample.rg = vec2(dot(erydonInlayRibbonNormal[0], baseNormal),
                dot(erydonInlayRibbonNormal[1], baseNormal)) * 0.5 + 0.5;
    }
    erydonInlayShadowLocal = hit;
    erydonInlayShadowBaseHeight = erydonInlayNormalSample.a;
    erydonInlaySpecularSample = textureGrad(specular, baseUv, baseDx, baseDy);
    sampledUv = baseUv;
    if (coverage > 0.0) {
        vec2 overlayPixel = clamp(hit * vec2(erydonMetalBounds.zw), vec2(0.5), vec2(erydonMetalBounds.zw) - vec2(0.5));
        vec2 overlayUv = (vec2(erydonMetalBounds.xy) + overlayPixel) / vec2(erydonMetalAtlas);
        vec4 metalColor = textureGrad(tex, overlayUv, dcdx, dcdy);
        if (footprint >= 0.75) metalColor.rgb = erydonMetalAlbedoMean;
        baseColor.rgb = mix(baseColor.rgb, metalColor.rgb, coverage);
        if (coverage > 0.999) {
            sampledUv = overlayUv;
            erydonInlayNormalSample = vec4(0.5, 0.5, 1.0, 1.0);
            erydonInlaySpecularSample = textureGrad(specular, overlayUv, dcdx, dcdy);
        }
    }
    color = vec4(baseColor.rgb * glColor.rgb, 1.0);
    erydonInlayMetalCoverage = coverage;
    erydonInlayWallNormal = wall;
}

float erydonInlayShadow(vec3 lightTangent, float dither) {
    float shadow = 1.0;
    if (erydonInlayShadowBaseFade > 0.0) {
        // The same four taps, height response and distance/ceiling fade as
        // CU's native GetParallaxShadow, using the substrate's CTM phase.
        vec3 direction = lightTangent;
        direction.xy *= ERYDON_INLAY_POM_DEPTH;
        for (int i = 0; i < 4 && shadow >= 0.01; i++) {
            float stepLC = 0.025 * (float(i) + dither);
            float currentHeight = erydonInlayShadowBaseHeight + direction.z * stepLC;
            float offsetHeight = erydonInlaySubstrateHeight(erydonInlayShadowLocal + direction.xy / erydonInlayRibbonScale * stepLC);
            shadow *= clamp(1.0 - (offsetHeight - currentHeight) * 4.0, 0.0, 1.0);
        }
        shadow = mix(1.0, shadow, erydonInlayShadowBaseFade);
    }
    if (erydonInlayMetalCoverage > 0.999 && erydonInlayCavityDepth > 0.0) {
        // Only direct light travelling through the groove opening reaches
        // its metal floor. Unknown adjacent CTM masks retain the local fallback.
        if (lightTangent.z <= 0.0) return 0.0;
        vec2 opening;
        vec3 wall;
        vec2 ray = lightTangent.xy / erydonInlayRibbonScale / max(lightTangent.z, 0.05)
                * erydonInlayCavityDepth * vec2(erydonMetalBounds.zw);
        shadow *= erydonInlayTraceGroove(erydonInlayShadowLocal, ray, opening, wall);
    }
    return shadow;
}
