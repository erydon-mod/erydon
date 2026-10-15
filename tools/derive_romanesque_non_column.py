#!/usr/bin/env python3
"""Read the edited Generic non-column Romanesque master without writing it.

The left jamb is authoritative. Its descending arch-brick detailing is
distributed by physical height, rather than its editor group, so the base,
repeatable body and lower crown can be composed at different cluster heights.
"""
from __future__ import annotations

from collections import defaultdict
import copy
import hashlib
import json
import math
from pathlib import Path
import uuid

import model_z_fighting_safety as geometry

DESIGN = Path("authoring/arches/romanesque")
MASTER = DESIGN / "arch_romanesque_non_column_3wide.bbmodel"
BASELINE = DESIGN / "arch_romanesque_non_column_3wide.json"
UUID_NAMESPACE = uuid.UUID("b6608cea-8b4e-5a69-a7dd-4947a829c075")
FACES = ("north", "south", "east", "west", "up", "down")
PREFIX = "non_column_"


def load(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def _face_fields(fields):
    result = {"texture": "#stone"}
    if "cullface" in fields:
        result["cullface"] = fields["cullface"]
    return result


def _enabled(fields):
    # Generic BB omits texture on enabled, unassigned faces. Only null hides.
    return fields.get("texture", "enabled") is not None


def native_elements(native):
    """Return implicit-UV cube/raw-polygon elements from a Generic BB element."""
    if native.get("export", True) is False:
        raise ValueError(f"Non-exported element needs an explicit decision: {native['name']}")
    kind = native.get("type", "cube")
    if kind == "cube":
        result = {key: copy.deepcopy(native[key]) for key in ("name", "from", "to")}
        result["rotation"] = dict(zip("xyz", native.get("rotation", [0, 0, 0])),
                                  origin=copy.deepcopy(native.get("origin", [8, 8, 8])))
        result["faces"] = {face: _face_fields(fields) for face, fields in native["faces"].items()
                           if face in FACES and _enabled(fields)}
        return [result] if result["faces"] else []
    if kind != "mesh":
        raise ValueError(f"Unsupported Generic element type {kind}: {native['name']}")
    origin = native.get("origin", [0, 0, 0])
    if any(abs(value) > 1e-10 for value in origin):
        raise ValueError(f"Bake the mesh origin before derivation: {native['name']}")
    rotation = geometry._parse_raw_rotation(dict(zip("xyz", native.get("rotation", [0, 0, 0])), origin=origin), origin)
    vertices = {key: geometry._transform_raw_vertex(point, [rotation]) for key, point in native["vertices"].items()}
    result, current = [], {}
    points = []

    def finish():
        nonlocal current, points
        if current:
            result.append({"name": native["name"], "faces": current,
                           "from": [min(point[axis] for point in points) for axis in range(3)],
                           "to": [max(point[axis] for point in points) + 1e-8 for axis in range(3)]})
        current, points = {}, []

    for face_key, fields in native["faces"].items():
        if not _enabled(fields):
            continue
        polygon = [list(vertices[key]) for key in fields["vertices"]]
        compact = []
        for point in polygon:
            if not compact or math.dist(point, compact[-1]) > 1e-9:
                compact.append(point)
        if len(compact) > 1 and math.dist(compact[0], compact[-1]) <= 1e-9:
            compact.pop()
        if len(compact) < 3:
            continue
        pieces = [compact] if len(compact) <= 4 else [[compact[0], compact[i], compact[i + 1]] for i in range(1, len(compact) - 1)]
        for polygon in pieces:
            name = face_key if face_key in FACES else geometry._raw_closest_direction(polygon)
            if name in current:
                finish()
            if len(polygon) == 3:
                polygon.append(polygon[-1].copy())
            current[name] = dict(_face_fields(fields), erydon_vertices=polygon)
            points.extend(polygon)
    finish()
    return result


def _geometry_vertices(element):
    if any("erydon_vertices" in fields for fields in element.get("faces", {}).values()):
        return sorted(set(tuple(round(value, 9) for value in vertex)
                          for fields in element["faces"].values() for vertex in fields.get("erydon_vertices", [])))
    rotation = geometry._parse_raw_rotation(element.get("rotation"), [8, 8, 8])
    return sorted(tuple(round(value, 9) for value in geometry._transform_raw_vertex(
        [element["from"][axis] if not (index & (1 << axis)) else element["to"][axis] for axis in range(3)], [rotation]))
                  for index in range(8))


def read_master(repo):
    """Return raw elements by component group and an audit against initial JSON."""
    project = load(repo / MASTER)
    baseline = load(repo / BASELINE)
    groups = {group["uuid"]: group for group in project["groups"]}
    owners = {}

    def walk(nodes, path=()):
        for node in nodes:
            if isinstance(node, str):
                if node in owners:
                    raise ValueError(f"Element occurs in multiple editor groups: {node}")
                owners[node] = path
            else:
                group = groups[node["uuid"]]
                if any(group.get("rotation", [0, 0, 0])):
                    raise ValueError("Bake editor group rotations before deriving the non-column arch")
                walk(node["children"], path + (group["name"],))

    walk(project["outliner"])
    old = {}
    for component in baseline["__erydon_authoring"]["components"]:
        for local in range(component["element_count"]):
            uid = str(uuid.uuid5(UUID_NAMESPACE, f"{component['group']}/{component['suffix']}/{local}"))
            old[uid] = (component["group"], baseline["elements"][component["element_start"] + local])
    rows = defaultdict(list)
    audit = defaultdict(lambda: {"elements": 0, "added_elements": 0, "deleted_elements": 0,
                                 "geometry_changes": 0, "newly_enabled_faces": 0, "newly_disabled_faces": 0})
    provenance = []
    current_ids = set()
    for native in project["elements"]:
        uid = native["uuid"]
        current_ids.add(uid)
        path = owners.get(uid)
        if path is None:
            raise ValueError(f"Element has no editor group: {native['name']}")
        role = next((name for name in reversed(path) if "__" in name), None)
        if role is None:
            raise ValueError(f"Element has no component owner: {native['name']}")
        elements = native_elements(native)
        rows[role].extend(elements)
        item = audit[role]
        item["elements"] += 1
        previous = old.get(uid)
        if previous is None:
            item["added_elements"] += 1
        else:
            original = previous[1]
            old_faces = set(original["faces"])
            new_faces = {face for element in elements for face in element["faces"]}
            item["newly_enabled_faces"] += len(new_faces - old_faces)
            item["newly_disabled_faces"] += len(old_faces - new_faces)
            old_points = _geometry_vertices(original)
            new_points = sorted(set(point for element in elements for point in _geometry_vertices(element)))
            error = max((min(math.dist(point, other) for other in new_points) for point in old_points), default=0) if new_points else math.inf
            error = max(error, max((min(math.dist(point, other) for other in old_points) for point in new_points), default=0))
            item["geometry_changes"] += error > 1e-4
        provenance.append({"uuid": uid, "group": role, "kind": native.get("type", "cube"),
                           "element_name": native["name"], "raw_element_count": len(elements)})
    for uid, (role, original) in old.items():
        if uid not in current_ids:
            audit[role]["deleted_elements"] += 1
    report = {"master": MASTER.as_posix(), "master_sha256": hashlib.sha256((repo / MASTER).read_bytes()).hexdigest(),
              "saved_elements": len(project["elements"]), "left_side_is_authoritative": True,
              "face_mask_policy": "Saved enabled faces; explicit null only disables; no automatic removal",
              "component_changes": dict(sorted(audit.items())), "element_provenance": provenance}
    return dict(rows), report


def _combine_faces(parts):
    """Keep one raw element per source solid whenever face directions allow."""
    result = []
    current = None
    for part in parts:
        if current is None or current["faces"].keys() & part["faces"].keys():
            current = copy.deepcopy(part)
            result.append(current)
        else:
            current["faces"].update(copy.deepcopy(part["faces"]))
    return result


def clipped_y(element, low, high, arch_api):
    """Clip exterior faces and backing volume together, without seam caps."""
    # Clipped face vertices already contain the editor cube's rotation.
    # Bake it first so later placement cannot rotate those vertices twice.
    element = arch_api.baked(element, lambda point: point)
    solid = copy.deepcopy(arch_api.volume(element))
    if solid["axis"] == "y":
        solid["depth"] = [max(low, solid["depth"][0]), min(high, solid["depth"][1])]
        if solid["depth"][1] - solid["depth"][0] <= 1e-10:
            return []
    else:
        solid["polygon"] = arch_api.clip_polygon(solid["polygon"], 1, low, True)
        solid["polygon"] = arch_api.clip_polygon(solid["polygon"], 1, high, False)
        if arch_api.polygon_area(solid["polygon"]) <= 1e-10:
            return []
    result = arch_api.clipped_element_faces(element, [(1, low, high)])
    for part in result:
        part["_solid"] = copy.deepcopy(solid)
    return _combine_faces(result)


def derive_components(repo, arch_api):
    """Derive independent canonical-left templates using the shared arch maps.

    Returns (models, report). Canonical templates use the existing renderer's
    source orientation; callers place LEFT with its half-turn and RIGHT with
    its depth mirror. They never replace approved column-setting templates.
    """
    rows, report = read_master(repo)
    models = {}
    curve = rows["CURVE__curve_lower_left"]
    for suffix, group, source_y in (
            ("side_large_base", "WALL__base_left", 0),
            ("side_large", "WALL__body_left", 16)):
        decoration = []
        for element in curve:
            decoration.extend(clipped_y(element, source_y if source_y else -1e6, source_y + 16, arch_api))
        models[PREFIX + suffix] = arch_api.raw_document(arch_api.canonical(rows[group] + decoration, [0, source_y, 0], 180))
    lower = []
    for element in curve:
        # Keep upper overlap into the crown; only the jamb portion below the
        # Y32 springline moves to the repeatable body/base components.
        lower.extend(clipped_y(element, 32, 1e6, arch_api))
    models[PREFIX + "corner_large_lower"] = arch_api.raw_document(arch_api.canonical(lower, [0, 32, 0], 180))
    for suffix, group, source_y in (
            ("side_large_lower", "WALL__curve_lower_left", 32),
            ("corner_large_upper", "CURVE__crown_left", 48),
            ("side_large_crown", "WALL__crown_left", 48)):
        models[PREFIX + suffix] = arch_api.raw_document(arch_api.canonical(rows[group], [0, source_y, 0], 180))
    for width, scale_name in ((1, "small"), (2, "medium")):
        for ending in ("", "_base"):
            source = models[PREFIX + "side_large" + ending]["elements"]
            narrowed = [arch_api.baked(element, lambda point: (16 - (16 - point[0]) * width / 3, point[1], point[2]))
                        for element in source]
            models[PREFIX + "side_" + scale_name + ending] = arch_api.raw_document(narrowed)
        # The crown needs its plain wall lining, not a repeated body jamb.
        # Reusing the body here carries its vertical brick trim all the way
        # to the crown's outer top edge, beyond the fitted arch detailing.
        crown_lining = models[PREFIX + "side_large_crown"]["elements"]
        models[PREFIX + "side_" + scale_name + "_crown"] = arch_api.raw_document([
            arch_api.baked(element, lambda point: (16 - (16 - point[0]) * width / 3, point[1], point[2]))
            for element in crown_lining])
    # Project the complete authored curve before clipping the narrow owner.
    # Original stems below Y32 can become a visible part of the narrow crown.
    # Only portions that project below local Y0 belong to its separate jambs.
    shared_rows, _ = arch_api.master_elements(repo)
    center = arch_api.source_center(shared_rows["KEYSTONE__crown_center"])
    crown_left = curve + rows["CURVE__crown_left"]
    crown = crown_left + [arch_api.x_mirror(element, 48) for element in crown_left] + center
    for width, scale_name in ((1, "small"), (2, "medium")):
        dx, dy = arch_api.ALIGNMENT[width]
        narrowed = [arch_api.baked(element, lambda point: (point[0] * width / 3 + dx,
                                                         point[1] * width / 3 + dy, point[2]))
                    for element in crown]
        owner_faces = []
        for element in narrowed:
            owner_faces.extend(clipped_y(element, 0, 1e6, arch_api))
        narrowed = owner_faces
        if width == 1:
            narrowed = arch_api.source_center(narrowed, width=16)
        else:
            selected = []
            for element in narrowed:
                solid = copy.deepcopy(arch_api.volume(element))
                solid["polygon"] = arch_api.clip_polygon(solid["polygon"], 0, 16, False)
                if arch_api.polygon_area(solid["polygon"]) <= 1e-10:
                    continue
                for part in _combine_faces(arch_api.clipped_element_faces(element, [(0, -1e6, 16)])):
                    part["_solid"] = copy.deepcopy(solid)
                    selected.append(part)
            narrowed = selected
        models[PREFIX + "corner_" + scale_name] = arch_api.raw_document([arch_api.half_turn(element) for element in narrowed])
    report["independent_templates"] = list(models)
    report["jamb_partition"] = "BASE through Y16 with small below-zero projections retained; repeatable BODY Y16..32; lower crown Y32 and above; exterior faces only"
    report["narrow_body_scale"] = "X width/3 anchored to outer jamb; Y and Z unchanged"
    report["narrow_crown_lining"] = "Saved crown WALL profile, X width/3; no repeated jamb bricks; Y/Z and authored edge projections unchanged"
    report["narrow_crown_scale"] = "Full curve projected with existing width/3 XY fit and alignment; clip projected Y below zero; depth unchanged"
    report["geometry_policy"] = "Left source only; pure X mirror on the right; column-setting components unchanged"
    return models, report


def main():
    # This command is deliberately read-only; integration is performed by the
    # family generator after its runtime routes select these templates.
    import generate_romanesque_arch as arch
    models, report = derive_components(Path(__file__).resolve().parents[1], arch)
    report.pop("element_provenance")
    report["template_elements"] = {name: len(document["elements"]) for name, document in models.items()}
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
