#!/usr/bin/env python3
"""Live microphone transcription with faster-whisper."""

import argparse
import csv
import gc
import importlib.util
import math
import platform
import queue
import re
import signal
import struct
import sys
import threading
import time as time_module
import wave
from dataclasses import dataclass
from datetime import datetime, time, timedelta, timezone
from pathlib import Path
from typing import Callable, Optional, Sequence

SAMPLE_RATE = 16000
CHANNELS = 1
MIN_FLUSH_SECONDS = 1.0
INITIAL_LIVE_WINDOW_SECONDS = 1.0
LIVE_MIN_WINDOW_SECONDS = 10.0
LIVE_MAX_WINDOW_SECONDS = 120.0
LIVE_STEP_SECONDS = 1.5
CHUNK_RMS_SILENCE_THRESHOLD = 0.003
CHUNK_RMS_LOW_ENERGY_THRESHOLD = 0.008
HIGH_PASS_CUTOFF_HZ = 80.0
HIGH_PASS_VECTOR_BLOCK_SAMPLES = 512
AGC_TARGET_RMS = 0.06
AGC_MIN_GAIN = 0.25
AGC_MAX_GAIN = 8.0
AGC_BLOCK_SECONDS = 0.1
AGC_TIME_CONSTANT_SECONDS = 3.0
CLIPPING_AMPLITUDE_THRESHOLD = 0.999
CLIPPING_SAMPLE_FRACTION_THRESHOLD = 0.001
SEGMENT_AVG_LOGPROB_THRESHOLD = -1.0
SEGMENT_NO_SPEECH_THRESHOLD = 0.6
SEGMENT_COMPRESSION_RATIO_THRESHOLD = 2.4
LOW_ENERGY_SHORT_SEGMENT_MAX_WORDS = 4
FINAL_PASS_MIN_BEAM_SIZE = 8
FINAL_PASS_MIN_BEST_OF = 8
FINAL_PASS_PATIENCE = 1.5
LIVE_MAX_BEAM_SIZE = 2
LIVE_MAX_BEST_OF = 2
COMPUTE_TYPE_FALLBACK = "int8"
TRANSCRIPTION_TEMPERATURES = (0.0, 0.2, 0.4, 0.6, 0.8, 1.0)
LIVE_TEMPERATURES = (0.0,)
LIVE_REPETITION_PENALTY = 1.15
FINAL_REPETITION_PENALTY = 1.05
LIVE_NO_REPEAT_NGRAM_SIZE = 3
FINAL_NO_REPEAT_NGRAM_SIZE = 0
HALLUCINATION_SILENCE_THRESHOLD_SECONDS = 2.0
PROMPT_RESET_ON_TEMPERATURE = 0.5
STRUCTURAL_LOOP_NGRAM_SIZE = 3
STRUCTURAL_LOOP_MIN_WORDS = 12
STRUCTURAL_LOOP_MAX_SHARE = 0.30
TRAILING_HALLUCINATION_MIN_SILENCE_SECONDS = 0.5
TRAILING_HALLUCINATION_REFERENCE_AUDIO_SECONDS = 0.5
TRAILING_HALLUCINATION_MAX_LEVEL_RATIO = 0.5
KNOWN_TRAILING_HALLUCINATION_PHRASES = frozenset({
    "amaraorg",
    "like and subscribe",
    "please like and subscribe",
    "please subscribe",
    "subscribe to my channel",
    "subtitles by",
    "thank you for watching",
    "thank you so much for watching",
    "thanks for watching",
    "thanks so much for watching",
    "we will be right back",
    "well be right back",
})
KNOWN_TRAILING_HALLUCINATION_PREFIXES = (
    "subtitles by ",
)
MAX_HOTWORD_TERMS = 32
PARAKEET_MLX_MODEL = "mlx-community/parakeet-tdt-0.6b-v3"
PARAKEET_STREAM_CONTEXT = (256, 64)
PARAKEET_SUPPORTED_LANGUAGES = frozenset({
    "bg", "cs", "da", "de", "el", "en", "es", "et", "fi", "fr", "hr",
    "hu", "it", "lt", "lv", "mt", "nl", "pl", "pt", "ro", "ru", "sk",
    "sl", "sv", "uk",
})
FAST_INITIAL_LIVE_WINDOW_SECONDS = 0.5
FAST_LIVE_STEP_SECONDS = 1.0
LOCAL_AGREEMENT_PROMPT_WORDS = 32
ENDPOINT_SILENCE_SECONDS = 0.7
ENDPOINT_WORD_GAP_SECONDS = 0.7
ENDPOINT_MAX_TURN_SECONDS = 12.0
TRANSCRIPT_LINE_GAP_SECONDS = 0.7
METER_EMIT_INTERVAL_SECONDS = 0.25
AUDIO_SILENCE_WARNING_SECONDS = 30.0
BACKLOG_WARNING_SECONDS = 6.0
RESUME_GAP_TOLERANCE_SECONDS = 0.25
AUDIO_CLOCK_DRIFT_TOLERANCE_SECONDS = 1.0
SILENCE_WRITE_CHUNK_FRAMES = SAMPLE_RATE * 30
SNR_MIN_CLASS_SAMPLES = 3
SNR_MAX_HISTORY_SAMPLES = 120
SNR_SPEECH_TO_NOISE_RATIO = 2.0
SNR_RECOMMENDED_DB = 8.0
SNR_CRITICAL_DB = 3.0
SNR_ANALYSIS_WINDOW_SECONDS = 0.5
LIVE_VAD_WINDOW_SECONDS = 0.5
LIVE_VAD_PARAMETERS = {
    "threshold": 0.5,
    "neg_threshold": 0.35,
    "min_speech_duration_ms": 250,
    "min_silence_duration_ms": 400,
    "speech_pad_ms": 200,
}
DEFAULT_PATHOLOGY_HOTWORDS = (
    "Gleason, mitotic figures, pleomorphism, Ki-67, HER2, "
    "immunohistochemistry, lymphovascular invasion, perineural invasion, "
    "adenocarcinoma, squamous cell carcinoma, ductal carcinoma in situ, "
    "reflex in situ hybridization, margin, malignancy, HULA Lab, QuPath, TimeStamp"
)
PROTOCOL_FIELDS = {
    "DEVICE": 2,
    "AUDIO_CHECK_READY": 0,
    "AUDIO_CHECK_RESULT": 2,
    "AUDIO_LEVEL": 2,
    "AUDIO_CLIPPING": 1,
    "AUDIO_SILENT": 1,
    "AUDIO_RECOVERED": 0,
    "TRANSCRIPT_READY": 0,
    "RECORDING_ORIGIN": 1,
    "LIVE_MODEL_READY": 1,
    "TRANSCRIPT_UPDATED": 0,
    "TRANSCRIPT_PARTIAL": 1,
    "TURN_ENDED": 1,
    "FINALIZE_PROGRESS": 2,
    "FINALIZATION_RESULT": 1,
}
PROTOCOL_PRINT_LOCK = threading.Lock()


def format_protocol_message(kind: str, *fields: object) -> str:
    expected_fields = PROTOCOL_FIELDS.get(kind)
    if expected_fields is None:
        raise ValueError(f"Unknown transcript protocol message: {kind}")
    if len(fields) != expected_fields:
        raise ValueError(
            f"{kind} requires {expected_fields} field(s), received {len(fields)}"
        )
    normalized_fields = [str(field).replace("\t", " ").replace("\n", " ") for field in fields]
    return "\t".join((kind, *normalized_fields))


def emit_protocol_message(kind: str, *fields: object) -> None:
    with PROTOCOL_PRINT_LOCK:
        print(format_protocol_message(kind, *fields), flush=True)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Capture live microphone audio and append transcript lines to a text file.",
    )
    parser.add_argument("--output", help="Transcript output path")
    parser.add_argument("--model", default="large-v3", help="faster-whisper model name")
    parser.add_argument(
        "--live-engine",
        choices=("auto", "parakeet-mlx", "whisper"),
        default="auto",
        help="Live ASR engine; auto prefers Parakeet MLX on supported Apple Silicon systems",
    )
    parser.add_argument(
        "--parakeet-model",
        default=PARAKEET_MLX_MODEL,
        help="Hugging Face Parakeet MLX model used by the Metal live engine",
    )
    parser.add_argument("--language", default="en", help="Language code, or 'auto' for detection")
    parser.add_argument(
        "--chunk-seconds",
        type=float,
        default=10.0,
        help="Rolling live context window in seconds (minimum 10)",
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
    parser.add_argument(
        "--check-audio",
        action="store_true",
        help="Open the selected microphone and emit level messages without loading a model",
    )
    parser.add_argument(
        "--check-seconds",
        type=float,
        default=0.0,
        help="Stop --check-audio automatically after this many seconds; 0 waits until stopped",
    )
    parser.add_argument(
        "--hotwords",
        default=DEFAULT_PATHOLOGY_HOTWORDS,
        help="Comma-separated terminology used to bias live and final recognition",
    )
    lifecycle = parser.add_mutually_exclusive_group()
    lifecycle.add_argument(
        "--capture-only",
        action="store_true",
        help="Capture live audio and transcript updates, but skip the offline final pass on exit",
    )
    lifecycle.add_argument(
        "--finalize-existing",
        action="store_true",
        help="Run only the offline final pass for an existing capture without opening a microphone",
    )
    return parser.parse_args()


def sanitized_device_name(name: object) -> str:
    return str(name).replace("\t", " ").replace("\n", " ").strip()


def resolve_input_device(sd, device_arg: Optional[str]) -> Optional[int]:
    if device_arg is None or not str(device_arg).strip():
        return None
    normalized = str(device_arg).strip()
    devices = sd.query_devices()
    if normalized.isdigit():
        index = int(normalized)
        if index < 0 or index >= len(devices) or int(devices[index].get("max_input_channels", 0) or 0) <= 0:
            raise ValueError(f"saved microphone index {index} is no longer an input device")
        return index
    exact_matches = [
        index for index, device in enumerate(devices)
        if int(device.get("max_input_channels", 0) or 0) > 0
        and sanitized_device_name(device.get("name", "")) == normalized
    ]
    if exact_matches:
        return exact_matches[0]
    raise ValueError(f"saved microphone '{normalized}' is not connected")


def print_input_devices(sd) -> None:
    default_input_index = None
    default_device = getattr(sd, "default", None)
    default_indices = getattr(default_device, "device", None)
    if isinstance(default_indices, (list, tuple)) and default_indices:
        try:
            candidate = int(default_indices[0])
            if candidate >= 0:
                default_input_index = candidate
        except (TypeError, ValueError):
            default_input_index = None

    input_devices: list[tuple[str, str]] = []
    default_label = "System Default"
    for index, device in enumerate(sd.query_devices()):
        max_input_channels = int(device.get("max_input_channels", 0) or 0)
        if max_input_channels <= 0:
            continue
        name = sanitized_device_name(device.get("name", f"Input {index}"))
        if index == default_input_index:
            default_label = f"System Default ({name})"
        input_devices.append((name, f"{index} - {name}"))

    emit_protocol_message("DEVICE", "", default_label)
    for value, label in input_devices:
        emit_protocol_message("DEVICE", value, label)


def format_timestamp(timestamp: datetime) -> str:
    local_timestamp = timestamp.astimezone() if timestamp.tzinfo is not None else timestamp
    return local_timestamp.strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3]


def format_utc_timestamp(timestamp: datetime) -> str:
    localized = timestamp.astimezone() if timestamp.tzinfo is None else timestamp
    return localized.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def format_transcript_line(timestamp: datetime, text: str) -> str:
    return f"[{format_timestamp(timestamp)}] {text}"


