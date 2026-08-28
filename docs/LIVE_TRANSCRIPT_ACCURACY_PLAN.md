# Live Transcript Accuracy Plan

Six phases to take the live transcript from repetition loops to roughly 95% word
accuracy, in the order that keeps the decoder ahead of the microphone.

- **Target files:** `scripts/live_whisper_demo.py`, `src/main/java/qupath/ext/timestamp/TimeStamp.java`
- **Stack:** faster-whisper 1.2.1, CTranslate2 4.8.1, macOS arm64 (CPU only — CTranslate2 has no Metal backend)
- **Tests:** `scripts/tests/test_live_whisper_demo.py`, run with
  `.venv-whisper/bin/python -m unittest scripts.tests.test_live_whisper_demo -v`

## Progress

- **Phase 0 complete — 2026-08-26.** The dependency floors are enforced,
  `deepdml/faster-whisper-large-v3-turbo-ct2` is cached, and the installed
  versions are faster-whisper 1.2.1 with CTranslate2 4.8.1.
- The turbo CPU `int8_float32` timing gate passed: a 30-second clip decoded in
  4.632 seconds (0.154 realtime factor), excluding a 3.857-second model load.
  Phase 3 therefore does not need to move ahead of Phase 2 for throughput on the
  baseline machine.
- The ignored local fixture at `demo-output/live-accuracy-phase-0/` contains a
  116.827-second standardized pathology dictation, the untouched incremental
  WAV, a 119-second speech-only regression WAV, live/final transcripts, timing
  CSVs, and scoring notes. The live baseline reproduced the runaway-loop bug in
  a single 211-word line; the final pass completed successfully.
- **Phase 1 complete — 2026-08-26.** Temperature fallback, explicit decode
  thresholds, live/final repetition controls, stricter VAD and RMS gates,
  final-only initial prompting, a 15-term hotword cap, and structural 3-gram
  loop rejection are active. The structural filter runs on both transcript
  paths even when confidence filtering is disabled.
- The original 211-word loop is rejected by the structural detector. Four
  rolling 10-second decodes around its failure timestamp produced no loops with
  the Phase 1 settings. A full virtual-microphone replay was also loop-free, but
  the legacy rolling-window merge retained only 44 of 358 reference words
  (91.62% WER); this is the Phase 3 LocalAgreement failure, not a remaining
  runaway decode. All 29 helper logic tests pass.
- The unchanged production `large-v3` final path produced the same 15.36% WER as
  the Phase 0 baseline with zero structural loops. It took 241.362 seconds to
  decode the 119-second fixture, reinforcing the planned Phase 2 batching work.
- **Phase 2 safe subset complete — 2026-08-26.** The application now defaults to
  `int8_float32` with an automatic `int8` load fallback. `distil-small.en`
  decoded a 30-second clip in 1.990 seconds with the new compute type. The
  unbatched `large-v3` final pass improved from 241.362 to 198.286 seconds while
  preserving 15.36% WER and zero loops.
- **Turbo and beam 5 are deferred until Phase 3.** Turbo beam 5 passed the
  isolated 30-second timing gate in 5.818 seconds, but the real one-second
  rolling schedule fell behind. Turbo beam 2 also emitted a backlog warning in
  a full replay. The shipping live default therefore remains
  `distil-small.en` with beam/best-of capped at 2.
- **CPU batch size 8 was rejected after validation.** It preserved 15.36% WER
  but took 328.846 seconds on the 119-second fixture, 66% slower than the
  unbatched `int8_float32` result. The production final path remains unbatched.
- **Phase 3 complete — 2026-08-26.** The rolling-window merge/stitch heuristics
  have been replaced by LocalAgreement-2. Live decoding now retains only
  uncommitted audio, carries the last 32 committed words as context, commits the
  common word prefix across consecutive hypotheses, and force-commits at a
  700 ms silence boundary or 20-second buffer cap. Committed words are grouped
  on sentence punctuation or 700 ms gaps.
- `TRANSCRIPT_PARTIAL` now carries the uncommitted tail to a dim italic panel
  label. Only committed text is atomically written to the `.txt`; the final save
  path continues to read only that file-backed text area. The obsolete eight
  merge/reconciliation helpers and their two timing constants are deleted.
- The corrected `distil-small.en` full replay retained 211 hypothesis words with
  59.78% WER and zero loops, compared with Phase 1's 44 retained words and
  91.62% WER. This verifies the structural retention improvement but does not
  meet the plan's optimistic 5% WER target.
- Turbo/beam 5 was re-tested after LocalAgreement as required. It produced 322
  words, 21.23% WER, and zero loops, but finished the 119-second stream roughly
  one minute late. The exact 20-second Phase 3 decode path took 14.606 seconds
  with turbo versus 6.612 seconds with `distil-small.en`. Turbo and beam 5 are
  therefore still not shipped on this CPU; the live default remains the faster
  small model with beam/best-of 2.
- All 32 Python helper tests and the Java/Gradle tests pass.
- **Phase 4 complete — 2026-08-26.** The live decoder now receives a separate
  conditioned copy with DC removal, a dependency-free 80 Hz one-pole high-pass,
  and block AGC toward 0.06 RMS with an 8x ceiling. Quiet-room blocks below the
  speech threshold are not amplified. The capture callback still passes the
  original samples directly to the incremental WAV writer.
- `AUDIO_CLIPPING` reports once per capture when at least 0.1% of a raw input
  chunk reaches full scale. Its numeric one-field grammar is enforced in Python
  and Java, shown persistently in the recording panel, and surfaced in the
  microphone-test dialog.
- Conditioning the complete 119-second fixture took 0.0876 seconds and moved RMS
  from 0.011630 to 0.063750. On a matched single-window `distil-small.en`
  diagnostic using the production live filters, unconditioned audio produced 81
  hypothesis words at 85.20% WER; conditioned audio produced 189 words at 63.97%
  WER. This is not a replacement for the Phase 3 streaming score, but confirms
  that the front end improves the same saved signal.
- The fixture SHA-256 remained
  `0c0ea76d90953b7f0deb4db4e5d3cc89bbdbef522b13dc79e6b48293ca8ea186`
  before and after validation. All 39 Python helper tests and Java tests pass.
- **Phase 5 complete — 2026-08-26.** Transcript polling now runs once per
  second, while `TRANSCRIPT_UPDATED` remains the immediate update path. Text is
  not reset when its rendered contents are unchanged, and refreshes follow the
  bottom only when the viewport was already at the tail; a user who scrolls up
  keeps the same selection and scroll position.
- A defensive renderer replaces the body of any transcript line over 80 words
  with `[decode error suppressed]`, preserving its timestamp. The Phase 3
  provisional tail remains a separate dim italic label, and both save/export
  paths were verified to persist only `liveTranscriptTextArea` (committed text).
- The settings dialog now describes live context as the maximum uncommitted
  audio buffer rather than a repeated decode window. Boundary tests cover the
  80/81-word guard and tail-follow behavior. The optional confidence colouring
  remains deliberately unimplemented because no stable word-to-render mapping
  is exposed to the Java panel yet.
- All 39 Python helper tests and the complete Gradle build pass. The six-phase
  live-transcript accuracy plan (Phases 0–5) is complete.
