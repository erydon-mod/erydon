# ERYDON-only CTM-aware POM architecture

## Scope and ownership

This compatibility layer is owned and distributed only by ERYDON. It covers every active 6x6 repeat-CTM tile family used by an `erydon:` block and targets the user-selected `ComplementaryUnbound_r5.9_dev5.zip`. It does not modify, repack, or redistribute Complementary Unbound source or assets.

Automatic activation is limited to the exact dev5 `shaders.properties` SHA-256 `a4c4e2156ad5aeb66c0ad495a2721ea092952f2cdebfaaf89a20dfa3409ef2e8`. Unknown properties remain untouched. Every terrain-source edit also requires five unique source anchors and five exact postconditions; any mismatch returns the original source byte-for-byte.

## Data flow

1. At block-atlas upload, ERYDON binds an invalid 1x1 lookup so shader sampling is always safe.
2. After stitching, ERYDON reads the active `optifine/ctm` and `mcpatcher/ctm` rules. It accepts only `method=repeat`, 6x6 rules that target ERYDON blocks, resolves their ordered 36-tile lists, and deduplicates shared families.
3. Every selected family must contain 36 distinct stitched sprites with a common per-family sprite size and one common atlas size. A malformed or missing family leaves the invalid placeholder active.
4. ERYDON encodes all stitched centres into one runtime-only protocol-v3 256x256 RGBA8 lookup: 262,144 bytes, nearest filtering, clamped edges, and no PNG. The prime-sized 24,571-slot hash table avoids atlas-alignment clustering and supports up to 454 complete families.
5. Optional Iris mixins add the dedicated `erydonCtmPomLookup` custom sampler and atomically adapt only `gbuffers_terrain` in memory. The directive is `texture.erydonCtmPomLookup=erydon:ctm_pom_lookup`; the shader ZIP stays unchanged.
6. The ERYDON GLSL helper recognizes the current stitched phase from its atlas midpoint. When a POM sample crosses a tile edge, it maps the crossed local tile to Continuity's repeat-grid direction for the rendered face, selects the adjacent 6x6 phase, and samples that stitched sprite.
7. Unknown sprites, invalid lookup headers, unsupported geometry, absent Iris, disabled POM, or unsupported shader source use Complementary's original wrap behaviour.

The four adapted POM domains are normal reads, final displaced material coordinates, parallax self-shadow taps, and slope-normal taps. Six cardinal face orientations are handled from the runtime tangent, binormal, and normal axes, so rotated or mirrored model UVs do not require baked texture sheets.

## Lifecycle and rollback

The lookup is rebuilt after every block-atlas upload, including resource reload. Replacing the texture at the same identifier releases the previous GPU texture. The one-switch rollback is `-Derydon.cuPom.mode=off`; `auto` is the production default, while `force` is accepted only in a development environment.

## Distribution boundary

The JAR contains Java integration code and one independently authored GLSL helper. It contains no Complementary source, patched shader archive, parent sheet, generated material texture, or new PNG.
