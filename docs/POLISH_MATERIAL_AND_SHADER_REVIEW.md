# Stone finish and metal review

Updated 2026-09-25. Honed, Polished and Mirror are now implemented for the
exact Complementary Unbound r5.9 dev5 and Unbound/Reimagined r5.9.3 labPBR
profiles. Other shader adapters are paused for this trial. The stone Mirror
finish, 90% two-way glass coating and stronger metal blend were visually approved,
as was the latest shader-enabled performance trial. No test JAR was produced.

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

## Implemented metal trial

All visible pixels in the eight shared in-world overlay families (four motifs,
bronze/silver, 47 tiles per family) already use specular `(255, 255, 0, 255)`.
Increasing red alone cannot polish them further. In the inspected CU source,
changing green 255 to the standard silver code would actually lower its
reflection weighting, so that earlier proposal was not applied.

CU tags metallic pixels on the eligible ERYDON stone families separately
and gives them an 85% reflection floor, weighted by their existing smoothness.
The initial trial assigned reflection tint in `deferred1`, where the variable
does not reach the output. The corrected adapter restores the albedo-normalised
tint in `composite`, immediately before it multiplies the traced reflection.
Bronze therefore keeps its warm tint and silver stays neutral.

CU's final solid blend also preserves 70% of the base texture wherever the
reflection is darker than that texture. This reduces an 85% reflection weight
to 25.5% effective influence for those colour channels. In `composite1`, only
tagged metal now uses 20% preservation, making that influence 68%. These are
blend coefficients before subsequent fog/tonemapping, not measured brightness.
The approved stone and two-way glass blends are unchanged.

The material tag comes from the blur filter's existing `colortex6` fetch and is
returned through an output parameter; no texture fetch, filtering loop, ray,
framebuffer or draw pass is added. CU's macOS/Distant Horizons low-sampler
profile omits that fetch, so it retains native preservation rather than adding
a sampler. The tint correction still applies there.

This includes overlay and embedded pattern metal when
the active texture identifies it as metal. There is no extra setting; the
adjustment is independent of Honed/Polished/Mirror and remains active in CU when
the stone master is off. Native flat/non-metallic pattern maps are not invented
or reclassified as metal.

Material mask 242 is reserved for Mirror stone and 243 for this metal trial;
241 remains water. Their branches reuse the existing deferred reflection pass.
The window coating has its separate existing handling and is unchanged.
Readiness now requires the terrain, deferred, water, composite and composite1
stages before assigning ERYDON material IDs.

The supplied Unbound and Reimagined r5.9.3 ZIPs differ in exactly one line:
the default `SHADER_STYLE` (4 versus 1). All reflection/PBR code is identical.
Their shared properties fingerprint is recognised alongside dev5, for both
the polish and CTM-POM adapters. Unknown releases still require review.

The user approved the stronger metal treatment and both Complementary styles.
The latest high-polish build's shader-enabled performance was also approved.

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
No Java render path, shader source, surface offset, sample or pass was changed.
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
| Complementary Reimagined | r5.9.3 is recognised and source-validated with labPBR; user approved the in-game appearance. Recheck the overlay depth correction. |
| Complementary Unbound | r5.9 dev5 and r5.9.3 are recognised with labPBR; user approved the stronger metallic blend. Recheck the overlay depth correction. |
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

Keep stone opaque and reuse existing material samples/reflection passes. Resolve
settings at load time; do not restore water-neighbour scans, translucent stone,
duplicate faces or per-frame Java decisions. The finish trial adds bounded material conditions and changes
coefficients in the existing shader work. It still needs same-scene GPU comparisons: some shaders spend more work on rough reflections,
and improved compatibility can enable reflections that were previously skipped.
Do not equate no extra render pass with a measured guarantee of zero FPS cost.

## Validation of the three-finish trial

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
