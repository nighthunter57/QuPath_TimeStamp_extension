#!/usr/bin/env python3
"""Live microphone transcription with faster-whisper."""

import argparse
import queue
import signal
import sys
from datetime import datetime, timedelta
from pathlib import Path
from typing import Optional, Sequence, Union

SAMPLE_RATE = 16000
CHANNELS = 1
MIN_FLUSH_SECONDS = 1.0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Capture live microphone audio and append transcript lines to a text file.",
    )
    parser.add_argument("--output", required=True, help="Transcript output path")
    parser.add_argument("--model", default="large-v3", help="faster-whisper model name")
    parser.add_argument("--language", default="en", help="Language code, or 'auto' for detection")
    parser.add_argument(
        "--chunk-seconds",
        type=float,
        default=3.5,
        help="Audio chunk size in seconds",
    )
    parser.add_argument(
        "--device",
        default=None,
        help="Optional input device index or name",
    )
    parser.add_argument(
        "--compute-type",
        default="int8",
        help="faster-whisper compute type, e.g. int8 or float16",
    )
    parser.add_argument("--beam-size", type=int, default=5, help="Beam size for decoding")
    parser.add_argument("--best-of", type=int, default=5, help="best_of value for decoding")
    parser.add_argument(
        "--previous-text",
        default="true",
        help="Use previous text context: true or false",
    )
    parser.add_argument(
        "--list-devices",
        action="store_true",
        help="List available audio devices and exit",
    )
    return parser.parse_args()


def normalize_device(device_arg: Optional[str]) -> Optional[Union[int, str]]:
    if device_arg is None:
        return None
    return int(device_arg) if device_arg.isdigit() else device_arg


def format_timestamp(timestamp: datetime) -> str:
    return timestamp.strftime("%H:%M:%S.%f")[:-3]


def append_lines(out_path: Path, lines: Sequence[str]) -> None:
    if not lines:
        return
    with out_path.open("a", encoding="utf-8") as handle:
        for line in lines:
            handle.write(line + "\n")


def transcribe_audio(
    model,
    audio,
    language: Optional[str],
    chunk_start_time: datetime,
    beam_size: int,
    best_of: int,
    previous_text: bool,
) -> list[str]:
    segments, _ = model.transcribe(
        audio,
        language=language,
        vad_filter=True,
        beam_size=beam_size,
        best_of=best_of,
        temperature=0.0,
        condition_on_previous_text=previous_text,
    )

    lines: list[str] = []
    for segment in segments:
        text = segment.text.strip()
        if not text:
            continue
        segment_offset = max(0.0, float(segment.start))
        segment_time = chunk_start_time + timedelta(seconds=segment_offset)
        lines.append(f"[{format_timestamp(segment_time)}] {text}")
    return lines


def explain_portaudio_error(error: Exception) -> str:
    message = str(error)
    lowered = message.lower()
    if "permission" in lowered or "not authorized" in lowered:
        return "Microphone access was denied. Grant microphone permission to Terminal or your shell app in macOS System Settings."
    if "device" in lowered:
        return "Unable to open the requested input device. Check --device or run with --list-devices."
    return f"Audio input error: {message}"


def parse_bool(value: str) -> bool:
    lowered = value.strip().lower()
    if lowered in {"1", "true", "yes", "on"}:
        return True
    if lowered in {"0", "false", "no", "off"}:
        return False
    raise ValueError(f"Invalid boolean value: {value}")


