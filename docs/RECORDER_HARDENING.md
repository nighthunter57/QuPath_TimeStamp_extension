# Recorder hardening — September 14, 2026

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
- Full session reopen UI, automatic retention controls,
  organization-approved storage encryption, remaining large-class decomposition,
  and a standalone always-visible toolbar/keyboard workflow are subsequent work.
- Speaker attribution remains a separate, unimplemented feature.
