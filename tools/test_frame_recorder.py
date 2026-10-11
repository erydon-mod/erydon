import json
from pathlib import Path
import tempfile
import unittest

from frame_recorder import queue, summarize


class FrameRecorderCliTest(unittest.TestCase):
    def test_request_publication_is_complete_and_cannot_overwrite(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            result = queue(root, "sample-1")
            self.assertEqual(json.loads(Path(result["queued"]).read_text())["captureSeconds"], 60)
            with self.assertRaises(ValueError):
                queue(root, "sample-1")
            self.assertEqual(len(list(root.rglob("*tmp"))), 0)

    def test_request_rejects_path_escape_and_impossible_timeout(self):
        with tempfile.TemporaryDirectory() as folder:
            for identifier in ("../escape", "a/b", "a\\b", "", "a" * 49, "CON", "lpt1"):
                with self.assertRaises(ValueError):
                    queue(Path(folder), identifier)
            with self.assertRaises(ValueError):
                queue(Path(folder), "safe", duration=60, timeout=10)

    def result(self, folder, state="COMPLETE", intervals=3, frames="0,10\n1,20\n2,3000\n"):
        directory = Path(folder)
        (directory / "status.json").write_text(json.dumps(dict(id="test", state=state, intervals=intervals)))
        (directory / "frames.csv").write_text("frame_index,frame_ms\n" + frames)
        return directory

    def test_summary_keeps_stalls_in_average_and_tail(self):
        with tempfile.TemporaryDirectory() as folder:
            result = summarize(self.result(folder))
            self.assertAlmostEqual(result["averageFps"], 3000 / 3030)
            self.assertEqual(result["longestFrameMs"], 3000)
            self.assertAlmostEqual(result["slowestOnePercentMeanFps"], 1 / 3)

    def test_invalid_capture_and_mismatched_or_corrupt_csv_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            for state in ("INVALID", "WAITING", "CAPTURING", "TIMED_OUT"):
                with self.assertRaises(ValueError):
                    summarize(self.result(folder, state=state))
            with self.assertRaises(ValueError):
                summarize(self.result(folder, intervals=2))
            for frames in ("0,NaN\n1,20\n2,30\n", "0,-1\n1,20\n2,30\n", "0,10\n2,20\n3,30\n"):
                with self.assertRaises(ValueError):
                    summarize(self.result(folder, frames=frames))


if __name__ == "__main__":
    unittest.main()
