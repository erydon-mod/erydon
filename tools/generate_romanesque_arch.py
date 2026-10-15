#!/usr/bin/env python3
"""Derive Romanesque arch components from the preserved editable BB master.

The user's left side is authoritative. The right side mirrors X without
reversing its depth. Existing narrow-arch circle proportions are retained;
profile depth, columns and plinths retain their authored dimensions.
"""
from __future__ import annotations

import argparse
import copy
from collections import defaultdict
import hashlib
import json
import math
import sys
from pathlib import Path

import export_romanesque_arch_authoring as initial
import generate_georgian_alcove as shared
import model_z_fighting_safety as geometry

ROOT = Path(__file__).resolve().parents[1]
DESIGN = Path("authoring/arches/romanesque")
MASTER = DESIGN / "arch_romanesque_3wide.bbmodel"
FIXTURE = DESIGN / "source_components.json"
RAW = Path("src/main/resources/assets/erydon/authoring_models/block/arch/romanesque")
PARENTS = Path("src/main/resources/assets/erydon/models/block/arch/romanesque")
ASSETS = Path("src/main/resources/assets/erydon")
ALIGNMENT = {1: (-.004586012, -5.320366771), 2: (-.009169896, -26.660180311)}
FACE_X = {"east": "west", "west": "east"}
FACE_Z = {"north": "south", "south": "north"}
FACE_Y180 = FACE_X | FACE_Z


def load(path):
    return initial.load(path)


def encoded(value):
    return initial.encoded(value)


def raw_document(elements):
    return {"format_version": "1.21.11", "credit": "Derived from the edited Romanesque arch BB project",
            "textures": {"stone": "erydon:block/aganite_block", "particle": "#stone"},
            "erydon_split_world_uv": True, "elements": elements}


def faces(elements):
    return shared.faces(raw_document(elements))


def hull(points):
    points = sorted(set(tuple(round(v, 9) for v in p) for p in points))
    def cross(o, a, b): return (a[0]-o[0])*(b[1]-o[1])-(a[1]-o[1])*(b[0]-o[0])
    lower, upper = [], []
    for part, sequence in ((lower, points), (upper, list(reversed(points)))):
        for p in sequence:
            while len(part) >= 2 and cross(part[-2], part[-1], p) <= 1e-12:
                part.pop()
            part.append(p)
    return [list(p) for p in lower[:-1] + upper[:-1]]


def clip_polygon(points, axis, boundary, greater):
    if not points: return []
    result = []
    previous = points[-1]
    inside = lambda p: p[axis] >= boundary - 1e-10 if greater else p[axis] <= boundary + 1e-10
    for current in points:
        if inside(previous) != inside(current):
            t = (boundary-previous[axis])/(current[axis]-previous[axis])
            result.append([previous[i]+t*(current[i]-previous[i]) for i in range(len(current))])
        if inside(current): result.append(list(current))
        previous = current
    compact = []
    for p in result:
        if not compact or math.dist(p, compact[-1]) > 1e-9: compact.append(p)
    if len(compact) > 1 and math.dist(compact[0], compact[-1]) <= 1e-9: compact.pop()
    return compact


def polygon_area(points):
    if len(points) < 3:
        return 0
    if len(points[0]) == 2:
        return abs(sum(p[0]*q[1]-q[0]*p[1] for p,q in zip(points,points[1:]+points[:1])))/2
    base = points[0]
    area = [0., 0., 0.]
    for i in range(1, len(points)-1):
        a, b = ([points[i][j]-base[j] for j in range(3)],
                [points[i+1][j]-base[j] for j in range(3)])
        area[0] += a[1]*b[2]-a[2]*b[1]
        area[1] += a[2]*b[0]-a[0]*b[2]
        area[2] += a[0]*b[1]-a[1]*b[0]
    return math.sqrt(sum(a*a for a in area))/2


def volume(element):
    if "_solid" in element: return element["_solid"]
    rotation = geometry._parse_raw_rotation(element.get("rotation"), (8, 8, 8))
    corners = [geometry._transform_raw_vertex([element["from"][a] if not (i & (1 << a)) else element["to"][a]
                                               for a in range(3)], [rotation]) for i in range(8)]
    axis = "y" if abs(rotation.y_degrees) > 1e-8 else "z"
    coordinates = (0, 2) if axis == "y" else (0, 1)
    depth_axis = 1 if axis == "y" else 2
    return {"axis": axis, "polygon": hull([[p[a] for a in coordinates] for p in corners]),
            "depth": [min(p[depth_axis] for p in corners), max(p[depth_axis] for p in corners)]}


