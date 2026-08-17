| Check | Status | Evidence |
| --- | --- | --- |
| Verified Daedalon reference architecture | PASS | Live OBJ loader, immutable mesh, material transform, reload and item paths traced read-only |
| ERYDON structural duplication audit | PASS | 56,113 models; 22,671 referenced resources; 243 ranked candidates; zero warnings |
| Pilot geometry sharing | PASS | 216 geometry backings reduced to 4; 212 hits and 4 misses |
| Continuity CTM parity | PASS | Exact sprites/UVs; standard floor and Gothic family rendered; no duplicate/fallback CTM error |
| Native textures | PASS | Fixed-camera baseline/prototype pair |
| 32x resources | PASS | Hash-verified Collection pack pair |
| 64x PBR resources | PASS | Hash-verified Collection pack pair with shader disabled and Complementary Unbound enabled |
| Resource-pack structural override fallback | PASS | One altered component produced one fallback; remaining 215 stayed shared |
| Item/inventory parity | PASS | All 54 item models validated; representative rendered crop has zero changed pixels |
| Dedicated-server safety | PASS | Reached `Done (9.747s)` without client-class loading failure |
| Model loading | PASS | Family-local bake plus binding median reduced from 36.591 ms to 15.055 ms |
| Resource reload | NOT RUN | Baseline saturated both 24 GB and 32 GB heaps before the first valid sample |
| Retained memory | NOT RUN | Vertex payload measured; retained-object graph not profiled |
| Chunk rebuilding | PASS | Ten-sample median improved 1.08%; no >2% regression |
| Stationary FPS | PASS | Neutral: 119.412 vs 119.543 mean FPS |
| Rollback switch | PASS | Default and invalid values select `baseline`; opt-in uses `shared_geometry` |

> Current update: the compatible family batch requested after this pilot is now
> implemented and measured. See `SHARED_GEOMETRY_BATCH_RESULTS.md`. The default
> remains `baseline`; the pilot-era candidate list below is historical.

# Shared geometry handoff

## Pilot decision (historical)

The Gothic-column pilot is technically successful and worth continuing, but it
is not ready to become ERYDON's default. It delivers a 98.15% reduction in
unique family geometry backings, a 97.92% lower measured vertex-payload
estimate, and about 58.85% less measured family-local bake-plus-binding time,
with exact output parity and no chunk-rebuild regression.

Keep `baseline` as production default. Do not bulk migrate or delete fallback
models. First obtain valid resource-reload and retained-heap measurements.

## Changed files

Production/runtime:

- `build.gradle`
- `src/main/java/com/oliver/erydon/ErydonClient.java`
- `src/main/java/com/oliver/erydon/client/model/ColumnBakedModel.java`
- `src/main/java/com/oliver/erydon/client/model/ErydonRawModelLoadingPlugin.java`
- `src/main/java/com/oliver/erydon/client/model/SharedGeometryChildModel.java`
- `src/main/java/com/oliver/erydon/client/profile/ErydonLoadProfiler.java`
- `src/main/java/com/oliver/erydon/client/profile/ErydonSharedGeometryMetrics.java`

Development-only and excluded from production JARs:

- `src/main/java/com/oliver/erydon/client/profile/ErydonSharedGeometryBenchmarkHarness.java`
- `src/main/java/com/oliver/erydon/client/profile/ErydonSharedGeometryMetricsDevelopmentSink.java`
- `src/main/java/com/oliver/erydon/client/profile/ErydonSharedGeometryMetricsProvider.java`
- `src/test/java/com/oliver/erydon/client/model/SharedGeometryParityDump.java`
- `src/test/java/com/oliver/erydon/client/model/SharedGeometryPrototypeTest.java`
- `src/test/java/com/oliver/erydon/client/profile/SharedGeometryBenchmarkWorldNameTool.java`

Documentation:

- `docs/performance/SHARED_GEOMETRY_DISCOVERY.md`
- `docs/performance/SHARED_GEOMETRY_ARCHITECTURE.md`
- `docs/performance/SHARED_GEOMETRY_TEST_RESULTS.md`
- `docs/performance/SHARED_GEOMETRY_HANDOFF.md`

No registry, blockstate, item, recipe, loot, tag, language, creative-order,
collision, or resource-pack source file changed. The unrelated untracked root
`AGENTS.md` remains untouched.

## Main commands

```powershell
.\gradlew.bat test --tests com.oliver.erydon.client.model.SharedGeometryPrototypeTest writeSharedGeometryParityDumps --no-daemon
.\gradlew.bat runClient "-Perydon.sharedGeometryMode=baseline" --no-daemon
.\gradlew.bat runClient "-Perydon.sharedGeometryMode=shared_geometry" --no-daemon
.\gradlew.bat runServer --no-daemon
.\gradlew.bat clean build --no-daemon
python -m erydon_geom_tools compare-dumps --baseline <baseline.jsonl> --prototype <prototype.jsonl> --tolerance 0
python -m erydon_geom_tools summarize-benchmarks --baseline <baseline.jsonl> --prototype <prototype.jsonl> --output-dir <summary>
python -m erydon_geom_tools jar-check --jar <production.jar> --config <jar-check-config.json> --output-dir <report>
```

