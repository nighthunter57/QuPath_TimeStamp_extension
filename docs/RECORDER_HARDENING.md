# Recorder hardening — September 14, 2026


## September 25 recovery and handoff update

> **October 1 (later):** superseded by `0.2.0-preview.20261001.3`. Tests with
> recorded human speech (LibriSpeech, three speakers, played into a loopback
> microphone through the full panel workflow) found and fixed: a stalled input
> device showing "Recording" with no warning (AUDIO_SILENT after 3 s without
> audio blocks); the final pass publishing its own prompt ("Pathology
> dictation.") over near-silence; and a regression in the clip-based final pass
> where a sentence split at an 80 ms gap was decoded as a repeat of the previous
> sentence, losing words (speech regions under 2 s apart are now decoded
> together; word-for-word repeats are flagged for review). The installer's
> microphone test now prints one plain sentence. Final verification: 0.0% word
> errors on both human speakers and 2.2% on the synthetic clinical dictation,
> every clinical phrase correct, paused speech excluded, events aligned.

> **October 1:** superseded by `0.2.0-preview.20261001`. An end-to-end run of
> the 20260926 package (fresh install, real models, loopback audio, Start →
> Pause → Resume → Finish & review → Save with audio → reopen) found two
> release blockers, both fixed: (1) fresh installs resolved PyAV 19, which
> broke every final pass ("unexpected keyword argument 'metadata_errors'");
> PyAV is now pinned to 18.1.0 and installer validation decodes a real WAV;
> (2) faster-whisper's VAD stitching stamped speech after a pause up to the
> pause length too early (15 s in the test); the final pass now decodes VAD
> speech regions through clip_timestamps on the recording clock. Do not hand
> out the 20260925 or 20260926 packages.

> **September 26:** superseded by `0.2.0-preview.20260926`, which fixes a
> Windows-only failure: the helper synced the saved WAV and the final
> transcript generation through read-only handles, which Windows rejects
> ("Bad file descriptor"), so Finish & review and final-transcript
> publication failed on Windows. Remote Windows CI found it. Do not hand out
> the 20260925 package.

The confirmed September 24 data-integrity defects are fixed in
`0.2.0-preview.20260925`. This is a supervised-evaluation package, not an
unconditional clinical-use approval.

- Capture reconciles incoming chunks against the original recording clock,
  including in-session dropouts. Missing intervals become silence in the WAV;
  original received samples remain unprocessed. The two-second-dropout regression
  now retains the expected 28-second timeline. Backward-clock discontinuities
  stop capture without deleting saved audio. A periodic low-disk check stops
  recording with a visible failure while retaining available files.
- Quality incidents persist in a checksummed export companion and remain visible
  after saving/reopening. Explicit microphone selection fails if unavailable
  rather than silently switching inputs. The default-input label does not yet
  identify the physical device automatically.
- Invalid WAVs are preserved. Known-format stale headers are repaired through a
  separate file with an immutable original backup; odd payloads are refused.
  Legacy RAW migration keeps its original. Audio is synchronized before the
  capture-saved acknowledgement. This does not establish full power-loss safety.
- Final transcript text, segment/word timings and review metadata are staged as
  one hashed generation. Interrupted publication retains a rollback record and
  validated backups; retry restores the prior generation before decoding. Save
  omits incompatible timing companions while retaining text/audio and warnings.
- Repeated phrases at distinct known times are retained. Only duplicate entries
  for the same known interval are removed by the final repetition pass; existing
  structural hallucination filtering remains. An unfiltered model-hypothesis
  artifact is not implemented, and no new accuracy percentage is claimed.
- **Cancel final pass** terminates inference and confirms exit before allowing
  save/retry. **More** offers retry from recorded audio, seekable audio review,
  selection of unsaved recordings and support information without recording text.
  Retry preserves previous corrections for comparison. Cross-time-zone sessions
  remain review-only for retry/append; replay and saving are available.
- Recovery restores the persisted audio clock if the event checkpoint is missing.
  Missing/conflicting clocks block further capture. Recovered sessions can be
  saved with a warning and reopened. Pending publication no longer hides text or
  exposes mismatched word confidence/timings.
- Setup prompts for the existing QuPath user folder, rejects Windows ARM64, and
  explains the Windows x64 Visual C++ runtime prerequisite. Both cached standard
  models load and decode silence during normal setup. The installed snapshot
  revisions/package versions are recorded; the managed recorder loads those
  snapshots offline. Dependencies are not yet locked transitively.
- Package verification checks every bundled source file, all checksums, exact
  built JAR bytes and embedded helper. Draft release creation now depends on the
  recorder and installer jobs. Main and feedback guides link this version and
  QuPath 0.6.0. Existing GitHub Pages hosting is preserved; nothing was published.

Validation: **118 Python tests**, **49 Java tests**, Java 21 build/package,
macOS/Linux shell syntax, isolated JavaFX controls/review/recovery/cancellation/
scrolling/background save/reopen checks, both cached standard model loads with
silence inference, website static export and **8 guide tests** all pass locally.
The GUI harness uses a fake microphone/process; it does not operate active QuPath.

Handoff package: `build/distributions/TimeStamp-Doctor-0.2.0-preview.20260925.zip`.
SHA-256: `7fbc362c55b01746033285536386a71bc1937582c207b69fa8f07ce184218290`.
Identical copies are in the website and feedback download folders.

Before routine use, complete physical microphone interruption and representative
long-session checks on the doctor's actual OS/QuPath 0.6 installation, exercise
Start → Pause/Resume → Finish & review → replay/correct → Save with audio → reopen,
and evaluate human pathology speech with checked references. Fresh workstation
installation/model downloads and remote Windows/Linux CI have not been executed
in this pass. No live-latency improvement or clinical validation is claimed.

## September 23 daily-use workflow update

- **Finish & review** uses a new FINISH/CAPTURE_SAVED handshake. The microphone
  controller closes capture, joins the raw-audio writer, and checks writer errors
  independently of model loading/decoding. Only after that acknowledgement does
  QuPath terminate obsolete preview work, confirm process exit, and launch the
  final pass. No acknowledgement means no successful handoff. Final inference
  itself is unchanged; standalone STOP still drains its live backlog.
- **Save Session** freezes the text, word-review metadata, serialized event data,
  recording origin, and outcome on the FX thread. File copying, writing, and
  checksum verification run in a worker. Recording/editing/switching/closing are
  guarded while it runs; the interface stays responsive. Success is published
  after verification. Save-before-close and save-before-new-recording continue
  only after success. Event serialization itself still occurs on the FX thread.
- **More → Open saved session…** selects the folder created by Save Session.
  Reopening verifies a completed schema-2 manifest with checksums, validates
  event counts and paths, and creates a separate working review copy. It restores
  the reviewed text, checked-word metadata, original machine transcript, image
  identity, events, and included audio. Modified, incomplete, or unchecked legacy
  exports are rejected rather than silently trusted. Original exports are not
  changed by opening. Interrupted imports are excluded from automatic recovery.
- Exports without audio support text review and saving; replay and Record more
  are unavailable. Record more also requires the original clock and matching
  time zone. New manifests retain the transcript time zone for elapsed display
  and event linking on other computers; old exports without it retain the local
  time-zone assumption. Cross-time-zone sessions support review/replay, but not
  appending speech into mixed local timestamps.
- Session switching stops audio replay and clears prior provisional text,
  selections, and review history. Working-copy recovery retains its saved zone
  and restrictions. Include audio at save time to replay words after reopening.

Verification: **104 Python tests and 43 Java tests pass**, plus the Gradle build
and isolated JavaFX harness. Tests block model loading/inference during FINISH,
reject saved acknowledgement on persistence failure, distinguish safe termination
from timeout, freeze storage while checking FX responsiveness, and exercise
save/open round trips, checksums, image identity, missing audio, corruption,
incomplete manifests, escaping paths, and time-zone interpretation.

These fixes do not establish physical-device or clinical acceptance. The live
model/settings and measured live-caption latency are unchanged. The next real
workstation check is a short non-identifying recording through Pause/Resume,
Finish & review, playback/correction, saving with audio, and reopening it. Human
pathology accuracy, long-session performance, and interruption testing remain
acceptance work, not claims made by the automated suite.

## September 22 implementation update

- Save/export rejects the working folder and overlapping parent/child locations,
  including symlink aliases. Managed-file copy/delete also rejects hard links to
  the source. Excluding audio cannot delete the original through those aliases.
- **Record more** preserves corrections and checked-word decisions in an atomic
  revision before capture starts. Appended live text retains the reviewed prefix.
  A regenerated final draft requires **Compare earlier review** before saving or
  another Record more cycle. The comparison lets the user carry corrections into
  the new draft; it does not guess how changed wording should be reconciled.
  Immutable revisions stay in the working folder's `video/review-history`;
  the latest `_previous_review.json` is copied into exports and checksummed.
- **Finish & review** replaces Done. The panel shows slide/microphone context,
  readable event summaries, and theme-aware transcript colors. Raw event details
  remain available in tooltips and exports. Automatic live following remains on.
- Each new event captures image ID/name/source at event time, including slide
  switches. IDs derive from project URI + entry ID, or image/server source when
  outside a project. Relocating a source may change its ID. Older events have no
  invented image identity. JSON, CSV, and recovery preserve the new fields.
- Finalization reports progress while consuming decoded segments, retaining only
  the final two segments for the existing trailing-hallucination rule. It still
  cannot report model work before the first segment is yielded.
- The live queue stops at observed silence/hard-cap boundaries before consuming
  later speech. Completed short utterances bypass the normal size/cadence gates.
  Raw WAV persistence, clock mapping, model defaults, and final decoding settings
  are unchanged.

Verification: **101 Python tests, 40 Java tests, Gradle build, and isolated JavaFX
smoke checks pass.** The harness covers 320/420/900-pixel layouts, light/dark CSS,
automatic live scrolling, recovery, and review preservation across Record more.
It does not operate the user's microphone or paused QuPath session.

One before/after replay of the same 119-second synthetic fixture gave:

| Measure | Before | After |
| --- | ---: | ---: |
| Confirmed-word delay p50 | 9.40 s | 8.76 s |
| Confirmed-word delay p95 | 15.28 s | 14.23 s |
| Maximum turn duration | 15.5 s | 12.0 s |
| Raw WER | 20.67% | 21.51% |
| Domain-normalized WER | 11.01% | 8.93% |
| Missing expected phrases | 9 / 35 | 7 / 35 |

These are mixed, synthetic results from a replay whose scheduling uses measured
decode duration, not a repeatable human accuracy verdict. Expected-phrase recall
does not detect all contradictory additions. No engine upgrade is justified by
this run. Source review still identifies asynchronous saving, bounded preview
shutdown, capture-callback I/O, and complete saved-session reopening as follow-up
work; they are not claimed fixed here.

## Implemented

- Resident microphone controller accepts PAUSE/RESUME/STOP over stdin. Pause
  acknowledges stream closure and saved queued audio without waiting for inference.
  Resume keeps the loaded model. Done drains capture before the separate final pass.
  The original recording clock and existing silence-gap WAV format are retained.
- Decoder backlog is spooled as exact float samples to a temporary disk file;
  the in-memory decode buffer drains at most one maximum turn at a time. The
  callback-to-writer queue has a finite limit and reports failure rather than
  silently claiming complete capture if storage cannot keep up.
- Equal-height Pause/Resume and Done controls; recent actions always visible;
  View all expands the action list. Confidence details are confined to review.
  Ctrl+Alt+R starts or toggles Pause/Resume when focus is inside the panel;
  Ctrl+Alt+D selects Done. These are panel-local, not global hardware shortcuts.
  Engine selection is behind Advanced; microphone and language stay prominent.
- Source-bound review checkpoints include corrected text and checked decisions.
  Undo/Redo restores both text and confidence metadata (100 undo steps, in memory).
  Original machine artifacts remain separate. Recovery data is checkpointed every
  two seconds; an abrupt crash may lose changes since the last checkpoint.
- New working sessions use `QuPath user folder/timestamp/recordings` rather than
  system temporary storage. Legacy temporary sessions remain discoverable.
  POSIX working-root permissions are owner-only. Windows relies on user-folder ACLs.
  Start refuses storage below 128 MB; this is not a promise of full-session capacity.
- Routine transcript payloads and detailed action content are no longer copied
  into normal log messages. Paths and exception diagnostics may still be logged.
- All three installers preserve prior extension JARs for rollback. CI definitions
  cover recorder tests and clean runtime install/upgrade on Linux, macOS and Windows,
  skipping model downloads and accepting unavailable audio hardware.
- Saved manifests carry SHA-256 checksums for their declared artifacts. Save
  re-reads and verifies these before reporting success. The read-only
  `qupath.ext.timestamp.SessionIntegrity` CLI accepts a manifest path to verify
  an exported session later. Old manifests without checksums are not claimed to
  be verified. Checksums detect corruption, not maliciously rewritten manifests.
- Python environment/cache files were removed from Git tracking, not from disk.

## Local verification

Verified September 15, 2026: 88 Python tests and 36 Java tests passed, along with
the JavaFX harness and the website production build. The packaged macOS installer
passed an isolated clean runtime install and upgrade; the previous JAR was backed
up. Model downloads and actual microphone capture were disabled for that check.
Package checksums verified successfully. The existing 40-utterance general-speech
benchmark stayed at 21 errors / 897 words (2.34% WER; number-normalized 2.23%).
That benchmark does not establish clinical or live-caption accuracy.

Run `.venv-whisper/bin/python -m unittest discover -s scripts/tests` and
`./gradlew build` with Java 21.

`scripts/qa/RecorderUiSmoke.java` is a standalone JavaFX harness. Compile against
`build/classes/java/main` and QuPath's `Contents/app/*` JARs, then launch
`qupath.ext.timestamp.RecorderUiSmoke` with a disposable output directory. It
uses isolated preferences, never opens the microphone, renders 320/420/900-pixel
layouts, and checks controls, edits, Undo/Redo, and actual review recovery.

The production-helper integration test deliberately blocks inference, then pauses
and resumes capture. It checks one model load, 36 retained test words, one origin,
and one 26-second WAV (18 seconds input plus the original-format eight-second gap).
This is mocked microphone input, not a physical-device acceptance result.

## Privacy and retention

Excluding audio from Save Session does not delete the working recording. Saved
and discarded markers stop recovery prompts; they do not erase data. No automatic
retention expiry or application-level encryption is enabled. Follow the local
organization's storage policy, protect the workstation and backups, and avoid
identifying details in filenames. Close QuPath before deliberately removing an
identified working-session folder, and verify any saved copy first.

## Human evaluation — requires real recordings

Use consented, non-identifying human speech and manually checked references.
Keep evaluation files outside Git (for example under ignored `demo-output`).
Create a version 1 JSON manifest with this structure:

```json
{"version":1,"recordings":[{
  "id":"speaker-01-room-a",
  "source":"human",
  "consent_confirmed":true,
  "reference_checked":true,
  "audio":"sample.wav",
  "reference":"reference.txt",
  "hypothesis":"final_transcript.txt",
  "concepts":"concepts.json"
}]}
```

Paths are relative to the manifest. `concepts` is optional and uses the same
`[{"id":"...","text":"..."}]` schema as `scripts/fixtures/pathology_concepts.json`.
Run `.venv-whisper/bin/python -m scripts.evaluate_cohort path/to/manifest.json`.
The report aggregates word-weighted errors and omits raw transcript text. Consent
and human-source fields are operator attestations, not independently verified.
Absent concept references produce an unavailable metric, not perfect accuracy.

## Remaining acceptance work — not claimed complete

- Physical microphone disconnect/reconnect, repeated pause/resume, and long-session
  CPU/memory/disk tests on each supported OS and representative workstations.
- Execute the new remote CI jobs; adding a workflow is not evidence it passed.
  The installer job does not test model download, QuPath GUI launch, or real audio.
- Scored human pathology corpus, negations/numbers/units, display latency, correction
  time, and confidence calibration. No model/beam settings were changed.
- Automatic retention controls,
  organization-approved storage encryption, remaining large-class decomposition,
  and a standalone always-visible toolbar/keyboard workflow are subsequent work.
- Speaker attribution remains a separate, unimplemented feature.