def transform_solid(solid, transform):
    axis = solid["axis"]
    a, b = (0, 2) if axis == "y" else (0, 1)
    depth_axis = 1 if axis == "y" else 2
    points = []
    for p in solid["polygon"]:
        for depth in solid["depth"]:
            point = [0, 0, 0]; point[a], point[b], point[depth_axis] = p[0], p[1], depth
            points.append(transform(point))
    return {"axis": axis, "polygon": hull([[p[a], p[b]] for p in points]),
            "depth": [min(p[depth_axis] for p in points), max(p[depth_axis] for p in points)]}


def baked(element, transform, face_map=None, reflected=False):
    """Bake a map into implicit-UV face vertices, preserving hidden-face masks."""
    face_map = face_map or {}
    output = {"name": element.get("name", "component"), "faces": {}}
    if '_nodes' in element:
        output['_nodes'] = {}
    points = []
    for face in faces([element]):
        vertices = [[round(v, 9) for v in transform(p)] for p in face.vertices]
        # Triangle faces occupy four raw slots. Remove their repeated padding
        # before reversing winding, then pad the final vertex again. Reversing
        # [A,B,C,C] directly would create [C,C,B,A] with a zero leading normal.
        unique = []
        for vertex in vertices:
            if not any(math.dist(vertex,other)<=1e-9 for other in unique):
                unique.append(vertex)
        if len(unique)==3:
            vertices = unique
        if reflected: vertices.reverse()
        if len(vertices)==3:
            vertices.append(vertices[-1].copy())
        name = face_map.get(face.face_name, face.face_name)
        fields = copy.deepcopy(element["faces"][face.face_name])
        fields.pop("uv", None)
        # FaceRecord vertices already include any runtime normal bias.
        fields.pop("erydon_normal_offset", None)
        fields["texture"], fields["erydon_vertices"] = "#stone", vertices
        if "cullface" in fields: fields["cullface"] = face_map.get(fields["cullface"], fields["cullface"])
        output["faces"][name] = fields
        if '_nodes' in element:
            output['_nodes'][name] = element['_nodes'][face.face_name]
        points.extend(vertices)
    if not points: raise ValueError("Empty authored element")
    output["from"] = [min(p[i] for p in points) for i in range(3)]
    output["to"] = [max(p[i] for p in points) for i in range(3)]
    # One-face pieces still have a real backing volume, recorded separately.
    for a in range(3):
        if output["to"][a]-output["from"][a] < 1e-10: output["to"][a] += 1e-8
    output["_solid"] = transform_solid(volume(element), transform)
    return output


def translated(element, delta):
    return baked(element, lambda p: [p[i]+delta[i] for i in range(3)])


def half_turn(element):
    return baked(element, lambda p: (16-p[0], p[1], 16-p[2]), FACE_Y180)


def depth_mirror(element):
    return baked(element, lambda p: (p[0], p[1], 16-p[2]), FACE_Z, True)


def x_mirror(element, width):
    return baked(element, lambda p: (width-p[0], p[1], p[2]), FACE_X, True)


