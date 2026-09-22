# TimeStamp QuPath Extension

TimeStamp is a QuPath extension for recording timestamped image events together
with a live microphone transcript. It is intended for recorded QuPath sessions:
the Java extension logs user events, and the Python Whisper helper captures
microphone audio, shows live text, preserves working audio for finalization, and
regenerates a final transcript from the full recording when capture stops.

## Requirements

- QuPath 0.6.0
- Microphone access for QuPath and the private TimeStamp recorder

Doctors should use the cross-platform doctor package from `build/distributions`. It
installs the extension, a private managed Python runtime, the recorder
libraries, and the default live/final Whisper models. Doctors do not install
Python or enter a Python executable path themselves. Internet access is needed
once while the installer downloads the runtime and models; recording and
transcription are local afterward.

Build the doctor package with:

```bash
./scripts/build_doctor_package.sh 0.1.0
```

Send the resulting `TimeStamp-Doctor-0.1.0.zip`, not the standalone JAR, to a
new doctor workstation. It includes installers for Windows 10/11 (x64 and
ARM64), macOS (Apple Silicon and Intel), and Linux (x64 and ARM64). On a
brand-new workstation, open QuPath once and complete its initial user-folder
setup before running the TimeStamp installer. QuPath must be closed while the
installer runs. Linux may request administrator access to install PortAudio if
the system does not already provide it.

### Developer environment

Developers building from source also need a Java 21 JDK and Python 3 with a
local `.venv-whisper` environment.

Create the Whisper environment from the repo root:

```bash
python3 -m venv .venv-whisper
source .venv-whisper/bin/activate
python -m pip install --upgrade pip
python -m pip install -r requirements-whisper.txt
```

For a source/development installation, open QuPath Preferences and set
`TimeStamp > Transcript Python executable` to the full path of the Python
executable inside this environment. For example:

```text
/path/to/qupath-extension-template/.venv-whisper/bin/python
```

The extension JAR includes the transcription helper script. During development,
the repository `.venv-whisper` is detected automatically. A doctor package
runtime is detected first at `~/QuPath/v0.6/timestamp/runtime/.venv`, so the
doctor does not need to configure this preference.

## Build

