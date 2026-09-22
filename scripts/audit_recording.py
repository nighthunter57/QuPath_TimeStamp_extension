"""Find candidate transcript gaps in an existing recording; never guess missing words.

Usage: python -m scripts.audit_recording --audio take_audio.wav
       --transcript take.txt --output new-audit-folder
"""
import argparse
import hashlib
import html
import json
import math
import wave
from pathlib import Path
from typing import Callable

import numpy as np

from scripts.live_whisper_demo import (
    SAMPLE_RATE, CHUNK_RMS_SILENCE_THRESHOLD, CLIPPING_SAMPLE_FRACTION_THRESHOLD,
    UNCLEAR_SPEECH_MARKER, contains_speech, maximum_audio_window_rms,
    clipped_sample_fraction,
)

AUDIT_WINDOW_SECONDS = 0.5
WORD_TIMING_TOLERANCE_SECONDS = 0.12
MIN_REVIEW_GAP_SECONDS = 0.12
HASH_BLOCK_BYTES = 1024 * 1024
LIMITATIONS = (
    "Candidate gaps are not confirmed missing words or an accuracy score. "
    "The speech detector can miss quiet/short speech and mistake noise for speech. "
    "Word timestamps are approximate; missing words inside an existing word span "
    "may not be detected. The report does not identify speakers or establish clinical accuracy."
)


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(HASH_BLOCK_BYTES), b""):
            digest.update(block)
    return digest.hexdigest()


def merged_ranges(ranges: list[tuple[float, float]]) -> list[tuple[float, float]]:
    result = []
    for start, end in sorted(ranges):
        if end <= start:
            continue
        if result and start <= result[-1][1] + 1e-9:
            result[-1] = (result[-1][0], max(end, result[-1][1]))
        else:
            result.append((start, end))
    return result


def uncovered_ranges(speech: list[tuple[float, float]],
                     coverage: list[tuple[float, float]]) -> list[tuple[float, float]]:
    """Linear interval subtraction after sorting; does not count words or infer text."""
    speech, coverage = merged_ranges(speech), merged_ranges(coverage)
    result = []
    cursor = 0
    for start, end in speech:
        while cursor < len(coverage) and coverage[cursor][1] <= start:
            cursor += 1
        position, index = start, cursor
        while index < len(coverage) and coverage[index][0] < end:
            covered_start, covered_end = coverage[index]
            if covered_start > position:
                result.append((position, min(end, covered_start)))
            position = max(position, covered_end)
            if position >= end:
                break
            index += 1
        if position < end:
            result.append((position, end))
    return result


def load_timing_ranges(transcript_path: Path, duration: float) -> tuple[list, list]:
    metadata = transcript_path.with_name(f"{transcript_path.stem}_review.json")
    document = json.loads(metadata.read_text(encoding="utf-8"))
    if not isinstance(document, dict) or document.get("version") != 1:
        raise ValueError("Expected version 1 machine review metadata")
    if document.get("transcript") != transcript_path.read_text(encoding="utf-8"):
        raise ValueError("Review metadata is stale or belongs to another transcript")
    rows = document.get("words")
    if not isinstance(rows, list):
        raise ValueError("Review metadata needs a words list")
    coverage, unclear = [], []
    for row in rows:
        if not isinstance(row, dict) or not isinstance(row.get("word"), str) or not row["word"].strip():
            raise ValueError("Malformed word timing row")
        values = [row.get("start_ms"), row.get("end_ms")]
        if any(isinstance(value, bool) or not isinstance(value, (int, float)) or
               not math.isfinite(value) for value in values):
            raise ValueError("Word timestamps must be finite numbers")
        start, end = (value / 1000 for value in values)
        if start < 0 or end < start or end > duration + 0.001:
            raise ValueError("Word timestamps fall outside this recording; check the file pair")
        if row["word"].strip() == UNCLEAR_SPEECH_MARKER:
            unclear.append((start, min(duration, end)))
        else:
            coverage.append((max(0, start - WORD_TIMING_TOLERANCE_SECONDS),
                             min(duration, end + WORD_TIMING_TOLERANCE_SECONDS)))
    return merged_ranges(coverage), merged_ranges(unclear)