def master_elements(repo):
    project, baseline = load(repo / MASTER), load(repo / DESIGN / "arch_romanesque_3wide.json")
    groups = {g["uuid"]: g for g in project["groups"]}
    owners = {}
    def walk(nodes, path=()):
        for node in nodes:
            if isinstance(node, str): owners[node] = path
            else:
                group = groups[node["uuid"]]
                if any(group.get("rotation", [0, 0, 0])): raise ValueError("Bake editor group rotations before deriving this design")
                walk(node["children"], path+(group["name"],))
    walk(project["outliner"])
    old = {initial.identifier(f"cube/{i}"): e for i, e in enumerate(baseline["elements"])}
    rows = defaultdict(list)
    changed = added = enabled_original_faces = disabled_original_faces = 0
    for cube in project["elements"]:
        if cube.get("type", "cube") != "cube": raise ValueError("This design uses editable BB cubes")
        original = old.get(cube["uuid"])
        element = {"name": cube["name"], "from": cube["from"].copy(), "to": cube["to"].copy()}
        origin = cube.get("origin", [8, 8, 8]).copy()
        rotation = dict(zip("xyz", cube.get("rotation", [0, 0, 0]))); rotation["origin"] = origin
        if original:
            old_rotation = geometry._parse_raw_rotation(original.get("rotation"), (8, 8, 8))
            meaningful = max(abs(a-b) for key in ("from", "to") for a, b in zip(cube[key], original[key])) > 1e-4
            meaningful |= any(abs(rotation[a]-getattr(old_rotation, a+"_degrees")) > 1e-4 for a in "xyz")
            meaningful |= max(abs(a-b) for a, b in zip(origin, old_rotation.origin)) > 1e-4
            changed += bool(meaningful)
            if not meaningful:
                element["from"], element["to"] = copy.deepcopy(original["from"]), copy.deepcopy(original["to"])
                rotation = dict(zip("xyz", (old_rotation.x_degrees, old_rotation.y_degrees, old_rotation.z_degrees)), origin=list(old_rotation.origin))
        else:
            added += 1
        # The saved BB face pass is authoritative, including on original cubes.
        # Untextured enabled faces omit "texture"; only an explicit null disables
        # a face. Never restore an older automatic visibility mask over this.
        element["faces"] = {f: copy.deepcopy(fields) for f, fields in cube["faces"].items()
                            if fields.get("texture", "enabled") is not None}
        for face in element["faces"].values():
            face.pop("uv", None)
            face["texture"] = "#stone"
        face_changed = False
        if original:
            enabled_original_faces += len(element["faces"].keys() - original["faces"].keys())
            disabled_original_faces += len(original["faces"].keys() - element["faces"].keys())
            face_changed = element["faces"].keys() != original["faces"].keys()
            face_changed |= any(fields.get("cullface") != original["faces"].get(f, {}).get("cullface")
                                for f, fields in element["faces"].items())
        element['_authored_changed'] = bool(meaningful or face_changed) if original else True
        element["rotation"] = rotation
        role = next(g for g in owners[cube["uuid"]] if "__" in g)
        # Added lower infill on the right was placed under CAPITALS in BB.
        # The left is authoritative; its names still identify the lower curve.
        rows[role].append(element)
    return rows, {"master_cubes": len(project["elements"]), "added_cubes": added,
                  "meaningful_original_changes": changed,
                  "newly_enabled_original_faces": enabled_original_faces,
                  "newly_disabled_original_faces": disabled_original_faces,
                  "face_mask_policy": "Saved BB enabled and disabled faces; no automatic face removal"}


def canonical(elements, translation, degrees):
    local = [translated(e, [-v for v in translation]) for e in elements]
    return [half_turn(e) for e in local] if degrees == 180 else local


def source_center(elements, width=48):
    """Clip the center's left half and mirror it, without adding seam faces."""
    output = []
    for element in elements:
        solid = volume(element)
        solid["polygon"] = clip_polygon(solid["polygon"], 0, width/2, False)
        if polygon_area(solid["polygon"]) < 1e-10: continue
        face_sets = [{}]
        points = []
        for face in faces([element]):
            polygon = clip_polygon(face.vertices, 0, width/2, False)
            if polygon_area(polygon) < 1e-10: continue
            pieces = [polygon] if len(polygon) <= 4 else [[polygon[0], polygon[i], polygon[i+1]] for i in range(1, len(polygon)-1)]
            for piece in pieces:
                if len(piece) == 3: piece = piece+[piece[-1]]
                fields = copy.deepcopy(element["faces"][face.face_name]); fields.pop("uv", None)
                fields["erydon_vertices"] = [[round(v, 9) for v in p] for p in piece]
                fields["texture"] = "#stone"
                if face.face_name in face_sets[-1]: face_sets.append({})
                face_sets[-1][face.face_name] = fields
                points.extend(piece)
        for fields in face_sets:
            if not fields: continue
            part = {"name": element["name"]+"_left", "faces": fields,
                    "from": [min(p[i] for p in points) for i in range(3)],
                    "to": [max(p[i] for p in points) for i in range(3)], "_solid": copy.deepcopy(solid)}
            for a in range(3):
                if part["to"][a]-part["from"][a] < 1e-10: part["to"][a] += 1e-8
            output += [part, x_mirror(part, width)]
    return output


