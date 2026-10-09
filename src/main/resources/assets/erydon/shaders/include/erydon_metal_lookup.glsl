// Shared by the vertex and fragment stages; injected after preprocessing.
uniform sampler2D erydonMetalLookup;

ivec4 erydonMetalBytes(int address) {
    return ivec4(floor(texelFetch(erydonMetalLookup, ivec2(address % 1024, address / 1024), 0) * 255.0 + 0.5));
}

int erydonMetalU16(ivec2 value) { return value.x + value.y * 256; }
int erydonMetalU32(ivec4 value) { return value.x + value.y * 256 + value.z * 65536 + value.w * 16777216; }

// A record is exact pixel bounds plus (kind, alloy, roughness byte, mask byte offset).
// A negative mask offset represents a completely covered, uniform metal sprite.
// Kind 4 is an exact Gloss inset marker with zero metal coverage and no mask.
bool erydonReadMetal(vec2 uv, ivec2 atlasSize, out ivec4 bounds, out ivec4 info, out vec3 authoredAlbedo) {
    bounds = ivec4(0);
    info = ivec4(0);
    authoredAlbedo = vec3(0.0);
    ivec2 pixel = ivec2(floor(uv * vec2(atlasSize)));
    if (any(lessThan(pixel, ivec2(0))) || any(greaterThanEqual(pixel, atlasSize))) return false;
    int columns = (atlasSize.x + 15) / 16;
    int record = erydonMetalU32(erydonMetalBytes(4 + (pixel.y / 16) * columns + pixel.x / 16));
    if (record == 0) return false;
    ivec4 position = erydonMetalBytes(record);
    ivec4 size = erydonMetalBytes(record + 1);
    bounds = ivec4(erydonMetalU16(position.xy), erydonMetalU16(position.zw),
                   erydonMetalU16(size.xy), erydonMetalU16(size.zw));
    if (any(lessThan(pixel, bounds.xy)) || any(greaterThanEqual(pixel, bounds.xy + bounds.zw))) return false;
    ivec4 flags = erydonMetalBytes(record + 2);
    if (flags.x == 4) {
        if (flags.y != 0 || flags.z != 0 || flags.w != 0) {
            bounds = ivec4(0);
            return false;
        }
        info = ivec4(4, 0, 0, 0);
        return true;
    }
    // The deferred buffers have explicit bronze/silver tags only. Unknown
    // alloys must not get a silver reflection after an authored direct lobe.
    if (flags.x < 1 || flags.x > 3 || flags.y < 1 || flags.y > 2) {
        bounds = ivec4(0);
        return false;
    }
    info = ivec4(flags.xyz, (flags.w % 2) == 1 ? -1 : erydonMetalU32(erydonMetalBytes(record + 3)));
    authoredAlbedo = vec3(erydonMetalBytes(record + 4).xyz) / 255.0;
    return info.x > 0;
}

bool erydonReadMetal(vec2 uv, ivec2 atlasSize, out ivec4 bounds, out ivec4 info) {
    vec3 authoredAlbedo;
    return erydonReadMetal(uv, atlasSize, bounds, info, authoredAlbedo);
}

float erydonMetalByte(int address) {
    ivec4 values = erydonMetalBytes(address / 4);
    int channel = address % 4;
    return float(channel == 0 ? values.x : channel == 1 ? values.y : channel == 2 ? values.z : values.w) / 255.0;
}
