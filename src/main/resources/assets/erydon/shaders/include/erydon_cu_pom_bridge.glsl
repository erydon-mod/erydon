/*
 * ERYDON-owned CTM-aware POM bridge for the exact supported Complementary
 * Unbound source shape. The shader ZIP is never changed. The vertex stage
 * corrects POM bounds only for ERYDON's reserved large-spiral material id.
 * Unknown sprites and invalid lookup data leave ordinary albedo intact.
 */

uniform sampler2D erydonCtmPomLookup;

const vec2 ERYDON_CTM_POM_LUT_SIZE = vec2(1024.0, 1057.0);
const float ERYDON_CTM_POM_OCCUPANCY_START = 1024.0;
const float ERYDON_CTM_POM_OCCUPANCY_WIDTH = 1024.0;
const float ERYDON_CTM_POM_OCCUPANCY_HEIGHT = 1024.0;
const float ERYDON_CTM_POM_RECORD_START = 1049600.0;
const float ERYDON_CTM_POM_RECORD_TEXELS = 2.0;
const float ERYDON_CTM_POM_ATLAS_QUANTUM = 16.0;
const float ERYDON_CTM_POM_PHASES_PER_FAMILY = 36.0;
const float ERYDON_CTM_POM_GRID_WIDTH = 6.0;

vec2 erydonCtmPomTexelUv(float linearIndex) {
    float y = floor(linearIndex / ERYDON_CTM_POM_LUT_SIZE.x);
    float x = linearIndex - y * ERYDON_CTM_POM_LUT_SIZE.x;
    return (vec2(x, y) + 0.5) / ERYDON_CTM_POM_LUT_SIZE;
}

vec4 erydonCtmPomReadBytes(float linearIndex) {
#ifdef ERYDON_CTM_POM_VERTEX_STAGE
    vec4 sampleValue = texture2DLod(
        erydonCtmPomLookup,
        erydonCtmPomTexelUv(linearIndex),
        0.0
    );
#else
    vec4 sampleValue = texture2D(
        erydonCtmPomLookup,
        erydonCtmPomTexelUv(linearIndex)
    );
#endif
    return floor(sampleValue * 255.0 + 0.5);
}

float erydonCtmPomDecodeU16(vec2 bytes) {
    return bytes.x + bytes.y * 256.0;
}

bool erydonCtmPomHeaderValid(vec2 currentAtlasSize) {
    vec4 magic = erydonCtmPomReadBytes(0.0);
    if (abs(magic.x - 69.0) > 0.5 ||
        abs(magic.y - 67.0) > 0.5 ||
        abs(magic.z - 80.0) > 0.5 ||
        abs(magic.w - 4.0) > 0.5) {
        return false;
    }

    vec4 atlasBytes = erydonCtmPomReadBytes(1.0);
    vec2 encodedAtlasSize = vec2(
        erydonCtmPomDecodeU16(atlasBytes.xy),
        erydonCtmPomDecodeU16(atlasBytes.zw)
    );
    vec4 lookupBytes = erydonCtmPomReadBytes(3.0);
    vec2 encodedLookupSize = vec2(
        erydonCtmPomDecodeU16(lookupBytes.xy),
        erydonCtmPomDecodeU16(lookupBytes.zw)
    );
    vec4 layoutBytes = erydonCtmPomReadBytes(4.0);
    return all(lessThan(abs(encodedAtlasSize - currentAtlasSize), vec2(0.5))) &&
        all(lessThan(abs(encodedLookupSize - ERYDON_CTM_POM_LUT_SIZE), vec2(0.5))) &&
        abs(erydonCtmPomDecodeU16(layoutBytes.xy) - ERYDON_CTM_POM_ATLAS_QUANTUM) < 0.5 &&
        abs(erydonCtmPomDecodeU16(layoutBytes.zw) - ERYDON_CTM_POM_PHASES_PER_FAMILY) < 0.5;
}

float erydonCtmPomRecordCount() {
    vec4 counts = erydonCtmPomReadBytes(2.0);
    return erydonCtmPomDecodeU16(counts.xy);
}

