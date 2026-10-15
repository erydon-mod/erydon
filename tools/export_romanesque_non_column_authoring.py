#!/usr/bin/env python3
"""Export a protected, editable 3-wide Romanesque arch in its base setting.

The jambs and arch sides remain native Blockbench cubes. Only the shared
keystone, whose mirrored center cuts require polygons, uses editable meshes.
Runtime resources and the existing column master are never written here.
Source changes can refresh an unchanged export; edited masters are protected.
"""
from __future__ import annotations

import argparse
import base64
import copy
import hashlib
import io
import json
import math
from pathlib import Path
import struct
import uuid

import export_romanesque_arch_authoring as initial
import generate_romanesque_arch as arch
import model_z_fighting_safety as geometry

ROOT = Path(__file__).resolve().parents[1]
DESTINATION = Path("authoring/arches/romanesque")
NAME = "arch_romanesque_non_column_3wide"
NAMESPACE = uuid.UUID("b6608cea-8b4e-5a69-a7dd-4947a829c075")
WIDTH, HEIGHT = 3, 4
MASTER_PARTS = {
    "corner_large_upper": ("CURVE__crown_left", 48),
    "corner_large_lower": ("CURVE__curve_lower_left", 32),
    "side_large": ("WALL__crown_left", 48),
    "side_large_lower": ("WALL__curve_lower_left", 32),
}
ROW_NAMES = ("BASE", "BODY", "CURVE_LOWER", "CROWN")


def identifier(value):
    return str(uuid.uuid5(NAMESPACE, value))


def translated_cube(source, delta, right):
    """Move an editable master cube, reflecting X without reversing depth."""
    result = {key: copy.deepcopy(source[key]) for key in ("name", "from", "to", "faces", "rotation")}
    for key in ("from", "to"):
        result[key] = [round(v + d, 10) for v, d in zip(result[key], delta)]
    rotation = result["rotation"]
    rotation["origin"] = [round(v + d, 10) for v, d in zip(rotation["origin"], delta)]
    if right:
        result["from"][0], result["to"][0] = WIDTH * 16 - result["to"][0], WIDTH * 16 - result["from"][0]
        rotation["origin"][0] = WIDTH * 16 - rotation["origin"][0]
        for axis in ("y", "z"):
            rotation[axis] = -rotation[axis]
        result["faces"] = {arch.FACE_X.get(face, face): fields for face, fields in result["faces"].items()}
        for fields in result["faces"].values():
            if "cullface" in fields:
                fields["cullface"] = arch.FACE_X.get(fields["cullface"], fields["cullface"])
    for fields in result["faces"].values():
        fields.pop("uv", None)
    return result


