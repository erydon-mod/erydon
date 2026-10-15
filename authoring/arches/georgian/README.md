# Georgian arch editing

Open **arch_georgian_3wide.bbmodel** in Blockbench. Only this master needs editing. It describes the complete three-wide, three-high open arch; 16 model units equal one block. The saved master is preserved byte-for-byte during generation.

The approved source is the **left side**, mirrored across X for the right side. The center crest uses its left half and matching mirror. Keep new cubes in the relevant `SIDES__base_left`, `SIDES__middle_left`, `SIDES__top_left`, `ROOF__top_left`, or `ROOF__top_center` groups. The base contains the feet, the middle contains repeatable fluting, and the top contains the capital bands, curved frame and crest. Empty back groups are intentional: this family is an open arch.

Run `python tools/generate_georgian_arch.py` after saving the master. `python tools/generate_georgian_arch.py --check` verifies that the runtime resources match it. The current complete examples are `arch_georgian_current_1wide.json` through `arch_georgian_current_6wide.json`; `derivation_manifest.json` records the source hash, fitting rules and validation counts.

Widths 1–3 use one crown row; widths 4–6 use two. Short, one-row examples at widths 4–6 have separate fitted crowns, shown in the matching `arch_georgian_current_4wide_short.json` through `6wide_short.json` files. Feet, flutes, capital bands, crest dimensions and ridge thickness stay fixed while the curve grows. Wider infill follows the fitted curve and flat lintel. Geometry is prepared separately for each crown cell, with cached rendering and collision components.

Blockbench UVs and texture assignments do not set placed texture mapping. All runtime templates use implicit world UVs and the shared atlas-safe split path for Synapheia. The authored front is +Z; placement rotates it toward the selected facing.

All arch families automatically form widths 1–6. The debugging manual width caps 1–5 remain available. Restart the development client after source/runtime changes before checking placement.

`python tools/render_georgian_arch_preview.py` updates the software previews, and `--check` verifies them. These show actual generated geometry at the same scale across widths; live texture joins, lighting, facings and Axiom previews still need in-game verification.
