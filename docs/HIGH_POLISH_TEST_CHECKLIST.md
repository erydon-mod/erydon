# High polish and architectural fixes: test checklist

Use the IDEA development client and your usual shader/resource-pack combination.
Enable the high-polish master switch when testing the reflective finish;
new installations default to off. No test JAR is needed for an IDEA launch.

## Latest follow-up checks

- Stone High polish now uses a 25% reflection floor in CU and Bliss, up from
  15%. Repeat the same-view comparison after restarting; the approved 90%
  two-way mirror coating is unchanged. Bliss's earlier screenshot had High
  polish Off, so its enabled finish still needs checking.
- Start with Complementary Unbound r5.9 dev5, labPBR and High polish On. Compare
  the same Glacium wall and alcove view with Off after a full restart. The enabled
  log should name adapted terrain, deferred and glass programs, followed by
  `Opaque high polish classified` with a non-zero count.
- Repeat the stone/inlay comparison with Bliss 2.1.2 and Specular Reflections
  enabled. Stone should gain the selected finish while the metal keeps its own
  response. Sildur's Vibrant 1.51 does not read terrain specular maps and is not
  supported by this high-polish adapter.
- With CU, check two-way glass outside and inside on both window families,
  including open windows. The outer coating should be strongly reflective while
  the inner face stays clear. Disable the stone alone and confirm the mirror
  remains strong; disable Two-way glass and restart to restore its usual finish.
  The dark-silver texture colour is unchanged.
- Fully restart before testing these changes. Automated checks do not replace
  the shader-on checks below; no new test JAR was produced.
- Inspect the menu at several GUI scales: four stone buttons per row where space
  permits, natural-width side borders, and space beneath Save/Cancel. With the
  master off, saved stone/glass choices should look dimmed but remain editable.
  Selected Stones/Glass and gallery options keep their metal frame; other options
  do not. Re-enable the master and confirm saved preferences remain intact.
- Submerge plain Glacium blocks and overlay slabs/slopes. Also test waterlogged
  shapes, flowing water, the waterline, and a chunk boundary. Faces and metal holes
  should remain correct. Include water touching only an edge or corner. Stone
  now stays opaque everywhere, so the pool perimeter should keep one finish.
- Compare plain stone and the stone beneath bronze/silver inlays under identical
  lighting. Check full blocks, slabs and slopes; the base finish should match.
- Toggle Kelastrion, Latmion and Psamatheon individually, restarting each time,
  with native 16x, Collection 32x and Collection 64x. On should match polished
  stones while preserving grout/metal details; Off should restore honed maps.
  Swap suggestions and successful swap messages should use Polished when enabled
  and Honed when disabled; both spellings must remain accepted.
- Inspect the side-to-dome join and top surface of two-wide Gothic and Georgian
  alcoves in all facings, alongside single- and three-wide controls.
- Inner-page logos should have a clear gap below the upper frame.
- All stone buttons should display the correct stone, with a proportional cropped
  sample rather than magenta squares or a stretched tile. Try native and Collection textures.
- Inspect the top of both Arch Window corner pieces from above, in all facings;
  glass should no longer protrude through the stone roof as a see-through stripe.
- Two-way glass keeps the approved darker silver base, with a CU coating-specific
  reflection adjustment. Compare bright daylight and a darker interior.
- Check inlays on triangular slope sides while moving the camera, including
  rotated and inverted slopes. They should keep their motif without a false repeat.
- With Complementary Unbound r5.9 dev5 and labPBR, place two polished walls facing
  one another: both should now appear in reflections. Compare an inlay wall with
  plain stone when a light source is behind it; a full wall should block the light.
- Check herringbone and weave depth at a shallow viewing angle with Collection
  32x and 64x, especially grout lines across CTM seams. Their height maps are
  present; verify their displayed depth with High polish on and off.
- Look upwards into two- and three-wide Gothic and Georgian alcoves. Check the
  crown and small triangles above the arch, then inspect their roofs from above.
- See HIGH_POLISH_REFLECTION_INVESTIGATION.md for the opaque-stone trial and
  the separate CurseForge chunk-heightmap warning findings.

## 1. Settings and restart behaviour

- Inner pages should use a smaller logo, with the landing-page logo unchanged.
- Stone buttons should show the matching gallery texture inside the usual metal
  frame. The larger layout fits all 27; smaller windows retain readable paging.
- Open Mods > ERYDON > Configure > High polish. Check the Stones and Glass tabs,
  page arrows, mouse-wheel paging, and keyboard navigation. Try your usual GUI
  scale and a smaller window; labels and the restart notice should remain readable.
- Open a stone to change its main switch and its Herringbone, Weave and Inlays
  overrides. Inherit follows the main stone switch; On and Off override it.
  Two-stone weaves follow the first named stone (Glacium-Nerium follows Glacium).
- Change some values and press Cancel: reopening the menu should show the previous
  saved values. Switch pages before saving to confirm edits survive navigation.
- Save changes while in a world. The current session should keep its original
  appearance, including after F3+T or leaving and rejoining the world.
- Fully restart Minecraft: the saved appearance should now apply. Reopen the menu
  and confirm the choices survived. Saving tooltip settings must not reset them.
- Turn the master switch off and restart: all high-polish enhancements should be
  off, while individual choices remain saved for when the master is enabled again.

## 2. Individual polished and honed stones and patterns