def assemble(repo):
    master_rows, _ = arch.master_elements(repo)
    document = arch.raw_document([])
    document["credit"] = "ERYDON editable Romanesque arch base setting"
    document["groups"] = []
    components = []
    runtime_models = {}
    for row, row_name in enumerate(ROW_NAMES):
        row_group = {"name": row_name, "origin": [24, row * 16 + 8, 8], "children": []}
        for column in range(WIDTH):
            selection = arch.component_selection(WIDTH, HEIGHT, row, column, False)
            if not selection:
                continue
            side_name = ("LEFT", "CENTER", "RIGHT")[column]
            cell = {"name": side_name, "origin": [column * 16 + 8, row * 16 + 8, 8], "children": []}
            for suffix, right in selection:
                source = arch.RAW / f"arch_romanesque_{suffix}.json"
                runtime = runtime_models.setdefault(suffix, initial.load(repo / source))
                start = len(document["elements"])
                shared = suffix != "side_large" or row == HEIGHT - 1
                if suffix == "top_large":
                    elements = [arch.placed_element(e, None, [column * 16, row * 16, 0]) for e in runtime["elements"]]
                    source_group = "KEYSTONE__crown_center"
                    role = "KEYSTONE"
                else:
                    source_group, source_y = MASTER_PARTS[suffix]
                    elements = [translated_cube(e, [0, row * 16 - source_y, 0], right) for e in master_rows[source_group]]
                    role = "CURVE" if suffix.startswith("corner_") else "WALL"
                for index, element in enumerate(elements):
                    element.pop("_solid", None)
                    element["name"] = element.get("name", f"{suffix}_{index + 1:03d}")
                document["elements"].extend(elements)
                name = f"{role}__{row_name.lower()}_{side_name.lower()}"
                cell["children"].append({"name": name, "origin": cell["origin"].copy(),
                                         "children": list(range(start, start + len(elements)))})
                components.append({
                    "group": name, "source": source.as_posix(), "suffix": suffix,
                    "source_master": arch.MASTER.as_posix(), "source_master_group": source_group,
                    "cell": [column, row, 0], "translation": [column * 16, row * 16, 0],
                    "runtime_y_rotation": 180 if right is False else 0, "right_reflect_z": right is True,
                    "editable_representation": "mesh" if suffix == "top_large" else "cube",
                    "shared_with_column_setting": shared,
                    "element_start": start, "element_count": len(elements),
                })
            row_group["children"].append(cell)
        document["groups"].append(row_group)
    document["__erydon_authoring"] = {
        "schema": 3, "width_blocks": WIDTH, "height_blocks": HEIGHT,
        "units_per_block": 16, "facing": "north (-Z)", "style_set": "base",
        "right_side": "pure X mirror of the left", "editable_master": NAME + ".bbmodel",
        "source_master": arch.MASTER.as_posix(), "components": components,
        "enabled_faces": "Saved face choices are authoritative; no automatic face removal is applied",
        "runtime_uvs": "Implicit world mapping; editor UVs are preview data only",
    }
    return document, runtime_models


def native_project(repo, document):
    original = initial.load(repo / arch.MASTER)
    groups = {group["uuid"]: group for group in original["groups"]}
    source_owners = {}

    def walk_source(nodes, role=None):
        for node in nodes:
            if isinstance(node, str):
                source_owners[node] = role
            else:
                name = groups[node["uuid"]]["name"]
                walk_source(node["children"], name if "__" in name else role)

    walk_source(original["outliner"])
    master_ids = {}
    for element in original["elements"]:
        master_ids.setdefault(source_owners[element["uuid"]], []).append(element)
    png = (repo / initial.TEXTURE).read_bytes()
    texture_width, texture_height = struct.unpack(">II", png[16:24])
    texture = {"name": "aganite_block.png", "path": "", "folder": "block", "namespace": "erydon", "id": "stone",
               "width": texture_width, "height": texture_height, "uv_width": 16, "uv_height": 16, "particle": True,
               "internal": True, "saved": False, "uuid": identifier("texture"),
               "source": "data:image/png;base64," + base64.b64encode(png).decode()}
    project = {"meta": {"format_version": "5.0", "model_format": "free", "box_uv": False},
               "name": NAME, "model_identifier": "", "resolution": {"width": 16, "height": 16},
               "elements": [], "groups": [], "outliner": [], "textures": [texture],
               "unhandled_root_fields": {"__erydon_authoring": copy.deepcopy(document["__erydon_authoring"])}}
    ids = []
    provenance = {}
    for component in document["__erydon_authoring"]["components"]:
        for local in range(component["element_count"]):
            index = component["element_start"] + local
            element = document["elements"][index]
            uid = identifier(f"{component['group']}/{component['suffix']}/{local}")
            ids.append(uid)
            source_group = master_ids[component["source_master_group"]]
            if component["editable_representation"] == "mesh":
                source_name = element["name"].removesuffix("_left")
                master_uuid = next((source["uuid"] for source in source_group if source["name"] == source_name), None)
                face_map = {}
            else:
                master_uuid = source_group[local]["uuid"]
                face_map = arch.FACE_Z if component["right_reflect_z"] else arch.FACE_Y180
            provenance[uid] = {"component_group": component["group"], "source": component["source"],
                               "source_element": local, "source_master_group": component["source_master_group"],
                               "source_master_element_uuid": master_uuid,
                               "source_faces": {face: face_map.get(face, face) for face in element["faces"]}}
            if master_uuid is None:
                raise ValueError(f"Cannot identify the source cube for {element['name']}")
            if component["editable_representation"] == "mesh":
                mesh = {"name": element["name"], "uuid": uid, "type": "mesh", "origin": [0, 0, 0],
                        "rotation": [0, 0, 0], "shading": "flat", "vertices": {}, "faces": {}}
                vertex_ids = {}
                for face in arch.faces([element]):
                    keys, mapping = [], {}
                    vertices = []
                    for vertex in face.vertices:
                        if not any(math.dist(vertex, other) < 1e-9 for other in vertices):
                            vertices.append(vertex)
                    uv = geometry._raw_default_uv(vertices, face.face_name)
                    for i, vertex in enumerate(vertices):
                        key = vertex_ids.get(tuple(vertex))
                        if key is None:
                            key = f"v{len(vertex_ids)}"
                            vertex_ids[tuple(vertex)] = key
                            mesh["vertices"][key] = list(vertex)
                        keys.append(key)
                        mapping[key] = uv[i * 2:i * 2 + 2]
                    mesh["faces"][face.face_name] = {"vertices": keys, "uv": mapping, "texture": 0}
                project["elements"].append(mesh)
                continue
            rotation = element["rotation"]
            cube = {key: copy.deepcopy(element[key]) for key in ("name", "from", "to")}
            cube.update({"uuid": uid, "type": "cube", "box_uv": False, "autouv": 0,
                         "origin": copy.deepcopy(rotation["origin"]),
                         "rotation": [rotation.get(axis, 0) for axis in "xyz"], "faces": {}})
            for face in initial.FACES:
                fields = {"uv": [0, 0, 16, 16], "texture": 0 if face in element["faces"] else None}
                if "cullface" in element["faces"].get(face, {}):
                    fields["cullface"] = element["faces"][face]["cullface"]
                cube["faces"][face] = fields
            project["elements"].append(cube)

    def convert(group, path):
        if isinstance(group, int):
            return ids[group]
        uid = identifier(path + "/" + group["name"])
        project["groups"].append({"name": group["name"], "uuid": uid,
                                   "origin": group["origin"], "rotation": [0, 0, 0]})
        return {"uuid": uid, "children": [convert(child, path + f"/{group['name']}/{i}")
                                             for i, child in enumerate(group["children"])]}
    project["outliner"] = [convert(group, f"root/{i}") for i, group in enumerate(document["groups"])]
    project["unhandled_root_fields"]["__erydon_authoring"]["element_provenance"] = provenance
    return project


