# Georgian wall coping

One item per stone and finish fits a full cube, a straight upward-facing slope,
or a flat diagonal wall made from vertical slopes.
There are 135 items: 27 materials in Polished, Aged, Rusticated, Hewn and Ashlar.
Placement chooses flat, standard, shallow lower/upper or steep lower/upper geometry
and copies the support's direction. Coping follows suitable support updates and
can be waterlogged. It can also be placed freely and keeps its profile when its
support is removed. Non-planar slope corners are outside this family.

The coping is 3.6 model pixels thick, a 20% increase from the original 3 pixels.
It grows above the support; the underside stays flush and the overhang is retained.
Breaking uses one simple debris box (at most 64 particles) while collision and
targeting retain the detailed profile.

## Horizontal diagonal walls

Vertical 45-degree wedges and opposing shallow broad/narrow wall pieces select
a horizontal coping automatically. These caps rotate around Y and translate
over the actual wall strip; they never acquire a pitch. The existing six saved
`surface` values remain unchanged. Nine additional flat fits encode the 45-degree
wall and the four shallow strip widths with both hands, avoiding impossible
pitched/diagonal blockstate combinations. Owner cells remain directly above their
supports, even where the visible centre crosses an owner-cell edge.

In the native NORTH wall frame the fitted cap uses these dimensions in blocks.
LEFT shallow fits reflect centre Z across 0.5 and reverse the yaw; quarter rotations
then rotate the complete centre and mesh around the block centre.

| Fit | Centre X/Z (RIGHT) | Core run length | Core width | Yaw |
| --- | --- | --- | --- | --- |
| 45 degrees | 0.25 / 0.25 | sqrt(2) | sqrt(0.5) | -45 degrees |
| Broad | 0.30 / 0.40 | 3/sqrt(5) | 2/sqrt(5) | -atan(2) |
| Broad wide | 0.10 / 0.30 | 3/sqrt(5) | 3/sqrt(5) | -atan(2) |
| Narrow | -0.15 / 0.30 | 2.5/sqrt(5) | 2/sqrt(5) | -atan(2) |
| Narrow thin | 0.05 / 0.40 | 2.5/sqrt(5) | 1/sqrt(5) | -atan(2) |

Shallow fits require an actual opposite-facing, same-hand partner sharing a
nonzero support edge. Conflicting widths or a partner with no reciprocal fit
do not guess an alignment. The saved fit stays stable if the support is removed.
A standalone 45-degree wedge has a canonical fit whose free ends cover its tips.

Straight, 45-degree and shallow runs discover shared horizontal ends from their
physical core footprints. Both ends must choose each other. Corner-only contact,
distant crossings and incompatible parallel width steps retain their free ends.
Connected end bevels are removed. The remaining run extends to the sharp corner,
then all shared ends clip against their actual mitre cuts together. A second
connected port cannot move the first seam. The cut preserves physical overhang and
bevel offsets on both widths, including the small inward part of the authored
side bevel. Placed selection and collision use the same expansion and cuts, sampled in
1/32-block rows; the standalone Axiom preview uses the saved state pose.
Placed shapes also include surviving side-bevel strips with their fixed perimeter
cuts, so the small inward bevel lips remain selectable at mixed and corner joins.
Discovery also checks the core, upper band's overhang and base overhang separately:
a shared cut may extend its joined end but must leave the opposite free end intact.
Geometrically incompatible short pieces keep their original free caps.
Side bevels have a fixed 0.03125-block run overhang, even where their rotated
cross-section projects up to 0.06712616 blocks beyond the wall. Their span is
checked separately so a join cannot borrow the base's larger overhang.
At a 63.435-degree straight/shallow turn, the shared centre cut can lie beyond
the flat cap's original end. Turns with a flat cap allow that extension by at most half
the smaller wall width, while at least one centre cut must remain within its
original run; distant crossings still retain their free ends. Other diagonal joins
retain their existing centre reach limit.

Horizontal reciprocal discovery caches at most 25 same-height owner cells per lookup;
flat caps retain the existing bounded pitch-facing lookup.
Ordinary flat outlines first check eight adjacent owner cells and reuse their
original cached shape when no diagonal fit is present.
Placed joined outlines and each material's meshes have separate bounded caches
of 1,024 entries. Picking checks at most 24 owner neighbours only while a ray
crosses the thin horizontal cap slab; this covers extended mixed-width corners
within two owner cells. No global world scan or per-frame global lock is used.

