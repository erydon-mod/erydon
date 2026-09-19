# High-polish rendering investigation

Status: opaque rendering and geometry were visually checked, but the user's
restarted on/off comparison exposed a polish activation failure. Startup and
specular-resource fixes are now implemented, with a separate Bliss adapter and
Complementary mirror-coating adjustment. The enabled finish and large-world
performance need a fresh in-game comparison. No test JAR was produced.

## Startup failure and the corrected validation

The on-session log showed the CTM-POM adapter, but no high-polish shader changes
or block classification. Iris 1.7.6 eagerly constructs its base `ProgramSet`
before `IdMap`; the earlier guard therefore disabled the effect while those
programs were loaded. CU's dimension map makes `world0` the base program set,
so assuming that the base contained no shader source was wrong.

The supported, enabled path now parses the same Iris ID map before that base
program set, checks reserved IDs, then reuses the map at its original assignment.
It does not parse twice. Disabled and unsupported packs keep the original order.
The resource override's constructor-HEAD handler is now static, as required by
Mixin; the previous non-static handler was rejected at runtime.

`verifyHighPolishMixinLaunch` uses an isolated Fabric pre-launch fixture and
exits before opening Minecraft. It verifies the actual transformed Iris
constructor order, map reuse and successful resource-override installation.
It does not use the player's options/world or create a JAR. Shader classification
also logs a preparation failure instead of silently leaving an enabled setting
inactive. A shader switch starts fresh readiness checks.

## Why the previous route caused trouble

The inspected combination is Minecraft 1.20.1, Iris 1.7.6, Sodium 0.5.13 and
Complementary Unbound r5.9 dev5. The previous High polish implementation moved
opaque stone into translucent terrain even with shaders disabled.

Complementary's `reflectionVoxelization.glsl` accepts solid and cutout terrain,
but excludes translucent terrain from its world-space reflection data. A block
can therefore cast a shadow while remaining absent from a reflection. CU also
treats unknown or odd-numbered solid-terrain material IDs as non-occluding for
its coloured-light volume; full stone cubes need an appropriate solid ID.

The raw 1.21.11 authoring loader produces ordinary baked block geometry. Its
file format does not require a transparent rendering pass. Iris documents the
[terrain pass mapping](https://shaders.properties/current/reference/miscellaneous/block_properties/).

## Implemented trial

- All stone keeps its ordinary opaque/cutout layer, including alcoves, window
  frames and the stone beneath inlays. Metal overlays remain cutout; their small
  separation from the base stone is retained to avoid flicker.
- A narrowly matched in-memory CU adapter reuses the existing specular sample
  and opaque reflection pass. Selected stone receives the transparent path's
  angular reflection strength, without moving the geometry to that pass.
- Existing normal/height mapping and the CTM-POM bridge remain in place. Metal
  pixels and low-smoothness grout retain their authored material response.
- Material mask 242 is unused in the inspected shader; 241 is water and must
  not be reused. Reserved block IDs retain CU's solid/partial-shape parity.
- Block-state classification runs at shader loading only. The water-neighbour
  wrapper and its chunk-rebuild fluid reads have been removed. Faces, edges and
  corners touching water now use the same opaque stone path everywhere.
- The shader-properties fingerprint, parsed ID collision checks and unique
  source anchors gate the adapter. Unsupported shaders retain their normal
  stone rendering. All required shader stages must be recognised before block
  states are classified. Large spiral stairs retain their existing POM bridge.
- Glazing and two-way glass retain their approved textures and rendering path.
  In CU, two-way coating states have distinct material IDs for polished versus
  ordinary frames. The existing glass specular sample identifies metallic
  coating pixels; only those receive a 90% reflection floor. Clear inner glass,
  normal glazing, stone frames and water retain their own handling. No second
  sample, surface or reflection pass is introduced.
  The master and individual preferences remain restart-bound and default-off.

This introduces no extra terrain surfaces, reflection render, framebuffer,
sampler or texture lookup for the polish effect. It does add small shader
conditions and changes which stone enters the shader's existing reflection
work. Zero FPS cost is not established; profile the same large scene before
and after. Partial shapes still have the shader's approximate voxel outlines.

## Bliss and Sildur's

Bliss 2.1.2 reads the specular maps, but clamps the authored zero green channel
on stone to only 0.02 reflectance. High polish now raises selected stone pixels
to full smoothness and a minimum 0.15 reflectance using the existing terrain
sample. Its existing metal and low-smoothness grout pixels are untouched, as
are normal/height maps and glass. An exact shader-properties fingerprint and
unique source anchor limit this to the inspected pack. Per-stone and per-finish
choices still work because selection uses block material IDs, not shared texture
replacement. Bliss's Specular Reflections setting must be enabled.

Sildur's Vibrant 1.51 Extreme-VL does not read terrain specular maps: the sampler
is commented out and its metal/polished response comes from hard-coded block
categories. It cannot distinguish an inlay from its stone using that route.
No speculative Sildur adapter or extra texture sampling was added. High polish
is supported by the inspected CU and Bliss versions; other packs retain their
ordinary material response. This follows the distinction between smoothness
and reflectance in the [labPBR material standard](https://shaderlabs.org/wiki/LabPBR_Material_Standard).

## Herringbone and weave height-map audit

Every CTM normal/height tile in the current Collection 32x and 64x sources was
checked: 1,944 herringbone and 864 weave tiles per pack contain varying height
alpha. The active 64x ZIP resolves the same counts through its texture aliases;
example alpha ranges are 245–255. The active overlay test pack does not replace
these patterned maps. No height texture was replaced or generated by this fix.

Sampled native 16x maps are flat (alpha 255), unlike the optional PBR packs.
Returning the patterned stone to opaque terrain restores access to the existing
CTM-aware POM path. The user confirmed visible patterned depth; keep it in the
fresh enabled-polish regression check with the desired Collection pack.

## Independent CurseForge chunk-loading evidence

The inspected closed session produced 26,490 incompatible chunk-heightmap
warnings covering 6,974 chunk coordinates. Its taller-world datapack defines
height 1,072 with minimum Y -64, while the rejected arrays use the shorter
encoding. The server discards these heightmaps and must recreate them, which is
a separate plausible contributor to chunk-loading delays even without shaders.
This is not a profile proving how much time each cause takes. The live world,
datapack, installed JAR, resource packs and options have not been modified.

The inspected installed 1.5.25 JAR predates the last water wrapper despite sharing
its version number. Compare exact builds as well as version labels during testing.

## Validation and next checks

Automated tests cover source recognition, disabled/unsupported behaviour, ID
collisions, reload state, shader-mask isolation, metal/grout preservation and
spiral predicate idempotence. A local optional test expands and preprocesses the
installed shader with Iris, applies the adapters and parses twelve CU stages
across the Overworld, Nether and End, plus three Bliss terrain fragments.
This is not GPU compilation or visual validation. Set `ERYDON_CU_TEST_SHADER`
and `ERYDON_BLISS_TEST_SHADER` to the installed shader ZIPs to run those tests.

Use HIGH_POLISH_TEST_CHECKLIST.md for the in-game comparison, including two
facing stone walls, overlay light blocking, patterned depth, water contact,
wide alcove crowns, shaders-off chunk movement and shader-on frame times.
