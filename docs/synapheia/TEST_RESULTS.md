# Synapheia validation

Version 1.5.17 replaces the embedded Continuity runtime with Synapheia for the
whole ERYDON texture set.

## Automated checks

- All 1,352 current production CTM rules parse: 1,160 repeat and 192 connected-overlay,
  including 135 independent coping rules that survive older pack overrides.
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

## Coping and triangular-side changes (2026-10-03)

Coping coverage is checked against an older material-rule override. Mixed-incline
mitres share their cross sections, while triangular slope sides use their whole
visible outline and reciprocal partial-edge connections. The tests preserve
diagonal corner conditions and reject height gaps and single-point contacts.
The Collection 64x v1.5.16 coverage audit found no path conflicts with the 135
dedicated coping rules; all 4,860 referenced tiles resolved through its files or aliases.
The coping geometry, triangular Trim borders and folded slope/full-block joins
have since received in-game visual approval during development. Automated checks
cover native and Collection rule coverage, actual placed mesh replay and texture
phase; the earlier 1.5.17 runtime figures above remain historical. Visual approval
does not constitute a measured chunk-rebuild performance comparison or prove
diagonal artwork for the other motif families.
