# Swap command

`/erydon swap` changes materials and finishes while keeping the matching block
shape. It covers registered material blocks from ERYDON and installed Daedalon
and Themelios. It requires operator permission level 2.

## Choosing From and To

| Choice | Used as From | Used as To |
| --- | --- | --- |
| `Aganite Polished` | Plain Aganite shapes only | Apply plain Aganite to the same shape |
| `Psamatheon Honed` | Plain Psamatheon shapes only | Apply plain Psamatheon to the same shape |
| `Aganite Aged` | Aged Aganite shapes only | Apply aged Aganite to the same shape |
| `Aganite Trim Bronze` | Aganite bronze-trim shapes only | Apply Aganite bronze trim to the same shape |
| `Aganite Family` | Every Aganite finish, including aged, inlays and weave | Change to Aganite while keeping the existing finish and shape |
| `Bronze` / `Bronze Family` | Daedalon's solid-bronze decoration | Use an existing solid-bronze counterpart |
| `All Family Blocks` | All supported material blocks across the three installed mods | Source only |
| `All ERYDON Blocks` | ERYDON material blocks only | Source only |
| `All Daedalon Blocks` | Daedalon material blocks only | Source only |
| `All Themelios Blocks` | Themelios material blocks only | Source only |

**Honed** is used for Kelastrion, Latmion and Psamatheon. The other 24 stone
materials use **Polished**. These are command labels; block IDs, item names and
textures are unchanged. Old names such as `aganite` still mean its plain finish;
`aganite_family` still means all its finishes. Polished, Honed and Base suffixes
are accepted as plain-finish aliases. Names containing spaces need quotes.

A destination appears when at least one different counterpart exists in the
installed registry, in the source block's own namespace. It no longer needs to
contain every shape in the source's entire catalogue. Suggestions describe
possible conversions, not a scan of the selected area: an area containing only
unsupported shapes can still produce zero replacements.

At execution, missing counterparts remain unchanged and are counted in the
summary. Already-correct blocks are not rewritten. There is no substitute-shape,
air, or cross-mod fallback. For example, a Daedalon statue remains a Daedalon
statue; it is never replaced by an ERYDON building block.

## Finishes and overlays

All 27 stone materials have command definitions for their plain finish, Aged,
Ashlar, Rusticated, Hewn, Rock, Herringbone Bronze and Herringbone Grout.
Only definitions backed by installed blocks appear in the suggestions.

The following inlays are separate finishes, rather than being silently included
in the plain stone choice:

| Motif | Metal choices | Shapes included |
| --- | --- | --- |
| Trim | Bronze, Silver | Every registered shape |
| Guilloche | Bronze, Silver | Every registered shape |
| Quatrefoil | Bronze, Silver | Every registered shape |
| Rosette | Bronze, Silver | Every registered shape |

This includes the extended slopes, shallow/steep and vertical slope variants,
stairs, shallow stairs and multiface layers wherever those counterparts exist.
Herringbone, the authored two-material Weave finishes, and Diaphanes are also
included. Shape lists are read from the installed registry, not a small fixed
list of cubes and slabs.

Whole-material swaps preserve the secondary stone in a Weave finish. If that
exact combination does not exist in the destination, the block stays unchanged.
An explicit finish destination replaces the original finish, including the
secondary stone or metal. It does not create nonexistent aged overlays.

### Changing only the overlay metal

Use `/erydon swap overlay` to change Bronze to Silver, or Silver to Bronze,
across mixed stone materials in one area:

```text
/erydon swap overlay chunk bronze silver
/erydon swap overlay radius silver bronze 8
/erydon swap overlay box bronze silver ~ ~ ~ ~15 ~15 ~15
```

This changes Trim, Guilloche, Quatrefoil and Rosette inlays while keeping each
block's stone material, motif, shape and supported state properties. It covers
matching inlays in the supported installed mods, using counterparts in the same
namespace. Metal names are case-insensitive; suggestions offer the opposite metal.
Solid-bronze decoration, plain stone, Herringbone and Weave finishes are outside
this selection. Missing counterparts stay unchanged and are counted in the summary.
The usual area limits and `/erydon swap undolast` apply to overlay swaps too.
Use `/erydon swap overlay help` for the in-game instructions.

## Examples

