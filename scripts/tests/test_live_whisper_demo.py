import contextlib
import csv
import hashlib
import io
import json
import queue
import sys
import threading
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

    def test_interactive_pause_resume_during_inference_preserves_one_wav_and_clock(self):
        origin = datetime(2026, 9, 14, tzinfo=timezone.utc)
        now = [origin]
        opened = [0]
        decoding = threading.Event()
        paused = threading.Event()
        resumed = threading.Event()
        model_loads = []
        class Clock(datetime):
            @classmethod
            def now(cls, tz=None):
                return now[0]
        class Microphone:
            active = True
            def __init__(self, **kwargs):
                self.callback = kwargs["callback"]
            def __enter__(self):
                offset, count = (0, 24) if opened[0] == 0 else (20, 12)
                opened[0] += 1
                for index in range(count):
                    now[0] = origin + timedelta(seconds=offset + (index + 1) * .5)
                    chunk = np.full((8000, 1), (index + 1 + (24 if offset else 0)) / 100, dtype=np.float32)
                    self.callback(chunk, 8000, SimpleNamespace(inputBufferAdcTime=index * .5), None)
                return self
            def __exit__(self, *args):
                self.active = False
        class Commands:
            def __iter__(self):
                if not decoding.wait(3):
                    raise AssertionError("Decoder did not start")
                yield "PAUSE\n"
                if not paused.wait(3):
                    raise AssertionError("Pause blocked on inference")
                yield "RESUME\n"
                if not resumed.wait(3):
                    raise AssertionError("Resume blocked on inference")
                yield "STOP\n"
        def decode(audio, **kwargs):
            decoding.set()
            if not resumed.wait(4):
                raise AssertionError("Control path waited for decoder")
            seconds = len(audio) / transcript.SAMPLE_RATE
            words = [SimpleNamespace(start=i / transcript.SAMPLE_RATE,
                end=(i + 8000) / transcript.SAMPLE_RATE,
                word=f"w{round(float(audio[i]) * 100)}", probability=.9)
                for i in range(0, len(audio), 8000)]
            return [SimpleNamespace(start=0, end=seconds, text=" ".join(w.word for w in words),
                avg_logprob=0, no_speech_prob=0, compression_ratio=1,
                words=words)], None
        def model(*args, **kwargs):
            model_loads.append(1)
            return SimpleNamespace(transcribe=decode)
        emit = transcript.emit_protocol_message
        def announce(kind, *fields):
            emit(kind, *fields)
            if kind == "CAPTURE_STATE":
                (paused if fields[0] == "paused" else resumed).set()
        fake_sd = SimpleNamespace(InputStream=Microphone, check_input_settings=lambda **kw: None,
                                  PortAudioError=type("PortAudioError", (Exception,), {}))
        with tempfile.TemporaryDirectory() as directory, contextlib.ExitStack() as stack:
            path = Path(directory) / "interactive.txt"
            stack.enter_context(patch.dict(sys.modules, {"sounddevice": fake_sd,
                "faster_whisper": SimpleNamespace(WhisperModel=model)}))
            stack.enter_context(patch.object(sys, "argv", ["helper", "--output", str(path),
                "--capture-only", "--interactive-control"]))
            stack.enter_context(patch.object(sys, "stdin", Commands()))
            stack.enter_context(patch.object(transcript, "datetime", Clock))
            stack.enter_context(patch.object(transcript.signal, "signal"))
            stack.enter_context(patch.object(transcript, "emit_protocol_message", side_effect=announce))
            stack.enter_context(patch.object(transcript, "LiveAudioConditioner",
                return_value=SimpleNamespace(process=lambda audio: audio)))
            stack.enter_context(patch.object(transcript, "resolve_or_fallback_input_device", return_value=None))
            stack.enter_context(patch.object(transcript, "resolve_live_engine", return_value="whisper"))
            stack.enter_context(patch.object(transcript, "should_buffer_live_audio", return_value=True))
            stack.enter_context(patch.object(transcript, "SignalQualityAnalyzer", return_value=SimpleNamespace(update=lambda a: None)))
            output = stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
            errors = stack.enter_context(contextlib.redirect_stderr(io.StringIO()))
            self.assertEqual(0, transcript.main())
            self.assertTrue(resumed.is_set(), output.getvalue() + errors.getvalue())
            self.assertEqual(2, opened[0])
            self.assertEqual(1, len(model_loads))
            self.assertEqual(26, transcript.wave_audio_duration_seconds(path.with_name("interactive_audio.wav")))
            self.assertEqual(origin, transcript.read_recording_start(path.with_name("interactive_audio.start.txt")))
            self.assertEqual(1, output.getvalue().count("RECORDING_ORIGIN\t"))
            self.assertEqual([f"w{i}" for i in range(1, 37)],
                [word for word in path.read_text().split() if word.startswith("w")])

    def test_disk_backlog_preserves_original_float_samples_and_order(self):
        with tempfile.TemporaryDirectory() as directory:
            backlog = transcript.DiskAudioQueue(directory)
            try:
                origin = datetime.now(timezone.utc)
                chunks = [np.full((8000, 1), i / 37, dtype=np.float32) for i in range(20)]
                for index, chunk in enumerate(chunks):
                    backlog.put((chunk, origin + timedelta(seconds=index * .5)))
                for index, chunk in enumerate(chunks):
                    restored, at = backlog.get_nowait()
                    np.testing.assert_array_equal(chunk, restored)
                    self.assertEqual(origin + timedelta(seconds=index * .5), at)
                self.assertTrue(backlog.empty())
                self.assertEqual(0, backlog.write_offset)
                backlog.put((chunks[0], origin))
                np.testing.assert_array_equal(chunks[0], backlog.get_nowait()[0])
            finally:
                backlog.close()

    def test_pause_resume_acknowledges_closed_stream_without_decoder(self):
        events = queue.Queue()
        stream_open = [False]
        flushes = []
        class Stream:
            def __enter__(self):
                stream_open[0] = True
                return self
            def __exit__(self, *args):
                stream_open[0] = False
        def announce(state):
            events.put((state, stream_open[0]))
        controller = transcript.CaptureController(Stream, lambda: flushes.append("saved"), lambda: None, announce)
        worker = threading.Thread(target=controller.run)
        worker.start()
        try:
            self.assertEqual(("ready", True), events.get(timeout=2))
            for _ in range(10):
                controller.command("PAUSE")
                self.assertEqual(("paused", False), events.get(timeout=2))
                controller.command("RESUME")
                self.assertEqual(("recording", True), events.get(timeout=2))
            controller.command("STOP")
            self.assertTrue(controller.finished.wait(2))
            self.assertIsNone(controller.error)
            self.assertFalse(stream_open[0])
            self.assertEqual(11, len(flushes))
        finally:
            controller.command("STOP")
            worker.join(2)

    def test_closed_control_pipe_stops_capture(self):
        controller = transcript.CaptureController(None, None, None, None)
        controller.read_commands(io.StringIO("PAUSE\nRESUME\n"))
        self.assertEqual(["RESUME", "PAUSE", "RESUME", "STOP"],
                         [controller.commands.get_nowait() for _ in range(4)])

    def test_controller_closes_microphone_on_writer_failure(self):
        closed = threading.Event()
        class Stream:
            def __enter__(self): return self
            def __exit__(self, *args): closed.set()
        def fail(): raise OSError("disk full")
        controller = transcript.CaptureController(Stream, lambda: None, lambda: None, lambda state: None, fail)
        worker = threading.Thread(target=controller.run)
        worker.start()
        try:
            self.assertTrue(controller.finished.wait(2))
            self.assertTrue(closed.is_set())
            self.assertIn("disk full", str(controller.error))
        finally:
            controller.command("STOP")
            worker.join(2)

    def test_writer_flush_preserves_audio_before_acknowledgement(self):
        saved = []
        writer = transcript.AudioCaptureWriter(lambda chunk, at: saved.append(chunk), queue.Queue())
        try:
            writer.submit("raw audio", datetime.now(timezone.utc))
            writer.flush()
            self.assertEqual(["raw audio"], saved)
        finally:
            writer.close()

    def test_capture_shutdown_decodes_all_backlog_and_flushes_original_audio(self):
        self.assert_captured_turns([True] * 36, [12.0, 6.0])

    def test_capture_backlog_preserves_silence_between_short_turns(self):
        self.assert_captured_turns([True, False, False, True, False, False, True], [0.5, 0.5, 0.5])

    def assert_captured_turns(self, speech_mask, expected_seconds):
        origin = datetime(2026, 9, 5, tzinfo=timezone.utc)
        now = [origin]
        handlers = {}
        decoded_seconds = []
        class ClockDatetime(datetime):
            @classmethod
            def now(cls, tz=None):
                return now[0]
        class Microphone:
            def __init__(self, **kwargs):
                self.callback = kwargs["callback"]
            def __enter__(self):
                for index in range(len(speech_mask)):
                    now[0] = origin + timedelta(seconds=(index + 1) * 0.5)
                    chunk = (0.1 * np.sin(2 * np.pi * 220 * np.arange(8000) / transcript.SAMPLE_RATE)).astype(np.float32)
                    self.callback(chunk.reshape(-1, 1), 8000,
                                  SimpleNamespace(inputBufferAdcTime=index * 0.5), None)
                handlers[transcript.signal.SIGINT](0, None)
                return self
            def __exit__(self, *args):
                pass
        def decode(audio, **kwargs):
            seconds = len(audio) / transcript.SAMPLE_RATE
            decoded_seconds.append(seconds)
            return [SimpleNamespace(start=0, end=seconds, text="recorded speech",
                avg_logprob=0, no_speech_prob=0, compression_ratio=1,
                words=[SimpleNamespace(start=0, end=seconds, word="recorded speech", probability=0.9)])], None
        fake_sd = SimpleNamespace(InputStream=Microphone, check_input_settings=lambda **kw: None,
                                  PortAudioError=type("PortAudioError", (Exception,), {}))
        fake_fw = SimpleNamespace(WhisperModel=lambda *a, **kw: SimpleNamespace(transcribe=decode))
        with tempfile.TemporaryDirectory() as directory, contextlib.ExitStack() as stack:
            path = Path(directory) / "capture.txt"
            stack.enter_context(patch.dict(sys.modules, {"sounddevice": fake_sd, "faster_whisper": fake_fw}))
            stack.enter_context(patch.object(sys, "argv", ["helper", "--output", str(path), "--capture-only"]))
            stack.enter_context(patch.object(transcript, "datetime", ClockDatetime))
            stack.enter_context(patch.object(transcript.signal, "signal", side_effect=lambda key, fn: handlers.update({key: fn})))
            stack.enter_context(patch.object(transcript, "resolve_or_fallback_input_device", return_value=None))
            stack.enter_context(patch.object(transcript, "resolve_live_engine", return_value="whisper"))
            stack.enter_context(patch.object(transcript, "should_buffer_live_audio", side_effect=speech_mask))
            stack.enter_context(patch.object(transcript, "SignalQualityAnalyzer", return_value=SimpleNamespace(update=lambda a: None)))
            output = stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
            stack.enter_context(contextlib.redirect_stderr(io.StringIO()))
            self.assertEqual(0, transcript.main())
            self.assertEqual(expected_seconds, decoded_seconds)
            self.assertEqual(len(speech_mask) * 0.5, transcript.wave_audio_duration_seconds(path.with_name("capture_audio.wav")))
            self.assertEqual(len(expected_seconds), path.read_text().count("recorded speech"))
            self.assertEqual(1, output.getvalue().count("FINALIZATION_RESULT\tpaused"))
            self.assertEqual(1, output.getvalue().count("RECORDING_ORIGIN\t"))
            self.assertEqual(origin, transcript.read_recording_start(path.with_name("capture_audio.start.txt")))

    def test_replay_models_slow_decoding_without_losing_backlogged_words(self):
        from scripts.replay_live_fixture import replay_audio
        clock = [0.0]
        windows = []
        class FakeTranscriber:
            def __init__(self):
                self.agreement = transcript.LocalAgreementState()
            def accept_audio(self, audio, start, force=False, audio_timeline=None, **kwargs):
                windows.append(len(audio) / transcript.SAMPLE_RATE)
                clock[0] += 15.0
                words = []
                for offset in np.arange(0, len(audio) / transcript.SAMPLE_RATE, 0.5):
                    begin = audio_timeline.map_offset(float(offset))
                    end = audio_timeline.map_offset(float(offset + 0.5), prefer_end=True)
                    index = round((begin - datetime(2026, 1, 1, tzinfo=timezone.utc)).total_seconds() * 2)
                    words.append((begin, end, f"w{index}"))
                committed, provisional = self.agreement.update(words, force=force)
                return transcript.LiveTranscriptionUpdate(tuple(self.agreement.committed_words),
                    tuple(provisional), committed[-1][1] if committed else None)
            def force_current(self, end):
                committed, _ = self.agreement.update([], force=True)
                return transcript.LiveTranscriptionUpdate(tuple(self.agreement.committed_words), (), end)
            def reset_turn(self):
                self.agreement = transcript.LocalAgreementState()
        result = replay_audio(np.full(transcript.SAMPLE_RATE * 24, 0.1, dtype=np.float32),
                              FakeTranscriber(), speech_detector=lambda a: True, clock=lambda: clock[0])
        self.assertLessEqual(result["max_buffer_seconds"], 12)
        self.assertLessEqual(max(windows), 12)
        self.assertEqual(48, result["committed_words"])
        import re
        self.assertEqual([f"w{i}" for i in range(48)], re.findall(r"\bw\d+\b", result["hypothesis"]))
        self.assertGreater(result["display_delay_p95_seconds"], 15)

        # A slow decode queues two pauses and sub-minimum utterances. Neither
        # the drain nor the decode cadence may merge them into the next turn.
        clock[0] = 0.0
        windows.clear()
        audio = np.repeat(np.array([.1, 0, 0, .1, 0, 0, .1], dtype=np.float32),
                          transcript.SAMPLE_RATE // 2)
        result = replay_audio(audio, FakeTranscriber(),
                              speech_detector=lambda a: bool(np.any(a)), clock=lambda: clock[0])
        self.assertEqual(3, result["committed_words"])
        self.assertEqual(2, result["endpoint_reasons"]["silence"])
        self.assertEqual(1, result["endpoint_reasons"]["stop"])
        self.assertTrue(all(window == .5 for window in windows), windows)

    def test_word_confidence_survives_live_agreement_and_final_export(self):
        origin = datetime(2026, 9, 5, tzinfo=timezone.utc)
        segment = SimpleNamespace(start=0, end=1, text="negative margin", words=[
            SimpleNamespace(start=0, end=0.5, word="negative", probability=0.35),
            SimpleNamespace(start=0.5, end=1, word=" margin", probability=0.98)],
            avg_logprob=0, no_speech_prob=0, compression_ratio=1)
        model = SimpleNamespace(transcribe=lambda *a, **kw: ([segment], SimpleNamespace(duration=1)))
        audio = np.full(transcript.SAMPLE_RATE, 0.1, dtype=np.float32)
        live = transcript.WhisperLiveTranscriber(model, "fake", "en", 2, 2, None)
        live.accept_audio(audio, origin)
        update = live.accept_audio(audio, origin)
        self.assertEqual([0.35, 0.98], [w.confidence for w in update.committed_words])
        with patch.object(transcript, "decode_saved_audio", return_value=audio):
            _, _, rows = transcript.transcribe_saved_audio_with_timings(
                model, Path("unused.wav"), "en", origin, 8, 8, False)
        self.assertEqual([0.35, 0.98], [r["confidence"] for r in rows])
        self.assertEqual([True, False], [r["needs_review"] for r in rows])

    def test_uncertain_speech_is_marked_but_non_speech_is_still_rejected(self):
        origin = datetime(2026, 9, 5, tzinfo=timezone.utc)
        audio = np.full(transcript.SAMPLE_RATE, 0.1, dtype=np.float32)
        segment = SimpleNamespace(start=0, end=1, text="guessed phrase", words=[],
                                  avg_logprob=-2, no_speech_prob=0.1, compression_ratio=1)
        model = SimpleNamespace(transcribe=lambda *a, **kw: ([segment], SimpleNamespace(duration=1)))
        for non_speech in (False, True):
            segment.no_speech_prob = 0.9 if non_speech else 0.1
            live = transcript.transcribe_audio_segments(model, audio, "en", origin, 2, 2, False)
            with patch.object(transcript, "decode_saved_audio", return_value=audio):
                lines, _, words = transcript.transcribe_saved_audio_with_timings(
                    model, Path("unused.wav"), "en", origin, 8, 8, False)
            if non_speech:
                self.assertEqual(([], [], []), (live, lines, words))
            else:
                self.assertEqual(transcript.UNCLEAR_SPEECH_MARKER, live[0][2])
                self.assertIn(transcript.UNCLEAR_SPEECH_MARKER, lines[0])
                self.assertTrue(words[0]["needs_review"])
                self.assertEqual(0, words[0]["start_ms"])
                self.assertEqual(1000, words[0]["end_ms"])

    def test_review_metadata_maps_repeated_words_and_unicode_and_rejects_stale_text(self):
        origin = datetime(2026, 9, 5, tzinfo=timezone.utc)
        line = transcript.format_transcript_line(origin, "🧪 no no invasion")
        rows = [{"word": word, "start_ms": i * 500, "end_ms": (i + 1) * 500,
                 "confidence": 0.4} for i, word in enumerate(("🧪", "no", "no", "invasion"))]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "capture.txt"
            transcript.write_lines(path, [line])
            transcript.write_review_metadata(path, [line], rows)
            document = json.loads(path.with_name("capture_review.json").read_text())
            encoded = document["transcript"].encode("utf-16-le")
            for word in document["words"]:
                self.assertEqual(word["word"], encoded[word["start"] * 2:word["end"] * 2].decode("utf-16-le"))
            self.assertEqual(4, len(transcript.load_review_rows(path)))
            transcript.write_lines(path, ["edited"])
            self.assertEqual([], transcript.load_review_rows(path))

    def test_unknown_confidence_is_never_fabricated(self):
        for value in (None, "bad", float("nan"), float("inf"), -0.1, 1.1):
            self.assertIsNone(transcript.word_confidence(value))

    def test_separate_unclear_passages_keep_their_own_review_markers(self):
        origin = datetime(2026, 9, 5, tzinfo=timezone.utc)
        words = [transcript.TimedWord(origin + timedelta(seconds=i), origin + timedelta(seconds=i + 1),
                                     transcript.UNCLEAR_SPEECH_MARKER) for i in range(6)]
        entries = transcript.group_committed_words(words)
        self.assertEqual(6, len(entries))
        lines = [transcript.format_transcript_line(t, text) for t, text in entries]
        kept, _, _, dropped = transcript.drop_repeated_final_segments(
            lines, [{"segment_index": i} for i in range(6)], [])
        self.assertEqual(lines, kept)
        self.assertEqual(0, dropped)
        self.assertEqual("", transcript.local_agreement_prompt(entries, words))

    def test_backlog_boundary_preserves_undecoded_speech_across_silence(self):
        origin = datetime(2026, 9, 5, tzinfo=timezone.utc)
        timeline = transcript.SpeechDecodeTimeline()
        timeline.append(origin, 10 * transcript.SAMPLE_RATE)
        timeline.append(origin + timedelta(seconds=15), 8 * transcript.SAMPLE_RATE)
        count, boundary = transcript.live_decode_boundary(
            timeline, timeline.frame_count, origin + timedelta(seconds=24))
        self.assertEqual(12 * transcript.SAMPLE_RATE, count)
        self.assertEqual(origin + timedelta(seconds=17), boundary)
        self.assertEqual(count, timeline.trim_through(boundary))
        self.assertEqual(6 * transcript.SAMPLE_RATE, timeline.frame_count)
        count, boundary = transcript.live_decode_boundary(
            timeline, timeline.frame_count, origin + timedelta(seconds=24))
        self.assertEqual(6 * transcript.SAMPLE_RATE, count)
        self.assertEqual(count, timeline.trim_through(boundary))

    def test_audio_writer_saves_while_decoder_is_idle_and_drains_on_close(self):
        origin = datetime(2026, 9, 5, tzinfo=timezone.utc)
        ready = queue.Queue()
        persisted = threading.Event()
        chunks = [np.full((8000, 1), level, dtype=np.float32) for level in (0.1, 0.2)]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "capture.wav"
            def persist(chunk, started_at):
                transcript.append_wave_audio(path, chunk)
                persisted.set()
            writer = transcript.AudioCaptureWriter(persist, ready)
            try:
                writer.submit(chunks[0], origin)
                self.assertTrue(persisted.wait(2))
                self.assertEqual(0.5, transcript.wave_audio_duration_seconds(path))
                writer.submit(chunks[1], origin + timedelta(seconds=0.5))
            finally:
                writer.close()
            self.assertEqual(2, ready.qsize())
            with wave.open(str(path), "rb") as audio:
                self.assertEqual(b"".join(transcript.pcm16_audio_bytes(c) for c in chunks),
                                 audio.readframes(audio.getnframes()))

    def test_audio_writer_reports_disk_failure_without_publishing_unsaved_audio(self):
        ready = queue.Queue()
        def fail(*args):
            raise OSError("disk full")
        writer = transcript.AudioCaptureWriter(fail, ready)
        writer.submit(np.zeros((8000, 1)), datetime.now(timezone.utc))
        with self.assertRaisesRegex(RuntimeError, "disk full"):
            writer.close()
        self.assertTrue(ready.empty())

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
            "CAPTURE_STATE": ("paused",),
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

    def test_protocol_grammar_matches_java_message_enum(self):
        """The Java enum is the other half of the contract; drift must fail here."""
        import re

        java_source = (
            Path(__file__).resolve().parents[2]
            / "src" / "main" / "java" / "qupath" / "ext" / "timestamp" / "TimeStamp.java"
        )
        enum_body = re.search(
            r"enum TranscriptMessageType \{(.*?)\n\s*private final int",
            java_source.read_text(encoding="utf-8"),
            re.S,
        )
        self.assertIsNotNone(
            enum_body, "TranscriptMessageType not found in TimeStamp.java"
        )
        # LOG(-1) and MALFORMED(-1) are Java-side sentinels, never sent on the wire.
        java_fields = {
            name: int(count)
            for name, count in re.findall(r"([A-Z_]+)\((-?\d+)\)", enum_body.group(1))
            if int(count) >= 0
        }
        self.assertEqual(transcript.PROTOCOL_FIELDS, java_fields)

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

    def test_final_progress_precedes_next_decode_and_preserves_tail_filter(self):
        events = []
        segments = [SimpleNamespace(start=i * 2, end=i * 2 + 1,
                    text="Thank you for watching." if i == 3 else "recorded speech",
                    words=[], avg_logprob=0, no_speech_prob=0, compression_ratio=1)
                    for i in range(4)]
        def decode():
            for i, segment in enumerate(segments):
                events.append(("decode", i))
                yield segment
        model = SimpleNamespace(transcribe=lambda *a, **kw: (decode(), SimpleNamespace(duration=8)))
        with patch.object(transcript, "decode_saved_audio", return_value=np.zeros(transcript.SAMPLE_RATE * 8)):
            lines, rows, _ = transcript.transcribe_saved_audio_with_timings(
                model, Path("unused.wav"), "en", datetime(2026, 1, 1, tzinfo=timezone.utc), 8, 8, False,
                progress_callback=lambda done, total: events.append(("progress", done)))
        self.assertEqual(("progress", 0.0), events[0])
        for i in range(3):
            self.assertLess(events.index(("progress", i * 2 + 1)), events.index(("decode", i + 1)))
        self.assertEqual(("progress", 8.0), events[-1])
        self.assertEqual(3, len(lines))
        self.assertEqual([0, 2000, 4000], [row["start_ms"] for row in rows])
        self.assertEqual(segments[:3], list(transcript.iter_final_segments(segments, None, 8)))

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

    def test_finalization_keeps_audio_times_when_live_phrases_match(self):
        origin = datetime(2026, 8, 26, 12, 0, tzinfo=timezone.utc)
        cases = {
            "same phrase at distant time": [(100, "The surgical margin is negative.")],
            "repeated phrase at different times": [
                (1, "The surgical margin is negative."), (100, "The surgical margin is negative.")],
            "same prefix with revised segmentation": [
                (100, "The surgical margin is negative. No invasion is identified.")],
        }
        final_segments = [
            (2.0, 3.0, "The surgical margin is negative."),
            (5.0, 6.0, "No invasion is identified."),
        ]
        for name, live_entries in cases.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                output = root / "case_transcript.txt"
                wave_path = root / "case_transcript_audio.wav"
                live_text = "".join(transcript.format_transcript_line(
                    origin + timedelta(seconds=offset), text) + "\n"
                    for offset, text in live_entries)
                output.write_text(live_text, encoding="utf-8")
                transcript.initialize_incremental_wave(wave_path)
                transcript.append_wave_audio(wave_path, np.full(
                    (7 * transcript.SAMPLE_RATE, 1), 0.01, dtype=np.float32))
                original_audio = hashlib.sha256(wave_path.read_bytes()).hexdigest()
                segments = []
                for start, end, text in final_segments:
                    tokens = text.split()
                    duration = (end - start) / len(tokens)
                    words = [SimpleNamespace(
                        start=start + index * duration,
                        end=start + (index + 1) * duration,
                        word=" " + token, probability=0.9)
                        for index, token in enumerate(tokens)]
                    segments.append(SimpleNamespace(
                        start=start, end=end, text=text, words=words,
                        avg_logprob=0.0, no_speech_prob=0.0, compression_ratio=1.0))
                model = SimpleNamespace(transcribe=lambda *args, **kwargs: (
                    iter(segments), SimpleNamespace(duration=7.0)))
                args = SimpleNamespace(model="large-v3", compute_type="int8_float32",
                                       beam_size=8, best_of=8, hotwords="Gleason")
                stdout = io.StringIO()
                with contextlib.redirect_stdout(stdout):
                    result = transcript.finalize_existing_capture(
                        lambda *args, **kwargs: model, args, output, wave_path,
                        origin, "en", True)
                final_text = output.read_text(encoding="utf-8")
                expected = "".join(transcript.format_transcript_line(
                    origin + timedelta(seconds=start), text) + "\n"
                    for start, _, text in final_segments)
                self.assertEqual(0, result)
                self.assertEqual(expected, final_text)
                self.assertEqual(live_text, root.joinpath(
                    "case_transcript_live.txt").read_text(encoding="utf-8"))
                self.assertEqual(original_audio, hashlib.sha256(wave_path.read_bytes()).hexdigest())
                with root.joinpath("case_transcript_segments.csv").open(newline="") as source:
                    rows = list(csv.DictReader(source))
                self.assertEqual([2000, 5000], [int(row["start_ms"]) for row in rows])
                line_times = [transcript.parse_transcript_line(line)[0]
                              for line in final_text.splitlines()]
                self.assertEqual(line_times, [datetime.fromisoformat(
                    row["start_utc"].replace("Z", "+00:00")) for row in rows])
                with root.joinpath("case_transcript_words.csv").open(newline="") as source:
                    word_rows = list(csv.DictReader(source))
                review = json.loads(root.joinpath("case_transcript_review.json").read_text())
                self.assertEqual(final_text, review["transcript"])
                self.assertEqual(len(word_rows), len(review["words"]))
                self.assertEqual([int(row["start_ms"]) for row in word_rows],
                                 [word["start_ms"] for word in review["words"]])
                for word in review["words"]:
                    self.assertEqual(word["word"], final_text[word["start"]:word["end"]])
                self.assertIn("FINALIZATION_RESULT\tfinal", stdout.getvalue())

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

        # Transcript lines use the machine's local zone; derive it so CI zones pass.
        local_start = (recording_start + timedelta(seconds=1.25)).astimezone()
        self.assertEqual(
            f"[{local_start:%Y-%m-%dT%H:%M:%S}.250] lymph node negative", lines[0])
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
