# High polish and architectural fixes: test checklist

Use the IDEA development client first, with Complementary Unbound r5.9 dev5
or Unbound/Reimagined r5.9.3, labPBR and your usual Collection pack. Full
Minecraft restarts are required after saving finish or glass settings. No test
JAR was produced.

## Current follow-up checks (25 September)

- Circular ERYDON columns: with Complementary's world-space reflections enabled,
  check that a circular column appears in a nearby Mirror-finish stone surface.
  Its reflected silhouette is deliberately a square-block approximation; the
  visible column, collision and voxel-light behaviour remain unchanged.
- Medium alcoves: Georgian and Gothic two-wide side walls are 30% thicker
  inward. Check the roof joins, all four facings and collision; one- and
  three-wide alcoves should retain their existing dimensions.
- Axiom: rotate Romanesque, Modern and Gothic arches and Arch Windows through
  90/180/270 degrees, then mirror along each horizontal axis. Include in-place
  overlapping edits, mixed arch materials, open windows and two-way glass.
  Check left/right pieces, undo/redo, further placement and recalc.
- Overlay multiface layers: place against each of six solid faces, including
  edge/corner hits. Crouch-click with the same item to thicken up to eight
  layers; repeat using a picked/Axiom-palette stack and a waterlogged sample.
- Inlay slopes: join standard, shallow, steep and vertical hypotenuses where
  their physical edges meet, including a continuation one block up/down.
  Check straight/corner and inverted variants; separate edges must keep a border.
- Bliss/Noble: retest POM with the corrected maps supplied by the project.
  An obsolete overlay test pack was overriding the IDEA maps and has been
  deselected. No replacement resource pack is needed for this check.
- Shared stone finishes: compare Themelios plain Imperium, ERYDON plain
  Imperium and ERYDON bronze-trim Imperium with Inlays set to Inherit. The
  original mismatch was a Themelios block excluded from the finish controls.
  Repeat Honed, Polished and Mirror, plus the master-off setting, after restarts.
  Themelios shapes and Daedalon stone decor now follow the same 27 material
  choices; include a Daedalon statue/capital and another shared stone family.
  Check pattern overrides on Themelios herringbone/weave/inlays and all three
  texture resolutions. Aged/rough stone and bronze sculptures remain unchanged.

Earlier visual approvals include the Gothic arch sliver repair, slices,
Mod Menu, stronger metal treatment, both Complementary styles, two-way glass
and the latest high-polish performance trial. The items above are new checks.

## 1. Three stone finishes

- In Mods > ERYDON > Configure > High polish, enable the master. Compare the
  same Glacium wall and Gothic/Georgian alcove view at Honed, Polished and Mirror,
  restarting each time and keeping the camera, time, weather and shader settings
  fixed. Honed should soften reflections, Polished should sharpen them, and
  Mirror should make them stronger. Mirror uses a 50% floor; Polished adds no
  reflection boost. These coefficients do not specify final screen brightness.
- Test the All: Honed / All: Polished / All: Mirror presets. They reset pattern
  overrides to Inherit and preserve glass preferences. Small windows may omit
  the presets while retaining per-stone controls and paging.
- Set different finishes on Glacium and Portorium; only the chosen material
  should change. Compare full blocks, slabs, stairs, slopes, arches, columns,
  walls and window frames at matching angles.
- Test Herringbone, Weave and Inlays at Inherit, Honed, Polished and Mirror.
  Inherit follows the main stone choice. Weaves follow their first named stone
  (Glacium-Nerium follows Glacium). The stone grid should show Mixed when needed.
- Repeat Kelastrion, Latmion and Psamatheon at all three levels with native 16x,
  Collection 32x and Collection 64x. They use their original specular maps now;
  CU selects smoothness directly. Polished and Mirror should no longer depend
  on a separate replacement map. Grout and normal/height detail must remain.
- Check aged, ashlar, hewn, rusticated, rock and Diaphanes controls alongside
  the samples. These stone finishes should remain unchanged.
- Turn the master off and restart: eligible stone should use Honed in CU and
  glass enhancements should turn off. Re-enabling restores saved stone choices.
- Existing true/on preferences should load as Mirror; false/off as Honed.
  Swap suggestions and success messages should use the active plain finish.
  Honed, Polished, Mirror and the old plain material names must all remain
  accepted command aliases without changing block IDs.

## 2. Metallic details, connected textures and depth

- Compare bronze and silver Trim, Guilloche, Quatrefoil and Rosette on walls,
  floors, slabs, stairs and slopes. CU's metal trial uses an 85% reflection
  floor independently of the stone finish. Its corrected final tint and 20%
  texture preservation should give stronger contrast between dark and bright
  reflected objects. Silver should stay neutral and bronze warm; confirm that
  highlights do not wash out the motif. Compare a dark object reflected beside
  the sky in each metal, and check that the surrounding stone keeps its finish.
- Check all six faces while moving the camera. Look for flicker, disappearing
  overlays, sorting through stone or detached edges. Include triangular slope
  sides, rotated/inverted slopes and a resource reload.
- Compare stone under inlays with the matching plain stone under the same light.
  Their selected finish should match. Metal should remain visible at every
  stone finish, including Honed and master Off.
- Check herringbone and weave depth at shallow angles with Collection 32x/64x,
  especially grout at CTM seams. Native 16x patterned height maps are flat;
  the finish adapter does not add height or invent missing metallic masks.
