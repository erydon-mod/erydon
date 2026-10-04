// The signed mid-block bytes identify the owning integer cell, not its UVs.
// Keep full position precision so shallow safety edges and block boundaries
// share the same world-phased stone mapping as SynapheiaCellGeometry.
int erydonInlayDecodeRecord(float renderType, out int ribbon) {
    int record = int(floor(-renderType - 2.0 + 0.5));
    ribbon = record >= 16384 ? 1 : 0;
    return record - ribbon * 16384;
}

vec2 erydonInlayProjectBase(vec3 world, vec3 midBlock, vec3 normal) {
    vec3 local = world - floor(world + midBlock / 64.0);
    bool xFace = abs(normal.x) > abs(normal.z);
    float s = xFace ? local.z : local.x;
    bool reverse = xFace ? normal.x > 0.0 : normal.z < 0.0;
    return vec2(reverse ? 1.0 - s : s, 1.0 - local.y);
}
