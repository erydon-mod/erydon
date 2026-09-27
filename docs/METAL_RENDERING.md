# Complementary metal rendering

Implemented 2026-09-27. Visual acceptance and scene performance remain user tests.
This is a shared material change in ERYDON, with no replacement texture pack.

## Separation from stone finishes

Honed, Polished and Mirror still control the underlying stone, including pattern
overrides and the existing restart requirement. Mirror's 50% reflection floor and
the two-way window coating's 90% target retain their existing paths. The old metal
85% floor, albedo-normalised tint and fixed 20% texture preservation are removed
from `HighPolishShaderAdapter`; the metal response does not stack on them.

Metal is selected by the actual stitched sprite, rather than a block-state ID.
This includes shared overlays, embedded bronze patterns, glossy/matte covers,
metal light fittings and family bronze decoration. Fixture emission and different
alloys on one model therefore keep their identities. Bronze/silver tinted glass
and the two-way coating are not generic metal. Unknown alloys remain native.
The dark lead bars in glazing have no authored metal PBR map and keep their
existing dark surface; they are not recolored or assigned silver reflectance.

## Fine detail

Iris can average a green-255 metal texel with green-0 stone until Complementary
interprets it as a dielectric. Metal coverage is now stored separately from that
categorical channel, with an area-averaged mip pyramid and bounded anisotropic
filtering. Exact sprite bounds and final CTM-POM coordinates prevent reading the
wrong connected tile. Coverage and the selected underlying finish travel through
the alpha channel of the existing material buffer. Its 16-bit payload also stores
metal roughness independently: six coverage bits, two finish bits and eight
roughness bits. A mixed pixel must not derive alloy roughness from the selected
stone finish. Coverage and roughness are quantized before mixing so the later
passes use the same values.
The lookup also carries each sprite's mean metal albedo, so the new alloy color
replaces the metal contribution without blending the already mixed stone twice.

At close range the original pixel outline is retained. Supported shared overlays
use a composite POM surface: the surrounding stone forms the groove walls and
the metal sits below it. The underlying stone's connected tile is carried through
Iris's existing block render-type short; the material/finish ID stays untouched.
The shader resolves that tile through the existing CTM lookup, including phase
changes where a ray crosses a connected tile. There is no replacement height PNG.

The payload is limited to the verified Iris/Sodium/Indium encoder and synchronous
terrain emitter. Invalid records, unavailable POM/normal mapping, unsupported UV layouts or an
unsupported renderer retain the flush overlay. The source adapter and successful
terrain compilation must both enable the recessed path before chunk building
can emit its payload. The original base surface remains the fallback.

The groove depth is 0.25 of a 64x texel at CU POM depth 1, scaled by that setting
and faded with distance and pixel footprint. Its cavity ray walks at most 24
mask cells; authored substrate relief uses at most 32 steps and five refinements.
When a cavity ray leaves the known overlay tile, it retains the opening's floor
without further displacement rather than inventing a wall or a neighboring
47-tile overlay. This is an explicit seam approximation, not neighboring overlay
lookup. Unsupported nonorthogonal CTM phase directions retain the current phase.
The original four-sample substrate POM shadow is retained, and visible metal
floors test whether the groove opening admits the directional light. These
local shadows affect the existing shadow multiplier, not emission or ambient light.

Embedded weave/herringbone and standalone metal keep their authored normal maps
and POM sidewall normals. They no longer receive the overlay-only normal reduction.
The existing small overlay bevel remains available on the flush fallback and is
disabled with normal-map strength zero.

At distance, flush overlay visibility uses CU's existing Bayer/TAA sequence and
filtered coverage instead of a fixed 50% alpha threshold. This avoids systematically
discarding thin strokes, but possible motion shimmer needs in-game assessment.
Embedded patterns mix the metallic and underlying stone responses continuously.

Native 16x bronze weave/herringbone have older, nonmetallic specular placeholders.
Only those exact placeholders can use an explicit bronze-versus-grout pair
comparison, guarded by the two exact authored bronze colors. Changed stone pixels,
missing pairs, other sizes or explicit pack metal masks do not use this recovery.
The complete native catalogue is covered by a data test.

