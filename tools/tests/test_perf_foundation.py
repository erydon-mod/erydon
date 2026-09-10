import importlib.util
import hashlib
import json
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('perf_foundation', Path(__file__).resolve().parents[1] / 'perf_foundation.py')
perf = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(perf)


class FoundationRunnerTests(unittest.TestCase):
    def test_control_results_cannot_be_accepted_as_performance(self):
        status = dict(state='INVALID', pause_menu_test_passed=True, performance_measurement=False,
                      intervals=10, dropped=0)
        self.assertTrue(perf.successful_completion(status, pause_self_test=True))
        self.assertFalse(perf.successful_completion(status))
        status['state'] = 'COMPLETE'
        self.assertFalse(perf.successful_completion(status))
        self.assertFalse(perf.successful_completion(status, pause_self_test=True))

    def test_control_failure_or_empty_capture_does_not_pass(self):
        for changes in [dict(pause_menu_test_passed=False), dict(intervals=0), dict(dropped=1)]:
            status = dict(state='INVALID', pause_menu_test_passed=True, performance_measurement=False,
                          intervals=10, dropped=0)
            status.update(changes)
            self.assertFalse(perf.successful_completion(status, pause_self_test=True))

    def test_timestamp_proof_reproduces_both_original_hashes(self):
        raw = b'#Iris options\r\n#Sat Sep 05 00:00:01 BST 2026\r\nenableShaders=true\r\n'
        other = raw.replace(b'00:00:01', b'00:00:02')
        hashes = [hashlib.sha256(value).hexdigest() for value in [raw, other]]
        proof = perf.prove_launch_timestamp(raw, hashes)
        self.assertEqual(set(proof['matched_sha256_to_time']), set(hashes))

    def test_timestamp_proof_rejects_real_setting_change(self):
        raw = b'#Iris options\n#Sat Sep 05 00:00:01 BST 2026\nenableShaders=true\n'
        changed = raw.replace(b'true', b'false')
        with self.assertRaises(ValueError):
            perf.prove_launch_timestamp(raw, [hashlib.sha256(changed).hexdigest()])

    def test_timestamp_proof_rejects_unknown_or_empty_header(self):
        for raw in [b'', b'#Iris options\n#unknown date\nenableShaders=true\n']:
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                perf.prove_launch_timestamp(raw, [hashlib.sha256(raw).hexdigest()])

    def test_timestamp_exception_never_applies_to_other_files(self):
        with self.assertRaises(ValueError):
            perf.verify_launch_timestamp_differences(
                [{'settings': {'options.txt': 'a'}}, {'settings': {'options.txt': 'b'}}], Path('.'))

    def fixture(self, root, state='COMPLETE', cpu='test CPU'):
        capture = root / 'capture'
        capture.mkdir(parents=True)
        (capture / 'capture-status.json').write_text(json.dumps({'state': state, 'dropped': 0}))
        environment = dict(cpu=cpu, gpu='GPU', driver='driver', power_mode='fixed', source_sha256='test hash')
        for name in ['runtime-before.json', 'runtime-after.json']:
            (capture / name).write_text(json.dumps(environment))
        (capture / 'frames.csv').write_text('frame_ms\n10\n20\n')

    def comparison(self, state='COMPLETE', cpu='test CPU'):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root / 'A')
            self.fixture(root / 'B', state, cpu)
            args = types.SimpleNamespace(repair_tools=root, first=root/'A', second=root/'B', out=root/'pair.json')
            summariser = types.SimpleNamespace(load_frames=lambda _: [10., 20.], summarise=lambda _: {}, compare_runs=lambda a, b: {})
            with patch.object(perf.importlib, 'import_module', return_value=summariser):
                perf.compare(args)
            result = json.loads(args.out.read_text())
            self.assertIsNone(result['optimisation_gain'])
            self.assertFalse(result['visual_approval'])

    def test_aa_smoke_never_claims_optimisation(self):
        self.comparison()

    def test_interrupted_capture_rejected(self):
        with self.assertRaises(ValueError): self.comparison(state='INVALID')

    def test_hardware_difference_rejected(self):
        with self.assertRaises(ValueError): self.comparison(cpu='different CPU')

    def test_unknown_environment_rejected(self):
        with self.assertRaises(ValueError): self.comparison(cpu='UNKNOWN')

    def test_no_output_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'x.json'
            perf.save(path, {})
            with self.assertRaises(FileExistsError): perf.save(path, {})


if __name__ == '__main__': unittest.main()