- On inlays, inspect all six faces while moving past a wall, floor, stairs and slopes.
  Test bronze and silver in all four motifs. Check for fading, flicker, sorting through
  the stone and detached edges, with High polish on and off and after F3+T.

- Place Glacium and Portorium beside matching Gothic and Georgian alcoves. Enable
  only Glacium, restart, and confirm Portorium stays at its ordinary finish.
  Reverse the choices and repeat.
- Compare full blocks, slabs, stairs, slopes, arches, columns, walls and window
  stonework. Matching materials should have a consistent finish at matching angles.
- Test herringbone with both bronze and grout, two-stone weaves, and bronze/silver
  Trim, Guilloche, Quatrefoil and Rosette inlays. Check Inherit, On and Off separately.
  Colours, metal details and connected textures should remain intact.
- Check Kelastrion, Latmion and Psamatheon with their individual switches on and off.
- Check aged, ashlar, hewn, rusticated,
  rock and Diaphanes variants. Stone high-polish switches should not change them.

The 27 eligible stones are Aganite, Aterzon, Borealis, Brectite, Calacattum,
Chalstrom, Chrysonyx, Etruscus, Gelastrum, Glacium, Hesperion, Imperium, Kelastrion, Kylorion, Latmion,
Laurentium, Mielonyx, Nerium, Noxoplis, Porphyros, Portorium, Psamatheon, Rosinium, Sanguenite,
Selenephos, Solistra and Striatus.

## 3. Glazing and ordinary window glass

- With the master on, enable Glazing & window glass and restart. Compare Crystal,
  Silver, Bronze and Tinted glazing in panes, full blocks, vertical layers and framed
  slopes. Check the thin edges as well as the broad faces.
- Check ordinary Arch Windows and French Georgian Windows, both open and closed.
  Glass should have stronger shader reflections while keeping its colour and
  transparency; stonework should follow its own stone setting.
- Disable only Glazing & window glass and restart. The added pure-red specular
  maps should no longer be active. Glass must remain transparent.
- Repeat with native textures and the optional Collection pack you normally use.
  With shaders off, there should be no missing textures or unexpected red glass.

## 4. Two-way Arch and Georgian Window glass

- On Georgian Windows, test both hinges, single and wide windows, open and closed.
  The opening wings keep the coating on their outside; fixed upper panes keep it
  facing outwards. Extend/recalculate the cluster and check the glass choice remains.

- Test both Arch Windows and French Georgian Windows. Select two_way with the debug stick. With the master and Two-way glass switches
  on, restart and check that the exterior has the stronger reflective finish while
  the interior retains its normal glass appearance. The exterior should now be
  neutral silver rather than black, with High polish on or off.
- Check north, east, south and west facings, especially the right upper arch piece,
  and check a wider connected window. The mirror must stay on the outside.
- Toggle Two-way glass independently of Glazing & window glass and the stone
  setting, restarting each time. Off restores the previous mirror rendering path;
  it does not remove the block's two_way glass mode.
- Extend the window or run recalc: its glass mode and correct outside face should
  survive. Check again after a resource reload.

## 5. Alcove connected textures

- Check the recessed back of both Gothic and Georgian alcoves against adjacent
  standard blocks. The large marble pattern should continue across block seams.
- Inspect the narrow strip above the Gothic alcove's apex (the strip highlighted
  earlier in this task). It should share the surrounding texture phase.
- Check all four facings, tops and undersides, before and after F3+T. Changing High
  polish must not reintroduce the repeated individual tiles or displaced strip.

## 6. Gothic arch lighting slivers

- Inspect the two thin areas beside the top of a Gothic arch, where the original
  screenshots showed darker slivers on the left and right.
- Test in a dark room lit by blocks as well as daylight, for one- and two-wide arches.
- View from both sides and several angles, with shaders on and off. Look for dark
  slivers, gaps, flickering overlapping faces or changes to the intended arch shape.

## 7. Georgian walls with Axiom

- Make a small wall with straight sections, corners and diagonal connections.
- Rotate copies by 90, 180 and 270 degrees, then mirror across each horizontal
  axis. Diagonal connections and their slope direction should transform along
  with the straight connections.
- Check joins and piers against an equivalent wall placed normally. Test undo,
  subsequent editing and recalc for unwanted disconnections or changed orientation.

## 8. Rendering and performance

- First test a small sample, then a larger facade with repeated polished blocks,
  patterned shapes and glass. Compare master off and on after full restarts, using
  the same camera, render distance, shader settings, time and weather.
- Compare standing still, moving the camera, walking past the facade and loading
  its chunks. Note sustained FPS changes, stutter, flickering or sorting artifacts.
- Test both shaders off and Complementary Unbound r5.9 dev5 with labPBR. Other
  shader versions should retain their normal stone appearance without errors.
- Turn shaders off/on, reload them, and switch dimensions. Check CTM, patterned
  depth, large spiral stairs, glazing and the approved two-way glass finish.
- No extra world render or polish texture lookup is added. Stone remains opaque;
  the former water-neighbour scans during chunk rebuilding are gone. Texture
  substitutions still run during resource loading. These implementation changes
  are not a measured FPS or chunk-loading guarantee.
- Compare the same saved chunks, camera, render distance, time and weather. The
  inspected CurseForge world also has incompatible stored heightmaps for its
  taller-world datapack; separate server loading time from client chunk meshing
  before attributing all delays to rendering. Keep the datapack and world intact.

For an issue report, include the block/material, facing, relevant saved switches,
whether Minecraft was restarted, shader/pack selection, and a screenshot.
