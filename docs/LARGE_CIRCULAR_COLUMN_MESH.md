# Large circular column meshes

**Unresolved visual issue (24 October 2026):** large circular columns still show
lighting bands in the user's live shader test. The normal/tangent and continuous
light-sampling checks pass, but neither correction removed the visible banding.
Further lighting work is deferred at the user's request. The current cached-cell
mesh system remains in place; passing source checks are not visual acceptance.

Only placed large circular columns use these meshes. Their block IDs, saved
states, section assembly, collision, materials and aged variants are unchanged.
Small columns, the shared circular JSON masters and inventory models are inputs
and remain unchanged.

`tools/generate_large_column_meshes.py` derives six surface templates from the
owned masters in `assets/erydon/models/block/column/circular/`. It writes
`assets/erydon/meshes/large_columns/`. Regeneration requires the authoring-only
dependency **Shapely 2.1.2**; Minecraft and ordinary tests do not use Shapely.

```
python tools/generate_large_column_meshes.py --check
python -m unittest discover -s tools/tests -p test_model_large_column_mesh_safety.py
```

Five templates sweep the union of authored cross sections into outer shells.
Genuine flute openings and ledges are retained. Enclosed strip-intersection
voids in the solid shaft are filled without moving its outer profile.

The Byzantine capital uses a conservative shared ornament mesh: whole buried
faces are removed, coplanar faces are joined, and coincident opposite internal
interfaces are cancelled. Partially hidden portions of the authored opaque
ornament are deliberately retained; cutting every interlocking band intersection
doubled the GPU primitive count. Its accepted budget is at most 5% more GPU
triangles and 8% more quads than the original renderer, checked using the actual
vanilla bake and production clipping path. This preserves its scroll bands and
the published internal `guilloche` style name without remodeling its ornament.

Only explicitly listed overlap-stagger Y clusters are folded onto their existing
profile planes. Maximum source-model movements are 0.009 units for the shaft,
Georgian capital and Byzantine capital, and 0.017 units for the bases and narrow
capital. At the doubled world scale these equal 0.001125 and 0.002125 blocks.
End bounds remain 0 and 16 model units. Contour simplification is bounded by
0.0009 model units; same-direction ornamental plane welding is bounded by 0.001.
Real decorative features are not rounded to a regular grid. Each generated file
records its measured limits and its master hash, with LF normalization solely
for Git's cross-platform line endings.

At model reload the templates are clipped into their actual 2x2 or 2x2x2 cells.
All materials share the resulting immutable geometry. Native emission applies
the material sprite to its normalized world-cell UVs before the existing
Synapheia wrapper processes CTM. Axiom uses cached quad views of the same mesh.
The original scaled surface's double-precision geometric normal chooses a
texture plane once, before cell clipping or renderer float conversion. A scoped column tag carries that plane through
Synapheia, and Axiom uses the same projection. The renderer's local geometric
light face and smooth normals remain independent: float rounding at a 45 degree
tie can change a cell's light face without flipping texture phase across the
shared plane. UVs are never individually wrapped or clamped to hide that flip.
Vertex normals are angle-weighted within continuous surfaces with a 40 degree
crease limit; profile ledges and flute lips retain hard edges. The established
ambient-occlusion policy is retained. A separately guarded, tagged terrain
normal bridge preserves these normals in the supported Iris/Indium encoder.
The same scoped bridge derives each corner's tangent from its packed normal and
stable world texture plane. This prevents clipped cells, UV precision and chunk
offsets from changing the normal-map frame. Tangent and normal layouts have
independent guards; unsupported tangent layouts retain the renderer's tangent.
The existing AO-disabled policy remains in place: forcing native smooth AO on
packed corner normals introduces quantized lighting seams in the pinned renderer.
During mesh emission, a reusable light sampler blends the surrounding block-centre
sky and block light at each surface vertex. Its lazy 3x3x3 lattice is shared by
the whole cell emission, so adjoining cells use the same world-position lighting
without repeated world lookups for every quad. Narrow Indigo and Indium hooks
replace only the flat renderer's owner-cell light floor; authored light values,
emissive materials, colours and normals retain their normal handling. The sampler
keeps fractional light levels and does not make dark surroundings full-bright.
Its scope ends with the mesh emission, including exceptional and nested draws;
unrelated models and third-party geometry fallbacks retain native lighting.

The conservative Byzantine neck and collar use a normal-only virtual weld of
their actual outer profiles. Shallow concave flute curves and the collar's
rounded joins share normals; convex flute lips, deep corners and high ornament
remain hard. A unit-normal chord maps those profile joins onto the original
partly hidden band endpoints without changing any polygon or crossing a hard
virtual lip. The normal helper never uses a generic radial cylinder field.

A third-party override of a shared master or a material child's inherited
geometry retains the authored model path for that large column. Texture-only
overrides continue using the mesh and the actual resolved sprite. Caches are
scoped to a resource reload.

Source tests compare independent ray envelopes against the original element
volumes and include a missing-cap negative control. The Fabric launch probe
checks actual native emission, Axiom parity, atlas bounds and world texture
phase at 16, 32 and 64 pixels, cache reuse, prepared family registration and
material-specific overrides, alongside existing section-repair/Axiom checks.
Surface-area and primitive counts describe geometry; they do not establish
frame rate, shader appearance or live lighting acceptance. The launch probe
reports old clipping versus cached Axiom-query CPU time separately from triangle
counts and surface area.
