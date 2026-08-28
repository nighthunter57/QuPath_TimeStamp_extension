#!/usr/bin/env python3
"""Find a live model that is both accurate enough and fast enough.

The shipping live default is `distil-small.en` at beam 2 (59.78% WER on the
fixture) because `large-v3-turbo` at beam 5 (21.23%) could not keep up with the
streaming schedule. Nothing between those two points has been measured.

For each candidate this reports:
  * decode time for one 20-second window, which is the Phase 3 streaming budget
  * WER over the whole fixture, which is the quality the doctor sees

A candidate is viable when the 20-second decode is comfortably under the live
step interval and the WER is materially better than the current default.
"""

import argparse
import re
import sys
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
FIXTURE_DIR = REPO_ROOT / "demo-output" / "live-accuracy-phase-0"
DEFAULT_AUDIO = FIXTURE_DIR / "regression_fixture_audio.wav"
DEFAULT_REFERENCE = FIXTURE_DIR / "reference.txt"
WINDOW_SECONDS = 20.0
CURRENT_DEFAULT_WER = 59.78

# (model, beam, best_of). Ordered cheapest first so early results arrive early.
CANDIDATES = [
    ("distil-small.en", 2, 2),
    ("distil-small.en", 5, 5),
    ("small.en", 2, 2),
    ("distil-large-v3", 1, 1),
    ("distil-large-v3", 2, 2),
    ("deepdml/faster-whisper-large-v3-turbo-ct2", 1, 1),
    ("deepdml/faster-whisper-large-v3-turbo-ct2", 2, 2),
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audio", type=Path, default=DEFAULT_AUDIO)
    parser.add_argument("--reference", type=Path, default=DEFAULT_REFERENCE)
    parser.add_argument("--compute-type", default="int8_float32")
    parser.add_argument("--step-seconds", type=float, default=1.0,
                        help="Live step interval the decode must fit inside")
    return parser.parse_args()


def normalize_tokens(text: str) -> list[str]:
    lowered = re.sub(r"\[[^\]]*\]", " ", text).strip().lower()
    lowered = re.sub(r"[^\w\s]", " ", lowered)
    return lowered.split()


def word_error_rate(reference: list[str], hypothesis: list[str]) -> tuple[int, int]:
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


def main() -> int:
    sys.path.insert(0, str(REPO_ROOT))
    from scripts.live_whisper_demo import (
        SAMPLE_RATE,
        build_transcribe_kwargs,
        condition_live_audio,
        decode_saved_audio,
    )
    from faster_whisper import WhisperModel

    args = parse_args()
    if not args.audio.is_file():
        raise SystemExit(f"Fixture audio not found: {args.audio}")

    audio = decode_saved_audio(args.audio)
    conditioned = condition_live_audio(audio)
    window = conditioned[: int(SAMPLE_RATE * WINDOW_SECONDS)]
    reference = normalize_tokens(args.reference.read_text(encoding="utf-8"))

    print(f"Audio      : {args.audio.name}  ({len(audio) / SAMPLE_RATE:.1f}s)")
    print(f"Reference  : {len(reference)} words")
    print(f"Budget     : one {WINDOW_SECONDS:.0f}s window must decode in < "
          f"{args.step_seconds:.1f}s to keep up")
    print(f"Current    : distil-small.en beam 2 = {CURRENT_DEFAULT_WER:.2f}% WER\n")

    results = []
    for model_name, beam_size, best_of in CANDIDATES:
        label = f"{model_name.split('/')[-1]} beam {beam_size}"
        print(f"--- {label}", flush=True)
        try:
            model = WhisperModel(model_name, device="cpu",
                                 compute_type=args.compute_type)
        except Exception as exc:
            print(f"    load failed: {exc}\n")
            continue

        kwargs = build_transcribe_kwargs(
            "en", beam_size, best_of,
            previous_text=False, final_pass=False, hotwords=None,
        )

        started = time.monotonic()
        list(model.transcribe(window, **kwargs)[0])
        window_seconds = time.monotonic() - started

        started = time.monotonic()
        segments, _ = model.transcribe(conditioned, **kwargs)
        hypothesis = normalize_tokens(" ".join(s.text for s in segments))
        full_seconds = time.monotonic() - started

        errors, words = word_error_rate(reference, hypothesis)
        wer = errors / max(1, words) * 100.0
        results.append((wer, window_seconds, label, len(hypothesis), full_seconds))
        print(f"    20s window : {window_seconds:6.2f}s")
        print(f"    WER        : {wer:6.2f}%  ({len(hypothesis)} words)")
        print(f"    full pass  : {full_seconds:6.1f}s\n", flush=True)
        del model

    print("=" * 72)
    print(f"{'candidate':<40} {'WER':>8} {'20s win':>9} {'verdict':>12}")
    print("-" * 72)
    for wer, window_seconds, label, _, _ in sorted(results):
        if window_seconds > WINDOW_SECONDS:
            verdict = "unusable"
        elif window_seconds > args.step_seconds * 4:
            verdict = "too slow"
        elif wer < CURRENT_DEFAULT_WER - 5:
            verdict = "CANDIDATE"
        else:
            verdict = "no gain"
        print(f"{label:<40} {wer:7.2f}% {window_seconds:8.2f}s {verdict:>12}")
    print("=" * 72)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
