from __future__ import annotations

from collections import Counter
from dataclasses import replace
import copy
import hashlib
import itertools
import json
import math
from pathlib import Path
import re
import sys
import unittest

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import generate_grand_coping as grand
import model_z_fighting_safety as scanner


# Only the original twelve authored elements qualify; the 32 added corner
# triangles must never acquire an overlap exception from this contract.
BANDS = {
    0: {4: "edge_east", 5: "edge_west", 6: "edge_south", 7: "edge_north"},
    2: {8: "edge_north", 9: "edge_south", 10: "edge_east", 11: "edge_west"},
}
EXPECTED_PAIRS = {
    (band, face, bevel, face)
    for band, bevels in BANDS.items()
    for bevel, name in bevels.items()
    for face in (("north", "south") if name in ("edge_east", "edge_west") else ("east", "west"))
}


def pair_key(finding):
    return (finding["a"]["element"], finding["a"]["face"],
            finding["b"]["element"], finding["b"]["face"])


def method_body(source, signature):
    start = source.index("{", source.index(signature))
    depth = 1
    for index in range(start + 1, len(source)):
        depth += (source[index] == "{") - (source[index] == "}")
        if depth == 0:
            return re.sub(r"\s+", "", source[start + 1:index])
    raise AssertionError("Unclosed production method " + signature)


class GrandCopingOverlapSafetyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.master = json.loads(grand.MASTER.read_bytes())
        cls.models = {}
        cls.faces = {}
        cls.bulk = {}
        cls.direct = {}
        for profile in grand.PROFILES:
            path = grand.RAW / f"{grand.SUFFIX}_{profile}.json"
            payload = path.read_bytes()
            document = json.loads(payload)
            relative = scanner.RAW_CANONICAL_BLOCK_PREFIX + "coping/georgian_grand/" + path.name
            sha = hashlib.sha256(payload).hexdigest()
            groups, _ = scanner._raw_group_state(document, len(document["elements"]))
            faces, unsupported = scanner._build_faces(document["elements"], raw_group_rotations=groups)
            if unsupported:
                raise AssertionError((profile, unsupported))
            cls.models[profile] = document
            cls.faces[profile] = {(face.element_index, face.face_name): face for face in faces}
            cls.bulk[profile] = scanner.scan_model_document(
                document, relative_path=relative, source_sha256=sha,
                raw_authoring=True, enable_raw_transforms=True,
            )
            # Independently inspect every pair so broad-phase omissions cannot
            # hide a new conflicting corner or invalidate the clipping proof.
            cls.direct[profile] = [
                finding
                for first, second in itertools.combinations(faces, 2)
                if (finding := scanner._inspect_pair(first, second, relative, sha, True)) is not None
            ]

    def test_only_exact_inherited_authored_caps_have_the_reviewed_overlap(self):
        flat = self.models["flat"]
        original = copy.deepcopy(flat["elements"][:12])
        for source, generated in zip(self.master["elements"], original):
            generated["name"] = source["name"]
            generated.pop("erydon_coping_band", None)
        self.assertEqual(self.master["elements"], original)
        self.assertEqual(12, len(original))
        for profile, findings in self.direct.items():
            with self.subTest(profile=profile):
                self.assertEqual(16, len(findings))
                self.assertEqual(EXPECTED_PAIRS, {pair_key(finding) for finding in findings})
                self.assertEqual(Counter({0: 8, 2: 8}), Counter(f["a"]["element"] for f in findings))
                for finding in findings:
                    self.assertEqual(("same", "exact", "partial", "REPORT_ONLY"),
                                     tuple(finding[key] for key in ("orientation", "plane_quality", "overlap_class", "repair_classification")))
                    self.assertEqual("overlap is not a literal identical-render duplicate", finding["reason"])
                    self.assertIsNone(finding["proposed_action"])
                    band, face, bevel, _ = pair_key(finding)
                    elements = self.models[profile]["elements"]
                    self.assertEqual("upper_band" if band == 0 else "lower_band", elements[band]["name"])
                    self.assertEqual(elements[band]["name"], elements[bevel]["erydon_coping_band"])
                    self.assertEqual(BANDS[band][bevel], elements[bevel]["name"])
                    for index in (band, bevel):
                        metadata = elements[index]["faces"][face]
                        self.assertEqual(self.master["elements"][index]["faces"][face], metadata)
                        self.assertEqual("#stone", metadata["texture"])
                        self.assertNotIn("cullface", metadata)
                        self.assertNotIn("rotation", metadata)
                    # Cropped caps retain different authored UV mappings; these
                    # are not literal duplicate faces eligible for deletion.
                    self.assertNotEqual(elements[band]["faces"][face]["uv"], elements[bevel]["faces"][face]["uv"])
                    expected_area = 1.3340205535549856 if band == 0 else 1.3340043177390386
                    self.assertAlmostEqual(expected_area, finding["overlap_area"], places=9)

    def test_own_band_clipping_removes_all_96_conflicts_including_standalone(self):
        self.check_production_clipping_contract()
        checked = 0
        for profile, findings in self.direct.items():
            for finding in findings:
                band, band_face, bevel, bevel_face = pair_key(finding)
                with self.subTest(profile=profile, pair=pair_key(finding)):
                    face = self.faces[profile][(band, band_face)]
                    cap = self.faces[profile][(bevel, bevel_face)]
                    bottom = min(self.normal_height(vertex, profile)
                                 for (index, _), surface in self.faces[profile].items() if index == band
                                 for vertex in surface.vertices)
                    clipped = self.clip_below_band(cap.vertices, bottom, profile)
                    # A fully covered cap can disappear; an exposed lower
                    # remnant would remain below its own band's side face.
                    self.assertTrue(all(self.normal_height(vertex, profile) <= bottom + 1e-10 for vertex in clipped))
                    remaining = scanner._overlap_area(face, replace(cap, vertices=clipped)) if len(clipped) >= 3 else 0
                    self.assertLessEqual(remaining, 1e-8)
                    checked += 1
        self.assertEqual(96, checked)

    def test_bulk_counts_match_every_direct_pair_without_near_or_cull_entries(self):
        exact_same = 0
        for profile, audit in self.bulk.items():
            with self.subTest(profile=profile):
                findings = audit["findings"]
                self.assertEqual(EXPECTED_PAIRS, {pair_key(finding) for finding in findings})
                self.assertEqual(16, len(findings))
                self.assertFalse([finding for finding in findings if finding["plane_quality"] == "near"])
                self.assertEqual(0, len(scanner._raw_cull_boundary_state(self.models[profile])))
                exact_same += sum(finding["orientation"] == "same" and finding["plane_quality"] == "exact"
                                  for finding in findings)
        self.assertEqual(96, exact_same)

    def test_broad_phase_preserves_overlap_across_rounded_45_degree_normal_ties(self):
        first = self.faces["slope"][(2, "east")]
        second = self.faces["slope"][(9, "east")]
        diagonal = math.sqrt(.5)
        normals = (
            (math.nextafter(diagonal, math.inf), -diagonal, 0.0),
            (diagonal, -math.nextafter(diagonal, math.inf), -1e-15),
        )
        for first_normal, second_normal in itertools.product(normals, repeat=2):
            pair = tuple(
                replace(face, normal=normal, plane_d=scanner._dot(normal, face.vertices[0]))
                for face, normal in ((first, first_normal), (second, second_normal))
            )
            for ordered in (pair, pair[::-1]):
                with self.subTest(normals=(first_normal, second_normal), first=ordered[0].sequence):
                    direct = scanner._inspect_pair(*ordered, "test.json", "0" * 64, True)
                    self.assertIsNotNone(direct)
                    candidates = list(scanner._broad_phase_pairs(ordered))
                    self.assertEqual(1, len(candidates))
                    self.assertEqual(direct, scanner._inspect_pair(*candidates[0], "test.json", "0" * 64, True))

    @staticmethod
    def normal_height(vertex, profile):
        start, end, high, low = grand.PROFILES[profile]
        gradient = (high - low) / (end - start)
        # Scanner positions use model units; Java's normalHeight uses cells.
        return (vertex[1] - (16 * high - gradient * (vertex[0] - 16 * start))) / math.sqrt(1 + gradient * gradient)

    @classmethod
    def clip_below_band(cls, vertices, bottom, profile):
        result = []
        first = vertices[-1]
        distance_a = cls.normal_height(first, profile) - bottom
        for second in vertices:
            distance_b = cls.normal_height(second, profile) - bottom
            if (distance_a <= 0) != (distance_b <= 0):
                amount = distance_a / (distance_a - distance_b)
                result.append(tuple(a + (b - a) * amount for a, b in zip(first, second)))
            if distance_b <= 0:
                result.append(second)
            first, distance_a = second, distance_b
        return tuple(result)

    def check_production_clipping_contract(self):
        path = grand.ROOT / "src/main/java/com/oliver/erydon/client/model/CopingGeometry.java"
        source = path.read_text(encoding="utf-8")
        exposed = method_body(source, "private List<Vertex> exposedBandEnd(")
        self.assertIn("bands.get(source.band)", exposed)
        self.assertIn("normalHeight(v,profile)", exposed)
        self.assertIn("returnclipBandEnd(source.vertices,profile,bottom);", exposed)
        joined = method_body(source, "List<Surface> joined(CopingBlock.Surface profile,CopingConnections.Joins joins)")
        # With mask zero, no edge is omitted and the same unconditional loop
        # still clips band ends. The proof therefore includes isolated coping.
        self.assertIn("for(Surfacesource:surfaces)", joined)
        self.assertIn("if((source.edge&mask)!=0)continue;", joined)
        self.assertIn("if(source.bandEnd)vertices=exposedBandEnd(source,profile);", joined)
        self.assertNotIn("if(mask==0)return", joined)
        horizontal = method_body(source, "private List<Surface> joinedHorizontal(")
        self.assertIn("List<Vertex>polygon=exposedBandEnd(source,profile);", horizontal)
        clipping = method_body(source, "private static List<Vertex> clipBandEnd(")
        self.assertIn("normalHeight(a,profile)", clipping)
        self.assertIn("normalHeight(b,profile)", clipping)
        self.assertIn("if(db<=0)output.add(b);", clipping)
        height = method_body(source, "static double normalHeight(")
        self.assertIn("return(vertex.y-profile.height(vertex.x))/Math.sqrt(1+profile.gradient()*profile.gradient());", height)
        parse = method_body(source, "static CopingGeometry parse(")
        self.assertIn('element.has("erydon_coping_band")', parse)
        self.assertIn("edge==CopingConnections.WEST||edge==CopingConnections.EAST", parse)
        self.assertIn("edge==CopingConnections.NORTH||edge==CopingConnections.SOUTH", parse)
        mesh = method_body(source, "Mesh mesh(Sprite sprite,")
        self.assertIn("for(Surfacesurface:joined(profile,joins))", mesh)

        baked_path = path.with_name("CopingBakedModel.java")
        baked = baked_path.read_text(encoding="utf-8")
        load = method_body(baked, "public Mesh load(MeshKey key)")
        self.assertIn("geometry.get(key.surface).mesh(getParticleSprite(),key.surface,key.joins,key.offset)", load)
        placed = method_body(baked, "public void emitBlockQuads(")
        self.assertIn("Meshsource=mesh(surface,CopingConnections.resolve(view,state,pos,facing),state.get(CopingBlock.OFFSET));", placed)
        item = method_body(baked, "public void emitItemQuads(")
        self.assertIn("mesh(CopingBlock.Surface.FLAT,newCopingConnections.Joins(0),false).outputTo(context.getEmitter());", item)
        preview = method_body(baked, "public List<BakedQuad> getQuads(")
        self.assertIn("mesh(surface,newCopingConnections.Joins(0),state!=null&&state.get(CopingBlock.OFFSET)).forEach(", preview)


if __name__ == "__main__":
    unittest.main()
