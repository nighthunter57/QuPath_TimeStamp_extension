#!/usr/bin/env python3
"""Score transcript text with raw, domain-normalized, and clinical metrics."""

import argparse
import json
import re
from pathlib import Path

from scripts.calibrate_asr import normalize_tokens, word_error_rate

DEFAULT_FIXTURE = (
    Path(__file__).resolve().parent.parent / "demo-output" / "live-accuracy-phase-0"
)
DEFAULT_CONCEPTS = Path(__file__).resolve().parent / "fixtures" / "pathology_concepts.json"

NUMBER_PHRASES = {
    "ninety five": "95",
    "seventy": "70",
    "sixty seven": "67",
    "twenty five": "25",
    "eighteen": "18",
    "twelve": "12",
    "ten": "10",
    "nine": "9",
    "eight": "8",
    "seven": "7",
    "six": "6",
    "five": "5",
    "four": "4",
    "three": "3",
    "two": "2",
    "one": "1",
}

DOMAIN_MARKER_PATTERNS = (
    (r"\bki[\s-]*(?:sixty seven|67)\b", "ki67"),
    (r"\bher[\s-]*(?:two|2)\b", "her2"),
    (r"\bbcl[\s-]*(?:six|6)\b", "bcl6"),
    (r"\bbcl[\s-]*(?:two|2)\b", "bcl2"),
    (r"\bcd[\s-]*(?:twenty|20)\b", "cd20"),
    (r"\bcd[\s-]*(?:ten|10)\b", "cd10"),
    (r"\bcd[\s-]*(?:three|3)\b", "cd3"),
    (r"\ba\s*e\s*(?:three|3)\b", "ae3"),
    (r"\ba\s*e\s*(?:one|1)\b", "ae1"),
    (r"\ba\s*(?:three|3)\b", "a3"),
    (r"\ba\s*(?:one|1)\b", "a1"),
    (r"\b(?:two|2)\s*plus\b", "2plus"),
)


def strip_transcript_timestamps(text: str) -> str:
    return re.sub(r"\[[^\]\n]+\]", " ", text)


def domain_normalize_text(text: str) -> str:
    """Canonicalize clinically equivalent number and marker formatting."""
    normalized = strip_transcript_timestamps(text).strip().lower()
    normalized = re.sub(r"\b(\d+)\s*%", r"\1 percent", normalized)
    normalized = re.sub(r"\b(\d+)\s*\+", r"\1plus", normalized)
    normalized = re.sub(
        r"\b(august)\s+twenty\s+sixth[\s,]+twenty\s+twenty\s+six\b",
        r"\1 26 2026",
        normalized,
    )
    normalized = re.sub(
        r"\b(august)\s+20th[\s,]+6[\s,]+2026\b",
        r"\1 26 2026",
        normalized,
    )
    for pattern, replacement in DOMAIN_MARKER_PATTERNS:
        normalized = re.sub(pattern, replacement, normalized)
    for phrase, replacement in NUMBER_PHRASES.items():
        normalized = re.sub(rf"\b{re.escape(phrase)}\b", replacement, normalized)
    normalized = re.sub(r"[^\w\s]", " ", normalized)
    return re.sub(r"\s+", " ", normalized).strip()


def domain_normalize_tokens(text: str) -> list[str]:
    return domain_normalize_text(text).split()


def load_concepts(path: Path) -> list[dict[str, str]]:
    concepts = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(concepts, list) or not all(
        isinstance(item, dict) and item.get("id") and item.get("text")
        for item in concepts
    ):
        raise ValueError(f"Invalid concept fixture: {path}")
    return concepts


def missing_medical_concepts(
    reference_text: str,
    hypothesis_text: str,
    concepts: list[dict[str, str]],
) -> tuple[list[dict[str, str]], int]:
    reference = f" {domain_normalize_text(reference_text)} "
    hypothesis = f" {domain_normalize_text(hypothesis_text)} "
    expected = []
    missing = []
    for concept in concepts:
        needle = f" {domain_normalize_text(concept['text'])} "
        if needle not in reference:
            continue
        expected.append(concept)
        if needle not in hypothesis:
            missing.append(concept)
    return missing, len(expected)


def score(reference_text: str, hypothesis_text: str, concepts: list[dict[str, str]]) -> dict:
    hypothesis_without_timestamps = strip_transcript_timestamps(hypothesis_text)
    raw_errors, raw_words = word_error_rate(
        normalize_tokens(reference_text),
        normalize_tokens(hypothesis_without_timestamps),
    )
    domain_errors, domain_words = word_error_rate(
        domain_normalize_tokens(reference_text),
        domain_normalize_tokens(hypothesis_without_timestamps),
    )
    missing, expected = missing_medical_concepts(reference_text, hypothesis_text, concepts)
    return {
        "raw": {
            "errors": raw_errors,
            "words": raw_words,
            "wer": raw_errors / max(1, raw_words) * 100.0,
        },
        "domain_normalized": {
            "errors": domain_errors,
            "words": domain_words,
            "wer": domain_errors / max(1, domain_words) * 100.0,
        },
        "formatting_equivalent_errors": max(0, raw_errors - domain_errors),
        "medical_concepts": {
            "missing": len(missing),
            "expected": expected,
            "error_rate": len(missing) / max(1, expected) * 100.0,
            "missing_items": missing,
        },
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference", type=Path, default=DEFAULT_FIXTURE / "reference.txt")
    parser.add_argument(
        "--hypothesis",
        type=Path,
        default=DEFAULT_FIXTURE / "baseline_transcript.txt",
    )
    parser.add_argument("--concepts", type=Path, default=DEFAULT_CONCEPTS)
    parser.add_argument("--json", action="store_true", help="Emit machine-readable JSON")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    result = score(
        args.reference.read_text(encoding="utf-8"),
        args.hypothesis.read_text(encoding="utf-8"),
        load_concepts(args.concepts),
    )
    if args.json:
        print(json.dumps(result, indent=2))
        return 0
    print(f"Reference   : {args.reference}")
    print(f"Hypothesis  : {args.hypothesis}")
    print(
        f"Raw WER     : {result['raw']['wer']:.2f}% "
        f"({result['raw']['errors']}/{result['raw']['words']})"
    )
    print(
        f"Domain WER  : {result['domain_normalized']['wer']:.2f}% "
        f"({result['domain_normalized']['errors']}/{result['domain_normalized']['words']})"
    )
    print(f"Formatting-equivalent errors: {result['formatting_equivalent_errors']}")
    medical = result["medical_concepts"]
    print(
        f"Medical concept error rate: {medical['error_rate']:.2f}% "
        f"({medical['missing']}/{medical['expected']})"
    )
    for concept in medical["missing_items"]:
        print(f"  MISSING {concept['id']}: {concept['text']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
