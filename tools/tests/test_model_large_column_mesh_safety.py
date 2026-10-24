"""Independent source-volume / generated-shell checks; no third-party Python packages."""
from __future__ import annotations

import hashlib
import json
import math
import sys
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/"tools"))
from model_z_fighting_safety import _parse_vanilla_rotation, _transform_vertex
MESHES=ROOT/"src/main/resources/assets/erydon/meshes/large_columns"
MASTERS=ROOT/"src/main/resources/assets/erydon/models/block/column/circular"
SUFFIXES=("pillar","base","base_narrow","capital","capital_narrow","capital_guilloche")


def sub(a,b):return tuple(x-y for x,y in zip(a,b))
def dot(a,b):return sum(x*y for x,y in zip(a,b))
def cross(a,b):return(a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0])
def norm(a):return math.sqrt(dot(a,a))


def boxes(document):
    result=[]
    for e in document["elements"]:
        r=_parse_vanilla_rotation(e.get("rotation"))
        origin=_transform_vertex((0.,0.,0.),r)
        axes=[sub(_transform_vertex(tuple(float(j==i)for j in range(3)),r),origin)for i in range(3)]
        result.append((e["from"],e["to"],origin,axes))
    return result


def source_envelope(elements,axis,fixed):
    p=list(fixed);p[axis]=0.
    low,high=math.inf,-math.inf
    for lo,hi,origin,axes in elements:
        minimum,maximum=-math.inf,math.inf
        for j,n in enumerate(axes):
            at=dot(sub(p,origin),n);direction=n[axis]
            if abs(direction)<1.e-12:
                if at<lo[j]-1.e-9 or at>hi[j]+1.e-9:break
                continue
            a,b=sorted(((lo[j]-at)/direction,(hi[j]-at)/direction))
            minimum=max(minimum,a);maximum=min(maximum,b)
            if maximum<minimum:break
        else:
            if maximum>=minimum:
                low=min(low,minimum);high=max(high,maximum)
    if high<low:return None
    return(max(0.,low),min(16.,high))


def triangles(faces):
    result=[]
    for f in faces:
        for i in range(1,len(f)-1):result.append((f[0][:3],f[i][:3],f[i+1][:3]))
    return result


def mesh_envelope(mesh,axis,fixed):
    others=[a for a in range(3)if a!=axis]
    x,y=others
    hits=[]
    for a,b,c in mesh:
        if not min(a[x],b[x],c[x])-1.e-8<=fixed[x]<=max(a[x],b[x],c[x])+1.e-8:continue
        if not min(a[y],b[y],c[y])-1.e-8<=fixed[y]<=max(a[y],b[y],c[y])+1.e-8:continue
        det=(b[y]-c[y])*(a[x]-c[x])+(c[x]-b[x])*(a[y]-c[y])
        if abs(det)<1.e-12:continue
        first=((b[y]-c[y])*(fixed[x]-c[x])+(c[x]-b[x])*(fixed[y]-c[y]))/det
        second=((c[y]-a[y])*(fixed[x]-c[x])+(a[x]-c[x])*(fixed[y]-c[y]))/det
        third=1-first-second
        if min(first,second,third)>=-1.e-8:hits.append(first*a[axis]+second*b[axis]+third*c[axis])
    return (min(hits),max(hits))if hits else None


def area2(points):
    return sum(a[0]*b[1]-b[0]*a[1]for a,b in zip(points,points[1:]+points[:1]))/2


def convex_intersection(subject,clipper):
    sign=1 if area2(clipper)>=0 else -1
    for a,b in zip(clipper,clipper[1:]+clipper[:1]):
        if not subject:break
        def side(p):return sign*((b[0]-a[0])*(p[1]-a[1])-(b[1]-a[1])*(p[0]-a[0]))
        result=[];previous=subject[-1];first=side(previous)
        for point in subject:
            second=side(point)
            if(first>=0)!=(second>=0):
                t=first/(first-second)
                result.append(tuple(previous[i]+t*(point[i]-previous[i])for i in range(2)))
            if second>=0:result.append(point)
            previous,first=point,second
        subject=result
    return subject


class LargeColumnMeshSafetyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data={s:json.loads((MESHES/f"{s}.json").read_text())for s in SUFFIXES}
        cls.sources={s:json.loads((MASTERS/cls.data[s]["source_model"]).read_text())for s in SUFFIXES}

    def test_all_six_templates_match_the_untouched_authoritative_masters(self):
        for suffix,data in self.data.items():
            with self.subTest(suffix=suffix):
                self.assertEqual("erydon_large_column_mesh_v1",data["format"])
                source=MASTERS/data["source_model"]
                self.assertEqual("utf8_lf",data["source_hash_format"])
                self.assertEqual(hashlib.sha256(source.read_bytes().replace(b"\r\n",b"\n").replace(b"\r",b"\n")).hexdigest(),data["source_sha256"])
                self.assertEqual(len(self.sources[suffix]["elements"]),data["source_elements"])
                self.assertEqual(sum(len(e["faces"])for e in self.sources[suffix]["elements"]),data["source_faces"])

    def test_seam_folding_is_explicit_and_measured_not_blanket_quantisation(self):
        expected={"pillar":.009,"base":.017,"base_narrow":.017,"capital":.009,"capital_narrow":.017,"capital_guilloche":.009}
        for suffix,data in self.data.items():
            with self.subTest(suffix=suffix):
                self.assertEqual(expected[suffix],data["max_y_seam_fold_model_units"])
                self.assertLessEqual(data["max_contour_error_model_units"],.001)
                self.assertLessEqual(data["max_parallel_plane_weld_model_units"],.001)
                for low,high,plane in data["seam_clusters"]:
                    self.assertLessEqual(max(abs(low-plane),abs(high-plane)),expected[suffix]+1.e-9)
                    self.assertLess(high-low,.025)
                # Reported world distance is two times model distance /16.
                self.assertLessEqual(expected[suffix]*2/16,.002125)

    def test_real_shell_faces_have_finite_unit_normals_and_outward_winding(self):
        for suffix,data in self.data.items():
            seen=set()
            for face in data["faces"]:
                with self.subTest(suffix=suffix,vertex=face[0][:3]):
                    self.assertIn(len(face),(3,4))
                    positions=tuple(tuple(v[:3])for v in face)
                    canonical=tuple(sorted(positions))
                    self.assertNotIn(canonical,seen,"Duplicate overlapping shell polygon")
                    seen.add(canonical)
                    normal=cross(sub(positions[1],positions[0]),sub(positions[2],positions[0]))
                    self.assertGreater(norm(normal),1.e-11)
                    for v in face:
                        self.assertEqual(6,len(v));self.assertTrue(all(math.isfinite(x)for x in v))
                        self.assertAlmostEqual(1.,norm(v[3:]),places=7)
                        self.assertGreater(dot(normal,v[3:]),0.,"Vertex normal reverses the actual surface")

    def test_pilot_preserves_flutes_end_bounds_and_removes_buried_box_surfaces(self):
        data=self.data["pillar"]
        self.assertLess(len(data["faces"]),data["source_faces"])
        self.assertEqual(0,min(v[1]for f in data["faces"]for v in f))
        self.assertEqual(16,max(v[1]for f in data["faces"]for v in f))
        normals={tuple(round(x,4)for x in v[3:])for f in data["faces"]for v in f if abs(v[4])<1.e-8}
        self.assertGreater(len(normals),16,"Curved/fluted surfaces must have genuine smooth normals")
        # The former hidden intersection planes inside a box union must not
        # reappear as arbitrary horizontal slices down the shaft.
        for f in data["faces"]:
            if max(v[1]for v in f)-min(v[1]for v in f)<1.e-9:self.assertIn(f[0][1],(0.,16.))

    def test_byzantine_conservative_strategy_has_no_coplanar_overlap_either_orientation(self):
        data=self.data["capital_guilloche"]
        self.assertEqual("conservative_authored_ornament",data["surface_strategy"])
        self.assertTrue(data["retains_partially_hidden_ornament"])
        for suffix in SUFFIXES[:-1]:
            self.assertEqual("outer_shell",self.data[suffix]["surface_strategy"])
            self.assertFalse(self.data[suffix]["retains_partially_hidden_ornament"])
        groups=[]
        for face in data["faces"]:
            ps=[v[:3]for v in face];n=cross(sub(ps[1],ps[0]),sub(ps[2],ps[0]));n=tuple(v/norm(n)for v in n)
            for value in n:
                if abs(value)>1.e-7:
                    if value<0:n=tuple(-v for v in n)
                    break
            target=None
            for group in groups:
                if max(abs(a-b)for a,b in zip(n,group[0]))>1.e-6:continue
                if any(abs(dot(group[0],p)-group[1])>1.e-7 for p in ps):continue
                target=group;break
            if target is None:
                u=sub(ps[1],ps[0]);u=tuple(x/norm(u)for x in u)
                target=(n,dot(n,ps[0]),u,cross(n,u),[]);groups.append(target)
            polygon=[(dot(p,target[2]),dot(p,target[3]))for p in ps]
            target[4].append(polygon)
        checked=0
        for _,_,_,_,polygons in groups:
            for i,a in enumerate(polygons):
                for b in polygons[i+1:]:
                    if any(max(p[axis]for p in a)<=min(p[axis]for p in b)
                           or max(p[axis]for p in b)<=min(p[axis]for p in a)for axis in (0,1)):continue
                    intersection=convex_intersection(a,b);checked+=1
                    self.assertLessEqual(abs(area2(intersection))if intersection else 0.,1.e-9,
                                         "Byzantine mesh contains overlapping coplanar faces")
        self.assertGreater(checked,100)

    def test_byzantine_rounded_core_is_smooth_and_authored_detail_stays_hard(self):
        rounded=details=caps=0
        for face in self.data["capital_guilloche"]["faces"]:
            ps=[v[:3]for v in face]
            normal=cross(sub(ps[1],ps[0]),sub(ps[2],ps[0]));normal=tuple(x/norm(normal)for x in normal)
            low,high=min(v[1]for v in face),max(v[1]for v in face)
            if abs(normal[1])<1.e-7 and low<.01 and 7.4<high<=7.5+1.e-7:
                if any(dot(normal,v[3:])<.9999 for v in face):rounded+=1
                for vertex in face:
                    self.assertAlmostEqual(0.,vertex[4],places=7)
                    self.assertGreaterEqual(dot(normal,vertex[3:]),math.cos(math.radians(40))-1.e-7)
            if low>=9.:
                for vertex in face:self.assertGreater(dot(normal,vertex[3:]),1-1.e-7,"Smoothing rounded a hard ornament face")
                details+=1
            if abs(normal[1])>1-1.e-7:
                for vertex in face:self.assertGreater(dot(normal,vertex[3:]),1-1.e-7,"A horizontal ledge lost its hard normal")
                caps+=1
        self.assertGreaterEqual(rounded,16,"Rounded Byzantine core is still using blanket flat normals")
        self.assertGreater(details,100)
        self.assertGreater(caps,10)

    def test_independent_axis_rays_preserve_original_visible_profiles(self):
        # Fixed off-grid probes avoid coplanar triangle boundaries and the
        # narrowly documented stagger planes. This reads ORIGINAL boxes, not
        # generator intermediate sections or its Boolean subtraction code.
        checked=0
        for suffix in SUFFIXES:
            source=boxes(self.sources[suffix]);mesh=triangles(self.data[suffix]["faces"])
            for axis in range(3):
                others=[i for i in range(3)if i!=axis]
                for i in range(8):
                    for j in range(8):
                        p=[0.,0.,0.];p[others[0]]=.237+i*2;p[others[1]]=.319+j*2
                        expected=source_envelope(source,axis,p)
                        actual=mesh_envelope(mesh,axis,p)
                        with self.subTest(suffix=suffix,axis=axis,point=p):
                            self.assertEqual(expected is None,actual is None)
                            if expected is None:continue
                            tolerance=.004 if axis!=1 else self.data[suffix]["max_y_seam_fold_model_units"]+.004
                            self.assertAlmostEqual(expected[0],actual[0],delta=tolerance)
                            self.assertAlmostEqual(expected[1],actual[1],delta=tolerance)
                            checked+=1
        self.assertGreater(checked,600)

    def test_volume_oracle_detects_a_missing_real_end_cap(self):
        faces=self.data["pillar"]["faces"]
        removed=[f for f in faces if not all(abs(v[1]-16)<1.e-9 for v in f)]
        point=(8.,0.,8.)
        self.assertAlmostEqual(16.,mesh_envelope(triangles(faces),1,point)[1])
        self.assertNotEqual(16.,mesh_envelope(triangles(removed),1,point)[1])


if __name__=="__main__":unittest.main()
