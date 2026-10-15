"""Derive Georgian fixed fanlights, open cluster edges and corner assemblies.

The authored ordinary-window templates remain authoritative. Existing vertices,
UVs and pane coatings are preserved; exposed stone cuts gain implicit-UV caps.
Source hashes use canonical JSON so checkout line endings do not affect the contract.
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


# Removing complete wings/jambs also removes the solids that hid these omitted
# element faces. Keep every original face, and cap only the newly exposed cuts.
# Indices are in the derived assemblies, protected by the source hashes above.
CUT_CAPS = {
    "open_corner_upper_single_lh": {18: ("east",)},
    "open_corner_upper_single_rh": {18: ("west",)},
    "open_corner_upper_multi_lh": {12: ("east",), 32: ("down",)},
    "open_corner_upper_multi_rh": {12: ("west",), 33: ("down",)},
    "open_upper_single_mid": {18: ("west", "east"), 22: ("west", "down"), 23: ("down",)},
    "open_upper_single_edge_lh": {18: ("west",), 22: ("west", "down"), 23: ("down",)},
    "open_upper_single_edge_rh": {18: ("east",), 22: ("west", "down"), 23: ("down",)},
}


def cap_exposed_cuts(suffix: str, model: dict) -> None:
    for index, directions in CUT_CAPS.get(suffix, {}).items():
        element = model["elements"][index]
        if any(face["texture"] != "#stone" for face in element["faces"].values()):
            raise ValueError(f"Cut cap targeted non-stone geometry: {suffix} element {index}")
        for direction in directions:
            if direction in element["faces"]:
                raise ValueError(f"Cut cap already exists: {suffix} element {index} {direction}")
            # These cuts sit inside the cell, so neighbour culling is invalid.
            # Implicit UVs let the existing Continuity repeat rule project them.
            element["faces"][direction] = {"texture": "#stone"}


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def outputs() -> dict[Path, dict]:
    result = {}
    sources = {}
    for suffix, (removed, expected_hash) in WINGS.items():
        source_name = PREFIX + "open_upper_" + suffix
        source = load(MODELS / (source_name + ".json"))
        actual = hashlib.sha256(json.dumps(source, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        if actual != expected_hash:
            raise ValueError(f"Authored {source_name} changed; review wing ownership before regenerating")
        sources[suffix] = source
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

    # The one-wide fanlight is fixed in every column. Remove its full-height
    # outside jamb in a multi-column opening; the original wide edge wings below
    # provide exactly the old outer opening, without any centre leaves.
    single = sources["single_lh"]
    jamb = single["elements"][34]
    if jamb["from"] != [0, 0, 0] or jamb["to"] != [1.5, 14.5, 3]:
        raise ValueError("Authored fixed jamb changed; review open cluster fanlight")
    removed = WINGS["single_lh"][0] | {34}
    fanlight = [element for index, element in enumerate(single["elements"]) if index not in removed]
    for suffix in ("mid", "edge_lh", "edge_rh"):
        derived = copy.deepcopy(single)
        derived["elements"] = copy.deepcopy(fanlight)
        if suffix != "mid":
            wing = suffix.replace("edge_", "multi_")
            derived["elements"].extend(copy.deepcopy(element)
                                       for index, element in enumerate(sources[wing]["elements"])
                                       if index in WINGS[wing][0])
        target_name = PREFIX + "open_upper_single_" + suffix
        result[MODELS / (target_name + ".json")] = derived
        for path in sorted(MODELS.glob("*_closed_upper_single*.json")):
            if path.name.startswith(PREFIX):
                continue
            child = load(path)
            if child.get("parent") != MODEL_ID + PREFIX + "closed_upper_single":
                raise ValueError(f"Unexpected authored parent: {path.name}")
            child["parent"] = MODEL_ID + target_name
            result[path.with_name(path.name.replace("_closed_upper_single", "_open_upper_single_" + suffix))] = child

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
    for path, model in result.items():
        if path.name.startswith(PREFIX):
            cap_exposed_cuts(path.stem.removeprefix(PREFIX), model)
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
    print(f"Georgian open models {'verified' if args.check else 'generated'}: {len(generated)} files; authored geometry preserved; exposed stone cuts capped")


if __name__ == "__main__":
    main()
