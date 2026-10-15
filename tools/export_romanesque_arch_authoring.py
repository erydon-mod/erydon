#!/usr/bin/env python3
"""Create an editable whole Romanesque arch without changing runtime resources.

The four-high column layout includes every large-crown, shaft, capital and
plinth component. Element transforms reproduce the existing NORTH-facing
three-wide renderer; retained component metadata permits later decomposition.
"""
from __future__ import annotations

import argparse
import base64
import copy
import hashlib
import json
import math
from pathlib import Path
import re
import struct
import uuid

import model_z_fighting_safety as geometry

ROOT = Path(__file__).resolve().parents[1]
DESTINATION = Path("authoring/arches/romanesque")
MODELS = Path("src/main/resources/assets/erydon/models/block/arch/romanesque")
BLOCK_SOURCE = Path("src/main/java/com/oliver/erydon/block/ArchRomanesqueBlock.java")
RENDER_SOURCE = Path("src/main/java/com/oliver/erydon/client/model/ArchRomanesqueBakedModel.java")
LAYOUT_SOURCE = Path("src/main/java/com/oliver/erydon/block/WideArchLayout.java")
TEXTURE = Path("src/main/resources/assets/erydon/textures/block/aganite_block.png")
NAME = "arch_romanesque_3wide"
NAMESPACE = uuid.UUID("a98d8bf6-ef7c-541b-9023-20f4f1c1b475")
FACES = ("north", "south", "east", "west", "up", "down")
MIRRORED_FACES = {"north": "south", "south": "north", "east": "west", "west": "east", "up": "up", "down": "down"}


