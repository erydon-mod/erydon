# Asset provenance

## ERYDON-owned material

Oliver has approved public release of the current ERYDON-owned textures,
models, sounds, data resources, authoring models, and native high-resolution
runtime composites in this repository. They are covered by
`LICENSE-ASSETS.md` unless a path is listed as an exception below.

The `assets/minecraft` namespace contains ERYDON-authored resource overrides;
using Minecraft's namespace does not claim ownership of Minecraft itself.

The six JSON files under
`src/test/resources/restored-family-geometry/legacy-assets/erydon/blockstates/`
are small ERYDON-owned fixtures extracted from an earlier ERYDON source state.
They replace a 40,112-file private development snapshot and are included only
for deterministic geometry validation.

## Cinzel exception

`src/main/resources/assets/erydon/font/cinzel.ttf` reports:

- Family: Cinzel
- Face: Regular
- Version: 2.000
- Designer: Natanael Gama
- Copyright: Copyright 2020 The Cinzel Project Authors
  (<https://github.com/NDISCOVER/Cinzel>)

Cinzel is licensed under OFL-1.1, not CC BY-SA 4.0. The exact upstream licence
and copyright notice are in `LICENSES/OFL-1.1.txt`.

## Deliberately absent material

- The separate ERYDON Collection 32x and 64x downloadable pack ZIPs are not
  source-repository inputs and are distributed separately.
- Three former inspiration-gallery screenshots were excluded because their
  rendered scenes credited third-party foliage and shaders. The public
  candidate uses ERYDON-owned material swatches in that screen instead.
- No Minecraft worlds, player data, logs, screenshots, or historical authoring
  meshes were imported.