def main() -> int:
    args = parse_args()

    if args.chunk_seconds <= 0:
        print("Error: --chunk-seconds must be greater than 0.", file=sys.stderr)
        return 2
    if args.beam_size <= 0 or args.best_of <= 0:
        print("Error: --beam-size and --best-of must be greater than 0.", file=sys.stderr)
        return 2

    try:
        previous_text = parse_bool(args.previous_text)
    except ValueError as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 2

    try:
        import numpy as np
        import sounddevice as sd
        from faster_whisper import WhisperModel
    except ModuleNotFoundError as exc:
        print(
            "Error: missing Python dependency "
            f"'{exc.name}'. Activate the venv and install faster-whisper, sounddevice, and numpy.",
            file=sys.stderr,
        )
        return 1

    if args.list_devices:
        print(sd.query_devices())
        return 0

    out_path = Path(args.output).expanduser().resolve()
    out_path.parent.mkdir(parents=True, exist_ok=True)

    language = None if args.language.lower() == "auto" else args.language
    device = normalize_device(args.device)
    samples_per_chunk = int(SAMPLE_RATE * args.chunk_seconds)
    min_flush_samples = int(SAMPLE_RATE * MIN_FLUSH_SECONDS)
    audio_queue: queue.Queue[tuple[np.ndarray, datetime]] = queue.Queue()
    stop_requested = False

    def request_stop(signum, frame) -> None:
        del signum, frame
        nonlocal stop_requested
        stop_requested = True

    def callback(indata, frames, time_info, status) -> None:
        del time_info
        if status:
            print(f"Audio status: {status}", file=sys.stderr)
        chunk_duration = frames / SAMPLE_RATE
        chunk_start_time = datetime.now() - timedelta(seconds=chunk_duration)
        audio_queue.put((indata.copy(), chunk_start_time))

    signal.signal(signal.SIGINT, request_stop)
    signal.signal(signal.SIGTERM, request_stop)

    try:
        if device is not None:
            sd.query_devices(device, "input")
    except Exception as exc:
        print(f"Error: invalid input device '{args.device}': {exc}", file=sys.stderr)
        return 2

    print("Live transcription configuration")
    print(f"  Model       : {args.model}")
    print(f"  Language    : {args.language}")
    print(f"  Chunk secs  : {args.chunk_seconds}")
    print(f"  Device      : {args.device if args.device is not None else 'default'}")
    print(f"  Compute type: {args.compute_type}")
    print(f"  Beam size   : {args.beam_size}")
    print(f"  Best of     : {args.best_of}")
    print(f"  Prev text   : {previous_text}")
    print(f"  Output file : {out_path}")
    print("Press Ctrl+C to stop.")

    try:
        print(f"Loading faster-whisper model: {args.model}")
        model = WhisperModel(args.model, device="cpu", compute_type=args.compute_type)
    except Exception as exc:
        print(f"Error: failed to load faster-whisper model '{args.model}': {exc}", file=sys.stderr)
        return 1

    audio_buffer = np.empty((0, CHANNELS), dtype=np.float32)
    audio_buffer_start_time: Optional[datetime] = None

    try:
        with sd.InputStream(
            samplerate=SAMPLE_RATE,
            channels=CHANNELS,
            dtype="float32",
            callback=callback,
            device=device,
        ):
            while not stop_requested:
                try:
                    chunk, chunk_start_time = audio_queue.get(timeout=0.25)
                except queue.Empty:
                    continue

                if audio_buffer_start_time is None:
                    audio_buffer_start_time = chunk_start_time
                audio_buffer = np.concatenate((audio_buffer, chunk), axis=0)

                while audio_buffer.shape[0] >= samples_per_chunk and audio_buffer_start_time is not None:
                    audio = audio_buffer[:samples_per_chunk, 0].copy()
                    audio_buffer = audio_buffer[samples_per_chunk:]
                    chunk_window_start = audio_buffer_start_time
                    lines = transcribe_audio(
                        model,
                        audio,
                        language,
                        chunk_window_start,
                        args.beam_size,
                        args.best_of,
                        previous_text,
                    )
                    append_lines(out_path, lines)
                    for line in lines:
                        print(line)
                    if audio_buffer.shape[0] > 0:
                        audio_buffer_start_time = chunk_window_start + timedelta(
                            seconds=samples_per_chunk / SAMPLE_RATE,
                        )
                    else:
                        audio_buffer_start_time = None
    except KeyboardInterrupt:
        stop_requested = True
    except sd.PortAudioError as exc:
        print(f"Error: {explain_portaudio_error(exc)}", file=sys.stderr)
        return 1
    except Exception as exc:
        print(f"Error: transcription failed: {exc}", file=sys.stderr)
        return 1

    if audio_buffer.shape[0] >= min_flush_samples and audio_buffer_start_time is not None:
        try:
            lines = transcribe_audio(
                model,
                audio_buffer[:, 0].copy(),
                language,
                audio_buffer_start_time,
                args.beam_size,
                args.best_of,
                previous_text,
            )
            append_lines(out_path, lines)
            for line in lines:
                print(line)
        except Exception as exc:
            print(f"Warning: failed to flush final audio chunk: {exc}", file=sys.stderr)

    print(f"Stopped. Transcript appended to: {out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
