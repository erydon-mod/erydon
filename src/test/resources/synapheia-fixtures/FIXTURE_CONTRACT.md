# Development fixture contract

This directory is generated evidence input, not production content.

- Namespace: `synapheia_dev`.
- Repeat family: 6×6, row-major, numbered 0–35.
- Albedo, `_n` and `_s` companions are present for every phase.
- Models include local, signed out-of-cell and boundary-spanning coordinates.
- `phase_cases.json` contains deterministic floor/cell and clipping expectations.
- Face flips and rotations are covered by the signed-face matrix and live visual checks.
- Coordinates beyond vanilla model limits may require the live Erydon-owned raw/custom model path.
- The production JAR checker forbids this namespace by default.

Codex should copy only the minimum fixture into an existing development source set, never into production resources.
