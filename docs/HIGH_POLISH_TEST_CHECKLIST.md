# High polish and architectural fixes: test checklist

Use the IDEA development client and your usual shader/resource-pack combination.
The high-polish master switch is enabled in the current local test configuration;
new installations default to off. No test JAR is needed for an IDEA launch.

## 1. Settings and restart behaviour

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

## 2. Individual polished stones and patterns

- Place Glacium and Portorium beside matching Gothic and Georgian alcoves. Enable
  only Glacium, restart, and confirm Portorium stays at its ordinary finish.
  Reverse the choices and repeat.
- Compare full blocks, slabs, stairs, slopes, arches, columns, walls and window
  stonework. Matching materials should have a consistent finish at matching angles.
- Test herringbone with both bronze and grout, two-stone weaves, and bronze/silver
  Trim, Guilloche, Quatrefoil and Rosette inlays. Check Inherit, On and Off separately.
  Colours, metal details and connected textures should remain intact.
- Check Kelastrion, Latmion and Psamatheon, plus aged, ashlar, hewn, rusticated,
  rock and Diaphanes variants. Stone high-polish switches should not change them.

The 24 eligible stones are Aganite, Aterzon, Borealis, Brectite, Calacattum,
Chalstrom, Chrysonyx, Etruscus, Gelastrum, Glacium, Hesperion, Imperium, Kylorion,
Laurentium, Mielonyx, Nerium, Noxoplis, Porphyros, Portorium, Rosinium, Sanguenite,
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

## 4. Two-way Arch Window glass

- Select two_way with the debug stick. With the master and Two-way glass switches
  on, restart and check that the exterior has the stronger reflective finish while
  the interior retains its normal glass appearance.
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
- In Complementary, surfaces using the high-polish translucent path may be absent
  from one another's reflections while their shadows remain visible. This is the
  known shader limitation, not a failed texture or missing block.
- No additional world-render pass was added. A large visible area using translucent
  rendering can still cost more to render, so passing automated checks is not an
  FPS guarantee.

For an issue report, include the block/material, facing, relevant saved switches,
whether Minecraft was restarted, shader/pack selection, and a screenshot.
