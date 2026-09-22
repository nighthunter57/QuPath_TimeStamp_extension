# TimeStamp QuPath extension

A QuPath 0.6.0 extension that records timestamped image events alongside a live
microphone transcript. Two halves that talk over a line protocol:

- **`src/main/java/qupath/ext/timestamp/TimeStamp.java`** — the JavaFX panel, event
  log, and session save. Launches and supervises the Python helper.
- **`scripts/live_whisper_demo.py`** — captures microphone audio, decodes it live
  with faster-whisper, and regenerates a higher-accuracy final transcript from the
  full recording when capture stops.

## Active plan

**Live transcription accuracy work is planned in
[`docs/LIVE_TRANSCRIPT_ACCURACY_PLAN.md`](docs/LIVE_TRANSCRIPT_ACCURACY_PLAN.md).**
Read it before changing decoding, live-transcript merging, or the transcript
panel.

The phases are ordered by dependency, not by size. **Do one phase at a time**, and
do not skip ahead — in particular, raising the live beam size (Phase 2) before
LocalAgreement streaming (Phase 3) will make the decoder fall behind the
microphone.

## Commands

```bash
# Java build and tests
./gradlew build

# Install the built extension into a local QuPath
./gradlew deployToQuPath

# Python helper tests (fast, no audio hardware needed)
.venv-whisper/bin/python -m unittest scripts.tests.test_live_whisper_demo

# Run the helper standalone against a demo session
./scripts/start_live_transcript.sh ./demo-output/<session> large-v3 en
```

The Python side needs `.venv-whisper` — created from `requirements-whisper.txt`,
see README. Java needs a JDK 21.

## Invariants

These are load-bearing. Breaking them corrupts recorded sessions rather than
failing loudly.

- **`RECORDING_ORIGIN` is the only recording clock.** Elapsed event times are
  `event.recordedAtUtc - RECORDING_ORIGIN`. `TRANSCRIPT_READY` does not establish
  it. Missing or negative elapsed values serialize as empty CSV cells and JSON
  `null` — never as zero.
- **The stdout protocol is a contract.** It is specified in
  [`docs/TRANSCRIPT_PROTOCOL.md`](docs/TRANSCRIPT_PROTOCOL.md) and enforced on both
  sides: `PROTOCOL_FIELDS` in `live_whisper_demo.py` and the
  `TranscriptMessageType` enum in `TimeStamp.java`. Adding or changing a message
  means updating all three, plus
  `test_protocol_grammar_covers_every_message`, which asserts exact key-set
  equality and will fail otherwise. A known message with the wrong field count is
  malformed and must not change UI state.
- **The captured WAV stays unprocessed.** Live-decode conditioning (filtering,
  gain) must not reach `append_wave_audio`. The final pass and any later
  re-analysis depend on the original signal.
- **Audio capture must survive a crash.** Audio is appended to an incremental WAV
  with a rewritten header as it arrives, and the transcript is written via a temp
  file plus atomic replace. Keep both properties.
- **The live transcript is a preview, not the deliverable.** The final pass
  rewrites it from the full recording on stop. Live-path changes must not assume
  their output is what gets saved.

## Conventions

- Java: 4-space indent, `static` panel state on `TimeStamp`, all FX mutation via
  `Platform.runLater`.
- Python: type hints on function signatures, module-level `UPPER_SNAKE` constants
  for every tunable — no magic numbers at call sites.
- Docs live in `docs/` as `SCREAMING_SNAKE.md`.
- Add a test to `scripts/tests/test_live_whisper_demo.py` for any transcript
  logic change. The suite is pure logic and runs in milliseconds; there is no
  excuse for skipping it.
