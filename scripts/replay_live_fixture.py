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
    MIN_FLUSH_SECONDS,
    FAST_INITIAL_LIVE_WINDOW_SECONDS,
    SAMPLE_RATE,
    LiveAudioConditioner,
    SpeechDecodeTimeline,
    SpeechEndpointState,
    WhisperLiveTranscriber,
    decode_saved_audio,
    format_transcript_line,
    group_committed_words,
    local_agreement_prompt,
    live_decode_boundary,
    restore_backlog_endpoint,
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
    parser.add_argument(
        "--no-score",
        action="store_true",
        help="Replay human audio without a matching reference and omit WER metrics",
    )
    parser.add_argument(
        "--no-hotwords",
        action="store_true",
        help="Disable pathology vocabulary for non-pathology validation audio",
    )
    parser.add_argument("--output", type=Path, help="Write the committed replay transcript")
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
        hotwords=None if args.no_hotwords else DEFAULT_PATHOLOGY_HOTWORDS,
    )
    result = replay_audio(audio, transcriber, args.step_seconds)
    hypothesis = result["hypothesis"]
    result["model"] = args.model
    result["metrics"] = None if args.no_score else score(
        args.reference.read_text(encoding="utf-8"), hypothesis, load_concepts(DEFAULT_CONCEPTS))
    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(hypothesis + ("\n" if hypothesis else ""), encoding="utf-8")
    return result


