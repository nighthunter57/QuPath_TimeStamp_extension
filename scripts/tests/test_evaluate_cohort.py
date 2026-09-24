import json
import tempfile
import unittest
from pathlib import Path
from scripts.evaluate_cohort import evaluate


class CohortTest(unittest.TestCase):
    def test_consent_gate_and_weighted_scores(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "audio.wav").write_bytes(b"test placeholder; evaluator does not decode audio")
            (root / "reference.txt").write_text("no invasion", encoding="utf-8")
            (root / "hypothesis.txt").write_text("invasion", encoding="utf-8")
            item = {"id": "speaker-01", "source": "human", "consent_confirmed": False,
                    "reference_checked": True, "audio": "audio.wav", "reference": "reference.txt",
                    "hypothesis": "hypothesis.txt"}
            manifest = root / "manifest.json"
            manifest.write_text(json.dumps({"version": 1, "recordings": [item]}), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "consent"):
                evaluate(manifest)
            item["consent_confirmed"] = True
            manifest.write_text(json.dumps({"version": 1, "recordings": [item]}), encoding="utf-8")
            report = evaluate(manifest)
            self.assertEqual(50, report["totals"]["raw"]["wer"])
            self.assertIsNone(report["totals"]["medical_concepts"]["error_rate"])
            self.assertNotIn("invasion", json.dumps(report))

    def test_empty_cohort_is_not_perfect_accuracy(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / "manifest.json"
            manifest.write_text('{"version":1,"recordings":[]}', encoding="utf-8")
            with self.assertRaises(ValueError):
                evaluate(manifest)
