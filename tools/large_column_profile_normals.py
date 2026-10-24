"""Authoring-only normals from the actual Byzantine neck/collar outer profiles.

The virtual contour joins overlapping authoring rectangles without changing the
rendered polygons. Only neighbouring contour facets within forty degrees share
a normal. Shallow concave flute curves therefore retain their own direction;
flute lips, deep corners, horizontal ledges, and the high ornament stay sharp.
"""
from __future__ import annotations

import math
from dataclasses import dataclass

from shapely.geometry import Polygon
from shapely.geometry.polygon import orient
from shapely.ops import unary_union

from model_z_fighting_safety import _local_face_vertices, _transform_vertex

MATCH = .001001  # the existing source-unit contour/weld budget, never a position edit
CREASE_COS = math.cos(math.radians(40))


def _dot(a, b): return sum(x * y for x, y in zip(a, b))
def _sub(a, b): return tuple(x - y for x, y in zip(a, b))
def _unit(v):
    length = math.sqrt(_dot(v, v))
    return tuple(x / length for x in v)
def _cross2(a, b): return a[0] * b[1] - a[1] * b[0]


@dataclass(frozen=True)
class ProfileEdge:
    a: tuple[float, float]
    b: tuple[float, float]
    normal: tuple[float, float]
    start_normal: tuple[float, float]
    end_normal: tuple[float, float]


def _profile(boxes, indices):
    footprints = []
    for index in indices:
        volume = boxes[index]
        points = [_transform_vertex(p, volume["rotation"])
                  for p in _local_face_vertices(volume["lo"], volume["hi"], "down")]
        footprints.append(Polygon([(p[0], p[2]) for p in points]))
    shape = unary_union(footprints).simplify(.0009, preserve_topology=True)
    polygons = [shape] if shape.geom_type == "Polygon" else list(shape.geoms)
    edges = []
    smooth_vertices = 0
    for polygon in polygons:
        # Exterior includes the actual concave flute openings. Internal authoring
        # cavities are excluded rather than assigning them a radial normal.
        points = list(orient(polygon, sign=1).exterior.coords)[:-1]
        normals = []
        for i, a in enumerate(points):
            b = points[(i + 1) % len(points)]
            dx, dz = b[0] - a[0], b[1] - a[1]
            normals.append(_unit((dz, -dx)))
        shared = []
        for i, normal in enumerate(normals):
            previous = normals[i - 1]
            if _dot(previous, normal) >= CREASE_COS:
                shared.append(_unit(tuple(a + b for a, b in zip(previous, normal))))
                if _dot(previous, normal) < .999999:
                    smooth_vertices += 1
            else:
                shared.append(None)
        for i, a in enumerate(points):
            following = (i + 1) % len(points)
            edges.append(ProfileEdge(a, points[following], normals[i],
                                     shared[i] or normals[i], shared[following] or normals[i]))
    return edges, smooth_vertices


def _face_normal(face):
    a = face[0]
    for i in range(1, len(face) - 1):
        u, v = _sub(face[i][:3], a[:3]), _sub(face[i + 1][:3], a[:3])
        n = (u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0])
        if _dot(n, n) > 1e-22:
            return _unit(n)
    return None


def _endpoint_field(normal, tangent, edge, low, high):
    """Unit endpoint normals whose interpolated direction meets both virtual ends.

    Authoring corners may lie under the adjacent solid, beyond the exposed
    segment. Solving the unit-normal chord preserves the exact hard lip normal
    at that virtual end instead of clamping it or smoothing through a groove.
    Reject fields requiring more than the forty-degree per-facet budget.
    """
    start, end = _dot(tangent, edge.a), _dot(tangent, edge.b)
    if end < start:
        start, end = end, start
        first, second = edge.end_normal, edge.start_normal
    else:
        first, second = edge.start_normal, edge.end_normal
    theta0 = math.atan2(_cross2(normal, first), _dot(normal, first))
    theta1 = math.atan2(_cross2(normal, second), _dot(normal, second))
    if abs(theta0) < 1e-7 and abs(theta1) < 1e-7:
        return None
    x0, x1 = 2 * (start - low) / (high - low) - 1, 2 * (end - low) / (high - low) - 1
    delta = math.tan(theta1 - theta0)
    discriminant = (x1 - x0) ** 2 - 4 * delta * delta * x0 * x1
    if discriminant < 0:
        return None
    denominator = x1 - x0 + math.sqrt(discriminant)
    if denominator <= 1e-12:
        return None
    slope = 2 * delta / denominator
    center = theta0 - math.atan(x0 * slope)
    angles = (center - math.atan(slope), center + math.atan(slope))
    if any(math.cos(angle) < CREASE_COS for angle in angles):
        return None
    return tuple(tuple(normal[i] * math.cos(angle) + tangent[i] * math.sin(angle) for i in range(2)) for angle in angles)


