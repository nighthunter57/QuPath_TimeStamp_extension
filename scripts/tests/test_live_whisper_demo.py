import contextlib
import hashlib
import io
import sys
import tempfile
import unittest
import wave
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import numpy as np

from scripts import live_whisper_demo as transcript


class TranscriptLogicTest(unittest.TestCase):

    def test_lifecycle_cli_modes_are_mutually_exclusive(self):
        with patch.object(sys, "argv", ["live_whisper_demo.py", "--output", "case.txt", "--capture-only"]):
            self.assertTrue(transcript.parse_args().capture_only)
        with patch.object(sys, "argv", ["live_whisper_demo.py", "--output", "case.txt", "--finalize-existing"]):
            self.assertTrue(transcript.parse_args().finalize_existing)
        with patch.object(sys, "argv", [
            "live_whisper_demo.py", "--output", "case.txt",
            "--capture-only", "--finalize-existing",
        ]), contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                transcript.parse_args()

    def test_live_engine_cli_defaults_to_auto_and_accepts_whisper_override(self):
        with patch.object(sys, "argv", ["live_whisper_demo.py", "--output", "case.txt"]):
            self.assertEqual("auto", transcript.parse_args().live_engine)
        with patch.object(sys, "argv", [
            "live_whisper_demo.py", "--output", "case.txt", "--live-engine", "whisper",
        ]):
            self.assertEqual("whisper", transcript.parse_args().live_engine)

    def test_timestamp_round_trip_preserves_date(self):
        expected = datetime(2026, 7, 30, 23, 59, 59, 123000).astimezone()
        line = transcript.format_transcript_line(expected, "diagnostic text")

        parsed = transcript.parse_transcript_line(line)

        self.assertEqual((expected.astimezone(timezone.utc), "diagnostic text"), parsed)

    def test_protocol_grammar_covers_every_message(self):
        examples = {
            "DEVICE": ("Built-in Microphone", "0 - Built-in Microphone"),
            "AUDIO_CHECK_READY": (),
            "AUDIO_CHECK_RESULT": ("14.0", "good"),
            "AUDIO_LEVEL": ("4.0", "low"),
            "AUDIO_CLIPPING": ("0.125",),
            "AUDIO_SILENT": ("30.0",),
            "AUDIO_RECOVERED": (),
            "TRANSCRIPT_READY": (),
            "RECORDING_ORIGIN": ("2026-08-25T20:00:00.000Z",),
            "LIVE_MODEL_READY": ("small.en",),
            "TRANSCRIPT_UPDATED": (),
            "TRANSCRIPT_PARTIAL": ("provisional words",),
            "TURN_ENDED": ("2026-08-25T20:00:04.000Z",),
            "FINALIZE_PROGRESS": ("12.0", "60.0"),
            "FINALIZATION_RESULT": ("final",),
        }
        self.assertEqual(set(transcript.PROTOCOL_FIELDS), set(examples))
        for kind, fields in examples.items():
            with self.subTest(kind=kind):
                message = transcript.format_protocol_message(kind, *fields)
                self.assertEqual(kind, message.split("\t", 1)[0])
                self.assertNotIn("\n", message)
        with self.assertRaises(ValueError):
            transcript.format_protocol_message("AUDIO_LEVEL", "missing-state")
        with self.assertRaises(ValueError):
            transcript.format_protocol_message("NOT_A_MESSAGE")

    def test_utc_timing_export_converts_local_offset(self):
        local_time = datetime(
            2026, 8, 20, 12, 0, 0, tzinfo=timezone(timedelta(hours=-5))
        )

        self.assertEqual(
            "2026-08-20T17:00:00.000Z",
            transcript.format_utc_timestamp(local_time),
        )

    def test_midnight_transcript_entries_sort_chronologically(self):
        before = transcript.parse_transcript_line(
            "[2026-07-30T23:59:59.900] before midnight"
        )
        after = transcript.parse_transcript_line(
            "[2026-07-31T00:00:00.100] after midnight"
        )

        self.assertGreater(after[0], before[0])

    def test_local_agreement_commits_only_shared_prefix(self):
        start = datetime(2026, 7, 30, 12, 0, 0)
        state = transcript.LocalAgreementState()
        first = self._timed_words(start, "negative for possible malignancy")
        second = self._timed_words(start, "negative for definite malignancy")

        committed, provisional = state.update(first)
        self.assertEqual([], committed)
        self.assertEqual("negative for possible malignancy", transcript.join_timed_words(provisional))

        committed, provisional = state.update(second)
        self.assertEqual("negative for", transcript.join_timed_words(committed))
        self.assertEqual("definite malignancy", transcript.join_timed_words(provisional))

    def test_local_agreement_correction_remains_provisional_until_repeated(self):
        start = datetime(2026, 7, 30, 12, 0, 0)
        state = transcript.LocalAgreementState()

        state.update(self._timed_words(start, "positive for malignancy"))
        committed, provisional = state.update(
            self._timed_words(start, "negative for malignancy")
        )
        self.assertEqual([], committed)
        self.assertEqual("negative for malignancy", transcript.join_timed_words(provisional))

        committed, provisional = state.update(
            self._timed_words(start, "negative for malignancy")
        )
        self.assertEqual("negative for malignancy", transcript.join_timed_words(committed))
        self.assertEqual([], provisional)

    def test_local_agreement_force_commits_latest_hypothesis(self):
        start = datetime(2026, 7, 30, 12, 0, 0)
        state = transcript.LocalAgreementState()
        state.update(self._timed_words(start, "provisional phrase"))

        committed, provisional = state.update([], force=True)

        self.assertEqual("provisional phrase", transcript.join_timed_words(committed))
        self.assertEqual([], provisional)

    def test_parakeet_backend_requires_apple_silicon_supported_language_and_package(self):
        self.assertTrue(transcript.parakeet_live_is_supported(
            "en", "Darwin", "arm64", True,
        ))
        self.assertTrue(transcript.parakeet_live_is_supported(
            "ru", "Darwin", "arm64", True,
        ))
        self.assertFalse(transcript.parakeet_live_is_supported(
            "vi", "Darwin", "arm64", True,
        ))
        self.assertFalse(transcript.parakeet_live_is_supported(
            "en", "Linux", "aarch64", True,
        ))
        self.assertFalse(transcript.parakeet_live_is_supported(
            "en", "Darwin", "arm64", False,
        ))

    def test_parakeet_is_explicit_opt_in_until_quality_gate_passes(self):
        self.assertEqual(
            "whisper",
            transcript.resolve_live_engine("auto", "en", "Darwin", "arm64", True),
        )
        self.assertEqual(
            "parakeet-mlx",
            transcript.resolve_live_engine(
                "parakeet-mlx", "en", "Darwin", "arm64", True,
            ),
        )

    def test_parakeet_live_transcriber_exposes_finalized_and_draft_words(self):
        tokens = lambda values: [
            SimpleNamespace(text=text, start=start, end=end)
            for text, start, end in values
        ]

        class FakeStream:
            def __init__(self):
                self.finalized_tokens = tokens([
                    (" The", 0.0, 0.2), (" margin", 0.2, 0.5),
                    (" is", 0.5, 0.7), (" negative", 0.7, 1.1),
                    (".", 1.1, 1.2),
                ])
                self.draft_tokens = tokens([(" Pending", 1.3, 1.7)])
                self.received = []

            def add_audio(self, audio):
                self.received.append(audio)

        class FakeContext:
            def __init__(self, stream):
                self.stream = stream
                self.closed = False

            def __enter__(self):
                return self.stream

            def __exit__(self, *args):
                self.closed = True

        stream = FakeStream()
        context = FakeContext(stream)
        model = SimpleNamespace(transcribe_stream=lambda **kwargs: context)
        live = transcript.ParakeetMlxLiveTranscriber(
            model_loader=lambda name: model,
            array_factory=lambda audio: audio,
        )
        start = datetime(2026, 8, 28, 12, 0, tzinfo=timezone.utc)
        audio = np.full(transcript.SAMPLE_RATE, 0.02, dtype=np.float32)

        update = live.accept_audio(audio, start)

        self.assertEqual("The margin is negative.", transcript.join_timed_words(update.committed_words))
        self.assertEqual("Pending", transcript.join_timed_words(update.provisional_words))
        self.assertEqual(start, update.committed_words[0][0])
        self.assertEqual(1, len(stream.received))
        live.close()
        self.assertTrue(context.closed)

    def test_committed_words_group_on_sentence_and_long_gap(self):
        start = datetime(2026, 7, 30, 12, 0, 0)
        words = [
            (start, start + timedelta(seconds=0.2), "First"),
            (start + timedelta(seconds=0.2), start + timedelta(seconds=0.4), " sentence."),
            (start + timedelta(seconds=0.5), start + timedelta(seconds=0.7), "Second"),
            (start + timedelta(seconds=1.5), start + timedelta(seconds=1.7), " line"),
        ]

        grouped = transcript.group_committed_words(words)

        self.assertEqual(["First sentence.", "Second", "line"], [text for _, text in grouped])

    def test_local_agreement_prompt_is_limited_to_last_32_words(self):
        start = datetime(2026, 7, 30, 12, 0, 0)
        entries = [(start, " ".join(f"word{index}" for index in range(40)))]

        prompt = transcript.local_agreement_prompt(entries, [])

        self.assertEqual(32, len(prompt.split()))
        self.assertTrue(prompt.startswith("word8 "))

    @staticmethod
    def _timed_words(start, text):
        words = []
        for index, piece in enumerate(text.split()):
            word_start = start + timedelta(seconds=index * 0.2)
            words.append((word_start, word_start + timedelta(seconds=0.2), piece))
        return words

    def test_raw_audio_is_streamed_to_valid_wave_file(self):
        with tempfile.TemporaryDirectory() as directory:
            raw_path = Path(directory) / "capture.raw"
            wave_path = Path(directory) / "capture.wav"
            raw_path.write_bytes(b"\x00\x00" * transcript.SAMPLE_RATE)

            self.assertTrue(transcript.export_raw_audio_to_wave(raw_path, wave_path))
            with wave.open(str(wave_path), "rb") as handle:
                self.assertEqual(transcript.SAMPLE_RATE, handle.getframerate())
                self.assertEqual(1, handle.getnchannels())
                self.assertEqual(transcript.SAMPLE_RATE, handle.getnframes())

    def test_incremental_wave_is_playable_after_every_append(self):
        with tempfile.TemporaryDirectory() as directory:
            wave_path = Path(directory) / "capture.wav"
            chunk = b"\x00\x00" * (transcript.SAMPLE_RATE // 2)
            for expected_frames in (transcript.SAMPLE_RATE // 2, transcript.SAMPLE_RATE):
                transcript.append_wave_bytes(wave_path, chunk)
                with wave.open(str(wave_path), "rb") as handle:
                    self.assertEqual(expected_frames, handle.getnframes())
                    self.assertEqual(transcript.SAMPLE_RATE, handle.getframerate())

    def test_invalid_partial_wave_is_recovered_before_recording(self):
        with tempfile.TemporaryDirectory() as directory:
            raw_path = Path(directory) / "capture.raw"
            wave_path = Path(directory) / "capture.wav"
            wave_path.write_bytes(b"RIFF-partial-crash")

            transcript.prepare_incremental_wave(raw_path, wave_path)
            transcript.append_wave_bytes(wave_path, b"\x00\x00" * 100)

            self.assertTrue(transcript.is_valid_capture_wave(wave_path))
            with wave.open(str(wave_path), "rb") as handle:
                self.assertEqual(100, handle.getnframes())

    def test_empty_transcript_does_not_delete_resumable_audio(self):
        with tempfile.TemporaryDirectory() as directory:
            output_path = Path(directory) / "case_transcript.txt"
            raw_path = Path(directory) / "case_transcript_audio.raw"
            start_path = Path(directory) / "case_transcript_audio.start.txt"
            recording_start = datetime(2026, 8, 12, 12, 0, 0, tzinfo=timezone.utc)
            output_path.write_text("", encoding="utf-8")
            raw_contents = b"\x00\x00" * transcript.SAMPLE_RATE
            raw_path.write_bytes(raw_contents)
            transcript.write_recording_start(start_path, recording_start)

            entries, loaded_start, committed_through = (
                transcript.load_existing_capture_state(
                    output_path,
                    raw_path,
                    start_path,
                )
            )

            self.assertEqual([], entries)
            self.assertEqual(recording_start, loaded_start)
            self.assertEqual(recording_start + timedelta(seconds=1), committed_through)
            self.assertEqual(raw_contents, raw_path.read_bytes())
            self.assertTrue(start_path.exists())

    def test_silence_watchdog_warns_once_and_recovers(self):
        watchdog = transcript.AudioSilenceWatchdog(threshold=0.01, warning_seconds=30.0)
        self.assertEqual([], watchdog.update(0.0, 10.0))
        self.assertEqual([("AUDIO_SILENT", ("30.0",))], watchdog.update(0.0, 40.0))
        self.assertEqual([], watchdog.update(0.0, 50.0))
        self.assertEqual([("AUDIO_RECOVERED", ())], watchdog.update(0.02, 51.0))
        self.assertEqual([], watchdog.update(0.02, 52.0))

    def test_signal_to_noise_estimator_classifies_good_signal(self):
        estimator = transcript.SignalToNoiseEstimator()
        for _ in range(3):
            estimator.update(0.001, vad_active=False)
            snr_db, state = estimator.update(0.01, vad_active=True)

        self.assertAlmostEqual(20.0, snr_db, places=3)
        self.assertEqual("good", state)

    def test_signal_to_noise_estimator_reports_calibrating_low_and_critical(self):
        estimator = transcript.SignalToNoiseEstimator()
        self.assertEqual((None, "calibrating"), estimator.update(0.002, vad_active=False))
        for _ in range(2):
            estimator.update(0.002, vad_active=False)
        for _ in range(3):
            snr_db, state = estimator.update(0.004, vad_active=True)
        self.assertAlmostEqual(6.0206, snr_db, places=3)
        self.assertEqual("low", state)

        critical = transcript.SignalToNoiseEstimator()
        for _ in range(3):
            critical.update(0.005, vad_active=False)
            snr_db, state = critical.update(0.006, vad_active=True)
        self.assertLess(snr_db, transcript.SNR_CRITICAL_DB)
        self.assertEqual("critical", state)

    def test_signal_quality_analyzer_does_not_treat_noise_energy_as_speech(self):
        speech_active = False
        analyzer = transcript.SignalQualityAnalyzer(
            speech_detector=lambda audio: speech_active,
        )
        window = np.full(analyzer.window_samples, 0.008, dtype=np.float32)
        for _ in range(3):
            result = analyzer.update(window)
        self.assertEqual((None, "calibrating"), result)

        speech_active = True
        speech = np.full(analyzer.window_samples, 0.04, dtype=np.float32)
        for _ in range(3):
            result = analyzer.update(speech)
        self.assertEqual("good", result[1])

    def test_device_selection_resolves_stable_name(self):
        fake_sounddevice = SimpleNamespace(query_devices=lambda: [
            {"name": "Speaker", "max_input_channels": 0},
            {"name": "Clinical USB Mic", "max_input_channels": 1},
        ])
        self.assertEqual(1, transcript.resolve_input_device(fake_sounddevice, "Clinical USB Mic"))
        with self.assertRaises(ValueError):
            transcript.resolve_input_device(fake_sounddevice, "Disconnected Mic")

    def test_live_context_uses_configured_window_above_ten_seconds(self):
        self.assertEqual(10.0, transcript.resolve_live_window_seconds(5.0))
        self.assertEqual(10.0, transcript.resolve_live_window_seconds(10.0))
        self.assertEqual(30.0, transcript.resolve_live_window_seconds(30.0))
        self.assertEqual(60.0, transcript.resolve_live_window_seconds(60.0))

    def test_live_context_rejects_unsafe_values(self):
        for value in (0.0, -1.0, 120.01, float("nan"), float("inf")):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    transcript.resolve_live_window_seconds(value)

    def test_repeated_silence_hallucinations_are_rejected(self):
        repeated = [
            f"[2026-08-12T12:{minute:02d}:00.000] you"
            for minute in range(10)
        ]
        ordinary = [
            "[2026-08-12T12:00:00.000] This is a live transcript test.",
            "[2026-08-12T12:00:03.000] Three tissue regions are present.",
        ]

        self.assertTrue(transcript.has_suspicious_transcript_repetition(repeated))
        self.assertFalse(transcript.has_suspicious_transcript_repetition(ordinary))

    def test_structural_loop_detector_rejects_repeated_three_grams(self):
        repeated = " ".join(["and system"] * 40)

        self.assertTrue(transcript.looks_like_structural_repetition_loop(repeated))

    def test_structural_loop_detector_keeps_normal_pathology_sentence(self):
        ordinary = (
            "The lymph node shows reactive follicular hyperplasia with polarized "
            "germinal centers and no evidence of metastatic carcinoma or lymphoma."
        )

        self.assertFalse(transcript.looks_like_structural_repetition_loop(ordinary))

    def test_structural_loop_detector_ignores_short_repetition(self):
        self.assertFalse(
            transcript.looks_like_structural_repetition_loop("yes yes yes")
        )

    def test_final_repetition_filter_drops_only_duplicate_offending_segments(self):
        repeated = "[2026-08-28T12:00:00.000] repeated decoder fragment"
        lines = [
            "[2026-08-28T11:59:59.000] useful opening",
            repeated,
            repeated,
            repeated,
            repeated,
            "[2026-08-28T12:00:05.000] useful closing",
        ]
        segment_rows = [
            {"segment_index": index, "text": line}
            for index, line in enumerate(lines)
        ]
        word_rows = [
            {"segment_index": index, "word": "word"}
            for index in range(len(lines))
        ]

        filtered_lines, filtered_segments, filtered_words, dropped = (
            transcript.drop_repeated_final_segments(lines, segment_rows, word_rows)
        )

        self.assertEqual(3, dropped)
        self.assertEqual(
            [lines[0], repeated, lines[-1]],
            filtered_lines,
        )
        self.assertEqual([0, 1, 5], [row["segment_index"] for row in filtered_segments])
        self.assertEqual([0, 1, 5], [row["segment_index"] for row in filtered_words])

    def test_trailing_hallucination_filter_drops_known_final_phrase_after_silence(self):
        segments = [
            SimpleNamespace(start=0.0, end=2.0, text="The margin is negative."),
            SimpleNamespace(start=2.7, end=3.4, text="THANKS for watching!"),
        ]

        filtered = transcript.filter_trailing_hallucination_segments(segments)

        self.assertEqual([segments[0]], filtered)

    def test_trailing_hallucination_filter_keeps_mid_transcript_occurrence(self):
        segments = [
            SimpleNamespace(start=0.0, end=1.0, text="The doctor said thank you for watching."),
            SimpleNamespace(start=1.8, end=2.5, text="Thank you for watching."),
            SimpleNamespace(start=3.2, end=4.0, text="The final margin is negative."),
        ]

        self.assertEqual(
            segments,
            transcript.filter_trailing_hallucination_segments(segments),
        )

    def test_trailing_hallucination_filter_requires_a_silence_gap(self):
        segments = [
            SimpleNamespace(start=0.0, end=2.0, text="The margin is negative."),
            SimpleNamespace(start=2.1, end=2.8, text="We'll be right back."),
        ]

        self.assertEqual(
            segments,
            transcript.filter_trailing_hallucination_segments(segments),
        )

    def test_trailing_hallucination_filter_uses_audio_drop_when_timestamps_touch(self):
        segments = [
            SimpleNamespace(start=0.0, end=2.0, text="The margin is negative."),
            SimpleNamespace(start=2.0, end=3.0, text="We'll be right back."),
        ]
        audio = np.concatenate((
            np.full(transcript.SAMPLE_RATE * 2, 0.1, dtype=np.float32),
            np.full(transcript.SAMPLE_RATE, 0.02, dtype=np.float32),
        ))

        self.assertEqual(
            [segments[0]],
            transcript.filter_trailing_hallucination_segments(segments, audio),
        )

    def test_trailing_hallucination_filter_is_wired_to_live_and_final_paths(self):
        segments = [
            SimpleNamespace(
                start=0.0, end=2.0, text=" The margin is negative.",
                avg_logprob=0.0, no_speech_prob=0.0, compression_ratio=1.0,
                words=[],
            ),
            SimpleNamespace(
                start=2.0, end=3.0, text=" Thanks for watching!",
                avg_logprob=0.0, no_speech_prob=0.0, compression_ratio=1.0,
                words=[],
            ),
        ]
        model = SimpleNamespace(transcribe=lambda *args, **kwargs: (
            segments, SimpleNamespace(duration=3.0)
        ))
        audio = np.concatenate((
            np.full(transcript.SAMPLE_RATE * 2, 0.1, dtype=np.float32),
            np.full(transcript.SAMPLE_RATE, 0.02, dtype=np.float32),
        ))
        recording_start = datetime(2026, 8, 27, tzinfo=timezone.utc)

        live_segments = transcript.transcribe_audio_segments(
            model, audio, "en", recording_start, 2, 2, False,
        )
        with patch.object(transcript, "decode_saved_audio", return_value=audio):
            final_lines, _, _ = transcript.transcribe_saved_audio_with_timings(
                model, Path("capture.wav"), "en", recording_start, 8, 8, True,
            )

        self.assertEqual(["The margin is negative."], [entry[2] for entry in live_segments])
        self.assertEqual(1, len(final_lines))
        self.assertIn("The margin is negative.", final_lines[0])

    def test_structural_loop_filter_is_wired_to_live_and_final_paths(self):
        loop_text = " ".join(["and system"] * 40)
        segment = SimpleNamespace(
            start=0.0,
            end=1.0,
            text=loop_text,
            avg_logprob=0.0,
            no_speech_prob=0.0,
            compression_ratio=1.0,
            words=[],
        )
        model = SimpleNamespace(
            transcribe=lambda *args, **kwargs: (
                [segment],
                SimpleNamespace(duration=1.0),
            )
        )
        recording_start = datetime(2026, 8, 26, tzinfo=timezone.utc)

        with patch.object(
            transcript,
            "decode_saved_audio",
            return_value=np.full(transcript.SAMPLE_RATE, 0.01, dtype=np.float32),
        ):
            live_segments = transcript.transcribe_audio_segments(
                model,
                np.full(transcript.SAMPLE_RATE, 0.01, dtype=np.float32),
                "en",
                recording_start,
                beam_size=2,
                best_of=2,
                previous_text=False,
                strict_segment_filtering=False,
            )
            final_lines, segment_rows, word_rows = (
                transcript.transcribe_saved_audio_with_timings(
                    model,
                    Path("capture.wav"),
                    "en",
                    recording_start,
                    beam_size=8,
                    best_of=8,
                    previous_text=True,
                )
            )

        self.assertEqual([], live_segments)
        self.assertEqual(([], [], []), (final_lines, segment_rows, word_rows))

    def test_final_text_refinement_preserves_matching_live_timestamps(self):
        live = [
            "[2026-08-12T12:00:01.000] This is a live transcript test.",
            "[2026-08-12T12:00:04.000] Three tissue regions are present.",
        ]
        final = [
            "[2026-08-12T12:00:31.000] This is a live transcript test.",
            "[2026-08-12T12:00:34.000] Three tissue regions are present.",
        ]

        resolved = transcript.preserve_matching_live_timestamps(final, live)

        self.assertEqual(live, resolved)

    def test_final_pass_keeps_voice_activity_filtering_enabled(self):
        settings = transcript.build_transcribe_kwargs(
            "en", beam_size=2, best_of=2, previous_text=True, final_pass=True
        )

        self.assertTrue(settings["vad_filter"])
        self.assertIn("vad_parameters", settings)
        self.assertEqual(8, transcript.FINAL_PASS_MIN_BEAM_SIZE)
        self.assertEqual(transcript.FINAL_PASS_MIN_BEAM_SIZE, settings["beam_size"])
        self.assertIn("Gleason", settings["hotwords"])
        self.assertIn(settings["hotwords"], settings["initial_prompt"])
        self.assertIsInstance(settings["temperature"], list)
        self.assertEqual(
            transcript.FINAL_REPETITION_PENALTY,
            settings["repetition_penalty"],
        )
        self.assertEqual(0, settings["no_repeat_ngram_size"])

    def test_live_preview_caps_expensive_decoding_settings(self):
        settings = transcript.build_transcribe_kwargs(
            "en", beam_size=8, best_of=8, previous_text=False, final_pass=False
        )

        self.assertEqual(transcript.LIVE_MAX_BEAM_SIZE, settings["beam_size"])
        self.assertEqual(transcript.LIVE_MAX_BEST_OF, settings["best_of"])
        self.assertEqual(2, settings["beam_size"])
        self.assertEqual(2, settings["best_of"])
        self.assertFalse(settings["vad_filter"])
        self.assertNotIn("vad_parameters", settings)
        self.assertEqual(list(transcript.LIVE_TEMPERATURES), settings["temperature"])
        self.assertNotIn("initial_prompt", settings)
        self.assertEqual(
            transcript.LIVE_REPETITION_PENALTY,
            settings["repetition_penalty"],
        )
        self.assertEqual(
            transcript.LIVE_NO_REPEAT_NGRAM_SIZE,
            settings["no_repeat_ngram_size"],
        )
        self.assertEqual(
            transcript.SEGMENT_COMPRESSION_RATIO_THRESHOLD,
            settings["compression_ratio_threshold"],
        )
        self.assertEqual(
            transcript.SEGMENT_AVG_LOGPROB_THRESHOLD,
            settings["log_prob_threshold"],
        )
        self.assertEqual(
            transcript.SEGMENT_NO_SPEECH_THRESHOLD,
            settings["no_speech_threshold"],
        )

    def test_non_speech_block_never_reaches_live_decode_buffer(self):
        audio = np.full(transcript.SAMPLE_RATE // 2, 0.01, dtype=np.float32)
        observed = []

        accepted = transcript.should_buffer_live_audio(
            audio,
            speech_detector=lambda candidate: observed.append(candidate.copy()) or False,
        )

        self.assertFalse(accepted)
        self.assertEqual(1, len(observed))

    def test_endpoint_layers_silence_word_gap_and_hard_cap(self):
        start = datetime(2026, 8, 25, 20, 0, tzinfo=timezone.utc)

        silence = transcript.SpeechEndpointState()
        silence.observe(start, 0.5, True)
        silence.observe(start + timedelta(seconds=0.5), 0.8, False)
        self.assertEqual("silence", silence.endpoint_reason(None))

        word_gap = transcript.SpeechEndpointState()
        word_gap.observe(start, 1.5, True)
        self.assertEqual(
            "word-gap",
            word_gap.endpoint_reason(start + timedelta(seconds=0.5)),
        )

        hard_cap = transcript.SpeechEndpointState(max_turn_seconds=2.0)
        hard_cap.observe(start, 2.1, True)
        self.assertEqual("hard-cap", hard_cap.endpoint_reason(None))

    def test_endpoint_reset_starts_a_new_turn(self):
        start = datetime(2026, 8, 25, 20, 0, tzinfo=timezone.utc)
        endpoint = transcript.SpeechEndpointState()
        endpoint.observe(start, 1.0, True)
        endpoint.reset()

        self.assertIsNone(endpoint.endpoint_reason(None))
        self.assertIsNone(endpoint.latest_audio_end)

    def test_decode_timeline_maps_gated_gaps_and_trims_matching_samples(self):
        start = datetime(2026, 8, 25, 20, 0, tzinfo=timezone.utc)
        timeline = transcript.SpeechDecodeTimeline(sample_rate=10)
        timeline.append(start, 10)
        timeline.append(start + timedelta(seconds=3), 10)

        self.assertEqual(start + timedelta(seconds=0.5), timeline.map_offset(0.5))
        self.assertEqual(start + timedelta(seconds=1), timeline.map_offset(1.0, prefer_end=True))
        self.assertEqual(start + timedelta(seconds=3), timeline.map_offset(1.0))
        self.assertEqual(start + timedelta(seconds=3.5), timeline.map_offset(1.5))

        self.assertEqual(15, timeline.trim_through(start + timedelta(seconds=3.5)))
        self.assertEqual(5, timeline.frame_count)
        self.assertEqual(start + timedelta(seconds=3.5), timeline.start_time)

    def test_transcribe_maps_compressed_word_offsets_to_capture_clock(self):
        start = datetime(2026, 8, 25, 20, 0, tzinfo=timezone.utc)
        timeline = transcript.SpeechDecodeTimeline(sample_rate=transcript.SAMPLE_RATE)
        timeline.append(start, transcript.SAMPLE_RATE)
        timeline.append(start + timedelta(seconds=3), transcript.SAMPLE_RATE)
        word = SimpleNamespace(word=" second", start=1.2, end=1.5)
        segment = SimpleNamespace(
            text="second",
            start=1.2,
            end=1.5,
            words=[word],
            avg_logprob=0.0,
            no_speech_prob=0.0,
            compression_ratio=1.0,
        )
        model = SimpleNamespace(transcribe=lambda audio, **kwargs: ([segment], None))
        audio = np.full(transcript.SAMPLE_RATE * 2, 0.02, dtype=np.float32)

        entries = transcript.transcribe_audio_segments(
            model,
            audio,
            "en",
            start,
            beam_size=2,
            best_of=2,
            previous_text=False,
            audio_is_conditioned=True,
            audio_timeline=timeline,
        )

        self.assertEqual(start + timedelta(seconds=3.2), entries[0][0])
        self.assertEqual(start + timedelta(seconds=3.5), entries[0][1])
        self.assertEqual(start + timedelta(seconds=3.2), entries[0][3][0][0])

    def test_live_context_prompt_is_used_only_when_supplied(self):
        settings = transcript.build_transcribe_kwargs(
            "en",
            beam_size=2,
            best_of=2,
            previous_text=False,
            final_pass=False,
            context_prompt="last committed words",
        )

        self.assertEqual("last committed words", settings["initial_prompt"])

    def test_loud_short_window_survives_quiet_buffer_rms_gate(self):
        audio = np.zeros(transcript.SAMPLE_RATE * 2, dtype=np.float32)
        audio[:transcript.SAMPLE_RATE // 4] = 0.006

        self.assertLess(transcript.audio_rms(audio), transcript.CHUNK_RMS_SILENCE_THRESHOLD)
        self.assertGreater(
            transcript.maximum_audio_window_rms(audio),
            transcript.CHUNK_RMS_SILENCE_THRESHOLD,
        )

    def test_high_pass_removes_dc_and_attenuates_30_hz_rumble(self):
        seconds = 2
        sample_times = np.arange(transcript.SAMPLE_RATE * seconds) / transcript.SAMPLE_RATE
        low_frequency = 0.1 * np.sin(2 * np.pi * 30 * sample_times) + 0.2
        speech_frequency = 0.1 * np.sin(2 * np.pi * 500 * sample_times) + 0.2

        filtered_low = transcript.remove_dc_and_high_pass(low_frequency.astype(np.float32))
        filtered_speech = transcript.remove_dc_and_high_pass(speech_frequency.astype(np.float32))

        self.assertLess(abs(float(filtered_low.mean())), 0.001)
        self.assertLess(
            transcript.audio_rms(filtered_low),
            transcript.audio_rms(low_frequency) * 0.25,
        )
        self.assertGreater(
            transcript.audio_rms(filtered_speech),
            transcript.audio_rms(speech_frequency - speech_frequency.mean()) * 0.9,
        )

    def test_slow_agc_boosts_quiet_speech_without_exceeding_eight_times_gain(self):
        sample_times = np.arange(transcript.SAMPLE_RATE) / transcript.SAMPLE_RATE
        quiet_speech = (0.005 * np.sin(2 * np.pi * 500 * sample_times)).astype(np.float32)

        adjusted = transcript.apply_slow_agc(quiet_speech)

        self.assertGreater(transcript.audio_rms(adjusted), transcript.audio_rms(quiet_speech) * 6)
        self.assertLessEqual(float(np.max(np.abs(adjusted))), float(np.max(np.abs(quiet_speech))) * 8.001)
        self.assertLessEqual(float(np.max(np.abs(adjusted))), 1.0)

    def test_live_conditioning_does_not_mutate_source_audio(self):
        sample_times = np.arange(transcript.SAMPLE_RATE) / transcript.SAMPLE_RATE
        raw_audio = (0.02 * np.sin(2 * np.pi * 500 * sample_times) + 0.1).astype(np.float32)
        original = raw_audio.copy()

        conditioned = transcript.condition_live_audio(raw_audio)

        np.testing.assert_array_equal(original, raw_audio)
        self.assertLess(abs(float(conditioned[transcript.SAMPLE_RATE // 2 :].mean())), 0.001)
        self.assertFalse(np.array_equal(original, conditioned))

    def test_final_conditioner_remains_separate_from_causal_live_path(self):
        sample_times = np.arange(transcript.SAMPLE_RATE * 2) / transcript.SAMPLE_RATE
        raw_audio = (
            0.004 * np.sin(2 * np.pi * 500 * sample_times) + 0.1
        ).astype(np.float32)

        expected = transcript.apply_slow_agc(
            transcript.remove_dc_and_high_pass(raw_audio)
        )
        final_audio = transcript.condition_final_audio(raw_audio)
        live_audio = transcript.condition_live_audio(raw_audio)

        np.testing.assert_array_equal(expected, final_audio)
        self.assertFalse(np.array_equal(final_audio, live_audio))

    def test_stateful_conditioning_matches_one_call_across_chunks(self):
        sample_times = np.arange(transcript.SAMPLE_RATE * 2) / transcript.SAMPLE_RATE
        raw_audio = (
            0.02 * np.sin(2 * np.pi * 500 * sample_times) + 0.1
        ).astype(np.float32)
        expected = transcript.LiveAudioConditioner().process(raw_audio)
        conditioner = transcript.LiveAudioConditioner()
        split_points = (1234, 9100)
        actual = np.concatenate((
            conditioner.process(raw_audio[:split_points[0]]),
            conditioner.process(raw_audio[split_points[0]:split_points[1]]),
            conditioner.process(raw_audio[split_points[1]:]),
        ))

        np.testing.assert_allclose(expected, actual, atol=1e-6, rtol=1e-6)

    def test_stateful_agc_gain_is_continuous_across_buffer_trim(self):
        sample_times = np.arange(transcript.SAMPLE_RATE) / transcript.SAMPLE_RATE
        speech = (0.01 * np.sin(2 * np.pi * 500 * sample_times)).astype(np.float32)
        agc = transcript.StatefulSlowAgc()
        first = agc.process(speech[: transcript.SAMPLE_RATE // 2])
        gain_before_trim = agc.gain
        second = agc.process(speech[transcript.SAMPLE_RATE // 2 :])

        self.assertGreater(gain_before_trim, 1.0)
        self.assertGreater(agc.gain, 1.0)
        self.assertLess(abs(float(second[0]) - float(first[-1])), 0.04)

    def test_live_transcribe_passes_conditioned_copy_to_model(self):
        captured = {}

        class RecordingModel:
            def transcribe(self, audio, **kwargs):
                captured["audio"] = audio.copy()
                return [], None

        sample_times = np.arange(transcript.SAMPLE_RATE) / transcript.SAMPLE_RATE
        raw_audio = (0.02 * np.sin(2 * np.pi * 500 * sample_times) + 0.1).astype(np.float32)
        original = raw_audio.copy()

        transcript.transcribe_audio_segments(
            RecordingModel(),
            raw_audio,
            "en",
            datetime.now(timezone.utc),
            beam_size=2,
            best_of=2,
            previous_text=False,
        )

        np.testing.assert_array_equal(original, raw_audio)
        self.assertLess(
            abs(float(captured["audio"][transcript.SAMPLE_RATE // 2 :].mean())),
            0.001,
        )
        self.assertFalse(np.array_equal(original, captured["audio"]))

    def test_final_transcribe_conditions_copy_without_changing_saved_wav(self):
        captured = {}

        class RecordingModel:
            def transcribe(self, audio, **kwargs):
                captured["audio"] = audio.copy()
                return [], SimpleNamespace(duration=1.0)

        with tempfile.TemporaryDirectory() as directory:
            wave_path = Path(directory) / "raw-capture.wav"
            sample_times = np.arange(transcript.SAMPLE_RATE) / transcript.SAMPLE_RATE
            raw_audio = (
                0.02 * np.sin(2 * np.pi * 500 * sample_times) + 0.1
            ).astype(np.float32)
            transcript.initialize_incremental_wave(wave_path)
            transcript.append_wave_audio(wave_path, raw_audio.reshape(-1, 1))
            before_hash = hashlib.sha256(wave_path.read_bytes()).hexdigest()

            transcript.transcribe_saved_audio_with_timings(
                RecordingModel(),
                wave_path,
                "en",
                datetime.now(timezone.utc),
                beam_size=8,
                best_of=8,
                previous_text=True,
            )

            after_hash = hashlib.sha256(wave_path.read_bytes()).hexdigest()

        self.assertEqual(before_hash, after_hash)
        self.assertLess(
            abs(float(captured["audio"][transcript.SAMPLE_RATE // 2 :].mean())),
            0.001,
        )
        self.assertFalse(np.array_equal(raw_audio, captured["audio"]))

    def test_wave_capture_keeps_unconditioned_samples(self):
        with tempfile.TemporaryDirectory() as directory:
            wave_path = Path(directory) / "raw-capture.wav"
            raw_audio = np.linspace(-0.25, 0.25, 1000, dtype=np.float32).reshape(-1, 1)
            expected_bytes = transcript.pcm16_audio_bytes(raw_audio)

            transcript.condition_live_audio(raw_audio[:, 0])
            transcript.append_wave_audio(wave_path, raw_audio)

            with wave.open(str(wave_path), "rb") as handle:
                self.assertEqual(expected_bytes, handle.readframes(handle.getnframes()))

    def test_clipping_watchdog_reports_point_one_percent_once(self):
        watchdog = transcript.AudioClippingWatchdog()
        audio = np.zeros(10000, dtype=np.float32)
        audio[:10] = 1.0

        self.assertAlmostEqual(0.1, watchdog.update(audio), places=6)
        self.assertIsNone(watchdog.update(audio))

    def test_clipping_watchdog_ignores_subthreshold_clipping(self):
        audio = np.zeros(10000, dtype=np.float32)
        audio[:9] = -1.0

        self.assertIsNone(transcript.AudioClippingWatchdog().update(audio))

    def test_decode_hotwords_are_capped(self):
        settings = transcript.build_transcribe_kwargs(
            "en",
            beam_size=2,
            best_of=2,
            previous_text=False,
            final_pass=False,
            hotwords=", ".join(
                f"term-{index}" for index in range(transcript.MAX_HOTWORD_TERMS + 5)
            ),
        )

        self.assertEqual(transcript.MAX_HOTWORD_TERMS, len(settings["hotwords"].split(",")))

    def test_default_hotwords_include_domain_phrases_and_project_names(self):
        hotwords = transcript.limited_hotwords(transcript.DEFAULT_PATHOLOGY_HOTWORDS)

        self.assertIn("ductal carcinoma in situ", hotwords)
        self.assertIn("reflex in situ hybridization", hotwords)
        self.assertIn("HULA Lab", hotwords)
        self.assertIn("QuPath", hotwords)

    def test_clinical_large_v3_uses_fast_english_live_preview(self):
        candidates = transcript.choose_live_model_candidates("large-v3", "en")

        self.assertEqual("small.en", candidates[0])
        self.assertIn("large-v3", candidates)

    def test_english_live_preview_prefers_small_en_over_distil_small(self):
        """small.en measures 19.27% WER against distil-small.en's 63.97% on the
        Phase 0 fixture, for 0.2 s more per 20-second window. The distilled model
        is a degraded speed fallback only, so it must never be selected first."""
        for final_model in ("large-v3", "small.en", "distil-large-v3"):
            with self.subTest(final_model=final_model):
                candidates = transcript.choose_live_model_candidates(final_model, "en")

                self.assertEqual("small.en", candidates[0])
                if "distil-small.en" in candidates:
                    self.assertLess(
                        candidates.index("small.en"),
                        candidates.index("distil-small.en"),
                    )

    def test_non_english_live_preview_avoids_english_only_models(self):
        candidates = transcript.choose_live_model_candidates("large-v3", "vi")

        for english_only in ("small.en", "distil-small.en"):
            self.assertNotIn(english_only, candidates)

    def test_int8_float32_model_load_falls_back_to_int8(self):
        attempts = []
        expected_model = object()

        def model_factory(model_name, *, device, compute_type):
            attempts.append((model_name, device, compute_type))
            if compute_type == "int8_float32":
                raise RuntimeError("unsupported compute type")
            return expected_model

        model, compute_type = transcript.load_cpu_whisper_model(
            model_factory,
            "example-model",
            "int8_float32",
        )

        self.assertIs(expected_model, model)
        self.assertEqual("int8", compute_type)
        self.assertEqual(
            [
                ("example-model", "cpu", "int8_float32"),
                ("example-model", "cpu", "int8"),
            ],
            attempts,
        )

    def test_final_transcript_exports_segment_and_word_timing_rows(self):
        recording_start = datetime(
            2026, 8, 20, 12, 0, 0, tzinfo=timezone(timedelta(hours=-5))
        )
        segment = SimpleNamespace(
            start=1.25,
            end=2.75,
            text=" lymph node negative",
            avg_logprob=0.0,
            no_speech_prob=0.0,
            compression_ratio=1.0,
            words=[
                SimpleNamespace(start=1.25, end=1.80, word=" lymph"),
                SimpleNamespace(start=1.85, end=2.75, word=" node negative"),
            ],
        )
        model = SimpleNamespace(transcribe=lambda *args, **kwargs: ([segment], None))

        with patch.object(
            transcript,
            "decode_saved_audio",
            return_value=np.zeros(transcript.SAMPLE_RATE * 3, dtype=np.float32),
        ):
            lines, segment_rows, word_rows = transcript.transcribe_saved_audio_with_timings(
                model,
                Path("capture.wav"),
                "en",
                recording_start,
                beam_size=8,
                best_of=8,
                previous_text=True,
            )

        self.assertEqual("[2026-08-20T12:00:01.250] lymph node negative", lines[0])
        self.assertEqual(1250, segment_rows[0]["start_ms"])
        self.assertEqual(2750, segment_rows[0]["end_ms"])
        self.assertTrue(segment_rows[0]["start_utc"].endswith("Z"))
        self.assertEqual([1250, 1850], [row["start_ms"] for row in word_rows])

    def test_finalize_existing_capture_regenerates_transcript_without_capture(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output_path = root / "case_transcript.txt"
            wave_path = root / "case_transcript_audio.wav"
            output_path.write_text(
                "[2026-08-26T12:00:00.000] live preview\n",
                encoding="utf-8",
            )
            transcript.initialize_incremental_wave(wave_path)
            transcript.append_wave_bytes(
                wave_path, b"\x00\x00" * transcript.SAMPLE_RATE
            )
            recording_start = datetime(2026, 8, 26, 12, 0, tzinfo=timezone.utc)
            segment = SimpleNamespace(
                start=0.0,
                end=1.0,
                text=" final transcript",
                avg_logprob=0.0,
                no_speech_prob=0.0,
                compression_ratio=1.0,
                words=[],
            )
            model = SimpleNamespace(transcribe=lambda *args, **kwargs: (
                [segment], SimpleNamespace(duration=1.0)
            ))
            model_factory = lambda *args, **kwargs: model
            args = SimpleNamespace(
                model="large-v3",
                compute_type="int8_float32",
                beam_size=8,
                best_of=8,
                hotwords="Gleason",
            )
            stdout = io.StringIO()

            with contextlib.redirect_stdout(stdout):
                exit_code = transcript.finalize_existing_capture(
                    model_factory,
                    args,
                    output_path,
                    wave_path,
                    recording_start,
                    "en",
                    True,
                )

            self.assertEqual(0, exit_code)
            self.assertIn("final transcript", output_path.read_text(encoding="utf-8"))
            self.assertTrue(root.joinpath("case_transcript_live.txt").is_file())
            self.assertTrue(root.joinpath("case_transcript_segments.csv").is_file())
            self.assertIn("FINALIZATION_RESULT\tfinal", stdout.getvalue())

    def test_generated_wav_keeps_click_inside_spoken_word_timing(self):
        with tempfile.TemporaryDirectory() as directory:
            audio_path = Path(directory) / "alignment.wav"
            transcript.initialize_incremental_wave(audio_path)
            transcript.append_wave_bytes(
                audio_path, b"\x00\x00" * transcript.SAMPLE_RATE * 2
            )
            origin = datetime(2026, 8, 25, 20, 0, 0, tzinfo=timezone.utc)
            word = SimpleNamespace(start=1.0, end=1.2, word=" margin")
            segment = SimpleNamespace(
                start=0.8, end=1.4, text=" margin",
                avg_logprob=0.0, no_speech_prob=0.0, compression_ratio=1.0,
                words=[word],
            )
            model = SimpleNamespace(transcribe=lambda *args, **kwargs: (
                [segment], SimpleNamespace(duration=2.0)
            ))
            progress = []

            _, _, word_rows = transcript.transcribe_saved_audio_with_timings(
                model, audio_path, "en", origin, 8, 8, True,
                progress_callback=lambda done, total: progress.append((done, total)),
            )

            click_elapsed_ms = 1100
            self.assertLessEqual(word_rows[0]["start_ms"], click_elapsed_ms)
            self.assertGreaterEqual(word_rows[0]["end_ms"], click_elapsed_ms)
            self.assertEqual((0.0, 2.0), progress[0])
            self.assertEqual((2.0, 2.0), progress[-1])


if __name__ == "__main__":
    unittest.main()
