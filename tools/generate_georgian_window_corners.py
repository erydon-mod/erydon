"""Derive open Georgian corner fanlights without their hinged leaf assemblies.

The authored ordinary-window templates remain authoritative. Retained elements,
including pane coatings and implicit CTM UVs, are copied unchanged. Source hashes
use canonical JSON so checkout line endings do not affect the contract.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MODELS = ROOT / "src/main/resources/assets/erydon/models/block/window/french_georgian"
PREFIX = "window_french_georgian_"
MODEL_ID = "erydon:block/window/french_georgian/"
# Explicit authored assemblies: bars, rails, edge stiles and the swinging pane.
# Do not infer ownership from individual baked faces: front edges of a wing can
# occupy the same depth as a fixed fanlight.
WINGS = {
    "single_lh": ({0, 3, 4, 5, 14, 23, 24, 33, 35, 36},
                  "2e54d3f3d32365f88e300f4d671d300ca45476e5fd2099c5fd0966e387fd4673"),
    "single_rh": ({0, 10, 11, 12, 13, 23, 24, 33, 34, 36},
                  "1d73ad0e58b604bf69837d0759c7de95d88310d4ffaaeb6227641492e1e44ed7"),
    "multi_lh": ({1, 2, 3, 4, 12, 13, 14, 15, 16, 42},
                 "c67d7ae7a4293d62cae906e76298a4624f69a3c8004d5ef9e21c7667f8f2bbaa"),
    "multi_rh": ({1, 2, 3, 4, 5, 13, 15, 16, 17, 43},
                 "1e46234cf495dc81e96598f71a2e062b4fc9d420e8e6bc56fd71cb03e27a861e"),
}


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def outputs() -> dict[Path, dict]:
    result = {}
    for suffix, (removed, expected_hash) in WINGS.items():
        source_name = PREFIX + "open_upper_" + suffix
        source = load(MODELS / (source_name + ".json"))
        actual = hashlib.sha256(json.dumps(source, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        if actual != expected_hash:
            raise ValueError(f"Authored {source_name} changed; review wing ownership before regenerating")
        derived = copy.deepcopy(source)
        derived["elements"] = [element for index, element in enumerate(source["elements"]) if index not in removed]
        # Keep the fixed pane and every authored UV/tint/face attribute unchanged.
        fixed_panes = [element for element in derived["elements"]
                       if any(face["texture"] == "#pane" for face in element["faces"].values())]
        if len(fixed_panes) != 1 or fixed_panes[0]["to"][2] - fixed_panes[0]["from"][2] > 0.3:
            raise ValueError(f"Unexpected fixed pane in {source_name}")
        target_name = source_name.replace("_open_upper_", "_open_corner_upper_")
        result[MODELS / (target_name + ".json")] = derived
        for path in sorted(MODELS.glob("*_open_upper_" + suffix + "*.json")):
            if path.name.startswith(PREFIX):
                continue
            child = load(path)
            if child.get("parent") != MODEL_ID + source_name:
                raise ValueError(f"Unexpected authored parent: {path.name}")
            child["parent"] = MODEL_ID + target_name
            result[path.with_name(path.name.replace("_open_upper_", "_open_corner_upper_"))] = child

    post_name = PREFIX + "open_corner_post"
    result[MODELS / (post_name + ".json")] = {
        "ambientocclusion": True,
        "elements": [{
            "from": [0, 0, 0], "to": [3, 16, 3],
            "faces": {face: {"texture": "#stone"} for face in ("north", "east", "south", "west", "up", "down")},
        }],
    }
    for path in sorted(MODELS.glob("*_closed_lower_single*.json")):
        if path.name.startswith(PREFIX):
            continue
        child = load(path)
        if child.get("parent") != MODEL_ID + PREFIX + "closed_lower_single":
            raise ValueError(f"Unexpected material parent: {path.name}")
        child["parent"] = MODEL_ID + post_name
        result[path.with_name(path.name.replace("_closed_lower_single", "_open_corner_post"))] = child
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify current generated outputs without writing")
    args = parser.parse_args()
    generated = outputs()
    mismatches = []
    for path, content in generated.items():
        if args.check:
            if not path.is_file() or load(path) != content:
                mismatches.append(str(path.relative_to(ROOT)))
        else:
            path.write_text(json.dumps(content, indent=2) + "\n", encoding="utf-8", newline="\n")
    if mismatches:
        raise SystemExit("Outdated Georgian corner models:\n" + "\n".join(mismatches))
    print(f"Georgian corner models {'verified' if args.check else 'generated'}: {len(generated)} files; retained fanlight elements unchanged")


if __name__ == "__main__":
    main()