vec4 erydonCtmPomReadBoundsPx(float record) {
    float start = ERYDON_CTM_POM_RECORD_START + record * ERYDON_CTM_POM_RECORD_TEXELS;
    vec4 minimumBytes = erydonCtmPomReadBytes(start);
    vec4 sizeBytes = erydonCtmPomReadBytes(start + 1.0);
    return vec4(
        erydonCtmPomDecodeU16(minimumBytes.xy),
        erydonCtmPomDecodeU16(minimumBytes.zw),
        erydonCtmPomDecodeU16(sizeBytes.xy),
        erydonCtmPomDecodeU16(sizeBytes.zw)
    );
}

vec4 erydonCtmPomReadBoundsUv(float record, vec2 currentAtlasSize) {
    vec4 boundsPx = erydonCtmPomReadBoundsPx(record);
    return vec4(boundsPx.xy / currentAtlasSize, boundsPx.zw / currentAtlasSize);
}

int erydonCtmPomFindRecord(vec2 interiorUv, vec2 currentAtlasSize) {
    if (!erydonCtmPomHeaderValid(currentAtlasSize)) return -1;

    vec2 atlasPx = floor(interiorUv * currentAtlasSize);
    if (any(lessThan(atlasPx, vec2(0.0))) ||
        any(greaterThanEqual(atlasPx, currentAtlasSize))) {
        return -1;
    }
    vec2 cell = floor(atlasPx / ERYDON_CTM_POM_ATLAS_QUANTUM);
    if (any(lessThan(cell, vec2(0.0))) ||
        cell.x >= ERYDON_CTM_POM_OCCUPANCY_WIDTH ||
        cell.y >= ERYDON_CTM_POM_OCCUPANCY_HEIGHT) {
        return -1;
    }

    float occupancy = ERYDON_CTM_POM_OCCUPANCY_START +
        cell.y * ERYDON_CTM_POM_OCCUPANCY_WIDTH + cell.x;
    vec4 entry = erydonCtmPomReadBytes(occupancy);
    float encodedRecord = erydonCtmPomDecodeU16(entry.xy);
    float record = encodedRecord - 1.0;
    if (encodedRecord < 0.5 || record >= erydonCtmPomRecordCount()) return -1;

    vec4 boundsPx = erydonCtmPomReadBoundsPx(record);
    if (any(lessThan(atlasPx, boundsPx.xy)) ||
        any(greaterThanEqual(atlasPx, boundsPx.xy + boundsPx.zw))) {
        return -1;
    }
    return int(floor(record + 0.5));
}

#ifdef ERYDON_CTM_POM_VERTEX_STAGE
void erydonCtmPomApplyExactBounds(
    vec2 atlasUv,
    vec2 currentAtlasSize,
    int record,
    inout vec4 spriteBounds,
    inout vec2 localSign,
    inout vec2 spriteRadius,
    inout vec2 spriteMidpoint
) {
    vec4 exactBounds = erydonCtmPomReadBoundsUv(float(record), currentAtlasSize);
    vec2 localUv = (atlasUv - exactBounds.xy) / exactBounds.zw;
    spriteBounds = exactBounds;
    localSign = localUv * 2.0 - 1.0;
    spriteRadius = exactBounds.zw * 0.5;
    spriteMidpoint = exactBounds.xy + spriteRadius;
}
#endif

#ifdef ERYDON_CTM_POM_FRAGMENT_STAGE
/*
 * Ordinary repeat-CTM blocks retain the established phase-aware POM path,
 * but resolve their record only if a POM ray actually crosses a tile edge.
 * The large spiral already supplies its record from the vertex stage.
 */
int erydonPomFragmentRecord = -2;

int erydonCtmPomResolveFragmentRecord() {
    if (erydonPomRecord >= 0) return erydonPomRecord;
    if (erydonPomFragmentRecord == -2) {
        vec2 currentMidpoint = vTexCoordAM.st + 0.5 * vTexCoordAM.pq;
        erydonPomFragmentRecord = erydonCtmPomFindRecord(
            currentMidpoint,
            vec2(atlasSize)
        );
    }
    return erydonPomFragmentRecord;
}

bool erydonCtmPomDominantAxis(vec3 value, out vec3 axis) {
    vec3 magnitude = abs(value);
    float greatest = max(magnitude.x, max(magnitude.y, magnitude.z));
    if (greatest < 0.5) {
        axis = vec3(0.0);
        return false;
    }
    if (magnitude.x >= magnitude.y && magnitude.x >= magnitude.z) {
        axis = vec3(sign(value.x), 0.0, 0.0);
    } else if (magnitude.y >= magnitude.z) {
        axis = vec3(0.0, sign(value.y), 0.0);
    } else {
        axis = vec3(0.0, 0.0, sign(value.z));
    }
    return true;
}

