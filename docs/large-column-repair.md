# Large circular column repair

Large columns use one material block with a 2×2 footprint. A base or capital is a
two-layer section; each shaft layer is a separate section. Breaking and Axiom
selection use the same section-membership function, including the stored
horizontal offsets, so adjoining columns are not merged.

Placement, removal and vertical neighbour changes schedule repair for nearby
layers on the next server tick. Resolving a layer reads at most three layers above
and below it. Repair changes only the section property of existing members: it
does not fill holes, replace other blocks, or reset base/capital styles. Components
at least four layers tall receive both ends. Short components retain complete
base/capital pairs and shafts; an unmatched decorative half becomes a shaft.

Large columns also participate in `/erydon recalc`. Discovery and planning finish
before changes are applied. An unloaded edge or a component exceeding the
existing 512-cell recalc limit is left unchanged.

Axiom keeps its original corners and magic-selection cells. Completion is refreshed
after selection edits, restoration, and immediately before creating an operation
buffer, so removed cells do not remain in temporary section completion.

## Automated validation

Run without packaging a JAR:

```powershell
.\gradlew.bat --no-daemon --max-workers=2 -I gradle/erydon-large-column-validation.init.gradle verifyLargeColumnRepair test
```

The init script isolates build output under `.gradle/large-column-validation/build`
so another task's ordinary `clean` cannot remove it.
The Fabric launch probe invokes actual block callbacks against an in-memory world
and exits before opening a game window or save. It covers breaking every corner
of each section, deferred repair,
detached decorative sections, missing anchors, adjoining offsets, unloaded edges,
oversized recalculation, and horizontal transforms.

To additionally exercise actual Axiom selection classes, pass
`-Perydon.largeColumnTestMods=<paths>` with named/remapped Axiom and client-API
archives separated by the platform path separator. These optional classes are
copied into the isolated probe fixture; external mods are not initialised.

## Remaining runtime check

In a restarted development client, check base/capital and shaft breaking; Axiom
corner expansion and shrinking; and move, clone, rotate, mirror, delete and undo
for detached sections and whole columns. Include adjoining columns and an edit
across a chunk boundary. Automated in-memory checks do not establish tool/network
timing or the rendered result in a live world.
