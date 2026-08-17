# Shared geometry test results

> These are the original one-family pilot results. Current combined-family
> results are in `SHARED_GEOMETRY_BATCH_RESULTS.md`.

## Outcome

The Gothic-column pilot passes structural, rendering, resource-pack, shader,
inventory, server, build, and runtime-regression checks. It reduces the
family's unique geometry backings from 216 to 4 and its measured vertex payload
estimate from 17,915,904 to 373,248 bytes.

It does not prove faster global startup or resource reload. The five-launch
startup result is noisy and neutral, while the baseline client could not
complete the first requested resource reload at either a 24 GB or 32 GB heap.
Retained heap was not profiled. The production default therefore remains the
baseline path.

## Test environment

- ERYDON `1.5.14`, Minecraft `1.20.1`, Fabric Loader `0.16.10`
- Fabric API `0.92.11+1.20.1`, Java 17, compatibility generation 2
- Sodium `0.5.13`, Indium `1.0.36`, Iris `1.7.6`, Continuity `3.0.0`
- Fixed benchmark save, camera, 120 FPS cap, 12-chunk render/simulation distance
- Dense scene: all 54 Gothic-column materials, 664 placed column blocks,
  positive/negative coordinates, origin crossing, and chunk/section boundaries
- Native, Collection 32x, Collection 64x, and Collection 64x with
  Complementary Unbound shader-on captures

The fixed scene is a family stress test. A separate controlled normal
architectural scene was not benchmarked.

## Structural audit

The supplied deterministic toolkit passed 29 tests across eight stages. The
repository contains 297 UTF-8-BOM JSON files, so a temporary toolkit copy used
`utf-8-sig` and was re-tested before auditing. Repository resources and the
supplied toolkit were not modified.

| Measure | Result |
| --- | ---: |
| Model JSON files | 56,113 |
| Model JSON bytes | 47,337,740 |
| Blockstate model references | 81,682 |
| Distinct referenced model resources | 22,671 |
| Exact resolved-signature groups | 2,497 |
| Conservative geometry groups | 301 |
| Ranked candidate groups | 243 |
| Audit warnings | 0 |

The 4,504 errors are unresolved external/vanilla parents in the standalone
audit, chiefly `minecraft:block/block`; they are not malformed ERYDON models.
Raw evidence is in `build/reports/erydon-shared-geometry/audit/`.

## Exact rendering parity

The all-material dump compared 139,968 emitted surface records per mode at
floating-point tolerance `0.0`.

| Comparison | Differences |
| --- | ---: |
| Missing or additional surfaces | 0 |
| Vertex geometry or winding | 0 |
| Source or final UVs | 0 |
| Sprite/material identifiers | 0 |
| Cull, tint, layer, AO or diffuse metadata | 0 |
| Ordering or determinism | 0 |
| Missed sharing or accidental backing reuse | 0 |

All four geometry keys were correctly shared across the 54 material variants.
The detailed comparator output is in
`build/reports/erydon-shared-geometry/comparison/`.

## Visual and compatibility checks

| Check | Result | Evidence |
| --- | --- | --- |
| Native, shader disabled | PASS | Baseline/prototype fixed-camera pair |
| Collection 32x, shader disabled | PASS | Baseline/prototype fixed-camera pair |
| Collection 64x, shader disabled | PASS | Baseline/prototype fixed-camera pair |
| Collection 64x, shader enabled | PASS | Complementary Unbound pair; PBR reflections and relief active |
| Continuity CTM | PASS | Standard floor and Gothic family rendered; no 47-tile fallback error |
| Structural model override | PASS | Deliberately altered base produced exactly one fallback |
| Item/inventory | PASS | All 54 item chains validated; representative item crop is pixel-identical |
| Dedicated server | PASS | Server reached `Done (9.747s)` and stopped cleanly |

Separate launches introduce small lighting, cloud, water, and shader-time
differences. The screenshot report records low mean absolute RGB differences;
the shader pair changes many pixels but averages below 0.43 levels per RGB
channel. Exact geometry/UV/sprite parity comes from the zero-tolerance surface
comparison, not screenshot identity.

Complementary Unbound logged the same authentication and shader-variable
warnings in both modes. The shader still compiled its world pipeline and both
captures completed. Iris was tested installed with shaders disabled and with
one representative shader enabled; a run with the Iris JAR physically absent
was not performed.

Visual evidence and numerical screenshot comparisons are in
`build/reports/erydon-shared-geometry/visual/`.