- **Phase 6 complete — 2026-08-27.** The six-button grid and seven-node status
  stack are replaced by one full-width, state-driven primary action, a single
  coloured-dot status line with an elapsed recording timer, and a reflowing
  Settings/More row. Microphone and finalization progress are shown only in the
  states where they are useful; microphone/language details now live in the
  Settings tooltip.
- The transcript/event split defaults to 0.5, moves to 0.75 while recording and
  back to 0.5 for review, persists manual divider changes, and switches from a
  vertical docked layout to a horizontal wide-window layout at 720 px.
- The event text blob is now a virtualized `TableView<EventEntry>` with elapsed,
  type, and details columns. Selecting an event scrolls to and highlights the
  matching timestamped transcript line; clicking a transcript line selects all
  events in that line's time window, falling back to the nearest event.
- Boundary coverage now includes responsive layout decisions, divider limits,
  elapsed-time display, transcript time windows, and nearest-event matching.
  All 25 Java tests, all 39 Python helper tests, and the complete Gradle build
  pass.
- **Phase 7A complete — 2026-08-27.** `PAUSED` is now a first-class workflow
  state. The primary action toggles Pause/Resume without ending the take, Done is
  the only action that starts finalization, and review exposes `Record more` to
  resume the same session. Save and export remain unavailable while paused so a
  live preview cannot be mistaken for the final transcript.
- Live capture launches with `--capture-only` and exits with
  `FINALIZATION_RESULT paused`; it never loads the offline model during Pause.
  Done launches a separate `--finalize-existing` process against the preserved
  WAV without opening the microphone. The original recording origin, resume-gap
  padding, transcript, and event lists remain continuous across any number of
  pause/resume cycles.
- All 27 Java tests and all 41 Python helper tests pass. A no-audio
  `--finalize-existing` smoke test also completed without opening an input
  device. Phase 7B remains optional and is deliberately not implemented until
  real use shows that the roughly one-model-load resume cost or long silence
  padding is material.
- **Phase 9A complete — 2026-08-27.** The final pass now decodes the saved WAV to
  a float array, conditions that in-memory copy with the Phase 4 high-pass and
  AGC path, and sends the array to faster-whisper. The captured WAV remains raw:
  its SHA-256 stayed
  `0c0ea76d90953b7f0deb4db4e5d3cc89bbdbef522b13dc79e6b48293ca8ea186`
  across finalization, with a regression test enforcing the invariant.
- Re-scoring the unchanged Phase 0 fixture after 9A produced **336 hypothesis
  words, 55 errors, and 15.36% WER** with `large-v3`, beam/best-of 8, and final
  VAD enabled—exactly the Phase 0–7 score. The Phase 4 gain seen with the small
  live model therefore does not transfer to this final-pass model and fixture.
- **Phase 9B complete — 2026-08-27.** The identical conditioned final pass was
  re-run with `vad_filter=False`. It produced **337 hypothesis words, 59 errors,
  and 16.48% WER** in 446.52 seconds: 1.12 percentage points worse than 9A.
  VAD is therefore not discarding useful speech on this fixture; final-pass VAD
  remains enabled, and 9C is not required on the evidence from this experiment.
- All 42 Python helper tests and the complete Gradle build pass after Phases 9A
  and 9B.
- **Phase 10A complete — 2026-08-27.** LibriSpeech `test-clean` was downloaded to
  the gitignored `demo-output/librispeech-calibration/` and decoded through the
  production final-pass settings by the new `scripts/calibrate_asr.py`, which
  imports `build_transcribe_kwargs(final_pass=True)` rather than calling
  faster-whisper directly. Over 40 utterances and 897 reference words it scored
  **3.68% WER** (3.57% with numbers normalized) at 0.91x realtime, against a
  published `large-v3` baseline of ~2.5%.
- **The decoder is healthy; the 15.36% is the synthetic fixture.** Three
  independent results now agree: conditioning the final pass (9A) changed the
  score by exactly nothing, disabling VAD (9B) made it 1.12 points worse, and
  clean human speech scores 3.68%. Neither signal level nor voice-activity
  gating explains the fixture score, so the remaining candidates are the
  synthesized voice being out of distribution and the domain-specific scoring.
  **9C is therefore not required**, and Phase 8 must not be justified by the
  15.36% figure.
- Calibration also exposed a real defect: three of the five worst utterances
  ended with memorized video boilerplate appended after the real speech —
  `thank you for watching.`, `we'll be right back.`, `thanks for watching!`.
  These are Whisper training artifacts on trailing silence. They defeat every
  Phase 1 defence: too short for the 12-word structural detector, fluent enough
  to pass the confidence and compression thresholds, and shorter than
  `hallucination_silence_threshold`. Tracked as Phase 10B.
- **Phase 10B complete — 2026-08-27.** Live, final, and calibration paths now
  share a normalized known-phrase filter. It removes only a standalone final
  boilerplate segment after either a measured 0.5-second timestamp gap or a 50%
  audio-level drop when faster-whisper collapses the gap to touching segment
  timestamps. Mid-transcript phrases and final phrases without either signal
  boundary survive.
- Re-running `.venv-whisper/bin/python -m scripts.calibrate_asr --count 40`
  removed all three documented endings and produced **21 errors over 897 words,
  2.34% WER, and 2.23% numbers-normalized WER**. Decode time was 460.4 seconds
  for 343.6 seconds of audio (0.75x realtime). This improves the Phase 10A score
  by 12 errors and 1.34 percentage points, placing it slightly below the
  published ~2.5% `large-v3` reference.
- All 47 Python helper tests and the complete Gradle build pass after Phase 10B.
- **Next:** 10C (rebuild the fixture with a human voice) and 10D (validate the
  fixture scorer). Keep
  `calibrate_asr.py` as a permanent regression gate — above about 5% means a
  change has broken the decoder.

---

## What went wrong

Observed output — note this is a **single transcript line**, so it came from a
single Whisper segment that collapsed into a decode loop:

```
[2026-08-26T14:06:03.781] and system, and system, and system, and system, … ×200
```

Four defences that normally catch this were all absent at once.

### 1. The temperature ladder is switched off — `live_whisper_demo.py:584`

faster-whisper defaults to a six-step temperature fallback
(`[0.0, 0.2, 0.4, 0.6, 0.8, 1.0]`). When a greedy decode degenerates into a
repetition, the compression-ratio check fires and it **re-decodes that window at a
higher temperature**. `build_transcribe_kwargs` passes a scalar `0.0`, which
leaves nothing to fall back to, so the loop is emitted verbatim.

This is the primary cause.

### 2. The repetition filter is disabled on the live path — `live_whisper_demo.py:1572`

`strict_segment_filtering=False` bypasses `looks_like_low_confidence_segment()`
(`:529`), which holds the `compression_ratio > 2.6` check that would have caught
this exact segment. The live path currently has no anti-repetition guard at all.

### 3. A long prompt is re-injected every second — `live_whisper_demo.py:590-595`

`hotwords` and `initial_prompt` are both set. Because live decoding runs with
`condition_on_previous_text=False`, that ~40-token pathology preamble is
re-stuffed into the 224-token prompt window on *every tick*. Over-prompting with a
repetitive terminology list makes the model echo prompt-shaped filler — which is
precisely what this output is.

