# ERYDON-only CTM-aware POM handoff

| Gate | Status | Result |
|---|---|---|
| Exact user-selected dev5 fingerprint | PASS | Archive SHA-256 matched before and after testing. |
| Complementary files unchanged | PASS | No shader ZIP was written or repacked. |
| All active ERYDON repeat families | PASS | 244 families and 8,784 ordered phases populated from the live 8192x8192 atlas. |
| Atomic source transformation | PASS | All anchors/postconditions unique; every mismatch test returned the original source. |
| Dedicated Iris sampler binding | PASS | Live sampler route uses `texture.erydonCtmPomLookup`; the earlier staged override route was removed. |
| Six face orientations | PASS | Cardinal and rotated/mirrored basis mapping tests passed. |
| POM march coordinates | PASS | Adapted source and live Iris pipeline compiled. |
| Final material sampling | PASS | Adapted source and live Iris pipeline compiled. |
| Parallax shadow sampling | PASS | Adapted source and live Iris pipeline compiled. |
| Slope-normal sampling | PASS | Adapted source and live Iris pipeline compiled. |
| Unsupported shader fail-closed | PASS | Exact-hash and source-shape gates passed their rejection tests. |
| Initial shader/atlas/world lifecycle | PASS | dev5 activated, full lookup rebuilt, and Synapheia entered. |
| Glacium Rock visual seam removal | PASS | Oliver confirmed in game that the edge artifacts were removed. |
| Individual visual review of all 244 families | NOT RUN | Runtime coverage is proven; each material was not inspected by eye. |
| F3+T reload and world re-entry after broadening | NOT RUN | Initial atlas lifecycle passed; the interactive reload cycle remains optional follow-up. |
| Iris-absent client launch | NOT RUN | Optional mixin gating and dedicated-server safety passed. |
| Dedicated-server safety | PASS | Server environment loaded ERYDON and 47 mods without Iris/client classloading, then stopped at the unchanged EULA. |
| Zero-new-PNG gate | PASS | 12,580 physical PNG entries before and after. |
| JAR byte delta | PASS | 42,525 bytes (45,003,372-byte final JAR). |
| Frame-time result | NOT RUN | No fixed-scene capture was performed. |
| One-switch rollback | PASS | `-Derydon.cuPom.mode=off`. |

## Deliverables

- Runtime discovery, lookup, validation, source-transform, configuration, and shader-bridge code: `src/main/java/com/oliver/erydon/client/pom/`.
- Optional Iris and atlas lifecycle mixins: `src/main/java/com/oliver/erydon/mixin/client/`.
- Independently authored helper: `src/main/resources/assets/erydon/shaders/include/erydon_cu_pom_bridge.glsl`.
- Unit and failure-path tests: `src/test/java/com/oliver/erydon/client/pom/`.
- Final JAR: `build/libs/erydon-fabric-mc1.20.1-compat2-1.5.17.jar`.
- Raw evidence: `build/reports/synapheia/pom/erydon-only/`.

Final JAR SHA-256: `620a681b740ed1d8527082a7669c3f179a0ba4c13005a764a4f05d35007788f9`.

## Reproduction

Use Java 17, then run:

```text
gradlew test --tests "com.oliver.erydon.client.pom.*"
gradlew --no-daemon clean build auditErydonModelGeometrySafety
gradlew runClient -Perydon.quickPlayWorld=Synapheia
gradlew runServer
```

The protected CurseForge instance was not modified.
