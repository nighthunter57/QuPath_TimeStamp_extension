#!/usr/bin/env python3
"""Calibrate the final-pass decoder against LibriSpeech test-clean.

The project's own fixture is synthesized speech routed through a virtual audio
device, so a poor score there cannot distinguish a pipeline defect from an
unrepresentative signal. LibriSpeech test-clean is human speech with exact
reference transcripts and a widely published Whisper baseline, which makes it a
control: if this script reports a WER far above the published figure, the defect
is in this project rather than in its audio.

Usage:
    .venv-whisper/bin/python -m scripts.calibrate_asr --count 40
    .venv-whisper/bin/python -m scripts.calibrate_asr --count 40 --hotwords
"""

import argparse
import re
import sys
import time
from pathlib import Path

DEFAULT_CORPUS = (
    Path(__file__).resolve().parent.parent
    / "demo-output" / "librispeech-calibration" / "LibriSpeech" / "test-clean"
)
# Published Whisper large-v3 WER on LibriSpeech test-clean, for orientation only.
PUBLISHED_LARGE_V3_WER = 2.5

NUMBER_WORDS = {
    "0": "zero", "1": "one", "2": "two", "3": "three", "4": "four",
    "5": "five", "6": "six", "7": "seven", "8": "eight", "9": "nine",
    "10": "ten", "11": "eleven", "12": "twelve", "13": "thirteen",
    "14": "fourteen", "15": "fifteen", "16": "sixteen", "17": "seventeen",
    "18": "eighteen", "19": "nineteen", "20": "twenty", "30": "thirty",
    "40": "forty", "50": "fifty", "60": "sixty", "70": "seventy",
    "80": "eighty", "90": "ninety", "100": "hundred", "1000": "thousand",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", type=Path, default=DEFAULT_CORPUS)
    parser.add_argument("--count", type=int, default=40,
                        help="Number of utterances to decode")
    parser.add_argument("--model", default="large-v3")
    parser.add_argument("--compute-type", default="int8_float32")
    parser.add_argument("--beam-size", type=int, default=8)
    parser.add_argument("--best-of", type=int, default=8)
    parser.add_argument("--hotwords", action="store_true",
                        help="Apply the pathology hotword bias (off by default)")
    parser.add_argument("--worst", type=int, default=5,
                        help="How many worst-scoring utterances to print")
    return parser.parse_args()


def normalize_tokens(text: str, expand_numbers: bool = False) -> list[str]:
    """Lowercase, drop punctuation, keep alphanumeric tokens.

    Mirrors the project's existing scoring so results stay comparable. With
    expand_numbers, digit tokens are mapped to words so that "25" and
    "twenty five" do not score as errors against each other; the gap between
    the two totals is the share of WER attributable to number formatting.
    """
    lowered = text.strip().lower()
    lowered = re.sub(r"[^\w\s]", " ", lowered)
    tokens = lowered.split()
    if not expand_numbers:
        return tokens
    expanded: list[str] = []
    for token in tokens:
        expanded.extend(NUMBER_WORDS.get(token, token).split())
    return expanded


def word_error_rate(reference: list[str], hypothesis: list[str]) -> tuple[int, int]:
    """Levenshtein distance over word tokens; returns (errors, reference length)."""
    if not reference:
        return len(hypothesis), 0
    previous = list(range(len(hypothesis) + 1))
    for ref_index, ref_word in enumerate(reference, start=1):
        current = [ref_index]
        for hyp_index, hyp_word in enumerate(hypothesis, start=1):
            current.append(min(
                previous[hyp_index] + 1,
                current[hyp_index - 1] + 1,
                previous[hyp_index - 1] + (ref_word != hyp_word),
            ))
        previous = current
    return previous[-1], len(reference)


def load_utterances(corpus: Path, count: int) -> list[tuple[str, Path, str]]:
    """Sample evenly across speakers so one voice cannot dominate the score."""
    if not corpus.is_dir():
        raise SystemExit(f"Corpus not found: {corpus}")
    entries: list[tuple[str, Path, str]] = []
    for transcript_path in sorted(corpus.rglob("*.trans.txt")):
        for line in transcript_path.read_text(encoding="utf-8").splitlines():
            utterance_id, _, text = line.partition(" ")
            audio_path = transcript_path.parent / f"{utterance_id}.flac"
            if text and audio_path.is_file():
                entries.append((utterance_id, audio_path, text))
    if not entries:
        raise SystemExit(f"No utterances found under {corpus}")
    if count >= len(entries):
        return entries
    stride = len(entries) / count
    return [entries[int(index * stride)] for index in range(count)]


def main() -> int:
    args = parse_args()
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
    from scripts.live_whisper_demo import (
        DEFAULT_PATHOLOGY_HOTWORDS,
        build_transcribe_kwargs,
        decode_saved_audio,
        filter_trailing_hallucination_segments,
    )
    from faster_whisper import WhisperModel

    utterances = load_utterances(args.corpus, args.count)
    print(f"Corpus      : {args.corpus}")
    print(f"Utterances  : {len(utterances)}")
    print(f"Model       : {args.model} ({args.compute_type}), beam {args.beam_size}")
    print(f"Hotwords    : {'pathology bias ON' if args.hotwords else 'off'}")
    print("Loading model…", flush=True)

    model = WhisperModel(args.model, device="cpu", compute_type=args.compute_type)
    kwargs = build_transcribe_kwargs(
        "en",
        args.beam_size,
        args.best_of,
        previous_text=False,          # each utterance is independent
        final_pass=True,
        hotwords=DEFAULT_PATHOLOGY_HOTWORDS if args.hotwords else None,
    )

    total_errors = total_words = 0
    total_errors_numeric = total_words_numeric = 0
    audio_seconds = 0.0
    scored: list[tuple[float, str, str, str]] = []
    started = time.monotonic()

    for index, (utterance_id, audio_path, reference_text) in enumerate(utterances, start=1):
        segments, info = model.transcribe(str(audio_path), **kwargs)
        segments = filter_trailing_hallucination_segments(
            segments,
            decode_saved_audio(audio_path),
        )
        hypothesis_text = " ".join(segment.text for segment in segments).strip()
        audio_seconds += float(getattr(info, "duration", 0.0) or 0.0)

        reference = normalize_tokens(reference_text)
        hypothesis = normalize_tokens(hypothesis_text)
        errors, words = word_error_rate(reference, hypothesis)
        total_errors += errors
        total_words += words

        errors_numeric, words_numeric = word_error_rate(
            normalize_tokens(reference_text, expand_numbers=True),
            normalize_tokens(hypothesis_text, expand_numbers=True),
        )
        total_errors_numeric += errors_numeric
        total_words_numeric += words_numeric

        scored.append((
            errors / max(1, words) * 100.0, utterance_id, reference_text, hypothesis_text,
        ))
        running = total_errors / max(1, total_words) * 100.0
        print(f"  [{index}/{len(utterances)}] {utterance_id}  running WER {running:.2f}%",
              flush=True)

    elapsed = time.monotonic() - started
    wer = total_errors / max(1, total_words) * 100.0
    wer_numeric = total_errors_numeric / max(1, total_words_numeric) * 100.0

    print("\n" + "=" * 62)
    print(f"  Words           : {total_words}")
    print(f"  Errors          : {total_errors}")
    print(f"  WER             : {wer:.2f}%")
    print(f"  WER (numbers normalized) : {wer_numeric:.2f}%")
    print(f"  Audio           : {audio_seconds:.1f}s")
    print(f"  Decode          : {elapsed:.1f}s  ({audio_seconds / max(elapsed, 1e-9):.2f}x realtime)")
    print(f"  Published large-v3 baseline: ~{PUBLISHED_LARGE_V3_WER:.1f}%")
    print("=" * 62)

    if wer <= PUBLISHED_LARGE_V3_WER * 2.5:
        print("  VERDICT: pipeline is healthy on human speech.")
        print("           A poor score on the synthetic fixture is the fixture,")
        print("           not the decoder.")
    else:
        print("  VERDICT: pipeline scores far above the published baseline on")
        print("           known-good human audio. The defect is in this project —")
        print("           investigate before any model or microphone work.")

    print(f"\nWorst {args.worst} utterances:")
    for utterance_wer, utterance_id, reference_text, hypothesis_text in \
            sorted(scored, reverse=True)[:args.worst]:
        print(f"\n  {utterance_id}  ({utterance_wer:.1f}% WER)")
        print(f"    REF: {reference_text.lower()}")
        print(f"    HYP: {hypothesis_text.lower()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
