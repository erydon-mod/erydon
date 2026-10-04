# Diagonal overlay feasibility

Diagonal borders are feasible, but the existing 47-tile selector describes four
axis-aligned borders and their corner neighbours. Its diagonal bits decide
whether a corner connects; they do not draw a sloping border along a triangle's
hypotenuse. Adding more neighbour checks alone cannot supply that artwork.

Synapheia already captures actual placed polygons, clips overlays to their
geometry and caches slope outlines. A small prototype can use those outlines to
draw narrow strips along exposed diagonal edges, sampling the existing border
tiles. Joined edges would omit their strips, using the same physical shared-edge
tests as the current triangular-side connections. The middle of the overlay
would retain its existing world texture phase.

Corner motifs are the main limitation: rotating an existing edge strip can work
for a simple trim, but intricate Guilloche, Quatrefoil and Rosette patterns need
careful angled joins, and may need additional tiles. Normal maps, metallic maps,
POM atlas boundaries and all three native/Collection resolutions must agree with
the new mapping. Reusing existing textures is a prototype, not proof that every
motif will look correct.

Start with one trim on standard and shallow triangular sides in all facings,
including upside-down, cube joins and gaps. Keep it limited to the Trim family;
current packs and the existing selector should continue to work. Cache border
geometry by slope state, edge connections and resource-reload generation, and
reuse the render-call neighbour cache. Measure chunk rebuild cost and retained
mesh memory before extending it to the full catalogue.

## Trim prototype

The first prototype is now enabled for bronze and silver Trim on standard and
shallow ramp sides, including upside-down placements. It draws the real diagonal
outline of a triangular or trapezoidal side while retaining the existing overlay
and stone texture projection in its interior. The straight Trim line uses the
existing tile 14, which has only its top border; no artwork or resource-pack
assets have changed. Set `-Derydon.debug.diagonalTrim=false` to disable this
prototype when comparing it in a development run.

The side uses the fully connected interior tile, preserving its world-phased
stone substrate. Its borders are composed from the coplanar union of the actual
nearby triangular, trapezoidal and cube faces. Shared axis edges are removed,
including partial joins; diagonal and axis lines meet at an angular-bisector
mitre. This removes the old square CTM corner underneath a diagonal and stops
acute ends from running past the inset perimeter. The inset diagonal continues
through a neighbouring cube corner at a raised/lowered slope join.

The native sides retain a 0.001-high end to make their POM quads non-degenerate.
Border topology welds that subpixel safety step to the true axis endpoint, so
the visible metal mitres to the horizontal/vertical stroke rather than to the
tiny artificial edge. Actual face clipping and substrate UVs retain the authored
geometry. Pixel-visible tests read both native tile-14 albedos and check every
opaque row (6–8 of the 64-pixel texture) on real safety-step profiles, in four
facings, both halves and both slope directions.

Cube faces enter this path only beside a matching Trim slope with a real
diagonal side. Ordinary cube faces and detailed motifs keep their existing
selector. The union uses at most nine coplanar cells in the existing bounded
render-call neighbour cache. Geometry and POM-safe UV rectangles are cached by
the shared outlines and relative placements, emitted once per whole side and
cleared on resource reload. A segmented cache retains at most 4096 composition
plans and avoids a global lock on ordinary render lookups. Occluded neighbour
faces do not erase a visible border, and detached diagonal corners do not
activate the cube path. Tests cap additional geometry at 48 primitives per
tested face, including gradient pairs; this is a structural bound, not a chunk
performance measurement. Steep and vertical slope families remain outside this
prototype.

The native resources and active Collection 64x compat2 v1.5.17 rules both cover
all 144 relevant slope IDs: 24 stone materials, two metals and three profiles.
The Collection overrides contain no shared Trim artwork, so both use the native
albedo, normal and specular channels. Automated checks cover four side facings,
upright/inverted profiles, clipping, joined edges and gaps, atlas bounds, Iris
triangle bounds, merged strip outlines, raised cube corners, acute mitres,
gradient pairs and cache reuse. In-game visual and shader
checks, plus a chunk-rebuild performance comparison, remain necessary before
expanding this renderer to other motif families.

## Artwork for detailed motifs

### Existing tiles used by Trim

The prototype does not add diagonal PNG tiles. It turns the existing straight
border in tile `14.png` along each exposed polygon edge, and uses the transparent
tile `26.png` for the connected interior. The renderer computes the inset and
corner joins from the placed geometry. Cube-to-hypotenuse connections use the
ordinary 47-tile selector and remove the border at a real shared edge, even when
the two visible faces point in different directions. Edge lookups include cubes
directly above or below a ramp endpoint, as well as raised/lowered cells across
the ramp. Corner tiles still require both joined edges and the actual projected
diagonal neighbour. Visibility is checked on the cube's joining face; a cube
touching only a hypotenuse endpoint does not hide the whole tilted face.

The native artwork is in
`src/main/resources/assets/minecraft/textures/optifine/ctm/overlay/trim/bronze/`
and the parallel `trim/silver/` folder. Both are 64 by 64 pixels. Each albedo has
matching normal and specular files: `14_n.png`, `14_s.png`, `26_n.png` and
`26_s.png`. Preserve transparent space and the position of the straight line
when matching new artwork to these tiles.

Other inlays use the same numbered layout under `overlay/guilloche/`,
`overlay/quatrefoil/` and `overlay/rose/` (Rosette), each with `bronze/` and
`silver/` folders. Their wider, repeating borders need a wider ribbon and a
repeat-length-aware mapping before this prototype can render them correctly.
Copying Trim's artwork layout alone does not enable their diagonal renderer.

One 45-degree corner can seed a prototype if its ends match the existing
straight border and its design can safely be rotated and mirrored. That does not
guarantee a complete motif set: acute and obtuse joins, inward and outward turns,
and intersections can require different compositions. An asymmetric repeating
motif may also need both handed versions. A useful starting sheet contains one
diagonal straight section and a corner joining it to the existing horizontal
border, with a marked repeat length and intended mirroring rules.

Matching normal/specular channels can be derived from an approved material or
height mask and the existing metal/stone settings. Colour alone does not fully
specify relief or material properties. When rotating a finished normal map,
transform its tangent-space X/Y vector components as well as its pixels; scalar
specular/material channels need the same pixel transform without that vector
rotation. The runtime ribbon's UV basis handles the rotated tangent frame for
the current Trim textures.
