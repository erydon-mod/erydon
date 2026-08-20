/*
 * ERYDON-owned CTM-aware POM bridge for the exact supported Complementary
 * Unbound source shape. The shader ZIP is never changed. Unknown sprites,
 * invalid lookup data and unsupported geometry use CU's original fract wrap.
 */

uniform sampler2D erydonCtmPomLookup;

const float ERYDON_CTM_POM_LUT_DIM = 256.0;
const float ERYDON_CTM_POM_HASH_START = 16.0;
const float ERYDON_CTM_POM_HASH_SLOTS = 24571.0;
const float ERYDON_CTM_POM_CENTRE_START = 49158.0;
const float ERYDON_CTM_POM_PHASES_PER_FAMILY = 36.0;
const float ERYDON_CTM_POM_GRID_WIDTH = 6.0;

vec2 erydonCtmPomTexelUv(float linearIndex) {
    float y = floor(linearIndex / ERYDON_CTM_POM_LUT_DIM);
    float x = linearIndex - y * ERYDON_CTM_POM_LUT_DIM;
    return (vec2(x, y) + 0.5) / ERYDON_CTM_POM_LUT_DIM;
}

vec4 erydonCtmPomReadBytes(float linearIndex) {
    return floor(texture2D(
        erydonCtmPomLookup,
        erydonCtmPomTexelUv(linearIndex)
    ) * 255.0 + 0.5);
}

float erydonCtmPomDecodeU16(vec2 bytes) {
    return bytes.x + bytes.y * 256.0;
}

bool erydonCtmPomHeaderValid(vec2 currentAtlasSize) {
    vec4 magic = erydonCtmPomReadBytes(0.0);
    if (abs(magic.x - 69.0) > 0.5 ||
        abs(magic.y - 67.0) > 0.5 ||
        abs(magic.z - 80.0) > 0.5 ||
        abs(magic.w - 3.0) > 0.5) {
        return false;
    }

    vec4 atlasBytes = erydonCtmPomReadBytes(1.0);
    vec2 encodedAtlasSize = vec2(
        erydonCtmPomDecodeU16(atlasBytes.xy),
        erydonCtmPomDecodeU16(atlasBytes.zw)
    );
    return all(lessThan(abs(encodedAtlasSize - currentAtlasSize), vec2(0.5)));
}

float erydonCtmPomRecordCount() {
    vec4 counts = erydonCtmPomReadBytes(2.0);
    return erydonCtmPomDecodeU16(counts.xy);
}

float erydonCtmPomFindRecord(vec2 currentMidUv, vec2 currentAtlasSize) {
    if (!erydonCtmPomHeaderValid(currentAtlasSize)) return -1.0;

    vec2 centrePx = floor(currentMidUv * currentAtlasSize + 0.5);
    float firstSlot = mod(
        centrePx.x * 73.0 + centrePx.y * 151.0,
        ERYDON_CTM_POM_HASH_SLOTS
    );

    for (int probe = 0; probe < 16; ++probe) {
        float slot = mod(firstSlot + float(probe), ERYDON_CTM_POM_HASH_SLOTS);
        float entry = ERYDON_CTM_POM_HASH_START + slot * 2.0;
        vec4 key = erydonCtmPomReadBytes(entry);
        vec4 payload = erydonCtmPomReadBytes(entry + 1.0);
        if (payload.z < 127.5) return -1.0;

        vec2 keyCentre = vec2(
            erydonCtmPomDecodeU16(key.xy),
            erydonCtmPomDecodeU16(key.zw)
        );
        if (all(lessThan(abs(keyCentre - centrePx), vec2(0.5)))) {
            float record = erydonCtmPomDecodeU16(payload.xy);
            return record < erydonCtmPomRecordCount() ? record : -1.0;
        }
    }
    return -1.0;
}

vec2 erydonCtmPomReadCentreUv(float record, vec2 currentAtlasSize) {
    vec4 bytes = erydonCtmPomReadBytes(ERYDON_CTM_POM_CENTRE_START + record);
    vec2 centrePx = vec2(
        erydonCtmPomDecodeU16(bytes.xy),
        erydonCtmPomDecodeU16(bytes.zw)
    );
    return centrePx / currentAtlasSize;
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

/* Convert crossed local U/V tiles to Continuity's repeat-grid X/Y offset. */
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

    vec2 currentAtlasSize = vec2(atlasSize);
    vec2 currentMidUv = vTexCoordAM.st + 0.5 * vTexCoordAM.pq;
    float currentRecord = erydonCtmPomFindRecord(currentMidUv, currentAtlasSize);
    if (currentRecord < 0.0) return ordinaryUv;

    vec2 repeatDelta;
    if (!erydonCtmPomRepeatDelta(floor(unwrappedLocalUv), repeatDelta)) {
        return ordinaryUv;
    }

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

    vec2 targetMidUv = erydonCtmPomReadCentreUv(targetRecord, currentAtlasSize);
    return targetMidUv + (wrapped - 0.5) * vTexCoordAM.pq;
}
