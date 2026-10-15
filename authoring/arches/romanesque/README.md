# Romanesque arch editing

Open **arch_romanesque_3wide.bbmodel** in Blockbench. This is the complete editable three-wide arch, four blocks high, with the column setting enabled. The Free Model format lets you edit the whole structure without a single-block size limit. The saved project is preserved exactly as you edit it.

Only the `.bbmodel` needs editing. Save that same file after adding your detailing; Codex can then extract its components and adapt the changes across widths 1–6, repeated heights, and material/finish parents.

- `CROWN` contains the upper curved corners, their walls, and the central keystone.
- `CURVE_LOWER` contains the lower curved corners and walls.
- `CAPITALS` contains the column transitions and the first shaft row.
- `BASE` contains the lower shaft row and plinths.
- Each row has `LEFT`/`RIGHT` groups; the crown also has `CENTER`. Keep these names and put new cubes inside the relevant `CURVE__`, `WALL__`, `CAPITAL__`, `COLUMN__`, `PLINTH__`, or `KEYSTONE__` component group.

16 model units equal one Minecraft block. The complete structure occupies X=0–48, Y=0–64, Z=0–16, with small existing authored projections. It uses the renderer's NORTH-facing orientation (-Z), extends across +X, and is open on both faces. Component rotations have already been applied, so every editor group has zero rotation.

The base/body shaft and capital-row shaft have separate components so their saved face choices can differ. Edit the matching `COLUMN__base_left` or `COLUMN__capitals_left` group; plinth-only and capital-only details belong in their separate groups. Width 3 is enough as the main design reference. Narrower examples are needed only if a specific detail leaves a design choice unclear. Widths 4–6 reuse the three-wide crown with proportional width/height growth.

The left side is the approved design source. Runtime models mirror it across X for the right side while keeping the same depth profile. The center keystone uses its left half and the matching mirror. Your saved enabled and disabled faces are preserved for both original and added cubes; no automatic face-removal pass overrides them. Editor UVs do not control placed textures: runtime templates use implicit world UVs.

`arch_romanesque_3wide.json` and `assembly_manifest.json` record the initial runtime geometry. `source_components.json` is the immutable source fixture used to retain narrow-arch proportions and unchanged column/plinth geometry. Keep these references so future derivation remains repeatable. The current derived assemblies are `arch_romanesque_current_1wide.json` through `arch_romanesque_current_6wide.json`; `derivation_manifest.json` records the edited master hash, adaptation rules and checks.

Do not replace the master by exporting it through the single-block Java format. Keep the editable `.bbmodel`; cube-based detailing preserves the simplest conversion path.

Regenerate runtime models and the current assembled references with `python tools/generate_romanesque_arch.py`. Check that they match the saved master with `python tools/generate_romanesque_arch.py --check`. The generator does not write the editable `.bbmodel`. The initial export tool refuses to overwrite an edited master.

The separate non-column setting is editable in **arch_romanesque_non_column_3wide.bbmodel**. It contains the current shared crown and plain jambs, assembled three blocks wide and four high. See [NON_COLUMN_README.md](NON_COLUMN_README.md) for its groups and how its edits will be carried across the family. Save changes in that `.bbmodel`; the exporter preserves edited masters.
