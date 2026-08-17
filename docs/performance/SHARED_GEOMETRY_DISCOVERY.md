# Shared geometry discovery

## Scope and repository state

This is Gate 0 for the one-family shared-geometry experiment. It records the
live Daedalon and ERYDON model paths before prototype code is introduced.

- ERYDON repository: `erydon-public`
- Branch at discovery: `main`, tracking `origin/main`
- Commit at discovery: `41a23be16` (`Use Python 3.13 for geometry validation`)
- Canonical remote: `https://github.com/erydon-mod/erydon.git`
- ERYDON version: `1.5.14`, Minecraft `1.20.1`, Fabric compatibility
  generation `2`
- Initial tracked diff: clean
- Preserved unrelated file: untracked root `AGENTS.md`
- Daedalon was inspected read-only. Its checkout already contained unrelated
  local work; none of it was changed by this investigation.

## Verified Daedalon architecture

The current code verifies the architectural lead: Daedalon has 68 OBJ sources,
68 mesh-definition JSON files, and 3,716 blockstates that select
`daedalon:mesh/...` models. The important unit of sharing is a mesh family, not
a blockstate or material model.

Representative trace: Corinthian capital

1. A family definition selects the shared `capital_corinthian.obj` source and
   creates normal and aged variants for every supported stone material.
2. `ObjMeshModelLoadingPlugin.register()` installs one
   `PreparableModelLoadingPlugin` per active family.
3. Its preparation stage runs on the supplied reload executor. It loads the
   mesh definition, parses the OBJ once, creates one `SharedGeometry`, and then
   creates lightweight `PreparedModel` objects for every material variant.
4. The first material model baked for that family calls the synchronized
   `SharedGeometry.bake(...)`. `ObjMeshBakedModel.bakeGeometry(...)` creates one
   immutable Fabric `Mesh`. Later variants receive the same `Mesh` reference.
5. Each `PreparedModel` remains unique where it must: model identifier,
   material and particle texture identifiers, atlas `Sprite` objects, display
   metadata, and world texture-phase layout.
6. At block render time, `ObjMeshBakedModel.emitBlockQuads(...)` pushes the
   material/texture transform and any state-position transform, then calls
   `mesh.outputTo(context.getEmitter())`. The transform binds the selected
   sprite using `MutableQuadView.spriteBake(...)`.
7. Sodium receives the ordinary Fabric Renderer API emission. Daedalon has no
   direct Sodium, Indium, Iris, or OpenGL path for these meshes.
8. Item emission uses the same shared mesh and material transform. Daedalon
   additionally has a bounded rasterized GUI-icon cache, but that is a separate
   inventory optimization and is outside this experiment.

What is shared:

- transformed positions, UV layout, normals, winding, nominal faces, culling,
  and immutable Fabric mesh storage;
- the parsed OBJ and mesh definition for a family during a resource reload;
- cached immutable state transforms where a block type needs them.

What remains per material:

- sprite and particle identity;
- texture identifiers and world-phase flags;
- display metadata and model identifiers;
- a small `ObjMeshBakedModel` binding object.

Reload behaviour:

- preparation creates a new `PreparedModels` graph and new `SharedGeometry`
  objects for each resource reload;
- the model loader atomically adopts that prepared graph;
- therefore old reload-scoped geometry is not kept in a global static cache.

## Verified ERYDON architecture

Representative trace: Gothic column

1. A Gothic-column blockstate points to
   `block/internal/wrapped/<block_id>`, a deliberately empty lightweight
   anchor model.
2. `ErydonFamilyModelLoadingPlugin.extraModels()` requests four component
   resources per material: plinth, base, pillar, and capital.
3. `ErydonRawModelLoadingPlugin` is a preparable loader registered before the
   family wrapper. It asynchronously parses four material-independent Gothic
   authoring files once per reload into immutable `RawModelData`.
4. Resolution maps each material-specific component identifier to one of those
   four `RawModelData` objects plus the material's exact texture identifier.
5. Current baking resolves the selected atlas sprite, walks every raw element
   and face, and creates a new `BakedQuad` plus a new 32-integer vertex array
   for every face of every material-specific component model.
6. `ErydonFamilyModelLoadingPlugin` wraps every placed Gothic-column
   blockstate with `ColumnBakedModel`. `DynamicBakedModelCache` reuses that
   lightweight wrapper across the block's blockstate variants; it does not
   deduplicate the heavy component quads.
