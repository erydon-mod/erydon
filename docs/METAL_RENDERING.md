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
the alpha channel of the existing material buffer.
The lookup also carries each sprite's mean metal albedo, so the new alloy color
replaces the metal contribution without blending the already mixed stone twice.

At close range the original pixel outline is retained. A shallow rounded bevel
fits inside its edges, including opposite edges of a one-pixel line. The added
bevel fades with the pixel footprint and is disabled with normal-map strength
zero. It never changes model geometry or POM height. Existing overlay corner
normals are tempered to avoid steep isolated glints.

At distance, shared overlay visibility uses CU's existing Bayer/TAA sequence and
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
`(0.72, 0.42, 0.16)` and silver `(0.95, 0.93, 0.88)`; these are rendering choices,
not a measurement of a particular alloy. Perceptual roughness has a 0.22 floor;
authored rougher metal and the 0.65 matte-cover fallback remain rougher.

Direct sun/moon highlights use a colored conductor lobe within CU's existing
lighting gates. The reflected color tends toward neutral at grazing angles.
Reflection filtering and history reject a different metal or adjacent ordinary
material, with a finite center-sample fallback for isolated thin detail. The final
texture-preservation term varies with roughness rather than imposing the earlier
high-contrast constant. The native low-sampler blend keeps its fallback.

Complementary still supplies the rays, visibility, lighting and color pipeline.
Block lights do not become physically traced point lights: their existing diffuse
lighting supplies the broad indirect proxy, and available reflected geometry
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
fragment coverage reads, but no new reflection rays, draw passes or geometry.
Two existing render buffers gain alpha storage; reflection-boundary checks reuse
their samplers. These costs need same-scene performance measurement.

`MetallicShaderPackTest` preprocesses installed packs through Iris, applies the
actual adapter order, and can compile/link through Iris's renderer transformations
and an invisible OpenGL context. Configure `ERYDON_CU_TEST_SHADER` for one archive,
or `ERYDON_CU_TEST_SHADER_DIR` for installed Complementary archives, plus
`ERYDON_CU_GL_VALIDATE=true` for driver checks. These are automated shader checks,
not visual acceptance. No Minecraft world or test JAR is required.

## Automated verification (27 September 2026)

The full Java run reported 350 tests: 349 passed, zero failures/errors and one
optional Bliss archive check skipped because that archive was not configured.
The numerical GPU test exercised the real packed lookup, one-pixel coverage,
opposed bevel slopes, authored albedo decoding and mixed stone/metal color.

All 348 metallic vertex/fragment program pairs compiled and linked through Iris
and the installed NVIDIA driver across Unbound r5.9 dev5, Unbound r5.9.3,
Reimagined r5.9.3 and Unbound r5.9.4 dev1. This includes all three dimensions,
POM on/off, anisotropic filtering 0/8, normal strength 0/120, TAA off, disabled
finish controls and the low-sampler profile. Compilation, overlay PBR validation
and the Mod Menu source audit also passed. The isolated Fabric/Iris launch probe
verified the real constructor order and Complementary-only sampler registration.
These checks do not establish visual quality or frame rate in a Minecraft scene.

Validation tasks: `compileJava test verifyErydonOverlayPbr
 auditErydonModMenuSources verifyHighPolishMixinLaunch`. No packaging task ran.

See [the visual checklist](HIGH_POLISH_TEST_CHECKLIST.md) for user comparisons.
The underlying channel meaning follows the
[LabPBR material standard](https://shaderlabs.org/wiki/LabPBR_Material_Standard).