def parse_transcript_line(line: str) -> Optional[tuple[datetime, str]]:
    match = re.match(
        r"^\[((?:\d{4}-\d{2}-\d{2}T)?\d{2}:\d{2}:\d{2}\.\d{3})\]\s+(.*)$",
        line.strip(),
    )
    if not match:
        return None
    timestamp_text, text = match.groups()
    try:
        if "T" in timestamp_text:
            parsed_timestamp = datetime.fromisoformat(timestamp_text)
        else:
            parsed_time = time.fromisoformat(timestamp_text)
            parsed_timestamp = datetime.combine(datetime.now().date(), parsed_time)
    except ValueError:
        return None
    if parsed_timestamp.tzinfo is None:
        parsed_timestamp = parsed_timestamp.astimezone()
    return parsed_timestamp.astimezone(timezone.utc), text.strip()


def write_lines(out_path: Path, lines: Sequence[str]) -> None:
    temporary_path = out_path.with_name(f"{out_path.name}.tmp")
    with temporary_path.open("w", encoding="utf-8") as handle:
        for line in lines:
            handle.write(line + "\n")
    temporary_path.replace(out_path)


def normalize_transcript_text(text: str) -> str:
    normalized = text.strip().lower()
    normalized = re.sub(r"[^\w\s]", "", normalized)
    normalized = re.sub(r"\s+", " ", normalized)
    return normalized


def normalized_words(text: str) -> list[str]:
    return normalize_transcript_text(text).split()


def common_prefix_word_count(first_words: Sequence[str], second_words: Sequence[str]) -> int:
    count = 0
    for first_word, second_word in zip(first_words, second_words):
        if first_word != second_word:
            break
        count += 1
    return count


def agreement_word(word: tuple[datetime, datetime, str]) -> str:
    normalized = normalize_transcript_text(word[2])
    return normalized or word[2].strip().lower()


def join_timed_words(words: Sequence[tuple[datetime, datetime, str]]) -> str:
    text = ""
    for _, _, piece in words:
        if not piece.strip():
            continue
        if not text:
            text = piece.strip()
        elif piece[:1].isspace() or piece.lstrip()[:1] in ",.;:!?)]}":
            text += piece
        else:
            text += " " + piece.strip()
    return re.sub(r"\s+", " ", text).strip()


def decoded_segments_to_timed_words(
    decoded_segments: Sequence[
        tuple[
            datetime,
            datetime,
            str,
            tuple[tuple[datetime, datetime, str], ...],
        ]
    ],
) -> list[tuple[datetime, datetime, str]]:
    resolved_words: list[tuple[datetime, datetime, str]] = []
    for segment_start, segment_end, text, timed_words in decoded_segments:
        if timed_words:
            resolved_words.extend(timed_words)
            continue
        pieces = text.split()
        if not pieces:
            continue
        duration_seconds = max(0.0, (segment_end - segment_start).total_seconds())
        word_duration = duration_seconds / len(pieces)
        for index, piece in enumerate(pieces):
            word_start = segment_start + timedelta(seconds=index * word_duration)
            word_end = segment_start + timedelta(seconds=(index + 1) * word_duration)
            resolved_words.append((word_start, word_end, piece))
    return resolved_words


class LocalAgreementState:
    """Commit words only after two consecutive decodes agree on their prefix."""

    def __init__(self) -> None:
        self.committed_words: list[tuple[datetime, datetime, str]] = []
        self.previous_hypothesis: list[tuple[datetime, datetime, str]] = []

    def update(
        self,
        current_hypothesis: Sequence[tuple[datetime, datetime, str]],
        force: bool = False,
    ) -> tuple[
        list[tuple[datetime, datetime, str]],
        list[tuple[datetime, datetime, str]],
    ]:
        current_words = list(current_hypothesis)
        if force:
            commit_source = current_words or self.previous_hypothesis
            commit_count = len(commit_source)
        else:
            commit_source = current_words
            commit_count = common_prefix_word_count(
                [agreement_word(word) for word in self.previous_hypothesis],
                [agreement_word(word) for word in current_words],
            )

        newly_committed = list(commit_source[:commit_count])
        self.committed_words.extend(newly_committed)
        self.previous_hypothesis = [] if force else current_words[commit_count:]
        return newly_committed, list(self.previous_hypothesis)


def group_committed_words(
    words: Sequence[tuple[datetime, datetime, str]],
) -> list[tuple[datetime, str]]:
    grouped: list[tuple[datetime, str]] = []
    current_words: list[tuple[datetime, datetime, str]] = []
    for index, word in enumerate(words):
        current_words.append(word)
        next_word = words[index + 1] if index + 1 < len(words) else None
        sentence_end = bool(re.search(r"[.!?][\"')\]]*$", word[2].strip()))
        gap_seconds = (
            (next_word[0] - word[1]).total_seconds()
            if next_word is not None
            else 0.0
        )
        if sentence_end or (next_word is not None and gap_seconds >= TRANSCRIPT_LINE_GAP_SECONDS):
            grouped.append((current_words[0][0], join_timed_words(current_words)))
            current_words = []
    if current_words:
        grouped.append((current_words[0][0], join_timed_words(current_words)))
    return grouped


def local_agreement_prompt(
    existing_entries: Sequence[tuple[datetime, str]],
    committed_words: Sequence[tuple[datetime, datetime, str]],
) -> str:
    words = normalized_words(" ".join(text for _, text in existing_entries))
    words.extend(word[2].strip() for word in committed_words if word[2].strip())
    return " ".join(words[-LOCAL_AGREEMENT_PROMPT_WORDS:])


def transcript_prefix_matches(first_text: str, second_text: str) -> bool:
    first_words = normalized_words(first_text)[:5]
    second_words = normalized_words(second_text)[:5]
    return bool(first_words) and first_words == second_words


def load_live_transcript_entries(out_path: Path) -> list[tuple[datetime, str]]:
    if not out_path.exists():
        return []
    try:
        contents = out_path.read_text(encoding="utf-8")
    except OSError:
        return []

    entries: list[tuple[datetime, str]] = []
    for line in contents.splitlines():
        parsed = parse_transcript_line(line)
        if parsed is not None:
            entries.append(parsed)
    return entries


def resolve_live_window_seconds(chunk_seconds: float) -> float:
    """Resolve the configured rolling context without silently capping it."""
    if not math.isfinite(chunk_seconds) or chunk_seconds <= 0 or chunk_seconds > LIVE_MAX_WINDOW_SECONDS:
        raise ValueError(
            f"live context must be greater than 0 and no more than {LIVE_MAX_WINDOW_SECONDS:g} seconds"
        )
    return max(chunk_seconds, LIVE_MIN_WINDOW_SECONDS)


def load_existing_capture_state(
    out_path: Path,
    raw_audio_path: Path,
    recording_start_path: Path,
    wave_audio_path: Optional[Path] = None,
) -> tuple[list[tuple[datetime, str]], Optional[datetime], Optional[datetime]]:
    """Load resumable capture state without modifying any recording artifacts."""
    live_entries = load_live_transcript_entries(out_path)
    recording_start = read_recording_start(recording_start_path)
    committed_through = None
    audio_duration = (
        wave_audio_duration_seconds(wave_audio_path)
        if wave_audio_path is not None
        else 0.0
    )
    if audio_duration <= 0:
        audio_duration = raw_audio_duration_seconds(raw_audio_path)
    if recording_start is not None and audio_duration > 0:
        committed_through = recording_start + timedelta(
            seconds=audio_duration,
        )
    return live_entries, recording_start, committed_through


def choose_live_model_candidates(final_model_name: str, language: Optional[str]) -> list[str]:
    normalized_model = final_model_name.strip().lower()
    normalized_language = (language or "").strip().lower()
    candidates: list[str] = []

    def add_candidate(name: str) -> None:
        if name and name not in candidates:
            candidates.append(name)

    if normalized_model.startswith(("tiny", "base")):
        add_candidate(final_model_name)
        add_candidate("small")
    elif normalized_model.startswith(("small", "small.en")):
        if normalized_language == "en":
            add_candidate("small.en")
        add_candidate(final_model_name)
        add_candidate("distil-small.en" if normalized_language == "en" else "small")
        add_candidate("distil-large-v2")
        add_candidate("large-v3")
    else:
        # small.en is the live default for English. Measured on the Phase 0
        # fixture by scripts/bench_live_models.py: 19.27% WER at 2.37 s per
        # 20-second window, against distil-small.en's 63.97% at 2.17 s. The
        # distilled model is kept below it only as a faster degraded fallback.
        add_candidate("small.en" if normalized_language == "en" else "small")
        add_candidate("distil-small.en" if normalized_language == "en" else "small")
        add_candidate(final_model_name)
        add_candidate("distil-large-v2")
        add_candidate("large-v3")

    return candidates


def compute_type_candidates(requested_compute_type: str) -> list[str]:
    requested = requested_compute_type.strip() or "default"
    candidates = [requested]
    if requested.lower() == "int8_float32":
        candidates.append(COMPUTE_TYPE_FALLBACK)
    return candidates


def parakeet_live_is_supported(
    language: Optional[str],
    system_name: Optional[str] = None,
    machine_name: Optional[str] = None,
    package_available: Optional[bool] = None,
) -> bool:
    """Return whether the native MLX live backend can serve this session."""
    resolved_system = system_name if system_name is not None else platform.system()
    resolved_machine = machine_name if machine_name is not None else platform.machine()
    resolved_language = (language or "").strip().lower()
    if package_available is None:
        package_available = importlib.util.find_spec("parakeet_mlx") is not None
    return (
        resolved_system == "Darwin"
        and resolved_machine == "arm64"
        and resolved_language in PARAKEET_SUPPORTED_LANGUAGES
        and package_available
    )


def resolve_live_engine(
    requested_engine: str,
    language: Optional[str],
    system_name: Optional[str] = None,
    machine_name: Optional[str] = None,
    package_available: Optional[bool] = None,
) -> str:
    """Resolve the engine without auto-selecting an unvalidated quality regression."""
    requested = requested_engine.strip().lower()
    if requested == "parakeet-mlx" and parakeet_live_is_supported(
        language,
        system_name,
        machine_name,
        package_available,
    ):
        return "parakeet-mlx"
    return "whisper"


def load_cpu_whisper_model(
    model_factory: Callable[..., object],
    model_name: str,
    compute_type: str,
) -> tuple[object, str]:
    errors: list[str] = []
    for candidate_compute_type in compute_type_candidates(compute_type):
        try:
            model = model_factory(
                model_name,
                device="cpu",
                compute_type=candidate_compute_type,
            )
            return model, candidate_compute_type
        except Exception as exc:
            errors.append(f"{candidate_compute_type}: {exc}")
    raise RuntimeError(" | ".join(errors))


@dataclass(frozen=True)
class LiveTranscriptionUpdate:
    committed_words: tuple[tuple[datetime, datetime, str], ...]
    provisional_words: tuple[tuple[datetime, datetime, str], ...]
    committed_through: Optional[datetime]


class LiveTranscriber:
    """Common interface for incremental live ASR engines."""

    engine_name = "unknown"
    model_name = "unknown"

    def accept_audio(
        self,
        audio,
        chunk_start_time: datetime,
        force: bool = False,
        context_prompt: Optional[str] = None,
    ) -> LiveTranscriptionUpdate:
        raise NotImplementedError

    def close(self) -> None:
        return None


