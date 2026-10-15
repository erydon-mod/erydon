"""Source geometry, mirror, cell, collision and regeneration contracts."""
from __future__ import annotations

from collections import defaultdict
import hashlib
import json
import math
from pathlib import Path
import sys
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import generate_georgian_arch as generate
import model_z_fighting_safety as geometry

ROOT = Path(__file__).resolve().parents[2]
SOURCE_HASH = 'a83d6d256327ccb90803cbf518bffa88dd44f52ef86e6adc78e2d946afb49980'


def clip_left(points, boundary):
    output = []
    previous = points[-1]
    for current in points:
        a,b = previous[0]<=boundary+1e-9,current[0]<=boundary+1e-9
        if a!=b:
            t = (boundary-previous[0])/(current[0]-previous[0])
            output.append(tuple(previous[j]+t*(current[j]-previous[j]) for j in range(3)))
        if b:output.append(tuple(current))
        previous = current
    return output


def area(points):
    if len(points)<3:return 0
    p0 = points[0]
    total = [0.,0.,0.]
    for i in range(1,len(points)-1):
        a = [points[i][j]-p0[j] for j in range(3)]
        b = [points[i+1][j]-p0[j] for j in range(3)]
        total[0]+=a[1]*b[2]-a[2]*b[1]
        total[1]+=a[2]*b[0]-a[0]*b[2]
        total[2]+=a[0]*b[1]-a[1]*b[0]
    return math.sqrt(sum(x*x for x in total))/2


def plane_areas(polygons):
    result = defaultdict(float)
    for points in polygons:
        size = area(points)
        if size<1e-9:continue
        normal = None
        for i in range(1,len(points)-1):
            a = [points[i][j]-points[0][j] for j in range(3)]
            b = [points[i+1][j]-points[0][j] for j in range(3)]
            n = [a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]]
            length = math.sqrt(sum(x*x for x in n))
            if length>1e-9:
                normal = [x/length for x in n]
                break
        key = tuple(round(x,5) for x in normal)+ (round(sum(normal[j]*points[0][j] for j in range(3)),5),)
        result[key]+=size
    return result


def document_faces(document):
    faces,unsupported = geometry._build_faces(document['elements'],raw_group_rotations={})
    if unsupported:raise AssertionError(unsupported)
    return [face.vertices for face in faces]


def assert_plane_coverage(test, left, right, context):
    """Compare polygon locations and coverage despite different triangle fans."""
    groups=[]
    for polygons in (left,right):
        planes=defaultdict(list)
        for p in polygons:
            keys=plane_areas([p])
            if not keys:continue
            key=next(iter(keys));drop=max(range(3),key=lambda i:abs(key[i]))
            axes=[i for i in range(3) if i!=drop]
            points=[]
            for vertex in p:
                v=tuple(vertex[i] for i in axes)
                if v not in points:points.append(v)
            planes[key].append(points)
        groups.append(planes)

    def edges(p):return zip(p,p[1:]+p[:1])
    def cross(a,b,p):return (b[0]-a[0])*(p[1]-a[1])-(b[1]-a[1])*(p[0]-a[0])
    def boundary(point,polygon):
        return any(abs(cross(a,b,point))<1e-7 and
                   min(a[0],b[0])-1e-7<=point[0]<=max(a[0],b[0])+1e-7 and
                   min(a[1],b[1])-1e-7<=point[1]<=max(a[1],b[1])+1e-7
                   for a,b in edges(polygon))
    def inside(point,polygon):
        signs=[cross(a,b,point) for a,b in edges(polygon)]
        return min(signs)>=-1e-7 or max(signs)<=1e-7

    for key,polygons in groups[0].items():
        counterparts=groups[1][key]
        for polygon in polygons+counterparts:
            center=[sum(p[i] for p in polygon)/len(polygon) for i in range(2)]
            samples=[center]+[[center[i]*.71+p[i]*.29 for i in range(2)] for p in polygon]
            for point in samples:
                # Internal fan diagonals can count in two adjacent triangles.
                # Compare strictly interior probes to keep the test invariant
                # to that harmless triangulation choice.
                if any(boundary(point,p) for p in polygons+counterparts):continue
                test.assertEqual(sum(inside(point,p) for p in polygons),
                                 sum(inside(point,p) for p in counterparts),
                                 f'{context}: plane {key}, probe {point}')


class GeorgianArchSafetyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.models,cls.profiles,cls.project = generate.models_from_master(ROOT)
        cls.refs = {w:json.loads((ROOT/generate.DESIGN/f'arch_georgian_current_{w}wide.json').read_text()) for w in range(1,7)}

    def test_edited_master_is_preserved(self):
        self.assertEqual(SOURCE_HASH,hashlib.sha256((ROOT/generate.MASTER).read_bytes()).hexdigest())
        self.assertEqual(75,len(self.project['elements']))

    def test_threewide_retains_authored_left_and_mirrors_it(self):
        rows,_ = generate.source_rows(ROOT)
        left = [e for role,es in rows.items() if role.endswith('_left') for e in es]
        expected = document_faces({'elements':left})
        expected += [[(48-x,y,z) for x,y,z in reversed(p)] for p in list(expected)]
        for polygon in document_faces({'elements':rows['ROOF__top_center']}):
            part = clip_left(polygon,24)
            if area(part)>1e-9:
                expected.extend([part,[(48-x,y,z) for x,y,z in reversed(part)]])
        a,b = plane_areas(expected),plane_areas(document_faces(self.refs[3]))
        self.assertEqual(set(a),set(b))
        for key in a:self.assertAlmostEqual(a[key],b[key],delta=1e-5)

    def test_all_widths_have_exact_x_mirror_geometry(self):
        for width,doc in self.refs.items():
            polygons = document_faces(doc)
            a = plane_areas(polygons)
            b = plane_areas([[(width*16-x,y,z) for x,y,z in reversed(p)] for p in polygons])
            self.assertEqual(set(a),set(b),width)
            for key in a:self.assertAlmostEqual(a[key],b[key],delta=1e-5,msg=str(width))

    def test_feet_flutes_and_capital_widths_stay_fixed(self):
        self.assertEqual(7,len(self.models['base_left']['elements']))
        self.assertEqual(5,len(self.models['middle_left']['elements']))
        for width in range(1,7):
            world,profile = generate.crown_geometry(generate.source_rows(ROOT)[0],width)
            caps = [e for e in world if e['name']=='outer_jamb_left' and e['from'][0]<3 and e['to'][1]-e['from'][1]<2]
            self.assertEqual(3,len(caps))
            self.assertEqual([2.60064,2.60064,3.10064],sorted(round(e['to'][0]-e['from'][0],5) for e in caps))

    def test_all_templates_use_implicit_uv_and_cached_split_flag(self):
        self.assertEqual(56,len(generate.suffixes()))
        for suffix in generate.suffixes():
            doc=json.loads((ROOT/generate.RAW/f'arch_georgian_{suffix}.json').read_text())
            self.assertTrue(doc['erydon_split_world_uv'])
            for e in doc['elements']:
                for face in e['faces'].values():self.assertNotIn('uv',face)

    def test_short_wide_and_crown_cell_contract(self):
        for width in (4,5,6):
            self.assertEqual(2,self.profiles[str(width)]['crown_rows'])
            self.assertEqual(1,self.profiles[str(width)+'_short']['crown_rows'])
            for column in range(width):
                self.assertEqual((generate.top_suffix(width,0,column,True),),generate.component_selection(width,1,0,column))

    def test_wide_raw_face_area_and_winding_mirror_before_baking(self):
        # Inspect stored vertices directly. A reader that skips degenerate
        # padded triangles must not make a missing mirrored face invisible.
        for width in (4,5,6):
            for short in (False,True):
                for row in range(1 if short else 2):
                    for column in range((width+1)//2):
                        names = [generate.top_suffix(width,row,c,short)
                                 for c in (column,width-1-column)]
                        polygons = []
                        for name in names:
                            doc=json.loads((ROOT/generate.RAW/f'arch_georgian_{name}.json').read_text())
                            pieces=[face['erydon_vertices'] for e in doc['elements'] for face in e['faces'].values()]
                            for p in pieces:
                                self.assertGreater(area(p[:3]),1e-9,name)
                            polygons.append(pieces)
                        left=plane_areas(polygons[0])
                        mirrored=[[(16-x,y,z) for x,y,z in reversed(p)] for p in polygons[1]]
                        right=plane_areas(mirrored)
                        self.assertEqual(set(left),set(right),names)
                        for key in left:self.assertAlmostEqual(left[key],right[key],delta=1e-5,msg=str(names))
                        assert_plane_coverage(self,polygons[0],mirrored,names)

    def test_collision_coordinates_are_quarter_grid_and_finite(self):
        document=json.loads((ROOT/generate.ASSETS/'interaction_shapes/arch_georgian.json').read_text())
        self.assertEqual(set(generate.suffixes())-{'icon'},set(document['components']))
        for boxes in document['components'].values():
            for box in boxes:
                self.assertTrue(all(math.isfinite(x) and abs(x*4-round(x*4))<1e-9 for x in box))
                self.assertTrue(all(box[a]<box[a+3] for a in range(3)))

    def test_deterministic_outputs_match_saved_master(self):
        for path,body in generate.generate(ROOT).items():self.assertEqual(body,(ROOT/path).read_bytes(),str(path))


if __name__=='__main__':unittest.main()
