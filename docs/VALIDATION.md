# Validation boundary

The public `build` task retains the production checks that operate only on the
current source, generated build output, or small redistributable fixtures.
These include Java tests, ID migration, CTM path and isolation checks,
texture-alias validation, Mod Menu checks, model performance checks, Georgian
structure checks, and the final JAR audit.

The restored-family geometry task no longer reads the private
`tools/main 050326` snapshot. It compares representative current components
against six small legacy blockstate fixtures under
`src/test/resources/restored-family-geometry/`.

The old cross-repository Collection-pack pilot tasks were not imported. The
Collection 32x and 64x source and deterministic packaging workflow are managed
separately, and those tasks were never part of the standard mod `build` or
`check` graph. Current native resources continue to be validated by the mod's
texture-deduplication and JAR audits.

The spiral-stair CTM source test always requires complete coverage in the
bundled core resources. It also validates any optional Collection pack roots
when those separately managed roots are present, without making them a public
source-build prerequisite.

Additional model geometry tooling remains available through:

```text
./gradlew auditErydonModelGeometrySafety
```

In-game visual and lighting validation remains a release test; it is not
represented as a clean-clone CI assertion.

`verifyAlcoveTransforms` checks Gothic and Georgian alcoves through Minecraft's
actual block-state transform entry points under Fabric. It covers widths one to
three, both flip/rotation orders, and the resulting whole-cluster shape bounds,
without opening a game window or world. The task runs as part of `check`.
An optional `erydon.alcoveTestMods` Gradle property accepts locally remapped Axiom
and dependency JAR paths, separated by the platform path separator, to exercise
the Move Builder Tool's actual region transformations instead.