def native_geometry(project):
    result = []
    for element in project["elements"]:
        if element["type"] == "cube":
            result.append({"name": element["name"], "from": element["from"], "to": element["to"],
                           "rotation": dict(zip("xyz", element["rotation"]), origin=element["origin"]),
                           "faces": {face: {"texture": "#stone"} for face, fields in element["faces"].items()
                                     if fields.get("texture") is not None}})
        else:
            fields = {}
            points = list(element["vertices"].values())
            for face, value in element["faces"].items():
                vertices = [element["vertices"][key] for key in value["vertices"]]
                if len(vertices) == 3:
                    vertices.append(vertices[-1])
                fields[face] = {"texture": "#stone", "erydon_vertices": vertices}
            result.append({"name": element["name"], "from": [min(p[a] for p in points) for a in range(3)],
                           "to": [max(p[a] for p in points) + 1e-8 for a in range(3)], "faces": fields})
    return result


def verify(document, native, runtime_models):
    actual = {(face.element_index, face.face_name): face for face in arch.faces(document["elements"])}
    native_faces = {(face.element_index, face.face_name): face for face in arch.faces(native_geometry(native))}
    if actual.keys() != native_faces.keys():
        raise ValueError("Native editable faces differ from the assembled reference")
    native_error = 0.0
    for key, face in actual.items():
        other = native_faces[key]
        error = max(min(math.dist(p, q) for q in other.vertices) for p in face.vertices)
        native_error = max(native_error, error)
        if error > 1e-8 or math.dist(face.normal, other.normal) > 1e-8:
            raise ValueError(f"Native face geometry changed: {key}")
    runtime_error, compared, max_bias = 0.0, 0, 0.0
    for component in document["__erydon_authoring"]["components"]:
        runtime = runtime_models[component["suffix"]]
        if len(runtime["elements"]) != component["element_count"]:
            raise ValueError("Runtime models differ from the saved master; regenerate Romanesque models first")
        right = None if component["suffix"] == "top_large" else component["right_reflect_z"]
        placed = [arch.placed_element(element, right, component["translation"]) for element in runtime["elements"]]
        expected = {(face.element_index + component["element_start"], face.face_name): face for face in arch.faces(placed)}
        component_keys = {key for key in actual if component["element_start"] <= key[0] < component["element_start"] + component["element_count"]}
        if expected.keys() != component_keys:
            raise ValueError(f"Runtime enabled faces differ from saved master: {component['group']}; regenerate models first")
        for element in runtime["elements"]:
            max_bias = max(max_bias, *(abs(face.get("erydon_normal_offset", 0)) for face in element["faces"].values()))
        for key, face in expected.items():
            error = max(min(math.dist(p, q) for q in actual[key].vertices) for p in face.vertices)
            runtime_error = max(runtime_error, error)
            if error > max_bias + 1e-7 or math.dist(face.normal, actual[key].normal) > 1e-7:
                raise ValueError(f"Runtime geometry changed beyond face-separation bias: {component['group']} {key} {error}")
            compared += 1
    uuids = [element["uuid"] for element in native["elements"]] + [group["uuid"] for group in native["groups"]]
    if len(set(uuids)) != len(uuids):
        raise ValueError("Duplicate Blockbench UUID")
    return {"elements": len(document["elements"]), "editable_cubes": sum(e["type"] == "cube" for e in native["elements"]),
            "editable_center_meshes": sum(e["type"] == "mesh" for e in native["elements"]),
            "enabled_faces": len(actual), "runtime_faces_compared": compared,
            "max_native_vertex_error_model_units": native_error,
            "max_runtime_vertex_error_model_units": runtime_error,
            "max_runtime_safety_bias_model_units": max_bias,
            "enabled_face_parity": True, "unique_uuids": True}


