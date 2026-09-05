# Column orientation

Square, circular, and Gothic columns have independent `capital_orientation` and
`base_orientation` enum properties: `straight` (the existing appearance and save
default) and `diagonal` (a 45-degree Y rotation around the block centre).

- Side right-click retains the existing capital/base style controls.
- Sneak-side-right-click with an empty hand toggles the selected end's
  orientation for the connected column. Capital/base blocks select that end;
  on a shaft or isolated plinth, the lower 45% selects the base and the rest
  selects the capital. The action bar reports the changed option.
- The debug stick exposes both properties for individual blocks, alongside the
  existing manual/automatic mode control.
- Extending a column inherits both orientations from its adjacent column block.
  Recalculation changes structural parts without resetting either choice.
- The base setting applies to the base and isolated plinth. A shaft, including
  a square/circular `capital=none` top, remains straight. Gothic capitals retain
  their fixed style and follow the capital orientation.

These are visual choices over existing baked components: no block entities,
tickers, extra model JSONs, duplicated baked geometry, or new block/item IDs.
The saved state space grows from 32 to 128 states per column block; all states
continue to share the existing unconditional wrapper and child model set.
Collision and selection shapes retain their existing simplified shapes.

The shared `WorldAlignedYRotation` path applies the turn after model baking, so
existing element rotations (including Byzantine/Guilloche and raw Gothic
geometry) compose without vanilla JSON rotation restrictions. Normals rotate
with geometry, and diagonal components are unculled because they may overhang
the original cell. Axiom fallback geometry uses the same turn and unculled
bucket. Safe flat stone tops and undersides use world projection; faces whose
projection crosses a sprite boundary retain their authored UVs without wrapping
or shifting their texture phase. Existing quarter-turn callers are unchanged.

## Verification

`ColumnBlockTest`, `ColumnBakedModelTest`, and `ColumnOrientationRenderingTest`
cover enum cycling, independent end selection across styles, unchanged shafts,
composition with tilted geometry, normals, source-data preservation, matching
Fabric/Axiom geometry, and cull buckets. The state audit includes both options.

In-game visual verification is still required: compare straight and diagonal
square, circular, Gothic, and Byzantine capitals/bases; inspect stone tops,
undersides, overhangs beside solid blocks, and Axiom previews. Check save/reload,
stack extension, and `/erydon recalc` preserve the settings. No test JAR is built
by this change.
