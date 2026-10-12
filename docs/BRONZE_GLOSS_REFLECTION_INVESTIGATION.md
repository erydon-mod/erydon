# Bronze Gloss reflection investigation

Initially investigated on 2026-10-09; the authorized revision is recorded below.
The matched diagnostic captures for changelog entry
`2.0.0-049` show brighter Bronze Gloss with less visible reflected marble detail
in 1.5.32 than in 1.5.30. The source and installed binaries establish a change
of reflection treatment. This is consistent with the captures; it does not
measure the contribution of every shader stage to the complete scene.

## Implementation history

Commit `4d02ae7c9fede0561be6aea9cf292bab4174dcd7`, dated 2026-10-09 at
06:32:53 British Summer Time, added the hard-coded cover Gloss treatment.
The [cover finish specification](POLISH_MATERIAL_AND_SHADER_REVIEW.md#cover-finish-enum)
explicitly selects Mirror stone's perfect smoothness and 50% frontal reflection
for ordinary Gloss, and a separate 90% neutral coating for Silver Gloss.
It records prior user approval. The code implements that distinction.

The subsequent uncommitted Complementary compatibility work preserves these
coefficients, material tags and cover geometry. It was not installed in the
diagnostic capture profiles and cannot explain the installed comparison.

## Evidence checked

- Diagnostic pair `04` uses Bronze Gloss; pair `05` uses Bronze Matte as a control.
  All four image hashes match their manifests.
- Within each pair, the camera, placement recipe, video options, Collection pack,
  shader ZIP hashes and every non-ERYDON mod match. Parsed shader and ERYDON
  settings also match. Raw settings-file differences are comments and line endings.
- Both installed ERYDON JAR hashes match their capture manifests. The older binary
  lacks the cover Gloss override and conductor exemption; the newer binary has both.
- The active shader is Complementary Unbound r5.9 dev5, with world-space reflections
  disabled. No world-space reflection improvement is inferred from this scene.
- The images are diagnostic evidence, not approved marketing visuals. Earlier
  unmatched drafts are excluded.

## Why reflection detail becomes weaker

`HighPolishShaderAdapter.adaptTerrain` assigns ordinary Gloss material mask 242
and smoothness 1.0. `MetallicShaderAdapter.surface` exempts Gloss cover states
12059/12061 from conductor mutation. Bronze Gloss therefore leaves the earlier
bronze conductor path even though its authored specular texture remains metallic.

| Treatment | Earlier Bronze Gloss | Current Bronze Gloss |
| --- | --- | --- |
| Deferred material | Bronze conductor, mask 243 | Mirror stone, mask 242 |
| Smoothness | Approximately 0.984 with authored red 255 and minimum roughness 2/255 | 1.0 |
| Reflection coefficient at normal incidence | Bronze conductor peak 0.92 | Mirror floor 0.50 |
| Reflection colour | Shared coloured conductor Fresnel | Native Mirror treatment |
| Final composition | Linear-light conductor addition and absorption | Native opaque base-colour preservation and interpolation |

The current Mirror curve is `min(0.99, 0.5 + 0.5 * fresnel^3)` before fog/history
attenuation. The earlier full-metal bronze curve starts at 0.92 and approaches
one at grazing angles, capped at 0.98 for signed-normalized reflection storage.
Those caps protect against Complementary's exact-one rejection; they do not
account for the large normal-incidence difference.

The installed dev5 `composite1` opaque blend uses:

```glsl
reflection = mix(reflection, max(base, reflection), 0.7);
result = mix(base, reflection, reflectionCoefficient);
```

For a reflected channel darker than the lit base, this reduces to
`result = base * (1 - 0.3 * reflectionCoefficient)
        + reflection * (0.3 * reflectionCoefficient)`.
At the 50% Mirror floor, only 15% of a change in that darker reflected channel
reaches the result. For example, with base 0.8 and reflected values 0.1/0.4,
the outputs are 0.695/0.740: contrast 0.045 from input contrast 0.300.
These are isolated encoded-channel calculations, not screenshot measurements.
Bright reflected channels above the base are not reduced by this `max` operation,
so a clear silhouette and faint dark marble veins can coexist.

The earlier conductor branch does not perform this base-colour lift. It preserves
coloured reflection transport and keeps only the small roughness-dependent broad
lighting contribution. The current cover exemption also removes that conductor
lighting treatment. Thus the difference includes surface lighting, tint,
reflection weighting and final composition; changing only the 50% floor would
not restore the earlier bronze response.

Silver Gloss already bypasses opaque albedo preservation through mask 245.
White/Black Gloss and Mirror stone still intentionally use the native opaque blend.
Matte keeps its metal response; its new odd geometry-only ID separately prevents
thin covers from writing a false full cube into the world-space reflection map.
That geometry correction must survive any future appearance change.

## Initial decision and verification boundary

The initial investigation made no rendering patch: the weaker Bronze Gloss was a consequence of the
documented, previously approved Mirror treatment rather than a failure to activate
the hard-coded Gloss code. Changing Bronze Gloss to retain coloured conductor
reflections would revise that appearance decision. A targeted revision should
retain the independent state-selected finish, authored emission, Matte treatment,
thin-cover geometry classification and separate Silver coating. Bronze ceiling
insets already retain their conductor sprite metadata. Only the White/Black inset
markers carry no conductor alloy or coverage; those continue to use Mirror stone.

The focused run passed 37 tests with zero failures, errors or skips, using the
same Unbound r5.9 dev5 archive and driver validation enabled. It included cover
emission/finish and opaque blend GPU readback, actual reflection-voxelizer
readback, packed conductor material transport through SSR and WSR shader slices,
conductor sunlight checks, installed reflection-source parsing and adapter
isolation tests. `compileJava` succeeded (up to date); `git diff --check` passed.
The run used `compileJava test` with these test filters:

```text
com.oliver.erydon.client.pom.CoverReflectionBlendTest
com.oliver.erydon.client.pom.MetalMaterialPipelineTest
com.oliver.erydon.client.pom.MetalReflectionResponseTest
com.oliver.erydon.client.pom.MetallicShaderAdapterTest
com.oliver.erydon.client.pom.HighPolishShaderAdapterTest
```

This verifies shader slices and data flow, not a complete rendered capture or
frame-rate comparison. It does not assign a measured percentage to the loss of
marble detail in the screenshots. No new tests or rendering sources were changed;
the investigation adds only this report.

No test JAR, installation, commit, push or capture-profile/world changes were part
of the initial investigation. A revised shader appearance still needs a matched in-game
comparison; numerical shader readback does not establish visual acceptance.

## Authorized Bronze revision (2026-10-09)

After the investigation, the user requested full metallic Bronze Gloss and
reported that its apparent smoothness had also weakened. Bronze Gloss now uses
dedicated odd material ID 12065. This retains thin-cover lighting/voxel treatment
while avoiding the ordinary Gloss early return and metal exemption. Its actual
metallic sprite follows the existing conductor path: full coverage, canonical
bronze Fresnel/tint, authored polish with minimum roughness 2/255, coloured direct
highlights and linear-light reflection composition. No global reflection or
roughness coefficient was changed. Silver keeps its separate 90% mirror coating.

This restores the earlier Bronze smoothness of approximately 0.984 with the native
Gloss specular red of 255. It also restores material-boundary-aware reflection
filtering and removes the opaque base-colour preservation that obscured dark detail.
The regression checks establish material handling; visual acceptance is separate.
On 2026-10-12, the user confirmed Bronze Gloss covers were checked and approved
in the current client.

The new GPU regression test executes the complete composed material function,
including both conductor guards and the earlier emission/return boundary. A
uniform fully metallic sprite lookup is supplied by the fixture; finish selection,
roughness, palette and packing execute production code. It checks Bronze Gloss,
Matte roughness, Mirror/coating exclusion and retained emission. The actual Iris
classification/lighting probe and actual CU reflection voxelizer also include
ID 12065, all Cover states, reserved-ID collision handling and all six normals.

Revision validation passed 38 tests with zero failures, errors or skips using
Unbound r5.9 dev5 and the installed driver. `compileJava` and
`verifyHighPolishMixinLaunch` passed; the real probe classified 4,608 Gloss and
4,608 Matte states with optional finish settings disabled, including 12 unchanged
material/light vertex encodings. `git diff --check` passed.

The native placed-model chains for all four cover colours retain their uniform
finish sprites. The active Collection 64x compat2 v1.5.17 pack provides no overrides
for these cover sprites or companion maps; native Bronze Gloss specular remains
`(255,255,0,255)` and has no authored normal map. Native and active-pack CTM rules
do not match Bronze covers. No models, textures, normals or CTM rules were edited.
The current cover appearance was user-approved on 2026-10-12; the checks above
do not constitute an exhaustive visual review of every facing and shader profile.

Existing changelog entry `2.0.0-049` was amended and read back; its change date is
2026-10-09 and launch date remains blank. No packaging, installation, commit,
push or live-world changes were performed for the revision.
