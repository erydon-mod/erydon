# Stone finish and metal review

Updated 2026-10-08. Honed, Polished and Mirror remain separate from metallic
materials. The new sprite-based metal treatment replaces the earlier 85%/20%
metal boost; it does not stack another boost on top. The approved stone finish
controls and 90% two-way coating retain their existing behavior. The follow-up
removes excessive lit-albedo preservation, corrects the reflection colour space
and restores authored metal normals. Shared overlays use recessed POM grooves
on the verified Complementary terrain path. This revised appearance and scene
performance await user testing; no test JAR was produced.
See [metal rendering](METAL_RENDERING.md) for the current implementation.

## Why plain stone can disappear from Noble reflections

The native, Collection 32x and Collection 64x Glacium CTM maps all contain
RGBA `(255, 0, 10, 255)`: maximum smoothness, but zero base reflectivity.
K/L/P use red 175, green 0, and blue 24/24/36 respectively. This was checked
across all 36 CTM tiles for each sampled family at each resolution.

The inspected installed `Noble-master.zip` reads green directly as F0 in
`programs/gbuffers/opaque.glsl` and returns without tracing a reflection when
F0 is zero in `programs/composite/reflections_pass.glsl:107`. Photon 1.3b instead
retains a default dielectric F0 of 0.02 when the sampled green is zero. CU and
Bliss have different native fallback handling. Thus pure red does not have a
consistent reflective meaning across these shaders.

The Collection Glacium herringbone-bronze and Calacattum/Portorium weave-bronze
maps contain green 255 on metal pixels, while stone is still green 0. That is
consistent with the reported pattern-only shine in Noble, but the exact viewed
pattern was not identified, so it does not prove which pixels the user saw.
The corresponding sampled native 16x patterned maps have green 0 throughout.