- Check alcove recessed backs and the narrow strip above the Gothic apex
  against adjacent blocks, in all facings and before/after F3+T. Marble texture
  phase must remain continuous across seams.

## 3. Glass and the approved two-way mirror

- Keep the master on and compare ordinary Crystal, Silver, Bronze and Tinted
  glazing with its switch on/off after restart. Include panes, full blocks,
  vertical layers, framed slopes and both window families, open and closed.
  Transparency and glass colour should remain correct.
- On Arch and French Georgian Windows, select two_way with the debug stick.
  The approved CU exterior mirror keeps its 90% target. Test all facings, wide
  windows, both Georgian hinges and opened wings; the inside should stay glass.
- Change the surrounding stone between Honed, Polished and Mirror while leaving
  Two-way glass enabled. The exterior mirror should remain equally strong.
- Toggle Two-way glass independently of ordinary glazing, then restart. Off
  restores the ordinary coating response without deleting the two_way mode.
  Extend/recalculate the window and verify its mode and exterior side survive.

## 4. Opaque stone, lighting and water

- Place two stone walls facing each other. Each should appear in the other's
  CU reflection where the shader can see it. Put light behind full overlay
  blocks and compare with plain blocks: both must block light normally.
- Submerge plain Glacium plus overlay slabs/slopes. Include waterlogged shapes,
  flowing water, the waterline, chunk boundaries, and water touching only an
  edge or corner. Check for missing-looking faces or a mixed finish at the pool
  perimeter. Wet and dry stone use the same opaque path.

## 5. Menu and persistence

- Inspect usual and smaller GUI scales: four stone buttons per row where space
  permits, readable finish labels, well-spaced logo, and room below Save/Cancel.
  Stone buttons should show their gallery texture at the correct proportions.
- Master Off should dim saved stone/glass choices without erasing them. Selected
  Stones/Glass and gallery tabs retain the metal frame; unselected tabs do not.
- Check page arrows, wheel scrolling and keyboard navigation. Visit a stone,
  return to the grid and switch tabs without losing unsaved choices.
- Cancel must discard edits; Save must retain them on restart. Saving tooltip
  options must not reset polish. Saving in-world, F3+T or rejoining must not
  apply new finish choices before a full restart.

## 6. Architectural regressions

- Inspect one-, two- and three-wide Gothic and Georgian alcoves from above and
  below. Check side-to-dome joins, crown holes, small triangles above the arch,
  top-surface artefacts and all facings.
- Inspect both Arch Window corner roofs for the formerly see-through stripe.
- Check both Gothic arch lighting slivers in daylight and a dark room lit by
  blocks, from both sides, with shaders on/off. Check for gaps or overlapping faces.
  In particular, check a three-wide opening at least two blocks tall: its long
  corner gap filler now belongs to the upper row, with its exposed world position
  unchanged. The removed lower overlap is covered on both sides by an existing
  curved panel; this correction was visually approved and remains a regression check.
- With Axiom, rotate a Georgian wall by 90/180/270 degrees and mirror it along
  each horizontal axis. Include straight, corner and diagonal sections. Compare
  joins and piers with a normally placed wall; check undo, further edits and recalc.
- With Axiom, yaw-rotate horizontal and vertical slices by 90/180/270 degrees,
  then mirror along X and Z. Include all eight thicknesses, each corner, top/bottom
  horizontal slices and waterlogged examples. Vertical slices should reflect into
  the matching corner. Flip horizontal slices upside down along Y and check that
  top/bottom swaps; flipping twice and undo/redo should restore the original state.
  Slice families, materials and thicknesses must stay unchanged.

## 7. Performance and shader switching

- Compare the same small sample, then a large facade at Honed/Polished/Mirror.
  Keep camera, render distance, weather, time and shader settings fixed. Compare
  standing still, turning, walking past it and loading its chunks.
- Repeat with shaders off. Check sustained FPS, frame-time spikes, chunk-loading
  delays and visible sorting errors. No new draw pass, specular sample, faces or
  fluid-neighbour scans are added; shader arithmetic and existing reflection
  work still need measured comparison before claiming no performance cost.
- Reload/switch shaders and visit all three dimensions. Check CTM, large spiral
  stairs, patterned depth, inlays, glazing and two-way mirrors afterward.
- Compare both Complementary r5.9.3 styles with labPBR enabled. Their default
  visual styles differ, but both should receive the same material treatment.
  The macOS/Distant Horizons low-sampler profile retains CU's ordinary texture
  preservation so the patch does not add a sampler on that constrained path.
- Bliss/Noble finish adapters remain deferred; their native materials should
  remain. With POM enabled, check bronze and silver in all four overlay motifs
  on full blocks, slabs and slope sides. The surface-height correction should
  keep metal visible at close and grazing angles without removing its bevel
  lighting. Compare Complementary Unbound/Reimagined for retained shine and no
  flicker, with high polish both on and off. Underlying patterned/rough stone
  should retain its height effect. Include native, Collection 32x and 64x.
- The previously inspected CurseForge world also had incompatible saved
  heightmaps for its taller-world datapack. Separate server loading from client
  meshing when profiling; preserve the world and datapack.

For reports, include the material/shape, facing, saved finish, restart status,
shader/pack selection and screenshot. See POLISH_MATERIAL_AND_SHADER_REVIEW.md
for the implementation and HIGH_POLISH_REFLECTION_INVESTIGATION.md for history.
