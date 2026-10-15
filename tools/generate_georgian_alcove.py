#!/usr/bin/env python3
"""Derive Georgian alcoves from the editable three-wide Blockbench project.

The master BB file is read-only. Its curved shell supplies every width; side
profiles and the crest retain their authored dimensions. Narrow ridge joints
are rebuilt as mitres rather than overlapping scaled rectangles.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import math
from pathlib import Path
import uuid

import model_z_fighting_safety as geometry
import georgian_alcove_coplanar as coplanar

ROOT = Path(__file__).resolve().parents[1]
ASSETS = Path("src/main/resources/assets/erydon")
RAW = ASSETS / "authoring_models/block/alcove"
DESIGN = Path("authoring/alcoves/georgian")
MASTER = DESIGN / "alcove_georgian_3wide.bbmodel"
UUID_NAMESPACE = uuid.UUID("5b8eb79c-c14c-4b03-8799-fc791e72c6e4")
DETAIL_SUFFIXES = tuple(prefix + part + "_detail_" + side
                        for prefix in ("", "double_", "triple_")
                        for part in ("base", "middle") for side in ("left", "right"))


def load(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def encoded(document) -> bytes:
    return (json.dumps(document, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def translate(element, xyz):
    result = copy.deepcopy(element)
    for key in ("from", "to", "origin"):
        if key in result:
            result[key] = [round(a + b, 8) for a, b in zip(result[key], xyz)]
    if isinstance(result.get("rotation"), dict) and "origin" in result["rotation"]:
        result["rotation"]["origin"] = [round(a + b, 8) for a, b in zip(result["rotation"]["origin"], xyz)]
    for face in result.get("faces", {}).values():
        if "erydon_vertices" in face:
            face["erydon_vertices"] = [[round(a + b, 8) for a, b in zip(point, xyz)]
                                       for point in face["erydon_vertices"]]
    return result


def native_to_raw(project):
    groups = {group["uuid"]: group for group in project["groups"]}
    elements, by_uuid, owners = [], {}, {}
    for index, cube in enumerate(project["elements"]):
        if cube.get("type", "cube") != "cube":
            raise ValueError("Only cube authoring is supported")
        if cube.get("export", True) is False:
            raise ValueError("Non-exported cubes need an explicit design decision")
        element = {key: copy.deepcopy(cube[key]) for key in ("name", "from", "to")}
        rotation = cube.get("rotation", [0, 0, 0])
        if any(rotation):
            element["rotation"] = dict(zip(("x", "y", "z"), rotation))
            element["rotation"]["origin"] = copy.deepcopy(cube.get("origin", [8, 8, 8]))
        element["faces"] = {
            face: {"texture": "#stone"}
            for face, fields in cube["faces"].items()
            # BB5 saves enabled unassigned faces without a texture field.
            if "texture" not in fields or fields["texture"] is not None
        }
        elements.append(element)
        by_uuid[cube["uuid"]] = index

    def walk(nodes, path=()):
        result = []
        for node in nodes:
            if isinstance(node, str):
                owners[by_uuid[node]] = path
                result.append(by_uuid[node])
                continue
            group = groups[node["uuid"]]
            if any(group.get("rotation", [0, 0, 0])):
                raise ValueError("Rotated groups need explicit conversion before generation")
            result.append({"name": group["name"], "origin": group.get("origin", [8, 8, 8]),
                           "children": walk(node["children"], path + (group["name"],))})
        return result

    document = {"format_version": "1.21.11", "credit": "ERYDON Georgian alcove design",
                "textures": {"stone": "erydon:block/aganite_block"},
                "elements": elements, "groups": walk(project["outliner"])}
    original = {str(uuid.uuid5(UUID_NAMESPACE, f"alcove_georgian_3wide/cube/{i}")) for i in range(190)}
    added = {i for i, cube in enumerate(project["elements"]) if cube["uuid"] not in original}
    return document, owners, added


def raw_document(elements):
    return {"format_version": "1.21.11", "credit": "Derived from the Georgian three-wide design",
            "textures": {"stone": "erydon:block/aganite_block"}, "elements": elements}


def faces(document):
    rotations, _ = geometry._raw_group_state(document, len(document["elements"]))
    result, unsupported = geometry._build_faces(document["elements"], raw_group_rotations=rotations)
    errors = [item for item in unsupported if item['reason'] not in
              ('zero-thickness element is unsupported', 'degenerate face normal')]
    if errors:
        raise ValueError(errors)
    return result


def baked_element(element, transform):
    """Apply one affine map to already rotated faces; keep quads planar."""
    output_faces = {}
    points = []
    for face in faces(raw_document([element])):
        vertices = [[round(v, 8) for v in transform(p)] for p in face.vertices]
        output_faces[face.face_name] = {"texture": "#stone", "erydon_vertices": vertices}
        points.extend(vertices)
    return {"name": element["name"],
            "from": [min(p[i] for p in points) for i in range(3)],
            "to": [max(p[i] for p in points) for i in range(3)], "faces": output_faces}


def intersect(a, u, b, v):
    denominator = u[0] * v[1] - u[1] * v[0]
    if abs(denominator) < 1e-10:
        raise ValueError("Parallel ridge sections need a shared straight segment")
    delta = [b[i] - a[i] for i in range(2)]
    distance = (delta[0] * v[1] - delta[1] * v[0]) / denominator
    return [a[i] + distance * u[i] for i in range(2)]


def ridge_lines(elements, transform):
    result = []
    for element in elements:
        face = next(f for f in faces(raw_document([element])) if f.face_name == "south")
        a, b = [list(transform(p)[:2]) for p in face.vertices[:2]]
        length = math.dist(a, b)
        tangent = [(b[i] - a[i]) / length for i in range(2)]
        normal = [-tangent[1], tangent[0]]
        thickness = element["to"][1] - element["from"][1]
        result.append((a, b, tangent, normal, thickness))
    return result


def mitre_band(elements, transform, springing):
    """Fixed perpendicular thickness, shared joints, no overlapping segments."""
    lines = ridge_lines(elements, transform)
    inner, outer = [lines[0][0]], [[lines[0][0][i] + lines[0][4] * lines[0][3][i] for i in range(2)]]
    for previous, current in zip(lines, lines[1:]):
        inner.append(intersect(previous[0], previous[2], current[0], current[2]))
        outer.append(intersect([previous[0][i] + previous[4] * previous[3][i] for i in range(2)], previous[2],
                               [current[0][i] + current[4] * current[3][i] for i in range(2)], current[2]))
    inner.append(lines[-1][1])
    outer.append([lines[-1][1][i] + lines[-1][4] * lines[-1][3][i] for i in range(2)])
    # The removed lowest band now joins the capital. End both edges at the
    # capital's top, rather than leaving a sloping overlap inside that layer.
    for edge, line in ((inner, lines[0]), (outer, lines[0]), (inner, lines[-1]), (outer, lines[-1])):
        index = 0 if line is lines[0] else -1
        p, u = edge[index], line[2]
        if abs(u[1]) > 1e-10:
            edge[index] = [p[0] + (springing - p[1]) * u[0] / u[1], springing]
    output = []
    for index, element in enumerate(elements):
        a, b, c, d = inner[index], inner[index + 1], outer[index], outer[index + 1]
        z1, z2 = element["from"][2], element["to"][2]
        def point(xy, z): return [round(xy[0], 8), round(xy[1], 8), z]
        a1, b1, c1, d1 = [point(p, z1) for p in (a, b, c, d)]
        a2, b2, c2, d2 = [point(p, z2) for p in (a, b, c, d)]
        face_vertices = {"south": [a2, b2, d2, c2], "north": [b1, a1, c1, d1],
                         "down": [a2, a1, b1, b2], "up": [c1, c2, d2, d1],
                         "west": [a1, a2, c2, c1], "east": [b2, b1, d1, d2]}
        all_points = [a1, b1, c1, d1, a2, b2, c2, d2]
        output.append({"name": element["name"],
                       "from": [min(p[i] for p in all_points) for i in range(3)],
                       "to": [max(p[i] for p in all_points) for i in range(3)],
                       "faces": {face: {"texture": "#stone", "erydon_vertices": vertices}
                                 for face, vertices in face_vertices.items()}})
    return output, inner[len(elements) // 2][1]


def collision_boxes(elements):
    """Tight 0.25-unit XY slices of the Z-rotated decorative cuboids."""
    boxes = []
    for element in elements:
        rotation = geometry._parse_raw_rotation(element.get("rotation"), (8, 8, 8))
        if any(abs(a) > 1e-8 for a in (rotation.x_degrees, rotation.y_degrees)):
            raise ValueError("Decorative collision supports Z rotation only")
        f, t = element["from"], element["to"]
        explicit = element.get("faces", {}).get("south", {}).get("erydon_vertices")
        polygon = explicit or [geometry._transform_raw_vertex((x, y, f[2]), [rotation])
                              for x, y in ((f[0], f[1]), (t[0], f[1]), (t[0], t[1]), (f[0], t[1]))]
        lo, hi = min(p[0] for p in polygon), max(p[0] for p in polygon)
        if explicit is None and abs(rotation.z_degrees) < 1e-8:
            boxes.append(f + t)
            continue
        steps = max(1, math.ceil((hi - lo) / .25))
        for step in range(steps):
            left, right = lo + (hi - lo) * step / steps, lo + (hi - lo) * (step + 1) / steps
            samples = [p[1] for p in polygon if left - 1e-8 <= p[0] <= right + 1e-8]
            for a, b in zip(polygon, polygon[1:] + polygon[:1]):
                for boundary in (left, right):
                    if min(a[0], b[0]) - 1e-8 <= boundary <= max(a[0], b[0]) + 1e-8 and abs(b[0] - a[0]) > 1e-10:
                        fraction = (boundary - a[0]) / (b[0] - a[0])
                        samples.append(a[1] + fraction * (b[1] - a[1]))
            if samples and max(samples) - min(samples) > 1e-8:
                boxes.append([left, min(samples), f[2], right, max(samples), t[2]])
    return [[round(v, 8) for v in box] for box in boxes]


def native_project(document, texture, width):
    project = {"meta": {"format_version": "5.0", "model_format": "free", "box_uv": False},
               "name": f"alcove_georgian_{width}wide", "resolution": {"width": 16, "height": 16},
               "elements": [], "groups": [], "outliner": [], "textures": copy.deepcopy(texture),
               "unhandled_root_fields": {"__erydon_authoring": {"derived_from": "alcove_georgian_3wide.bbmodel"}}}
    ids = []
    for index, element in enumerate(document["elements"]):
        uid = str(uuid.uuid5(UUID_NAMESPACE, f"derived_georgian_{width}/cube/{index}"))
        ids.append(uid)
        if any("erydon_vertices" in face for face in element["faces"].values()):
            mesh = {"name": element["name"], "uuid": uid, "type": "mesh", "origin": [0, 0, 0],
                    "rotation": [0, 0, 0], "shading": "flat", "vertices": {}, "faces": {}}
            vertex_ids = {}
            for name, face in element["faces"].items():
                points = face["erydon_vertices"]
                keys = []
                uv = geometry._raw_default_uv(points, name)
                mapping = {}
                for i, point in enumerate(points):
                    key = vertex_ids.get(tuple(point))
                    if key is None:
                        key = f"v{len(vertex_ids)}"
                        vertex_ids[tuple(point)] = key
                        mesh["vertices"][key] = point
                    keys.append(key)
                    mapping[key] = [uv[i * 2], uv[i * 2 + 1]]
                mesh["faces"][name] = {"vertices": keys, "uv": mapping, "texture": 0}
            project["elements"].append(mesh)
            continue
        cube = {key: copy.deepcopy(element[key]) for key in ("name", "from", "to")}
        cube.update({"uuid": uid, "type": "cube", "box_uv": False, "autouv": 0,
                     "origin": element.get("rotation", {}).get("origin", [8, 8, 8]), "faces": {}})
        rotation = element.get("rotation", {})
        cube["rotation"] = [rotation.get(a, rotation.get("angle", 0) if rotation.get("axis") == a else 0)
                            for a in ("x", "y", "z")]
        for face in ("north", "south", "east", "west", "up", "down"):
            cube["faces"][face] = {"uv": [0, 0, 16, 16], "texture": 0 if face in element["faces"] else None}
        project["elements"].append(cube)

    def convert(group, path=""):
        if isinstance(group, int):
            return ids[group]
        uid = str(uuid.uuid5(UUID_NAMESPACE, f"derived_georgian_{width}/{path}/{group['name']}"))
        project["groups"].append({"name": group["name"], "uuid": uid,
                                   "origin": group.get("origin", [8, 8, 8]), "rotation": [0, 0, 0]})
        return {"uuid": uid, "children": [convert(c, path + "/" + group["name"]) for c in group["children"]]}
    project["outliner"] = [convert(g) for g in document["groups"]]
    return project


def generate(repo: Path):
    project = load(repo / MASTER)
    master, owners, _ = native_to_raw(project)
    source = master["elements"]
    # Classification follows semantic groups and physical position, not old
    # UUIDs, element indices, or the number of original front panels.
    def group(index):
        return next(name for name in owners[index] if name.startswith(("SIDES__", "BACK__", "ROOF__")))
    def is_jamb(index): return source[index]["name"].startswith("outer_jamb")
    def is_promoted(index): return group(index).startswith("SIDES__middle_") and source[index]["from"][1] >= 32
    def is_ridge(index):
        rotation = source[index].get("rotation", {})
        return (group(index).startswith("ROOF__top_") and abs(rotation.get("z", 0)) > 1e-8
                and abs(rotation.get("x", 0)) < 1e-8 and abs(rotation.get("y", 0)) < 1e-8
                and source[index]["to"][2] >= 16.25 and "center" not in group(index))
    # The last two crest steps end at16.5/16.75; detect all rigid centre pieces
    # except the full-cell flat slab and the legacy apex/lining.
    def is_crest(index):
        e = source[index]
        return group(index) == "ROOF__top_center" and e["name"] == "top" and e["to"][0] - e["from"][0] < 8
    def is_slab(index):
        e = source[index]
        return group(index).startswith("ROOF__top_") and e["name"] == "top" and e["to"][0] - e["from"][0] >= 15.9
    ridge = {side: sorted((i for i in owners if is_ridge(i) and group(i).endswith(side)),
                         key=lambda i: abs(source[i]["rotation"]["z"]), reverse=True)
             for side in ("left", "right")}
    if not ridge["left"] or len(ridge["left"]) != len(ridge["right"]):
        raise ValueError("Both sides need matching front-ridge segments")
    ridge_order = ridge["left"] + list(reversed(ridge["right"]))
    ridge_set = set(ridge_order)
    top_indices = [i for i in owners if (owners[i][0] == "TOP" and not group(i).startswith("BACK__")) or is_promoted(i)]
    jamb_width = max(source[i]["to"][0] for i in top_indices if group(i) == "SIDES__top_left" and is_jamb(i))
    body_indices = [i for i in owners if group(i).startswith("SIDES__middle_") and not is_jamb(i)]
    top_cap_height = max(source[i]["to"][1] - 32 for i in top_indices
                         if is_jamb(i) and (group(i).startswith("ROOF__") or is_promoted(i)))
    models, collision_elements = {}, {}
    source_peak_lines = ridge_lines([source[i] for i in ridge_order], lambda p: (p[0], p[1] - 32, p[2]))
    mid = len(ridge_order) // 2
    source_peak = intersect(source_peak_lines[mid - 1][0], source_peak_lines[mid - 1][2],
                            source_peak_lines[mid][0], source_peak_lines[mid][2])[1]
    back = [translate(source[i], [0, 0, 0]) for i in owners if group(i) == "BACK__base_left"]
    models["alcove_georgian_single_back.json"] = raw_document(back)
    collision_elements["alcove_georgian_single_back.json"] = []
    crest_shifts = {}
    for width, prefix in ((1, ""), (2, "double_"), (3, "triple_")):
        springing = 7.5 if width == 1 else 0
        x_scale = (width * 16 - 2 * jamb_width) / (48 - 2 * jamb_width)
        y_scale = (16 - springing) / 16
        def core(p, top=False):
            return (jamb_width + (p[0] - jamb_width) * x_scale,
                    (p[1] - 32) * y_scale + springing if top else p[1] - 16, p[2])
        def column(e):
            points = [p for f in faces(raw_document([e])) for p in f.vertices]
            return max(0, min(width - 1, int(sum(p[0] for p in points) / len(points) / 16)))
        def side_name(col): return "left" if col == 0 else "right" if col == width - 1 else "center"
        def suffix(role, col):
            if width == 1: return "single_sides" if role == "side" else "single_top"
            return prefix + role + "_" + side_name(col)
        def register(role, world):
            for col in range(width):
                chosen = [(e, decorative) for owner, e, decorative in world if owner == col]
                name = "alcove_georgian_" + suffix(role, col) + ".json"
                models[name] = raw_document([translate(e, [-col * 16, 0, 0]) for e, _ in chosen])
                collision_elements[name] = [translate(e, [-col * 16, 0, 0]) for e, decoration in chosen if decoration]
        body_world = []
        for i in body_indices:
            e = translate(source[i], [0, -16, 0]) if width == 3 else baked_element(source[i], lambda p: core(p))
            owner = (0 if "left" in group(i) else 2 if "right" in group(i) else 1) if width == 3 else column(e)
            body_world.append((owner, e, False))
        register("side", body_world)
        for part, row in (("base", 0), ("middle", 1)):
            for side, original_col, target_col in (("left", 0, 0), ("right", 2, width - 1)):
                indices = [i for i in owners if group(i) == f"SIDES__{part}_{side}" and is_jamb(i) and not is_promoted(i)]
                elements = [translate(source[i], [(target_col - original_col) * 16 - target_col * 16, -row * 16, 0]) for i in indices]
                # Repeatable rows stop at their boundary. Preserve every flute,
                # capital/plinth width and projection rather than flattening it.
                for e in elements: e["to"][1] = min(e["to"][1], 16)
                name = f"alcove_georgian_{prefix}{part}_detail_{side}.json"
                models[name] = raw_document(elements)
                collision_elements[name] = elements
        if width == 3:
            fitted = {i: translate(source[i], [0, -32, 0]) for i in ridge_order}
            crest_shift = 0
        else:
            mitred, target_peak = mitre_band([source[i] for i in ridge_order], lambda p: core(p, True), springing + top_cap_height)
            fitted = dict(zip(ridge_order, mitred))
            crest_shift = target_peak - source_peak
        crest_shifts[str(width)] = round(crest_shift, 8)
        top_world = []
        for i in top_indices:
            original_col = 0 if group(i).endswith("left") else 2 if group(i).endswith("right") else 1
            e = source[i]
            if width == 3:
                output = translate(e, [0, -32, 0])
                owner = original_col
            elif i in ridge_set:
                output = fitted[i]
                owner = column(output)
            elif is_crest(i):
                output = translate(e, [(width - 3) * 8, -32 + crest_shift, 0])
                owner = column(output)
            elif is_jamb(i):
                delta_y = -32 + (springing if group(i).startswith("ROOF__") or is_promoted(i) else 0)
                output = translate(e, [0 if original_col == 0 else (width - 3) * 16, delta_y, 0])
                owner = 0 if original_col == 0 else width - 1
            elif is_slab(i):
                output = baked_element(e, lambda p: (p[0] * width / 3, p[1] - 32 + crest_shift, p[2]))
                owner = column(output)
            else:
                output = baked_element(e, lambda p: core(p, True))
                owner = column(output)
            rotation = e.get("rotation", {})
            collision_detail = not any(abs(rotation.get(axis, 0)) > 1e-8 for axis in ("x", "y"))
            top_world.append((owner, output, collision_detail))
        # Width1's shorter crown starts above the bottom of its block. Fill the
        # lower interval with the same curved body walls, meeting at7.5 exactly.
        if width == 1:
            for owner, e, _ in body_world:
                output = baked_element(e, lambda p: (p[0], p[1] * springing / 16, p[2]))
                top_world.append((owner, output, False))
        register("top", top_world)

    # Unbiased assembled design references retain all authored faces. Rendering
    # offsets are added to packaged children only, after this exact export.
    assemblies = {}
    for width in (1, 2, 3):
        document = raw_document([])
        document["groups"] = []
        prefix = "" if width == 1 else "double_" if width == 2 else "triple_"
        for row, part in enumerate(("base", "middle", "top")):
            row_group = {"name": part.upper(), "origin": [width * 8, row * 16 + 8, 8], "children": []}
            for col in range(width):
                side = "single" if width == 1 else "left" if col == 0 else "right" if col == width - 1 else "center"
                column_group = {"name": side.upper(), "origin": [col * 16 + 8, row * 16 + 8, 8], "children": []}
                entries = [("BACK", "alcove_georgian_single_back.json")]
                if part == "top": entries.append(("ROOF", f"alcove_georgian_{'single_top' if width == 1 else prefix + 'top_' + side}.json"))
                else:
                    entries.append(("SIDES", f"alcove_georgian_{'single_sides' if width == 1 else prefix + 'side_' + side}.json"))
                    for detail_side, outer_col in (("left", 0), ("right", width - 1)):
                        if col == outer_col: entries.append(("DETAIL_" + detail_side.upper(), f"alcove_georgian_{prefix}{part}_detail_{detail_side}.json"))
                for role, name in entries:
                    start = len(document["elements"])
                    document["elements"].extend(translate(e, [col * 16, row * 16, 0]) for e in models[name]["elements"])
                    column_group["children"].append({"name": f"{role}__{part}_{side}", "origin": column_group["origin"],
                                                       "children": list(range(start, len(document["elements"])))})
                row_group["children"].append(column_group)
            document["groups"].append(row_group)
        assemblies[width] = document
    icon = load(repo / RAW / "alcove_modern_icon.json")
    icon.pop("groups", None)
    icon["elements"] = copy.deepcopy(models["alcove_georgian_single_back.json"]["elements"] + models["alcove_georgian_single_top.json"]["elements"])
    models["alcove_georgian_icon.json"] = icon
    collision_elements["alcove_georgian_icon.json"] = []
    # Legacy zero-height floor reference is not a placed component. Retain its
    # established contract for offset decorative items and older consumers.
    models["alcove_georgian_single_base.json"] = load(repo / RAW / "alcove_modern_single_base.json")
    collision_elements["alcove_georgian_single_base.json"] = []
    collision = {"schema": 1, "units_per_block": 16, "additive_to_legacy": True, "parts": {}}
    span_widths = {"single": (1, 0), "left": (2, 0), "right": (2, 1),
                   "triple_left": (3, 0), "triple_center": (3, 1), "triple_right": (3, 2)}
    for part in ("single", "base", "middle", "top"):
        collision["parts"][part] = {}
        for span, (width, col) in span_widths.items():
            prefix = "" if width == 1 else "double_" if width == 2 else "triple_"
            detail = []
            if part in ("single", "top"):
                side = "left" if col == 0 else "right" if col == width - 1 else "center"
                name = f"alcove_georgian_{'single_top' if width == 1 else prefix + 'top_' + side}.json"
                detail = collision_elements[name]
            else:
                for side, outer_col in (("left", 0), ("right", width - 1)):
                    if col == outer_col: detail.extend(collision_elements[f"alcove_georgian_{prefix}{part}_detail_{side}.json"])
            collision["parts"][part][span] = collision_boxes(detail)
    output = {DESIGN / "alcove_georgian_3wide.json": encoded(master),
              ASSETS / "interaction_shapes/alcove_georgian.json": encoded(collision)}
    for width in (1, 2):
        output[DESIGN / f"alcove_georgian_{width}wide.json"] = encoded(assemblies[width])
        output[DESIGN / f"alcove_georgian_{width}wide.bbmodel"] = encoded(native_project(assemblies[width], project["textures"], width))
    separation = coplanar.separate(models)
    for name, document in models.items():
        document["erydon_split_world_uv"] = True
        output[RAW / name] = encoded(document)
    parents = ASSETS / "models/block/alcove/georgian"
    generic = load(repo / parents / "alcove_georgian_sides.json")
    variants = sorted(path for path in (repo / parents).glob("*_alcove_georgian_sides*.json") if not path.name.startswith("alcove_georgian_"))
    for suffix in DETAIL_SUFFIXES:
        output[parents / f"alcove_georgian_{suffix}.json"] = encoded(generic)
        for variant in variants:
            document = load(variant)
            document["parent"] = f"erydon:block/alcove/georgian/alcove_georgian_{suffix}"
            output[parents / variant.name.replace("_sides", "_" + suffix)] = encoded(document)
    output[DESIGN / "derivation_manifest.json"] = encoded({"schema": 2,
            "master": MASTER.as_posix(), "master_sha256": hashlib.sha256((repo / MASTER).read_bytes()).hexdigest(),
            "master_cubes": len(master["elements"]), "derived_widths": [1, 2],
            "material_finish_variants": len(variants), "component_models": len(models),
            "coplanar_faces_separated": separation["coplanar_faces_separated"],
            "face_separation": separation,
            "middle_strip_height": 16,
            "collision_slice_units": .25, "fixed_jamb_inner_edge_units": jamb_width,
            "ridge_segments_per_side": len(ridge["left"]), "crest_vertical_shifts_units": crest_shifts,
            "notes": ["Master BB is preserved byte for byte; geometry roles do not depend on original UUIDs.",
                      "Narrow curved cores are affine face meshes anchored to the fixed jamb inner edges.",
                      "Narrow mouldings retain authored perpendicular thickness and share mitred joints.",
                      "Pilasters, capitals, plinths and crest retain their authored dimensions.",
                      "Physically top-row capitals are promoted even when grouped in the middle row.",
                      "Exact overlapping faces use graph-colored microscopic offsets without shifting texture phase.",
                      "Runtime UV-cell tessellation preserves overhangs and atlas-safe samples."]})
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    output = generate(ROOT)
    changed = [path for path, data in output.items() if not (ROOT / path).is_file() or (ROOT / path).read_bytes() != data]
    if args.check:
        if changed:
            print(f"Georgian outputs need regeneration: {len(changed)} files")
            for path in changed[:12]: print(path)
            return 1
        print(f"Georgian outputs match the edited three-wide BB source ({len(output)} files).")
    else:
        for path in changed:
            (ROOT / path).parent.mkdir(parents=True, exist_ok=True)
            (ROOT / path).write_bytes(output[path])
        print(f"Generated Georgian alcoves: {len(changed)} changed, {len(output)} outputs; master BB untouched.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
