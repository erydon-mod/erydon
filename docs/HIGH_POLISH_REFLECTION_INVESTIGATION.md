# High-polish reflection visibility

Status: investigation complete; proposed shader trial is not enabled or implemented.

The inspected combination is Minecraft 1.20.1, Iris 1.7.6 and Complementary
Unbound r5.9 dev5, with world-space reflections enabled. The stronger polish
uses the translucent terrain pass even though the stone texture is opaque.

## Confirmed cause

Complementary's `shaders/lib/voxelization/reflectionVoxelization.glsl`, in
`UpdateSceneVoxelMap`, accepts only solid, cutout and cutout-mipped terrain.
Translucent terrain returns before reflection data is written. The shadow
program already calls this function once per quad, so an excluded stone can
cast a shadow while being absent from world-space reflections.

This is a shader reflection-data filter, not a missing CTM rule or a missing
model face. The 1.21.11-style authoring format does not create a separate
Minecraft renderer here.

## Recommended experiment

Add a narrowly matched, optional in-memory adapter for the supported
Complementary version, using the existing Iris source-adapter infrastructure.
Allow opaque ERYDON high-polish stone into the existing shadow-stage voxel
update. Keep water, glazing, two-way panes and transparent metal overlays out.

The filter needs sprite identity and opacity, not just a block identifier:
windows contain stone and glass in the same block. Build the sprite eligibility
lookup when resources load, then query it in the shader. The existing CTM-POM
lookup is a useful precedent, but its repeat-family membership alone does not
prove opacity or cover every desired sprite.

The adapter should leave unsupported shader sources unchanged and initially
be an opt-in trial. Do not modify installed shader ZIPs or add duplicate world
geometry. Reuse the existing shadow draw and reflection buffers.

## Performance and visual limits

This avoids an extra world render and extra block surfaces, but it adds GPU
lookups and voxel-buffer writes for surfaces currently skipped. It cannot be
called performance-neutral without a controlled comparison on a large build.
The existing voxel representation also approximates partial-block shapes;
arch openings, alcove recesses and slopes may not have exact reflected outlines.

Returning stone to the solid pass is the available fallback, but loses the
currently preferred shader response. Changing only a specular map cannot make
an excluded translucent surface enter the world-space reflection data.

## Trial acceptance checks

- Two facing high-polish stone walls reflect one another while retaining the
  approved surface appearance; test standard blocks and both alcoves.
- Water, ordinary glass, two-way glass and overlay transparency stay correct.
- Verify all facings, CTM phases, partial blocks and resource reloads.
- Disabling the trial restores the unmodified shader source.
- Unsupported shader versions and disabled world-space reflections stay safe.
- Compare frame times and GPU load with an identical camera, weather, shader
  settings and complex scene; check both stationary and moving views.
- Keep the trial opt-in until visual and performance results justify rollout.
