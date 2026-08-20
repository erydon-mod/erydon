# ERYDON-only CTM-aware POM test results

Tested on 2026-08-19 against ERYDON 1.5.17, Minecraft 1.20.1, Fabric Loader 0.16.10, Iris 1.7.6, Java 17, the active ERYDON Collection 64x compat2 v1.5.15 pack, and `ComplementaryUnbound_r5.9_dev5.zip`.

## Automated and structural results

| Check | Result | Evidence |
|---|---|---|
| GPT Pro support-tool self-test | PASS | 5/5: lookup round trip, offsets `-13..+13`, properties mode, atomic transform, and fail-closed rejection. |
| Exact dev5 fingerprint | PASS | Archive SHA-256 `6a95d388f5eda5acd82e9f0e33af1adff499e854e070bc33c5f6b75ae9c9f737`; the source archive was unchanged after testing. |
| Active ERYDON family discovery | PASS | 1,025 active ERYDON repeat rules deduplicated to 244 ordered 6x6 tile families. Non-ERYDON and non-repeat rules are excluded. |
| Lookup protocol | PASS | Protocol v3 encoded and found all 8,784 active phase records in one 256x256 lookup; incomplete, duplicate, mixed-size, mixed-atlas, and malformed-rule inputs fail closed. |
| Six face mappings | PASS | Cardinal-face and rotated/mirrored UV basis tests passed. |
| Atomic source adapter | PASS | One anchor and one postcondition for each of the five transformations; changed or duplicate anchors leave source untouched. |
| Focused unit tests | PASS | All five `com.oliver.erydon.client.pom` test suites passed. |
| Full repository build and audits | PASS | `gradlew --no-daemon clean build auditErydonModelGeometrySafety` completed 35 tasks and all repo-native JAR, CTM, model, UV, geometry, alias, and migration audits. |
| New PNG entries | PASS | Baseline and final JAR both contain 12,580 physical PNG entries. |

## Live client evidence

The dev client selected exact dev5, transformed only its in-memory terrain source, built the complete lookup from the real 8192x8192 atlas, entered Synapheia, and created the Iris overworld pipeline without an ERYDON shader or mixin error.

Observed ERYDON facts:

- exact Complementary Unbound dev5 adapter enabled in memory;
- `gbuffers_terrain` adapted in memory;
- lookup ready with 244 families, 8,784 phases, and 262,144 runtime bytes;
- Oliver visually confirmed that the Glacium Rock edge artifacts were removed after the sampler binding correction.

The initial staged `texture.gbuffers.*` route did not expose a new arbitrary Iris sampler. The final implementation uses Iris's dedicated custom-sampler collection with `texture.erydonCtmPomLookup`, which was proved live before broadening the lookup.

## Artifact size

- Unchanged baseline: 44,960,847 bytes; SHA-256 `277ab13548d4540587a533e14645ded963b297f8fe96722cec1a9713e7328c44`.
- Final all-family JAR: 45,003,372 bytes; SHA-256 `620a681b740ed1d8527082a7669c3f179a0ba4c13005a764a4f05d35007788f9`.
- Delta: 42,525 bytes; zero new PNG files.

## Not run

Every family was populated and validated against the live atlas, but all 244 were not individually inspected in game. The full fixed-view screenshot matrix, F3+T reload/re-entry cycle after broadening, Iris-absent client launch, unrelated-shader launch, and frame-time capture were not run.

Raw evidence is under `build/reports/synapheia/pom/erydon-only/`.