class WhisperLiveTranscriber(LiveTranscriber):
    """LocalAgreement-backed live adapter for faster-whisper."""

    engine_name = "whisper"

    def __init__(
        self,
        model,
        model_name: str,
        language: Optional[str],
        beam_size: int,
        best_of: int,
        hotwords: Optional[str],
    ) -> None:
        self.model = model
        self.model_name = model_name
        self.language = language
        self.beam_size = beam_size
        self.best_of = best_of
        self.hotwords = hotwords
        self.agreement = LocalAgreementState()
        self.has_emission = False

    def accept_audio(
        self,
        audio,
        chunk_start_time: datetime,
        force: bool = False,
        context_prompt: Optional[str] = None,
    ) -> LiveTranscriptionUpdate:
        segments = transcribe_audio_segments(
            self.model,
            audio,
            self.language,
            chunk_start_time,
            self.beam_size,
            self.best_of,
            False,
            allow_low_energy_short_segments=not self.has_emission,
            strict_segment_filtering=True,
            hotwords=self.hotwords,
            context_prompt=context_prompt,
            audio_is_conditioned=True,
        )
        newly_committed, provisional_words = self.agreement.update(
            decoded_segments_to_timed_words(segments),
            force=force,
        )
        self.has_emission = bool(self.agreement.committed_words or provisional_words)
        committed_through = newly_committed[-1][1] if newly_committed else None
        if force and not provisional_words and committed_through is None:
            committed_through = chunk_start_time + timedelta(seconds=len(audio) / SAMPLE_RATE)
        return LiveTranscriptionUpdate(
            tuple(self.agreement.committed_words),
            tuple(provisional_words),
            committed_through,
        )

    def force_current(self, audio_end_time: datetime) -> LiveTranscriptionUpdate:
        """Commit the latest hypothesis without paying for a duplicate decode."""
        newly_committed, provisional_words = self.agreement.update([], force=True)
        committed_through = newly_committed[-1][1] if newly_committed else audio_end_time
        return LiveTranscriptionUpdate(
            tuple(self.agreement.committed_words),
            tuple(provisional_words),
            committed_through,
        )

    def reset_turn(self) -> None:
        self.agreement = LocalAgreementState()
        self.has_emission = False


def parakeet_tokens_to_timed_words(
    tokens: Sequence[object],
    stream_start_time: datetime,
) -> list[tuple[datetime, datetime, str]]:
    """Convert tokenizer pieces to monotonic word timings for transcript display."""
    resolved: list[tuple[datetime, datetime, str]] = []
    current_pieces: list[str] = []
    current_start = 0.0
    current_end = 0.0
    timeline_offset = 0.0
    previous_start = 0.0
    previous_end = 0.0

    def flush_word() -> None:
        nonlocal current_pieces
        text = "".join(current_pieces).strip()
        if text:
            resolved.append((
                stream_start_time + timedelta(seconds=current_start),
                stream_start_time + timedelta(seconds=max(current_start, current_end)),
                text,
            ))
        current_pieces = []

    for token in tokens:
        piece = str(getattr(token, "text", ""))
        if not piece:
            continue
        raw_start = max(0.0, float(getattr(token, "start", 0.0)))
        raw_end = max(raw_start, float(getattr(token, "end", raw_start)))
        candidate_start = raw_start + timeline_offset
        if candidate_start + 0.05 < previous_start:
            timeline_offset = previous_end
            candidate_start = raw_start + timeline_offset
        candidate_end = max(candidate_start, raw_end + timeline_offset)
        previous_start = candidate_start
        previous_end = max(previous_end, candidate_end)

        if piece[:1].isspace() and current_pieces:
            flush_word()
        if not current_pieces:
            current_start = candidate_start
        current_end = candidate_end
        current_pieces.append(piece)
    flush_word()
    return resolved


class ParakeetMlxLiveTranscriber(LiveTranscriber):
    """Native Apple-Silicon streaming adapter for parakeet-mlx."""

    engine_name = "parakeet-mlx"

    def __init__(
        self,
        model_name: str = PARAKEET_MLX_MODEL,
        model_loader=None,
        array_factory=None,
    ) -> None:
        if model_loader is None:
            from parakeet_mlx import from_pretrained

            model_loader = from_pretrained
        if array_factory is None:
            import mlx.core as mx

            array_factory = mx.array
        self.model_name = model_name
        self.model = model_loader(model_name)
        self.array_factory = array_factory
        self.stream_context = self.model.transcribe_stream(
            context_size=PARAKEET_STREAM_CONTEXT,
        )
        self.stream = self.stream_context.__enter__()
        self.stream_start_time: Optional[datetime] = None
        self.closed = False

    def accept_audio(
        self,
        audio,
        chunk_start_time: datetime,
        force: bool = False,
        context_prompt: Optional[str] = None,
    ) -> LiveTranscriptionUpdate:
        del context_prompt
        if self.stream_start_time is None:
            self.stream_start_time = chunk_start_time
        if len(audio):
            self.stream.add_audio(self.array_factory(audio))
        finalized_tokens = list(self.stream.finalized_tokens)
        draft_tokens = list(self.stream.draft_tokens)
        if force:
            finalized_tokens.extend(draft_tokens)
            draft_tokens = []
        committed_words = parakeet_tokens_to_timed_words(
            finalized_tokens,
            self.stream_start_time,
        )
        provisional_words = parakeet_tokens_to_timed_words(
            draft_tokens,
            self.stream_start_time,
        )
        committed_through = committed_words[-1][1] if committed_words else None
        return LiveTranscriptionUpdate(
            tuple(committed_words),
            tuple(provisional_words),
            committed_through,
        )

    def close(self) -> None:
        if not self.closed:
            self.closed = True
            self.stream_context.__exit__(None, None, None)


def audio_rms(audio) -> float:
    if audio.size == 0:
        return 0.0
    return float((audio.astype("float32") ** 2).mean() ** 0.5)


