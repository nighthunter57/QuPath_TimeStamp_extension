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
