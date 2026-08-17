# Shared geometry prototype architecture

> This document records the original Gothic-column pilot. The later compatible
> family batch is documented in `SHARED_GEOMETRY_BATCH_RESULTS.md`; it keeps the
> same opt-in switch and conservative fallback rules.

## Decision

The Gothic-column pilot keeps ERYDON's existing model-loading and Continuity
path, but adds an opt-in bake mode that stores four immutable Fabric meshes
instead of 216 material-specific `BakedQuad` geometries.

The production default remains `baseline`. No blockstate, model resource,
registry identifier, item model, collision shape, or resource-pack asset is
removed or renamed.

## Modes and rollback

The development system property `erydon.shared_geometry.mode` accepts:

- `baseline`: the existing material-specific baked-quad path;
- `shared_geometry`: the Gothic-column pilot.

Missing, blank, or invalid values select `baseline`. The two paths use the same
resources and can be compared without restoring files or rebuilding assets.
Rollback is therefore one property change or simply omitting the property.

## Reload-owned data model

Every Fabric resource preparation creates a new prepared model graph. The
pilot's four `SharedGeometry` objects live only in that graph, so a later
reload replaces them rather than retaining a global static mesh cache.

The design separates three identities:

| Identity | Contains | Excludes |
| --- | --- | --- |
| `GeometryKey` | The exact Gothic authoring-model resource, including positions, UV layout, winding, faces, culling and transforms | Material texture identifiers |
| `MaterialBinding` | Exact surface and particle texture identifiers resolved for the active resource pack | Vertex storage |
| `AssemblyKey` | The selected component model identity used by the column assembly | Material identity |

The pilot is deliberately narrow: only the four Gothic-column authoring models
can produce shared meshes.

## Bake and emission flow

In `baseline`, each of 54 normal/aged material variants bakes four component
models. That creates 216 independent backing geometries.

In `shared_geometry`:

1. The existing preparable raw loader parses the same four authoring JSON files.
2. The first material requesting each `GeometryKey` synchronously builds one
   immutable Fabric `Mesh`.
3. The other 53 material variants receive lightweight baked models referring
   to that mesh and their own `MaterialBinding`.
4. `ColumnBakedModel` selects the same component for the current block state.
5. `SharedGeometryChildModel` emits directly into the already-wrapped Fabric
   render context.
6. A pushed quad transform applies the active sprite with normalized UVs, then
   the immutable mesh emits through the normal Fabric Renderer API.

The synchronized first bake makes creation safe when model baking is parallel.
The finished Fabric mesh is immutable, and render calls only use request-local
material transforms. No geometry is parsed or generated during chunk building.

## Continuity and renderer compatibility

The family wrapper remains registered through the established simple
`modifyModelAfterBake()` path. It is not moved into `WRAP_LAST_PHASE`.

Continuity therefore remains the outer wrapper. Its transform is already on the
render context when `ColumnBakedModel` selects and directly emits the shared
child. Sodium/Indium receives ordinary Fabric Renderer API mesh output; the
prototype has no direct Sodium, Iris, OpenGL, or shader hook.

Exact material identifiers are retained until render emission, so Continuity
and LabPBR resource companions see the resource-pack-selected sprite rather
than a generic material alias.

## Resource-pack override safety

The preparation stage inspects all 216 Gothic component resources in the active
resource graph.

- A texture-only child override can keep sharing and supplies its exact surface
  and particle identifiers as the material binding.
- A model with elements, a non-canonical parent, an unresolved texture slot, or
  another ambiguous structural change uses the original JSON bake path.
- The fallback applies per affected material component; it does not disable the
  other safe shared components.

A deliberately altered Aganite base proved this route at runtime: one component
fell back, the remaining 215 material models used the pilot, and the scene
rendered without a crash or ignored override.

## Item and Axiom paths

`ColumnBakedModel.emitItemQuads()` is unchanged and continues to emit the
wrapped display-oriented item model. Shared in-world components never replace
inventory geometry.

The vanilla `getQuads()` view used by Axiom remains available. A shared child
can lazily materialize equivalent vanilla quads for that compatibility path;
the normal Fabric/Sodium block path still emits the shared mesh directly.

## Diagnostics and production JAR boundary

`ErydonSharedGeometryMetrics` is a dormant no-op facade in production. The
collector, development sink, benchmark harness, and benchmark-world tool are
excluded from the production JAR. They are loaded reflectively only when an
explicit development property is present.

The release JAR retains only the runtime mode switch, shared mesh implementation,
small no-op facade, and existing fallback path. The clean JAR inspection found
no benchmark harness, metrics provider, development sink, test class, report,
toolkit file, or machine-specific path.

## Non-goals

This prototype does not:

- migrate another family;
- reduce emitted surfaces or chunk-buffer vertices;
- merge faces across blocks;
- replace Continuity or implement Synapheia;
- convert JSON geometry to OBJ;
- change inventory icons;
- make `shared_geometry` the default.