def audit(audio_path: Path, transcript_path: Path,
          speech_detector: Callable = contains_speech) -> dict:
    """Read original PCM in bounded blocks. No ASR model, microphone, or network call."""
    audio_path, transcript_path = Path(audio_path), Path(transcript_path)
    before = file_sha256(audio_path)
    metadata_path = transcript_path.with_name(f"{transcript_path.stem}_review.json")
    transcript_hash, metadata_hash = file_sha256(transcript_path), file_sha256(metadata_path)
    speech, quiet, clipping = [], [], []
    with wave.open(str(audio_path), "rb") as source:
        if (source.getnchannels(), source.getsampwidth(), source.getframerate(), source.getcomptype()) != (1, 2, SAMPLE_RATE, "NONE"):
            raise ValueError("Use the original TimeStamp mono, 16-bit, 16000 Hz PCM WAV")
        total_frames = source.getnframes()
        duration = total_frames / SAMPLE_RATE
        if total_frames == 0:
            raise ValueError("The recording contains no audio frames")
        coverage, unclear = load_timing_ranges(transcript_path, duration)
        position = 0
        while position < total_frames:
            expected_frames = min(round(AUDIT_WINDOW_SECONDS * SAMPLE_RATE), total_frames - position)
            raw = source.readframes(expected_frames)
            if len(raw) != expected_frames * 2:
                raise ValueError("The WAV is truncated; preserve the file and inspect capture/storage")
            samples = np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768.0
            start, end = position / SAMPLE_RATE, (position + expected_frames) / SAMPLE_RATE
            if clipped_sample_fraction(samples) >= CLIPPING_SAMPLE_FRACTION_THRESHOLD:
                clipping.append((start, end))
            if speech_detector(samples):
                speech.append((start, end))
                if maximum_audio_window_rms(samples) < CHUNK_RMS_SILENCE_THRESHOLD:
                    quiet.append((start, end))
            position += expected_frames
    if (before != file_sha256(audio_path) or transcript_hash != file_sha256(transcript_path)
            or metadata_hash != file_sha256(metadata_path)):
        raise ValueError("Session changed during the audit; finish recording before auditing")
    gaps = [interval for interval in uncovered_ranges(speech, coverage)
            if interval[1] - interval[0] >= MIN_REVIEW_GAP_SECONDS - 1e-9]
    candidates = []
    for reason, ranges in (("speech_without_word_timing", gaps), ("unclear_speech_marker", unclear),
                           ("quiet_detected_speech", merged_ranges(quiet)),
                           ("clipped_audio", merged_ranges(clipping))):
        for start, end in ranges:
            candidates.append({"reason": reason, "start_ms": round(start * 1000),
                               "end_ms": round(end * 1000),
                               "replay_start_ms": round(max(0, start - 1) * 1000),
                               "replay_end_ms": round(min(duration, end + 1) * 1000)})
    candidates.sort(key=lambda item: (item["start_ms"], item["reason"]))
    return {
        "version": 1,
        "audio_sha256": before, "transcript_sha256": transcript_hash,
        "review_metadata_sha256": metadata_hash,
        "duration_ms": round(duration * 1000),
        "detected_speech_ms": round(sum(end - start for start, end in merged_ranges(speech)) * 1000),
        "gap_candidate_count": len(gaps), "review_items": candidates,
        "policy": {"window_seconds": AUDIT_WINDOW_SECONDS,
                   "word_timing_tolerance_seconds": WORD_TIMING_TOLERANCE_SECONDS,
                   "minimum_gap_seconds": MIN_REVIEW_GAP_SECONDS},
        "limitations": LIMITATIONS,
        "file_pairing": "Exact transcript/metadata match checked. Audio identity recorded, but original metadata does not bind audio by hash; operator must select the matching session WAV.",
    }


