# High-polish rendering investigation

Status: the earlier opaque CU route and two-way mirror were visually approved.
The current trial adds Honed, Polished and Mirror stone finishes, plus stronger
metallic reflections, for CU first. Mirror starts at 50%; Polished has full
smoothness with no added reflection floor; Honed matches the old K/L/P
smoothness. The 90% two-way coating is unchanged. The new finish levels and
metal adjustment still need large-scene profiling. The user approved Mirror;
the subsequent metal-tint/20%-preservation revision needs in-game comparison.
Unbound and Reimagined r5.9.3 are now recognised alongside Unbound r5.9 dev5.

The [material and shader review](POLISH_MATERIAL_AND_SHADER_REVIEW.md) records
the implementation and compatibility scope. No test JAR was produced.

## Startup failure and the corrected validation

The on-session log showed the CTM-POM adapter, but no high-polish shader changes
or block classification. Iris 1.7.6 eagerly constructs its base `ProgramSet`
before `IdMap`; the earlier guard therefore disabled the effect while those
programs were loaded. CU's dimension map makes `world0` the base program set,
so assuming that the base contained no shader source was wrong.

The supported, enabled path now parses the same Iris ID map before that base
program set, checks reserved IDs, then reuses the map at its original assignment.
It does not parse twice. Unsupported packs keep the original order. CU also
uses this path with the master off to provide the common Honed baseline.
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
  and opaque reflection pass. Stone uses Honed or full smoothness; only Mirror
  gets a 50% reflection floor, increasing toward grazing angles. Geometry
  stays in the opaque pass.
- Existing normal/height mapping and the CTM-POM bridge remain in place.
  Low-smoothness grout retains its authored response. Eligible metallic
  pixels receive the separate CU 85% floor. Their tint is restored in the final
  reflection stage, and they use 20% rather than 70% texture preservation.
  The blur filter returns this choice using material data it already sampled.
- Material masks 242 and 243 are unused in the inspected shader; they now
  identify Mirror stone and boosted metal. Mask 241 remains water. Reserved
  block IDs retain CU's solid/partial-shape parity.
- Block-state classification runs at shader loading only. The water-neighbour
  wrapper and its chunk-rebuild fluid reads have been removed. Faces, edges and
  corners touching water now use the same opaque stone path everywhere.
- The shader-properties fingerprint, parsed ID collision checks and unique
  source anchors gate the adapter. Unsupported shaders retain their normal
  stone rendering. All required shader stages must be recognised before block
  states are classified. Large spiral stairs retain their existing POM bridge.
- Glazing and two-way glass retain their approved textures and rendering path.
  In CU, window states have distinct material IDs for each stone finish;
  their coating is excluded from the inlay-metal adjustment. The existing glass specular sample identifies metallic
  coating pixels; only those receive a 90% reflection floor. Clear inner glass,
  normal glazing, stone frames and water retain their own handling. No second
  sample, surface or reflection pass is introduced.
  The master remains default-off and all preferences remain restart-bound.
  Off uses Honed stone in CU; individual preferences are preserved.

This introduces no extra terrain surfaces, reflection render, framebuffer,
sampler or texture lookup for the polish effect. It does add small shader
conditions and changes which stone enters the shader's existing reflection
work. Zero FPS cost is not established; profile the same large scene before
and after. Partial shapes still have the shader's approximate voxel outlines.

## Other shaders

The three-finish trial targets Complementary Unbound dev5 and both r5.9.3 styles.
The supplied Unbound/Reimagined r5.9.3 ZIPs differ only in their default style
line; their reflection stages are identical. The previous Bliss 2.1.2
polish adapter is paused; Bliss and other packs retain their native material
handling. Noble was never patched. The user reports missing overlays in both
Bliss and Noble with the old polish option enabled; that visual compatibility
issue is not claimed fixed by this trial.

Sildur's Vibrant 1.51 Extreme-VL does not sample terrain specular maps and uses
hard-coded categories instead. Distinguishing metal inlays from their stone
would require a shader feature change. It remains outside this inexpensive
adapter scope. See the review for candidate shader support and source evidence.

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
collisions, reload state, shader-mask isolation, separate metal/grout handling and
spiral predicate idempotence. A local optional test expands and preprocesses the
installed shader with Iris, applies the adapters and parses eighteen stages
across the Overworld, Nether and End, plus the low-sampler composite fallback
and three unchanged Bliss terrain fragments. It checks the actual tint consumer,
the final blend handoff and unchanged texture-fetch counts. Shader ZIPs are
declared test inputs so switching versions/styles cannot reuse stale results.
This is not GPU compilation or visual validation. Set `ERYDON_CU_TEST_SHADER`
and `ERYDON_BLISS_TEST_SHADER` to the installed shader ZIPs to run those tests.

Use HIGH_POLISH_TEST_CHECKLIST.md for the in-game comparison, including two
facing stone walls, overlay light blocking, patterned depth, water contact,
wide alcove crowns, shaders-off chunk movement and shader-on frame times.
