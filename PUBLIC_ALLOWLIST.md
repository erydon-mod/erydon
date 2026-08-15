# Public-candidate allowlist

This candidate was created from the current working files in the ERYDON source
repository without copying its `.git` directory or recovering any historical
object.

## Imported source categories

- Current `src/main/java`, `src/test/java`, and runtime/test resources.
- Current runtime assets under `src/main/resources`, excluding the three
  inspiration screenshots with unresolved third-party visual provenance.
- `build.gradle`, `settings.gradle`, `gradle.properties`, Gradle wrapper files,
  and build-referenced Gradle audit scripts.
- `libs/continuity-3.0.0+1.20.1.jar` for current runtime parity.
- `config/texture-dedupe-native.json`.
- The following build and validation tools:
  - `texture_dedupe.py` and its tests
  - `id_migration.py`
  - `generate_georgian_wall_pier.py`
  - model geometry/UV/z-fighting tools and their source fixtures
  - `normalize_java_model_bounds.py`, a transitive model-tool helper
  - the generators directly imported by those source tests
- Six explicit legacy blockstate fixtures required by the restored-family
  geometry audit.

## Deliberately excluded categories

- All Git history and source-repository metadata.
- IntelliJ, assistant, Gradle-cache, build, runtime, log, profiling, world,
  screenshot, player, and temporary state.
- Resource-pack ZIPs and separately distributed Collection pack trees.
- Disabled mod JARs and downloaded caches.
- `runclient_errors`, PresentMon, FabricSkyBoxes, and the stray JEI source copy.
- The 40,112-file `tools/main 050326` snapshot, except for the six named fixture
  files extracted into the test-resource fixture directory.
- Old CTM backups, duplicated sources, internal reports, and machine-path files.

The public documentation, licences, workflow, requirements file, and report in
this folder were created specifically for this candidate.
