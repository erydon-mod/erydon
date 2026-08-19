# Synapheia CTM architecture

Synapheia is ERYDON's built-in connected-texture engine. Continuity is not
required or bundled.

## Rule discovery

At resource reload, Synapheia discovers active `.properties` files below
`optifine/ctm` and `mcpatcher/ctm`. Rules are accepted only when `matchBlocks`
targets ERYDON. Published legacy block IDs are resolved through ERYDON's
approved ID-migration map so the same rule also covers its canonical block.

Supported production contracts are:

- `method=repeat`, `width=6`, `height=6`, exactly 36 tiles;
- `method=overlay_ctm`, exactly 47 tiles, translucent layer;
- `connect=block`, `faces`, `matchTiles`, `innerSeams`, and `priority`.

Unsupported ERYDON rules fail during reload instead of silently rendering with
the wrong mapping. Foreign namespaces are left untouched.

## Rendering

The final Synapheia wrapper runs after ERYDON's specialised slope, spiral-stair,
Gothic-arch, and Modern-arch model phases. It captures the quads emitted by the
wrapped Fabric model, so both ordinary JSON models and runtime-assembled custom
models use the same path.

Repeat faces are split on signed world-cell boundaries and mapped to their 6x6
tile. This preserves automatic UV assignment and geometry extending below zero
or beyond the normal 0..16 model bounds without changing texture phase.

Connected overlays use the standard 47-tile edge-and-corner mask. Neighbours
connect by canonical block identity, with `innerSeams` and face orientation
applied before the translucent overlay quad is emitted.