## Light and reflections

Bronze and silver use separate colored Schlick conductor Fresnel responses and
finite GGX roughness. The shared linear RGB F0 starting points are bronze
`(0.92, 0.41262, 0.0)` and silver `(0.95, 0.93, 0.88)`. Bronze preserves the
linear colour ratios of the shared authored sRGB gold `(239, 166, 0)`, scaled to
the existing 0.92 peak reflectance. The earlier `(0.92, 0.70, 0.30)` override
introduced blue and excess green, turning reflected gold into pale cream.
This is one shared alloy palette, not a per-texture adjustment or a measured
physical bronze alloy. Direct lighting and opaque/translucent reflections use
the same palette; grazing Fresnel can still approach white. Resource packs keep
their artwork and wear, but do not supply a separate per-pixel deferred F0.
Reflected roughness preserves the authored
polish with a minimum of 2/255; authored rougher metal and the 0.65 matte-cover
fallback remain rougher. Direct sun/moon highlights use a separate finite 0.12
roughness minimum so tiny highlights do not require point-sized sampling.

Direct sun/moon highlights use a colored conductor lobe within CU's existing
lighting gates. The reflected color tends toward neutral at grazing angles.
Linear reflectance coefficients are encoded before multiplication into CU's
encoded scene RGB; the final metal reflection is added in linear light. This
avoids applying gamma twice to alloy colour or replacing absorbed light with
brightly lit albedo. Direct highlights are retained separately from the broad
metal illumination approximation, whose weight is the greater of perceptual
roughness squared and 0.06. A previous 0.22 roughness floor forced even fully
polished metal to CU smoothness 0.603: CU then applied nearly four times Mirror's
spatial blur radius, rough reflection normals and blurred reflection mip levels.
Raising the broad-light share to 0.30 hid this problem behind a milky base.
Authored polish now reaches CU's sharp-reflection range and both reflection paths
reserve 94% for resolved reflections on polished metal. The small remaining
lighting-dependent contribution vanishes at zero illumination. Matte materials
keep their roughness and lighting split.
Fractional stone/metal pixels recover the base smoothness and evaluate CU's
active SSR/WSR Fresnel curve, instead of merely undoing its final multiplier.
Only an actually applied Mirror mask selects the Mirror reflection floor.
The unknown substrate reflectivity and shared base-colour attenuation remain
bounded approximations; pure material endpoints are exact for this model.
Later CU rain/snow changes remain native; this is not a separate layered wet-metal
model. Zero-coverage formulas are unchanged, although G6 now stores native RGB
values with higher precision rather than reproducing their previous byte rounding.

Reflection filtering and history reject a different metal or adjacent ordinary
material, with a finite center-sample fallback for isolated thin detail. The
stored reflection coefficient is capped below CU's invalid-value sentinel after
RGBA8_SNORM quantization. The native low-sampler blend keeps its fallback.

Complementary still supplies the rays, visibility, lighting and color pipeline.
Block lights do not become physically traced point lights: their existing diffuse
lighting supplies the broad proxy above, and available reflected geometry
supplies detail. This is not a full spectral or energy-conserving renderer. A dark
room can legitimately have dark reflections; there is no emission or brightness
floor added to make metal glow.

## Compatibility and cost

Only recognized Complementary properties fingerprints and matching labPBR source
are eligible. All eight affected programs are preflighted together for each Iris
ProgramSet before source modification. Unknown or incomplete source remains
native. The adapter does not edit installed shader archives or global resource
pack format declarations. Existing CTM, shape and light-material IDs remain.

The nearest RGBA8 lookup is rebuilt once after atlas upload, deduplicates coverage
masks, omits uniform masks, and has a 32 MiB maximum. Unsupported/animated sprite
layouts retain native rendering. It adds vertex metadata reads and bounded
fragment coverage reads. Recessed overlays add bounded local height and light
visibility traces, but
no new reflection rays, draw passes, geometry, vertex stride or GPU texture.
The existing G6 material buffer is now RGBA16 UNORM, adding four bytes per pixel
per attachment compared with the previous RGBA8 revision (about 31.6 MiB per
4K attachment; a pair of full-size attachments doubles that). The existing G1
reflection-history buffer keeps RGBA8_SNORM. Reflection-boundary checks reuse
their samplers. These costs need same-scene performance measurement.