def replay_audio(audio, transcriber, step_seconds: float = 1.0,
                 speech_detector=should_buffer_live_audio, clock=time.monotonic) -> dict:
    """Simulate microphone arrival during measured decoding, without wall-clock sleeps."""
    if step_seconds <= 0:
        raise ValueError("step_seconds must be positive")
    conditioner = LiveAudioConditioner()
    endpoint = SpeechEndpointState()
    origin = datetime(2026, 1, 1, tzinfo=timezone.utc)
    chunk_samples = round(SAMPLE_RATE * LIVE_VAD_WINDOW_SECONDS)
    decode_buffer = np.empty(0, dtype=np.float32)
    decode_timeline = SpeechDecodeTimeline()
    buffer_start = None
    last_decode_end = None
    last_word_end = None
    completed_words = []
    completed_entries = []
    endpoint_counts = {"silence": 0, "word-gap": 0, "hard-cap": 0, "stop": 0}
    turn_durations = []
    decode_seconds = 0.0
    simulated_seconds = 0.0
    sample_start = 0
    last_reported_words = 0
    display_delays = []
    max_buffer_seconds = 0.0
    has_emission = False

    def trim_buffer(committed_through):
        nonlocal decode_buffer, buffer_start
        if buffer_start is None or committed_through is None:
            return
        trim_samples = min(decode_buffer.shape[0], decode_timeline.trim_through(committed_through))
        decode_buffer = decode_buffer[trim_samples:]
        buffer_start = decode_timeline.start_time if decode_buffer.size else None

    while sample_start < audio.shape[0] or decode_buffer.size:
        next_chunk_end = min(audio.shape[0], sample_start + chunk_samples) / SAMPLE_RATE
        simulated_seconds = max(simulated_seconds, next_chunk_end)
        # The microphone continues producing chunks while the previous decode runs.
        while sample_start < audio.shape[0] and (
                min(audio.shape[0], sample_start + chunk_samples) / SAMPLE_RATE <= simulated_seconds):
            if decode_buffer.size and endpoint.endpoint_reason(last_word_end, include_word_gap=False) is not None:
                break
            raw_chunk = audio[sample_start:sample_start + chunk_samples]
            chunk_start = origin + timedelta(seconds=sample_start / SAMPLE_RATE)
            speech_active = speech_detector(raw_chunk)
            endpoint.observe(chunk_start, raw_chunk.shape[0] / SAMPLE_RATE, speech_active)
            conditioned = conditioner.process(raw_chunk)
            if speech_active:
                if buffer_start is None:
                    buffer_start = chunk_start
                decode_timeline.append(chunk_start, raw_chunk.shape[0])
                decode_buffer = np.concatenate((decode_buffer, conditioned))
            sample_start += raw_chunk.shape[0]

        stopped = sample_start >= audio.shape[0]
        if not decode_buffer.size:
            continue
        max_buffer_seconds = max(max_buffer_seconds, decode_buffer.size / SAMPLE_RATE)
        decode_samples, chunk_end = live_decode_boundary(
            decode_timeline, decode_buffer.size, endpoint.latest_audio_end)

        endpoint_reason = endpoint.endpoint_reason(last_word_end, include_word_gap=False)
        enough_audio = decode_buffer.shape[0] >= round(SAMPLE_RATE * (
            MIN_FLUSH_SECONDS if has_emission else FAST_INITIAL_LIVE_WINDOW_SECONDS))
        decode_due = (
            last_decode_end is None
            or (chunk_end - last_decode_end).total_seconds() >= (
                step_seconds if has_emission else FAST_INITIAL_LIVE_WINDOW_SECONDS)
        )
        if not stopped and endpoint_reason is None and (not enough_audio or not decode_due):
            continue

        window = decode_buffer[:decode_samples]
        force = stopped or endpoint_reason is not None
        started = clock()
        update = transcriber.accept_audio(
            window,
            buffer_start,
            force=force,
            context_prompt=local_agreement_prompt(
                completed_entries,
                transcriber.agreement.committed_words,
            ),
            audio_timeline=decode_timeline.prefix(window.shape[0]),
        )
        elapsed = max(0.0, clock() - started)
        decode_seconds += elapsed
        simulated_seconds += elapsed
        last_decode_end = chunk_end
        observed_words = (*update.committed_words, *update.provisional_words)
        if observed_words:
            last_word_end = max(word[1] for word in observed_words)
            has_emission = True
        if not force:
            endpoint_reason = endpoint.endpoint_reason(
                last_word_end,
            )
            if endpoint_reason is not None:
                update = transcriber.force_current(chunk_end)
                force = True
        for word in update.committed_words[last_reported_words:]:
            display_delays.append(max(0.0, simulated_seconds - (word[1] - origin).total_seconds()))
        last_reported_words = len(update.committed_words)
        if force:
            completed_words.extend(update.committed_words)
            completed_entries.extend(group_committed_words(update.committed_words))
            endpoint_counts[endpoint_reason or "stop"] += 1
            if endpoint.turn_started_at is not None and endpoint.latest_audio_end is not None:
                turn_durations.append(
                    (chunk_end - endpoint.turn_started_at).total_seconds()
                )
            latest_capture_end = endpoint.latest_audio_end
            trim_buffer(chunk_end)
            last_decode_end = None
            last_word_end = None
            restore_backlog_endpoint(endpoint, decode_timeline, latest_capture_end)
            transcriber.reset_turn()
            last_reported_words = 0
        else:
            trim_buffer(update.committed_through)

    if transcriber.agreement.committed_words:
        completed_words.extend(transcriber.agreement.committed_words)
        completed_entries.extend(group_committed_words(transcriber.agreement.committed_words))
        endpoint_counts["stop"] += 1

    transcript_lines = [
        format_transcript_line(timestamp, text)
        for timestamp, text in completed_entries
    ]
    hypothesis = "\n".join(transcript_lines)
    return {
        "audio_seconds": audio.shape[0] / SAMPLE_RATE,
        "decode_seconds": decode_seconds,
        "simulated_finish_seconds": simulated_seconds,
        "max_buffer_seconds": max_buffer_seconds,
        "display_delay_p50_seconds": float(np.percentile(display_delays, 50)) if display_delays else None,
        "display_delay_p95_seconds": float(np.percentile(display_delays, 95)) if display_delays else None,
        "committed_words": len(completed_words),
        "turns": sum(endpoint_counts.values()),
        "endpoint_reasons": endpoint_counts,
        "max_turn_seconds": max(turn_durations, default=0.0),
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
    print(f"Finish/backlog  : {result['simulated_finish_seconds']:.1f}s / {result['max_buffer_seconds']:.1f}s buffered")
    print(f"Display p50/p95 : {result['display_delay_p50_seconds']} / {result['display_delay_p95_seconds']} seconds")
    print(f"Committed words : {result['committed_words']}")
    print(f"Turns           : {result['turns']} {result['endpoint_reasons']}")
    print(f"Longest turn    : {result['max_turn_seconds']:.1f}s")
    if result["metrics"] is not None:
        print(f"Raw WER         : {result['metrics']['raw']['wer']:.2f}%")
        print(f"Domain WER      : {result['metrics']['domain_normalized']['wer']:.2f}%")
        print(f"Medical CER     : {result['metrics']['medical_concepts']['error_rate']:.2f}%")
    else:
        print("WER metrics     : omitted (no matching reference)")
    if args.output is not None:
        print(f"Transcript      : {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