def render_report(report: dict, audio_path: Path) -> str:
    labels = {"speech_without_word_timing": "Possible transcript gap",
              "unclear_speech_marker": "Speech already marked unclear",
              "quiet_detected_speech": "Quiet detected speech — check microphone placement",
              "clipped_audio": "Audio clipping — check microphone gain"}
    items = []
    for row in report["review_items"]:
        start, end = row["start_ms"] / 1000, row["end_ms"] / 1000
        items.append(f'<li><strong>{html.escape(labels[row["reason"]])}</strong>'
                     f'<p>{start:.2f}–{end:.2f} seconds from the recording origin</p>'
                     f'<button data-start="{row["replay_start_ms"] / 1000}" '
                     f'data-end="{row["replay_end_ms"] / 1000}">Replay with context</button></li>')
    content = "".join(items) or '<li>No candidate gaps found. This does not prove every word was captured. Listen to the recording and compare it with a checked reference.</li>'
    audio_url = html.escape(audio_path.resolve().as_uri(), quote=True)
    return f'''<!doctype html><html lang="en"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>TimeStamp recording audit</title>
<style>body{{font:16px/1.6 system-ui;margin:32px auto;padding:0 20px;max-width:850px;color:#20382c;background:#f8faf6}}li{{margin:20px 0;padding:20px;background:white;border:1px solid #ccd8ca;border-radius:8px}}button{{padding:10px 16px;color:white;background:#145c50;border:0;border-radius:6px;font:inherit;cursor:pointer}}button:focus-visible{{outline:3px solid #9b6312;outline-offset:3px}}audio{{width:100%}}small{{display:block}}#status{{min-height:1.6em}}</style>
<h1>Check potentially missed speech</h1>
<p>This local report does not change the audio or transcript, and it does not guess missing words.</p>
<p><strong>{report["gap_candidate_count"]} candidate gaps</strong> · {report["duration_ms"] / 1000:.1f} seconds of audio</p>
<audio id="audio" controls preload="metadata" src="{audio_url}"></audio>
<p id="status" role="status">Choose Replay with context to listen around a flagged interval.</p>
<ol>{content}</ol><p>{html.escape(report["limitations"])}</p>
<small>{html.escape(report["file_pairing"])}</small>
<p>Keep this report local: it links to the original audio path. It does not embed or upload the recording. If playback is blocked, open the original WAV in an approved local player and seek to the times above.</p>
<script>
const audio = document.getElementById('audio');
const status = document.getElementById('status');
let stopAt = null;
document.querySelectorAll('button[data-start]').forEach(button => button.addEventListener('click', async () => {{
  audio.pause(); stopAt = Number(button.dataset.end);
  try {{ audio.currentTime = Number(button.dataset.start); await audio.play();
    status.textContent = 'Playing the flagged interval with surrounding audio.';
  }} catch {{ stopAt = null; status.textContent = 'Playback unavailable. Open the original WAV in a local audio player.'; }}
}}));
audio.addEventListener('timeupdate', () => {{ if (stopAt !== null && audio.currentTime >= stopAt) {{ audio.pause(); stopAt = null; status.textContent = 'Replay finished. Compare what you heard with the transcript.'; }} }});
audio.addEventListener('error', () => {{ status.textContent = 'Audio unavailable here. Use the original WAV in a local audio player.'; }});
</script></html>'''


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audio", type=Path, required=True)
    parser.add_argument("--transcript", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="New, nonexisting local report folder")
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Output folder already exists; choose a new folder (nothing is overwritten)")
    try:
        report = audit(args.audio, args.transcript)
        report_html = render_report(report, args.audio)
        args.output.mkdir(mode=0o700, parents=False, exist_ok=False)
        (args.output / "audit.json").write_text(json.dumps(report, indent=2, allow_nan=False) + "\n", encoding="utf-8")
        (args.output / "review.html").write_text(report_html, encoding="utf-8")
    except (OSError, ValueError, KeyError, wave.Error) as exc:
        parser.exit(1, f"Audit could not finish: {exc}\n")
    print(f"Created local review report: {args.output / 'review.html'}")
    print("No audio or transcript was changed. Candidates need human review, not automatic correction.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