def profile_depth_delta(source_old, source_new, target):
    """Transfer depth and lateral profile edits without shrinking column motifs."""
    result = copy.deepcopy(target)
    pairs = [(source_old[i], source_new[i]) for i in range(min(len(source_old), len(source_new)))
             if not any(abs(v) > 1e-8 for v in source_old[i].get("rotation", {}).values() if isinstance(v, (int, float)))]
    # Profiles are matched by depth envelope, not source element order.
    for element in result:
        if abs(element.get("rotation", {}).get("angle", 0)) > 1e-8: continue
        old, new = min(pairs, key=lambda pair: abs(pair[0]["from"][2]-element["from"][2]) + abs(pair[0]["to"][2]-element["to"][2]))
        for bound in ("from", "to"):
            for axis in (0, 2): element[bound][axis] += new[bound][axis]-old[bound][axis]
            if "origin" in element.get("rotation", {}):
                # Keep the zero-angle pivot near the edited profile.
                element["rotation"]["origin"][2] += (new["from"][2]-old["from"][2]+new["to"][2]-old["to"][2])/4
        for fields in element["faces"].values(): fields.pop("uv", None)
    return result


def models_from_master(repo):
    rows, delta = master_elements(repo)
    fixture = load(repo / FIXTURE)["models"]
    models = {}
    mapping = {"corner_large_upper": ("CURVE__crown_left", [0, 48, 0]),
               "corner_large_lower": ("CURVE__curve_lower_left", [0, 32, 0]),
               "side_large": ("WALL__crown_left", [0, 48, 0]),
               "side_large_lower": ("WALL__curve_lower_left", [0, 32, 0]),
               "side_large_upper": ("CAPITAL__capitals_left", [0, 16, 0]),
               "side_column_upper": ("COLUMN__capitals_left", [0, 16, 0])}
    for suffix, (group, translation) in mapping.items(): models[suffix] = raw_document(canonical(rows[group], translation, 180))
    for suffix in ("side_column", "plinth"):
        # Retain untouched repeated motifs exactly. Subsequent authored edits
        # in their left base group become the shared repeatable component.
        group = 'COLUMN__base_left' if suffix=='side_column' else 'PLINTH__base_left'
        if any(e['_authored_changed'] for e in rows[group]):
            source = canonical(rows[group], [0,0,0], 180)
        else:
            source = copy.deepcopy(fixture[suffix]["document"]["elements"])
        for i, element in enumerate(source):
            element.setdefault("name", f"{suffix}_{i+1:03d}")
            for face in element["faces"].values(): face.pop("uv", None)
        models[suffix] = raw_document(source)
    center = source_center(rows["KEYSTONE__crown_center"])
    models["top_large"] = raw_document([translated(e, [-16, -48, 0]) for e in center])
    crown_left = rows["CURVE__crown_left"]+rows["CURVE__curve_lower_left"]
    whole_crown = crown_left+[x_mirror(e, 48) for e in crown_left]+center
    for width, suffix in ((1, "corner_small"), (2, "corner_medium")):
        dx, dy = ALIGNMENT[width]
        narrowed = [baked(e, lambda p: (p[0]*width/3+dx, p[1]*width/3+dy, p[2])) for e in whole_crown]
        if width == 1:
            # The legacy fit's tiny X offset is useful to the left contour,
            # but the complete single-cell crown must mirror at exactly X8.
            narrowed = source_center(narrowed, width=16)
        if width == 2:
            # The two owner models meet at their shared X=16 plane. Clip faces
            # there and leave their authored solid volume for collision.
            selected = []
            for e in narrowed:
                solid = volume(e); solid["polygon"] = clip_polygon(solid["polygon"], 0, 16, False)
                if polygon_area(solid["polygon"]) < 1e-10: continue
                for f in faces([e]):
                    polygon = clip_polygon(f.vertices, 0, 16, False)
                    if polygon_area(polygon) < 1e-10: continue
                    pieces = [polygon] if len(polygon) <= 4 else [[polygon[0], polygon[i], polygon[i+1]] for i in range(1, len(polygon)-1)]
                    for p in pieces:
                        if len(p) == 3: p = p+[p[-1]]
                        fields = copy.deepcopy(e["faces"][f.face_name]); fields["erydon_vertices"] = p
                        part = {"name": e["name"], "from": e["from"], "to": e["to"], "faces": {f.face_name: fields}, "_solid": copy.deepcopy(solid)}
                        selected.append(part)
            narrowed = selected
        models[suffix] = raw_document([half_turn(e) for e in narrowed])
    old_large_side = fixture["side_large"]["document"]["elements"]
    new_large_side = models["side_large"]["elements"]
    # Baked zero-angle meshes have no rotation field; their bounds still
    # describe the same straight profile, ready for depth transfer.
    for suffix in ("side_small", "side_medium"):
        models[suffix] = raw_document(profile_depth_delta(old_large_side, new_large_side, fixture[suffix]["document"]["elements"]))
    old_upper = fixture["side_large_upper"]["document"]["elements"]
    models["side_medium_upper"] = raw_document(profile_depth_delta(old_upper, models["side_large_upper"]["elements"], fixture["side_medium_upper"]["document"]["elements"]))
    return models, delta


