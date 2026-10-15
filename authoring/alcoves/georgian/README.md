# Georgian alcove design files

Start with `alcove_georgian_3wide.bbmodel` in Blockbench. It includes the
current stone texture and allows editing the whole assembly without a
single-block size limit. The `.bbmodel` files are editable JSON projects;
matching `.json` files contain the assembled ERYDON model geometry.

Each width is three blocks high. The lower edge is at Y = 0, the front faces
positive Z, and 16 model units equal one Minecraft block.

- `BASE`: lower section, suitable for foot and plinth detailing.
- `MIDDLE`: the repeatable section used to build taller alcoves.
- `TOP`: upper section and crown.

These groups contain `LEFT`, `CENTER`, `RIGHT`, or `SINGLE` column groups,
then labelled `BACK`, `SIDES`, and `ROOF` components. Keep these group names
and add your detailing within the appropriate group. Detail may cross a
cell boundary; the grouping identifies the intended component owner.
Use cubes for new detailing so it can be converted into the current component
models. Cube rotations are supported.

The edited **3-wide BB project is the design master**. Its detailing now also
generates the 1-wide and 2-wide projects, individual Georgian components,
inventory icon, material/finish parents, and collision detailing. The smaller
crowns retain their established proportions, using the edited three-wide curved
shell fitted between the fixed jamb edges. The narrower front ridges have shared
mitred joints, forming one continuous band at the authored perpendicular thickness.
Pilasters, plinths, capitals and the crest retain their authored dimensions.
The one-wide crest and flat roof move together to follow its fitted arch apex.
You do not need to edit the narrower projects unless you want a different design.

After changing the master, run `python tools/generate_georgian_alcove.py` from
the repository root, then use `--check` to verify the generated outputs.
The generator never writes the master BB project or the Modern alcove family.
The middle jamb strips stop at their block boundary so taller alcoves repeat
cleanly. The complete top section owns its side walls and capitals, including
capital cubes placed physically in the top row but grouped in `MIDDLE`.
Microscopic offsets separate overlapping rendered faces while
keeping the authored cubes and their texture phase.
Coordinates may extend beyond 0–16. The runtime `erydon_split_world_uv` option
splits implicit texture mapping into atlas-safe cells without moving geometry.
`erydon_normal_offset` separates only coincident faces, in model units; it is
validated in the range 0–0.05 and applied after texture coordinates are calculated.
Generated curved shells and mitred pieces use `erydon_vertices` for exact
four-vertex faces and native meshes in the narrower Blockbench projects.
The master stays cubes. Roles are identified from the component groups and
geometry, so replacing cubes does not require retaining their original UUIDs.

The assemblies reproduce the current placed geometry, including its open
floor. They are design sources outside the packaged assets. Editing them does
not update the game until the generator is run. Modern keeps its original design.

`assembly_manifest.json` records the source components, cell offsets, and
geometry checks for the initial plain assemblies. `derivation_manifest.json`
records the current edited source and generated outputs.

Blockbench project format reference:
https://github.com/JannisX11/blockbench/blob/master/js/formats/bbmodel.js
