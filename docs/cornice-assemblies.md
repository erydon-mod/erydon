# Cornices and ceiling covers in shared cells

Cornices still attach to walls through normal item placement. If a front cell already
contains another cornice or a cover, it becomes an internal `cornice_assembly` holding
the original parts. The supporting wall and ceiling cells are not replaced or moved.
There is no room controller or automatic whole-room fill.

## Placement and editing

- A cell can retain up to four paid cornice attachments and one cover. Attachments
  are identified by their supporting edge, so the two valid front cells of one slope
  cannot create duplicate mouldings on the same wall face.
- Place the cover before or after the cornices. Clicking moulding beneath a solid
  ceiling with a cover item applies the ceiling skin in that same cell.
- Each part keeps its original block ID and state. Cover size, finish, light and
  colour remain independent of the moulding. The debug stick edits the part under
  the cursor, and picking or breaking targets that part.
- Removing the last extra part restores an ordinary block where it has an exact
  representation. Existing cornices and covers are not converted by a world scan.
- Whole-cell removal drops the paid parts once each. Removing a supporting wall
  removes only the attachments that depended on it.

## Rendering and performance

Shared cells use normal chunk-baked models and a block entity without a ticker or a
block entity renderer. Each part delegates to its original model and material state,
including the existing Synapheia/Continuity path. Joining uses a finite local search;
it does not discover or traverse the whole room while drawing. Existing fitted-mesh
budgets remain shared across the cornice family.

The carrier stores parts in a local orientation basis. Saved block entity NBT and
the carrier's rotation/reflection state must travel together when copying structures.
Only horizontal rotations and reflections are represented; vertical flips and tilts
are unsupported.
`/erydon swap` also maps stored part IDs and retains the original NBT for undo.
State-only preview APIs do not receive the stored parts and cannot reconstruct an
assembly; the carrier's state-only model is deliberately empty. Live Axiom and
WorldEdit copy/rotate/undo and hologram behaviour require separate in-game checks.

## Lighting audit boundary

Overhanging cornice geometry can lie outside its owner cell. The installed Indium
smooth-lighting path samples the owner neighbourhood and clamps local positions,
so the same physical face can receive different ambient occlusion from its two
possible owners. This is separate from CTM orientation seams. Cornices use opaque
terrain; no incorrect translucent pass was found in the source audit.

This change preserves per-part lighting/material policy when a lit cover shares a
cell with an unlit cornice. It does not claim to fix owner-relative overhang lighting
or the reported shader-specific appearance. Compare identical scenes with matched
shader, resource packs and renderer settings before attributing a visual result.

Source/probe reports are produced under `build/reports`. They establish storage,
geometry and bounded CPU behaviour, not live FPS, shader appearance or visual CTM
acceptance.