def component_selection(width, height, row, column, column_style=True, crown_source=False):
    right = column == width-1
    side = "right" if right else "left"
    if width == 1:
        suffix = ('non_column_side_small_crown' if row == height-1 else
                  'non_column_side_small_base' if row == 0 else 'non_column_side_small')
        return ([('non_column_corner_small', False)] if row == height-1 else []) + [(suffix, False), (suffix, True)]
    if width == 2:
        if row == height-1:
            prefix = '' if column_style else 'non_column_'
            return [(prefix+'corner_medium', right),
                    ('side_medium' if column_style else 'non_column_side_medium_crown', right)]
        if not column_style:
            return [('non_column_side_medium_base' if row == 0 else 'non_column_side_medium', right)]
        result = [('side_column_upper' if row == height-2 else 'side_column', right)]
        if row == height-2: result.insert(0, ('side_medium_upper', right))
        if row == 0: result.append(('plinth', right))
        return result
    if column == 1:
        return [('top_large', None)] if row == height-1 else []
    if row == height-1:
        prefix = '' if column_style else 'non_column_'
        return [(prefix+('corner_medium' if height == 1 else 'corner_large_upper'), right),
                ('side_large' if column_style else 'non_column_side_large_crown', right)]
    if row == height-2:
        prefix = '' if column_style else 'non_column_'
        return [(prefix+'corner_large_lower', right), (prefix+'side_large_lower', right)]
    if not column_style:
        return [('non_column_side_large_base' if row == 0 and not crown_source else 'non_column_side_large', right)]
    if crown_source:
        return [('side_large', right)]
    result = [('side_column_upper' if row == height-3 else 'side_column', right)]
    if row == height-3: result.insert(0, ('side_large_upper', right))
    if row == 0: result.append(('plinth', right))
    return result


def placed_element(element, right, translation):
    if right is False: element = half_turn(element)
    elif right is True: element = depth_mirror(element)
    return translated(element, translation)


def identified(element, suffix, index):
    result = copy.deepcopy(element)
    result['_nodes'] = {face: (suffix, index, face) for face in element['faces']}
    return result


def assembly(models, width, height=4, column_style=True, crown_source=False):
    document = raw_document([]); document['groups'] = []; components = []
    for row in range(height):
        group = {"name": f"ROW_{row}", "origin": [width*8, row*16+8, 8], "children": []}
        for column in range(width):
            cell = {"name": f"CELL_{column}", "origin": [column*16+8, row*16+8, 8], "children": []}
            for suffix, right in component_selection(width, height, row, column, column_style, crown_source):
                start = len(document['elements']); name = f"{suffix}__{row}_{column}"
                elements = [placed_element(identified(e,suffix,i), right, [column*16, row*16, 0])
                            for i,e in enumerate(models[suffix]['elements'])]
                document['elements'] += elements
                cell['children'].append({"name": name, "origin": cell['origin'], "children": list(range(start, start+len(elements)))})
                components.append({"group": name, "suffix": suffix, "source": (RAW/f"arch_romanesque_{suffix}.json").as_posix(),
                                   "cell": [column,row,0], "translation": [column*16,row*16,0],
                                   "runtime_y_rotation": 180 if right is False else 0, "right_reflect_z": right is True,
                                   "element_start": start, "element_count": len(elements)})
            group['children'].append(cell)
        document['groups'].append(group)
    document['__erydon_authoring'] = {"schema": 2, "width_blocks": width, "height_blocks": height,
                                     "facing": "north (-Z)", "style_set": "column" if column_style else "base",
                                     "right_side": "pure X mirror of the left", "components": components}
    return document