def maximum_audio_window_rms(audio, window_samples: int = SAMPLE_RATE // 4) -> float:
    if audio.size == 0 or window_samples <= 0:
        return 0.0
    return max(
        audio_rms(audio[index:index + window_samples])
        for index in range(0, audio.shape[0], window_samples)
    )


class StatefulHighPassFilter:
    """Causal one-pole high-pass filter whose state survives capture chunks."""

    def __init__(
        self,
        sample_rate: int = SAMPLE_RATE,
        cutoff_hz: float = HIGH_PASS_CUTOFF_HZ,
    ) -> None:
        self.sample_rate = sample_rate
        self.cutoff_hz = cutoff_hz
        if cutoff_hz > 0 and sample_rate > 0:
            rc_seconds = 1.0 / (2.0 * math.pi * cutoff_hz)
            sample_period_seconds = 1.0 / sample_rate
            self.alpha = rc_seconds / (rc_seconds + sample_period_seconds)
        else:
            self.alpha = 0.0
        self.previous_input = 0.0
        self.previous_output = 0.0

    def process(self, audio):
        samples = audio.astype("float32", copy=True)
        if samples.size == 0 or self.alpha <= 0:
            if samples.size:
                self.previous_input = float(samples[-1])
            return samples
        import numpy as np

        output = np.empty(samples.shape[0], dtype="float64")
        for block_start in range(0, samples.shape[0], HIGH_PASS_VECTOR_BLOCK_SAMPLES):
            block_end = min(samples.shape[0], block_start + HIGH_PASS_VECTOR_BLOCK_SAMPLES)
            block = samples[block_start:block_end].astype("float64", copy=False)
            prior_inputs = np.empty(block.shape[0], dtype="float64")
            prior_inputs[0] = self.previous_input
            if block.shape[0] > 1:
                prior_inputs[1:] = block[:-1]
            forcing = self.alpha * (block - prior_inputs)
            powers = self.alpha ** (1.0 + np.arange(block.shape[0]))
            filtered = powers * (
                self.previous_output + np.cumsum(forcing / powers)
            )
            output[block_start:block_end] = filtered
            self.previous_input = float(block[-1])
            self.previous_output = float(filtered[-1])
        return output.astype("float32")


def remove_dc_and_high_pass(
    audio,
    sample_rate: int = SAMPLE_RATE,
    cutoff_hz: float = HIGH_PASS_CUTOFF_HZ,
):
    """Return a causal high-pass-filtered copy; the filter itself removes DC."""
    return StatefulHighPassFilter(sample_rate, cutoff_hz).process(audio)


class StatefulSlowAgc:
    """Causal AGC with continuous level and gain state across calls."""

    def __init__(
        self,
        sample_rate: int = SAMPLE_RATE,
        target_rms: float = AGC_TARGET_RMS,
    ) -> None:
        self.sample_rate = sample_rate
        self.target_rms = target_rms
        self.level_squared = 0.0
        self.gain = 1.0
        self.has_speech_gain = False

    def process(self, audio):
        samples = audio.astype("float32", copy=True)
        if samples.size == 0 or self.sample_rate <= 0:
            return samples
        import numpy as np

        level_smoothing = 1.0 - math.exp(
            -1.0 / max(1.0, self.sample_rate * AGC_BLOCK_SECONDS)
        )
        gain_smoothing = 1.0 - math.exp(
            -1.0 / max(1.0, self.sample_rate * AGC_TIME_CONSTANT_SECONDS)
        )
        for index in range(samples.shape[0]):
            sample = float(samples[index])
            self.level_squared += level_smoothing * (
                sample * sample - self.level_squared
            )
            level = math.sqrt(max(0.0, self.level_squared))
            if level >= CHUNK_RMS_SILENCE_THRESHOLD:
                desired_gain = min(
                    AGC_MAX_GAIN,
                    max(AGC_MIN_GAIN, self.target_rms / level),
                )
                if not self.has_speech_gain:
                    self.gain = desired_gain
                    self.has_speech_gain = True
                else:
                    self.gain += gain_smoothing * (desired_gain - self.gain)
            samples[index] = sample * self.gain
        return np.clip(samples, -1.0, 1.0)


def apply_slow_agc(
    audio,
    sample_rate: int = SAMPLE_RATE,
    target_rms: float = AGC_TARGET_RMS,
):
    """Move speech toward the target RMS without amplifying quiet-room noise."""
    return StatefulSlowAgc(sample_rate, target_rms).process(audio)


class LiveAudioConditioner:
    """Stateful live front end applied once to each newly captured speech chunk."""

    def __init__(self) -> None:
        self.high_pass = StatefulHighPassFilter()
        self.agc = StatefulSlowAgc()

    def process(self, audio):
        return self.agc.process(self.high_pass.process(audio))


def condition_live_audio(audio):
    """Condition a decode copy; captured WAV samples must never pass through here."""
    return LiveAudioConditioner().process(audio)


def decode_saved_audio(audio_path: Path):
    """Decode a saved recording to a mono float32 array without changing the WAV."""
    from faster_whisper.audio import decode_audio

    return decode_audio(str(audio_path), sampling_rate=SAMPLE_RATE)


def clipped_sample_fraction(audio) -> float:
    if audio.size == 0:
        return 0.0
    import numpy as np
    return float(np.count_nonzero(np.abs(audio) >= CLIPPING_AMPLITUDE_THRESHOLD) / audio.size)


class AudioClippingWatchdog:
    """Report the first material clipping event in a capture session."""

    def __init__(self) -> None:
        self.reported = False

    def update(self, audio) -> Optional[float]:
        fraction = clipped_sample_fraction(audio)
        if self.reported or fraction < CLIPPING_SAMPLE_FRACTION_THRESHOLD:
            return None
        self.reported = True
        return fraction * 100.0


def looks_like_low_confidence_segment(segment) -> bool:
    avg_logprob = getattr(segment, "avg_logprob", None)
    if avg_logprob is not None and float(avg_logprob) < SEGMENT_AVG_LOGPROB_THRESHOLD:
        return True

    no_speech_prob = getattr(segment, "no_speech_prob", None)
    if no_speech_prob is not None and float(no_speech_prob) > SEGMENT_NO_SPEECH_THRESHOLD:
        return True

    compression_ratio = getattr(segment, "compression_ratio", None)
    if compression_ratio is not None and float(compression_ratio) > SEGMENT_COMPRESSION_RATIO_THRESHOLD:
        return True

    return False


def looks_like_structural_repetition_loop(text: str) -> bool:
    words = normalized_words(text)
    if len(words) < STRUCTURAL_LOOP_MIN_WORDS:
        return False

    ngram_size = STRUCTURAL_LOOP_NGRAM_SIZE
    ngram_count = len(words) - ngram_size + 1
    if ngram_count <= 0:
        return False

    counts: dict[tuple[str, ...], int] = {}
    for index in range(ngram_count):
        ngram = tuple(words[index:index + ngram_size])
        counts[ngram] = counts.get(ngram, 0) + 1
    return max(counts.values()) / ngram_count > STRUCTURAL_LOOP_MAX_SHARE


def looks_like_known_trailing_hallucination(text: str) -> bool:
    normalized = normalize_transcript_text(text)
    return (
        normalized in KNOWN_TRAILING_HALLUCINATION_PHRASES
        or any(normalized.startswith(prefix) for prefix in KNOWN_TRAILING_HALLUCINATION_PREFIXES)
    )


def final_segment_follows_audio_level_drop(previous_segment, final_segment, audio) -> bool:
    if audio is None or audio.size == 0:
        return False
    final_start_sample = max(0, min(audio.shape[0], round(float(final_segment.start) * SAMPLE_RATE)))
    final_end_sample = max(
        final_start_sample,
        min(audio.shape[0], round(float(final_segment.end) * SAMPLE_RATE)),
    )
    reference_samples = round(TRAILING_HALLUCINATION_REFERENCE_AUDIO_SECONDS * SAMPLE_RATE)
    reference_start_sample = max(0, final_start_sample - reference_samples)
    final_level = audio_rms(audio[final_start_sample:final_end_sample])
    reference_level = audio_rms(audio[reference_start_sample:final_start_sample])
    return (
        reference_level >= CHUNK_RMS_SILENCE_THRESHOLD
        and final_level <= reference_level * TRAILING_HALLUCINATION_MAX_LEVEL_RATIO
    )


def filter_trailing_hallucination_segments(segments, audio=None):
    """Drop known boilerplate only when it follows silence at the transcript tail."""
    resolved_segments = list(segments)
    if len(resolved_segments) < 2:
        return resolved_segments
    previous_segment = resolved_segments[-2]
    final_segment = resolved_segments[-1]
    silence_gap_seconds = max(
        0.0,
        float(final_segment.start) - float(previous_segment.end),
    )
    if (
        looks_like_known_trailing_hallucination(final_segment.text)
        and (
            silence_gap_seconds >= TRAILING_HALLUCINATION_MIN_SILENCE_SECONDS
            or final_segment_follows_audio_level_drop(
                previous_segment,
                final_segment,
                audio,
            )
        )
    ):
        return resolved_segments[:-1]
    return resolved_segments


def should_drop_low_energy_short_segment(text: str, chunk_rms: float) -> bool:
    if LOW_ENERGY_SHORT_SEGMENT_MAX_WORDS <= 0:
        return False
    if chunk_rms >= CHUNK_RMS_LOW_ENERGY_THRESHOLD:
        return False

    word_count = len(normalize_transcript_text(text).split())
    return 0 < word_count <= LOW_ENERGY_SHORT_SEGMENT_MAX_WORDS


def limited_hotwords(hotwords: Optional[str]) -> str:
    terms = [term.strip() for term in (hotwords or "").split(",") if term.strip()]
    return ", ".join(terms[:MAX_HOTWORD_TERMS])


def build_transcribe_kwargs(
    language: Optional[str],
    beam_size: int,
    best_of: int,
    previous_text: bool,
    final_pass: bool,
    hotwords: Optional[str] = DEFAULT_PATHOLOGY_HOTWORDS,
    context_prompt: Optional[str] = None,
) -> dict:
    resolved_beam_size = beam_size
    resolved_best_of = best_of
    patience = 1.0
    word_timestamps = True
    if final_pass:
        resolved_beam_size = max(beam_size, FINAL_PASS_MIN_BEAM_SIZE)
        resolved_best_of = max(best_of, FINAL_PASS_MIN_BEST_OF)
        patience = FINAL_PASS_PATIENCE
        word_timestamps = True
    else:
        resolved_beam_size = min(beam_size, LIVE_MAX_BEAM_SIZE)
        resolved_best_of = min(best_of, LIVE_MAX_BEST_OF)

    kwargs = {
        "language": language,
        "vad_filter": final_pass,
        "beam_size": resolved_beam_size,
        "best_of": resolved_best_of,
        "patience": patience,
        "temperature": list(
            TRANSCRIPTION_TEMPERATURES if final_pass else LIVE_TEMPERATURES
        ),
        "compression_ratio_threshold": SEGMENT_COMPRESSION_RATIO_THRESHOLD,
        "log_prob_threshold": SEGMENT_AVG_LOGPROB_THRESHOLD,
        "no_speech_threshold": SEGMENT_NO_SPEECH_THRESHOLD,
        "repetition_penalty": (
            FINAL_REPETITION_PENALTY if final_pass else LIVE_REPETITION_PENALTY
        ),
        "no_repeat_ngram_size": (
            FINAL_NO_REPEAT_NGRAM_SIZE if final_pass else LIVE_NO_REPEAT_NGRAM_SIZE
        ),
        "hallucination_silence_threshold": HALLUCINATION_SILENCE_THRESHOLD_SECONDS,
        "prompt_reset_on_temperature": PROMPT_RESET_ON_TEMPERATURE,
        "condition_on_previous_text": previous_text,
        "word_timestamps": word_timestamps,
    }
    if final_pass:
        kwargs["vad_parameters"] = LIVE_VAD_PARAMETERS
    normalized_hotwords = limited_hotwords(hotwords)
    if normalized_hotwords:
        kwargs["hotwords"] = normalized_hotwords
        if final_pass:
            kwargs["initial_prompt"] = (
                "Pathology dictation. Expected terminology: " + normalized_hotwords
            )
    if not final_pass and context_prompt and context_prompt.strip():
        kwargs["initial_prompt"] = context_prompt.strip()
    return kwargs


def transcribe_audio_segments(
    model,
    audio,
    language: Optional[str],
    chunk_start_time: datetime,
    beam_size: int,
    best_of: int,
    previous_text: bool,
    allow_low_energy_short_segments: bool = False,
    strict_segment_filtering: bool = True,
    hotwords: Optional[str] = DEFAULT_PATHOLOGY_HOTWORDS,
    context_prompt: Optional[str] = None,
    audio_is_conditioned: bool = False,
) -> list[
    tuple[
        datetime,
        datetime,
        str,
        tuple[tuple[datetime, datetime, str], ...],
    ]
]:
    chunk_rms = maximum_audio_window_rms(audio)
    if chunk_rms < CHUNK_RMS_SILENCE_THRESHOLD:
        return []

    conditioned_audio = audio if audio_is_conditioned else condition_live_audio(audio)
    segments, _ = model.transcribe(
        conditioned_audio,
        **build_transcribe_kwargs(
            language,
            beam_size,
            best_of,
            previous_text,
            final_pass=False,
            hotwords=hotwords,
            context_prompt=context_prompt,
        ),
    )
    segments = filter_trailing_hallucination_segments(segments, audio)

    entries: list[
        tuple[
            datetime,
            datetime,
            str,
            tuple[tuple[datetime, datetime, str], ...],
        ]
    ] = []
    for segment in segments:
        text = segment.text.strip()
        if not text:
            continue
        if looks_like_structural_repetition_loop(text):
            continue
        if strict_segment_filtering and looks_like_low_confidence_segment(segment):
            continue
        if not allow_low_energy_short_segments and should_drop_low_energy_short_segment(text, chunk_rms):
            continue
        segment_start_offset = max(0.0, float(segment.start))
        segment_end_offset = max(segment_start_offset, float(segment.end))
        segment_start_time = chunk_start_time + timedelta(seconds=segment_start_offset)
        segment_end_time = chunk_start_time + timedelta(seconds=segment_end_offset)
        timed_words = []
        for word in segment.words or []:
            word_text = word.word
            if not word_text.strip():
                continue
            word_start_offset = max(segment_start_offset, float(word.start))
            word_end_offset = max(word_start_offset, float(word.end))
            timed_words.append((
                chunk_start_time + timedelta(seconds=word_start_offset),
                chunk_start_time + timedelta(seconds=word_end_offset),
                word_text,
            ))
        entries.append((
            segment_start_time,
            segment_end_time,
            text,
            tuple(timed_words),
        ))
    return entries


def transcribe_audio(
    model,
    audio,
    language: Optional[str],
    chunk_start_time: datetime,
    beam_size: int,
    best_of: int,
    previous_text: bool,
    allow_low_energy_short_segments: bool = False,
    strict_segment_filtering: bool = True,
    hotwords: Optional[str] = DEFAULT_PATHOLOGY_HOTWORDS,
) -> list[tuple[datetime, str]]:
    return [
        (segment_start, text)
        for segment_start, _, text, _ in transcribe_audio_segments(
            model,
            audio,
            language,
            chunk_start_time,
            beam_size,
            best_of,
            previous_text,
            allow_low_energy_short_segments,
            strict_segment_filtering,
            hotwords,
        )
    ]


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


def pcm16_audio_bytes(audio) -> bytes:
    pcm16 = (audio.reshape(-1).clip(-1.0, 1.0) * 32767.0).astype("<i2")
    return pcm16.tobytes()


def raw_audio_duration_seconds(raw_path: Path) -> float:
    if not raw_path.exists():
        return 0.0
    bytes_per_frame = CHANNELS * 2
    if bytes_per_frame <= 0:
        return 0.0
    return raw_path.stat().st_size / bytes_per_frame / SAMPLE_RATE


def initialize_incremental_wave(wave_path: Path) -> None:
    wave_path.parent.mkdir(parents=True, exist_ok=True)
    header = struct.pack(
        "<4sI4s4sIHHIIHH4sI",
        b"RIFF", 36, b"WAVE", b"fmt ", 16, 1, CHANNELS, SAMPLE_RATE,
        SAMPLE_RATE * CHANNELS * 2, CHANNELS * 2, 16, b"data", 0,
    )
    wave_path.write_bytes(header)


def update_incremental_wave_header(handle, data_size: int) -> None:
    handle.seek(4)
    handle.write(struct.pack("<I", 36 + data_size))
    handle.seek(40)
    handle.write(struct.pack("<I", data_size))
    handle.flush()


def append_wave_bytes(wave_path: Path, pcm_bytes: bytes) -> None:
    if not wave_path.exists():
        initialize_incremental_wave(wave_path)
    with wave_path.open("r+b") as handle:
        header = handle.read(44)
        if len(header) != 44 or header[:4] != b"RIFF" or header[8:12] != b"WAVE":
            raise ValueError(f"Invalid incremental WAV capture: {wave_path}")
        handle.seek(0, 2)
        handle.write(pcm_bytes)
        data_size = max(0, handle.tell() - 44)
        update_incremental_wave_header(handle, data_size)


def append_wave_audio(wave_path: Path, audio) -> None:
    append_wave_bytes(wave_path, pcm16_audio_bytes(audio))


def append_wave_silence(wave_path: Path, frame_count: int) -> None:
    remaining_frames = max(0, frame_count)
    silence = b"\x00" * (CHANNELS * 2 * min(SILENCE_WRITE_CHUNK_FRAMES, max(1, remaining_frames)))
    while remaining_frames > 0:
        frames_to_write = min(remaining_frames, SILENCE_WRITE_CHUNK_FRAMES)
        append_wave_bytes(wave_path, silence[:frames_to_write * CHANNELS * 2])
        remaining_frames -= frames_to_write


def wave_audio_duration_seconds(wave_path: Path) -> float:
    if not wave_path.exists() or wave_path.stat().st_size < 44:
        return 0.0
    try:
        with wave.open(str(wave_path), "rb") as handle:
            return handle.getnframes() / max(1, handle.getframerate())
    except (OSError, wave.Error):
        return 0.0


def is_valid_capture_wave(wave_path: Path) -> bool:
    if not wave_path.exists() or wave_path.stat().st_size < 44:
        return False
    try:
        with wave.open(str(wave_path), "rb") as handle:
            return (
                handle.getnchannels() == CHANNELS
                and handle.getsampwidth() == 2
                and handle.getframerate() == SAMPLE_RATE
            )
    except (OSError, wave.Error):
        return False


def prepare_incremental_wave(raw_path: Path, wave_path: Path) -> None:
    """Migrate legacy raw capture once, then keep only the crash-playable WAV."""
    if is_valid_capture_wave(wave_path):
        raw_path.unlink(missing_ok=True)
        return
    if wave_path.exists():
        wave_path.unlink()
    if raw_path.exists() and raw_path.stat().st_size > 0:
        if not export_raw_audio_to_wave(raw_path, wave_path):
            raise OSError(f"Could not migrate legacy raw recording {raw_path}")
        raw_path.unlink(missing_ok=True)


def export_raw_audio_to_wave(raw_path: Path, wave_path: Path) -> bool:
    if not raw_path.exists() or raw_path.stat().st_size == 0:
        return False

    wrote_audio = False
    with raw_path.open("rb") as source, wave.open(str(wave_path), "wb") as handle:
        handle.setnchannels(CHANNELS)
        handle.setsampwidth(2)
        handle.setframerate(SAMPLE_RATE)
        while audio_bytes := source.read(1024 * 1024):
            handle.writeframesraw(audio_bytes)
            wrote_audio = True
    if not wrote_audio:
        wave_path.unlink(missing_ok=True)
        return False
    return True


def read_recording_start(start_path: Path) -> Optional[datetime]:
    if not start_path.exists():
        return None
    try:
        parsed = datetime.fromisoformat(start_path.read_text(encoding="utf-8").strip().replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            parsed = parsed.astimezone()
        return parsed.astimezone(timezone.utc)
    except (OSError, ValueError):
        return None


def write_recording_start(start_path: Path, timestamp: datetime) -> None:
    start_path.write_text(format_utc_timestamp(timestamp), encoding="utf-8")


def transcribe_saved_audio_with_timings(
    model,
    audio_path: Path,
    language: Optional[str],
    recording_start_time: datetime,
    beam_size: int,
    best_of: int,
    previous_text: bool,
    hotwords: Optional[str] = DEFAULT_PATHOLOGY_HOTWORDS,
    progress_callback=None,
) -> tuple[list[str], list[dict], list[dict]]:
    raw_audio = decode_saved_audio(audio_path)
    conditioned_audio = condition_live_audio(raw_audio)
    segments, transcription_info = model.transcribe(
        conditioned_audio,
        **build_transcribe_kwargs(
            language,
            beam_size,
            best_of,
            previous_text,
            final_pass=True,
            hotwords=hotwords,
        ),
    )
    segments = filter_trailing_hallucination_segments(segments, raw_audio)

    duration_seconds = float(getattr(transcription_info, "duration", 0.0) or 0.0)
    if duration_seconds <= 0:
        duration_seconds = wave_audio_duration_seconds(audio_path)
    if progress_callback is not None:
        progress_callback(0.0, duration_seconds)

    lines: list[str] = []
    segment_rows: list[dict] = []
    word_rows: list[dict] = []
    for segment_index, segment in enumerate(segments):
        if progress_callback is not None:
            progress_callback(min(float(segment.end), duration_seconds or float(segment.end)), duration_seconds)
        text = segment.text.strip()
        if not text:
            continue
        if looks_like_structural_repetition_loop(text):
            continue
        if looks_like_low_confidence_segment(segment):
            continue
        segment_start_offset = max(0.0, float(segment.start))
        segment_end_offset = max(segment_start_offset, float(segment.end))
        segment_time = recording_start_time + timedelta(seconds=segment_start_offset)
        segment_end_time = recording_start_time + timedelta(seconds=segment_end_offset)
        lines.append(f"[{format_timestamp(segment_time)}] {text}")
        segment_rows.append({
            "segment_index": segment_index,
            "text": text,
            "start_utc": format_utc_timestamp(segment_time),
            "end_utc": format_utc_timestamp(segment_end_time),
            "start_ms": round(segment_start_offset * 1000),
            "end_ms": round(segment_end_offset * 1000),
        })
        for word_index, word in enumerate(segment.words or []):
            word_text = str(word.word)
            if not word_text.strip():
                continue
            word_start_offset = max(segment_start_offset, float(word.start))
            word_end_offset = max(word_start_offset, float(word.end))
            word_rows.append({
                "segment_index": segment_index,
                "word_index": word_index,
                "word": word_text.strip(),
                "start_utc": format_utc_timestamp(
                    recording_start_time + timedelta(seconds=word_start_offset)
                ),
                "end_utc": format_utc_timestamp(
                    recording_start_time + timedelta(seconds=word_end_offset)
                ),
                "start_ms": round(word_start_offset * 1000),
                "end_ms": round(word_end_offset * 1000),
            })
    if progress_callback is not None and duration_seconds > 0:
        progress_callback(duration_seconds, duration_seconds)
    return lines, segment_rows, word_rows


def transcribe_saved_audio(
    model,
    audio_path: Path,
    language: Optional[str],
    recording_start_time: datetime,
    beam_size: int,
    best_of: int,
    previous_text: bool,
    hotwords: Optional[str] = DEFAULT_PATHOLOGY_HOTWORDS,
) -> list[str]:
    lines, _, _ = transcribe_saved_audio_with_timings(
        model,
        audio_path,
        language,
        recording_start_time,
        beam_size,
        best_of,
        previous_text,
        hotwords,
    )
    return lines


def write_csv_rows(path: Path, fieldnames: Sequence[str], rows: Sequence[dict]) -> None:
    temporary_path = path.with_name(f"{path.name}.tmp")
    with temporary_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)
    temporary_path.replace(path)


def build_live_fallback_segment_rows(
    lines: Sequence[str],
    recording_start_time: datetime,
) -> list[dict]:
    rows = []
    for segment_index, line in enumerate(lines):
        parsed = parse_transcript_line(line)
        if parsed is None:
            continue
        timestamp, text = parsed
        start_ms = max(0, round((timestamp - recording_start_time).total_seconds() * 1000))
        rows.append({
            "segment_index": segment_index,
            "text": text,
            "start_utc": format_utc_timestamp(timestamp),
            "end_utc": format_utc_timestamp(timestamp),
            "start_ms": start_ms,
            "end_ms": start_ms,
        })
    return rows


def has_suspicious_transcript_repetition(lines: Sequence[str]) -> bool:
    normalized_lines = []
    for line in lines:
        parsed = parse_transcript_line(line)
        text = parsed[1] if parsed is not None else line
        normalized = normalize_transcript_text(text)
        if normalized:
            normalized_lines.append(normalized)

    if len(normalized_lines) < 6:
        return False

    counts: dict[str, int] = {}
    for line in normalized_lines:
        counts[line] = counts.get(line, 0) + 1
    most_frequent_count = max(counts.values())
    return most_frequent_count >= 4 and most_frequent_count / len(normalized_lines) >= 0.4


def drop_repeated_final_segments(
    lines: Sequence[str],
    segment_rows: Sequence[dict],
    word_rows: Sequence[dict],
) -> tuple[list[str], list[dict], list[dict], int]:
    """Keep the first offending segment instead of discarding a whole final pass."""
    resolved_lines = list(lines)
    resolved_segment_rows = list(segment_rows)
    resolved_word_rows = list(word_rows)
    if not has_suspicious_transcript_repetition(resolved_lines):
        return resolved_lines, resolved_segment_rows, resolved_word_rows, 0

    normalized = []
    for line in resolved_lines:
        parsed = parse_transcript_line(line)
        text = parsed[1] if parsed is not None else line
        normalized.append(normalize_transcript_text(text))
    counts = {text: normalized.count(text) for text in set(normalized) if text}
    offenders = {
        text for text, count in counts.items()
        if count >= 4 and count / max(1, len(normalized)) >= 0.4
    }
    seen: set[str] = set()
    kept_positions = []
    for position, text in enumerate(normalized):
        if text in offenders and text in seen:
            continue
        kept_positions.append(position)
        seen.add(text)

    kept_lines = [resolved_lines[position] for position in kept_positions]
    kept_segment_rows = [
        resolved_segment_rows[position]
        for position in kept_positions
        if position < len(resolved_segment_rows)
    ]
    kept_segment_indexes = {
        row.get("segment_index") for row in kept_segment_rows
    }
    kept_word_rows = [
        row for row in resolved_word_rows
        if row.get("segment_index") in kept_segment_indexes
    ]
    return (
        kept_lines,
        kept_segment_rows,
        kept_word_rows,
        len(resolved_lines) - len(kept_lines),
    )


def preserve_matching_live_timestamps(
    final_lines: Sequence[str],
    live_lines: Sequence[str],
) -> list[str]:
    live_entries = [
        parsed for line in live_lines
        if (parsed := parse_transcript_line(line)) is not None
    ]
    next_live_index = 0
    resolved_lines: list[str] = []

    for line in final_lines:
        final_entry = parse_transcript_line(line)
        if final_entry is None:
            resolved_lines.append(line)
            continue

        final_time, final_text = final_entry
        resolved_time = final_time
        for live_index in range(next_live_index, len(live_entries)):
            live_time, live_text = live_entries[live_index]
            if transcript_prefix_matches(live_text, final_text):
                resolved_time = live_time
                next_live_index = live_index + 1
                break
        resolved_lines.append(format_transcript_line(resolved_time, final_text))

    return resolved_lines


class AudioSilenceWatchdog:
    """Emit one silence warning per quiet stretch and a recovery event afterward."""

    def __init__(
        self,
        threshold: float = CHUNK_RMS_SILENCE_THRESHOLD,
        warning_seconds: float = AUDIO_SILENCE_WARNING_SECONDS,
    ) -> None:
        self.threshold = threshold
        self.warning_seconds = warning_seconds
        self.quiet_since: Optional[float] = None
        self.warning_active = False

    def update(self, rms: float, now_monotonic: float) -> list[tuple[str, tuple[object, ...]]]:
        messages: list[tuple[str, tuple[object, ...]]] = []
        if rms >= self.threshold:
            if self.warning_active:
                messages.append(("AUDIO_RECOVERED", ()))
            self.quiet_since = None
            self.warning_active = False
            return messages

        if self.quiet_since is None:
            self.quiet_since = now_monotonic
        quiet_seconds = max(0.0, now_monotonic - self.quiet_since)
        if quiet_seconds >= self.warning_seconds and not self.warning_active:
            self.warning_active = True
            messages.append(("AUDIO_SILENT", (f"{quiet_seconds:.1f}",)))
        return messages


class SignalToNoiseEstimator:
    """Estimate signal-to-noise ratio from VAD-classified speech and silence levels."""

    def __init__(
        self,
        min_class_samples: int = SNR_MIN_CLASS_SAMPLES,
        max_history_samples: int = SNR_MAX_HISTORY_SAMPLES,
    ) -> None:
        self.min_class_samples = min_class_samples
        self.max_history_samples = max_history_samples
        self.noise_levels: list[float] = []
        self.speech_levels: list[float] = []

    @staticmethod
    def _median(values: list[float]) -> float:
        ordered = sorted(values)
        middle = len(ordered) // 2
        if len(ordered) % 2:
            return ordered[middle]
        return (ordered[middle - 1] + ordered[middle]) / 2.0

    def noise_floor(self) -> Optional[float]:
        if not self.noise_levels:
            return None
        return self._median(self.noise_levels)

    def update(self, rms: float, vad_active: Optional[bool] = None) -> tuple[Optional[float], str]:
        level = max(0.0, float(rms))
        if vad_active is None:
            noise_floor = self.noise_floor()
            speech_gate = CHUNK_RMS_SILENCE_THRESHOLD
            if noise_floor is not None:
                speech_gate = max(speech_gate, noise_floor * SNR_SPEECH_TO_NOISE_RATIO)
            vad_active = level >= speech_gate
        history = self.speech_levels if vad_active else self.noise_levels
        history.append(level)
        del history[:-self.max_history_samples]
        return self.current()

    def current(self) -> tuple[Optional[float], str]:
        if (
            len(self.noise_levels) < self.min_class_samples
            or len(self.speech_levels) < self.min_class_samples
        ):
            return None, "calibrating"
        noise_floor = max(1e-9, self._median(self.noise_levels))
        speech_level = max(noise_floor, self._median(self.speech_levels))
        snr_db = max(0.0, min(60.0, 20.0 * math.log10(speech_level / noise_floor)))
        if snr_db < SNR_CRITICAL_DB:
            return snr_db, "critical"
        if snr_db < SNR_RECOMMENDED_DB:
            return snr_db, "low"
        return snr_db, "good"


def contains_speech(audio) -> bool:
    """Return whether Silero VAD finds speech in an analysis window."""
    from faster_whisper.vad import VadOptions, get_speech_timestamps

    return bool(get_speech_timestamps(
        audio,
        VadOptions(
            threshold=LIVE_VAD_PARAMETERS["threshold"],
            neg_threshold=LIVE_VAD_PARAMETERS["neg_threshold"],
            min_speech_duration_ms=100,
            min_silence_duration_ms=100,
            speech_pad_ms=0,
        ),
        sampling_rate=SAMPLE_RATE,
    ))


def should_buffer_live_audio(audio, speech_detector=contains_speech) -> bool:
    """Gate a captured block before it enters the live decoder's moving window."""
    if audio.size == 0:
        return False
    return bool(speech_detector(audio))


class SpeechEndpointState:
    """Layered live endpoint over capture time, independent of decoder buffering."""

    def __init__(
        self,
        silence_seconds: float = ENDPOINT_SILENCE_SECONDS,
        word_gap_seconds: float = ENDPOINT_WORD_GAP_SECONDS,
        max_turn_seconds: float = ENDPOINT_MAX_TURN_SECONDS,
    ) -> None:
        self.silence_seconds = silence_seconds
        self.word_gap_seconds = word_gap_seconds
        self.max_turn_seconds = max_turn_seconds
        self.turn_started_at: Optional[datetime] = None
        self.silence_started_at: Optional[datetime] = None
        self.latest_audio_end: Optional[datetime] = None

    def observe(
        self,
        chunk_start: datetime,
        chunk_duration_seconds: float,
        speech_active: bool,
    ) -> None:
        chunk_end = chunk_start + timedelta(seconds=max(0.0, chunk_duration_seconds))
        self.latest_audio_end = chunk_end
        if speech_active:
            if self.turn_started_at is None:
                self.turn_started_at = chunk_start
            self.silence_started_at = None
        elif self.turn_started_at is not None and self.silence_started_at is None:
            self.silence_started_at = chunk_start

    def endpoint_reason(
        self,
        last_decoded_word_end: Optional[datetime],
        include_word_gap: bool = True,
        decoded_audio_end: Optional[datetime] = None,
    ) -> Optional[str]:
        if self.turn_started_at is None or self.latest_audio_end is None:
            return None
        if self.silence_started_at is not None and (
            self.latest_audio_end - self.silence_started_at
        ).total_seconds() >= self.silence_seconds:
            return "silence"
        word_gap_clock = decoded_audio_end or self.latest_audio_end
        if include_word_gap and last_decoded_word_end is not None and (
            word_gap_clock - last_decoded_word_end
        ).total_seconds() >= self.word_gap_seconds:
            return "word-gap"
        if (
            self.latest_audio_end - self.turn_started_at
        ).total_seconds() >= self.max_turn_seconds:
            return "hard-cap"
        return None

    def reset(self) -> None:
        self.turn_started_at = None
        self.silence_started_at = None


class SignalQualityAnalyzer:
    """Buffer audio and update SNR only after real voice-activity classification."""

    def __init__(self, speech_detector=contains_speech) -> None:
        import numpy as np

        self.estimator = SignalToNoiseEstimator()
        self.speech_detector = speech_detector
        self.window_samples = round(SNR_ANALYSIS_WINDOW_SECONDS * SAMPLE_RATE)
        self.pending_audio = np.empty(0, dtype=np.float32)

    def update(self, audio) -> Optional[tuple[Optional[float], str]]:
        import numpy as np

        self.pending_audio = np.concatenate((self.pending_audio, audio.astype("float32", copy=False)))
        result = None
        while self.pending_audio.shape[0] >= self.window_samples:
            window = self.pending_audio[:self.window_samples]
            self.pending_audio = self.pending_audio[self.window_samples:]
            result = self.estimator.update(
                audio_rms(window),
                vad_active=self.speech_detector(window),
            )
        return result


def emit_signal_quality(result: tuple[Optional[float], str]) -> None:
    snr_db, state = result
    emit_protocol_message("AUDIO_LEVEL", f"{snr_db:.2f}" if snr_db is not None else "-1", state)


def resolve_or_fallback_input_device(sd, device_arg: Optional[str]) -> Optional[int]:
    try:
        return resolve_input_device(sd, device_arg)
    except ValueError as exc:
        print(f"Warning: {exc}; using the system default microphone.", file=sys.stderr, flush=True)
        return None


def run_audio_check(sd, np, device: Optional[int], check_seconds: float) -> int:
    """Run the same input path as recording, without importing or loading Whisper."""
    stop_event = threading.Event()
    watchdog = AudioSilenceWatchdog()
    clipping_watchdog = AudioClippingWatchdog()
    signal_analyzer = SignalQualityAnalyzer()
    signal_queue = queue.Queue()
    last_meter_emit = 0.0

    def request_check_stop(signum, frame) -> None:
        del signum, frame
        stop_event.set()

    def audio_check_callback(indata, frames, time_info, status) -> None:
        del frames, time_info
        if status:
            print(f"Audio status: {status}", file=sys.stderr, flush=True)
        rms = audio_rms(indata[:, 0])
        clipped_percent = clipping_watchdog.update(indata[:, 0])
        if clipped_percent is not None:
            emit_protocol_message("AUDIO_CLIPPING", f"{clipped_percent:.3f}")
        now_monotonic = time_module.monotonic()
        for kind, fields in watchdog.update(rms, now_monotonic):
            emit_protocol_message(kind, *fields)
        signal_queue.put(indata[:, 0].copy())

    signal.signal(signal.SIGINT, request_check_stop)
    signal.signal(signal.SIGTERM, request_check_stop)
    started = time_module.monotonic()
    try:
        with sd.InputStream(
            samplerate=SAMPLE_RATE,
            channels=CHANNELS,
            dtype="float32",
            callback=audio_check_callback,
            device=device,
        ):
            emit_protocol_message("AUDIO_CHECK_READY")
            while not stop_event.wait(0.05):
                while True:
                    try:
                        signal_audio = signal_queue.get_nowait()
                    except queue.Empty:
                        break
                    result = signal_analyzer.update(signal_audio)
                    now_monotonic = time_module.monotonic()
                    if result is not None and now_monotonic - last_meter_emit >= METER_EMIT_INTERVAL_SECONDS:
                        emit_signal_quality(result)
                        last_meter_emit = now_monotonic
                if check_seconds > 0 and time_module.monotonic() - started >= check_seconds:
                    break
    except KeyboardInterrupt:
        pass
    except Exception as exc:
        print(f"Error: {explain_portaudio_error(exc)}", file=sys.stderr, flush=True)
        return 1

    snr_db, result = signal_analyzer.estimator.current()
    emit_protocol_message(
        "AUDIO_CHECK_RESULT",
        f"{snr_db:.2f}" if snr_db is not None else "-1",
        result,
    )
    return 0


def finalize_existing_capture(
    whisper_model_class,
    args: argparse.Namespace,
    out_path: Path,
    raw_audio_wave_path: Path,
    recording_start_time: Optional[datetime],
    language: Optional[str],
    previous_text: bool,
    reusable_model=None,
    reusable_model_name: Optional[str] = None,
) -> int:
    """Regenerate the final transcript from an existing capture and emit one terminal result."""
    live_backup_path = out_path.with_name(f"{out_path.stem}_live{out_path.suffix}")
    segment_timing_path = out_path.with_name(f"{out_path.stem}_segments.csv")
    word_timing_path = out_path.with_name(f"{out_path.stem}_words.csv")
    finalization_result = "no-audio"
    finalization_failed = False

    if recording_start_time is not None:
        try:
            if wave_audio_duration_seconds(raw_audio_wave_path) > 0:
                final_model = reusable_model
                if final_model is None or reusable_model_name != args.model:
                    print(f"Loading final faster-whisper model: {args.model}")
                    final_model = None
                    gc.collect()
                    final_model, final_compute_type = load_cpu_whisper_model(
                        whisper_model_class,
                        args.model,
                        args.compute_type,
                    )
                    if final_compute_type != args.compute_type:
                        print(
                            "Warning: final model compute type "
                            f"'{args.compute_type}' is unavailable; using "
                            f"'{final_compute_type}'.",
                            file=sys.stderr,
                        )

                def report_finalization_progress(completed_seconds: float, total_seconds: float) -> None:
                    emit_protocol_message(
                        "FINALIZE_PROGRESS",
                        f"{max(0.0, completed_seconds):.3f}",
                        f"{max(0.0, total_seconds):.3f}",
                    )

                final_lines, segment_rows, word_rows = transcribe_saved_audio_with_timings(
                    final_model,
                    raw_audio_wave_path,
                    language,
                    recording_start_time,
                    args.beam_size,
                    args.best_of,
                    previous_text,
                    args.hotwords,
                    report_finalization_progress,
                )
                (
                    final_lines,
                    segment_rows,
                    word_rows,
                    dropped_repeated_segments,
                ) = drop_repeated_final_segments(final_lines, segment_rows, word_rows)
                if dropped_repeated_segments:
                    print(
                        "Warning: dropped "
                        f"{dropped_repeated_segments} repeated final segment(s); "
                        "the remaining offline transcript was preserved.",
                        file=sys.stderr,
                    )
                if final_lines:
                    existing_lines = []
                    if out_path.exists():
                        existing_text = out_path.read_text(encoding="utf-8")
                        existing_lines = existing_text.splitlines()
                        live_backup_path.write_text(existing_text, encoding="utf-8")
                    final_lines = preserve_matching_live_timestamps(final_lines, existing_lines)
                    write_csv_rows(
                        segment_timing_path,
                        ("segment_index", "text", "start_utc", "end_utc", "start_ms", "end_ms"),
                        segment_rows,
                    )
                    write_csv_rows(
                        word_timing_path,
                        ("segment_index", "word_index", "word", "start_utc", "end_utc", "start_ms", "end_ms"),
                        word_rows,
                    )
                    write_lines(out_path, final_lines)
                    finalization_result = "final"
                    print(f"Final transcript regenerated from full audio: {out_path}")
                else:
                    finalization_result = "live-fallback-empty"
                    print(
                        "Warning: final offline transcript pass produced no text; keeping live transcript.",
                        file=sys.stderr,
                    )
            else:
                finalization_result = "no-audio"
                print("Warning: no saved audio available for final transcript pass.", file=sys.stderr)
        except Exception as exc:
            finalization_result = "failed"
            finalization_failed = True
            print(f"Warning: failed final offline transcript pass: {exc}", file=sys.stderr)

    if finalization_result.startswith("live-fallback") and out_path.exists():
        try:
            fallback_lines = out_path.read_text(encoding="utf-8").splitlines()
            write_csv_rows(
                segment_timing_path,
                ("segment_index", "text", "start_utc", "end_utc", "start_ms", "end_ms"),
                build_live_fallback_segment_rows(
                    fallback_lines,
                    recording_start_time or datetime.now(timezone.utc),
                ),
            )
            write_csv_rows(
                word_timing_path,
                ("segment_index", "word_index", "word", "start_utc", "end_utc", "start_ms", "end_ms"),
                (),
            )
        except OSError as exc:
            finalization_result = "failed"
            finalization_failed = True
            print(f"Warning: failed to save fallback transcript timings: {exc}", file=sys.stderr)

    emit_protocol_message("FINALIZATION_RESULT", finalization_result)
    print(f"Stopped. Transcript available at: {out_path}")
    return 1 if finalization_failed else 0


def main() -> int:
    args = parse_args()

    if not args.list_devices and not args.check_audio and not args.output:
        print("Error: --output is required unless --list-devices or --check-audio is used.", file=sys.stderr)
        return 2
    if args.finalize_existing and (args.list_devices or args.check_audio):
        print("Error: --finalize-existing cannot be combined with audio-device commands.", file=sys.stderr)
        return 2

    try:
        live_window_seconds = resolve_live_window_seconds(args.chunk_seconds)
    except ValueError as exc:
        print(f"Error: --chunk-seconds {exc}.", file=sys.stderr)
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
    except ModuleNotFoundError as exc:
        print(
            "Error: missing Python dependency "
            f"'{exc.name}'. Activate the venv and install sounddevice and numpy.",
            file=sys.stderr,
        )
        return 1

    if args.list_devices:
        print_input_devices(sd)
        return 0

    device = None
    if not args.finalize_existing:
        device = resolve_or_fallback_input_device(sd, args.device)
        try:
            sd.check_input_settings(
                device=device,
                channels=CHANNELS,
                samplerate=SAMPLE_RATE,
                dtype="float32",
            )
        except Exception as exc:
            print(f"Error: invalid input device '{args.device}': {exc}", file=sys.stderr)
            return 2

        if args.check_audio:
            return run_audio_check(sd, np, device, max(0.0, args.check_seconds))

    try:
        from faster_whisper import WhisperModel
    except ModuleNotFoundError as exc:
        print(
            "Error: missing Python dependency "
            f"'{exc.name}'. Activate the venv and install faster-whisper.",
            file=sys.stderr,
        )
        return 1

    out_path = Path(args.output).expanduser().resolve()
    out_path.parent.mkdir(parents=True, exist_ok=True)
    raw_audio_path = out_path.with_name(f"{out_path.stem}_audio.raw")
    raw_audio_wave_path = out_path.with_name(f"{out_path.stem}_audio.wav")
    recording_start_path = out_path.with_name(f"{out_path.stem}_audio.start.txt")
    try:
        prepare_incremental_wave(raw_audio_path, raw_audio_wave_path)
    except OSError as exc:
        print(f"Error: could not prepare the recording file: {exc}", file=sys.stderr)
        return 1

    language = None if args.language.lower() == "auto" else args.language
    if args.finalize_existing:
        recording_start_time = read_recording_start(recording_start_path)
        return finalize_existing_capture(
            WhisperModel,
            args,
            out_path,
            raw_audio_wave_path,
            recording_start_time,
            language,
            previous_text,
        )

    live_model_candidates = choose_live_model_candidates(args.model, language)
    live_model_name = live_model_candidates[0]
    initial_live_window_seconds = min(FAST_INITIAL_LIVE_WINDOW_SECONDS, args.chunk_seconds, INITIAL_LIVE_WINDOW_SECONDS)
    step_seconds = min(FAST_LIVE_STEP_SECONDS, live_window_seconds, LIVE_STEP_SECONDS)
    initial_samples_per_chunk = int(SAMPLE_RATE * initial_live_window_seconds)
    min_flush_samples = int(SAMPLE_RATE * MIN_FLUSH_SECONDS)
    audio_queue: queue.Queue[tuple[np.ndarray, datetime]] = queue.Queue()
    stop_requested = False
    (
        live_transcript_entries,
        recording_start_time,
        committed_live_audio_through,
    ) = load_existing_capture_state(
        out_path,
        raw_audio_path,
        recording_start_path,
        raw_audio_wave_path,
    )
    existing_live_transcript_entries = list(live_transcript_entries)
    has_live_emission = bool(live_transcript_entries)
    last_partial_text = ""
    last_meter_emit_time = 0.0
    last_backlog_warning_time = 0.0
    last_live_decode_end_time: Optional[datetime] = None
    resume_gap_checked = False
    stream_time_anchor: Optional[float] = None
    stream_wall_anchor: Optional[datetime] = None
    silence_watchdog = AudioSilenceWatchdog()
    clipping_watchdog = AudioClippingWatchdog()
    signal_analyzer = SignalQualityAnalyzer()
    live_audio_conditioner = LiveAudioConditioner()
    endpoint_state = SpeechEndpointState()
    last_decoded_word_end: Optional[datetime] = None
    recording_origin_emitted = False

    def request_stop(signum, frame) -> None:
        del signum, frame
        nonlocal stop_requested
        stop_requested = True

    def callback(indata, frames, time_info, status) -> None:
        nonlocal stream_time_anchor, stream_wall_anchor
        if status:
            print(f"Audio status: {status}", file=sys.stderr)
        chunk_duration = frames / SAMPLE_RATE
        fallback_chunk_start_time = datetime.now(timezone.utc) - timedelta(seconds=chunk_duration)
        chunk_start_time = fallback_chunk_start_time
        adc_time = getattr(time_info, "inputBufferAdcTime", None)
        if adc_time is not None:
            try:
                adc_timestamp = float(adc_time)
                if stream_time_anchor is None or stream_wall_anchor is None:
                    stream_time_anchor = adc_timestamp
                    stream_wall_anchor = fallback_chunk_start_time
                candidate_start_time = stream_wall_anchor + timedelta(seconds=adc_timestamp - stream_time_anchor)
                if abs((candidate_start_time - fallback_chunk_start_time).total_seconds()) > AUDIO_CLOCK_DRIFT_TOLERANCE_SECONDS:
                    stream_time_anchor = adc_timestamp
                    stream_wall_anchor = fallback_chunk_start_time
                    chunk_start_time = fallback_chunk_start_time
                else:
                    chunk_start_time = candidate_start_time
            except (TypeError, ValueError):
                chunk_start_time = fallback_chunk_start_time
        chunk_rms = audio_rms(indata[:, 0])
        clipped_percent = clipping_watchdog.update(indata[:, 0])
        if clipped_percent is not None:
            emit_protocol_message("AUDIO_CLIPPING", f"{clipped_percent:.3f}")
        now_monotonic = time_module.monotonic()
        for kind, fields in silence_watchdog.update(chunk_rms, now_monotonic):
            emit_protocol_message(kind, *fields)
        audio_queue.put((indata.copy(), chunk_start_time))

    preferred_live_engine = resolve_live_engine(
        args.live_engine,
        language,
    )
    print("Live transcription configuration")
    print(f"  Live engine : {preferred_live_engine}")
    print(f"  Live model  : {args.parakeet_model if preferred_live_engine == 'parakeet-mlx' else live_model_name}")
    print(f"  Live fallbacks: {', '.join(live_model_candidates)}")
    print(f"  Final model : {args.model}")
    print(f"  Language    : {args.language}")
    print(f"  Live max turn: {ENDPOINT_MAX_TURN_SECONDS}")
    print(f"  First live  : {initial_live_window_seconds}")
    print(f"  Live step   : {step_seconds}")
    print(f"  Agreement prompt words: {LOCAL_AGREEMENT_PROMPT_WORDS}")
    print(f"  High-pass   : {HIGH_PASS_CUTOFF_HZ:g} Hz (live only)")
    print(f"  AGC target  : {AGC_TARGET_RMS:g} RMS, max {AGC_MAX_GAIN:g}x (live only)")
    print(f"  Device      : {args.device if args.device is not None else 'default'}")
    print(f"  Compute type: {args.compute_type}")
    print(f"  Live beam   : {min(args.beam_size, LIVE_MAX_BEAM_SIZE)}")
    print(f"  Live best   : {min(args.best_of, LIVE_MAX_BEST_OF)}")
    print("  Prev text   : live=off, final=on" if previous_text else "  Prev text   : live=off, final=off")
    print(
        "  Initial prompt: live=off, final=on"
        if limited_hotwords(args.hotwords)
        else "  Initial prompt: live=off, final=off"
    )
    print(f"  Final beam  : {max(args.beam_size, FINAL_PASS_MIN_BEAM_SIZE)}")
    print(f"  Final best  : {max(args.best_of, FINAL_PASS_MIN_BEST_OF)}")
    print(f"  Output file : {out_path}")
    print(f"  Audio file  : {raw_audio_wave_path}")
    print("Press Ctrl+C to stop.")

    audio_buffer = np.empty((0, CHANNELS), dtype=np.float32)
    audio_buffer_start_time: Optional[datetime] = None
    live_transcriber: Optional[LiveTranscriber] = None
    reusable_whisper_model = None
    live_compute_type: Optional[str] = None

    def trim_live_audio_buffer_through(committed_through: datetime) -> None:
        nonlocal audio_buffer, audio_buffer_start_time
        if audio_buffer_start_time is None:
            return
        trim_seconds = (committed_through - audio_buffer_start_time).total_seconds()
        trim_samples = min(audio_buffer.shape[0], max(0, int(trim_seconds * SAMPLE_RATE)))
        if trim_samples <= 0:
            return
        audio_buffer = audio_buffer[trim_samples:]
        audio_buffer_start_time = audio_buffer_start_time + timedelta(seconds=trim_samples / SAMPLE_RATE)
        if audio_buffer.shape[0] == 0:
            audio_buffer_start_time = None

    def align_resumed_wave_audio(first_chunk_start_time: datetime) -> None:
        nonlocal resume_gap_checked
        if resume_gap_checked:
            return
        resume_gap_checked = True
        saved_audio_seconds = wave_audio_duration_seconds(raw_audio_wave_path)
        if recording_start_time is None or saved_audio_seconds <= 0:
            return

        expected_audio_end = recording_start_time + timedelta(seconds=saved_audio_seconds)
        gap_seconds = (first_chunk_start_time - expected_audio_end).total_seconds()
        if gap_seconds <= RESUME_GAP_TOLERANCE_SECONDS:
            return

        silence_frames = int(round(gap_seconds * SAMPLE_RATE))
        append_wave_silence(raw_audio_wave_path, silence_frames)
        print(f"Inserted {gap_seconds:.2f}s transcript silence gap for resumed capture.")

    def append_captured_chunk(chunk, chunk_start_time: datetime) -> None:
        nonlocal audio_buffer, audio_buffer_start_time, recording_start_time, recording_origin_emitted
        nonlocal last_meter_emit_time

        if recording_start_time is None:
            existing_audio_seconds = wave_audio_duration_seconds(raw_audio_wave_path)
            recording_start_time = chunk_start_time - timedelta(seconds=existing_audio_seconds)
            write_recording_start(recording_start_path, recording_start_time)
        else:
            align_resumed_wave_audio(chunk_start_time)

        if not recording_origin_emitted:
            emit_protocol_message("RECORDING_ORIGIN", format_utc_timestamp(recording_start_time))
            recording_origin_emitted = True

        append_wave_audio(raw_audio_wave_path, chunk)

        signal_result = signal_analyzer.update(chunk[:, 0])
        now_monotonic = time_module.monotonic()
        if signal_result is not None and now_monotonic - last_meter_emit_time >= METER_EMIT_INTERVAL_SECONDS:
            emit_signal_quality(signal_result)
            last_meter_emit_time = now_monotonic

        speech_active = should_buffer_live_audio(chunk[:, 0])
        endpoint_state.observe(
            chunk_start_time,
            chunk.shape[0] / SAMPLE_RATE,
            speech_active,
        )
        conditioned_chunk = live_audio_conditioner.process(chunk[:, 0]).reshape(-1, CHANNELS)
        if not speech_active:
            return

        if audio_buffer_start_time is None:
            audio_buffer_start_time = chunk_start_time
        audio_buffer = np.concatenate((audio_buffer, conditioned_chunk), axis=0)

    def drain_captured_audio(block: bool = False) -> int:
        nonlocal last_backlog_warning_time
        drained_chunks = 0
        drained_seconds = 0.0

        if block:
            try:
                chunk, chunk_start_time = audio_queue.get(timeout=0.25)
            except queue.Empty:
                return 0
            append_captured_chunk(chunk, chunk_start_time)
            drained_chunks += 1
            drained_seconds += chunk.shape[0] / SAMPLE_RATE

        while True:
            try:
                chunk, chunk_start_time = audio_queue.get_nowait()
            except queue.Empty:
                break
            append_captured_chunk(chunk, chunk_start_time)
            drained_chunks += 1
            drained_seconds += chunk.shape[0] / SAMPLE_RATE

        now_monotonic = time_module.monotonic()
        if (
                drained_seconds >= BACKLOG_WARNING_SECONDS and
                now_monotonic - last_backlog_warning_time >= BACKLOG_WARNING_SECONDS):
            print(
                "Warning: live decoder is behind; full audio is still being saved for the final transcript.",
                file=sys.stderr,
            )
            last_backlog_warning_time = now_monotonic

        return drained_chunks

    def emit_live_update(
        update: LiveTranscriptionUpdate,
        current_audio_end_time: datetime,
        consume_all_audio: bool,
    ) -> None:
        nonlocal has_live_emission, last_partial_text
        nonlocal committed_live_audio_through, live_transcript_entries
        previous_lines = [
            format_transcript_line(timestamp, text)
            for timestamp, text in live_transcript_entries
        ]
        if consume_all_audio:
            committed_live_audio_through = current_audio_end_time
            trim_live_audio_buffer_through(current_audio_end_time)
        elif update.committed_through is not None:
            committed_live_audio_through = update.committed_through
            trim_live_audio_buffer_through(update.committed_through)

        live_transcript_entries = (
            existing_live_transcript_entries
            + group_committed_words(update.committed_words)
        )
        updated_lines = [
            format_transcript_line(timestamp, text)
            for timestamp, text in live_transcript_entries
        ]
        if updated_lines != previous_lines:
            write_lines(
                out_path,
                updated_lines,
            )
            emit_protocol_message("TRANSCRIPT_UPDATED")
        partial_text = join_timed_words(update.provisional_words)
        if partial_text != last_partial_text:
            emit_protocol_message("TRANSCRIPT_PARTIAL", partial_text)
            last_partial_text = partial_text
        if live_transcript_entries or partial_text:
            has_live_emission = True

    def maybe_transcribe_latest_live_audio(force: bool = False) -> None:
        nonlocal last_live_decode_end_time, last_decoded_word_end
        nonlocal existing_live_transcript_entries
        if live_transcriber is None or audio_buffer_start_time is None or audio_buffer.shape[0] == 0:
            return

        available_samples = audio_buffer.shape[0]
        is_streaming = isinstance(live_transcriber, ParakeetMlxLiveTranscriber)
        required_samples = 1 if force else (
            min_flush_samples
            if is_streaming or has_live_emission
            else initial_samples_per_chunk
        )
        if available_samples < required_samples:
            return

        decode_samples = (
            available_samples
            if is_streaming
            else min(available_samples, int(SAMPLE_RATE * ENDPOINT_MAX_TURN_SECONDS))
        )
        current_audio_end_time = endpoint_state.latest_audio_end or (
            audio_buffer_start_time + timedelta(seconds=decode_samples / SAMPLE_RATE)
        )
        decode_interval_seconds = step_seconds if has_live_emission else initial_live_window_seconds
        if (
                not force and
                last_live_decode_end_time is not None and
                (current_audio_end_time - last_live_decode_end_time).total_seconds() < decode_interval_seconds):
            return

        audio = audio_buffer[:decode_samples, 0].copy()
        chunk_window_start = audio_buffer_start_time
        endpoint_reason = endpoint_state.endpoint_reason(
            last_decoded_word_end,
            include_word_gap=False,
        )
        force_commit = force or endpoint_reason is not None
        committed_words = ()
        if isinstance(live_transcriber, WhisperLiveTranscriber):
            committed_words = live_transcriber.agreement.committed_words
        update = live_transcriber.accept_audio(
            audio,
            chunk_window_start,
            force=force if is_streaming else force_commit,
            context_prompt=local_agreement_prompt(
                existing_live_transcript_entries,
                committed_words,
            ),
        )
        observed_words = (*update.committed_words, *update.provisional_words)
        if observed_words:
            last_decoded_word_end = max(word[1] for word in observed_words)
        if not force_commit:
            decoded_audio_end = chunk_window_start + timedelta(
                seconds=audio.shape[0] / SAMPLE_RATE,
            )
            endpoint_reason = endpoint_state.endpoint_reason(
                last_decoded_word_end,
                decoded_audio_end=decoded_audio_end,
            )
            if endpoint_reason is not None and isinstance(live_transcriber, WhisperLiveTranscriber):
                update = live_transcriber.force_current(current_audio_end_time)
                force_commit = True
        last_live_decode_end_time = current_audio_end_time
        emit_live_update(
            update,
            current_audio_end_time,
            consume_all_audio=is_streaming or force_commit,
        )
        if force_commit:
            emit_protocol_message("TURN_ENDED", format_utc_timestamp(current_audio_end_time))
            endpoint_state.reset()
            last_decoded_word_end = None
            if isinstance(live_transcriber, WhisperLiveTranscriber):
                existing_live_transcript_entries = list(live_transcript_entries)
                live_transcriber.reset_turn()

    load_errors: list[str] = []
    model_load_complete = threading.Event()

    def load_live_model() -> None:
        nonlocal live_compute_type, live_transcriber, live_model_name
        nonlocal reusable_whisper_model
        try:
            try_parakeet = preferred_live_engine == "parakeet-mlx"
            if try_parakeet:
                try:
                    print(f"Loading live Parakeet MLX model: {args.parakeet_model}", flush=True)
                    live_transcriber = ParakeetMlxLiveTranscriber(args.parakeet_model)
                    live_model_name = args.parakeet_model
                    emit_protocol_message(
                        "LIVE_MODEL_READY",
                        f"parakeet-mlx:{args.parakeet_model}",
                    )
                    return
                except Exception as exc:
                    load_errors.append(f"parakeet-mlx:{args.parakeet_model}: {exc}")
                    print(
                        "Warning: failed to load Parakeet MLX; falling back to "
                        f"faster-whisper: {exc}",
                        file=sys.stderr,
                        flush=True,
                    )
            elif args.live_engine == "parakeet-mlx":
                print(
                    "Warning: Parakeet MLX is unavailable for this platform, "
                    "installed runtime, or language; falling back to faster-whisper.",
                    file=sys.stderr,
                    flush=True,
                )
            for candidate_model_name in live_model_candidates:
                try:
                    print(f"Loading live faster-whisper model: {candidate_model_name}", flush=True)
                    loaded_model, loaded_compute_type = load_cpu_whisper_model(
                        WhisperModel,
                        candidate_model_name,
                        args.compute_type,
                    )
                    reusable_whisper_model = loaded_model
                    live_transcriber = WhisperLiveTranscriber(
                        loaded_model,
                        candidate_model_name,
                        language,
                        args.beam_size,
                        args.best_of,
                        args.hotwords,
                    )
                    live_model_name = candidate_model_name
                    live_compute_type = loaded_compute_type
                    if loaded_compute_type != args.compute_type:
                        print(
                            "Warning: live model compute type "
                            f"'{args.compute_type}' is unavailable; using "
                            f"'{loaded_compute_type}'.",
                            file=sys.stderr,
                            flush=True,
                        )
                    emit_protocol_message("LIVE_MODEL_READY", candidate_model_name)
                    return
                except Exception as exc:
                    load_errors.append(f"{candidate_model_name}: {exc}")
                    print(
                        f"Warning: failed to load live faster-whisper model '{candidate_model_name}': {exc}",
                        file=sys.stderr,
                        flush=True,
                    )
            print(
                "Error: failed to load any live faster-whisper model. Tried: "
                + " | ".join(load_errors),
                file=sys.stderr,
                flush=True,
            )
        finally:
            model_load_complete.set()

    signal.signal(signal.SIGINT, request_stop)
    signal.signal(signal.SIGTERM, request_stop)

    try:
        with sd.InputStream(
            samplerate=SAMPLE_RATE,
            channels=CHANNELS,
            dtype="float32",
            blocksize=round(SAMPLE_RATE * LIVE_VAD_WINDOW_SECONDS),
            callback=callback,
            device=device,
        ):
            emit_protocol_message("TRANSCRIPT_READY")
            if recording_start_time is not None and not recording_origin_emitted:
                emit_protocol_message("RECORDING_ORIGIN", format_utc_timestamp(recording_start_time))
                recording_origin_emitted = True
            model_thread = threading.Thread(target=load_live_model, name="live-asr-model-loader")
            model_thread.start()

            while not stop_requested:
                if drain_captured_audio(block=True):
                    maybe_transcribe_latest_live_audio()
    except KeyboardInterrupt:
        stop_requested = True
    except sd.PortAudioError as exc:
        print(f"Error: {explain_portaudio_error(exc)}", file=sys.stderr)
        return 1
    except Exception as exc:
        print(f"Error: transcription failed: {exc}", file=sys.stderr)
        return 1

    drain_captured_audio()
    if 'model_thread' in locals():
        model_thread.join()
    if live_transcriber is not None:
        maybe_transcribe_latest_live_audio(force=True)

    if args.capture_only:
        if live_transcriber is not None:
            live_transcriber.close()
        emit_protocol_message("FINALIZATION_RESULT", "paused")
        print(f"Paused. Transcript and audio remain resumable at: {out_path}")
        return 0

    if live_transcriber is not None:
        live_transcriber.close()
    return finalize_existing_capture(
        WhisperModel,
        args,
        out_path,
        raw_audio_wave_path,
        recording_start_time,
        language,
        previous_text,
        reusable_model=reusable_whisper_model,
        reusable_model_name=live_model_name if reusable_whisper_model is not None else None,
    )


if __name__ == "__main__":
    raise SystemExit(main())
