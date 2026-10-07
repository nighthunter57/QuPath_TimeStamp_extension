export type ReleaseNotice = {
  version: string;
  date: string;
  status: "Preview" | "Stable";
  title: string;
  summary: string;
  improvements: string[];
  fixes: string[];
};

export const releaseNotices: ReleaseNotice[] = [
  {
    version: "0.2.0-preview.20261005",
    date: "October 5, 2026",
    status: "Preview",
    title: "Pilot-study recording and a safer Finish & review",
    summary:
      "A preview for the diagnostic-process pilot: it records the cursor path by default, hands off to the final transcript reliably, and keeps every transcript line on the recording clock.",
    improvements: [
      "Record the mouse path over the image by default for the pilot study; it can still be turned off in Preferences.",
      "Finish & review saves the audio first, then builds the final transcript in the background, and saved sessions can be reopened.",
      "Warn while recording if the microphone stops sending sound.",
      "The installer's microphone test now gives a plain answer, such as OK, no sound, or too quiet.",
      "Transcript times include the time zone offset, so they read the same on any computer.",
      "The printable install guide opens with the pilot study and a short routine to follow for each case."
    ],
    fixes: [
      "Fixed Finish & review and final transcript saving on Windows.",
      "Fixed the final transcript failing on new installs.",
      "Speech after a pause is no longer stamped earlier than when it was spoken.",
      "The final transcript no longer drops or repeats words where the speaker paused for breath.",
      "Microphone dropouts are saved as silence, so later speech stays aligned with image events."
    ]
  },
  {
    version: "0.1.0-SNAPSHOT",
    date: "September 15, 2026",
    status: "Preview",
    title: "Clearer recording controls and installation guide",
    summary: "A supervised-evaluation preview with separate Pause/Resume and Done controls, review recovery, and computer-specific setup instructions.",
    improvements: [
      "Pause and resume the same recording without reloading the speech model.",
      "Keep recent image actions visible alongside the transcript.",
      "Correct and review text with Undo/Redo and recovery checkpoints.",
      "Follow separate Windows, Mac, and Linux installation guides, then make a short test recording."
    ],
    fixes: [
      "Added saved-artifact checksums and installer backups.",
      "Replaced outdated folder-selection and stop instructions with the current workflow."
    ]
  },
  {
    version: "0.1.0",
    date: "August 24, 2026",
    status: "Preview",
    title: "Doctor-ready recording and local transcription setup",
    summary:
      "This preview packages TimeStamp with one-time Windows, macOS, and Linux setup for its private recorder runtime and local speech-to-text models.",
    improvements: [
      "Live transcript output keeps stable phrases visible while the current phrase is still being revised.",
      "The final transcript pass uses the complete recording to recover words that may be missed during live processing.",
      "The recording overlay now uses one compact line with a small status indicator and HH:mm:ss time.",
      "Recent click and annotation messages clear after four seconds while complete timestamps remain in exported logs.",
      "Added a cross-platform doctor package that installs a private recorder runtime and preloads the live and final Whisper models."
    ],
    fixes: [
      "Corrected QuPath extension-directory detection and duplicate JAR loading.",
      "Improved transcript Python executable discovery and preference handling.",
      "Added one-command development deployment and release catalog automation.",
      "Validated long-form pathology and radiology speech with timestamp-delay checks."
    ]
  }
];

export const latestRelease = releaseNotices[0];
