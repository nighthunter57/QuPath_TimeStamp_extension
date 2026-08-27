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

Phases 6A and 6B are independent of the accuracy work and can be done at any
point. **6C depends on Phase 3** for word timings.

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

## Deliberately out of scope

Swapping the live path to a Metal backend (`mlx-whisper` with
`mlx-community/whisper-large-v3-turbo`, or `parakeet-mlx` with
`parakeet-tdt-0.6b-v3`). CTranslate2 is CPU-only, so the GPU sits idle and both
model loads hardcode `device="cpu"` (`:1598`, `:1668`). It is a real option and
would be a step change, but it is a separate two-to-three-day project, and Phases
1–4 should reach the target without it.