`CopingBlock.attachmentPose(state)` exposes the saved centre, yaw, top height,
gradient, run length and width for optional upright attachments. It is state-only;
neighbour mitres do not move the attachment centre. Flat caps retain any existing
pitch mitre and continuous normals on a different end while joining a horizontal wall.

Neighbouring pieces trim their shared overhangs and end bevels. Where different
inclines meet, short end sections automatically form a shared mitre. These stubs
are derived from the editable models, with interpolated UVs; no separate model
for each angle combination is required. Flat caps orient their joints along the
adjacent ramp. Only endpoints that actually share a position and height join.
Exposed edges retain the authored overhang. The two steep pieces can be capped
even when stacked: the upper cap uses the adjacent empty cell when the lower
slope occupies its usual cell. Its outline, collision and targeting follow the
visible geometry. The adjacent cell must be free.

## Editing geometry

Edit these Minecraft 1.21.11 Blockbench sources directly:

`src/main/resources/assets/erydon/authoring_models/block/coping/georgian/`

The flat source preserves the supplied design. The five pitched files are editable
models with a `coping_profile` rotation group. Model reload reads them through
ERYDON's raw parser; there is no vanilla rotation conversion. Keep the named
`edge_west`, `edge_east`, `edge_north` and `edge_south` elements so shared-end
bevels are removed correctly. `#stone` binds to the selected material at reload.
All pieces of a pitched profile belong to its `coping_profile` group, including
the four bevels. Saved positions and rotations are rendered directly, without
an additional runtime stretch. The edge names describe their final positions
before the profile pitch: west/east are the low/high X ends and north/south the
low/high Z ends. Their authored texture planes remain stable
across equal inclines and transition stubs instead of following lighting normals.
The texture plane is stored in each mesh quad's tag and rotated with the model.
Fabric mesh replay restores nominal faces from lighting normals, so nominal-face
metadata alone cannot retain texture alignment at incline mitres.
Profile tops and undersides use continuous vertex normals through the short
mitre sections, including a shared normal at each incline join. This avoids a
lighting step at the internal 20% split with vanilla smooth lighting; rotated
bevels keep their sharp normals. CTM still uses the authored texture plane.
The authored cropped UVs remain within the texture atlas. Covered bevel end faces
are trimmed against the upper band when rendering, avoiding coplanar flicker
while preserving the editable source geometry.

Horizontal diagonal fits are derived from the edited flat source at model reload.
`CopingHorizontalFit` contains the plain centre/angle/core-width/core-length table.
Core body coordinates are resized about (0.5, 0.5). Coordinates outside the core
keep their authored distance from its edge, and the named edge bevels translate
to the resized edge without changing their cross-section. Y coordinates and the
authored UVs remain unchanged. Editing the flat parent therefore edits all these
fits; there are no hidden rotation groups or separate generated parent copies.
Placed Y transforms retain authored face tags and Synapheia projects from the
transformed world positions, including faces crossing a texture-cell boundary.
Individual boundary-crossing UVs are never shifted or wrapped.

`python tools/generate_coping.py` generates material wrappers, loot, tags,
translations and native CTM matches. It preserves edits to all six authoring
files. `--check` verifies those generated assets without writing. To deliberately
recreate the five slope files from a changed flat model, use `--derive-slopes`.
If changing the profile's thickness or overhang, update `CopingBlock.shapes()`
and check the outline/collision against the edited model.
If editing the flat side bevels, also check the `SIDE_BEVEL_*` metrics in
`CopingConnections`; the raw-model regression checks these against all four
authored bevels and identifies any metric that needs updating.

Each material/finish also has a dedicated native `coping.properties` CTM rule.
An older Collection pack can override the original material rule without knowing
the new coping IDs; the dedicated rule survives and uses the pack's existing tile
paths. The optional packs and their textures do not need rewriting.

## Validation

`./gradlew --no-daemon test verifyCopingPlacement` checks geometry, join clipping,
UV interpolation, all registered variants, support profiles, rotations, mirrors,
waterlogging, stacked steep placement and Minecraft's actual raycast entry point.
It does not open a game window or create a mod JAR. A restarted client still needs
the visual check for seams, lighting, CTM, selection and optional pack/shader use.
