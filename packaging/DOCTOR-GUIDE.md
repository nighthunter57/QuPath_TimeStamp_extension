# TimeStamp installation and use

Doctor guide | QuPath 0.6.0 | Updated 26 September 2026

TimeStamp records spoken observations and timestamped image actions in QuPath. Follow this guide to install, record, review and save. If setup is already complete, start on page 2.

**Current use:** preview for supervised evaluation. Review every transcript against the recording before relying on it. Routine clinical use and accuracy have not been validated.

## Install once before the first session

Ask the study coordinator or IT team to finish setup ahead of your appointment. You already have QuPath; keep that installation if it is **version 0.6.0**. If it is another version, ask the coordinator to confirm compatibility before proceeding.

**1  Obtain the correct package.** Ask your coordinator for **TimeStamp-Doctor-0.2.0-preview.20261001.zip**. The installer adds TimeStamp and its recording tools automatically; you do not need to install Python separately.

**2  Prepare the computer.** Connect a microphone and internet. First setup downloads about **3.6 GB of speech models**, plus recording tools. Allow time and storage for these files; download speed determines how long setup takes.

**3  Check your QuPath folder.** In QuPath Preferences, note the **User directory**. Save any QuPath work and close QuPath completely. Keep this folder path for the installer prompt.

**4  Extract the ZIP and run your installer.** Open the extracted TimeStamp-Doctor folder. Use only the instructions for your computer below.

- **Windows 10 or 11, x64:** right-click the ZIP and choose **Extract All**. Double-click **Install TimeStamp on Windows.bat**. Windows ARM64 is not supported by this package.

- **Mac, Apple Silicon or Intel:** double-click the ZIP to extract it, then double-click **Install TimeStamp.command**. A Terminal window opens automatically.

- **Linux, x64 or ARM64:** ask IT to open a terminal in the extracted folder and run the command below. Setup may need administrator permission for microphone support.

bash "Install TimeStamp on Linux.sh"

**5  Select the QuPath folder.** When asked, press Enter only if the displayed folder matches your QuPath User directory. Otherwise paste the exact folder path you noted.

**6  Wait for setup to finish.** Keep the window open through all four stages. Speak during its microphone test if prompted. Wait for **TimeStamp is ready for the doctor**, then reopen QuPath. Complete the short test on page 2 even if setup reported success.

If your computer blocks setup, ask IT to approve it. On Windows, a missing DLL may require the Microsoft Visual C++ 2015-2022 x64 runtime. Do not change organizational security settings to make setup run.

# Record review and save

The normal sequence is **Start Recording > Pause or Resume > Finish & review > Save Session**. Timestamps are recorded automatically once recording starts.

## Make a short test before the first session

Use a non-identifying test slide and a few test sentences. Follow all steps below, including saving with audio and reopening. Confirm you can hear the recording and see the recorded image actions. Ask the coordinator for help if any step fails.

**1  Open the recorder.** In QuPath, choose **Extensions > TimeStamp Extension > Open Clinical Session Recorder**. Open the slide you want to observe.

**2  Check the microphone.** Open **Settings**, select the microphone and choose **Test microphone**. Allow microphone access if requested. Confirm that speaking changes the signal indicator. Keep the supplied transcription settings for the first test.

**3  Start recording.** Choose **Start Recording** and wait for **Recording** before speaking. Observe the slide normally. TimeStamp records supported image actions, such as zooms, clicks and annotation changes, with timestamps. Live text follows automatically; it may lag behind your speech and is a preview.

**4  Pause when needed.** Choose **Pause** for a break and **Resume** to continue the same session. Pause does not create the final transcript or save a session folder. Finish the session before changing the microphone or transcription settings.

**5  Finish and review.** Choose **Finish & review**. Capture stops and the app prepares a transcript from the full recording. Keep QuPath open while it processes. Longer recordings can take longer to finish.

**6  Check and correct the text.** Use **Next uncertain**, **Replay** and **Edit transcript...** as needed. Use **More > Review recording audio...** to listen to any interval, including speech missing from the text. Check names, numbers, units, negations and omissions. Confidence highlights do not guarantee correctness. Use **Mark checked** only after verifying the selected word.

**7  Save the session.** Choose **Save Session**, select an approved destination and enter a non-identifying session name. Select **Include audio recording** if you need playback or to add speech later; this option is off by default. Wait for the saved confirmation before closing QuPath.

## Reopen or add more observations

Choose **More > Open saved session...** and select the folder created by Save Session. Keep the whole folder together. TimeStamp verifies it and opens a working copy for review. After making changes, choose Save Session again.

**Record more** is available when the saved audio and original timing information allow it. If you corrected earlier text, use **Compare earlier review** to reconcile those corrections with the new transcript before saving. Sessions from another time zone support review and playback but may not allow more recording.

# Get help and protect recordings

## If something goes wrong

**Setup is taking a long time**
The speech models are the largest download. Keep setup open. If a download fails, rerun the same installer; completed model files are reused. If it fails again, give the error message to your coordinator or IT team.

**TimeStamp is missing from the Extensions menu**
Close and reopen QuPath. Confirm version 0.6.0 and the User directory in Preferences. If the installer used a different folder, rerun it and enter the correct folder. Ask the coordinator if TimeStamp still does not appear.

**The microphone is silent or cannot be opened**
Check its connection, operating-system microphone permission and the input selected in Settings. Run Test microphone again. Setup can finish even when its microphone test fails. Confirm the input works before recording.

**The final transcript is taking too long**
Choose **Cancel final pass · keep saved audio** and wait until cancellation finishes. You can save the available transcript and include its audio. Later, choose **More > Retry final transcription** to process the saved audio without recording again. A cancelled pass has not produced a completed final transcript.

**A recording quality warning appears**
Replay the affected recording and inspect the transcript for missing or incorrect speech. A warning can indicate an audio gap, clipping or a clock problem. Missing speech cannot be recovered from an interval that was not captured. The warning stays with the saved session; ask the coordinator whether the session should be repeated.

**QuPath closed before I saved**
Reopen the recorder and use its recovery prompt or **More > Recover an unsaved session...**. Review the available text and audio, then Save Session. Recovery cannot guarantee that everything immediately before the interruption was captured.

## Storage and privacy

After setup, standard recording and transcription run locally using the installed models. Use your organization’s approved storage and recording procedures. Avoid identifying information in test speech, session names and support messages.

**Save Session creates an export; it does not erase the working recording.** Working audio remains in the QuPath User directory under **timestamp/recordings**, even if audio is excluded from the export. Choosing not to save on exit can suppress recovery prompts without deleting that audio. Automatic deletion and application-level encryption are not configured. Ask your coordinator or IT team to manage retention and removal.

## What to provide when asking for help

Use **More > Copy support information** for the app version and status. Give your coordinator that information, what you were doing and the exact error message. Do not include patient speech, images or transcript contents. Do not delete a working recording while troubleshooting.

Guide for TimeStamp 0.2.0-preview.20261001. Local checks cover automated recording logic, recovery, saving and interface behavior. The doctor’s actual microphone, longer sessions and human pathology accuracy still require evaluation.