```text
/erydon swap chunk "Aganite Polished" "Aganite Aged"
/erydon swap chunk "Aganite Aged" "Psamatheon Honed"
/erydon swap radius "Aganite Family" "Aganite Aged" 8
/erydon swap radius "Aganite Family" "Latmion Family" 8
/erydon swap chunk "Aganite Polished" "Aganite Guilloche Bronze"
/erydon swap chunk "Aganite Trim Bronze" "Nerium Rosette Silver"
/erydon swap chunk "All Daedalon Blocks" Bronze
/erydon swap box "All Family Blocks" "Psamatheon Honed" ~ ~ ~ ~15 ~15 ~15
/erydon swap undolast
/erydon swap help
```

## Area, state and undo behavior

- `chunk` covers the current 16 by 16 chunk at every valid build height.
- `radius` covers an inclusive cube around the command's position, with a radius
  from 1 to 32. It is not a sphere.
- `box` accepts two corners in either order, including relative coordinates.
  Its maximum volume is 524,288 blocks; height is clamped to the world's bounds.
- Each block keeps properties that the counterpart supports with the same name
  and value, including facing, waterlogging, dimensions and manual geometry
  choices. Unsupported properties use the counterpart's defaults.
- Forced architectural placement is preserved. A material swap does not rerun
  survival checks to remove unsupported placements.
- Compatible block-entity data is copied to the new block. Daedalon fountain
  plinths and bowl counts are retained, matching attached plinth materials are
  converted where available, and their assembly interaction parts are refreshed.
- `undolast` affects the last successful swap in that loaded world/dimension,
  shared between operators. It preserves blocks whose state or block-entity data
  changed afterwards. Undo is held in memory, not saved across restarts; opening
  another world cannot reuse the previous world's undo.
- A no-op does not replace the previous undo. All-block choices cannot be used
  as destinations, and identical source/destination selectors are rejected.
- Legacy `all` and `all_erydon` mean ERYDON only. A quoted namespace prefix on a
  material name is retained as a legacy accepted spelling, not a mod filter;
  use the explicit All Daedalon/Themelios source selectors to restrict a mod.

## Coverage audit

The audit uses current ERYDON source blockstates and a compact companion-ID
snapshot in `src/test/resources/swap/companion-blocks.tsv`. The companion snapshot
is checked against supplied live checkouts when the corresponding environment
variables are set. It covers Daedalon's exceptional Spartan ID ordering as well
as canonical aged IDs and ERYDON's published aliases.

The 2026-09-11 audit covers 16,670 material blocks: 9,305 in ERYDON, 4,425 in
Daedalon and 2,940 in Themelios. This includes 2,496 ERYDON inlay shapes and
192 Themelios inlay blocks, with 465 available selectors and 212,499 valid
source/destination combinations. Themelios 1.20.1 and 1.21.1 have the same ID
inventory; the command itself remains part of ERYDON for Minecraft 1.20.1.

Rusticated multiface layers are included for all 27 stone materials. For example,
`/erydon swap box "Glacium Hewn" "Glacium Rusticated" 8304 68 3201 8239 99 3153`
maps `glacium_hewn_layer_multiface` to `glacium_rusticated_layer_multiface`.
Earlier builds lacked the Rusticated block, so the command correctly reported
a missing counterpart and left the Hewn layer unchanged.

Daedalon's `fountain_basin_part` and `monopteros_part` are internal assembly cells,
not separate material variants. Their parent structures own them. ERYDON's 43
non-material blocks (glazing, covers, unframed coffered ceilings and the halo
pendant) have no corresponding stone-material family and remain unchanged.

The tests generate these complete maps under `build/reports/erydon-swap/`:

- `blocks.tsv`: each covered block, its finish/material selectors, and available
  plain/aged counterparts.
- `families.tsv`: each available selector with its per-mod block counts.
- `destinations.tsv`: every possible source/destination pair, including counts
  of mapped, missing and already-correct forms.
- `non-material-blocks.txt`: the exact ERYDON exclusions.

Run `test --tests 'com.oliver.erydon.command.ErydonSwap*'` with the Gradle wrapper.
To compare the snapshot with live sources, set `ERYDON_SWAP_DAEDALON_ROOT`,
`ERYDON_SWAP_THEMELIOS_ROOT`, and optionally
`ERYDON_SWAP_THEMELIOS_MODERN_ROOT` to their checkout roots before running it.

The tests verify IDs, aliases, destination availability, conversion round trips
and fountain data copying. In-game validation is still needed for visible
rendering, force-placed architecture and fountain/roof interaction cells.

Validation completed with 221 tests passing and the ID-migration audit passing.
Windows exhausted system resources during the full asset-copy step, so this run
used the source resources directly on the test classpath, one Gradle worker and
a 1 GB Gradle heap. Compilation and tests passed; the full resource-copy step
and in-game checks have not been validated by this run. No JAR was packaged.