7. Continuity's normal `WRAP_LAST_PHASE` wraps the placed blockstate model.
   ERYDON does not order the family wrapper into that phase. At runtime the
   Continuity wrapper pushes its CTM transform, `ColumnBakedModel` selects one
   material component from the baked-model manager, and the component quads
   pass through Continuity exactly once.
8. `ColumnBakedModel.emitBlockQuads(...)` uses Fabric's fallback consumer, so
   Sodium/Indium receives standard Fabric Renderer API output. There is no
   direct Sodium or OpenGL hook.
9. The column item model remains a separate display-oriented vanilla JSON.
   `ColumnBakedModel.emitItemQuads(...)` deliberately uses that wrapped item
   model. The four in-world components do not replace its inventory geometry.
10. `ColumnBakedModel.getQuads(...)` exposes the selected component to Axiom's
    fallback path, so a prototype must retain an equivalent vanilla-quad view.

ERYDON already shares:

- one parsed `RawModelData` authoring graph for each of the four Gothic-column
  components across all materials;
- one lightweight placed-family wrapper per block path across blockstate
  variants;
- the model manager's baked child models at chunk-render time.

ERYDON does not yet share:

- the Fabric/baked geometry backing each material-specific Gothic component;
- the `BakedQuad` lists or their vertex arrays produced during baking.

This distinction matters: the 3,962 lightweight family wrappers found by the
static audit are not evidence of 3,962 copies of heavy geometry.

## Structural-audit evidence available at Gate 0

The supplied deterministic toolkit passed all 29 tests across all eight
stages. Its original strict UTF-8 reader reported the repository's known UTF-8
BOM JSON files as malformed. A temporary copy under `build/tmp` was changed to
read `utf-8-sig`; the toolkit was then re-tested before the repository audit.
No supplied toolkit file or source resource was modified.

The corrected native audit reports:

| Measure | Verified value |
| --- | ---: |
| Model JSON files | 56,113 |
| Blockstate JSON files | 6,963 |
| Model JSON bytes | 47,337,740 |
| Blockstate model references | 81,682 |
| Distinct referenced model resources | 22,671 |
| Resolved model resources | 37,873 |
| Unresolved/external resources | 18,240 |
| Exact resolved-signature groups | 2,497 |
| Conservative geometry groups | 301 |
| Ranked candidate groups | 243 |
| Audit warnings after BOM-compatible read | 0 |

The 4,504 remaining audit errors are unresolved external parent resources,
chiefly `minecraft:block/block`; they are not malformed ERYDON JSON. The raw
reports are under
`build/reports/erydon-shared-geometry/audit/native-compatible/`.

## Pilot selection

The selected pilot is the Gothic column family.

Verified reasons:

- 54 material variants (27 materials, normal and aged) select the same four
  authoring geometries;
- the 216 material component model files are texture/parent bindings totalling
  53,922 bytes, while the four shared authoring models contain the real
  geometry;
- current parsing is already shared, isolating the remaining question: whether
  sharing the baked geometry is worthwhile;
- the family uses ERYDON's real lightweight-wrapper, extra-model, Continuity,
  chunk-emission, Axiom-fallback, and separate-item paths;
- its state selection is bounded to plinth, base, pillar, and capital;
- the Collection 32x and 64x sources contain texture replacements but no
  Gothic-column model overrides, so the same structure can be tested across
  native, 32x, and 64x PBR resources;
- columns are a realistic dense architectural use case without requiring a
  second family or unrelated asset migration.

The audit does not place these models in a conservative group because the
external vanilla parent is unresolved by the standalone toolkit. Direct
resolved inspection confirms that the material files differ only by texture
identity and that all 54 variants route to the same four raw authoring graphs.

## Inferences to test, not established facts

- Replacing 216 material-specific baked component geometries with four shared
  meshes should reduce retained vertex storage and family-local bake work.
- Global startup and reload gains may be small because the pilot is a narrow
  fraction of ERYDON's complete model graph.
- Stationary FPS should remain neutral: the prototype does not reduce emitted
  surfaces, shader passes, shadows, or chunk-buffer vertices.
- Runtime sprite binding adds a quad transform. It must be benchmarked; a
  chunk-rebuild regression above the stated tolerance would reject the design.
- Other high-fan-out families may offer larger savings, but none will be
  migrated or assigned a benefit until the pilot passes correctness and
  performance gates.

Gate 0 passes: both live emission paths are understood well enough to design a
narrow, reload-scoped, switchable Gothic-column prototype.