Use Java 21 when building:

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
./gradlew build
```

The extension JAR is written to `build/libs`.

## Development Auto-Reload

QuPath loads Java extensions when its JVM starts, so a running production
instance cannot safely replace the TimeStamp classes in place. For development,
use the watcher instead:

```bash
./scripts/dev_watch_qupath.sh
```

The watcher builds the project, launches a separate QuPath instance directly
from `build/classes`, and watches the Java, Python, shell, test, and Gradle source
files. After a change, it runs the tests and restarts only that development
instance. It does not reinstall the extension JAR.

The watcher uses an isolated temporary home and preferences directory. It must
not be used for clinical work or unsaved QuPath annotations. If TimeStamp is
recording, finalizing, or has an unsaved recording, the automatic restart waits
until the recording is saved or discarded.

The defaults match the macOS development machine used for this project. Override
them when necessary:

```bash
TIMESTAMP_QUPATH_APP_DIR="/Applications/QuPath.app" \
TIMESTAMP_JAVA_HOME="/path/to/jdk-21" \
./scripts/dev_watch_qupath.sh
```

Press `Ctrl+C` in the watcher terminal to close its development QuPath instance
and stop watching.

## Install a Development Build

Build and copy the latest JAR into the local QuPath extensions directory:

```bash
./gradlew deployToQuPath
```

The default destination is `~/QuPath/v0.6/extensions`. Override it when QuPath
uses another user directory:

```bash
./gradlew deployToQuPath -PqupathUserDir="/path/to/QuPath/v0.6"
```

The task replaces older directly installed `TimeStamp-*.jar` files so QuPath
does not load duplicate versions. Restart QuPath after deployment because Java
extensions are loaded at application startup.

## Release and Automatic Updates

The repository includes `catalog.json`, which follows QuPath's extension catalog
format. To publish a release:

1. Open GitHub Actions and run `Build draft extension release`.
2. Enter a semantic version such as `0.1.0`.
3. Review and publish the generated draft GitHub release.
4. The `Update QuPath extension catalog` workflow adds the published release to
   `catalog.json`.

Users add this catalog in
`Extensions > Manage extensions > Manage extension catalogs`:

```text
https://github.com/nighthunter57/QuPath_TimeStamp_extension
```

After the first published release is present in `catalog.json`, QuPath's
Extension Manager can detect newer catalog releases and install them. QuPath
must still be restarted after an extension update.

The private doctor runtime and downloaded models are stored outside the
extension JAR. Extension Manager updates therefore preserve transcription
support and do not download the multi-gigabyte models again.

## Demo Session

The helper script can still prepare a folder for command-line demos:

```bash
./scripts/prepare_demo.sh caseA ./demo-output
```

Start live transcription directly:

```bash
./scripts/start_live_transcript.sh ./demo-output/<session_id> large-v3 en
```

For the normal QuPath workflow, no session folder is needed before recording:

1. Install the built extension JAR in QuPath.
2. Open `Extensions > TimeStamp Extension > Open Clinical Session Recorder`.
3. Click `Start Recording`; the extension creates a private, durable working
   session automatically.
4. Use `Pause` and `Resume` as needed; they keep one continuous take and do not
   run the slow final transcript pass or reload the model. Wait for the
   acknowledged Paused/Recording state before speaking again. Done is separate.
5. Click `Done` once to finish the take, then wait until the monitor says the
   transcript is ready. Do not close QuPath while
   the status says `Finalizing transcript`.
6. Review or edit the transcript. Choose `Record more` to resume the same take,
   or click `Save Session` when it is complete.
7. In the Save dialog, choose the parent folder, enter a session name, and decide
   whether to include the raw audio. Clicking Save creates the named session
   folder and writes the transcript and all timestamp data together.

Nothing is copied to the user's chosen location when recording stops. Save is
always an explicit user action. Raw audio stays in the private working
working session and is excluded from the saved package by default because it may
contain sensitive speech.

Working sessions live under the QuPath user folder at `timestamp/recordings`.
Older temporary recordings are still discovered for recovery. Text corrections
and checked-word decisions are checkpointed every two seconds during review;
Undo/Redo is available while the panel remains open. The original machine text
and timings are preserved separately. Actions remain visible throughout recording.

See [Recorder hardening and validation](docs/RECORDER_HARDENING.md) for the tested
scope, privacy/retention behavior, real-human evaluation procedure, and remaining
acceptance work. Do not interpret confidence highlighting as verified accuracy.

After Save, the transcript is written to:

```text
<parent_folder>/<session_name>/video/<session_name>_transcript.txt
```

Transcript timing companions are saved next to it:

- `<session_name>_transcript_timed.txt`
- `<session_name>_transcript_segments.csv`
- `<session_name>_transcript_words.csv`
- `<session_name>_transcript_live.txt`

The main transcript is the user-reviewed text. The `_timed.txt` file preserves
the machine transcript to which the segment and word timing rows refer, so text
corrections made during review do not silently invalidate the timing evidence.
The timing CSVs contain UTC timestamps and elapsed milliseconds from the
recording start. If `Include raw audio files` is selected, these additional files
are saved next to the transcript:

- `<session_name>_transcript_audio.raw`
- `<session_name>_transcript_audio.wav`
- `<session_name>_transcript_audio.start.txt`

When the user chooses a folder from `Save Session`, the extension
saves the displayed transcript and automatically writes all matching timestamp
artifacts:

- `events/<session_name>_event.csv`
- `events/<session_name>_event.json`
- `cursor/<session_name>_cursor.json`
- `<session_name>_recording_manifest.json`

Every event has a stable sequence number, local display time, UTC instant, and
elapsed milliseconds. The save operation writes each file atomically, reads the
critical files back to verify their contents, and writes the completed manifest
last. The UI only changes to `Saved` after these checks succeed.

The manifest is written last. Its `workflowState` is `complete` only when the
transcript helper exits successfully and the final transcript exists;
`complete_with_warning` identifies a preserved live-transcript fallback or a
recording with no audio, and `incomplete` identifies an interrupted or failed
finalization. `Record more` before Save resumes the current take. Starting again
after a successful Save creates a new temporary recording session.

While a take is unsaved, closing QuPath offers Save, Discard, or Cancel. A small
recovery checkpoint is also written during recording; if QuPath or the computer
stops unexpectedly, the next launch offers to recover the latest unsaved
transcript and event log for review and saving.

## Transcript Behavior

The live transcript is optimized for immediate feedback and may revise recent
lines. The final transcript is regenerated from the full saved WAV after capture
stops, so the final text is the source of truth.

If live decoding falls behind, the script preserves the full raw audio and skips
stale live decode windows instead of trying to transcribe every old chunk. This
keeps the UI closer to the current speaker while still protecting the final
offline transcript.