def clipped_element_faces(element, bounds):
    result = []
    for face in faces([element]):
        polygon = list(face.vertices)
        for axis, low, high in bounds:
            polygon = clip_polygon(polygon, axis, low, True)
            polygon = clip_polygon(polygon, axis, high, False)
        if polygon_area(polygon) < 1e-10: continue
        pieces = [polygon] if len(polygon) <= 4 else [[polygon[0], polygon[i], polygon[i+1]] for i in range(1, len(polygon)-1)]
        for points in pieces:
            if len(points) == 3: points = points+[points[-1]]
            fields = copy.deepcopy(element['faces'][face.face_name]); fields['erydon_vertices'] = points
            e = copy.deepcopy(element); e['faces'] = {face.face_name: fields}; result.append(e)
    return result


def wide_assembly(models, width, height=None, column_style=True):
    rows = (2*width+2)//3
    height = height or rows+2
    crown_rows = min(height, rows)
    sx, sy = width/3, min(width/3, crown_rows/2) if crown_rows > 1 else 1
    source = assembly(models, 3, 3 if crown_rows > 1 else 1, column_style, crown_source=True)
    result = raw_document([])
    # Live WideArchLayout includes one straight continuation row below its
    # two-row source crown. Target owner cells clip it to the crown boundary.
    for e in source['elements']:
        transformed = baked(e, lambda p: (p[0]*sx, height*16+(p[1]-len(source['groups'])*16)*sy, p[2]))
        result['elements'] += clipped_element_faces(transformed, [(0,0,width*16),(1,(height-crown_rows)*16,height*16)])
    body_height = height-crown_rows
    if body_height:
        for row in range(body_height):
            for column, right in ((0,False),(2,True)):
                local_height = body_height+2
                for suffix, hand in component_selection(3, local_height, row, column, column_style):
                    for i,e in enumerate(models[suffix]['elements']):
                        placed = placed_element(identified(e,suffix,i), hand, [column*16,row*16,0])
                        transformed = baked(placed, lambda p: (p[0]*sx,p[1],p[2]))
                        result['elements'] += clipped_element_faces(transformed, [(0,0,width*16),(1,row*16,(row+1)*16)])
    result['__erydon_authoring'] = {"schema":2,"width_blocks":width,"height_blocks":height,"facing":"north (-Z)",
                                    "style_set":"column" if column_style else "base", "projection":"Existing WideArchLayout; cell union before internal owner cuts"}
    return result


def collision_boxes(elements):
    boxes = []
    for element in elements:
        solid = volume(element); polygon = solid['polygon']
        if polygon_area(polygon) < 1e-10: continue
        low, high = min(p[0] for p in polygon), max(p[0] for p in polygon)
        # Shared global coordinates bound Minecraft's union-grid size.
        first, last = math.floor(low*4+1e-9), math.ceil(high*4-1e-9)
        for step in range(first, last):
            left, right = step/4, (step+1)/4
            strip = clip_polygon(clip_polygon(polygon,0,left,True),0,right,False)
            if polygon_area(strip) < 1e-10: continue
            a,b = min(p[1] for p in strip), max(p[1] for p in strip)
            d1,d2 = solid['depth']
            if b-a <= 1e-10 or d2-d1 <= 1e-10: continue
            box = [left,d1,a,right,d2,b] if solid['axis']=='y' else [left,a,d1,right,b,d2]
            boxes.append([math.floor(v*4+1e-9)/4 if i<3 else math.ceil(v*4-1e-9)/4
                          for i,v in enumerate(box)])
    return [list(b) for b in sorted(set(tuple(b) for b in boxes))]


def clean(document):
    result = copy.deepcopy(document)
    for e in result['elements']:
        e.pop('_solid',None)
        e.pop('_nodes',None)
        e.pop('_authored_changed',None)
        for f in e['faces'].values(): f.pop('uv',None)
    return result