README = """# Romanesque arch without columns

Open **arch_romanesque_non_column_3wide.bbmodel** in Blockbench and save your edits in that same file. It is the complete 3-wide, 4-high master for the non-column setting. The original `arch_romanesque_3wide.bbmodel` remains the separate column-setting master.

- `BASE` and `BODY` contain the jambs. Edit the side nearest X=0; the opposite side is mirrored during generation. If the bottom needs its own detail, keep that edit in `BASE`.
- `CURVE_LOWER` contains the lower arch curve and its adjacent wall.
- `CROWN` contains the upper arch curve, adjacent wall, and central keystone. Non-column arch and wall edits remain independent of the column setting. The common keystone comes from the column-setting master.
- Keep the existing row, side and component group names. Put new elements in the appropriate component group so later extraction remains unambiguous.

The sides and jambs are ordinary editable cubes. The central keystone uses editable meshes because its exact mirrored shape includes angled cuts through the middle. Both are editable in this Generic/Free Model project. Keep this format rather than converting to a single-block Java model.

16 units equal one Minecraft block. The assembly spans X=0–48, Y=0–64, Z=0–16, with the authored projections. The front is NORTH (-Z). Keep editor group rotations at zero. Blockbench UVs and texture assignments are editor preview data; placed models use automatic world texture mapping.

The matching initial JSON, `non_column_assembly_manifest.json` and `non_column_assembly_preview.png` preserve the original export reference. Current generated geometry is in `arch_romanesque_non_column_current_1wide.json` through `arch_romanesque_non_column_current_6wide.json`. The `non_column_assemblies` and `non_column_assemblies_wide` previews show the current family. Saved enabled/disabled face choices are preserved; generation does not remove faces automatically. Tiny runtime face offsets prevent flicker without changing the editable master.

Only the `.bbmodel` needs editing. After saving it, run `python tools/generate_romanesque_arch.py` to update widths 1–6, repeated heights, material/finish models and interaction shapes. Check the generated files with `python tools/generate_romanesque_arch.py --check`. The generator reads both saved masters independently and never rewrites either `.bbmodel`.

The initial exporter is only for creating the starting editable assembly. Its manifest records the exported master's SHA-256, and it refuses to re-export an edited master. Use the family generator above for normal integration of saved edits.
"""


