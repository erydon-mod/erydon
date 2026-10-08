# Validation boundary

`python tools/sync_catalogue_tags.py` fills missing catalogue/material/finish,
geology, form and colour members, and synchronises searchable item tags with
the block tags, including the material/style/colour namespace bridges.
`verifyCatalogueTags`, included in `check`, rejects new coverage gaps. Internal
blocks without player items are excluded from item tags; legacy aliases are
preserved. Full-block tags contain all 440 full cubes and exclude partial shapes.
Gothic and Gothic Ornate have separate tags; Byzantine and Guilloche share
search vocabulary. Geology follows each material's plain block classification,
including its contribution to mixed weaves.

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

`verifyWideArches` exercises the shared Romanesque, Modern and Gothic cluster
callbacks with widths one to six, mixed materials, placement inheritance,
removal and recalc. It checks taller crowns, column transitions, selectable
empty cells, all horizontal facings and reversible mirrors, plus clipped mesh
area and rotated normals. Larger arches reuse the existing registered IDs and
child models; their geometry is cached and split between owning block cells.
The default maximum width remains three; the debug stick selects wider runs.
The probe also checks every canonical state transition and requires the compact
arch transition lookup to be active under Fabric. For a complete registration
and collision-cache startup regression check, run
`./gradlew verifyWideArches -Perydon.arch.fullStartup=true` (8 GiB heap, no window
or JAR). This exercises all 486 registered arch variants instead of only one
block per style.
Arch model identifiers are shared per registered material, since the family
wrapper renders the supplied state at runtime. Immutable Minecraft shape caches
are shared only when collision, side, culling and outline shapes plus fluid,
opacity and offset inputs match. The probe compares every cached field against a fresh vanilla
cache across its layout cases, and compares batched collision geometry with the
original per-piece unions, including reflections and every facing. It reports unique model and shape counts in the
full startup check. Saved state values and the authored component geometry remain
unchanged.
Texture phase, lighting, shader relief and Axiom's live preview still require
the restarted-client visual check.

`verifyAlcoveTransforms` checks Gothic and Georgian alcoves through Minecraft's
actual block-state transform entry points under Fabric. It covers widths one to
three, both flip/rotation orders, and the resulting whole-cluster shape bounds,
without opening a game window or world. The task runs as part of `check`.
An optional `erydon.alcoveTestMods` Gradle property accepts locally remapped Axiom
and dependency JAR paths, separated by the platform path separator, to exercise
the Move Builder Tool's actual region transformations instead.

`verifyCopingPlacement` checks 135 registered coping variants, 148 reciprocal
joins across profiles/facings/offset owners, and 63 placement/transform/water/raycast
cases without a game window. It also compares 200 standard, shallow and steep
slope states with their rendered surfaces at 1,024 points per state, accepting
only the existing voxel-step precision of the outline/collision approximations.
The coping unit tests additionally compare the shared mitre cross sections and
CTM coverage when an older Collection pack replaces the base material rules.
The same probe exercises REI's actual mixed-in text cache for the complete player
catalogue, including standard-finish synonyms and their alternate-finish exclusions.
It checks unsupported coping retention and single-box particle bounds, and replays
coping geometry through Fabric's real mesh encoder/decoder to verify that authored
CTM planes survive cached lighting normals and all four facings.

The standalone large-column probe runs with
`-I gradle/erydon-large-column-validation.init.gradle verifyLargeColumnRepair`.
The optional `erydon.largeColumnTestMods` property accepts named/remapped Axiom
and client API JAR paths. With Axiom 5.4.2, it checks the actual selection, corner,
shrink, restore, reset and operation entry points, including every cell of each
capital style before deferred section repair. No game window or mod JAR is created.

The framed-glazing probes run with
`-I gradle/erydon-glazing-validation.init.gradle verifyGlazingSlopes verifyGlazingSlopesServer`.
They check smooth triangular meshes, automatic inner/outer corners, placement,
collision and selection shapes, and the permanent Vertical Diagonal aliases under
Fabric on both client and server. Outputs stay in the isolated Gradle validation
directory; no game window, world or mod JAR is created.
