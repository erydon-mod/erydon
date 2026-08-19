# Synapheia validation

Version 1.5.17 replaces the embedded Continuity runtime with Synapheia for the
whole ERYDON texture set.

## Automated checks

- All 1,217 production CTM rules parse: 1,025 repeat and 192 connected-overlay.
- Every supported 47-tile connection mask is covered by the selector test.
- Signed negative coordinates, geometry beyond 0..16, automatic UVs, culling,
  face selection, and canonical legacy-ID resolution have regression tests.
- The release audit verifies zero CTM BOMs, zero missing tile references, no
  bundled Continuity files, and no production test fixtures.

## Runtime checks

- Clean 1.5.17 launch without Continuity using the Collection 64x pack.
- Live rule load: 1,025 repeat and 192 connected-overlay rules, with legacy IDs
  also indexed to their canonical blocks.
- The texture showcase placed 440 pads spanning the complete material/variant
  grid. The 32x out-of-range cube, automatic UV model, Gothic cornice, and
  Georgian showcase were also checked in game.
- Visual checks passed for standard and aged repeat textures, connected-overlay
  joins and corners, albedo, normal maps, and specular maps.