def apply_core_profile_normals(faces, boxes, source_elements):
    """Mutate ONLY six-component corner normal fields; return deterministic stats.

    `boxes` are the generator's seam-folded/welded source volumes. The caller
    invokes this only for capital_guilloche after its geometry is final and
    before sorting/serialization. No module imports the generator itself.
    """
    if len(boxes) < 144 or len(source_elements) != len(boxes):
        raise ValueError("Byzantine core profile groups do not match their source")
    for element in source_elements[:144]:
        rotation = element.get("rotation", {})
        if rotation.get("axis", "y") != "y" and rotation.get("angle", 0) != 0:
            raise ValueError("Byzantine core is no longer a Y-extruded profile")
    neck, neck_vertices = _profile(boxes, range(8, 144))
    collar, collar_vertices = _profile(boxes, range(8))
    stats = dict(neck_profile_smooth_vertices=neck_vertices, collar_profile_smooth_vertices=collar_vertices,
                 neck_faces=0, collar_faces=0, changed_vertices=0, ambiguous_faces=0, rejected_fields=0)
    for face in faces:
        normal3 = _face_normal(face)
        if normal3 is None or abs(normal3[1]) > 1e-7:
            continue
        low_y, high_y = min(v[1] for v in face), max(v[1] for v in face)
        if high_y - low_y < .01:
            continue
        if low_y >= -1e-7 and high_y <= 7.5 + 1e-7 and low_y < 7 - 1e-7:
            profile, group = neck, "neck_faces"
        elif low_y >= 7 - 1e-7 and high_y <= 8 + 1e-7:
            profile, group = collar, "collar_faces"
        else:
            continue
        normal = _unit((normal3[0], normal3[2]))
        tangent = (-normal[1], normal[0])
        plane = _dot(normal, (face[0][0], face[0][2]))
        positions = [_dot(tangent, (v[0], v[2])) for v in face]
        low, high = min(positions), max(positions)
        if high - low < 1e-5:
            continue
        matches = []
        for edge in profile:
            if _dot(normal, edge.normal) < .999999:
                continue
            if max(abs(_dot(normal, p) - plane) for p in (edge.a, edge.b)) > MATCH:
                continue
            a, b = sorted((_dot(tangent, edge.a), _dot(tangent, edge.b)))
            if min(high, b) - max(low, a) > 1e-5:
                matches.append(edge)
        # A single linear field cannot represent disconnected visible pieces
        # belonging to separate flute curves. Keep those authored facets hard.
        if len(matches) != 1:
            if matches:
                stats["ambiguous_faces"] += 1
            continue
        field = _endpoint_field(normal, tangent, matches[0], low, high)
        if field is None:
            stats["rejected_fields"] += 1
            continue
        replacements = []
        for position in positions:
            t = (position - low) / (high - low)
            value = _unit(tuple((1 - t) * a + t * b for a, b in zip(*field)))
            replacements.append([round(value[0], 9), 0.0, round(value[1], 9)])
        if not any(max(abs(a - b) for a, b in zip(vertex[3:], replacement)) > 1e-6
                   for vertex, replacement in zip(face, replacements)):
            continue
        for vertex, replacement in zip(face, replacements):
            if max(abs(a - b) for a, b in zip(vertex[3:], replacement)) > 1e-6:
                stats["changed_vertices"] += 1
            vertex[3:6] = replacement
        stats[group] += 1
    return stats