/* Convert crossed local U/V tiles to Synapheia's repeat-grid X/Y offset. */
bool erydonCtmPomRepeatDelta(vec2 localTiles, out vec2 repeatTiles) {
    mat3 viewToWorld = mat3(gbufferModelViewInverse);
    vec3 worldTangent = normalize(viewToWorld * tangent);
    vec3 worldBinormal = normalize(viewToWorld * binormal);
    vec3 worldNormal = normalize(viewToWorld * normal);

    vec3 tangentAxis;
    vec3 binormalAxis;
    vec3 normalAxis;
    if (!erydonCtmPomDominantAxis(worldTangent, tangentAxis) ||
        !erydonCtmPomDominantAxis(worldBinormal, binormalAxis) ||
        !erydonCtmPomDominantAxis(worldNormal, normalAxis)) {
        repeatTiles = vec2(0.0);
        return false;
    }
    if (abs(dot(tangentAxis, binormalAxis)) > 0.25 ||
        abs(dot(tangentAxis, normalAxis)) > 0.25 ||
        abs(dot(binormalAxis, normalAxis)) > 0.25) {
        repeatTiles = vec2(0.0);
        return false;
    }

    vec3 worldDelta = tangentAxis * localTiles.x + binormalAxis * localTiles.y;
    if (normalAxis.y < -0.5) {
        repeatTiles = vec2(worldDelta.x, -worldDelta.z);
    } else if (normalAxis.y > 0.5) {
        repeatTiles = vec2(worldDelta.x, worldDelta.z);
    } else if (normalAxis.z < -0.5) {
        repeatTiles = vec2(-worldDelta.x, -worldDelta.y);
    } else if (normalAxis.z > 0.5) {
        repeatTiles = vec2(worldDelta.x, -worldDelta.y);
    } else if (normalAxis.x < -0.5) {
        repeatTiles = vec2(worldDelta.z, -worldDelta.y);
    } else {
        repeatTiles = vec2(-worldDelta.z, -worldDelta.y);
    }
    return true;
}

vec2 erydonCtmPomAtlasUv(vec2 unwrappedLocalUv) {
    vec2 wrapped = fract(unwrappedLocalUv);
    vec2 ordinaryUv = vTexCoordAM.st + wrapped * vTexCoordAM.pq;
    bool crossed =
        unwrappedLocalUv.x < 0.0 || unwrappedLocalUv.x >= 1.0 ||
        unwrappedLocalUv.y < 0.0 || unwrappedLocalUv.y >= 1.0;
    if (!crossed) {
        return vTexCoordAM.st + unwrappedLocalUv * vTexCoordAM.pq;
    }
    int resolvedRecord = erydonCtmPomResolveFragmentRecord();
    if (resolvedRecord < 0) return ordinaryUv;

    vec2 repeatDelta;
    if (!erydonCtmPomRepeatDelta(floor(unwrappedLocalUv), repeatDelta)) {
        return ordinaryUv;
    }

    float currentRecord = float(resolvedRecord);
    float familyBase =
        floor(currentRecord / ERYDON_CTM_POM_PHASES_PER_FAMILY) *
        ERYDON_CTM_POM_PHASES_PER_FAMILY;
    float phase = currentRecord - familyBase;
    vec2 phaseXY = vec2(
        mod(phase, ERYDON_CTM_POM_GRID_WIDTH),
        floor(phase / ERYDON_CTM_POM_GRID_WIDTH)
    );
    vec2 targetPhase = mod(phaseXY + repeatDelta, ERYDON_CTM_POM_GRID_WIDTH);
    float targetRecord =
        familyBase + targetPhase.y * ERYDON_CTM_POM_GRID_WIDTH + targetPhase.x;
    if (targetRecord >= erydonCtmPomRecordCount()) return ordinaryUv;

    vec4 targetBounds = erydonCtmPomReadBoundsUv(targetRecord, vec2(atlasSize));
    vec2 targetMidpoint = targetBounds.xy + 0.5 * targetBounds.zw;
    return targetMidpoint + (wrapped - 0.5) * vTexCoordAM.pq;
}
#endif
