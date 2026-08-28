import unittest
from pathlib import Path

from scripts import score_transcript


class TranscriptScoringTest(unittest.TestCase):

    def test_domain_normalization_equates_clinical_marker_and_number_formatting(self):
        spoken = "CD twenty, Ki sixty seven, HER two score two plus, ninety five percent"
        formatted = "CD20, Ki-67, HER2 score 2+, 95%"

        self.assertEqual(
            score_transcript.domain_normalize_text(spoken),
            score_transcript.domain_normalize_text(formatted),
        )

    def test_domain_normalization_equates_fixture_date_formatting(self):
        spoken = "August twenty sixth, twenty twenty six"
        formatted = "August 20th, 6, 2026"

        self.assertEqual(
            score_transcript.domain_normalize_text(spoken),
            score_transcript.domain_normalize_text(formatted),
        )

    def test_medical_concept_metric_detects_missing_negation_concept(self):
        concepts = [{"id": "negative_margin", "text": "margin is negative"}]

        missing, expected = score_transcript.missing_medical_concepts(
            "The margin is negative.",
            "The margin is positive.",
            concepts,
        )

        self.assertEqual(1, expected)
        self.assertEqual(concepts, missing)

    def test_phase_zero_fixture_classifies_formatting_errors(self):
        fixture = score_transcript.DEFAULT_FIXTURE
        if not fixture.is_dir():
            self.skipTest("ignored local Phase 0 fixture is unavailable")
        result = score_transcript.score(
            fixture.joinpath("reference.txt").read_text(encoding="utf-8"),
            fixture.joinpath("baseline_transcript.txt").read_text(encoding="utf-8"),
            score_transcript.load_concepts(score_transcript.DEFAULT_CONCEPTS),
        )

        self.assertEqual(55, result["raw"]["errors"])
        self.assertEqual(3, result["domain_normalized"]["errors"])
        self.assertEqual(52, result["formatting_equivalent_errors"])
        self.assertEqual(3, result["medical_concepts"]["missing"])
        self.assertEqual(35, result["medical_concepts"]["expected"])


if __name__ == "__main__":
    unittest.main()
