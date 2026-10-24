"""Build exposed large-column shells from the owned circular-column masters.

Authoring-only dependency: Shapely 2.1.2 (GEOS constrained triangulation).
Minecraft reads the generated meshes; it does not run CSG or use Shapely.
The small-column models are inputs and are never rewritten.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from collections import defaultdict
from pathlib import Path

from shapely import constrained_delaunay_triangles, make_valid, set_precision
from shapely.geometry import Polygon
from shapely.geometry.polygon import orient
from shapely.ops import unary_union

from model_z_fighting_safety import _local_face_vertices, _parse_vanilla_rotation, _transform_vertex
from large_column_profile_normals import apply_core_profile_normals

ROOT = Path(__file__).resolve().parents[1]
MODELS = ROOT / "src/main/resources/assets/erydon/models/block/column/circular"
OUTPUT = ROOT / "src/main/resources/assets/erydon/meshes/large_columns"
SUFFIXES = ("pillar", "base", "base_narrow", "capital", "capital_narrow", "capital_guilloche")
# Explicit, measured overlap-only Y stagger clusters. No blanket quantisation:
# in particular the Byzantine ornament's genuine sub-half-unit ledges survive.
SEAMS = {
    "pillar": ((0, .009, 0), (15.993, 16, 16)),
    "base": ((2.493, 2.509, 2.5), (4.993, 5.017, 5), (5.493, 5.5, 5.5), (5.993, 6, 6), (15.993, 16, 16)),
    "base_narrow": ((0, .007, 0), (.493, .5, .5), (1.993, 2.009, 2), (4.993, 5.017, 5), (5.493, 5.5, 5.5), (5.993, 6, 6), (15.993, 16, 16)),
    "capital": ((0, .009, 0), (10, 10.007, 10), (10.5, 10.507, 10.5), (10.993, 11, 11), (13.5, 13.507, 13.5), (15.493, 15.5, 15.5)),
    "capital_narrow": ((0, .007, 0), (10, 10.007, 10), (10.5, 10.507, 10.5), (10.983, 11.007, 11), (13.99, 14.007, 14), (15.5, 15.507, 15.5), (15.993, 16, 16)),
    "capital_guilloche": ((0, .009, 0), (7, 7.007, 7)),
}
EPS = 1.e-8
OUTSIDE = 1.e-6
SIMPLIFY = .0009  # source model units, strictly less than the agreed .001 X/Z budget
SMOOTH_COS = math.cos(math.radians(40))


def sub(a, b): return tuple(x-y for x, y in zip(a, b))
def add(a, b): return tuple(x+y for x, y in zip(a, b))
def mul(a, k): return tuple(x*k for x in a)
def dot(a, b): return sum(x*y for x, y in zip(a, b))
def cross(a, b): return (a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0])
def length(a): return math.sqrt(dot(a, a))
def unit(a): return mul(a, 1/length(a))
def key(a): return tuple(round(x, 7) for x in a)


def source_boxes(suffix):
    source = MODELS / f"column_circular_{suffix}.json"
    document = json.loads(source.read_text(encoding="utf-8-sig"))
    boxes, max_delta = [], 0.
    for element in document["elements"]:
        lo, hi = [list(element[k]) for k in ("from", "to")]
        # Only vertical prisms have Y coordinates representing these staggered
        # levels. Rotated X/Z ornament coordinates have a different meaning.
        if element.get("rotation", {}).get("axis", "y") == "y":
            for endpoint in (lo, hi):
                for low, high, plane in SEAMS[suffix]:
                    if low-EPS <= endpoint[1] <= high+EPS:
                        max_delta = max(max_delta, abs(endpoint[1]-plane))
                        endpoint[1] = plane
                        break
        rotation = _parse_vanilla_rotation(element.get("rotation"))
        origin = _transform_vertex((0., 0., 0.), rotation)
        axes = tuple(sub(_transform_vertex(tuple(float(j == i) for j in range(3)), rotation), origin) for i in range(3))
        corners = [_transform_vertex(v, rotation) for face in ("down", "up") for v in _local_face_vertices(tuple(lo), tuple(hi), face)]
        bounds = tuple(tuple(f(v[i] for v in corners) for i in range(3)) for f in (min, max))
        boxes.append(dict(lo=tuple(lo), hi=tuple(hi), rotation=rotation, origin=origin, axes=axes, bounds=bounds))
    return source, document, boxes, max_delta


def weld_parallel_seams(boxes):
    """Join near-identical box planes within a <=.001 source-unit budget.

    Same-direction staggered faces otherwise manufacture tiny exposed strips
    during Boolean subtraction. Distinct directions and larger feature steps
    are never grouped. A cluster's total range is bounded, not chained.
    """
    planes=defaultdict(list)
    for i,b in enumerate(boxes):
        for axis,n in enumerate(b["axes"]):
            for endpoint in ("lo","hi"):
                value=dot(n,b["origin"])+b[endpoint][axis]
                planes[key(n)].append((value,i,endpoint,axis))
    maximum=0.
    for group in planes.values():
        values=sorted(group); clusters=[]
        for item in values:
            if not clusters or item[0]-clusters[-1][0][0]>.001999:clusters.append([item])
            else:clusters[-1].append(item)
        for cluster in clusters:
            if len(cluster)<3:continue
            low,high=cluster[0][0],cluster[-1][0]
            center=(low+high)/2
            if low<=0<=high and high-low<=.001: center=0.
            if low<=16<=high and high-low<=.001:center=16.
            for value,i,endpoint,axis in cluster:
                maximum=max(maximum,abs(value-center))
                b=boxes[i]; coords=list(b[endpoint]); coords[axis]+=center-value;b[endpoint]=tuple(coords)
    for b in boxes:
        corners=[_transform_vertex(v,b["rotation"])for face in ("down","up")for v in _local_face_vertices(b["lo"],b["hi"],face)]
        b["bounds"]=tuple(tuple(f(v[i]for v in corners)for i in range(3))for f in (min,max))
    return maximum


def clip(polygon, normal, bound):
    """Inside half-plane dot(vertex, normal) <= bound, on the existing face."""
    if not polygon: return []
    output = []
    previous = polygon[-1]
    before = dot(previous, normal)-bound
    for vertex in polygon:
        after = dot(vertex, normal)-bound
        if (before <= EPS) != (after <= EPS):
            t = before/(before-after)
            output.append(add(previous, mul(sub(vertex, previous), t)))
        if after <= EPS: output.append(vertex)
        previous, before = vertex, after
    return output


def occluded(polygon, normal, box):
    # Test an infinitesimal point outside the face. Coincident same-facing
    # outer faces survive here and are merged exactly once by planar union.
    result = polygon
    for axis, low, high in zip(box["axes"], box["lo"], box["hi"]):
        origin = dot(axis, box["origin"])
        offset = OUTSIDE*dot(axis, normal)
        result = clip(result, axis, origin+high-offset)
        result = clip(result, mul(axis, -1), -origin-low+offset)
        if len(result) < 3: return []
    return result


def polygons(value):
    if value.is_empty: return []
    if value.geom_type == "Polygon": return [value]
    return [p for p in value.geoms if p.geom_type == "Polygon" and not p.is_empty]


def quadrangulate(shape):
    triangles = [p for p in constrained_delaunay_triangles(shape).geoms if p.area > 1.e-12]
    edge_map = defaultdict(list)
    for index, triangle in enumerate(triangles):
        points = list(triangle.exterior.coords)[:-1]
        for a, b in zip(points, points[1:]+points[:1]): edge_map[tuple(sorted((key(a), key(b))))].append(index)
    pairs = []
    for edge, values in edge_map.items():
        if len(values) == 2: pairs.append((-math.dist(*edge), values[0], values[1]))
    used, result = set(), []
    for _, a, b in sorted(pairs):
        if a in used or b in used: continue
        merged = triangles[a].union(triangles[b])
        if merged.geom_type!="Polygon":continue
        coords = list(merged.exterior.coords)[:-1]
        if len(coords) == 4 and abs(merged.convex_hull.area-merged.area) < 1.e-10:
            result.append(coords); used.update((a,b))
    result.extend(list(p.exterior.coords)[:-1] for i,p in enumerate(triangles) if i not in used)
    return result


def prism_faces(boxes,axis=1):
    """Sweep the union of vertical cross sections, never the buried boxes.

    Unlike per-face CSG this joins the whole contour before tessellation, so
    microscopic intersections cannot fragment a long flute into many strips.
    """
    permutation={0:(2,0,1),1:(0,1,2),2:(1,2,0)}[axis]
    def restore(p):
        output=[0.,0.,0.]
        for i,a in enumerate(permutation):output[a]=p[i]
        return tuple(output)
    levels=sorted({b[k][axis] for b in boxes for k in ("lo","hi")})
    footprints=[]
    for b in boxes:
        ps=[_transform_vertex(v,b["rotation"]) for v in _local_face_vertices(b["lo"],b["hi"],("west","down","north")[axis])]
        footprints.append(Polygon([(p[permutation[0]],p[permutation[2]]) for p in ps]))
    sections=[]; sides=defaultdict(list); faces=[]
    for low,high in zip(levels,levels[1:]):
        middle=(low+high)/2
        shape=unary_union([p for p,b in zip(footprints,boxes) if b["lo"][axis]<middle<b["hi"][axis]])
        simplified=shape.simplify(SIMPLIFY,preserve_topology=True)
        if shape.hausdorff_distance(simplified)>SIMPLIFY+EPS:raise ValueError("Profile escaped its X/Z budget")
        # The source is a solid stone column assembled from overlapping strips.
        # Enclosed gaps BETWEEN those strips are internal authoring voids, not
        # exterior flute openings. Generate the outer shell, never new cavity
        # walls. This does not move any vertex of the exterior profile.
        simplified=unary_union([Polygon(p.exterior,[r.coords for r in p.interiors if axis!=1 or Polygon(r).area>.001]) for p in polygons(simplified)])
        sections.append(simplified)
        for polygon in polygons(simplified):
            polygon=orient(polygon,sign=1.)
            for ring in (polygon.exterior,*polygon.interiors):
                points=list(ring.coords)[:-1]
                for a,b in zip(points,points[1:]+points[:1]):
                    sides[(key(a),key(b))].append((low,high,a,b))
    for _, intervals in sorted(sides.items()):
        merged=[]
        for low,high,a,b in intervals:
            if merged and abs(low-merged[-1][1])<EPS:merged[-1]=(merged[-1][0],high,a,b)
            else:merged.append((low,high,a,b))
        for low,high,a,b in merged:
            points=[(a[0],low,a[1]),(a[0],high,a[1]),(b[0],high,b[1]),(b[0],low,b[1])]
            faces.append(dict(points=[restore(p)for p in points],normal=restore(unit(cross(sub(points[1],points[0]),sub(points[2],points[0]))))))
    for i,y in enumerate(levels):
        below=sections[i-1] if i else Polygon()
        above=sections[i] if i<len(sections) else Polygon()
        for shape,normal in ((below.difference(above),(0.,1.,0.)),(above.difference(below),(0.,-1.,0.))):
            for polygon in polygons(shape):
                for coords in quadrangulate(polygon):
                    points=[(x,y,z)for x,z in coords]
                    if dot(cross(sub(points[1],points[0]),sub(points[2],points[0])),normal)<0:points.reverse()
                    faces.append(dict(points=[restore(p)for p in points],normal=restore(normal)))
    return faces


def authored_inputs(document,boxes):
    """Conservative Byzantine detail: discard whole buried faces, not every
    hidden portion of an otherwise visible opaque ornamental band.

    Cutting every interlocking band intersection doubles GPU primitives.
    Coplanar unions and opposite-interface cancellation remain exact.
    """
    grouped={index:[box]for index,box in enumerate(boxes)}
    inputs=[]
    for index,(element,box)in enumerate(zip(document["elements"],boxes)):
        for direction in element["faces"]:
            points=[_transform_vertex(p,box["rotation"])for p in _local_face_vertices(box["lo"],box["hi"],direction)]
            normal=unit(cross(sub(points[1],points[0]),sub(points[2],points[0])))
            inputs.append((index,dict(points=points,normal=normal)))
    return grouped,inputs


def remap_plane(shape,source,target):
    def point(x,y):
        p=add(source["origin"],add(mul(source["u"],x),mul(source["v"],y)))
        return dot(p,target["u"]),dot(p,target["v"])
    return unary_union([Polygon([point(*p)for p in polygon.exterior.coords],
                               [[point(*p)for p in ring.coords]for ring in polygon.interiors])
                        for polygon in polygons(shape)])


def cancel_opposite_planes(planes):
    """Remove coincident internal opposing interfaces without full 3D CSG."""
    values=list(planes.values())
    for i,first in enumerate(values):
        for second in values[i+1:]:
            if max(abs(a+b)for a,b in zip(first["normal"],second["normal"]))>1.e-7:continue
            if abs(dot(first["normal"],sub(second["origin"],first["origin"])))>1.e-7:continue
            a=unary_union(first["shapes"])
            b=remap_plane(unary_union(second["shapes"]),second,first)
            interface=a.intersection(b)
            if interface.area<=1.e-12:continue
            first["shapes"]=polygons(a.difference(interface))
            second["shapes"]=polygons(remap_plane(b.difference(interface),first,second))


def build(suffix):
    source, document, boxes, max_delta = source_boxes(suffix)
    # Only the 3D ornament path needs plane welding: swept profiles already
    # join their whole contour with a smaller, measured simplification budget.
    max_plane_weld=weld_parallel_seams(boxes) if suffix=="capital_guilloche" else 0.
    planes = {}
    source_face_count = sum(len(e.get("faces", {})) for e in document["elements"])
    vertical_only=all(e.get("rotation",{}).get("axis","y")=="y" for e in document["elements"])
    grouped,inputs=({},[])if vertical_only else authored_inputs(document,boxes)
    for axis,face in inputs:
        points=face["points"]; n=face["normal"]
        u=unit(sub(points[1],points[0]));v=cross(n,u);distance=dot(n,points[0])
        basis_origin=mul(n,distance)
        project=lambda p:(dot(p,u),dot(p,v))
        shape=Polygon([project(p)for p in points])
        bounds=tuple(tuple(f(p[a]for p in points)for a in range(3))for f in (min,max))
        covered=[]
        for other_axis,others in grouped.items():
            if other_axis==axis:continue
            for other in others:
                if any(bounds[1][a]<other["bounds"][0][a]-EPS or bounds[0][a]>other["bounds"][1][a]+EPS for a in range(3)):continue
                intersection=occluded(points,n,other)
                if len(intersection)>=3:
                    p=Polygon([project(q)for q in intersection])
                    if p.area>1.e-12:covered.extend(polygons(set_precision(make_valid(p),1.e-8)))
        if covered:
            remainder=set_precision(shape,1.e-8).difference(unary_union(covered))
            if remainder.is_empty or remainder.area<1.e-12:continue
        if shape.is_empty or shape.area<1.e-12:continue
        plane_key=key(n)+(round(distance,7),)
        if plane_key not in planes:planes[plane_key]=dict(normal=n,origin=basis_origin,u=u,v=v,shapes=[])
        plane=planes[plane_key]
        def reproject(x,y):
            p=add(basis_origin,add(mul(u,x),mul(v,y)))
            return(dot(p,plane["u"]),dot(p,plane["v"]))
        for p in polygons(shape):
            plane["shapes"].append(Polygon([reproject(*q)for q in p.exterior.coords],[[reproject(*q)for q in ring.coords]for ring in p.interiors]))
    faces=prism_faces(boxes) if vertical_only else []
    if not vertical_only:cancel_opposite_planes(planes)
    for _, plane in sorted(planes.items()):
        shape=unary_union(plane["shapes"])
        simplified=shape.simplify(SIMPLIFY,preserve_topology=True)
        # Simplification can only remove collinear/sliver seam vertices within
        # its published geometric error budget; genuine holes remain holes.
        if shape.hausdorff_distance(simplified) > SIMPLIFY+EPS: raise ValueError("Contour simplification escaped its budget")
        for p in polygons(simplified):
            for coords in quadrangulate(p):
                points=[add(plane["origin"],add(mul(plane["u"],x),mul(plane["v"],y))) for x,y in coords]
                if dot(cross(sub(points[1],points[0]),sub(points[2],points[0])),plane["normal"])<0:points.reverse()
                faces.append(dict(points=points,normal=plane["normal"]))
    # Angle-weighted normals only join coincident exposed vertices whose face
    # normals differ by <=40 degrees. Profile ledges and flute lips stay hard.
    incident=defaultdict(list)
    for face in faces:
        ps=face["points"]
        for i,p in enumerate(ps):
            a,b=unit(sub(ps[i-1],p)),unit(sub(ps[(i+1)%len(ps)],p))
            angle=math.acos(max(-1.,min(1.,dot(a,b))))
            incident[key(p)].append((face["normal"],angle))
    output=[]
    for face in faces:
        ps=face["points"]
        # Put the largest genuine triangle first. A collinear A-B-C triple
        # would make Iris generate a zero tangent even for an otherwise valid
        # four-corner polygon. Boolean dust below this area has no representable
        # profile extent at the much tighter-than-texel contour budget.
        signed=sum(dot(cross(sub(ps[i],ps[0]),sub(ps[i+1],ps[0])),face["normal"])for i in range(1,len(ps)-1))
        if signed<0:ps=list(reversed(ps))
        start=max(range(len(ps)),key=lambda i:dot(cross(sub(ps[(i+1)%len(ps)],ps[i]),sub(ps[(i+2)%len(ps)],ps[i])),face["normal"]))
        ps=ps[start:]+ps[:start]
        if length(cross(sub(ps[1],ps[0]),sub(ps[2],ps[0])))<=1.e-10:continue
        corners=[]
        for p in ps:
            weighted=(0.,0.,0.)
            for n,weight in incident[key(p)]:
                if dot(n,face["normal"])>=SMOOTH_COS: weighted=add(weighted,mul(n,weight))
            normal=unit(weighted) if length(weighted)>1.e-15 else face["normal"]
            corners.append([*[round(x,12)for x in p],*[round(x,9)for x in normal]])
        output.append(corners)
    core_normals={}
    if suffix=="capital_guilloche":
        positions=[tuple(tuple(vertex[:3])for vertex in face)for face in output]
        core_normals=apply_core_profile_normals(output,boxes,document["elements"])
        if positions!=[tuple(tuple(vertex[:3])for vertex in face)for face in output]:
            raise ValueError("Core normal generation changed geometry or face ordering")
    output.sort(key=lambda face:tuple(value for vertex in face for value in vertex))
    source_bytes=source.read_bytes().replace(b"\r\n",b"\n").replace(b"\r",b"\n")
    result={"format":"erydon_large_column_mesh_v1","surface_strategy":"outer_shell"if vertical_only else"conservative_authored_ornament","retains_partially_hidden_ornament":not vertical_only,"source_model":source.name,"source_hash_format":"utf8_lf","source_sha256":hashlib.sha256(source_bytes).hexdigest(),"source_faces":source_face_count,"source_elements":len(boxes),"max_y_seam_fold_model_units":round(max_delta,9),"max_parallel_plane_weld_model_units":round(max_plane_weld,9),"max_contour_error_model_units":SIMPLIFY,"seam_clusters":[list(v)for v in SEAMS[suffix]],"smooth_angle_degrees":40,"faces":output}
    if core_normals:result["byzantine_core_normals"]=core_normals
    label="outer shell"if vertical_only else"conservative ornament mesh"
    print(f"{suffix}: {source_face_count} authored faces -> {len(output)} {label} faces; {sum(len(f)==3 for f in output)} triangles; Y fold {max_delta:.6f}")
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check",action="store_true")
    parser.add_argument("--only",choices=SUFFIXES)
    args=parser.parse_args()
    for suffix in (args.only,)if args.only else SUFFIXES:
        result=build(suffix)
        content=(json.dumps(result,separators=(",",":"))+"\n").encode()
        path=OUTPUT/f"{suffix}.json"
        if args.check:
            if not path.is_file()or path.read_bytes()!=content:raise SystemExit(f"Stale generated mesh: {path}")
        else:
            path.parent.mkdir(parents=True,exist_ok=True)
            if not path.is_file()or path.read_bytes()!=content:path.write_bytes(content)


if __name__=="__main__": main()
