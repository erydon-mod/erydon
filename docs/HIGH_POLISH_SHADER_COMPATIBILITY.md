# High polish and glazing shader compatibility

ERYDON checks the shader's unmodified `shaders.properties` fingerprint, not its
ZIP filename or a claimed version string. Each recognized dimension must also
pass a complete terrain/deferred/glass source preflight before its reflection
programs are changed. The CTM-POM and metal adapters retain their separate source
checks. Reserved block/tag material IDs are checked before Iris reads programs.

## Audited profiles

| Release | Unbound polish/glazing/POM | Reimagined polish/glazing/POM | Enhanced metals in inspected ZIPs |
| --- | --- | --- | --- |
| r5.5.1 | Unsupported | Unsupported | Native |
| r5.6.1 | Supported source profile | Supported source profile | Native |
| r5.8.1 | Supported source profile | Supported source profile | Native |
| r5.9_dev2 | Supported source profile | No reference ZIP inspected | Native |
| r5.9_dev5 | Supported source profile | No reference ZIP inspected | Supported |
| r5.9.3 | Supported source profile | Supported source profile | Supported |
| r5.9.4_dev1 | Supported; shares r5.9.3 properties | No reference ZIP inspected | Supported |
| r5.10_dev2 | Supported source profile | No reference ZIP inspected | Supported |
| r5.10_dev3 | No reference ZIP inspected | Supported source profile | Supported |

This is an exact release list, not a promise that every intervening or future
version works. The audited stable Unbound/Reimagined pairs share properties
fingerprints. Their style defaults are kept intact. Unrecognized fingerprints,
including modified properties and third-party shader edits, retain native
rendering. r5.5.1 has incompatible deferred/glass and metal contracts and is
deliberately excluded. The log identifies recognized profiles, unsupported
properties fingerprints, material collisions and failed program preflights.
The enhanced metal pipeline separately requires all eight of its programs;
r5.6.1, r5.8.1 and r5.9_dev2 retain native metals because their later reflection
stages differ. Their supported polish/glazing/POM programs are still enabled.

## Retained controls and rendering

Use labPBR/custom resource-pack materials. High polish remains restart-bound;
stone finishes, glazing and two-way controls remain independent. Glazing keeps
its tint and 50% reflection floor independently of pane alpha; the metallic
two-way coating keeps its 90% floor. Frame finishes, Gloss covers, grout,
authored metal channels and height maps retain their existing handling.

Daedalon's published form-first Spartan statue IDs use the same per-stone
Honed, Polished or Mirror preference as its other fresh stone sculptures.
Their registry IDs stay unchanged; aged, bronze, silver and white finishes
remain outside the configurable stone-finish selection.

The adapters change shader strings in memory only. They add no reflection pass,
pane geometry or glass texture samples. Native CTM resources and Collection
textures are unchanged. Circular-column reflections still depend on the pack
providing its optional world-space voxelizer; older profiles may only provide
screen-space reflections.

## Reproduce source validation

Set `ERYDON_CU_TEST_SHADER` to one reference ZIP, `ERYDON_CU_TEST_SHADER_DIR` to
a directory of reference ZIPs, or `ERYDON_CU_TEST_SHADER_LIST` to a list separated
by the host's path separator (`;` on Windows, `:` on Linux). Archives are read
only and are not redistributed by the tests. Set `ERYDON_CU_GL_VALIDATE=true`
to enable the separate invisible OpenGL driver compiler.

```powershell
.\gradlew.bat --no-daemon test --tests '*ComplementaryCompatibilityMatrixTest' --tests '*HighPolishShaderAdapterTest' verifyHighPolishMixinLaunch
```

The matrix covers all three dimensions, combined CTM-POM/polish/metal programs,
POM on/off, filtering and normal-strength variants, glazing and two-way
response, optional column voxelization, unknown edits and the r5.5.1 fallback.
The matrix also checks the installed reflection preset and the capture preset
with world-space reflections disabled, alongside the original POM/filter variants.
Unit tests also cover missing/ambiguous programs, material-ID collisions and
shader reloads. The Fabric pre-launch probe verifies the actual woven Iris
constructor order without starting Minecraft or producing a JAR.

On 2026-10-09 the twelve reference ZIPs passed 480 vertex/fragment program pairs
through Iris preprocessing, AST parsing, Iris's renderer transformation and
NVIDIA driver compilation/linking. The two r5.5.1 ZIPs were rejected without
runtime shader changes. The production adapters still repeat their source
checks for the player's actual shader settings; this matrix does not certify
every optional shader feature or third-party modification.

On 2026-10-10 the four environment reference ZIPs (Reimagined r5.10_dev3 and
Unbound r5.10_dev2, r5.9_dev5 and r5.9.4_dev1) passed 384 combined program pairs
through the same NVIDIA compiler/linker, including both installed and
screen-space-only capture presets. All eight metal programs passed source
preflight in each case. The focused suite passed 40 tests without skips or
failures, and the Fabric/Iris pre-launch probe verified Gloss/Matte cover
classification and native light transport. These checks confirm source
compatibility; live user acceptance is recorded separately below.

## Live visual acceptance

On 2026-10-12, the user confirmed bronze inlays, Bronze Gloss covers and glazing
were checked and approved in the current client. Their appearance is accepted
for the performance changes. This approval does not certify every shader release,
optional setting or third-party modification.

Compiler and pre-launch checks do not prove the appearance in a world. For future
shader profiles or rendering changes, compare
Honed/Polished/Mirror, tinted/clear glazing, frame finishes, two-way glass, Gloss
covers and patterned CTM-POM joins in a separately authorized client. Inspect
reflection opacity and world texture phase across joins and all four facings.
No active capture profile, protected instance, save or player configuration is
changed by this source validation.

The earlier horizontal striation in stacked glazing was investigated separately;
its cause was not established. The current glazing appearance was approved on
2026-10-12. The capture subject uses ordinary `PaneBlock` connection flags,
vanilla pane templates, a uniform tinted surface texture and fully transparent
edge texture. The inspected Collection pack does not replace those textures;
the built-in glazing override supplies specular maps only. The compatibility
gate explains missing enhanced reflections but does not establish the source
of periodic bands. No pane geometry, texture, culling or shader response
coefficient was changed to address them.

## Cover and two-way mirror environment comparison

The 2026-10-10 comparison found an unrecognized Reimagined r5.10_dev3
properties fingerprint in the affected client. That gate disabled the shared
polish/glazing classification, including all Gloss covers and two-way windows.
Its exact fingerprint is now recognized; program-source preflight remains required.

The installed shader adapters, cover/glazing assets and enabled client controls
matched the working development environment. Supported Unbound capture runs
passed program preflight and classified block states, so the fingerprint gate
does not explain their reported appearance. Capture settings disabled world-space
reflections while the development settings enabled them. Screen-space-only
reflections can miss geometry outside the camera view, but this difference does
not disable the mirror coating and has not been established as the cause by a
matched in-world comparison. No client settings or worlds were changed.