def encoded(value):
    return (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def load(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def identifier(value):
    return str(uuid.uuid5(NAMESPACE, value))


def point(value, degrees, translation):
    x, y, z = value
    if degrees == 180:
        x, z = 16 - x, 16 - z
    elif degrees != 0:
        raise ValueError("This assembly requires only the renderer's 0/180 degree rotations")
    return [round(v + t, 10) for v, t in zip((x, y, z), translation)]


def element(source, degrees, translation, name):
    result = copy.deepcopy(source)
    result["name"] = source.get("name", name)
    a, b = point(source["from"], degrees, translation), point(source["to"], degrees, translation)
    result["from"] = [min(x, y) for x, y in zip(a, b)]
    result["to"] = [max(x, y) for x, y in zip(a, b)]
    rotation = source.get("rotation", {})
    if rotation.get("rescale", False):
        raise ValueError("Rescaled elements need a dedicated conversion")
    angle = rotation.get("angle", 0)
    axis = rotation.get("axis", "y")
    if degrees == 180 and axis != "y":
        angle = -angle
    result["rotation"] = {"angle": angle, "axis": axis,
                          "origin": point(rotation.get("origin", [8, 8, 8]), degrees, translation)}
    faces = {}
    for face, fields in source["faces"].items():
        target = MIRRORED_FACES[face] if degrees == 180 else face
        copied = copy.deepcopy(fields)
        copied["texture"] = "#stone"
        if "cullface" in copied and degrees == 180:
            copied["cullface"] = MIRRORED_FACES[copied["cullface"]]
        faces[target] = copied
    result["faces"] = faces
    return result


def source_group(group, start, degrees, translation):
    if isinstance(group, int):
        return start + group
    result = copy.deepcopy(group)
    if any(group.get("rotation", [0, 0, 0])):
        raise ValueError("Source group rotations require explicit handling")
    result["origin"] = point(group.get("origin", [8, 8, 8]), degrees, translation)
    result["children"] = [source_group(child, start, degrees, translation) for child in group["children"]]
    return result


def arrangements(repo):
    pattern = re.compile(r'^\s*(\w+)\("([^"]+)",\s*Corner\.(\w+),\s*(true|false),\s*Side\.(\w+),\s*Side\.(\w+),\s*Upper\.(\w+),\s*Upper\.(\w+),\s*(true|false),\s*(true|false),\s*(true|false),\s*(true|false),\s*(true|false),\s*(true|false)\)', re.M)
    return {match[1]: match.groups()[2:] for match in pattern.finditer((repo / BLOCK_SOURCE).read_text())}


def selected_components(arrangement):
    corner, flipped, side_l, side_r, upper_l, upper_r, column_l, column_r, plinth_l, plinth_r, top, _ = arrangement
    selected = []
    if corner != "NONE":
        selected.append(("CURVE", "corner_" + corner.lower(), 0 if flipped == "true" else 180))
    for role, suffix, left, right in (("WALL", "side", side_l, side_r), ("CAPITAL", "side", upper_l, upper_r)):
        for value, degrees in ((left, 180), (right, 0)):
            if value != "NONE":
                selected.append((role, suffix + "_" + value.lower() + ("_upper" if role == "CAPITAL" else ""), degrees))
    for role, suffix, left, right in (("COLUMN", "side_column", column_l, column_r), ("PLINTH", "plinth", plinth_l, plinth_r)):
        for flag, degrees in ((left, 180), (right, 0)):
            if flag == "true":
                selected.append((role, suffix, degrees))
    if top == "true":
        selected.append(("KEYSTONE", "top_large", 0))
    return selected


def assemble(repo):
    definitions = arrangements(repo)
    rows = [
        ("BASE", 0, "TRIPLE_COLUMN_BASE_L", "TRIPLE_COLUMN_BASE_R"),
        ("CAPITALS", 1, "TRIPLE_COLUMN_UPPER_L", "TRIPLE_COLUMN_UPPER_R"),
        ("CURVE_LOWER", 2, "TRIPLE_ROW2_L", "TRIPLE_ROW2_R"),
        ("CROWN", 3, "TRIPLE_TOP_L", "TRIPLE_TOP_R"),
    ]
    document = {"format_version": "1.21.11", "credit": "ERYDON Romanesque arch editing assembly",
                "textures": {"stone": "erydon:block/aganite_block", "particle": "#stone"},
                "elements": [], "groups": []}
    components = []
    for row_name, y, left, right in rows:
        row_group = {"name": row_name, "origin": [24, y * 16 + 8, 8], "children": []}
        cells = [("LEFT", 0, left), ("RIGHT", 2, right)]
        if row_name == "CROWN":
            cells.insert(1, ("CENTER", 1, "TOP_LARGE"))
        for cell_name, x, arrangement in cells:
            cell = {"name": cell_name, "origin": [x * 16 + 8, y * 16 + 8, 8], "children": []}
            translation = [x * 16, y * 16, 0]
            for role, suffix, degrees in selected_components(definitions[arrangement]):
                source_path = MODELS / f"arch_romanesque_{suffix}.json"
                source = load(repo / source_path)
                start = len(document["elements"])
                for index, cube in enumerate(source["elements"]):
                    document["elements"].append(element(cube, degrees, translation, f"{suffix}_{index + 1:03d}"))
                name = f"{role}__{row_name.lower()}_{cell_name.lower()}"
                group = {"name": name, "origin": cell["origin"].copy(), "children":
                         [source_group(g, start, degrees, translation) for g in source.get("groups", list(range(len(source["elements"]))))]}
                cell["children"].append(group)
                components.append({"group": name, "source": source_path.as_posix(), "suffix": suffix,
                                   "arrangement": arrangement.lower(), "cell": [x, y, 0], "translation": translation,
                                   "runtime_y_rotation": degrees, "element_start": start,
                                   "element_count": len(source["elements"])})
            row_group["children"].append(cell)
        document["groups"].append(row_group)
    document["__erydon_authoring"] = {"schema": 1, "width_blocks": 3, "height_blocks": 4,
                                     "units_per_block": 16, "facing": "north (-Z)", "style_set": "column",
                                     "reflected": False, "components": components}
    return document


def vanilla_uv(cube, face):
    f, t = cube["from"], cube["to"]
    return {"north": [16-t[0], 16-t[1], 16-f[0], 16-f[1]],
            "south": [f[0], 16-t[1], t[0], 16-f[1]],
            "east": [16-t[2], 16-t[1], 16-f[2], 16-f[1]],
            "west": [f[2], 16-t[1], t[2], 16-f[1]],
            "up": [f[0], f[2], t[0], t[2]],
            "down": [f[0], 16-t[2], t[0], 16-f[2]]}[face]


def native_project(repo, document):
    png = (repo / TEXTURE).read_bytes()
    width, height = struct.unpack('>II', png[16:24])
    project = {"meta": {"format_version": "5.0", "model_format": "free", "box_uv": False},
               "name": NAME, "model_identifier": "", "resolution": {"width": 16, "height": 16},
               "elements": [], "groups": [], "outliner": [], "textures": [{
                   "name": "aganite_block.png", "path": "", "folder": "block", "namespace": "erydon", "id": "stone",
                   "width": width, "height": height, "uv_width": 16, "uv_height": 16, "particle": True,
                   "internal": True, "saved": False, "uuid": identifier("texture"),
                   "source": "data:image/png;base64," + base64.b64encode(png).decode()}],
               "unhandled_root_fields": {"__erydon_authoring": copy.deepcopy(document["__erydon_authoring"])}}
    ids = []
    for index, cube in enumerate(document["elements"]):
        uid = identifier(f"cube/{index}")
        ids.append(uid)
        rotation = cube["rotation"]
        native = {"name": cube["name"], "uuid": uid, "type": "cube", "box_uv": False, "autouv": 0,
                  "from": cube["from"], "to": cube["to"], "origin": rotation["origin"],
                  "rotation": [rotation["angle"] if rotation["axis"] == axis else 0 for axis in ("x", "y", "z")], "faces": {}}
        component = next(c for c in document["__erydon_authoring"]["components"]
                         if c["element_start"] <= index < c["element_start"] + c["element_count"])
        source = load(repo / component["source"])["elements"][index - component["element_start"]]
        degrees = component["runtime_y_rotation"]
        for face in FACES:
            source_face = MIRRORED_FACES[face] if degrees == 180 else face
            enabled = source_face in source["faces"]
            fields = source["faces"].get(source_face, {})
            face_rotation = fields.get("rotation", 0)
            if degrees == 180 and face in ("up", "down"):
                face_rotation = (face_rotation + 180) % 360
            native["faces"][face] = {"uv": fields.get("uv", vanilla_uv(source, source_face)),
                                     "texture": 0 if enabled else None, "rotation": face_rotation}
            if enabled and "cullface" in cube["faces"][face]:
                native["faces"][face]["cullface"] = cube["faces"][face]["cullface"]
        project["elements"].append(native)

    def convert(group, path):
        if isinstance(group, int):
            return ids[group]
        current = path + "/" + group["name"]
        uid = identifier(current)
        # Some source column groups share a name. Include sibling index below.
        project["groups"].append({"name": group["name"], "uuid": uid, "origin": group["origin"], "rotation": [0, 0, 0]})
        return {"uuid": uid, "children": [convert(child, current + f"/{i}") for i, child in enumerate(group["children"])]}
    project["outliner"] = [convert(group, f"root/{i}") for i, group in enumerate(document["groups"])]
    return project


def transformed_faces(document):
    rotations, _ = geometry._raw_group_state(document, len(document["elements"]))
    faces, errors = geometry._build_faces(document["elements"], raw_group_rotations=rotations)
    if errors:
        raise ValueError(f"Unsupported authoring geometry: {errors}")
    return faces


def verify(repo, document, native):
    actual = {(face.element_index, face.face_name): face for face in transformed_faces(document)}
    max_error, count = 0.0, 0
    for component in document["__erydon_authoring"]["components"]:
        source = load(repo / component["source"])
        source_faces, errors = geometry._build_faces(source["elements"])
        if errors:
            raise ValueError(f"Unsupported runtime model: {component['source']}: {errors}")
        for source_face in source_faces:
            name = MIRRORED_FACES[source_face.face_name] if component["runtime_y_rotation"] == 180 else source_face.face_name
            face = actual[component["element_start"] + source_face.element_index, name]
            expected = [point(p, component["runtime_y_rotation"], component["translation"]) for p in source_face.vertices]
            error = max(min(math.dist(p, q) for q in face.vertices) for p in expected)
            max_error = max(max_error, error)
            assert error < 1e-8, (component["group"], source_face.element_index, name, error)
            expected_normal = source_face.normal
            if component["runtime_y_rotation"] == 180:
                expected_normal = (-expected_normal[0], expected_normal[1], -expected_normal[2])
            assert math.dist(expected_normal, face.normal) < 1e-8, (component["group"], name, "normal")
            count += 1
    # Check the native BB cube rotations and enabled faces independently.
    native_raw = {"elements": []}
    for cube in native["elements"]:
        native_raw["elements"].append({"from": cube["from"], "to": cube["to"],
                                       "rotation": dict(zip(("x", "y", "z"), cube["rotation"]), origin=cube["origin"]),
                                       "faces": {face: {"texture": "#stone"} for face, fields in cube["faces"].items() if fields["texture"] is not None}})
    native_faces = {(f.element_index, f.face_name): f for f in transformed_faces(native_raw)}
    assert native_faces.keys() == actual.keys()
    for key, face in actual.items():
        error = max(min(math.dist(p, q) for q in native_faces[key].vertices) for p in face.vertices)
        assert error < 1e-8, (key, error)
        assert math.dist(face.normal, native_faces[key].normal) < 1e-8, (key, "native normal")
    assert len({g["uuid"] for g in native["groups"]}) == len(native["groups"])
    return {"elements": len(document["elements"]), "faces": count, "component_instances": len(document["__erydon_authoring"]["components"]),
            "max_source_vertex_error_model_units": max_error, "native_cube_face_parity": True}


def preview(document):
    from PIL import Image, ImageDraw, ImageFont
    image = Image.new("RGB", (1600, 1150), "#f6f4ef")
    draw = ImageDraw.Draw(image)
    try:
        title_font, label_font = ImageFont.truetype("arial.ttf", 34), ImageFont.truetype("arial.ttf", 21)
    except OSError:
        title_font = label_font = ImageFont.load_default()
    draw.text((55, 35), "Romanesque arch — editable 3-wide assembly", fill="#272b2a", font=title_font)
    draw.text((55, 87), "4 blocks high · column setting · current runtime geometry", fill="#666963", font=label_font)
    faces = transformed_faces(document)

    def norm(v):
        length = math.sqrt(sum(x*x for x in v)); return [x/length for x in v]
    def dot(a, b): return sum(x*y for x, y in zip(a, b))
    def cross(a, b): return [a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]]

    for label, eye, viewport in (("Straight view", [24, 32, 120], (75, 185, 690, 1030)),
                                  ("Three-quarter view", [110, 82, 140], (790, 185, 1510, 1030))):
        center = [24, 32, 8]
        forward = norm([center[i]-eye[i] for i in range(3)])
        right, up = norm(cross(forward, [0, 1, 0])), None
        up = cross(right, forward)
        def project(p):
            v = [p[i]-center[i] for i in range(3)]
            return dot(v, right), dot(v, up), dot(v, forward)
        vertices = [project(p) for f in faces for p in f.vertices]
        min_x, max_x = min(v[0] for v in vertices), max(v[0] for v in vertices)
        min_y, max_y = min(v[1] for v in vertices), max(v[1] for v in vertices)
        x1, y1, x2, y2 = viewport
        scale = min((x2-x1)/(max_x-min_x), (y2-y1)/(max_y-min_y)) * .9
        cx, cy = (x1+x2)/2, (y1+y2)/2
        light = norm([-.5, .8, .9])
        polygons = []
        for face in faces:
            if dot(face.normal, [-v for v in forward]) <= 1e-7:
                continue
            points = [project(p) for p in face.vertices]
            shade = .53 + .47*max(0, dot(face.normal, light))
            colour = tuple(round(channel*shade) for channel in (208, 200, 180))
            polygons.append((sum(p[2] for p in points)/4,
                             [(cx+(p[0]-(min_x+max_x)/2)*scale, cy-(p[1]-(min_y+max_y)/2)*scale) for p in points], colour))
        for _, polygon, colour in sorted(polygons, reverse=True, key=lambda p: p[0]):
            draw.polygon(polygon, fill=colour)
        draw.text((x1, y2+25), label, fill="#3d413d", font=label_font)
    draw.text((55, 1100), "Software preview of source geometry. In-game lighting and connected textures are not shown.", fill="#6a6d66", font=label_font)
    return image


README = """# Romanesque arch editing

Open **arch_romanesque_3wide.bbmodel** in Blockbench. This is the complete existing three-wide arch, four blocks high, with the column setting enabled. Its Aganite texture is embedded, and the Free Model format lets you edit the whole structure without a single-block size limit.

Only the `.bbmodel` needs editing. Save that same file after adding your detailing; Codex can then extract its components and adapt the changes across widths 1–6, repeated heights, and material/finish parents.

- `CROWN` contains the upper curved corners, their walls, and the central keystone.
- `CURVE_LOWER` contains the lower curved corners and walls.
- `CAPITALS` contains the column transitions and the first shaft row.
- `BASE` contains the lower shaft row and plinths.
- Each row has `LEFT`/`RIGHT` groups; the crown also has `CENTER`. Keep these names and put new cubes inside the relevant `CURVE__`, `WALL__`, `CAPITAL__`, `COLUMN__`, `PLINTH__`, or `KEYSTONE__` component group.

16 model units equal one Minecraft block. The complete structure occupies X=0–48, Y=0–64, Z=0–16, with small existing authored projections. It uses the renderer's NORTH-facing orientation (-Z), extends across +X, and is open on both faces. Component rotations have already been applied, so every editor group has zero rotation.

The two shaft rows represent the same repeatable column component. Detailing repeated in every shaft row should match; plinth-only and capital-only details belong in their separate groups. Width 3 is enough as the main design reference. Narrower examples are needed only if a specific detail leaves a design choice unclear. Widths 4–6 currently reuse the three-wide crown with proportional width/height growth; Codex will retain that behaviour when integrating the edited design.

The matching `arch_romanesque_3wide.json` is a generated geometry reference. `assembly_manifest.json` records the original runtime components, transformations and vertex checks. `assembly_preview.png` shows the starting geometry. Editing these authoring files does not update the in-game arch until the conversion is completed.

Do not replace the master by exporting it through the single-block Java format. Keep the editable `.bbmodel`; cube-based detailing preserves the simplest conversion path.

The authoring export can be checked with `python tools/export_romanesque_arch_authoring.py --check`. Re-exporting refuses to overwrite an edited master.
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    document = assemble(ROOT)
    native = native_project(ROOT, document)
    validation = verify(ROOT, document, native)
    sources = {c["source"] for c in document["__erydon_authoring"]["components"]}
    sources.update(p.as_posix() for p in (BLOCK_SOURCE, RENDER_SOURCE, LAYOUT_SOURCE, TEXTURE))
    manifest = {"schema": 1, "purpose": "Editable Romanesque arch; runtime sources are unchanged",
                "master": NAME + ".bbmodel", "json": NAME + ".json", "width_blocks": 3, "height_blocks": 4,
                "units_per_block": 16, "facing": "north (-Z)", "style_set": "column", "validation": validation,
                "components": document["__erydon_authoring"]["components"],
                "source_hashes": {p: hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for p in sorted(sources)}}
    output = {NAME + ".json": encoded(document), NAME + ".bbmodel": encoded(native),
              "assembly_manifest.json": encoded(manifest), "README.md": README.encode("utf-8")}
    directory = ROOT / DESTINATION
    if args.check:
        changed = [name for name, data in output.items() if not (directory/name).is_file() or (directory/name).read_bytes() != data]
        if changed:
            raise SystemExit("Authoring files differ from the initial export (edited master preserved): " + ", ".join(changed))
        if not (directory/"assembly_preview.png").is_file():
            raise SystemExit("Missing assembly preview")
    else:
        master = directory/(NAME + ".bbmodel")
        if master.exists() and master.read_bytes() != output[master.name]:
            raise SystemExit("The editable master has changed; refusing to overwrite it")
        directory.mkdir(parents=True, exist_ok=True)
        for name, data in output.items():
            (directory/name).write_bytes(data)
        preview(document).save(directory/"assembly_preview.png")
    print(json.dumps(validation, indent=2))


if __name__ == "__main__":
    main()
