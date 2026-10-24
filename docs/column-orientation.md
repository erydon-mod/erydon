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
  a `capital=none` top on square, circular or Gothic columns, remains straight.
  Visible capital models follow the capital orientation.

Gothic capital side-clicks cycle the existing Gothic head (`georgian`), the
Byzantine placeholder (`guilloche`) and None (`none`). The published internal
`guilloche` value stays unchanged. Until a separate Gothic Byzantine model is
authored, that option displays the existing Gothic head. None uses the existing
raw Gothic pillar with no cap or capital rotation. Its cached five-lobe collision
and outline follow that shaft's footprint, without the wider invisible head.
Existing Gothic base, head and middle collision approximations are unchanged.
The lower Gothic base remains fixed; capital and base orientations remain saved.

These are visual choices over existing baked components: no block entities,
tickers, extra model JSONs, duplicated baked geometry, or new block/item IDs.
The saved state space remains 128 states per column block; all states
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
`GothicColumnControlsLaunchProbe` calls the real `onUse` callback for Gothic,
square and circular stacks on all four side faces and both orientations, and
checks saved-state round trips, recalculation and ordinary placement/debug-stick
pass-through. Run it without a JAR using
`-I gradle/erydon-gothic-column-validation.init.gradle verifyGothicColumnControls`.
`test_model_gothic_column_controls_safety.py` checks all 54 stone/aged Gothic
model routes and native repeat rules. The existing child models and block IDs
also remain covered by both Collection resolutions; no pack source changes or
new material rules are needed. Actual lighting and texture joins remain a live
client check.

In-game visual verification is still required: compare straight and diagonal
square, circular, Gothic, and Byzantine capitals/bases; inspect stone tops,
undersides, overhangs beside solid blocks, and Axiom previews. Check save/reload,
stack extension, and `/erydon recalc` preserve the settings. No test JAR is built
by this change.
