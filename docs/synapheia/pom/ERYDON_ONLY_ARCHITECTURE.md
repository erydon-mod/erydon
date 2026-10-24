# ERYDON-only CTM-aware POM architecture

## Scope and ownership

This compatibility layer is owned and distributed only by ERYDON. It covers every active 6x6 repeat-CTM tile family used by an `erydon:` block and targets the audited Complementary profiles in `ComplementaryShaderProfiles`. It does not modify, repack, or redistribute Complementary source or assets.

Automatic activation is limited to the reviewed `shaders.properties` SHA-256 values in that profile list. Unknown properties remain untouched. Every terrain-source edit also requires unique source anchors and exact postconditions; any mismatch returns the original source byte-for-byte.

## Data flow

1. At block-atlas upload, ERYDON binds an invalid 1x1 lookup so shader sampling is always safe.
2. After stitching, ERYDON reads the active `optifine/ctm` and `mcpatcher/ctm` rules. It accepts only `method=repeat`, 6x6 rules that target ERYDON blocks, resolves their ordered 36-tile lists, and deduplicates shared families.
3. Every selected family must contain 36 distinct stitched sprites with a common per-family sprite size and one common atlas size. A malformed or missing family leaves the invalid placeholder active.
4. ERYDON encodes the stitched sprite bounds and their atlas occupancy into one runtime-only protocol-v4 1024x1057 RGBA8 lookup: 4,329,472 bytes, nearest filtering, clamped edges, and no PNG. The direct occupancy lookup locates the full sprite even when a model element uses only part of it.
5. Optional Iris mixins add the dedicated `erydonCtmPomLookup` custom sampler and atomically adapt only `gbuffers_terrain` in memory. The directive is `texture.erydonCtmPomLookup=erydon:ctm_pom_lookup`; the shader ZIP stays unchanged.
6. The vertex helper recognizes the current stitched phase from the quad's atlas midpoint and restores its full sprite bounds. Cropped model elements therefore retain their position inside the same heightmap instead of restarting POM at every element edge. The fragment helper reuses that record; when a ray crosses a real tile edge, it maps the crossed local tile to Synapheia's repeat-grid direction for the rendered face, selects the adjacent 6x6 phase, and samples that stitched sprite.
7. Unknown sprites, invalid lookup headers, unsupported geometry, absent Iris, disabled POM, or unsupported shader source use Complementary's original wrap behaviour.

The four adapted POM domains are normal reads, final displaced material coordinates, parallax self-shadow taps, and slope-normal taps. Six cardinal face orientations are handled from the runtime tangent, binormal, and normal axes, so rotated or mirrored model UVs do not require baked texture sheets.

Exact bounds depend on a recognized repeat sprite, not a stone-finish material ID. This includes Romanesque rock, aged and ashlar variants as well as standard stone. Unknown sprites and invalid lookup data retain their original bounds; the separate large-spiral missing-record POM safeguard remains unchanged. The model geometry and resource-pack textures are not altered.

The cropped-element regression fixture exercises the production vertex and fragment helpers on the GPU, including the Romanesque top's two coplanar half-width faces, narrow crops, six face directions, rotated/mirrored UV bases, actual tile boundaries and native fallback. It does not certify visual seam removal: compare a heightmapped arch in the intended shader and pack, including POM shadows and oblique views. Also check an ordinary full block and an unheightmapped stone as controls. Vertex lookups replace fragment record discovery for recognized faces; no frame-time improvement is claimed without a scene measurement.

## Lifecycle and rollback

The lookup is rebuilt after every block-atlas upload, including resource reload. Replacing the texture at the same identifier releases the previous GPU texture. The one-switch rollback is `-Derydon.cuPom.mode=off`; `auto` is the production default, while `force` is accepted only in a development environment.

## Distribution boundary

The JAR contains Java integration code and one independently authored GLSL helper. It contains no Complementary source, patched shader archive, parent sheet, generated material texture, or new PNG.
