import hashlib
import json
import tempfile
import unittest
import wave
from pathlib import Path
from unittest.mock import patch

import numpy as np

from scripts import audit_recording as auditor
from scripts.live_whisper_demo import UNCLEAR_SPEECH_MARKER


class AuditRecordingTest(unittest.TestCase):
    def fixture(self, directory, levels=(0.1, 0.1, 0, 0.1), words=None):
        root = Path(directory)
        audio, text = root / "test_audio.wav", root / "test.txt"
        samples = np.concatenate([np.full(8000, level, dtype=np.float32) for level in levels])
        with wave.open(str(audio), "wb") as output:
            output.setnchannels(1)
            output.setsampwidth(2)
            output.setframerate(16000)
            output.writeframes((samples * 32767).astype("<i2").tobytes())
        text.write_text("[2026-09-17T10:00:00] private phrase\n", encoding="utf-8")
        rows = words if words is not None else [{"word": "private", "start_ms": 0, "end_ms": 500}]
        metadata = root / "test_review.json"
        metadata.write_text(json.dumps({"version": 1, "transcript": text.read_text(encoding="utf-8"), "words": rows}), encoding="utf-8")
        return audio, text, metadata

    def test_gaps_keep_capture_clock_and_do_not_change_inputs_or_leak_text(self):
        with tempfile.TemporaryDirectory() as directory:
            audio, text, metadata = self.fixture(directory)
            original = [path.read_bytes() for path in (audio, text, metadata)]
            report = auditor.audit(audio, text, speech_detector=lambda samples: samples.max() > 0)
            gaps = [row for row in report["review_items"] if row["reason"] == "speech_without_word_timing"]
            self.assertEqual([(620, 1000), (1500, 2000)], [(row["start_ms"], row["end_ms"]) for row in gaps])
            self.assertEqual(1500, report["detected_speech_ms"])
            self.assertEqual(original, [path.read_bytes() for path in (audio, text, metadata)])
            self.assertNotIn("private phrase", json.dumps(report))
            self.assertEqual(hashlib.sha256(original[0]).hexdigest(), report["audio_sha256"])

    def test_silence_is_not_claimed_as_a_gap_or_perfect_accuracy(self):
        with tempfile.TemporaryDirectory() as directory:
            audio, text, _ = self.fixture(directory, levels=(0, 0), words=[])
            report = auditor.audit(audio, text, speech_detector=lambda samples: False)
            self.assertEqual([], report["review_items"])
            self.assertIn("does not prove", auditor.render_report(report, audio))
            self.assertNotIn("accuracy_percent", report)

    def test_quiet_speech_is_a_candidate_even_below_live_rms_gate(self):
        with tempfile.TemporaryDirectory() as directory:
            audio, text, _ = self.fixture(directory, levels=(0.001,), words=[])
            report = auditor.audit(audio, text, speech_detector=lambda samples: True)
            self.assertEqual({"quiet_detected_speech", "speech_without_word_timing"},
                             {row["reason"] for row in report["review_items"]})

    def test_unclear_markers_do_not_count_as_recognized_words(self):
        with tempfile.TemporaryDirectory() as directory:
            audio, text, _ = self.fixture(directory, levels=(0.1,), words=[
                {"word": UNCLEAR_SPEECH_MARKER, "start_ms": 0, "end_ms": 500}])
            report = auditor.audit(audio, text, speech_detector=lambda samples: True)
            self.assertEqual({"unclear_speech_marker", "speech_without_word_timing"},
                             {row["reason"] for row in report["review_items"]})

    def test_clipping_is_reported_even_if_speech_detector_rejects_audio(self):
        with tempfile.TemporaryDirectory() as directory:
            audio, text, _ = self.fixture(directory, levels=(1,), words=[])
            report = auditor.audit(audio, text, speech_detector=lambda samples: False)
            self.assertEqual(["clipped_audio"], [row["reason"] for row in report["review_items"]])

    def test_stale_metadata_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            audio, text, _ = self.fixture(directory)
            text.write_text("edited transcript", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "stale"):
                auditor.audit(audio, text)

    def test_bad_or_out_of_bounds_timings_are_rejected(self):
        for start, end in [(None, 1), (False, 500), (-1, 100), (500, 400), (0, 9000), (0, float("nan"))]:
            with self.subTest(start=start, end=end), tempfile.TemporaryDirectory() as directory:
                audio, text, _ = self.fixture(directory, words=[{"word": "test", "start_ms": start, "end_ms": end}])
                with self.assertRaises(ValueError):
                    auditor.audit(audio, text)

    def test_interval_subtraction_handles_overlap_and_short_internal_gap(self):
        self.assertEqual([(0.2, 0.4), (0.8, 1.0)],
                         auditor.uncovered_ranges([(0, 1)], [(0, .2), (.4, .7), (.6, .8)]))
        self.assertEqual([], auditor.uncovered_ranges([(0, 1)], [(0, 2)]))
        self.assertEqual([(0, 1)], auditor.uncovered_ranges([(0, .5), (.5, 1)], []))

    def test_report_escapes_paths_and_does_not_embed_audio_or_transcript(self):
        with tempfile.TemporaryDirectory() as directory:
            audio, text, _ = self.fixture(directory)
            report = auditor.audit(audio, text, speech_detector=lambda samples: True)
            rendered = auditor.render_report(report, Path(directory) / '"<script>.wav')
            self.assertNotIn('src="file://"<script>', rendered)
            self.assertIn("%3Cscript%3E", rendered)
            self.assertNotIn("private phrase", rendered)
            self.assertNotIn("data:audio", rendered)
            self.assertIn("Replay with context", rendered)

    def test_changed_recording_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            audio, text, metadata = self.fixture(directory)
            def detector(samples):
                text.write_text("changed while recording", encoding="utf-8")
                return True
            with self.assertRaisesRegex(ValueError, "changed"):
                auditor.audit(audio, text, speech_detector=detector)

    def test_cli_refuses_existing_output_without_running_audit(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(auditor, "audit") as audit:
            with patch("sys.argv", ["audit", "--audio", "x.wav", "--transcript", "x.txt", "--output", directory]):
                with self.assertRaises(SystemExit) as result:
                    auditor.main()
            self.assertEqual(2, result.exception.code)
            audit.assert_not_called()