def preview(document):
    # The existing software geometry renderer is shared; only its descriptive
    # text changes. This does not alter any user artwork or runtime resource.
    from PIL import ImageDraw, ImageFont
    # The shared renderer looks from +Z. Reflect its viewing coordinates so
    # the preview shows this NORTH-facing project's detailed front at -Z.
    viewed = copy.deepcopy(document)
    viewed["elements"] = [arch.depth_mirror(element) for element in document["elements"]]
    image = initial.preview(viewed)
    draw = ImageDraw.Draw(image)
    draw.rectangle((45, 25, 1550, 125), fill="#f6f4ef")
    try:
        title_font, label_font = ImageFont.truetype("arial.ttf", 34), ImageFont.truetype("arial.ttf", 21)
    except OSError:
        title_font = label_font = ImageFont.load_default()
    draw.text((55, 35), "Romanesque arch — editable non-column setting", fill="#272b2a", font=title_font)
    draw.text((55, 87), "3 blocks wide · 4 blocks high · current shared crown and plain jambs", fill="#666963", font=label_font)
    return image


def output_files(repo):
    document, runtime_models = assemble(repo)
    native = native_project(repo, document)
    validation = verify(document, native, runtime_models)
    sources = {arch.MASTER.as_posix(), initial.BLOCK_SOURCE.as_posix(), initial.RENDER_SOURCE.as_posix(), initial.TEXTURE.as_posix()}
    sources.update(component["source"] for component in document["__erydon_authoring"]["components"])
    sources.update(("tools/generate_romanesque_arch.py", "tools/export_romanesque_non_column_authoring.py"))
    native_bytes = initial.encoded(native)
    manifest = {"schema": 1, "purpose": "Editable Romanesque arch base setting; runtime sources unchanged",
                "master": NAME + ".bbmodel", "json": NAME + ".json", "width_blocks": WIDTH, "height_blocks": HEIGHT,
                "editable_master_sha256": hashlib.sha256(native_bytes).hexdigest(),
                "style_set": "base", "validation": validation,
                "components": document["__erydon_authoring"]["components"],
                "element_provenance": native["unhandled_root_fields"]["__erydon_authoring"]["element_provenance"],
                "source_hashes": {path: hashlib.sha256((repo / path).read_bytes()).hexdigest() for path in sorted(sources)}}
    image = io.BytesIO()
    preview(document).save(image, format="PNG")
    return {NAME + ".bbmodel": native_bytes, NAME + ".json": initial.encoded(document),
            "non_column_assembly_manifest.json": initial.encoded(manifest),
            "non_column_assembly_preview.png": image.getvalue(), "NON_COLUMN_README.md": README.encode("utf-8")}, validation


def protect_edited_master(directory, desired=None):
    """Allow source refresh only while the editable file still matches export."""
    master = directory / (NAME + ".bbmodel")
    if not master.exists() or (desired is not None and master.read_bytes() == desired):
        return
    manifest = directory / "non_column_assembly_manifest.json"
    previous = initial.load(manifest) if manifest.is_file() else {}
    previous_hash = previous.get("editable_master_sha256")
    current_hash = hashlib.sha256(master.read_bytes()).hexdigest()
    if previous.get("master") != master.name or not previous_hash or previous_hash != current_hash:
        raise SystemExit("The non-column master has changed or has no trusted export hash; refusing to overwrite it. Use python tools/generate_romanesque_arch.py to integrate saved edits.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    directory = ROOT / DESTINATION
    # Refuse an edited master before attempting any source assembly or export.
    protect_edited_master(directory)
    output, validation = output_files(ROOT)
    if args.check:
        changed = [name for name, data in output.items() if not (directory / name).is_file() or (directory / name).read_bytes() != data]
        if changed:
            raise SystemExit("Non-column authoring files differ from the initial export (edited master preserved): " + ", ".join(changed))
    else:
        protect_edited_master(directory, output[NAME + ".bbmodel"])
        directory.mkdir(parents=True, exist_ok=True)
        for name, data in output.items():
            (directory / name).write_bytes(data)
    print(json.dumps(validation, indent=2))


if __name__ == "__main__":
    main()
