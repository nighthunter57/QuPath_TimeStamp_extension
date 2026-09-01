#!/usr/bin/env python3
"""Replay a saved fixture through the production Whisper live policy."""

import argparse
import json
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

import numpy as np

from scripts.live_whisper_demo import (
    ENDPOINT_MAX_TURN_SECONDS,
    DEFAULT_PATHOLOGY_HOTWORDS,
    LIVE_VAD_WINDOW_SECONDS,
    SAMPLE_RATE,
    LiveAudioConditioner,
    SpeechEndpointState,
    WhisperLiveTranscriber,
    decode_saved_audio,
    format_transcript_line,
    group_committed_words,
    local_agreement_prompt,
    should_buffer_live_audio,
)
from scripts.score_transcript import DEFAULT_CONCEPTS, load_concepts, score

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_FIXTURE = REPO_ROOT / "demo-output" / "live-accuracy-phase-0"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audio", type=Path,
                        default=DEFAULT_FIXTURE / "regression_fixture_audio.wav")
    parser.add_argument("--reference", type=Path,
                        default=DEFAULT_FIXTURE / "reference.txt")
    parser.add_argument("--model", default="small.en")
    parser.add_argument("--compute-type", default="int8_float32")
    parser.add_argument("--beam-size", type=int, default=2)
    parser.add_argument("--best-of", type=int, default=2)
    parser.add_argument("--step-seconds", type=float, default=1.0)
    parser.add_argument("--json", action="store_true")
    return parser.parse_args()


def replay(args: argparse.Namespace) -> dict:
    from faster_whisper import WhisperModel

    audio = decode_saved_audio(args.audio)
    model = WhisperModel(args.model, device="cpu", compute_type=args.compute_type)
    transcriber = WhisperLiveTranscriber(
        model,
        args.model,
        "en",
        args.beam_size,
        args.best_of,
        hotwords=DEFAULT_PATHOLOGY_HOTWORDS,
    )
    conditioner = LiveAudioConditioner()
    endpoint = SpeechEndpointState()
    origin = datetime(2026, 1, 1, tzinfo=timezone.utc)
    chunk_samples = round(SAMPLE_RATE * LIVE_VAD_WINDOW_SECONDS)
    decode_buffer = np.empty(0, dtype=np.float32)
    buffer_start = None
    last_decode_end = None
    last_word_end = None
    completed_words = []
    endpoint_counts = {"silence": 0, "word-gap": 0, "hard-cap": 0, "stop": 0}
    turn_durations = []
    decode_seconds = 0.0

    def trim_buffer(committed_through):
        nonlocal decode_buffer, buffer_start
        if buffer_start is None or committed_through is None:
            return
        trim_samples = min(
            decode_buffer.shape[0],
            max(0, round((committed_through - buffer_start).total_seconds() * SAMPLE_RATE)),
        )
        decode_buffer = decode_buffer[trim_samples:]
        if decode_buffer.size:
            buffer_start += timedelta(seconds=trim_samples / SAMPLE_RATE)
        else:
            buffer_start = None

    for sample_start in range(0, audio.shape[0], chunk_samples):
        raw_chunk = audio[sample_start:sample_start + chunk_samples]
        chunk_start = origin + timedelta(seconds=sample_start / SAMPLE_RATE)
        chunk_end = chunk_start + timedelta(seconds=raw_chunk.shape[0] / SAMPLE_RATE)
        speech_active = should_buffer_live_audio(raw_chunk)
        endpoint.observe(chunk_start, raw_chunk.shape[0] / SAMPLE_RATE, speech_active)
        if speech_active:
            if buffer_start is None:
                buffer_start = chunk_start
            decode_buffer = np.concatenate((decode_buffer, conditioner.process(raw_chunk)))

        endpoint_reason = endpoint.endpoint_reason(last_word_end, include_word_gap=False)
        enough_audio = decode_buffer.shape[0] >= round(SAMPLE_RATE * 0.5)
        decode_due = (
            last_decode_end is None
            or (chunk_end - last_decode_end).total_seconds() >= args.step_seconds
        )
        if not enough_audio or (not decode_due and endpoint_reason is None):
            continue

        window = decode_buffer[: round(SAMPLE_RATE * ENDPOINT_MAX_TURN_SECONDS)]
        force = endpoint_reason is not None
        started = time.monotonic()
        update = transcriber.accept_audio(
            window,
            buffer_start,
            force=force,
            context_prompt=local_agreement_prompt(
                group_committed_words(completed_words),
                transcriber.agreement.committed_words,
            ),
        )
        decode_seconds += time.monotonic() - started
        last_decode_end = chunk_end
        observed_words = (*update.committed_words, *update.provisional_words)
        if observed_words:
            last_word_end = max(word[1] for word in observed_words)
        if not force:
            decoded_audio_end = buffer_start + timedelta(
                seconds=window.shape[0] / SAMPLE_RATE,
            )
            endpoint_reason = endpoint.endpoint_reason(
                last_word_end,
                decoded_audio_end=decoded_audio_end,
            )
            if endpoint_reason is not None:
                update = transcriber.force_current(chunk_end)
                force = True
        if force:
            completed_words.extend(update.committed_words)
            endpoint_counts[endpoint_reason or "stop"] += 1
            if endpoint.turn_started_at is not None and endpoint.latest_audio_end is not None:
                turn_durations.append(
                    (endpoint.latest_audio_end - endpoint.turn_started_at).total_seconds()
                )
            decode_buffer = np.empty(0, dtype=np.float32)
            buffer_start = None
            last_decode_end = None
            last_word_end = None
            endpoint.reset()
            transcriber.reset_turn()
        else:
            trim_buffer(update.committed_through)

    if decode_buffer.size and buffer_start is not None:
        started = time.monotonic()
        update = transcriber.accept_audio(decode_buffer, buffer_start, force=True)
        decode_seconds += time.monotonic() - started
        completed_words.extend(update.committed_words)
        endpoint_counts["stop"] += 1

    transcript_lines = [
        format_transcript_line(timestamp, text)
        for timestamp, text in group_committed_words(completed_words)
    ]
    hypothesis = "\n".join(transcript_lines)
    metrics = score(
        args.reference.read_text(encoding="utf-8"),
        hypothesis,
        load_concepts(DEFAULT_CONCEPTS),
    )
    return {
        "model": args.model,
        "audio_seconds": audio.shape[0] / SAMPLE_RATE,
        "decode_seconds": decode_seconds,
        "committed_words": len(completed_words),
        "turns": sum(endpoint_counts.values()),
        "endpoint_reasons": endpoint_counts,
        "max_turn_seconds": max(turn_durations, default=0.0),
        "metrics": metrics,
        "hypothesis": hypothesis,
    }


def main() -> int:
    args = parse_args()
    result = replay(args)
    if args.json:
        print(json.dumps(result, indent=2))
        return 0
    print(f"Model           : {result['model']}")
    print(f"Audio/decode    : {result['audio_seconds']:.1f}s / {result['decode_seconds']:.1f}s")
    print(f"Committed words : {result['committed_words']}")
    print(f"Turns           : {result['turns']} {result['endpoint_reasons']}")
    print(f"Longest turn    : {result['max_turn_seconds']:.1f}s")
    print(f"Raw WER         : {result['metrics']['raw']['wer']:.2f}%")
    print(f"Domain WER      : {result['metrics']['domain_normalized']['wer']:.2f}%")
    print(f"Medical CER     : {result['metrics']['medical_concepts']['error_rate']:.2f}%")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