`MetallicShaderPackTest` preprocesses installed packs through Iris, applies the
actual adapter order, and can compile/link through Iris's renderer transformations
and an invisible OpenGL context. Configure `ERYDON_CU_TEST_SHADER` for one archive,
or `ERYDON_CU_TEST_SHADER_DIR` for installed Complementary archives, plus
`ERYDON_CU_GL_VALIDATE=true` for driver checks. These are automated shader checks,
not visual acceptance. No Minecraft world or test JAR is required.

## Verification (27 September 2026)

Before the golden-bronze illumination tuning, the full Java run reported
363 tests: 362 passed, zero failures/errors and one
optional Bliss archive check skipped because that archive was not configured.
The numerical GPU tests exercised the real packed lookup, one-pixel coverage,
authored albedo decoding, mixed stone/metal color, recessed floors and stone
walls, directional cavity shadows, and displacement over nonflat substrate.
Groove visibility and shadows were checked at 16x, 32x and 64x. The actual
RGBA16 material and SNORM reflection targets carried coverage, finish and
independent metal roughness through production shader slices. SSR and WSR
curves, all stone finishes, ineligible Mirror, fractional coverage, bright/dark
reflected surroundings and preservation of direct lighting were exercised.

All 444 metallic and 204 stone-finish vertex/fragment program pairs compiled and
linked through Iris and the installed NVIDIA driver across Unbound r5.9 dev5,
Unbound r5.9.3, Reimagined r5.9.3 and Unbound r5.9.4 dev1. This includes all three
dimensions, POM on/off, anisotropic filtering 0/8, normal strength 0/120/200,
TAA off, disabled finish controls and the low-sampler profile. Compilation,
overlay PBR validation and the Mod Menu source audit also passed. The isolated
Fabric/Iris launch probe verified the real constructor order, Complementary-only
sampler registration, and the actual woven Iris encoder: only the intended
substrate shorts changed, allocation guards and other vertex data survived, and
the next ordinary quad retained its native values.

A separate targeted test compiled and linked all eight complete adapted programs
for Unbound r5.9.4 dev1's Overworld with world-space reflections, player
reflections and coloured lighting enabled. That profile used AF8, normal strength
200, POM depth 2, quality 512 and distance 1024. It exercises the WSR resource
declarations and translucent reflection replacement in addition to the numerical
shader slices. Iris supplies the test's actual render-stage definitions.
These checks do not establish visual quality or frame rate in a Minecraft scene.

The subsequent golden-bronze illumination tuning passed 14 focused tests with
zero failures or skips, including the actual GPU material pipeline, one-pixel
and recessed-surface fixtures, and all eight full dev1 programs with the WSR
profile above. Tests check retained illumination at normal incidence with dark
reflections, zero-light behavior and the independent stone finish. The user has
approved the groove appearance but rejected the resulting milky metallic finish.

The reflection-sharpness correction passed 19 focused tests with zero failures
or skips, including all eight complete dev1 programs under the same active WSR
profile. GPU checks verify that authored polished metal reaches smoothness above
0.97 independently of the stone finish, retains coloured reflections and keeps
only a small light-dependent fill. The user confirmed that metal reflections are
now visible; their Noble/Complementary/Bliss comparison exposed the remaining
pale bronze colour mismatch addressed by the shared palette above.

The shared bronze palette correction also passed those 19 focused tests with
zero failures or skips. GPU assertions now compare the authored gold hue against
both surface colour and the completed deferred reflection, including fractional
coverage and the independent stone finishes. All eight complete dev1 programs
compile under the active WSR profile. Visual approval remains with the user.

Validation tasks: `compileJava test verifyErydonOverlayPbr
 auditErydonModMenuSources verifyHighPolishMixinLaunch`. No packaging task ran.

See [the visual checklist](HIGH_POLISH_TEST_CHECKLIST.md) for user comparisons.
The underlying channel meaning follows the
[LabPBR material standard](https://shaderlabs.org/wiki/LabPBR_Material_Standard).
