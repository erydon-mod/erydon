"""Separate derived Georgian faces across every child and cluster layout.

Only the runtime face bias is changed. The editable project, element bounds,
vertex positions, and texture phase remain authored design data.
"""
from __future__ import annotations

import copy
import math
from collections import defaultdict

import model_z_fighting_safety as geometry

STEP = .00025
Node = tuple[str, int, str]


def state_children(width: int, column: int, part: str) -> tuple[str, ...]:
    prefix = "" if width == 1 else "double_" if width == 2 else "triple_"
    side = "left" if column == 0 else "right" if column == width - 1 else "center"
    sides = "single_sides" if width == 1 else prefix + "side_" + side
    crown = "single_top" if width == 1 else prefix + "top_" + side
    suffixes = ["single_back"]
    if part in ("single", "top"):
        suffixes.append(crown)
    else:
        suffixes.append(sides)
        if column == 0:
            suffixes.append(prefix + part + "_detail_left")
        if column == width - 1:
            suffixes.append(prefix + part + "_detail_right")
    return tuple("alcove_georgian_" + suffix + ".json" for suffix in suffixes)


def _translate(element: dict, dx: int, dy: int) -> dict:
    result = copy.deepcopy(element)
    delta = (dx, dy, 0)
    for key in ("from", "to", "origin"):
        if key in result:
            result[key] = [value + offset for value, offset in zip(result[key], delta)]
    rotation = result.get("rotation")
    if isinstance(rotation, dict) and "origin" in rotation:
        rotation["origin"] = [value + offset for value, offset in zip(rotation["origin"], delta)]
    for face in result.get("faces", {}).values():
        if "erydon_vertices" in face:
            face["erydon_vertices"] = [[value + offset for value, offset in zip(point, delta)]
                                       for point in face["erydon_vertices"]]
    return result


def separate(models: dict[str, dict]) -> dict[str, int | float]:
    """Color shared source faces once, including overlaps across owner cells."""
    neighbours: dict[Node, set[Node]] = defaultdict(set)
    observations = 0
    verifying = False

    for name, document in models.items():
        rotations, _ = geometry._raw_group_state(document, len(document["elements"]))
        if rotations:
            raise ValueError(f"Bake group transforms before global alcove face separation: {name}")
        for element in document["elements"]:
            for face in element.get("faces", {}).values():
                face.pop("erydon_normal_offset", None)

    def scan(document: dict, identities: list[tuple[str, int]], label: str) -> None:
        nonlocal observations
        report = geometry.scan_model_document(document, relative_path=label, source_sha256="",
                                             raw_authoring=True, enable_raw_transforms=True)
        unexpected = [entry for entry in report["unsupported"]
                      if entry["reason"] != "zero-thickness element is unsupported"]
        if unexpected:
            raise ValueError(f"Unsupported geometry while separating {label}: {unexpected}")
        for finding in report["findings"]:
            if finding["orientation"] != "same" or finding["plane_quality"] != "exact":
                continue
            nodes = []
            for endpoint in ("a", "b"):
                record = finding[endpoint]
                filename, index = identities[record["element"]]
                nodes.append((filename, index, record["face"]))
            first, second = nodes
            if verifying:
                raise ValueError(f"Face separation left an overlap in {label}: {first}, {second}")
            if first == second:
                raise ValueError(f"One repeated source face overlaps itself in {label}: {first}")
            neighbours[first].add(second)
            neighbours[second].add(first)
            observations += 1

    def layout(cells: list[tuple[int, int, int, str]], label: str) -> None:
        elements, identities = [], []
        for width, column, row, part in cells:
            for name in state_children(width, column, part):
                document = models[name]
                for index, element in enumerate(document["elements"]):
                    elements.append(_translate(element, column * 16, row * 16))
                    identities.append((name, index))
        scan({"elements": elements}, identities, label)

    for name in sorted(models):
        document = models[name]
        scan(document, [(name, index) for index in range(len(document["elements"]))], name)
    state_count = 0
    cluster_count = 0
    layouts = []
    for width in (1, 2, 3):
        for column in range(width):
            for part in ("single", "base", "middle", "top"):
                cells, label = [(width, column, 0, part)], f"state/{width}/{column}/{part}"
                layouts.append((cells, label))
                layout(cells, label)
                state_count += 1
        for height in (1, 2, 3, 4):
            parts = ["single"] if height == 1 else ["base"] + ["middle"] * (height - 2) + ["top"]
            cells = [(width, column, row, part) for row, part in enumerate(parts) for column in range(width)]
            label = f"cluster/{width}/{height}"
            layouts.append((cells, label))
            layout(cells, label)
            cluster_count += 1

    levels: dict[Node, int] = {}
    for node in sorted(neighbours, key=lambda value: (-len(neighbours[value]), value)):
        occupied = {levels[neighbour] for neighbour in neighbours[node] if neighbour in levels}
        level = next(value for value in range(len(occupied) + 1) if value not in occupied)
        levels[node] = level
    max_level = max(levels.values(), default=0)
    max_offset = max_level * STEP
    if not math.isfinite(max_offset) or max_offset >= .05:
        raise ValueError(f"Alcove face separation exceeds its microscopic offset limit: {max_offset}")
    for (name, index, face), level in levels.items():
        if level:
            models[name]["elements"][index]["faces"][face]["erydon_normal_offset"] = round(level * STEP, 8)
    # A new bias can coincide with a different authored plane. Verify the final
    # emitted geometry before the caller writes any derived resources.
    verifying = True
    for name in sorted(models):
        document = models[name]
        scan(document, [(name, index) for index in range(len(document["elements"]))], name)
    for cells, label in layouts:
        layout(cells, label)
    return {
        "standalone_models_checked": len(models),
        "single_cell_states_checked": state_count,
        "cluster_layouts_checked": cluster_count,
        "observed_overlap_pairs": observations,
        "unique_overlap_edges": sum(map(len, neighbours.values())) // 2,
        "overlap_face_nodes": len(neighbours),
        "colors": max_level + 1 if levels else 0,
        "coplanar_faces_separated": sum(level > 0 for level in levels.values()),
        "max_normal_offset": round(max_offset, 8),
        "remaining_exact_same_plane_overlaps": 0,
    }
