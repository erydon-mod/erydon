# Shared geometry family batch results

## Outcome

The opt-in `shared_geometry` path now covers ERYDON's compatible architectural
component families in one batch. It reduced 21,674 accepted material-specific
geometry backings to 906 shared backings. The production default remains
`baseline`.

Unsupported, structurally overridden, inventory, slope, light and specialist
models keep their existing path. No identifier, blockstate, texture or resource
pack source was renamed or removed.

## Covered families

- Gothic columns, Georgian/Gothic alcoves and Gothic arches use 43 shared raw
  authoring geometries.
- Circular/square columns, cornices, coffered ceilings, solid/glazing vertical
  layers, surrounds, windows, and Romanesque/Modern arches are pooled from
  Minecraft's completed baked output.
- The baked-output gate accepts a model only when every position, UV, tint,
  face, cull direction, shade flag, sprite slot and ambient-occlusion value can
  be reproduced exactly. Otherwise that individual model stays unchanged.

## Measured result

| Measure | Baseline-equivalent | Shared batch | Change |
| --- | ---: | ---: | ---: |
| Accepted material models / geometry backings | 21,674 | 906 | -95.82% |
| Cache hits / misses | 0 / 0 | 20,768 / 906 | 95.82% hit ratio |
| Vertex-array payload represented | 320,354,304 B | 57,132,288 B | -82.17% |
| Two-run stable-title mean | 79.52 s | 81.19 s | +2.10% |
| Stationary FPS mean | 117.94 | 119.59 | +1.40% |
| Stationary FPS median | 119.30 | 119.90 | +0.50% |
| Chunk rebuild mean | 50.526 ms | 49.613 ms | -1.81% |
| Chunk rebuild median | 51.898 ms | 51.111 ms | -1.52% |

The 57.1 MB shared estimate includes both the 30.5 MB Fabric mesh estimate and
26.6 MB of exact compatibility vertex arrays retained for vanilla/Axiom
`getQuads()` access. It is still not a whole-client retained-memory profile.

The two launch ranges overlap (baseline 76.59-82.45 s; shared 78.31-84.07 s),
so the small mean difference is inconclusive. The in-world result is neutral to
slightly better and shows no FPS or chunk-rebuild regression. TPS is unaffected
by design because the pooling and emission code is client-only.

## Safety and validation

- 2,079 incompatible or structural cases used the original path rather than
  being forced into the pool.
- Focused tests cover the mode switch, all 43 raw authoring models, supported
  family routing, texture-only overrides, and structural fallbacks.
- A combined native scene rendered 664 Gothic-column blocks plus 98 samples
  across 13 covered block types. It completed 30 FPS samples and ten full chunk
  rebuilds per mode without a render failure.
- The shared native visual capture showed no missing-texture or missing-model
  output.
- Detailed per-model metrics are disabled by default; benchmark totals no
  longer flood the log or distort launch timing.

Evidence is under `build/reports/erydon-shared-geometry-batch/`. The earlier
one-family resource-pack, shader, item and server evidence remains under
`build/reports/erydon-shared-geometry/`.

## Decision

Keep the batch behind the existing opt-in switch for now. The geometry and
runtime results are good, but two launches are not enough to justify changing
the production default, and full retained-heap/resource-reload proof is still
unavailable.
