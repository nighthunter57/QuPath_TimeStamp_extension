# Transcript reliability and word review

Reliability changes implemented September 5, 2026; recorder hardening updated September 22, 2026.

Latest validation and the mixed before/after synthetic replay results are in
[Recorder hardening](RECORDER_HARDENING.md#september-22-implementation-update).

## Recording and review

- Raw microphone chunks are saved by a dedicated writer before they are made
  available to transcription. Slow inference does not block that writer. Pause
  closes the input stream and drains the writer; live decoding continues separately.
  Disk failures are reported as failures, not successful recordings.
- A capped Whisper decode consumes only its decoded prefix. Undecoded backlog
  remains available, including during the final drain on Pause or Done. Resume
  retains the original recording clock and the existing silence-gap behavior.
- Committed words with estimated confidence below 0.6 are amber and underlined
  in the caption view. This threshold is a review heuristic, not a calibrated
  accuracy guarantee. Provisional words retain their dim, italic appearance.
- An uncertain segment with measurable signal and without the existing
  non-speech/repetition rejection signals becomes `[unclear speech - review]`.
  Its audio timing is retained; guessed text is not presented as confirmed speech.
  Silence, structural loops, and known trailing hallucinations remain filtered.
- After **Finish & review**, use **Next uncertain** or click a word in the
  highlighted transcript. Use **Replay** to hear its surrounding context (at
  most six seconds), then apply a correction or **Mark checked**.
  Playback is disabled during recording, pause, and finalization.
- Review stays in one highlighted view. A selected word opens a small correction
  field; **Edit transcript…** opens a full-text editor for larger changes or notes.
  Editing removes model confidence from affected words while preserving offsets
  and audio timing for untouched words. Marking checked clears the review flag,
  not the original estimated confidence.
- Each new live caption automatically scrolls to the latest words while recording.
  Paused and stopped review allows free scrolling. Timestamps show elapsed recording time, or local
  time when the recording origin is unavailable; hovering shows the full original
  timestamp. Exported text and timing data keep their original precision.
- Recent recorded actions are always visible. **View all actions · N** shows the
  complete event list. Transcript/event selection remains linked.
- **Undo/Redo** restores text and review decisions. Automatic source-bound
  checkpoints recover reviewed text and remaining word metadata after a crash.
  See [Recorder hardening](RECORDER_HARDENING.md) for scope and retention details.
- **Record more** saves an earlier review revision. When the final draft changes,
  **Compare earlier review** shows both versions and lets you carry corrections
  into the transcript to keep. Complete that comparison before saving. Earlier
  corrections are retained on disk rather than silently replaced by a new draft.
- Save Session copies the reviewed text and machine timing/confidence artifacts.
  Select **Include audio recording** to include the WAV in the saved session;
  audio remains excluded from that package by default.

## Saved confidence data

Final transcript line timestamps come from the full-audio final pass, matching
the final segment and word timing data. Similar wording in the live preview does
not replace those timestamps. The separate `_live.txt` backup retains the preview
as it was before finalization. Existing saved recordings are not rewritten.

The word CSV adds `confidence` (0–1, empty when unavailable) and `needs_review`.
The adjacent `_review.json` has version 1 and contains:

- `transcript`: the exact machine text, including line endings;
- `words`: ordered records with `word`, UTF-16 `start`/`end` text offsets,
  recording-relative `start_ms`/`end_ms`, nullable `confidence`, and `needs_review`.

The JSON is atomically replaced and loaded only against matching machine text.
It is copied with the session and listed as `wordReview` in the manifest. Like
the `_timed.txt` file, it describes the machine transcript, not subsequent manual
edits. The main transcript remains exactly the text the user reviewed and saved.
An additional `_reviewed.json` snapshot uses the same schema against the reviewed
text and preserves remaining word metadata and checked decisions at save time.
It is listed as `reviewedWords` in the manifest. Corrected words have no inferred
confidence or timing in that snapshot. Machine metadata remains unchanged.
Older recordings without metadata remain readable; confidence is unavailable.
The experimental Parakeet adapter does not supply word confidence.

No new stdout messages were required. Confidence metadata is refreshed alongside
the existing transcript file notification/polling path.

## Validation and limits

- 80 Python tests pass, including an actual helper lifecycle driven by a mocked
  microphone with an 18-second backlog: it decodes 12 seconds, then the remaining
  six, and saves all 18 seconds of raw audio before reporting paused.
- A simulated decoder taking 15 seconds per update retains all 48 test words
  from 24 seconds of speech. The replay advances microphone arrival by measured
  decode time and conditions silent blocks as production does.
- 34 Java tests pass, including compact timestamp/source-offset preservation and
  reviewed metadata serialization. A separate JavaFX smoke check rendered panel
  states, checked event expansion, word selection/correction/checking, and reading
  position preservation while new text arrives. Physical microphone
  capture and audible playback were not exercised in this run.
- The unchanged `calibrate_asr --count 40` benchmark produced 21 errors across
  897 reference words: 2.34% WER, 2.23% with numbers normalized, in 1013.5 seconds
  for 343.6 seconds of audio. That benchmark checks model settings and trailing
  filtering; it does not exercise every production capture/review branch.
- The corrected replay of the 119-second synthetic pathology fixture produced
  324 words, 24.02% raw WER, 10.71% domain-normalized WER, and 22.86% medical
  concept error rate. Decode time was 124.9 seconds; simulated completion was
  126.4 seconds. Confirmed-word display delay was 8.83 seconds at p50 and 13.70
  seconds at p95. Maximum buffered speech was 13.5 seconds. Capture time across
  a turn reached 15 seconds even though each decoded speech window was capped
  at 12 seconds. Earlier replay scores ignored decode-induced microphone backlog
  and used a different conditioning path, so they are not matched comparisons.

Confidence highlights cannot identify every recognition error. These changes do
not guarantee verbatim speech or speaker identification. Actual live accuracy
still needs recordings of the intended speakers and microphones with manually
checked reference transcripts; no live model or beam defaults were changed.