def separate(models):
    """Color source faces across standalone components and live layouts."""
    graph = defaultdict(set)
    observations = 0
    scaled_repeat_nodes = set()
    verifying = False

    def scan(doc, label, suffix=None):
        nonlocal observations
        report = geometry.scan_model_document(clean(doc), relative_path=label,
            source_sha256='', raw_authoring=True, enable_raw_transforms=True)
        unexpected = [u for u in report['unsupported']
                      if u['reason'] != 'zero-thickness element is unsupported']
        if unexpected:
            raise ValueError(f'Unsupported geometry in {label}: {unexpected[:3]}')
        for finding in report['findings']:
            if finding['orientation'] != 'same' or (finding['plane_quality'] != 'exact' and suffix!='icon'):
                continue
            nodes = []
            for end in ('a', 'b'):
                record = finding[end]
                if suffix:
                    node = (suffix, record['element'], record['face'])
                else:
                    node = tuple(doc['elements'][record['element']]['_nodes'][record['face']])
                nodes.append(node)
            a, b = nodes
            if verifying:
                raise ValueError(f'Face separation left an overlap in {label}: {a}, {b}')
            if a == b:
                # WideArchLayout projects the crown continuation in Y while
                # retaining body Y scale 1. A nonzero source bias separates
                # the repeated boundary face; verification below proves it.
                if not label.startswith('cluster/') or int(label.split('/')[1]) < 4:
                    raise ValueError(f'A repeated source face overlaps itself in {label}: {a}')
                scaled_repeat_nodes.add(a)
                graph[a]
                observations += 1
                continue
            graph[a].add(b)
            graph[b].add(a)
            observations += 1

    layouts = [(width,height,style) for width in (1,2,3)
               for height in (1,2,3,4) for style in (False,True)]
    layouts += [(width,height,style) for width in (4,5,6)
                for height in sorted({1,2,3,4,(2*width+2)//3+2})
                for style in (False,True)]

    def inspect_all():
        for suffix, doc in models.items():
            scan(doc, suffix, suffix)
        for width,height,style in layouts:
            doc = assembly(models,width,height,style) if width<=3 else wide_assembly(models,width,height,style)
            scan(doc, f'cluster/{width}/{height}/{style}')

    inspect_all()
    colors = {}
    for node in sorted(graph, key=lambda n:(-len(graph[n]),n)):
        occupied = {colors[n] for n in graph[node] if n in colors}
        start = 1 if node in scaled_repeat_nodes else 0
        colors[node] = next(i for i in range(start,len(occupied)+start+1) if i not in occupied)
    # The miniature icon also compresses existing 0.001-unit gaps. Choose a
    # level wider than the audit's near-plane tier after the quarter scale.
    bias_step = .000375
    max_offset = max(colors.values(), default=0)*bias_step
    if max_offset >= .05:
        raise ValueError('Microscopic face separation limit exceeded')
    for (suffix,i,face), color in colors.items():
        if color:
            models[suffix]['elements'][i]['faces'][face]['erydon_normal_offset'] = round(color*bias_step,8)
    verifying = True
    inspect_all()
    return {'standalone_models_checked':len(models), 'cluster_layouts_checked':len(layouts),
            'observed_overlap_pairs':observations, 'unique_overlap_edges':sum(map(len,graph.values()))//2,
            'overlap_face_nodes':len(graph), 'coplanar_faces_separated':sum(v>0 for v in colors.values()),
            'scaled_repeat_source_faces':len(scaled_repeat_nodes),
            'normal_offset_step':bias_step, 'max_normal_offset':max_offset,
            'remaining_exact_same_plane_overlaps':0, 'remaining_icon_near_same_plane_overlaps':0}


def generate(repo):
    models, delta = models_from_master(repo)
    import derive_romanesque_non_column as non_column
    non_column_models, non_column_report = non_column.derive_components(repo, sys.modules[__name__])
    models.update(non_column_models)
    assemblies = {width: assembly(models,width) for width in (1,2,3)}
    assemblies.update({width:wide_assembly(models,width) for width in (4,5,6)})
    icon_elements = [baked(e,lambda p:(p[0]/4+2,p[1]/4,p[2]/4+6)) for e in assemblies[3]['elements']]
    models['icon']=raw_document(icon_elements)
    collisions={suffix:collision_boxes(doc['elements']) for suffix,doc in models.items() if suffix!='icon'}
    separation=separate(models)
    output={RAW/f'arch_romanesque_{suffix}.json':encoded(clean(doc)) for suffix,doc in models.items()}
    fixture=load(repo/FIXTURE)['models']
    for suffix in models:
        anchor={'parent':'minecraft:block/block','textures':{'particle':'#stone'},'elements':[]}
        if suffix=='icon':anchor['display']=fixture['icon']['document']['display']
        output[PARENTS/f'arch_romanesque_{suffix}.json']=encoded(anchor)
    ids=sorted(p.stem for p in (repo/ASSETS/'blockstates').glob('*_arch_romanesque.json'))
    if len(ids)!=162:raise ValueError(f'Expected 162 Romanesque variants, found {len(ids)}')
    for block_id in ids:
        aged='_aged_' in block_id; base=block_id.replace('_aged_','_',1) if aged else block_id
        sample_path=PARENTS/(base+'_side_column'+('_aged' if aged else '')+'.json')
        sample=load(repo/sample_path)
        for suffix in models:
            path=PARENTS/(base+'_'+suffix+('_aged' if aged else '')+'.json')
            child=copy.deepcopy(sample);child['parent']='erydon:block/arch/romanesque/arch_romanesque_'+suffix
            # Existing material children already point to these anchors.
            # Keep their formatting byte-for-byte when binding is unchanged.
            current_path = repo/path
            output[path] = current_path.read_bytes() if current_path.is_file() and load(current_path)==child else encoded(child)
        item_path=ASSETS/'models/item'/f'{block_id}.json';item=load(repo/item_path)
        # Keep vanilla inheritance on the JSON display anchor. The family
        # wrapper renders the separate material-bound raw icon component.
        item['parent']='erydon:block/arch/romanesque/arch_romanesque_icon'
        output[item_path]=encoded(item)
    for width,doc in assemblies.items():output[DESIGN/f'arch_romanesque_current_{width}wide.json']=encoded(clean(doc))
    for width in range(1, 7):
        doc = assembly(models, width, column_style=False) if width <= 3 else wide_assembly(models, width, column_style=False)
        output[DESIGN/f'arch_romanesque_non_column_current_{width}wide.json'] = encoded(clean(doc))
    output[Path('src/main/resources/assets/erydon/interaction_shapes/arch_romanesque.json')]=encoded({'schema':1,'units_per_block':16,'components':collisions})
    master_hash=hashlib.sha256((repo/MASTER).read_bytes()).hexdigest()
    output[DESIGN/'derivation_manifest.json']=encoded({'schema':1,'master':MASTER.as_posix(),'master_sha256':master_hash,
        **delta,'mirror_policy':'Authoritative LEFT; pure X reflection on RIGHT',
        'non_column_derivation': non_column_report,
        'component_templates':len(models),'material_finish_variants':len(ids),'narrow_alignment':{str(k):list(v) for k,v in ALIGNMENT.items()},
        'narrow_core_xy_scale':'width/3','profile_depth_scale':1,'collision_slice_units':.25,
        'collision_grid_units':.25, 'collision_quantization':'Bounds rounded outward on all axes',
        'face_separation':separation,
        'assemblies':{str(w):{'height_blocks':doc['__erydon_authoring']['height_blocks'],'elements':len(doc['elements'])} for w,doc in assemblies.items()}})
    return output


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check',action='store_true');args=parser.parse_args()
    output=generate(ROOT);changed=[p for p,b in output.items() if not (ROOT/p).is_file() or (ROOT/p).read_bytes()!=b]
    if args.check:
        if changed:
            print(f'Romanesque outputs need regeneration: {len(changed)} files')
            for p in changed[:12]:print(p)
            return 1
        print(f'Romanesque outputs match the edited BB ({len(output)} files).')
        return 0
    for p,b in output.items():(ROOT/p).parent.mkdir(parents=True,exist_ok=True);(ROOT/p).write_bytes(b)
    print(f'Generated Romanesque arches: {len(changed)} changed, {len(output)} outputs; master BB untouched.')
    return 0


if __name__=='__main__':raise SystemExit(main())