There is also an independent compatibility problem in the installed Noble ZIP:
its fog code declares `eyeBrightnessSmooth` as `vec2`; Iris provides `ivec2`.
The test session logged this mismatch followed by repeated OpenGL errors.
[Current upstream Noble](https://github.com/BelmuTM/Noble/blob/b82a5fb19f819330322bc31303139e2ef5b205fd/shaders/include/atmospherics/fog.glsl#L27)
uses `ivec2`. Retest an appropriate current release before introducing an ERYDON
workaround for that old shader error. Upstream was inspected, not installed.

## Implemented three-finish trial

Mod Menu offers all-stone presets and per-stone choices for all 27 families.
Herringbone, weave and inlays can inherit or select any of the three levels.
The master remains default-off; Off selects Honed in CU and disables the glass
extras. Existing true/on stone preferences migrate to Mirror; false/off migrate
to Honed. Saving still requires a full restart. English, German and Spanish
labels and swap aliases have been updated together.

| Finish | Smoothness input | Reflection adjustment |
| --- | --- | --- |
| Honed | Red 175/255, matching the old K/L/P maps | Ordinary CU dielectric response, with valid F0 of at least 10/255 |
| Polished | Red 1.0 | Ordinary CU dielectric response; 0% extra reflection boost |
| Mirror | Red 1.0 | 50% floor before smoothness weighting, rising toward grazing angles |

CU squares the red input when calculating smoothness. The adapter sets the
selected input before that calculation, using the specular sample CU already
read. A dull source map therefore does not weaken Polished or Mirror. The 50%
Mirror floor is an artistic starting point, not compensation for the old map.
These are material coefficients, not percentages of final screen colour.

The K/L/P image replacement has been removed. Every finish uses the same
original specular texture at native 16x, Collection 32x or Collection 64x; no
PNG decoding, generated polished texture or per-resolution alternate is needed.
The adapter changes only eligible stone pixels' sampled red/green values. Blue,
alpha, normal/height maps, metal pixels and low-red grout are preserved. Aged,
hewn, ashlar, rusticated, rock and Diaphanes stone stay outside this change.
The tiny generated resource pack now supplies command labels only.

For this CU-first trial the PNG files themselves are unchanged. Native material
behaviour in other shaders is therefore unchanged by these finish controls;
future cross-shader work can address their different F0 handling separately.

## Glazing reflections

The glazing toggle already supplies maximum-smoothness specular maps. Increasing
red beyond 255 cannot increase shine. In the inspected Complementary Unbound
r5.9.4 dev1, the translucent surface blend also multiplies reflection by pane
opacity. Crystal's authored alpha of 20/255 reduces its frontal reflection to
about 1.18%, despite maximum smoothness; the other three glazing finishes use
150/255. The Collection packs inherit these native glazing textures.

Following visual approval, glazing uses the fixed strong response: a 50% frontal
reflection coefficient, rising toward grazing angles. This is an artistic
coated-glass treatment; the experimental strength selector and its saved property
have been removed. Settings are captured at launch and require a full restart.
The existing glazing toggle still disables the adjustment independently of the
two-way mirror coating.

The supported Complementary adapter reuses the existing translucent reflection
pass and its one specular sample. With authored opacity `a` and angle-dependent
reflection `R`, surface compositing uses `a' = a * (1 - R) + R` and a reflection
blend of `R / a'`. The resulting reflection contribution is `R`, tint is
`a * (1 - R)`, and transmitted background is `(1 - a) * (1 - R)`. Crystal therefore
still transmits about 46% of the background frontally.
CU's volumetric tint is calculated before this compositing-alpha adjustment,
and its existing fog and world-space reflection attenuation remain in place.
Reflection is capped below one to avoid CU's exact-one history rejection.

Only partially transparent, smooth dielectric panes in ERYDON glazing and window
families receive the adjustment. Zero-alpha edges, opaque lead/stone frames,
metal coatings and other mods' glass retain their existing response. No albedo,
normal map, height map, shader ZIP or Collection pack is rewritten. Other shader
packs retain their native material behavior. This adds no reflection ray, texture
sample, draw pass or per-frame Java decision; same-scene appearance and performance
still need in-game review.

Validation on 2026-10-08 includes the three-language Mod Menu audit and the real
Fabric/Iris mixin launch probe. The installed Unbound r5.9.4 dev1 is checked through
Iris transformation and GPU compile/link across dimensions and POM modes, with
the metal adapter also active. GPU blend readback covers crystal/colored glass at
different angles, unaffected edges, frames, coatings and the reflection-history
storage limit. The user approved the strong treatment in-game. These checks do
not establish a measured same-scene FPS result.

The angle response follows the dielectric Fresnel principle described in
[Physically Based Rendering](https://www.pbr-book.org/4ed/Reflection_Models/Dielectric_BSDF).
The native maps remain non-metallic under the
[LabPBR material standard](https://shaderlabs.org/wiki/LabPBR_Material_Standard).

## Current metal treatment

### Cover finish enum

Cover blocks select their reflection treatment through the existing `finish`
property. With supported Complementary shaders, Gloss uses Mirror stone's
perfect smoothness and 50% frontal reflection response. Silver + Gloss instead
uses the outward two-way window coating's 90% reflection floor and neutral
reflection blend. This is opaque mirror cladding: the cover remains opaque.

The treatment belongs to actual CoverBlock Gloss states, including lit covers,
and is independent of the stone, glazing and two-way-glass preferences. Gloss
coffered-ceiling insets use the same treatment; Matte retains its existing material. No texture,
model, geometry, CTM rule or Collection pack changes are involved. Shader state
classification runs at shader load; changing the placed finish uses the existing
block-state update. Other shader packs retain native PBR.

The ceiling panels are identified by exact Gloss cover sprites in the existing
atlas metadata lookup. Their surrounding stone and cornices retain the selected
stone finish. These panel markers carry no metal coverage or conductor alloy.

Dedicated partial-block material IDs 12059/12061 preserve voxel-lighting rules.
Matte covers use geometry-only ID 12063. An unmapped thin cover previously entered
CU's world reflection map as a full cube at its owner cell, producing a false
central square on the 3x3 metal panel. The odd ID excludes that phantom cube;
metal coverage, roughness 166/255, alloy colour and soft direct-light sheen are
unchanged. White and Black matte covers receive the same thin geometry classification.
Opaque mask 242 carries ordinary Gloss and 245 carries Silver + Gloss; the
sprite-metal adapter leaves these explicit finishes alone. Authored emission is
evaluated before the cover override. Mirror and Silver reflections are capped at .99 to
survive CU's signed-normalized history storage. The normal composite path reuses
its material read to distinguish the Silver coating from native opaque albedo
preservation. CU's restricted low-sampler composite path keeps its original
sampler budget and texture preservation, so Silver can retain more lit base
colour there. This reuses the existing metadata lookup and surface samples,
without new reflection rays or draw passes.

Validation on 2026-10-09 passed the installed Unbound r5.9.4 dev1 shader matrix,
including GPU compile/link, opaque reflection blend readback, emission retention
and signed-normalized grazing storage. The real Fabric/Iris launch probe checks
4,608 Gloss and 4,608 Matte states (3,072 lit in each finish), foreign states, ceiling framework mappings,
all optional finish controls off, collision fallbacks and unchanged vertex light
bytes. Native resources and both current Collection resolutions use the same
uniform cover textures; no cover CTM rule or patterned texture phase is involved.
The user approved the Gloss appearance and Matte correction. The Matte geometry correction also
executes the installed CU voxelizer on the GPU across all six normals, proving
unmapped/even IDs write a full cube while all three cover IDs skip it. The soft
sheen and square removal remain covered by the client regression checklist.

The shared metal adapter identifies actual bronze and silver sprites after atlas
upload, independently of the block's stone finish or light-emission ID. It covers
connected overlays, embedded weave/herringbone, covers, light-fitting metal and
family bronze decoration. Tinted glass and the two-way mirror coating are excluded.

A compact lookup carries exact sprite bounds, authored roughness and an independent
linear coverage pyramid. This prevents thin metal from becoming dielectric when
Iris averages the labPBR green channel. Shared overlay alpha is tested against
filtered coverage rather than dropping every sample below 50%; the existing
outline is retained. Supported shared overlays now combine the actual stone
substrate and metal in a single recessed POM surface; unsupported render paths
retain the flush overlay. Embedded patterns keep their authored POM normals.

Colored conductor Fresnel, finite roughness and material-aware reflection history
replace the previous high-contrast metal boost. Ordinary stone is unchanged at
zero metal coverage. Mixed pixels retain the selected underlying finish. Material
mask 242 remains Mirror stone/Gloss covers, 243 is bronze and 244 silver; Silver
Gloss covers use mask 245 and the window coating keeps its separate path.
The metal adapter preflights all eight affected programs
before enabling a dimension, including the existing buffer-format declarations.

Native 16x bronze pattern masks can be recovered from their exact grout counterpart
only when the original all-red specular placeholder and authored bronze colors
match. Explicit pack masks take priority. No PNG, resource pack, model, CTM rule,
height map or installed shader archive is rewritten.

The finish controls continue to require terrain, deferred and water support.
Metal compatibility has its own atomic preflight; changing a stone preference does
not change the metal roughness or alloy response. Other shaders retain native PBR.
The earlier visual approval applies to the superseded treatment, not this revision.

On 2026-09-24 the user confirmed that disabling POM restores missing overlays
in both Bliss and Noble; Noble's AO switch alone did not restore them. All
visible metal texels had height 250/255, below the uncut stone backing. Both
inspected packs write this recessed POM depth, so the separate overlay can fail
the depth test against the stone. This is independent of their polish adapters:
Bliss's previous polish patch remains disabled, and Noble was never patched.

The shared overlay generator now writes surface height 255 throughout each
overlay normal map, retaining the exact RGB bevel normals, material AO, albedo,
specular maps and dimensions. Underlying stone and embedded herringbone/weave
heightmaps are unchanged. ERYDON and Themelios 1.20.1 ship the same corrected
maps; Collection 32x/64x deliberately inherit these native overlay images.
That height-map correction did not change the Java render path, shader source,
surface offset, sample or pass. The later metal adapter is documented separately.
The existing high-polish offset is retained to avoid reintroducing flicker.

`verifyErydonOverlayPbr` checks all 376 tile companions and the bevel-normal
orientation. The correction is in the project itself. An older overlay test
pack was overriding 368 corrected maps in IDEA; that exact pack was deselected
while the development client was closed. Visual confirmation with POM enabled
remains required for Bliss, Noble and the previously approved Complementary
treatment; no override pack is required.

## Circular columns and material parity

The inspected Complementary world-space reflection voxelizer discards ordinary
odd-numbered partial-block material IDs. Circular columns therefore remain
absent even though their visible surface uses the correct opaque finish.
The approved workaround assigns four dedicated odd column IDs and admits
only those IDs through the existing reflection voxelizer's solid-block filter.
Their lighting classification remains odd, and the visible model is unchanged.
Reflections use the shader's square-block approximation, not curved geometry.

The adapter changes only recognised source with a unique filter anchor and
falls back to the existing behaviour otherwise. It adds no texture sample,
draw pass, reflection ray or Java frame callback. The existing voxel writes
now include columns previously skipped, so this is not a measured promise of
zero GPU cost. The installed Unbound r5.9.4 dev1 source and recognised
Reimagined r5.9.3 source pass the focused parser checks; visual confirmation
of the column approximation is still required.

The plain-versus-trim Imperium discrepancy was a cross-mod classification gap.
The user confirmed that the dark plain block was `themelios:imperium_block`,
while the trim was ERYDON. The Themelios model references the same ERYDON stone
texture, but both the material selector and Iris state-map hook previously
excluded every non-ERYDON namespace. Changing position or POM could therefore
not resolve the finish mismatch.

The selector now accepts the 27 shared stone families in ERYDON, Themelios and
Daedalon. Their stone and pattern preferences use the same existing controls.
Aged, ashlar, hewn, rusticated, rock and Diaphanes remain excluded; pure bronze
sculptures are not stone. ERYDON's special window, circular-column and spiral
handling remains scoped to its own namespace. Daedalon's existing panel hook
preserves explicit material IDs, so it does not overwrite these assignments.
No companion-mod source change or dependency is required.

This only extends the existing shader-load classification to additional block
states; it introduces no extra texture, draw pass, reflection ray or per-frame
Java work. The original native and Collection specular maps remain in use.
Cross-mod Honed/Polished/Mirror visual comparisons are still required.

Validation passed: compilation, 29 focused material/settings/shader tests,
the three-language Mod Menu source audit and the real Fabric/Iris mixin
launch probe. The companion source catalogues contain all 27 shared materials;
1,401 Themelios and 2,133 Daedalon blockstate files qualify for the controls.
No test JAR was produced.

## Shader support scope

The following is a candidate test matrix from Modrinth's download-sorted shader
catalogue on the investigation date, not a claim of tested support for all ten.
Ordering is platform-specific. PBR capability and ERYDON's configurable polish
adapter are separate capabilities.

| Candidate | Evidence and current ERYDON status |
| --- | --- |
| Complementary Reimagined | r5.9.3 is recognised with labPBR. The current metal revision needs user visual testing. |
| Complementary Unbound | r5.9 dev5, r5.9.3 and the inspected r5.9.4 dev1 are recognised with labPBR. The current metal revision needs user visual testing. |
| BSL | Official project advertises PBR; no ERYDON polish adapter or local visual validation in this review. |
| Photon | Installed 1.3b reads labPBR and the user reports good baseline rendering; current polish menu does not add the CU/Bliss reflection boost. |
| Solas | Official project documents labPBR/oldPBR and requires PBR Resourcepack enabled; additional adapter/testing needed. |
| Bliss | Previous 2.1.2 adapter is paused; native specular materials remain. POM-off comparison confirmed the missing-overlay cause; corrected overlay maps await visual validation with POM on. |
| Rethinking Voxels | Inspected upstream has the Complementary-derived custom-PBR path; requires its own release validation. |
| MakeUp Ultra Fast | Inspected upstream contains no normal/specular-map samplers or labPBR path. Matching these material controls would require a larger shader feature, outside a cheap compatibility patch. |
| Super Duper Vanilla | Inspected upstream reads both maps and offers Resource PBR; explicitly requires current labPBR. No ERYDON polish adapter yet. |
| Insanity | Official project calls for Advanced Materials under Materials and Reflections; additional adapter/testing needed. |

Also include Noble and Sildur's because the user tests them. The installed
Sildur's Vibrant 1.51 Extreme-VL does not sample terrain specular maps and
uses hard-coded categories instead. It cannot pick out an inlay from stone
without a shader feature change. Do not advertise identical finishes on every
shader, or treat a good-looking baseline as proof that Mod Menu changes apply.

Primary sources: [Modrinth download catalogue](https://modrinth.com/shaders?s=downloads),
[Photon](https://github.com/sixthsurge/photon),
[Solas](https://modrinth.com/shader/solas-shader),
[BSL](https://modrinth.com/shader/bsl-shaders),
[Insanity](https://modrinth.com/shader/insanity-shader),
[Rethinking Voxels](https://github.com/gri573/rethinking-voxels/tree/1fd788f2c897169b7fc3375f0cb77526a2e65dc9),
[Super Duper Vanilla](https://github.com/Eldeston/Super-Duper-Vanilla/tree/1ca20aa4dc47fa750888e523a9325ba558478583),
[MakeUp](https://github.com/javiergcim/MakeUpUltraFast/tree/13931cf0acef7113c055326d2eb7cb9da44d714e).

## Performance boundary

Keep stone opaque and reuse existing reflection passes. The metal lookup adds a
custom sampler, bounded coverage reads and material-boundary filtering; it does
not add reflection rays or draw passes. Resolve
settings at load time; do not restore water-neighbour scans, translucent stone,
duplicate faces or per-frame Java decisions. The finish trial adds bounded material conditions and changes
coefficients in the existing shader work. It still needs same-scene GPU comparisons: some shaders spend more work on rough reflections,
and improved compatibility can enable reflections that were previously skipped.
Do not equate no extra render pass with a measured guarantee of zero FPS cost.

## Historical validation of the three-finish trial

Compilation, all 265 Java tests, the three-language Mod Menu source audit
and the isolated Fabric/Iris startup probe passed on 2026-09-20. The installed
Unbound r5.9 dev5 and Unbound/Reimagined r5.9.3 archives each passed the focused
source checks: 18 expanded, preprocessed and parsed stages across all three
dimensions, plus the macOS/Distant Horizons low-sampler fallback. The tests
verify tint reaches the final reflection, the metal blend reuses an existing
material sample, and the inspected Bliss source stays unchanged. No test JAR
was built.

These checks do not establish GPU performance or the final appearance. Use
HIGH_POLISH_TEST_CHECKLIST.md for the restart-based CU comparisons before
expanding support to other shaders.