The benchmark harness also accepts `launch`, `reload`, `world`, `visual`, and
`item` scenarios through the Gradle properties documented in `build.gradle`.

## Evidence map

| Evidence | Location |
| --- | --- |
| Structural model audit | `build/reports/erydon-shared-geometry/audit/native-compatible/` |
| Baseline/prototype surface dumps | `build/reports/erydon-shared-geometry/dumps/` |
| Zero-tolerance comparison | `build/reports/erydon-shared-geometry/comparison/` |
| Raw launch/world/item/visual samples | `build/reports/erydon-shared-geometry/benchmarks/` |
| Runtime model snapshots | `build/reports/erydon-shared-geometry/runtime/` |
| Robust benchmark summaries | `build/reports/erydon-shared-geometry/summaries/` |
| Native, 32x, 64x, shader, item and fallback captures | `build/reports/erydon-shared-geometry/visual/` |
| Launch, reload, shader and server logs | `build/reports/erydon-shared-geometry/logs/` |
| Production JAR hygiene | `build/reports/erydon-shared-geometry/jar-check/` |
| Toolkit false-positive record | `build/reports/erydon-shared-geometry/jar-check-default-false-positive/` |
| Hash-verified pack builds | `build/reports/erydon-shared-geometry/resource-packs/` |

## Baseline versus prototype

| Metric | Baseline | Shared geometry |
| --- | ---: | ---: |
| Gothic material models | 216 | 216 |
| Unique geometry backings | 216 | 4 |
| Cache hits / misses | 0 / 0 | 212 / 4 |
| Vertex-payload estimate | 17,915,904 B | 373,248 B |
| Gothic geometry bake median | 36.591 ms | 10.543 ms |
| Material binding median | 0 ms | 4.512 ms |
| Stable-title median, five launches | 71.324 s | 72.908 s |
| Chunk rebuild median, ten samples | 51.877 ms | 51.319 ms |
| Stationary FPS mean, 30 samples | 119.412 | 119.543 |

## What sharing improved

- One immutable Fabric mesh now backs every material for each of the four
  genuine Gothic structural components.
- Family-local geometry bake work fell substantially even after counting the
  new material-binding work.
- The exact integer vertex-payload estimate is 48 times smaller.
- Resource-pack material identity, Continuity transforms, shader behaviour,
  Axiom-compatible vanilla quads, and the separate item path remain available.

## What did not change

- Authoring resource opens and JSON parses: ERYDON already shared those four
  parsed authoring graphs.
- Material baked-model count: each model identifier still needs a lightweight
  binding object.
- Emitted surface/vertex work, shader passes, shadows, and chunk-buffer size by
  design.
- Global startup: the five-run result is too noisy to distinguish a pilot-wide
  signal.
- Production default: still `baseline`.

## Known risks and incomplete evidence

- Ten resource-reload samples are unavailable because the existing baseline
  full-model graph saturated both tested heaps before one reload completed.
- Retained heap and allocation profiles were not captured; the payload estimate
  must not be presented as whole-client memory savings.
- The benchmark covered a dense family stress scene, not a second controlled
  normal architectural scene.
- Iris was tested installed with shaders disabled and with Complementary
  Unbound enabled, not with the Iris JAR physically removed.
- Complementary Unbound reports the same older-version shader-variable warnings
  in both modes; this did not prevent either capture.
- The Axiom fallback is covered by code, focused tests and the clean build, but
  an interactive Axiom visual session was not run.
- The standalone audit cannot resolve vanilla/external parents, so candidate
  scores are leads that still require family-specific resolution and runtime
  parity.

## Pilot-era ranked candidates (now superseded by the batch)

This is a risk-adjusted order, not an instruction to migrate them now.

1. **Circular columns** — the same wrapper and component-selection path as the
   successful pilot; 385 model files and about 1.09 MB, including several of
   the largest individual ERYDON component models.
2. **Square columns** — the same bounded architecture with 330 files and about
   0.53 MB; lower geometric complexity makes it a useful confirmation pilot.
3. **Layer/vertical-slice family** — the largest raw opportunity at 5,880 files
   and about 8.10 MB, but its many depths/slices require a separate key and UV
   proof rather than reusing the column assumptions.
4. **Gothic Ornate and Georgian surrounds** — 1,872 files and about 2.49 MB
   combined; high-value complex geometry with more assembly and item risk.
5. **Georgian and Gothic alcoves** — 4,890 files and about 1.18 MB combined;
   large fan-out, but seam/span states and multi-part assembly make validation
   broader than the column pilot.

## Pilot-era smallest next step (superseded)

Run one baseline and one shared resource reload in an isolated client setup that
can complete the current full model graph, then capture a retained-heap profile
after model loading. If both confirm the family-local evidence, prototype
circular columns under the same opt-in switch and the same zero-tolerance gates.

Do not change the default, migrate another family, remove JSON fallbacks,
commit, push, publish, or release without a separate instruction.