## Loading and geometry results

Five full launches were recorded for each mode.

| Metric | Baseline median | Shared median | Change |
| --- | ---: | ---: | ---: |
| Unique geometry backings | 216 | 4 | -98.15% |
| Geometry cache hits/misses | 0 / 0 | 212 / 4 | 98.15% hit ratio |
| Estimated vertex payload | 17,915,904 B | 373,248 B | -97.92% |
| Gothic geometry bake | 36.591 ms | 10.543 ms | -71.19% |
| Material binding | 0 ms | 4.512 ms | Added work |
| Geometry bake plus binding | 36.591 ms | 15.055 ms | -58.85% |
| Gothic authoring opens/parses | 4 / 4 | 4 / 4 | No change |
| All authoring opens/parses | 43 / 43 | 43 / 43 | No change |
| Material baked models | 216 | 216 | No change |

Parsing does not fall because ERYDON already parses the four Gothic authoring
graphs once per reload. The demonstrated benefit is shared immutable baked
geometry and reduced family-local bake work.

## Startup and resource reload

| Metric | Baseline | Shared | Interpretation |
| --- | ---: | ---: | --- |
| Stable-title median, 5 launches | 71.324 s | 72.908 s | +2.22%; inconclusive |
| Stable-title range | 67.099–72.816 s | 63.862–76.039 s | Wide system noise |
| Relative median deviation | 2.09% | 4.29% | Larger than a pilot-wide signal |

The global startup result does not prove either a gain or a regression.

The requested ten-reload comparison is `NOT RUN`. The baseline path saturated
the 24 GB heap before completing its first reload. A 32 GB retry also saturated
before its first sample and spent the observation window in garbage collection.
No prototype reload result was collected because there was no valid baseline
comparison. Logs are in `build/reports/erydon-shared-geometry/logs/`.

## Runtime results

The corrected dense scene produced ten chunk rebuilds and 30 one-second FPS
samples per mode.

| Metric | Baseline | Shared | Change |
| --- | ---: | ---: | ---: |
| Chunk rebuild mean | 49.394 ms | 48.927 ms | -0.95% |
| Chunk rebuild median | 51.877 ms | 51.319 ms | -1.08% |
| Slowest chunk rebuild | 53.864 ms | 53.520 ms | -0.64% |
| Stationary FPS mean | 119.412 | 119.543 | +0.11% |
| Stationary FPS median | 119.65 | 119.70 | +0.04% |

The chunk result is within noise but clearly does not breach the 2% regression
rejection threshold. FPS is neutral, as expected; geometry sharing does not
reduce the surfaces, shader passes, shadows, or GPU chunk-buffer work.

Summaries are in `build/reports/erydon-shared-geometry/summaries/`.

## Memory interpretation

The exact integer vertex-payload estimate falls 48-fold. This is strong
family-local evidence, but it is not a retained-heap measurement and excludes
object headers, mesh implementation overhead, atlas sprites, and unrelated
Minecraft allocations.

Simple used-heap readings moved in opposite directions between launch and world
samples and are not retained-memory proof. Retained heap is therefore
`NOT RUN`, not a claimed 98% whole-client memory saving.

## Build and JAR inspection

`clean build` completed all 31 tasks, including tests, model-safety checks,
texture alias validation, CTM isolation/path checks, restored-family geometry,
ID migration, structure audits, remapping, and the release JAR audit.

The production JAR is
`build/libs/erydon-fabric-mc1.20.1-compat2-1.5.14.jar` with SHA-256
`2E205D4D5AC9418B05E0A52A86C6F44EAE6212F3BC8149462F7DE3B50214F270`.
It contains 78,277 entries and 441 classes. The configured toolkit hygiene
check reports zero errors and zero warnings, and an independent scan found no
machine-specific path strings.

The toolkit's unconfigured first pass is retained separately because it
demonstrates a checker limitation: it interpreted random PNG/nested-JAR bytes
as drive paths and expected a literal `NOTICE` instead of the repository's
`THIRD_PARTY_NOTICES.md`. The configured pass disables binary-content scanning,
uses the repository's real licence/notice names, and is supplemented by the
independent path-string scan and explicit class-entry inspection.

## Recommendation

Continue the design as an opt-in research path, but do not make it the
production default or bulk-migrate families yet. The pilot has worthwhile,
low-risk family-local savings with no observed runtime regression. Wider
adoption should wait for a valid resource-reload comparison and retained-heap
profile.