### 4. Near-silence is being fed to the model — `live_whisper_demo.py:28`, `:50-54`

VAD `threshold: 0.35` (Silero's default is 0.5) lets room tone through, and
`CHUNK_RMS_SILENCE_THRESHOLD = 0.0005` against typical speech RMS of 0.01–0.1
means audio is essentially never classified as silent. Whisper hallucinates
hardest on non-speech. `LOW_ENERGY_SHORT_SEGMENT_MAX_WORDS = 0` (`:33`) disables
the only remaining low-energy defence.

---

## Phase 0 — Setup and baseline (30 min, no risk)

Establish the fixture every later phase is measured against.

- Add version floors to `requirements-whisper.txt`: **`faster-whisper>=1.2`**,
  `ctranslate2>=4.8`. Below 1.1 there is no `hallucination_silence_threshold` or
  `neg_threshold`.
- Pre-cache the turbo model so the first recording doesn't stall on a 1.6 GB
  download: `deepdml/faster-whisper-large-v3-turbo-ct2`, loaded once with
  `device="cpu", compute_type="int8_float32"`.
- **Time it on a 30-second clip.** You need under ~10 s decode for 30 s of audio
  (3× realtime) for the Phase 3 design to keep up. If it is slower, keep the live
  model on `distil-large-v3` and do **Phase 3 before Phase 2** — Phase 3 is what
  creates the headroom.
- Record a two-minute dictation on the current build and keep both the transcript
  and the `*_audio.wav`. That WAV is the regression fixture for every phase below.

---

## Phase 1 — Stop the hallucination loops (~2 h, low risk)

Restores every defence identified above. Self-contained, and it fixes the pasted
output.

### Changes

1. **Rewrite `build_transcribe_kwargs()` (`:555-596`)** to restore the temperature
   ladder and pass the decode thresholds explicitly, plus the repetition controls.
   See the parameter table below.

2. **Drop `initial_prompt` from the live path entirely.** Keep `hotwords` only —
   faster-whisper builds the bias prompt from it. Reserve `initial_prompt` for the
   final pass, and cap the terminology list at ~15 terms.

3. **Flip `strict_segment_filtering` to `True`** at the live call site (`:1572`).

4. **Add a structural loop detector.** Reject any segment whose most frequent
   3-gram covers more than 30% of its tokens, with a 12-word minimum so short
   repeats like "yes yes yes" survive. Run it on **both** the live and final
   paths, independent of the model — this alone would have killed the pasted line.
   Wire it into `transcribe_audio_segments` (`:642`) and
   `transcribe_saved_audio_with_timings` (`:1001`).

5. **Tighten the VAD and RMS gates** so room tone stops reaching the decoder.

6. **Fix the config banner at `:1400`**, which misreports the final-pass prompt
   setting and will mislead you while debugging.

### Parameter table

| Parameter | From | To | Why |
| --- | --- | --- | --- |
| `temperature` | `0.0` | `[0.0, 0.2, 0.4, 0.6, 0.8, 1.0]` | restores fallback re-decode |
| `compression_ratio_threshold` | — | `2.4` | triggers the fallback |
| `log_prob_threshold` | — | `-1.0` | triggers the fallback |
| `no_speech_threshold` | — | `0.6` | triggers the fallback |
| `repetition_penalty` | — | `1.15` live / `1.05` final | |
| `no_repeat_ngram_size` | — | `3` (live only) | hard-blocks `X, X, X` |
| `hallucination_silence_threshold` | — | `2.0` | needs `word_timestamps`, already on |
| `prompt_reset_on_temperature` | — | `0.5` | stops a bad prompt propagating |
| VAD `threshold` | `0.35` | `0.5` | Silero default; 0.35 passes room tone |
| VAD `neg_threshold` | — | `0.35` | |
| VAD `min_speech_duration_ms` | — | `250` | |
| VAD `speech_pad_ms` | `500` | `200` | less non-speech per segment |
| `SEGMENT_COMPRESSION_RATIO_THRESHOLD` | `2.6` | `2.4` | |
| `SEGMENT_AVG_LOGPROB_THRESHOLD` | `-1.2` | `-1.0` | |
| `SEGMENT_NO_SPEECH_THRESHOLD` | `0.75` | `0.6` | |
| `CHUNK_RMS_SILENCE_THRESHOLD` | `0.0005` | `0.003` | speech RMS is 0.01–0.1 |
| `CHUNK_RMS_LOW_ENERGY_THRESHOLD` | `0.0015` | `0.008` | |

All of these are real `faster-whisper` 1.2.1 `transcribe()` parameters — verified
against the installed signature. `VadOptions` accepts exactly: `threshold`,
`neg_threshold`, `min_speech_duration_ms`, `max_speech_duration_s`,
`min_silence_duration_ms`, `speech_pad_ms`.

### Tests to add

- Loop detector rejects 40× `"and system"`.
- Loop detector keeps a normal pathology sentence.
- Loop detector ignores phrases under the 12-word minimum.
- Live kwargs expose a list temperature and no `initial_prompt`.
- Final kwargs keep `initial_prompt` and set `no_repeat_ngram_size = 0`.

**Touches:** `live_whisper_demo.py:28-42`, `:50-54`, `:529-555`, `:642`, `:1001`,
`:1400`, `:1572`; `scripts/tests/test_live_whisper_demo.py`

---

## Phase 2 — Raise the accuracy ceiling (~1 h, low risk)

The live path currently runs **`distil-small.en` at beam 2** —
`choose_live_model_candidates()` (`:497`) picks it first when the final model is
`large-v3`, and `LIVE_MAX_BEAM_SIZE = 2` (`:37`) clamps the configured beam of 8
down to 2. On pathology dictation that is realistically 12–20% WER. That is the
gap to the 95%-accurate transcribers you are comparing against.

The reason it is stuck on a small model is throughput: the current schedule
decodes a 10 s window every 1 s, demanding ~10× realtime. The
`BACKLOG_WARNING_SECONDS` machinery at `:1490` exists to apologise for falling
behind.

### Changes

- **Put `large-v3-turbo` first** in `choose_live_model_candidates()`. Roughly 8×
  faster than `large-v3` at near-identical English WER, so the preview no longer
  has to drop to a distilled small model. Keep `distil-large-v3` and
  `distil-small.en` behind it as fallbacks.
- **Raise `LIVE_MAX_BEAM_SIZE` / `LIVE_MAX_BEST_OF` from 2 to 5** (`:37-38`).
  **Only after Phase 3**, or after confirming the Phase 0 timing.
- **Default compute type to `int8_float32`** (`TimeStamp.java:116`), with an
  `int8` fallback in `load_live_model` (`:1596`) since not every CTranslate2 build
  accepts it. Recovers quantisation loss for near-zero cost on arm64.
- **Wrap the final pass in `BatchedInferencePipeline`** (`:977`) with
  `batch_size=8`. Large speedup on the post-Stop wait. It ignores
  `condition_on_previous_text`, which also removes the main repetition risk from
  finalize. Validate against the Phase 0 fixture before shipping.

**Touches:** `live_whisper_demo.py:37-38`, `:497-522`, `:977`, `:1596`;
`TimeStamp.java:116`

---

## Phase 3 — LocalAgreement streaming (1–2 days, medium risk)

The structural fix, and the one that actually reaches 95%. **It deletes more code
than it adds.**

### What is wrong today

`maybe_transcribe_latest_live_audio` (`:1541`) re-decodes the trailing 10 s from
scratch every tick with no carried context, then ~200 lines of heuristics
reconcile successive decodes: `is_same_live_utterance` (`:283`),
`merge_live_transcript_entries` (`:319`), `trim_transcript_overlap` (`:383`),
`select_monotonic_live_display` (`:428`), `delay_unconfirmed_stable_segments`
(`:770`).

Words at the window boundary lose their context on every single decode, and the
40-word tail matcher cannot dedup text the model never produced before — which is
why the loop passed straight through.

### The replacement

Implement **LocalAgreement-2**, the standard streaming-Whisper policy:

1. Trim the audio buffer to the **last committed word** rather than a fixed 10 s
   window, so each decode covers only uncommitted audio.
2. Carry the last ~32 committed words forward as the decode prompt, so context
   survives the boundary instead of being discarded.
3. **Commit the longest common prefix** between this decode's word sequence and
   the previous decode's; hold the remainder as provisional.
4. Force a hard commit at every VAD silence boundary and at a 20 s buffer cap, so
   a model that never agrees cannot stall the transcript.
5. Write **only committed text** to the `.txt`. Send the provisional tail over a
   new `TRANSCRIPT_PARTIAL` protocol message.

Group committed words into transcript lines on gaps ≥ 700 ms or sentence-final
punctuation — that replaces all of the merge/stitch/trim helpers.

### Protocol change

Register `TRANSCRIPT_PARTIAL` with **1 field** in both:

- `PROTOCOL_FIELDS` — `live_whisper_demo.py:60`
- `TranscriptMessageType` enum — `TimeStamp.java:186`

`test_protocol_grammar_covers_every_message`
(`test_live_whisper_demo.py:22`) asserts exact key-set equality against
`PROTOCOL_FIELDS`, so it will fail until the example is added there too. Also
update `docs/TRANSCRIPT_PROTOCOL.md`.

### Delete once green

`is_same_live_utterance`, `merge_live_transcript_entries`,
`replace_live_transcript_window`, `trim_transcript_overlap`,
`append_stitched_transcript_entries`, `select_monotonic_live_display`,
`update_live_transcript_segments`, `delay_unconfirmed_stable_segments`, and the
`LIVE_STABILITY_LAG_SECONDS` / `LIVE_REFINEMENT_WINDOW_SECONDS` constants.

Keep `preserve_matching_live_timestamps` — the finalize path still uses it, but it
will need a simpler matcher than `is_same_live_utterance`. Normalized equality on
the first five words is sufficient.

**Replaces:** `live_whisper_demo.py:283-445`, `:704-815`, `:1501-1585`
**Touches:** `:60`, `TimeStamp.java:186`, `docs/TRANSCRIPT_PROTOCOL.md`

---

## Phase 4 — Audio front-end (~4 h, low risk)

Raw microphone float32 goes straight to the model today, with no conditioning at
all.

- **Remove DC offset and high-pass at 80 Hz.** Kills HVAC rumble, a major
  hallucination source in a lab.
- **Add slow AGC toward ~0.06 RMS**, capped at 8× gain. Whisper's Mel front-end is
  not level-invariant in practice; quiet dictation measurably degrades
  recognition.
- **Apply this only to the live decoder's audio.** Keep writing unprocessed audio
  to the WAV in `append_wave_audio` (`:1453`) so the final pass and any later
  re-analysis work from the original signal.
- **Add a clipping detector** (≥0.1% of samples at full scale) and a new
  `AUDIO_CLIPPING` protocol message surfaced in the panel status. Clipped audio is
  unrecoverable and users will not notice.

A one-pole high-pass avoids adding `scipy` as a dependency; if `scipy` is
acceptable, `lfilter` will be considerably faster than a Python sample loop.

**Touches:** `live_whisper_demo.py:525` (near `audio_rms`), `:1353` (callback),
`:1453` (keep unprocessed)

---

## Phase 5 — Panel (~4 h, low risk)

Two real bugs and one design gap. The bugs are worth doing right after Phase 1 —
they are an hour and immediately visible.

### Bugs

- **Autoscroll is unconditional** — `TimeStamp.java:1782`.
  `positionCaret(getLength())` runs on every 250 ms refresh, so a user physically
  cannot scroll up to re-read while recording. Only follow the tail when the
  viewport was already at the tail.

- **Full re-read and full `setText` every 250 ms** — `refreshTranscriptContents`
  (`:1811`) with `TRANSCRIPT_REFRESH_INTERVAL_SECONDS = 0.25` (`:105`). At an hour
  of dictation that re-parses and re-lays-out hundreds of KB four times a second
  on the FX thread.

### Changes

- **Add a client-side runaway guard.** Replace any line over ~80 words with a
  `[decode error suppressed]` marker before rendering. Defence in depth — one
  runaway line makes the whole panel unusable regardless of what Python does.
- **Render the provisional tail** from `TRANSCRIPT_PARTIAL` in a dimmed italic
  `Label` below the text area (construct near `:1133`, dispatch near `:2731`).
  This is the change that makes live transcription *feel* accurate: committed text
  stops flickering and in-flight words are visibly marked as provisional. Make
  sure the save path (`:2382`, `:2452`) reads only `liveTranscriptTextArea`, never
  the partial label.
- **Raise the poll interval to 1.0 s** once partial text carries the fast-moving
  updates, and rely on the `TRANSCRIPT_UPDATED` push for immediacy.
- **Optional:** colour low-confidence words using the per-word probabilities
  already captured, so the pathologist can see what to double-check.
- Update the settings dialog copy (`:2314`) — with LocalAgreement the
  "live context seconds" setting becomes a max-buffer bound, not a decode window.

**Touches:** `TimeStamp.java:105`, `:1018-1023`, `:1133`, `:1773-1783`, `:1811`,
`:2314`, `:2731`

---

## Phase 6 — Panel redesign (~1 day, low risk)

Phase 5 fixes what is broken. Phase 6 changes what is *shown*. Do the two parts of
this phase in order — 6A is cosmetic and independent, 6B depends on Phase 3's word
timings.

The panel is built entirely in `createLiveEventMonitorPane()`
(`TimeStamp.java:1011-1156`), and its state is driven by
`updateLiveEventMonitorControls()` (`:1209`).

### The problem

Today the header carries a title row, **six equal-weight buttons in a 3×2
`GridPane`**, and a **seven-node status stack** (four labels, two progress bars,
one settings summary). Below that a vertical `SplitPane` at `0.68` gives the
transcript two thirds and the event log one third.

- At most two of the six buttons are usable at any moment. During recording,
  five are greyed out. The workflow is a straight line already modelled as
  `RecordingWorkflowState`, but that enum only drives enable/disable, never
  layout.
- `recordingStatusLabel` ("Status: Recording") and `transcriptStatusLabel`
  ("Transcript: live transcription running") say the same thing in two stacked
  lines.
- Nothing is responsive. The button grid is a hardcoded 3 columns, and
  `liveEventTextArea.setPrefColumnCount(60)` (`:1015`) forces a wide preferred
  size that fights QuPath's narrow analysis tab.

Net effect: roughly 180 px of chrome above the transcript in a docked tab, most
of it inert.

### Target layout

```
┌────────────────────────────────────┐
│ TimeStamp Recorder           v0.1.0│
├────────────────────────────────────┤
│ ● Recording · 02:14                │  one status line, coloured dot
│ ▌▌▌▌▌▌▌░░░░░░  Mic                 │  meter only while recording
├────────────────────────────────────┤
│ ┌────────────────────────────────┐ │
│ │       ■ Stop Recording         │ │  ONE primary action, full width
│ └────────────────────────────────┘ │
│  ⚙ Settings              ⋯ More    │  secondary, small, text-only
├────────────────────────────────────┤
│ Transcript                         │
│ [14:06:03] The specimen shows…     │
│ [14:06:11] moderately differen…    │
│ hearing the next few words…        │  provisional, dimmed (Phase 5)
│ ════════════════════════════════   │  draggable
│ Events                        12   │
│ 00:04  Zoom    4.2× → 10×          │
│ 00:11  Pan     (1024, 780)         │
└────────────────────────────────────┘
```

### 6A — Simplify the chrome

- **Collapse six buttons to one primary plus two secondary.** A single full-width
  primary button whose text follows the state machine: `Start Recording` →
  `Stop Recording` → *(disabled)* `Creating final transcript…` → `Save Session`.
  Keep `Settings` visible — it is touched before recording. Move `Export
  Transcript` and `Clear Events` into a `⋯` `MenuButton`; neither is on the
  critical path.
- **Hide what is not usable rather than greying it.** Recording and review are
  different jobs: while recording the pathologist is looking at the slide and
  needs only a transcript, a meter, and Stop. Extend
  `updateLiveEventMonitorControls()` to drive `setVisible`/`setManaged`, the way
  it already does for the mic meter (`:1254-1262`), rather than only `setDisable`.
- **Collapse the status stack to one line plus two conditional bars.** Merge
  `recordingStatusLabel` and `transcriptStatusLabel` into a single sentence
  prefixed with a coloured state dot (grey ready / red recording / amber
  finalizing / green saved). Show the mic meter only while recording and the
  finalize bar only while finalizing. Fold `transcriptSettingsLabel`
  (`en · Built-in Microphone`) into the Settings button tooltip.
- Add an elapsed recording timer to the status line. It is the one piece of
  information genuinely missing today.

This should take the header from ~180 px to ~70 px; all of it becomes transcript.

### 6B — Make it dynamic

- **Balance the split.** Default the `SplitPane` to `0.5` as a baseline, then make
  it state-aware: `0.75` while `RECORDING` (you want words), `0.5` on entering
  `UNSAVED_REVIEW` (you want to correlate speech with events). Persist the divider
  position in preferences so a manual drag sticks across sessions.
- **Flip orientation on width.** Bind the `SplitPane` orientation to the pane's
  width: `VERTICAL` when narrow (docked analysis tab), `HORIZONTAL` when wide
  (floating window), so transcript and events sit side by side when there is room.
- **Let the buttons reflow.** Replace the 3-column `GridPane` (`:1100-1113`) with
  a `FlowPane`, and drop `liveEventTextArea.setPrefColumnCount(60)` (`:1015`)
  so the pane stops fighting narrow layouts.

### 6C — Link the two panes *(after Phase 3)*

The highest-value change in this phase, and the reason the accuracy work pays off.

The entire point of this extension is correlating **what the pathologist said**
with **what they were looking at**. Today those are two disconnected text blobs
that can only be aligned by reading timestamps by eye.

- Replace `liveEventTextArea` with a `TableView<EventEntry>` — columns for
  elapsed time, event type, and details.
- **Click an event → the transcript scrolls to that moment and highlights it.**
  **Click a transcript line → the events in that window highlight.**
  `RECORDING_ORIGIN` already gives both sides the same clock; Phase 3's word
  timings make the mapping precise to the word.

A `TableView` is virtualized, so this also fixes the same `setText()` +
`positionCaret()` rebuild-everything bug the transcript has — see
`refreshLiveEventMonitorContents` (`:1200-1201`).

**Touches:** `TimeStamp.java:1011-1156` (layout), `:1209-1270`
(state-driven visibility), `:1015`, `:1100-1113`, `:1143-1145`, `:1200-1201`

---

## Phase 7 — Pause and resume (~1 day, low risk)

Most of this already exists. The work is making it reachable and making it cheap.

### The product decision

**A pause/resume cycle is one continuous recording, not two takes.** The session
stays open across any number of pauses and ends only when the doctor chooses
**Done** or exports. One transcript, one audio timeline, one set of event
timestamps. Do not add per-segment takes.

### What already works

- The Python helper is built for resume. `load_existing_capture_state()` reloads
  prior transcript entries and the recording origin; `align_resumed_wave_audio()`
  measures the gap since the last capture and pads the WAV so the timeline stays
  aligned; `RESUME_GAP_TOLERANCE_SECONDS` sets the tolerance.
- `TranscriptStartMode { NEW_TAKE, RESUME, CANCEL }` exists
  (`TimeStamp.java:254`), and `resolveTranscriptStartMode()` (`:1773`) already
  returns `RESUME` when `transcriptCaptureStarted` is set, or offers a
  resume/new-take/cancel dialog.
- The stop path is already *named* pause: `pauseRecordingSession()` (`:2009`)
  logs the event `"Recording Paused"`.

### Two things break it

**1. Phase 6 removed the only route back into a recording.** The previous UI kept
a separate Start button enabled during review, so pressing it resumed. The single
primary button now maps `UNSAVED_REVIEW → saveTranscriptAndTimestamps()`
(`:1217`), and `startRecordingSession()` is reachable only from `READY` and
`SAVED` — and from `SAVED` it calls `resetWorkingSessionForNewRecording()`, which
discards the take. This is a regression, not an original limitation.

**2. Pause runs a full finalization.** `pauseRecordingSession()` sets
`FINALIZING` and kills the helper, which runs its complete offline pass on exit —
**198 seconds** on the Phase 0 fixture — plus a model reload on resume. That is
why pause behaves like a hard stop.

### Prior art

Voice Memos, Otter, Rev, Dragon, Zoom, Teams and OBS all use the same shape:
**three states, two controls.** The primary control toggles Record ⇄ Pause and is
cheap and reversible; a separate terminal control (Done / Stop / Finish) is the
only thing that triggers processing. Zoom and OBS both produce a *single
continuous file* across pauses. Pause never triggers processing anywhere.

### Target workflow

Add `PAUSED` to `RecordingWorkflowState` (`:260`) and split the controls.

```
● Recording · 02:14
▌▌▌▌▌▌░░░░  Mic
┌──────────────────────────┐
│        ⏸  Pause          │   primary: Record ⇄ Pause, cheap
└──────────────────────────┘
  ⏹ Done    ⚙ Settings   ⋯      Done is the only terminal action
```

| State | Primary button | Also shown |
| --- | --- | --- |
| `READY` | ● Start Recording | — |
| `RECORDING` | ⏸ Pause | ⏹ Done |
| `PAUSED` | ● Resume | ⏹ Done · *"Paused · 02:14 recorded"* |
| `FINALIZING` | *(disabled)* Creating final transcript… | — |
| `UNSAVED_REVIEW` | Save Session | **↺ Record more** |
| `SAVED` | ● Start New Recording | — |

`Record more` in review is what repairs the lost path: it routes to
`startRecordingSession()` in `RESUME` mode instead of resetting the session.
Export and Save require Done first and remain unavailable while the session is
paused.

### 7A — Make pause cheap (first, ~3 h)

Keep the process-restart mechanism, but **stop finalizing on pause**. Live capture
runs with `--capture-only`, which exits with `FINALIZATION_RESULT paused` and
skips the offline pass. A real Done launches `--finalize-existing` against the
preserved WAV without opening a microphone. No new protocol message type is
needed, and the existing WAV-resume logic is untouched.

### 7B — Make pause instant (optional, ~1 day)

A true in-process pause: read `PAUSE` / `RESUME` commands on the helper's stdin,
and on pause stop appending to the WAV and the decode buffer while keeping the
process and the loaded model alive. Resume becomes instantaneous.

This also removes a wart: `align_resumed_wave_audio()` pads the gap with **real
silence**, so a ten-minute break writes ten minutes of silence into the WAV that
the final pass must then decode. With a true pause, stop writing instead and keep
a list of (audio-offset → wall-time) anchors so event timestamps stay correct.

Do 7A first. Only do 7B if long pauses turn out to be common in practice.

**Touches:** `TimeStamp.java:254-269` (states), `:1213-1220` (primary action),
`:1674-1727` (start/resume), `:2009-2020` (pause); `live_whisper_demo.py`
finalization block and argument parsing

---

## Phase 8 — Engine and signal (~1 week)

Phases 0–7 tuned the existing pipeline as far as it goes. This phase replaces two
of its foundations: the audio going in, and the engine decoding it.

### Why this phase exists

The final pass currently decodes 119 seconds of audio in 198 seconds — **0.6×
realtime** — at **15.36 % WER**. Published benchmarks put freely available
alternatives far ahead on both axes:

| Model | English WER | Speed | Notes |
| --- | --- | --- | --- |
| Canary-Qwen 2.5B | 5.63 % | slow | tops the Open ASR leaderboard |
| Parakeet TDT 0.6B v3 | 6.34 % | RTFx 3,332 | English + 25 EU languages only |
| Whisper large-v3-turbo | ~7–8 % | fast | "best balance" among free models in clinical testing |
| **This project, final** | **15.36 %** | **RTFx 0.6** | `large-v3`, CPU, `int8_float32` |
| **This project, live** | **59.78 %** | realtime | `distil-small.en`, beam 2 |

Whisper is no longer the accuracy leader; it is now the *multilingual* choice.

For a target: Whisper with customization has reached **~1.5 % WER on
neurosurgical dictation** — quiet room, structured dictation, close microphone.

> **Corrected 2026-08-27.** This section previously concluded "the 15.36 % is an
> audio problem, not a model problem." Phase 10 calibration disproved that. The
> production final path scores **3.68 % on LibriSpeech test-clean**, close to the
> published `large-v3` baseline of ~2.5 %. The decoder is healthy; the 15.36 %
> comes from the synthetic fixture. Treat the model-comparison table above as
> still valid — a better model is still a better model — but **do not justify
> Phase 8 by the 15.36 % figure**.

2### 8A — Measure and gate signal-to-noise ratio (first, ~4 h)

A study of ASR in noisy emergency-medical settings found accuracy **stable at
SNR ≥ 8 dB and degrading sharply at −2 dB**, identifying **3 dB as the critical
tipping point** for clinical transcription quality. A headset microphone at 5 cm
typically delivers 20–30 dB; a laptop microphone across a desk delivers 5–15 dB;
audio reaching the microphone via a loudspeaker is worse than either.

Replace the RMS level bar with a **real SNR estimate**: track the noise floor
during VAD-silent stretches and the speech level during VAD-active stretches, and
display the difference in dB.

- Show it as `Signal 14 dB — good` / `Signal 4 dB — below the 8 dB recommended
  for clinical dictation`.
- Warn hard below 3 dB, and consider refusing to start there.
- The meter plumbing already exists (`AUDIO_LEVEL`, `transcriptAudioLevelBar`);
  this turns it from decoration into a gate.

This is the highest-value change in the plan and the cheapest to build.

### 8B — Re-record the fixture and re-baseline (~2 h)

Every number measured so far — Phase 0 through Phase 7 — is scored against a
fixture captured on far-field audio. Re-record the standardized pathology
dictation with a headset or lapel microphone, confirm SNR ≥ 8 dB with the new
meter, and re-run the Phase 0 through Phase 3 measurements.

**Do this before any engine work.** If the offline pass drops from 15.36 % toward
single digits on clean audio, the conclusions about model choice change.

### 8C — Replace the live engine (~3 d)

CTranslate2 is CPU-only, so the GPU has been idle for this entire project.
Parakeet.cpp reports **96× faster than CPU inference** with native Apple Silicon
Metal support — roughly **27 ms to encode 10 s of audio** on the 110M model — and
the streaming variant supports **configurable latency from 80 ms to 1,120 ms**.
That is a different regime from the current one-second rolling window.

- Introduce a `LiveTranscriber` interface with two implementations: `parakeet-mlx`
  (Metal, default) and the existing faster-whisper path (CPU, fallback).
- `parakeet-mlx` exposes a real streaming API, so much of the Phase 3
  LocalAgreement machinery becomes optional rather than load-bearing. Keep it for
  the Whisper path.
- **Keep Whisper for multilingual work.** Parakeet v3 covers English plus 25
  European languages; Whisper covers 99. If Vietnamese or other non-EU dictation
  is ever in scope, the Whisper path must remain reachable, selected by the
  existing language setting.

### 8D — Adopt Medical WER as the reported metric (~4 h)

The clinical study's explicit recommendation: **domain-specific metrics capture
safety-critical errors better than standard WER.** Plain WER charges equally for
dropping "the" and for turning "negative margin" into "positive margin"; only one
of those can harm a patient.

Score the fixture on whether clinical concepts survive — margins, grades,
laterality, measurements, specimen sites — and report that number alongside
overall WER. It changes what the project optimizes for.

### 8E — Domain vocabulary and constrained correction (later)

- **Vocabulary.** Systematic medical-vocabulary construction from authoritative
  sources (the United-MedASR approach, built from ICD-10 and similar) plus
  fine-tuning delivered **37–47 % WER reduction** across Whisper sizes. The
  current 15-term hotword cap is a thin version of this. Building a real pathology
  term list is the cheap half; fine-tuning is the expensive half.
- **LLM post-correction.** GPT-4 paired with Whisper produced the **lowest
  Medical Concept WER** in published testing. The safe boundary is **correction
  against context, not completion of missing words**: fixing "adeno carcinoma" to
  "adenocarcinoma" is legitimate; inventing a clause that was never spoken is not.
  Final pass only, presented to the doctor as a diff they approve, never applied
  silently and never to live text.

### Expected outcome

8A and 8B together should move the offline pass substantially toward the ~1.5 %
demonstrated for close-microphone clinical dictation. 8C is what makes the live
preview trustworthy, because it removes the CPU throughput ceiling that has
forced every live-model compromise so far.

### Sources

- Open ASR Leaderboard — <https://huggingface.co/blog/open-asr-leaderboard>
- `nvidia/parakeet-tdt-0.6b-v3` — <https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3>
- Speech-to-text robustness in noisy emergency medical dialogues —
  <https://pmc.ncbi.nlm.nih.gov/articles/PMC12628192/>
- United-MedASR — <https://arxiv.org/html/2412.00055v1>
- Improving Medical Transcription ASR Accuracy with LLMs —
  <https://arxiv.org/pdf/2402.07658>
- `parakeet-mlx` — <https://github.com/EliFuzz/parakeet-mlx>

**Touches:** `live_whisper_demo.py` (SNR estimation in the audio callback, new
`LiveTranscriber` abstraction); `TimeStamp.java` (meter → SNR display and gate);
`requirements-whisper.txt`; the fixture at `demo-output/live-accuracy-phase-0/`

---

## Phase 9 — Signal path corrections (~1 day)

Defects in how audio reaches each decoder. **Every item here is testable against
the existing fixture and needs no new hardware** — do this phase before spending
money or time on Phase 8.

> **Scope corrected 2026-08-27.** This phase originally claimed these defects
> "explain why the final-pass WER stayed at exactly 15.36 %." Phase 10
> calibration disproved that: the same final path scores **3.68 % on LibriSpeech
> test-clean**. The 15.36 % is the synthetic fixture, not these defects.
>
> The items below remain worth doing — the fixture is genuinely quiet
> (RMS 0.011630) and real dictation may be too, so gates that discard quiet
> speech are still a real risk. But they are **corrections, not the explanation**,
> and their expected gain is smaller than originally stated. Run Phase 10 first.

### 9A — Condition the final pass (first; largest expected gain)

`transcribe_saved_audio_with_timings` (`:1001`) passes a **file path** to the
model:

```python
segments, info = model.transcribe(str(audio_path), ...)
```

faster-whisper therefore loads the raw WAV. `condition_live_audio()` (`:599`) is
called from exactly one place — `:756`, the live path. **The final pass, which
produces the saved deliverable, is the only path that never benefits from
conditioning.**

Phase 4 measured the effect of conditioning on this same saved signal:

| | Words | WER |
| --- | --- | --- |
| Unconditioned | 81 | 85.20 % |
| Conditioned | 189 | **63.97 %** |

**Change:** read the WAV into an array, apply `condition_live_audio()`, and pass
the array to `model.transcribe` instead of the path.

**This does not violate the Phase 4 invariant.** That rule — "the captured WAV
must never pass through here" — protects the **file on disk**, which stays
untouched. Conditioning an in-memory decode copy is exactly what the live path
already does. Add a test asserting the WAV's SHA-256 is unchanged across a
finalization run, so the invariant stays enforced rather than assumed.

### 9B — Test the final pass with VAD disabled (cheap; run before 9C)

Phase 1 raised the VAD threshold from 0.35 to 0.5 to stop hallucination on
near-silence. Correct for the loop bug — but the final pass runs that VAD on
**raw, quiet, unconditioned** audio. On a low-level far-field recording, Silero
at 0.5 classifies real speech as non-speech and it never reaches the decoder.

Run the fixture through the final pass with `vad_filter=False` and compare WER.

- If WER improves, VAD is discarding speech and 9C is required.
- If it does not change, VAD is ruled out for the cost of one run.

Record the result in this Progress log either way.

### 9C — Make the level gates adaptive

Two gates currently use a hardcoded absolute threshold, and both are applied to
raw audio.

**Gate 1 — before conditioning** (`:753-756`):

```python
if chunk_rms < CHUNK_RMS_SILENCE_THRESHOLD:   # 0.003, measured on RAW audio
    return []
conditioned_audio = condition_live_audio(audio)   # applies up to 8x gain
```

Audio below 0.003 raw is discarded *before* the gain that would have lifted it to
roughly 0.024. The fixture's raw RMS is **0.011630** — under 4x the gate — so
quiet syllables and sentence endings sit directly on the threshold. Whisper
already drops trailing words; this compounds it.

**Gate 2 — inside the AGC** (`:583`): blocks below the same 0.003 are skipped and
never amplified. The intent is right (do not amplify room noise), but the
constant was calibrated for close-microphone audio.

**Changes:**

1. Move gate 1 to *after* conditioning.
2. Replace both absolute constants with a threshold derived from a **measured
   noise floor**: sample the level during VAD-silent stretches early in the
   session and set the gate relative to it. This is the same measurement Phase 8A
   needs for its SNR display, so build it once and use it in both places.

### 9D — Spend the offline budget that is already being spent

The final pass has no realtime constraint and already takes 198 s. It is
currently under-using that budget.

- **Raise the final-pass beam from 8 to 16.** Offline time is cheap.
- **Use a more accurate final model.** `large-v3` is no longer the leader;
  Canary-Qwen 2.5B benchmarks at 5.63 % against its ~7–8 %. Strictly better on
  the audio that already exists. Parakeet TDT is both faster and more accurate,
  with the language caveat in Phase 8C.
- **Extend the hotword list** with operator and institution names. The observed
  `Hal` / `Juan` substitutions for a spoken name are exactly what biasing fixes.
  Note the 15-term cap from Phase 1 may need raising to fit both terminology and
  proper nouns.

### Order

9A → 9B → 9C → 9D. 9A has the largest predicted effect and the existing Phase 4
measurement to support it; 9B is one run and decides whether 9C matters.

Re-score the fixture after each step and record it in Progress, so the
contribution of each change is separable.

**Touches:** `live_whisper_demo.py:570-601` (conditioning and AGC gate),
`:753-756` (pre-conditioning gate), `:990-1010` (final pass input),
`:27` (`CHUNK_RMS_SILENCE_THRESHOLD`); `scripts/tests/test_live_whisper_demo.py`

---

## Phase 10 — Calibration, trailing hallucinations, and a real fixture

**Do this first.** It is already half done, and it invalidates premises that
Phases 8 and 9 were written on.

### 10A — LibriSpeech calibration (complete — 2026-08-27)

The project fixture is **synthesized** speech (`reference.aiff`, macOS `say`)
routed through a `Dubbing Virtual Device` loopback. It never passed through a
speaker, a room, or a microphone. A poor score on it therefore could not
distinguish a pipeline defect from an unrepresentative signal.

`scripts/calibrate_asr.py` resolves that by decoding LibriSpeech `test-clean` —
human speech with exact references — through the **production** final-pass
settings, importing `build_transcribe_kwargs(final_pass=True)` rather than
calling faster-whisper directly.

Result over 40 utterances, 897 reference words:

| Metric | Value |
| --- | ---: |
| WER | **3.68 %** |
| WER, numbers normalized | 3.57 % |
| Published `large-v3` baseline | ~2.5 % |
| Decode speed | 0.91x realtime |

**The decoder is healthy.** The remaining gap to 2.5 % is explained by 10B. The
15.36 % on the project fixture is the fixture.

Keep this as a permanent regression gate: **if `calibrate_asr.py` ever exceeds
about 5 %, a change has broken the decoder.** Run it after any decoding change.

Corpus lives at `demo-output/librispeech-calibration/` (gitignored). Re-download
with `curl -L -O https://www.openslr.org/resources/12/test-clean.tar.gz`.

### 10B — Filter trailing hallucinations (real defect, found by 10A)

Three of the five worst calibration utterances ended with memorized video
boilerplate appended after the real speech:

```
HYP: ...doubting of the rest   thank you for watching.
HYP: ...the great sorceress    we'll be right back.
HYP: ...part of the royalists  thanks for watching!
```

These are Whisper training-data artifacts emitted on trailing silence. **They
defeat every Phase 1 defence:**

- Too short for the structural 3-gram detector, which requires 12+ words.
- Fluent and high-confidence, so `avg_logprob`, `no_speech_prob` and
  compression-ratio checks all pass them.
- `hallucination_silence_threshold=2.0` does not fire, because the trailing
  silence is shorter than the threshold.

The same failure appeared in live manual testing as stray trailing fragments.

**Change:** add a known-phrase filter applied to both transcript paths. Drop a
segment when it matches a normalized phrase from the list **and** is the final
segment **and** is preceded by a silence gap. Seed the list with the documented
Whisper set — "thank you for watching", "thanks for watching", "we'll be right
back", "please subscribe", "subtitles by", "amara.org" and similar.

Match on the normalized form so punctuation and casing do not matter. Require the
positional conditions, so the filter cannot delete a doctor genuinely saying
"thank you" mid-dictation. Add tests for both the positive case and that
mid-transcript occurrences survive.

Re-run 10A afterwards; WER should move toward the published baseline.

### 10C — Rebuild the fixture with a human voice

Every accuracy decision so far was made against synthesized audio at RMS
0.011630. Replace it:

- Read the existing `reference.txt` aloud into a real microphone, so the
  reference text and its 358-word ground truth are preserved exactly.
- Verify the level is reasonable before keeping the take.
- Keep the synthetic fixture as `reference_synthetic.*` for comparison — it is
  still useful for isolating voice effects from pipeline effects.
- **Re-baseline Phases 1 through 4 against the new fixture.** The live 59.78 %,
  the Phase 4 conditioning gain, and the turbo throughput comparison were all
  measured on the synthetic signal.

### 10D — Validate the fixture scorer

Numbers-normalized WER differed from raw by only 0.11 points on LibriSpeech — but
LibriSpeech contains almost no numbers, whereas pathology dictation is dense with
`Ki-67`, `HER2`, grades, and measurements.

Diff `reference.txt` against `baseline_transcript.txt` and classify the 55 errors:
genuine mishearings, versus tokenization artifacts where `Ki-67` becomes
`ki 67`. If a material share are artifacts, the scorer needs domain-aware
normalization before it can be trusted to rank future changes.

### Order

10A is done. Then 10B (real defect, cheap), 10C (unblocks everything else), 10D
(makes the resulting numbers trustworthy). **Only then** revisit Phases 8 and 9
with corrected premises.

**Touches:** `scripts/calibrate_asr.py` (exists); `live_whisper_demo.py`
(hallucination phrase list and segment filter);
`scripts/tests/test_live_whisper_demo.py`;
`demo-output/live-accuracy-phase-0/`

---

## Order and expected payoff

Accuracy figures are for pathology dictation in a normal room, measured against
the Phase 0 fixture.

| Phase | Effort | Gets you to |
| --- | --- | --- |
| 0 — Setup and baseline | 30 min | a fixture to measure against |
| 1 — Anti-hallucination | 2 h | ~85–88%, loops gone |
| 2 — Turbo + `int8_float32` | 1 h | ~90–92% |
| 5 — Panel bugs only | 1 h | immediately visible |
| 3 — LocalAgreement | 1–2 d | ~94–96%, −200 lines |
| 4 — Audio front-end | 4 h | +1–2% in a noisy room |
| 5 — Panel polish | 3 h | perceived stability |
| 6A/6B — Panel redesign | 6 h | 6 buttons → 1, ~110 px back to the transcript |
| 6C — Event ↔ transcript linking | 4 h | turns two logs into a review tool |
| 7A — Cheap pause | 3 h | pause costs ~4 s instead of ~200 s |
| 7B — Instant pause | 1 d | optional; removes the reload and the silence padding |
| 8A — SNR meter and gate | 4 h | makes bad audio visible before it ruins a session |
| 8B — Re-record and re-baseline | 2 h | every number so far was scored on far-field audio |
| 8C — Parakeet on Metal | 3 d | removes the CPU ceiling forcing every live compromise |
| 8D — Medical WER metric | 4 h | scores the errors that matter clinically |
| 9A — Condition the final pass | 2 h | the deliverable finally gets Phase 4's gain |
| 9B — VAD-off experiment | 30 min | one run; decides whether 9C is needed |
| 9C — Adaptive level gates | 4 h | stops quiet speech being discarded pre-gain |
| 9D — Spend the offline budget | 3 h | beam 16, better final model, wider hotwords |

| 10A — LibriSpeech calibration | done | **3.68 % — the decoder is healthy** |
| 10B — Trailing hallucination filter | 3 h | removes memorized video boilerplate |
| 10C — Rebuild fixture with a human voice | 2 h | unblocks every other measurement |
| 10D — Validate the fixture scorer | 2 h | makes future comparisons trustworthy |

**Do Phase 10 before Phases 8 and 9.** 10A is complete and it disproved the
premise both of those phases were written on: the final path scores 3.68 % on
human speech, so the 15.36 % is the synthetic fixture, not the decoder. Phase 9
remains worth doing as a set of genuine corrections, but its expected gain is
smaller than originally stated, and Phase 8 must not be justified by the 15.36 %.

Phases 6A and 6B are independent of the accuracy work and can be done at any
point. **6C depends on Phase 3** for word timings. **Phase 7A is a regression
fix** — Phase 6 removed the only UI route back into an existing recording, so
resume is currently unreachable even though the machinery for it exists.

---

## Two things to watch

**Do not raise the beam before Phase 3.** Beam 5 on a re-decoded 10-second window
every second is not achievable on CPU. Either land Phase 3 first, or hold
`LIVE_MAX_BEAM_SIZE` at 2 until it is in.

**The finalize fallback is too blunt.** `has_suspicious_transcript_repetition`
(`:1097`) discards the *entire* final transcript when it trips (`:1708`), falling
back to the lower-quality live text. After Phase 1 that should be rare — downgrade
it to dropping only the offending segments, or good finalize passes will keep
being lost to one bad segment.

---

## Superseded: "deliberately out of scope"

This section previously deferred the Metal backend on the assumption that Phases
1–4 would reach the accuracy target without it. **That assumption did not
survive contact with the measurements.**

Phase 3 turbo testing showed the accuracy is reachable (21.23 % WER) but not
within the time budget on CPU — 14.606 s versus 6.612 s for the small model on
the same decode path. CTranslate2 is CPU-only and both model loads hardcode
`device="cpu"` (`:1598`, `:1668`), so the GPU has been idle throughout.

The Metal backend is therefore **no longer out of scope — it is Phase 8C**, and
it is on the critical path for a trustworthy live preview.
