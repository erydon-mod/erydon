# Romanesque arch without columns

Open **arch_romanesque_non_column_3wide.bbmodel** in Blockbench and save your edits in that same file. It is the complete 3-wide, 4-high master for the non-column setting. The original `arch_romanesque_3wide.bbmodel` remains the separate column-setting master.

- `BASE` and `BODY` contain the jambs. Edit the side nearest X=0; the opposite side is mirrored during generation. If the bottom needs its own detail, keep that edit in `BASE`.
- `CURVE_LOWER` contains the lower arch curve and its adjacent wall.
- `CROWN` contains the upper arch curve, adjacent wall, and central keystone. Non-column arch and wall edits remain independent of the column setting. The common keystone comes from the column-setting master.
- Keep the existing row, side and component group names. Put new elements in the appropriate component group so later extraction remains unambiguous.

The sides and jambs are ordinary editable cubes. The central keystone uses editable meshes because its exact mirrored shape includes angled cuts through the middle. Both are editable in this Generic/Free Model project. Keep this format rather than converting to a single-block Java model.

16 units equal one Minecraft block. The assembly spans X=0–48, Y=0–64, Z=0–16, with the authored projections. The front is NORTH (-Z). Keep editor group rotations at zero. Blockbench UVs and texture assignments are editor preview data; placed models use automatic world texture mapping.

The matching initial JSON, `non_column_assembly_manifest.json` and `non_column_assembly_preview.png` preserve the original export reference. Current generated geometry is in `arch_romanesque_non_column_current_1wide.json` through `arch_romanesque_non_column_current_6wide.json`. The `non_column_assemblies` and `non_column_assemblies_wide` previews show the current family. Saved enabled/disabled face choices are preserved; generation does not remove faces automatically. Tiny runtime face offsets prevent flicker without changing the editable master.

Only the `.bbmodel` needs editing. After saving it, run `python tools/generate_romanesque_arch.py` to update widths 1–6, repeated heights, material/finish models and interaction shapes. Check the generated files with `python tools/generate_romanesque_arch.py --check`. The generator reads both saved masters independently and never rewrites either `.bbmodel`.

The initial exporter is only for creating the starting editable assembly. Its manifest records the exported master's SHA-256, and it refuses to re-export an edited master. Use the family generator above for normal integration of saved edits.
