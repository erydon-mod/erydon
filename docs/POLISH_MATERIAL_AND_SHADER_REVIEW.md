# Stone finish and metal review

Investigated 2026-09-19. The implemented change from this review is the existing
High polish stone reflection floor increasing from 25% to 35% in the supported
CU and Bliss profiles. Compilation and all 20 targeted HighPolish tests passed,
including source checks against the installed shader packs. The approved CU
two-way coating remains at 90%. No test JAR was produced.

The three-finish menu, revised baseline maps, metal enhancement and additional
shader adapters below are proposals, not implemented features.

## Why plain stone can disappear from Noble reflections

The native, Collection 32x and Collection 64x Glacium CTM maps all contain
RGBA `(255, 0, 10, 255)`: maximum smoothness, but zero base reflectivity.
K/L/P use red 175, green 0, and blue 24/24/36 respectively. This was checked
across all 36 CTM tiles for each sampled family at each resolution.

The inspected installed `Noble-master.zip` reads green directly as F0 in
`programs/gbuffers/opaque.glsl` and returns without tracing a reflection when
F0 is zero in `programs/composite/reflections_pass.glsl:107`. Photon 1.3b instead
retains a default dielectric F0 of 0.02 when the sampled green is zero. CU and
Bliss have their own fallback/adapter handling. Thus pure red does not have a
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

## Recommended finish design

Use the same three choices globally and per stone, with Inherit for pattern and
inlay overrides. Keep the master switch and separate glass controls. Provisional
artistic targets are:

| Finish | Smoothness | Base reflection target |
| --- | --- | --- |
| Honed | Existing K/L/P red 175 | A valid dielectric baseline, initially about 4% |
| Polished | Full smoothness | Standard dielectric baseline; no extra reflection boost |
| Mirror | Full smoothness | 35%, as requested for the strongest stone setting |

These are material targets, not percentages of the final screen colour. Light,
viewing angle and shader implementation still affect the result. Stone Mirror
would be a stronger stone finish; the separate two-way glass coating stays at
its approved 90% target.

Polished intentionally adds no reflection boost: full smoothness already makes
its reflections sharper than Honed. This does not mean zero base reflectivity;
both need a valid dielectric baseline for consistent shader compatibility.

Correct the baseline maps to encode a nonzero dielectric F0, rather than copying
the old K/L/P colour wholesale. The [labPBR standard](https://shaderlabs.org/wiki/LabPBR_Material_Standard)
assigns smoothness to red and base reflectivity to green. Green 10 is about 4%;
blue and alpha have separate porosity/scattering and emission meanings.
Preserve those channels, metal pixels, grout masks and all normal/height maps.
Apply the same policy to native 16x and the actual Collection 32x/64x sources,
including both CTM and fallback sprites. Do not resize or overwrite pack artwork.

The existing reload-only image override is a useful basis, but currently covers
only K/L/P plain/herringbone and still writes green zero. A broader solution
needs explicit material/mask coverage. Shared sprites can be used by several
block families, so resource replacement alone cannot promise independent
per-shape choices. Preserve the existing per-block shader classification where
needed, and audit shared aliases before changing a baseline texture. Keep aged,
hewn and other intentionally rough finishes outside the smooth-stone scope.

Migrate saved boolean choices explicitly and update all three languages and
swap labels together; retain command aliases. The user-facing default should
be Honed only when this proposed baseline change is implemented. It is not the
current behaviour of every stone with the master off.

## Metal: improve reflectivity, not only smoothness

All visible pixels in the eight shared in-world overlay families (four motifs,
bronze/silver, 47 tiles per family) already use specular `(255, 255, 0, 255)`.
The slight red-252 differences in some old composite block sprites therefore
do not explain the in-world overlay response. Collection release packs defer
these shared overlay textures to the mods.

Green 255 asks labPBR shaders to derive metal reflection colour from albedo.
The sampled silver colour averages 206-212 per channel, which is substantially
below polished silver reflectivity once interpreted in linear colour space.
The bronze colours have almost no blue, so increasing white reflection blindly
would change their intended warm appearance.

Trial the standard silver material code (green 237) on silver specular pixels,
keeping the existing albedo/normal detail. For bronze, tune a conductor response
that preserves its tint; do not silently substitute pure copper or gold, neither
of which is bronze. Start with one silver and one bronze comparison under CU,
Bliss, Photon and Noble before rolling out across overlays and embedded inlays.
No additional Mod Menu toggle is needed for the proposed metal improvement.

## Shader support scope

The following is a candidate test matrix from Modrinth's download-sorted shader
catalogue on the investigation date, not a claim of tested support for all ten.
Ordering is platform-specific. PBR capability and ERYDON's configurable polish
adapter are separate capabilities.

| Candidate | Evidence and current ERYDON status |
| --- | --- |
| Complementary Reimagined | Shares the Complementary codebase; its selected release still needs fingerprint, stage and visual validation. |
| Complementary Unbound | Existing adapter targets r5.9 dev5 with labPBR; user confirmed stone/mirror visuals before the 35% adjustment. |
| BSL | Official project advertises PBR; no ERYDON polish adapter or local visual validation in this review. |
| Photon | Installed 1.3b reads labPBR and the user reports good baseline rendering; current polish menu does not add the CU/Bliss reflection boost. |
| Solas | Official project documents labPBR/oldPBR and requires PBR Resourcepack enabled; additional adapter/testing needed. |
| Bliss | Existing adapter targets 2.1.2; Specular Reflections must be enabled. Enabled-polish visual comparison remains outstanding. |
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
duplicate faces or per-frame Java decisions. The 25%-to-35% patch changes only
coefficients in the existing shader work. Future material changes still need
same-scene GPU comparisons: some shaders spend more work on rough reflections,
and improved compatibility can enable reflections that were previously skipped.
Do not equate no extra render pass with a measured guarantee of zero FPS cost.
